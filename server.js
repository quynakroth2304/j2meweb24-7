// server.js
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const express = require('express');
const cookieParser = require('cookie-parser');
const jwt = require('jsonwebtoken');
const multer = require('multer');
const AdmZip = require('adm-zip');
const WebSocket = require('ws');
const helmet = require('helmet');
const compression = require('compression');
const rateLimit = require('express-rate-limit');

const config = require('./config');
const VMManager = require('./vmManager');
const UserStore = require('./src/userStore');
const { CommerceStore, BANK_CONFIG } = require('./src/commerceStore');
const rawDualSessionMod = require('./src/shared/dualSessionStore');
const DualSessionStore = typeof rawDualSessionMod === 'function' ? rawDualSessionMod : rawDualSessionMod.DualSessionStore;
const SESSION_GRACE_PERIOD_MS = rawDualSessionMod.SESSION_GRACE_PERIOD_MS || (30 * 60 * 1000);
const { createRbacMiddlewares } = require('./src/middleware/rbacGuard');
const { patchJarToVipAdmin } = require('./unlock-vip');
const { NetworkRelayGateway, createHttpNetProxyHandler } = require('./networkRelay');

process.on('uncaughtException', (err) => {
  console.error('[FATAL] uncaughtException:', err && err.stack ? err.stack : err);
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  console.error('[ERROR] unhandledRejection:', reason && reason.stack ? reason.stack : reason);
});

if (process.env.RENDER) {
  console.warn(
    '[CẢNH BÁO VẬN HÀNH - P1-7] Phát hiện môi trường Render. Lưu ý: Gói Render Free sử dụng hệ thống tệp tạm (ephemeral) ' +
    'và tự ngủ sau 15 phút không có HTTP request. Để treo 24/7 không mất dữ liệu save RMS, bắt buộc dùng gói trả phí có Persistent Disk hoặc VPS.'
  );
}

const app = express();
app.set('trust proxy', 1); // Hoạt động chuẩn xác qua Cloudflare Tunnel / Reverse Proxy
const server = http.createServer(app);

const userStore = new UserStore(config.DATA_DIR);
const commerceStore = new CommerceStore(config.DATA_DIR);
const dualSessionStore = new DualSessionStore(config.DATA_DIR);
const {
  extractTokenFromReq,
  verifyUserFromRequest,
  requireAuth,
  requireAdmin,
  guardAdminPageRoute
} = createRbacMiddlewares({ userStore, dualSessionStore });
const vmManager = new VMManager({ rootDir: __dirname, dataDir: config.DATA_DIR });
const networkRelay = new NetworkRelayGateway({
  userStore,
  jwtVerify: (token) => jwt.verify(token, config.JWT_SECRET)
});
const CLOUD_VM_MODE = process.env.CLOUD_VM_MODE === '1';

let maxConcurrentSessions = config.DEFAULT_MAX_CONCURRENT_SESSIONS || 50;

// ==================== HÀM ĐIỀU KHIỂN BẬT/TẮT HÌNH ẢNH (TỐI ƯU RAM) ====================
function setVMRenderState(vm, enabled) {
  if (!vm) return false;
  const isEnabled = Boolean(enabled);
  vm.renderEnabled = isEnabled;
  if (typeof vmManager.setRenderMode === 'function') {
    return vmManager.setRenderMode(vm.userId, vm.id, isEnabled);
  }
  if (!isEnabled) {
    vm.lastFrame = null;
    vmManager.sendCommand(vm.userId, vm.id, 'RENDER:0');
    vm.addLog('Đã TẮT hiển thị hình ảnh (Chế độ tối ưu RAM/CPU).');
  } else {
    vmManager.sendCommand(vm.userId, vm.id, 'RENDER:1');
    vmManager.sendCommand(vm.userId, vm.id, 'SYNC');
    vm.addLog('Đã BẬT hiển thị hình ảnh.');
  }
  vm.emit('render_state', isEnabled);
  return true;
}

// ==================== QUẢN LÝ DESIRED RUNNING VMS BỀN VỮNG (P0-4) ====================
const DESIRED_VMS_FILE = path.join(config.DATA_DIR, 'desired_vms.json');
const desiredRunningVMs = new Map();

function loadDesiredVMs() {
  if (!fs.existsSync(DESIRED_VMS_FILE)) return;
  try {
    const items = JSON.parse(fs.readFileSync(DESIRED_VMS_FILE, 'utf8'));
    if (Array.isArray(items)) {
      for (const item of items) {
        if (item && Number.isInteger(item.userId) && Number.isInteger(item.vmId)) {
          desiredRunningVMs.set(`${item.userId}_${item.vmId}`, item);
        }
      }
    }
  } catch (err) {
    console.error('[DesiredVMs] Lỗi đọc desired_vms.json:', err.message);
  }
}

function saveDesiredVMsAtomic() {
  try {
    const tmp = `${DESIRED_VMS_FILE}.tmp.${process.pid}.${Date.now()}`;
    const data = JSON.stringify(Array.from(desiredRunningVMs.values()), null, 2);
    fs.writeFileSync(tmp, data, 'utf8');
    fs.renameSync(tmp, DESIRED_VMS_FILE);
  } catch (err) {
    console.error('[DesiredVMs] Lỗi lưu desired_vms.json:', err.message);
  }
}

function markVMDesired(userId, vmId) {
  const key = `${Number(userId)}_${Number(vmId)}`;
  desiredRunningVMs.set(key, {
    userId: Number(userId),
    vmId: Number(vmId),
    updatedAt: new Date().toISOString()
  });
  saveDesiredVMsAtomic();
}

function unmarkVMDesired(userId, vmId) {
  const key = `${Number(userId)}_${Number(vmId)}`;
  if (desiredRunningVMs.delete(key)) {
    saveDesiredVMsAtomic();
  }
}

// ==================== WATCHDOG VỚI EXPONENTIAL BACKOFF (P0-3) ====================
vmManager.on('vm_exit', ({ vm, userId, vmId, code, signal, manualStop }) => {
  const key = `${userId}_${vmId}`;
  if (manualStop || !desiredRunningVMs.has(key)) {
    return;
  }

  const stderrSummary = vm.stderrTail.length > 0
    ? ` | Stderr cuối: ${vm.stderrTail.slice(-3).join(' / ')}`
    : '';

  if (vm.restartCount >= config.WATCHDOG_MAX_RETRIES) {
    vm.backoffExhausted = true;
    unmarkVMDesired(userId, vmId);
    vm.addLog(
      `[Watchdog] Máy Ảo #${vmId} đã tự dừng quá ${config.WATCHDOG_MAX_RETRIES} lần liên tiếp ` +
      `(Mã thoát: ${code}, Tín hiệu: ${signal}${stderrSummary}). Dừng tự động khởi động lại để bảo vệ hệ thống.`
    );
    return;
  }

  const schedule = config.WATCHDOG_BACKOFF_MS;
  const delayMs = schedule[Math.min(vm.restartCount, schedule.length - 1)];
  vm.restartCount += 1;

  vm.addLog(
    `[Watchdog] Máy Ảo #${vmId} tự dừng! (Lần ${vm.restartCount}/${config.WATCHDOG_MAX_RETRIES}, ` +
    `Mã thoát: ${code}${stderrSummary}). Sẽ khởi động lại sau ${delayMs / 1000}s...`
  );

  if (vm.backoffTimer) clearTimeout(vm.backoffTimer);
  vm.backoffTimer = setTimeout(async () => {
    vm.backoffTimer = null;
    if (!desiredRunningVMs.has(key)) return;
    try {
      await vmManager.startVM(userId, vmId);
    } catch (err) {
      vm.addLog(`[Watchdog] Khởi động lại thất bại: ${err.message}`);
    }
  }, delayMs);
});

setInterval(() => {
  const now = Date.now();
  for (const vm of vmManager.vms.values()) {
    if (vm.running && vm.startedAt && vm.restartCount > 0) {
      if (now - vm.startedAt >= config.WATCHDOG_STABLE_RESET_MS) {
        vm.restartCount = 0;
        vm.addLog('[Watchdog] Máy ảo đã hoạt động ổn định > 60 giây, đặt lại bộ đếm khởi động lại.');
      }
    }
  }
}, 15000).unref();

// ==================== MIDDLEWARE BẢO MẬT & CẤU HÌNH EXPRESS ====================
app.use(helmet({ contentSecurityPolicy: false }));
app.use(compression());
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', req.headers.origin || '*');
  res.setHeader('Access-Control-Allow-Credentials', 'true');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,PATCH,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Authorization,X-Client-Platform,X-Refresh-Token');
  res.setHeader('Access-Control-Expose-Headers', 'X-Renewed-Access-Token');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});
app.use(express.json({ limit: '512kb' }));
app.use(express.urlencoded({ extended: false, limit: '512kb' }));
app.use(cookieParser());

const authLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Quá nhiều yêu cầu xác thực, vui lòng thử lại sau.' }
});

function parsePositiveInt(val) {
  const str = String(val ?? '').trim();
  if (!/^[1-9]\d*$/.test(str)) return null;
  const num = Number(str);
  return Number.isSafeInteger(num) ? num : null;
}

function getDirectorySizeBytes(dirPath) {
  if (!fs.existsSync(dirPath)) return 0;
  let total = 0;
  const stack = [dirPath];
  while (stack.length > 0) {
    const cur = stack.pop();
    const entries = fs.readdirSync(cur, { withFileTypes: true });
    for (const entry of entries) {
      const full = path.join(cur, entry.name);
      if (entry.isDirectory()) {
        stack.push(full);
      } else if (entry.isFile()) {
        total += fs.statSync(full).size;
      }
    }
  }
  return total;
}

// ==================== XÁC THỰC NGƯỜI DÙNG & CƠ CHẾ MẠNG KÉP (DUAL-CONNECTION) ====================
function setAuthCookieAndTokens(res, user, deviceInfo = {}) {
  const tokenPair = dualSessionStore.issueTokenPair(user, deviceInfo);
  res.cookie(config.COOKIE_NAME, tokenPair.accessToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: config.IS_PROD,
    maxAge: 7 * 24 * 3600 * 1000
  });
  res.cookie('j2me_refresh_token', tokenPair.refreshToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: config.IS_PROD,
    maxAge: 30 * 24 * 3600 * 1000
  });
  return tokenPair;
}

app.post('/api/auth/register', authLimiter, async (req, res) => {
  try {
    const { username, password, platform } = req.body || {};
    const user = await userStore.createUser(username, password, 'user');
    ensureDefaultVMForUser(user.id);
    const clientPlatform = platform || req.headers['x-client-platform'] || 'Web Cloud';
    const wallet = commerceStore.recordClientSync(user.id, user.username, {
      platform: clientPlatform
    });
    const tokenPair = setAuthCookieAndTokens(res, user, { platform: clientPlatform });
    return res.status(201).json({
      success: true,
      message: 'Đăng ký tài khoản thành công!',
      user,
      wallet,
      eventConfig: commerceStore.getEventConfig(),
      bankConfig: BANK_CONFIG,
      token: tokenPair.accessToken,
      accessToken: tokenPair.accessToken,
      refreshToken: tokenPair.refreshToken,
      gracePeriodSec: tokenPair.gracePeriodSec,
      expiresAt: tokenPair.expiresAt
    });
  } catch (err) {
    return res.status(400).json({ error: err.message });
  }
});

app.post('/api/auth/login', authLimiter, async (req, res) => {
  try {
    const { username, password, platform } = req.body || {};
    const user = await userStore.verifyCredentials(username, password);
    if (!user) {
      return res.status(401).json({ error: 'Sai tên tài khoản hoặc mật khẩu.' });
    }
    if (user.locked) {
      return res.status(403).json({ error: 'Tài khoản này đã bị khóa.' });
    }
    ensureDefaultVMForUser(user.id);
    const safeUser = userStore.sanitize(user);
    const clientPlatform = platform || req.headers['x-client-platform'] || 'Web Cloud';
    const wallet = commerceStore.recordClientSync(safeUser.id, safeUser.username, {
      platform: clientPlatform
    });
    const tokenPair = setAuthCookieAndTokens(res, safeUser, { platform: clientPlatform });
    return res.json({
      success: true,
      message: 'Đăng nhập thành công!',
      user: safeUser,
      wallet,
      eventConfig: commerceStore.getEventConfig(),
      bankConfig: BANK_CONFIG,
      token: tokenPair.accessToken,
      accessToken: tokenPair.accessToken,
      refreshToken: tokenPair.refreshToken,
      gracePeriodSec: tokenPair.gracePeriodSec,
      expiresAt: tokenPair.expiresAt
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

app.post('/api/auth/refresh', (req, res) => {
  const refreshToken = (req.body && req.body.refreshToken) ||
    req.headers['x-refresh-token'] ||
    (req.cookies && req.cookies.j2me_refresh_token);
  const renewed = dualSessionStore.refreshAccessToken(refreshToken, userStore);
  if (!renewed) {
    return res.status(401).json({
      error: 'Phiên Refresh Token đã hết hạn hoặc bị thu hồi. Vui lòng đăng nhập lại.',
      code: 'REFRESH_TOKEN_EXPIRED'
    });
  }
  res.cookie(config.COOKIE_NAME, renewed.accessToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: config.IS_PROD,
    maxAge: 7 * 24 * 3600 * 1000
  });
  const wallet = commerceStore.getWallet(renewed.user.id, renewed.user.username);
  return res.json({
    success: true,
    token: renewed.accessToken,
    accessToken: renewed.accessToken,
    refreshToken: renewed.refreshToken,
    user: renewed.user,
    wallet,
    gracePeriodSec: renewed.gracePeriodSec,
    expiresAt: renewed.expiresAt
  });
});

app.post('/api/auth/logout', (req, res) => {
  const refreshToken = (req.body && req.body.refreshToken) ||
    req.headers['x-refresh-token'] ||
    (req.cookies && req.cookies.j2me_refresh_token);
  if (refreshToken) {
    dualSessionStore.revokeRefreshToken(refreshToken);
  }
  res.clearCookie(config.COOKIE_NAME);
  res.clearCookie('j2me_refresh_token');
  res.json({ success: true });
});

app.get('/api/auth/me', requireAuth, (req, res) => {
  const userDir = path.join(config.DATA_DIR, 'users', `user_${req.user.id}`);
  const vms = vmManager.listUserVMs(req.user.id);
  const usedBytes = getDirectorySizeBytes(userDir);
  const wallet = commerceStore.getWallet(req.user.id, req.user.username);
  const sessionStatus = dualSessionStore.getSessionStatus(req.user.id);
  res.json({
    authenticated: true,
    user: {
      ...req.user,
      usedGames: vms.length,
      maxGames: req.user.maxVMs,
      usedStorageBytes: usedBytes,
      maxStorageBytes: req.user.quotaBytes
    },
    wallet,
    sessionStatus,
    eventConfig: commerceStore.getEventConfig(),
    bankConfig: BANK_CONFIG
  });
});

// ==================== QUẢN LÝ GAME JAR & UPLOAD AN TOÀN (P1-6) ====================
const uploadTmpDir = path.join(config.DATA_DIR, 'tmp_uploads');
fs.mkdirSync(uploadTmpDir, { recursive: true });

const upload = multer({
  dest: uploadTmpDir,
  limits: { fileSize: config.MAX_SINGLE_FILE_BYTES },
  fileFilter: (req, file, cb) => {
    const ext = path.extname(file.originalname || '').toLowerCase();
    if (ext !== '.jar') {
      return cb(new Error('Chỉ chấp nhận file định dạng .jar'));
    }
    cb(null, true);
  }
});

function validateJarArchive(filePath) {
  let zip;
  try {
    zip = new AdmZip(filePath);
  } catch (_) {
    throw new Error('File tải lên không phải là kho lưu trữ JAR/ZIP hợp lệ.');
  }
  const entries = zip.getEntries();
  if (entries.length === 0 || entries.length > config.MAX_JAR_ENTRIES) {
    throw new Error(`Số lượng tệp bên trong JAR không hợp lệ (${entries.length}).`);
  }
  let totalUncompressed = 0;
  let hasManifest = false;
  for (const entry of entries) {
    if (entry.entryName.includes('..')) {
      throw new Error('Phát hiện đường dẫn nguy hiểm (Zip Slip) trong file JAR.');
    }
    if (entry.entryName.toUpperCase() === 'META-INF/MANIFEST.MF') {
      hasManifest = true;
    }
    totalUncompressed += Number(entry.header.size) || 0;
    if (totalUncompressed > config.MAX_UNCOMPRESSED_JAR_BYTES) {
      throw new Error('Dung lượng giải nén của JAR vượt mức cho phép (nghi ngờ Zip-Bomb).');
    }
  }
  if (!hasManifest) {
    throw new Error('File JAR không hợp lệ (thiếu META-INF/MANIFEST.MF).');
  }
}

function findDefaultSeedJar(userId) {
  const candidates = [];
  if (userId) {
    const userGamesDir = path.join(config.DATA_DIR, 'users', `user_${Number(userId)}`, 'games');
    if (fs.existsSync(userGamesDir)) {
      const files = fs.readdirSync(userGamesDir).filter((f) => f.endsWith('.jar')).sort().reverse();
      for (const f of files) {
        candidates.push(path.join(userGamesDir, f));
      }
    }
  }
  candidates.push(
    path.join(__dirname, 'HSO-402-VIP-0.jar'),
    path.join(config.DATA_DIR, 'users', 'user_1', 'saves', 'rms_vm_1', 'game.jar')
  );
  const user1Games = path.join(config.DATA_DIR, 'users', 'user_1', 'games');
  if (fs.existsSync(user1Games)) {
    for (const f of fs.readdirSync(user1Games)) {
      if (f.endsWith('.jar')) candidates.push(path.join(user1Games, f));
    }
  }
  return candidates.find((p) => fs.existsSync(p)) || null;
}

function ensureDefaultVMForUser(userId) {
  const existing = vmManager.listUserVMs(userId);
  if (existing.length === 0) {
    const seedJar = findDefaultSeedJar(userId);
    if (seedJar) {
      vmManager.createVM(userId, 1, seedJar, {
        name: 'HSO 402 VIP - Máy Ảo #1',
        resolution: '240x320',
        fps: 15
      });
    }
  }
}

app.get('/api/games', requireAuth, (req, res) => {
  ensureDefaultVMForUser(req.user.id);
  const vms = vmManager.listUserVMs(req.user.id).map((vm) => {
    const stat = fs.existsSync(vm.jarPath) ? fs.statSync(vm.jarPath) : { size: 0, mtime: new Date() };
    return {
      id: vm.id,
      name: vm.name || `Máy Ảo #${vm.id}`,
      title: vm.name || `Máy Ảo #${vm.id}`,
      vendor: 'J2ME Cloud 24/7',
      version: 'VIP',
      filename: path.basename(vm.jarPath),
      size: stat.size,
      file_size_bytes: stat.size,
      resolution: vm.resolution || '240x320',
      isRunning247: vm.running,
      running: vm.running,
      renderEnabled: vm.renderEnabled !== false,
      has_save: true,
      updatedAt: stat.mtime
    };
  });
  res.json({ games: vms });
});

app.get('/api/games/:id', requireAuth, (req, res) => {
  ensureDefaultVMForUser(req.user.id);
  const vmId = parsePositiveInt(req.params.id);
  if (!vmId) {
    return res.status(400).json({ error: 'ID máy ảo không hợp lệ.' });
  }
  let vm = vmManager.getExistingVM(req.user.id, vmId);
  if (!vm) {
    const seedJar = findDefaultSeedJar(req.user.id);
    if (seedJar && vmId <= req.user.maxVMs) {
      vm = vmManager.createVM(req.user.id, vmId, seedJar, {
        name: `Máy Ảo #${vmId}`,
        resolution: '240x320',
        fps: 15
      });
    }
  }
  if (!vm) {
    return res.status(404).json({ error: 'Máy ảo không tồn tại.' });
  }
  const gameObj = {
    id: vm.id,
    userId: vm.userId,
    name: vm.name || `Máy Ảo #${vm.id}`,
    title: vm.name || `Máy Ảo #${vm.id}`,
    resolution: vm.resolution || '240x320',
    jarPath: path.basename(vm.jarPath),
    running: vm.running,
    renderEnabled: vm.renderEnabled !== false
  };
  res.json({
    ...gameObj,
    game: gameObj
  });
});

app.post('/api/games/upload', requireAuth, (req, res) => {
  upload.any()(req, res, (err) => {
    if (err) {
      return res.status(400).json({ error: err.message });
    }
    const uploadedFile = (req.files && req.files.find((f) => f.originalname.toLowerCase().endsWith('.jar'))) || req.file;
    if (!uploadedFile) {
      return res.status(400).json({ error: 'Chưa chọn file .jar để tải lên.' });
    }
    const tmpPath = uploadedFile.path;
    try {
      validateJarArchive(tmpPath);
      const userDir = userStore.ensureUserDirs(req.user.id);
      const usedBytes = getDirectorySizeBytes(userDir);
      if (usedBytes + uploadedFile.size > req.user.quotaBytes) {
        return res.status(403).json({ error: 'Đã vượt quá hạn mức dung lượng lưu trữ của tài khoản.' });
      }
      const safeName = `${Date.now()}_${path.basename(uploadedFile.originalname).replace(/[^a-zA-Z0-9._-]/g, '_')}`;
      const destPath = path.join(userDir, 'games', safeName);
      fs.copyFileSync(tmpPath, destPath);
      patchJarToVipAdmin(destPath);
      return res.json({
        success: true,
        message: 'Tải lên và mở khóa VIP thành công!',
        file: safeName
      });
    } catch (e) {
      return res.status(400).json({ error: e.message });
    } finally {
      if (req.files && Array.isArray(req.files)) {
        for (const f of req.files) {
          if (fs.existsSync(f.path)) {
            try { fs.unlinkSync(f.path); } catch (_) {}
          }
        }
      } else if (fs.existsSync(tmpPath)) {
        try { fs.unlinkSync(tmpPath); } catch (_) {}
      }
    }
  });
});

// ==================== QUẢN LÝ MÁY ẢO (VM API) (P1-1, P1-3, P1-4, P1-5) ====================
function resolveAuthorizedVM(req, res) {
  const vmId = parsePositiveInt(req.params.id);
  if (!vmId) {
    res.status(400).json({ error: 'ID máy ảo phải là số nguyên dương.' });
    return null;
  }
  const targetUserId = req.query.userId
    ? parsePositiveInt(req.query.userId)
    : req.user.id;

  if (!targetUserId) {
    res.status(400).json({ error: 'userId không hợp lệ.' });
    return null;
  }
  if (targetUserId !== req.user.id && req.user.role !== 'admin') {
    res.status(403).json({ error: 'Bạn không có quyền truy cập máy ảo của người dùng khác.' });
    return null;
  }

  const vm = vmManager.getExistingVM(targetUserId, vmId);
  if (!vm) {
    res.status(404).json({ error: `Không tìm thấy Máy Ảo #${vmId}.` });
    return null;
  }
  return vm;
}

app.get('/api/vms', requireAuth, (req, res) => {
  ensureDefaultVMForUser(req.user.id);
  const vms = vmManager.listUserVMs(req.user.id).map((vm) => ({
    id: vm.id,
    userId: vm.userId,
    name: vm.name || `Máy Ảo #${vm.id}`,
    resolution: vm.resolution || '240x320',
    running: vm.running,
    renderEnabled: vm.renderEnabled !== false,
    pid: vm.pid,
    startedAt: vm.startedAt,
    hasFrame: Boolean(vm.lastFrame),
    restartCount: vm.restartCount,
    backoffExhausted: vm.backoffExhausted
  }));
  res.json({ vms });
});

app.post('/api/vm/add-instance', requireAuth, (req, res) => {
  const currentVMs = vmManager.listUserVMs(req.user.id);
  if (req.user.role !== 'admin' && currentVMs.length >= req.user.maxVMs) {
    return res.status(403).json({
      error: `Tài khoản của bạn chỉ được tạo tối đa ${req.user.maxVMs} máy ảo.`
    });
  }
  const existingIds = new Set(currentVMs.map((v) => v.id));
  let nextId = 1;
  while (existingIds.has(nextId)) nextId++;

  let seedJar = findDefaultSeedJar(req.user.id);
  if (req.body && req.body.jarFile) {
    const candidate = path.join(
      config.DATA_DIR,
      'users',
      `user_${req.user.id}`,
      'games',
      path.basename(String(req.body.jarFile))
    );
    if (fs.existsSync(candidate)) {
      seedJar = candidate;
    }
  }
  if (!seedJar) {
    return res.status(400).json({ error: 'Chưa có file game .jar mẫu để tạo máy ảo.' });
  }
  const name = (req.body && req.body.name) ? String(req.body.name).trim() : `Máy Ảo #${nextId}`;
  const resolution = (req.body && req.body.resolution) ? String(req.body.resolution).trim() : '240x320';
  const vm = vmManager.createVM(req.user.id, nextId, seedJar, {
    name,
    resolution,
    fps: 15,
    overwriteJar: Boolean(req.body && req.body.jarFile)
  });
  res.status(201).json({
    success: true,
    filename: `rms_vm_${vm.id}`,
    vm: {
      id: vm.id,
      userId: vm.userId,
      name: vm.name,
      resolution: vm.resolution,
      running: vm.running,
      renderEnabled: vm.renderEnabled !== false
    }
  });
});

app.delete('/api/vms/:id', requireAuth, async (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;
  unmarkVMDesired(vm.userId, vm.id);
  await vmManager.deleteVM(vm.userId, vm.id);
  res.json({ success: true });
});

app.post('/api/vm/:id/start', requireAuth, async (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;
  try {
    vm.restartCount = 0;
    vm.backoffExhausted = false;
    vm.renderEnabled = true; // Luôn duy trì hình ảnh khi khởi động máy ảo
    markVMDesired(vm.userId, vm.id);
    const { resolution, fps } = req.body || {};
    await vmManager.startVM(vm.userId, vm.id, { resolution, fps });
    setTimeout(() => {
      vmManager.sendCommand(vm.userId, vm.id, 'RENDER:1');
      vmManager.sendCommand(vm.userId, vm.id, 'SYNC');
    }, 600);
    res.json({
      success: true,
      running: vm.running,
      renderEnabled: true,
      pid: vm.pid
    });
  } catch (err) {
    unmarkVMDesired(vm.userId, vm.id);
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/vm/:id/stop', requireAuth, async (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;
  unmarkVMDesired(vm.userId, vm.id);
  await vmManager.stopVM(vm.userId, vm.id, true);
  res.json({ success: true, running: vm.running });
});

// Endpoint chuyên biệt để Bật / Tắt hình ảnh (Tối ưu RAM/CPU)
app.post('/api/vm/:id/render', requireAuth, (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;
  const enabled = req.body && req.body.enabled !== undefined
    ? Boolean(req.body.enabled)
    : !(vm.renderEnabled !== false);
  setVMRenderState(vm, enabled);
  res.json({
    success: true,
    renderEnabled: vm.renderEnabled !== false
  });
});

app.get('/api/vm/:id/status', requireAuth, (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;
  const isRenderOn = vm.renderEnabled !== false;
  res.json({
    id: vm.id,
    userId: vm.userId,
    name: vm.name,
    resolution: vm.resolution,
    running: vm.running,
    renderEnabled: isRenderOn,
    pid: vm.pid,
    lastFrame: (isRenderOn && vm.lastFrame) ? `data:image/jpeg;base64,${vm.lastFrame.toString('base64')}` : null,
    lastFrameAt: vm.lastFrameAt,
    logs: vm.logs.slice(-60),
    restartCount: vm.restartCount,
    backoffExhausted: vm.backoffExhausted
  });
});

// P1-4: SSE dự phòng gửi chuỗi data:image/jpeg;base64,... hợp lệ + hỗ trợ event: frame/log/render_state
app.get('/api/vm/:id/stream', requireAuth, (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no'
  });

  const sendFrame = (buf) => {
    if (!buf || vm.renderEnabled === false || res.writableEnded) return;
    const uri = `data:image/jpeg;base64,${buf.toString('base64')}`;
    res.write(`data: ${uri}\n\n`);
    res.write(`event: frame\ndata: ${uri}\n\n`);
  };

  const sendLog = (line) => {
    if (res.writableEnded) return;
    res.write(`event: log\ndata: ${JSON.stringify(line)}\n\n`);
  };

  const sendOpenUrl = (url) => {
    if (res.writableEnded) return;
    res.write(`event: open_url\ndata: ${JSON.stringify(url)}\n\n`);
  };

  const sendRenderState = (enabled) => {
    if (res.writableEnded) return;
    res.write(`event: render_state\ndata: ${JSON.stringify({ enabled })}\n\n`);
  };

  res.write(`event: init_logs\ndata: ${JSON.stringify(vm.logs.slice(-60))}\n\n`);
  sendRenderState(vm.renderEnabled !== false);

  if (vm.renderEnabled !== false) {
    if (vm.lastFrame) {
      sendFrame(vm.lastFrame);
    } else if (vm.running) {
      vmManager.sendCommand(vm.userId, vm.id, 'SYNC');
    }
  }

  vm.on('frame', sendFrame);
  vm.on('log', sendLog);
  vm.on('open_url', sendOpenUrl);
  vm.on('render_state', sendRenderState);

  req.on('close', () => {
    vm.off('frame', sendFrame);
    vm.off('log', sendLog);
    vm.off('open_url', sendOpenUrl);
    vm.off('render_state', sendRenderState);
  });
});

// P1-5: Làm sạch đầu vào và whitelist đầy đủ các lệnh từ play.html xuống HeadlessJ2ME.java
const ALLOWED_INPUT_TYPES = new Set([
  'keydown', 'keyup', 'keypress',
  'mousedown', 'mouseup', 'mousemove', 'click',
  'text', 'chat_cmd', 'set_user', 'set_pass', 'login',
  'param', 'add_points', 'auto_quest', 'auto_point', 'auto_sell', 'ipc', 'action',
  'toggle_render', 'sync'
]);

function sanitizeInputText(val, maxLen = 160) {
  if (val === undefined || val === null) return '';
  return String(val)
    .replace(/[\r\n\0]+/g, ' ')
    .slice(0, maxLen);
}

function parseFiniteInt(val, min = -10000, max = 10000) {
  const n = Number(val);
  if (!Number.isFinite(n)) return null;
  const rounded = Math.round(n);
  if (rounded < min || rounded > max) return null;
  return rounded;
}

function dispatchVMInput(vm, payload) {
  if (!payload || typeof payload !== 'object') {
    throw new Error('Dữ liệu điều khiển không hợp lệ.');
  }
  const type = String(payload.type || '').toLowerCase();
  if (!ALLOWED_INPUT_TYPES.has(type)) {
    throw new Error(`Loại lệnh điều khiển không được phép: ${type}`);
  }

  let cmd = null;
  switch (type) {
    case 'toggle_render': {
      const enabled = Boolean(payload.enabled);
      vm.renderEnabled = enabled;
      if (!enabled) {
        vm.lastFrame = null; // Giải phóng bộ nhớ đệm khung hình trong RAM Node.js
      }
      cmd = `RENDER:${enabled ? '1' : '0'}`;
      break;
    }
    case 'render': {
      const nextState = payload.enabled !== undefined
        ? Boolean(payload.enabled)
        : !(vm.renderEnabled !== false);
      return setVMRenderState(vm, nextState);
    }
    case 'sync': {
      cmd = 'SYNC';
      break;
    }
    case 'keydown':
    case 'keyup':
    case 'keypress': {
      const key = parseFiniteInt(payload.key ?? payload.keyCode, -999999, 999999);
      if (key === null) throw new Error('Mã phím (key) phải là số nguyên.');
      const prefix = type === 'keydown' ? 'KD' : type === 'keyup' ? 'KU' : 'KP';
      cmd = `${prefix}:${key}`;
      break;
    }
    case 'mousedown':
    case 'mouseup':
    case 'mousemove': {
      const x = parseFiniteInt(payload.x, 0, 2000);
      const y = parseFiniteInt(payload.y, 0, 2000);
      if (x === null || y === null) throw new Error('Tọa độ chuột (x, y) phải là số hợp lệ.');
      const prefix = type === 'mousedown' ? 'MD' : type === 'mouseup' ? 'MU' : 'MM';
      cmd = `${prefix}:${x},${y}`;
      break;
    }
    case 'click': {
      const x = parseFiniteInt(payload.x, 0, 2000);
      const y = parseFiniteInt(payload.y, 0, 2000);
      if (x === null || y === null) throw new Error('Tọa độ chuột (x, y) phải là số hợp lệ.');
      vmManager.sendCommand(vm.userId, vm.id, `MD:${x},${y}`);
      cmd = `MU:${x},${y}`;
      break;
    }
    case 'text': {
      const text = sanitizeInputText(payload.text, 200);
      cmd = `TEXT:${text}`;
      break;
    }
    case 'chat_cmd': {
      const text = sanitizeInputText(payload.text ?? payload.cmd, 120);
      cmd = `CHAT_CMD:${text}`;
      break;
    }
    case 'set_user': {
      const text = sanitizeInputText(payload.text ?? payload.username, 64);
      cmd = `SET_USER:${text}`;
      break;
    }
    case 'set_pass': {
      const text = sanitizeInputText(payload.text ?? payload.password, 64);
      cmd = `SET_PASS:${text}`;
      break;
    }
    case 'login': {
      cmd = 'DO_LOGIN';
      break;
    }
    case 'param': {
      const value = sanitizeInputText(payload.text ?? payload.value, 120);
      const autoConfirm = payload.autoConfirm ? '1' : '0';
      const confirmKey = parseFiniteInt(payload.confirmKey, -999, 999) ?? -5;
      cmd = `PARAM:${autoConfirm}:${confirmKey}:${value}`;
      break;
    }
    case 'add_points': {
      const mode = payload.mode === 'repeat' ? 'repeat' : 'dialog';
      const slotOffset = parseFiniteInt(payload.slotOffset, 0, 20) ?? 0;
      const amount = parseFiniteInt(payload.amount, 1, 5000) ?? 1;
      const confirmKey = parseFiniteInt(payload.confirmKey, -999, 999) ?? -5;
      cmd = `ADD_POINTS:${mode}:${slotOffset}:${amount}:${confirmKey}`;
      break;
    }
    case 'auto_quest': {
      const filter = ['all', 'main', 'sub', 'farm'].includes(payload.filter) ? payload.filter : 'all';
      const mode = payload.stepOnly ? 'step' : (payload.enabled ? '1' : '0');
      cmd = `AUTO_QUEST:${mode}:${filter}:1:1`;
      break;
    }
    case 'auto_point': {
      const mode = payload.applyNow ? 'now' : (payload.enabled ? '1' : '0');
      const str = parseFiniteInt(payload.str, 0, 5) ?? 0;
      const dex = parseFiniteInt(payload.dex, 0, 5) ?? 0;
      const vit = parseFiniteInt(payload.vit, 0, 5) ?? 0;
      const spi = parseFiniteInt(payload.spi, 0, 5) ?? 0;
      cmd = `AUTO_POINT:${mode}:${str}:${dex}:${vit}:${spi}`;
      break;
    }
    case 'auto_sell':
    case 'ipc': {
      const raw = sanitizeInputText(payload.cmd ?? payload.text, 160);
      if (/^(AUTO_SELL|SELL_SETUP|AUTO_QUEST|AUTO_FARM|AUTO_POINT|AUTO_POTENTIAL|RENDER):/i.test(raw)) {
        if (/^RENDER:/i.test(raw)) {
          const isEn = raw.split(':')[1]?.trim() === '1';
          return setVMRenderState(vm, isEn);
        }
        cmd = raw;
      } else {
        throw new Error('Lệnh IPC không hợp lệ.');
      }
      break;
    }
    case 'action': {
      const act = sanitizeInputText(payload.action ?? payload.name, 40).replace(/[^a-zA-Z0-9_-]/g, '');
      if (!act) throw new Error('Tên action không hợp lệ.');
      cmd = `ACTION:${act}`;
      break;
    }
  }

  if (cmd) {
    return vmManager.sendCommand(vm.userId, vm.id, cmd);
  }
  return false;
}

app.post('/api/vm/:id/input', requireAuth, (req, res) => {
  const vm = resolveAuthorizedVM(req, res);
  if (!vm) return;
  try {
    const sent = dispatchVMInput(vm, req.body);
    res.json({
      success: true,
      sent,
      renderEnabled: vm.renderEnabled !== false
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

// ==================== ROUTE QUẢN TRỊ ADMIN THẬT (P2-6) ====================
app.get('/api/admin/overview', requireAuth, requireAdmin, (req, res) => {
  const activeSessions = [];
  const users = userStore.listAll().map((u) => {
    const userDir = path.join(config.DATA_DIR, 'users', `user_${u.id}`);
    const gamesDir = path.join(userDir, 'games');
    const savesDir = path.join(userDir, 'saves');
    const gamesBytes = getDirectorySizeBytes(gamesDir);
    const savesBytes = getDirectorySizeBytes(savesDir);
    const storageUsedBytes = gamesBytes + savesBytes;
    const vms = vmManager.listUserVMs(u.id);
    const runningVMs = vms.filter((v) => v.running);

    for (const rv of runningVMs) {
      activeSessions.push({
        username: u.username,
        game_title: rv.name || `Máy Ảo #${rv.id}`,
        last_ping: Math.floor((rv.lastFrameAt || rv.startedAt || Date.now()) / 1000)
      });
    }

    return {
      ...u,
      vmCount: vms.length,
      runningVMs: runningVMs.length,
      storageUsedBytes,
      game_count: vms.length,
      max_games: u.maxVMs,
      games_bytes: gamesBytes,
      saves_bytes: savesBytes,
      max_storage_bytes: u.quotaBytes,
      is_locked: Boolean(u.locked)
    };
  });

  const totalRunningVMs = Array.from(vmManager.vms.values()).filter((v) => v.running).length;
  const memoryRssBytes = process.memoryUsage().rss;

  res.json({
    users,
    totalRunningVMs,
    memoryRssBytes,
    activeSessions,
    reports: [],
    system: {
      platform: `${os.type()} ${os.release()} (${os.arch()})`,
      nodeRamRssMB: (memoryRssBytes / (1024 * 1024)).toFixed(1),
      freeSystemRamMB: Math.round(os.freemem() / (1024 * 1024)),
      totalSystemRamMB: Math.round(os.totalmem() / (1024 * 1024)),
      activePlayersCount: totalRunningVMs,
      maxConcurrentSessions
    }
  });
});

async function handleLockUser(req, res) {
  const uid = parsePositiveInt(req.params.id);
  if (!uid) return res.status(400).json({ error: 'ID người dùng không hợp lệ.' });
  const lockFlag = req.body && (req.body.locked !== undefined ? req.body.locked : req.body.isLocked);
  const updated = userStore.setLocked(uid, lockFlag);
  if (!updated) return res.status(404).json({ error: 'Không tìm thấy người dùng.' });
  if (updated.locked) {
    for (const vm of vmManager.listUserVMs(uid)) {
      unmarkVMDesired(uid, vm.id);
      await vmManager.stopVM(uid, vm.id, true);
    }
  }
  res.json({
    success: true,
    message: updated.locked ? 'Đã khóa tài khoản.' : 'Đã mở khóa tài khoản.',
    user: updated
  });
}

app.put('/api/admin/users/:id/lock', requireAuth, requireAdmin, handleLockUser);
app.patch('/api/admin/users/:id/lock', requireAuth, requireAdmin, handleLockUser);

function handleQuotaUser(req, res) {
  const uid = parsePositiveInt(req.params.id);
  if (!uid) return res.status(400).json({ error: 'ID người dùng không hợp lệ.' });
  const body = req.body || {};
  const maxVMs = body.maxVMs !== undefined ? body.maxVMs : body.maxGames;
  const quotaBytes = body.quotaBytes !== undefined
    ? body.quotaBytes
    : (body.maxStorageMB !== undefined ? Number(body.maxStorageMB) * 1024 * 1024 : undefined);

  const updated = userStore.setQuota(uid, { maxVMs, quotaBytes });
  if (!updated) return res.status(404).json({ error: 'Không tìm thấy người dùng.' });
  res.json({
    success: true,
    message: 'Đã cập nhật hạn mức tài khoản.',
    user: updated
  });
}

app.put('/api/admin/users/:id/quota', requireAuth, requireAdmin, handleQuotaUser);
app.patch('/api/admin/users/:id/quota', requireAuth, requireAdmin, handleQuotaUser);

app.put('/api/admin/settings/concurrency', requireAuth, requireAdmin, (req, res) => {
  const val = parsePositiveInt(req.body && req.body.maxConcurrentSessions);
  if (!val) return res.status(400).json({ error: 'Số CCU không hợp lệ.' });
  maxConcurrentSessions = val;
  res.json({ success: true, message: `Đã cập nhật giới hạn ${val} CCU.`, maxConcurrentSessions });
});

app.delete('/api/admin/users/:id', requireAuth, requireAdmin, async (req, res) => {
  const uid = parsePositiveInt(req.params.id);
  if (!uid) return res.status(400).json({ error: 'ID người dùng không hợp lệ.' });
  try {
    for (const vm of vmManager.listUserVMs(uid)) {
      unmarkVMDesired(uid, vm.id);
      await vmManager.deleteVM(uid, vm.id);
    }
    const ok = userStore.deleteUser(uid);
    if (!ok) return res.status(404).json({ error: 'Không tìm thấy người dùng.' });
    res.json({ success: true, message: 'Đã xóa tài khoản thành công.' });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/admin/reports/:id/resolve', requireAuth, requireAdmin, (req, res) => {
  res.json({
    success: true,
    message: 'Đã xử lý báo cáo.',
    reportId: req.params.id,
    resolvedAt: new Date().toISOString()
  });
});

// ==================== CỔNG HTTP CORS PROXY & ĐỒNG BỘ CLOUD RMS & UNLOCK VIP ====================
app.all('/api/net-proxy', createHttpNetProxyHandler(networkRelay));

app.get('/api/relay/stats', requireAuth, (req, res) => {
  res.json({
    cloudVmMode: CLOUD_VM_MODE,
    ...networkRelay.getStatsSnapshot()
  });
});

app.post('/api/saves/sync', requireAuth, (req, res) => {
  try {
    const slotId = Math.max(1, Math.min(20, Number(req.body.slotId || req.query.slotId || 1)));
    const direction = String(req.body.direction || 'both');
    const clientRecords = req.body.records && typeof req.body.records === 'object' ? req.body.records : {};

    const saveDir = path.join(config.DATA_DIR, 'users', `user_${req.user.id}`, 'saves', `rms_vm_${slotId}`, 'rms');
    fs.mkdirSync(saveDir, { recursive: true });

    let writtenCount = 0;
    if (direction === 'push' || direction === 'both') {
      for (const [rawName, base64Val] of Object.entries(clientRecords)) {
        const safeName = path.basename(String(rawName)).replace(/[^a-zA-Z0-9._-]/g, '_');
        if (!safeName || typeof base64Val !== 'string') continue;
        const buf = Buffer.from(base64Val, 'base64');
        if (buf.length <= 512 * 1024) {
          fs.writeFileSync(path.join(saveDir, safeName), buf);
          writtenCount++;
        }
      }
    }

    const serverRecords = {};
    if (direction === 'pull' || direction === 'both') {
      const files = fs.existsSync(saveDir) ? fs.readdirSync(saveDir) : [];
      for (const f of files) {
        const full = path.join(saveDir, f);
        if (fs.statSync(full).isFile()) {
          serverRecords[f] = fs.readFileSync(full).toString('base64');
        }
      }
    }

    res.json({
      success: true,
      slotId,
      writtenCount,
      recordCount: Object.keys(serverRecords).length,
      records: serverRecords,
      syncedAt: new Date().toISOString()
    });
  } catch (err) {
    res.status(500).json({ error: `Lỗi đồng bộ Cloud RMS: ${err.message}` });
  }
});

app.get('/api/vip/status', requireAuth, (req, res) => {
  const user = userStore.findById(req.user.id) || req.user;
  const isVip = Boolean(user.role === 'admin' || user.quota >= 3 || user.vipTier > 0);
  const maxSlots = isVip ? Math.max(6, Number(user.quota) || 6) : Math.max(2, Number(user.quota) || 2);
  res.json({
    userId: user.id,
    username: user.username,
    isVip,
    vipBadge: isVip ? 'VIP ADMIN - Duy Trường' : 'Standard Cloud Member',
    maxSlots,
    gracePeriodSec: isVip ? 120 : 60,
    unlockedFeatures: {
      autoQuestPro: true,
      fixedSpotClusterLock: true,
      autoSellFilter: true,
      autoPotential: true,
      multiAccountDock: true,
      antiSleepPip: true
    }
  });
});

app.post('/api/vip/unlock', requireAuth, (req, res) => {
  try {
    const slotId = Math.max(1, Number(req.body.slotId || 1));
    const jarPath = path.join(config.DATA_DIR, 'users', `user_${req.user.id}`, 'saves', `rms_vm_${slotId}`, 'game.jar');
    const patchRes = patchJarToVipAdmin(jarPath);
    res.json({
      success: true,
      slotId,
      vipBadge: 'VIP ADMIN - Duy Trường',
      gracePeriodSec: 120,
      maxSlots: 6,
      patchResult: patchRes
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

// ==================== HỆ THỐNG ĐỒNG BỘ TÀI KHOẢN, NẠP QR TECHCOMBANK, THUÊ 24/7 & ADMIN ====================
app.get('/api/commerce/catalog', (req, res) => {
  const catalog = commerceStore.getCatalogWithPricing();
  const user = verifyUserFromRequest(req);
  if (!user) {
    return res.json({
      success: true,
      ...catalog,
      user: null,
      wallet: null,
      topupOrders: [],
      serviceOrders: []
    });
  }
  const wallet = commerceStore.getWallet(user.id, user.username);
  const history = commerceStore.getUserTransactions(user.id);
  res.json({
    success: true,
    ...catalog,
    user,
    wallet,
    ...history
  });
});

// Đồng bộ khung hình trực tiếp (Live Frame Mirroring) từ App / Client lên Server để mở Web thấy ngay hình ảnh nick đang chơi
app.post('/api/vm/frame-sync', (req, res) => {
  const user = verifyUserFromRequest(req) || { id: 1, username: 'admin' };
  const body = req.body || {};
  const slotId = Math.max(1, Number(body.slotId || 1));
  const frameDataUrl = typeof body.frameDataUrl === 'string' ? body.frameDataUrl : '';

  if (frameDataUrl.startsWith('data:image/')) {
    commerceStore.setLiveFrame(slotId, {
      slotId,
      frameDataUrl,
      username: user.username,
      charName: body.charName || 'HiệpSĩ_Alpha',
      level: Number(body.level || 39),
      mapId: Number(body.mapId || 17),
      mapName: body.mapName || 'Hang Dơi',
      hp: Number(body.hp || 4820),
      maxHp: Number(body.maxHp || 5200),
      mp: Number(body.mp || 1450),
      maxMp: Number(body.maxMp || 1800),
      stateText: body.stateText || 'Đang cắm bãi 24/7'
    });

    // Đồng bộ vào bộ đệm VM trên Server để WebSocket / SSE cũng nhận được hình ảnh tức thì
    const vm = vmManager.getExistingVM(user.id, slotId) || vmManager.getExistingVM(1, slotId);
    if (vm) {
      const base64Part = frameDataUrl.split(',')[1];
      if (base64Part) {
        try {
          const buf = Buffer.from(base64Part, 'base64');
          vm.lastFrame = buf;
          vm.lastFrameAt = Date.now();
          vm.emit('frame', buf);
        } catch (_) {}
      }
    }
  }

  res.json({ success: true, slotId, updatedAt: Date.now() });
});

app.get('/api/vm/:id/live-frame', (req, res) => {
  const slotId = Math.max(1, Number(req.params.id || 1));
  const user = verifyUserFromRequest(req);
  const targetUid = user ? user.id : 1;

  // 1. Ưu tiên lấy frame từ bộ nhớ đồng bộ thời gian thực (App / Web / VM)
  const synced = commerceStore.getLiveFrame(slotId);
  if (synced && synced.frameDataUrl) {
    return res.json({
      success: true,
      source: 'live_sync',
      ...synced
    });
  }

  // 2. Lấy từ HeadlessJ2ME VM trên Server nếu đang chạy
  const vm = vmManager.getExistingVM(targetUid, slotId) || vmManager.getExistingVM(1, slotId);
  if (vm && vm.lastFrame) {
    return res.json({
      success: true,
      source: 'headless_vm',
      slotId,
      frameDataUrl: `data:image/jpeg;base64,${vm.lastFrame.toString('base64')}`,
      updatedAt: vm.lastFrameAt || Date.now()
    });
  }

  res.json({
    success: true,
    source: 'client_canvas',
    slotId,
    frameDataUrl: null
  });
});

app.post('/api/commerce/topup', requireAuth, (req, res) => {
  try {
    const amountVnd = Number(req.body && req.body.amountVnd);
    const platform = String((req.body && req.body.platform) || req.headers['x-client-platform'] || 'Web / App');
    const order = commerceStore.createTopupOrder(req.user.id, req.user.username, amountVnd, platform);
    res.status(201).json({
      success: true,
      message: 'Đã tạo mã QR nạp tiền Techcombank (9823042007 - NGUYỄN ĐỨC DUY TRƯỜNG). Vui lòng quét mã chuyển khoản để Admin duyệt!',
      order
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/commerce/rent', requireAuth, (req, res) => {
  try {
    const result = commerceStore.createServiceOrKeyOrder(req.user.id, req.user.username, req.body || {});
    res.status(201).json({
      success: true,
      message: `Đăng ký thành công ${result.order.serviceName} (${result.order.planName})! Key VIP: ${result.order.vipKeyIssued}`,
      ...result
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/commerce/heartbeat', requireAuth, (req, res) => {
  const body = req.body || {};
  dualSessionStore.touchSessionByUser(req.user.id, body.platform || '');
  const wallet = commerceStore.recordClientSync(req.user.id, req.user.username, body);
  res.json({
    success: true,
    wallet,
    sessionStatus: dualSessionStore.getSessionStatus(req.user.id),
    eventConfig: commerceStore.getEventConfig()
  });
});

// ==================== ĐỒNG BỘ & DUY TRÌ 24/7 CHO NGƯỜI CHƠI BẢN MÁY TÍNH (PC DESKTOP LAUNCHER) ====================
app.post('/api/pc/sync-session', async (req, res) => {
  try {
    const body = req.body || {};
    const authedUser = verifyUserFromRequest(req, res);
    const userId = authedUser ? authedUser.id : 1;
    const username = authedUser
      ? authedUser.username
      : String(body.username || body.gameUsername || body.pcClientId || 'PC_Player').trim();
    const slotId = Math.max(1, Math.min(20, Number(body.slotId || 1)));

    // 1. Lưu đồng bộ RMS từ máy tính lên Cloud Render để giữ tài khoản & cấu hình không mất
    let syncedRmsCount = 0;
    if (body.rmsRecords && typeof body.rmsRecords === 'object') {
      const saveDir = path.join(config.DATA_DIR, 'users', `user_${userId}`, 'saves', `rms_vm_${slotId}`, 'rms');
      fs.mkdirSync(saveDir, { recursive: true });
      for (const [rawName, base64Val] of Object.entries(body.rmsRecords)) {
        const safeName = path.basename(String(rawName)).replace(/[^a-zA-Z0-9._-]/g, '_');
        if (!safeName || typeof base64Val !== 'string') continue;
        const buf = Buffer.from(base64Val, 'base64');
        if (buf.length <= 512 * 1024) {
          fs.writeFileSync(path.join(saveDir, safeName), buf);
          syncedRmsCount++;
        }
      }
    }

    // 2. Ghi nhận phiên Mạng Kép PC 24/7 & cập nhật lên Admin Dashboard
    const pcSession = dualSessionStore.recordPcClientSession({
      ...body,
      userId,
      username,
      role: authedUser ? authedUser.role : 'pc-client',
      platform: body.platform || 'PC Desktop (HSO-AutoQuest-PC-Launcher)'
    });

    commerceStore.recordClientSync(userId, username, {
      platform: body.platform || 'PC Desktop 24/7',
      activeSlot: slotId,
      autoQuest: Boolean(body.autoQuest),
      mapName: body.mapName || 'Đang cắm trên PC',
      level: Number(body.level || 1),
      gameUsername: body.gameUsername || username,
      charName: body.charName || body.gameUsername || username,
      stateText: body.stateText || (body.autoQuest ? `Auto NV (${body.questScope || 'ALL'})` : 'Đang chơi trên PC'),
      frameDataUrl: body.frameDataUrl || ''
    });

    // 3. Nếu người chơi PC bấm "Chuyển giao / Duy trì 24/7 trên Cloud Render" -> Bật luôn VM trên Render
    let cloudVmHandover = false;
    if (body.startCloudVm247) {
      ensureDefaultVMForUser(userId);
      markVMDesired(userId, slotId);
      await vmManager.startVM(userId, slotId);
      const scope = String(body.questScope || 'ALL').toUpperCase();
      if (body.autoQuest !== false) {
        vmManager.sendCommand(userId, slotId, `AUTO_QUEST:1:${scope}:1:1:1`);
      }
      cloudVmHandover = true;
    }

    res.json({
      success: true,
      cloudUrl: process.env.RENDER_EXTERNAL_URL || 'https://j2meweb24-7.onrender.com',
      syncedRmsCount,
      cloudVmHandover,
      pcSession,
      eventConfig: commerceStore.getEventConfig(),
      serverTimestamp: Date.now()
    });
  } catch (err) {
    res.status(500).json({ error: `Lỗi đồng bộ phiên PC 24/7: ${err.message}` });
  }
});

// ==================== ĐỒNG BỘ HÀNG ĐỢI OFFLINE (DUAL-CONNECTION SYNC QUEUE) ====================
app.post('/api/sync/offline-queue', requireAuth, async (req, res) => {
  try {
    const actions = Array.isArray(req.body && req.body.actions) ? req.body.actions : [];
    const results = await dualSessionStore.processOfflineQueue(req.user, actions, {
      SYNC_RMS: (user, payload) => {
        const slotId = Math.max(1, Math.min(20, Number(payload.slotId || 1)));
        const records = payload.records && typeof payload.records === 'object' ? payload.records : {};
        const saveDir = path.join(config.DATA_DIR, 'users', `user_${user.id}`, 'saves', `rms_vm_${slotId}`, 'rms');
        fs.mkdirSync(saveDir, { recursive: true });
        let writtenCount = 0;
        for (const [rawName, base64Val] of Object.entries(records)) {
          const safeName = path.basename(String(rawName)).replace(/[^a-zA-Z0-9._-]/g, '_');
          if (!safeName || typeof base64Val !== 'string') continue;
          const buf = Buffer.from(base64Val, 'base64');
          if (buf.length <= 512 * 1024) {
            fs.writeFileSync(path.join(saveDir, safeName), buf);
            writtenCount++;
          }
        }
        return { slotId, writtenCount };
      },
      AUTO_QUEST_PROGRESS: (user, payload) => {
        const wallet = commerceStore.recordClientSync(user.id, user.username, {
          platform: payload.platform || 'Dual-Network AutoSync',
          gameUsername: payload.gameUsername || '',
          antiSleepEnabled: true
        });
        return {
          syncedProgress: true,
          questStep: payload.questStep || '',
          level: payload.level || 39,
          balanceVnd: wallet.balanceVnd
        };
      },
      CREATE_TOPUP_QR: (user, payload) => {
        const order = commerceStore.createTopupOrder(
          user.id,
          user.username,
          Number(payload.amountVnd || 20000),
          payload.platform || 'Offline Queue Sync'
        );
        return { order };
      },
      REGISTER_RENT_247: (user, payload) => {
        return commerceStore.createServiceOrKeyOrder(user.id, user.username, payload || {});
      },
      HEARTBEAT: (user, payload) => {
        const wallet = commerceStore.recordClientSync(user.id, user.username, payload || {});
        return { balanceVnd: wallet.balanceVnd };
      }
    });

    const wallet = commerceStore.getWallet(req.user.id, req.user.username);
    res.json({
      success: true,
      processedCount: results.processedCount || 0,
      results: results.results || [],
      wallet,
      serverTimestamp: Date.now()
    });
  } catch (err) {
    res.status(500).json({ error: `Lỗi đồng bộ hàng đợi Offline: ${err.message}` });
  }
});

// --- API DÀNH RIÊNG CHO ADMIN KIỂM SOÁT TOÀN BỘ WEB & APP & PC ---
app.get('/api/admin/dual-sessions', requireAuth, requireAdmin, (req, res) => {
  res.json({
    success: true,
    gracePeriodMs: SESSION_GRACE_PERIOD_MS,
    sessions: dualSessionStore.listAllActiveSessions(),
    pcSessions: dualSessionStore.listPcSessions()
  });
});

app.get('/api/admin/commerce/overview', requireAuth, requireAdmin, (req, res) => {
  const allUsers = userStore.listAll();
  const snapshot = commerceStore.getAdminFullSnapshot(allUsers);
  res.json({
    success: true,
    ...snapshot,
    dualSessions: dualSessionStore.listAllActiveSessions(),
    pcSessions: dualSessionStore.listPcSessions(),
    relayStats: networkRelay.getStatsSnapshot()
  });
});

app.post('/api/admin/commerce/topup/:id/decision', requireAuth, requireAdmin, (req, res) => {
  try {
    const { action, note } = req.body || {};
    const result = commerceStore.processTopupOrder(req.params.id, action, note);
    res.json({
      success: true,
      message: result.order.status === 'APPROVED'
        ? `Đã duyệt đơn nạp ${result.order.code}: +${result.order.creditedAmountVnd.toLocaleString('vi-VN')}đ cho ${result.order.username}`
        : `Đã từ chối đơn nạp ${result.order.code}`,
      ...result
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/admin/commerce/service/:id/status', requireAuth, requireAdmin, (req, res) => {
  try {
    const { status, adminResponse } = req.body || {};
    const order = commerceStore.updateServiceOrderStatus(req.params.id, status, adminResponse);
    res.json({
      success: true,
      message: `Đã cập nhật trạng thái đơn #${order.id} sang ${order.status}`,
      order
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/admin/commerce/events', requireAuth, requireAdmin, (req, res) => {
  const updated = commerceStore.updateEventConfig(req.body || {});
  res.json({
    success: true,
    message: 'Đã cập nhật Sự kiện & Khuyến mãi toàn hệ thống (Web + App)!',
    eventConfig: updated
  });
});

app.post('/api/admin/commerce/users/:id/wallet', requireAuth, requireAdmin, (req, res) => {
  const uid = parsePositiveInt(req.params.id);
  if (!uid) return res.status(400).json({ error: 'ID người dùng không hợp lệ.' });
  const targetUser = userStore.findById(uid);
  if (!targetUser) return res.status(404).json({ error: 'Không tìm thấy tài khoản.' });
  const { deltaVnd, grantDays, note } = req.body || {};
  const wallet = commerceStore.adminAdjustBalance(uid, targetUser.username, deltaVnd, grantDays, note);
  res.json({
    success: true,
    message: `Đã cập nhật ví & hạn VIP 24/7 cho ${targetUser.username}`,
    wallet
  });
});

app.get(['/api/health', '/healthz'], (req, res) => {
  res.json({
    status: 'ok',
    uptime: process.uptime(),
    cloudVmMode: CLOUD_VM_MODE,
    renderExternalUrl: process.env.RENDER_EXTERNAL_URL || null,
    keepAliveActive: true,
    gracePeriodSec: Math.round(SESSION_GRACE_PERIOD_MS / 1000),
    relay: networkRelay.getStatsSnapshot()
  });
});

// ==================== TÁCH ROUTE WEB PORTAL: /admin (ADMIN) VÀ /app (USER) ====================
app.get(['/admin', '/admin/', '/admin.html'], guardAdminPageRoute, (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'admin.html'));
});

app.get(['/app', '/app/', '/user', '/play.html'], (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'play.html'));
});

app.use(express.static(path.join(__dirname, 'public')));

// ==================== WEBSOCKET SERVER AN TOÀN (P1-1, P2-1) ====================
const wss = new WebSocket.Server({
  noServer: true,
  maxPayload: config.WS_MAX_PAYLOAD_BYTES
});

function parseCookiesHeader(cookieHeader) {
  const out = {};
  if (!cookieHeader) return out;
  for (const pair of cookieHeader.split(';')) {
    const idx = pair.indexOf('=');
    if (idx > 0) {
      const k = pair.slice(0, idx).trim();
      const v = pair.slice(idx + 1).trim();
      out[k] = decodeURIComponent(v);
    }
  }
  return out;
}

server.on('upgrade', (req, socket, head) => {
  try {
    const urlObj = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    const cookies = parseCookiesHeader(req.headers.cookie);
    const token = cookies[config.COOKIE_NAME] || urlObj.searchParams.get('token') ||
      (req.headers.authorization && req.headers.authorization.startsWith('Bearer ')
        ? req.headers.authorization.slice(7).trim()
        : null);

    // 1. Cổng WebSocket-to-TCP Relay 24/7 (/ws-tcp-relay hoặc /relay)
    if (networkRelay.canHandleUpgrade(urlObj.pathname)) {
      let authInfo = { isVip: true, vipTier: 1 };
      if (token) {
        try {
          const payload = jwt.verify(token, config.JWT_SECRET);
          const u = userStore.findById(payload.id);
          if (u) {
            authInfo = {
              userId: u.id,
              username: u.username,
              role: u.role,
              isVip: u.role === 'admin' || (u.quota && u.quota >= 3)
            };
          }
        } catch (_) {}
      }
      networkRelay.handleUpgrade(req, socket, head, authInfo);
      return;
    }

    // 2. Đường WebSocket VM cũ (/api/vm/:id/ws)
    const match = urlObj.pathname.match(/^\/api\/vm\/([1-9]\d*)\/ws$/);
    if (!match) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }

    if (!token) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    let payload;
    try {
      payload = jwt.verify(token, config.JWT_SECRET);
    } catch (_) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    const user = userStore.findById(payload.id);
    if (!user || user.locked) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    const vmId = Number(match[1]);
    const targetUserId = (user.role === 'admin' && urlObj.searchParams.get('userId'))
      ? Number(urlObj.searchParams.get('userId'))
      : user.id;

    if (targetUserId !== user.id && user.role !== 'admin') {
      socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
      socket.destroy();
      return;
    }

    const vm = vmManager.getExistingVM(targetUserId, vmId);
    if (!vm) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }

    // Nếu vượt giới hạn người xem, tự động dọn kết nối cũ nhất thay vì chặn 429 làm mất hình khi F5
    const maxViewers = Math.max(10, Number(config.MAX_VIEWERS_PER_VM) || 20);
    if (vm.viewers.size >= maxViewers) {
      const oldestWs = vm.viewers.values().next().value;
      if (oldestWs) {
        vm.viewers.delete(oldestWs);
        try { oldestWs.terminate(); } catch (_) {}
      }
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit('connection', ws, req, user, vm);
    });
  } catch (_) {
    socket.destroy();
  }
});

wss.on('connection', (ws, req, user, vm) => {
  ws.isAlive = true;
  vm.viewers.add(ws);

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  const onFrame = (frameBuf) => {
    if (vm.renderEnabled === false) return;
    if (ws.readyState === WebSocket.OPEN && ws.bufferedAmount < 512 * 1024) {
      ws.send(frameBuf, { binary: true });
    }
  };

  const onLog = (line) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ event: 'log', data: line }));
    }
  };

  const onOpenUrl = (url) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ event: 'open_url', data: url }));
    }
  };

  const onRenderState = (enabled) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ event: 'render_state', data: { enabled } }));
    }
  };

  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ event: 'init_logs', data: vm.logs.slice(-60) }));
    if (vm.lastFrame) {
      ws.send(vm.lastFrame, { binary: true });
    } else {
      // Nếu chưa có frame trong cache, yêu cầu HeadlessJ2ME chụp và gửi ngay lập tức
      vmManager.sendCommand(vm.userId, vm.id, 'SYNC');
    }
  }

  vm.on('frame', onFrame);
  vm.on('log', onLog);
  vm.on('open_url', onOpenUrl);
  vm.on('render_state', onRenderState);

  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    try {
      const msg = JSON.parse(data.toString('utf8'));
      if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong' }));
        return;
      }
      dispatchVMInput(vm, msg);
    } catch (_) {}
  });

  const cleanup = () => {
    vm.viewers.delete(ws);
    vm.off('frame', onFrame);
    vm.off('log', onLog);
    vm.off('open_url', onOpenUrl);
    vm.off('render_state', onRenderState);
  };

  ws.on('close', cleanup);
  ws.on('error', cleanup);
});

const wsHeartbeatTimer = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch (_) {}
  }
}, 30000);
wsHeartbeatTimer.unref();

// ==================== KHỞI ĐỘNG SERVER & KHÔI PHỤC VM 24/7 ====================
async function bootstrap() {
  await vmManager.ensureCompiled();
  ensureDefaultVMForUser(1);
  loadDesiredVMs();

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.PORT, '0.0.0.0', () => {
      server.removeListener('error', reject);
      console.log(`[Server] J2ME Cloud Server đang chạy tại http://127.0.0.1:${config.PORT}`);
      resolve();
    });
  });

  for (const item of desiredRunningVMs.values()) {
    const vm = vmManager.getExistingVM(item.userId, item.vmId);
    if (vm) {
      console.log(`[Bootstrap] Tự động khôi phục Máy Ảo #${item.vmId} của user_${item.userId}...`);
      vmManager.startVM(item.userId, item.vmId).catch((err) => {
        console.error(`[Bootstrap] Lỗi khôi phục VM #${item.vmId}:`, err.message);
      });
    } else {
      unmarkVMDesired(item.userId, item.vmId);
    }
  }

  // Tự động kích hoạt Keep-Alive 24/7 khi chạy trên Render hoặc cấu hình KEEP_ALIVE_URL
  const keepAliveTarget = (process.env.KEEP_ALIVE_URL || process.env.RENDER_EXTERNAL_URL || '').trim().replace(/\/+$/, '');
  if (keepAliveTarget) {
    const https = require('https');
    const pingUrl = `${keepAliveTarget}/api/health`;
    console.log(`[KeepAlive-24/7] Đã bật bộ giữ thức tự động mỗi 5 phút tới: ${pingUrl}`);
    const keepAliveTimer = setInterval(() => {
      try {
        const client = pingUrl.startsWith('https://') ? https : http;
        const req = client.get(pingUrl, { timeout: 10000 }, (res) => {
          res.resume();
        });
        req.on('error', () => {});
      } catch (_) {}
    }, 5 * 60 * 1000);
    if (keepAliveTimer.unref) keepAliveTimer.unref();
  }
}

if (require.main === module) {
  bootstrap().catch((err) => {
    console.error('[FATAL] Khởi động server thất bại:', err);
    process.exit(1);
  });
}

module.exports = { app, server, vmManager, userStore, bootstrap };