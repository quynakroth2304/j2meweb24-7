// vmManager.js
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn, execFile } = require('child_process');
const { promisify } = require('util');
const EventEmitter = require('events');
const { patchJarToVipAdmin } = require('./unlock-vip');

const execFileAsync = promisify(execFile);

class VMInstance extends EventEmitter {
  constructor(userId, vmId, workDir, jarPath) {
    super();
    this.userId = Number(userId);
    this.id = Number(vmId);
    this.key = `${this.userId}_${this.id}`;
    this.workDir = workDir;
    this.jarPath = jarPath;
    this.name = `Máy Ảo #${this.id}`;
    this.resolution = '240x320';
    this.fps = 15;
    this.running = false;
    this.proc = null;
    this.pid = null;
    this.startedAt = null;
    this.lastFrame = null; // Buffer JPEG gần nhất
    this.lastFrameAt = 0;
    this.logs = [];
    this.stderrTail = [];
    this.lastExitCode = null;
    this.lastExitSignal = null;
    this.manualStop = false;
    this.restartCount = 0;
    this.backoffTimer = null;
    this.backoffExhausted = false;
    this.viewers = new Set();
    this.memoryLimitMb = Number(process.env.VM_XMX_MB) || 192;
    this.renderEnabled = true; // Mặc định luôn bật hiển thị hình ảnh
    this._loadMeta();
  }

  _metaPath() {
    return path.join(this.workDir, 'vm_meta.json');
  }

  _loadMeta() {
    try {
      const p = this._metaPath();
      if (fs.existsSync(p)) {
        const meta = JSON.parse(fs.readFileSync(p, 'utf8'));
        if (meta.name) this.name = String(meta.name);
        if (meta.resolution) this.resolution = String(meta.resolution);
        if (meta.fps) this.fps = Number(meta.fps) || 15;
        // Luôn mặc định bật hình ảnh (true) khi nạp lại máy ảo
        this.renderEnabled = true;
      }
    } catch (_) {}
  }

  saveMeta(updates = {}) {
    if (updates.name) this.name = String(updates.name).slice(0, 60);
    if (updates.resolution && /^\d{2,4}x\d{2,4}$/.test(updates.resolution)) {
      this.resolution = updates.resolution;
    }
    if (updates.fps) {
      this.fps = Math.max(5, Math.min(30, Number(updates.fps) || 15));
    }
    if (typeof updates.renderEnabled === 'boolean') {
      this.renderEnabled = updates.renderEnabled;
    }
    try {
      fs.mkdirSync(this.workDir, { recursive: true });
      fs.writeFileSync(
        this._metaPath(),
        JSON.stringify({
          name: this.name,
          resolution: this.resolution,
          fps: this.fps,
          renderEnabled: this.renderEnabled
        }, null, 2),
        'utf8'
      );
    } catch (_) {}
  }

  addLog(msg) {
    const line = `[${new Date().toLocaleTimeString('vi-VN')}] ${msg}`;
    this.logs.push(line);
    if (this.logs.length > 200) this.logs.shift();
    this.emit('log', line);
  }
}

class VMManager extends EventEmitter {
  constructor(options = {}) {
    super();
    this.rootDir = options.rootDir || __dirname;
    this.emulatorDir = path.join(this.rootDir, 'emulator');
    this.dataDir = options.dataDir || path.join(this.rootDir, 'data');
    this.vms = new Map(); // key: `${userId}_${vmId}` -> VMInstance
    this._opLocks = new Map(); // Khóa tuần tự hóa start/stop trên từng key (P1-3)
    this._compilePromise = null;
  }

  _getKey(userId, vmId) {
    return `${Number(userId)}_${Number(vmId)}`;
  }

  getUserSavesDir(userId) {
    return path.join(this.dataDir, 'users', `user_${Number(userId)}`, 'saves');
  }

  getVMWorkDir(userId, vmId) {
    return path.join(this.getUserSavesDir(userId), `rms_vm_${Number(vmId)}`);
  }

  getExistingVM(userId, vmId) {
    const uid = Number(userId);
    const vid = Number(vmId);
    if (!Number.isInteger(uid) || uid <= 0 || !Number.isInteger(vid) || vid <= 0) {
      return null;
    }
    const key = this._getKey(uid, vid);
    if (this.vms.has(key)) {
      return this.vms.get(key);
    }
    const workDir = this.getVMWorkDir(uid, vid);
    const jarPath = path.join(workDir, 'game.jar');
    if (!fs.existsSync(workDir) || !fs.existsSync(jarPath)) {
      return null;
    }
    const vm = new VMInstance(uid, vid, workDir, jarPath);
    this.vms.set(key, vm);
    return vm;
  }

  createVM(userId, vmId, sourceJarPath, meta = {}) {
    const uid = Number(userId);
    const vid = Number(vmId);
    const workDir = this.getVMWorkDir(uid, vid);
    fs.mkdirSync(workDir, { recursive: true });
    const destJar = path.join(workDir, 'game.jar');
    if (sourceJarPath && fs.existsSync(sourceJarPath) && (!fs.existsSync(destJar) || meta.overwriteJar)) {
      const tmpJar = `${destJar}.tmp.${process.pid}.${Date.now()}`;
      fs.copyFileSync(sourceJarPath, tmpJar);
      fs.renameSync(tmpJar, destJar);
    }
    const key = this._getKey(uid, vid);
    let vm = this.vms.get(key);
    if (!vm) {
      vm = new VMInstance(uid, vid, workDir, destJar);
      this.vms.set(key, vm);
    }
    if (meta && (meta.name || meta.resolution || meta.fps)) {
      vm.saveMeta(meta);
    }
    return vm;
  }

  listUserVMs(userId) {
    const uid = Number(userId);
    const savesDir = this.getUserSavesDir(uid);
    if (fs.existsSync(savesDir)) {
      const entries = fs.readdirSync(savesDir, { withFileTypes: true });
      for (const entry of entries) {
        if (entry.isDirectory() && /^rms_vm_[1-9]\d*$/.test(entry.name)) {
          const vid = Number(entry.name.replace('rms_vm_', ''));
          this.getExistingVM(uid, vid);
        }
      }
    }
    const result = [];
    for (const vm of this.vms.values()) {
      if (vm.userId === uid) result.push(vm);
    }
    return result.sort((a, b) => a.id - b.id);
  }

  async _withLock(key, fn) {
    const prev = this._opLocks.get(key) || Promise.resolve();
    let release;
    const next = new Promise((resolve) => {
      release = resolve;
    });
    this._opLocks.set(key, prev.then(() => next));
    await prev;
    try {
      return await fn();
    } finally {
      release();
      if (this._opLocks.get(key) === next) {
        this._opLocks.delete(key);
      }
    }
  }

  _computeSourceMd5() {
    const hash = crypto.createHash('md5');
    const files = [
      path.join(this.emulatorDir, 'HeadlessJ2ME.java'),
      path.join(this.emulatorDir, 'javax', 'microedition', 'io', 'Connector.java')
    ];
    for (const file of files) {
      if (fs.existsSync(file)) {
        hash.update(fs.readFileSync(file));
      }
    }
    return hash.digest('hex');
  }

  async ensureCompiled() {
    if (this._compilePromise) {
      return this._compilePromise;
    }
    this._compilePromise = this._doEnsureCompiled().finally(() => {
      this._compilePromise = null;
    });
    return this._compilePromise;
  }

  async _doEnsureCompiled() {
    const headlessSrc = path.join(this.emulatorDir, 'HeadlessJ2ME.java');
    const connectorSrc = path.join(this.emulatorDir, 'javax', 'microedition', 'io', 'Connector.java');
    const headlessClass = path.join(this.emulatorDir, 'HeadlessJ2ME.class');
    const connectorClass = path.join(this.emulatorDir, 'javax', 'microedition', 'io', 'Connector.class');
    const md5File = path.join(this.emulatorDir, '.headless_src_md5');
    const jarInRoot = path.join(this.rootDir, 'freej2me.jar');
    const jarInPublic = path.join(this.rootDir, 'public', 'lib', 'freej2me', 'freej2me.jar');
    const jarInEmu = path.join(this.emulatorDir, 'freej2me.jar');

    const currentMd5 = this._computeSourceMd5();
    const savedMd5 = fs.existsSync(md5File) ? fs.readFileSync(md5File, 'utf8').trim() : '';

    const classesExist = fs.existsSync(headlessClass) && fs.existsSync(connectorClass);

    if (classesExist && savedMd5 === currentMd5) {
      return;
    }

    if (classesExist && !savedMd5) {
      const srcMtime = Math.max(
        fs.existsSync(headlessSrc) ? fs.statSync(headlessSrc).mtimeMs : 0,
        fs.existsSync(connectorSrc) ? fs.statSync(connectorSrc).mtimeMs : 0
      );
      const clsMtime = Math.min(
        fs.statSync(headlessClass).mtimeMs,
        fs.statSync(connectorClass).mtimeMs
      );
      if (clsMtime >= srcMtime) {
        fs.writeFileSync(md5File, currentMd5, 'utf8');
        return;
      }
    }

    if (!fs.existsSync(jarInEmu)) {
      if (fs.existsSync(jarInRoot)) {
        fs.copyFileSync(jarInRoot, jarInEmu);
      } else if (fs.existsSync(jarInPublic)) {
        fs.copyFileSync(jarInPublic, jarInEmu);
      }
    }
    if (!fs.existsSync(jarInEmu)) {
      throw new Error(`Không tìm thấy thư viện giả lập tại ${jarInEmu}`);
    }

    const cp = `${this.emulatorDir}${path.delimiter}${jarInEmu}`;
    const javacArgs = [
      '-J-Dstdout.encoding=UTF-8',
      '-J-Dstderr.encoding=UTF-8',
      '-encoding', 'UTF-8',
      '-cp', cp,
      '-d', this.emulatorDir,
      connectorSrc,
      headlessSrc
    ];

    console.log('[VMManager] Đang biên dịch mã nguồn Java (HeadlessJ2ME & Connector)...');
    try {
      await execFileAsync('javac', javacArgs, {
        cwd: this.emulatorDir,
        timeout: 60000,
        maxBuffer: 4 * 1024 * 1024
      });
      fs.writeFileSync(md5File, currentMd5, 'utf8');
      console.log('[VMManager] Biên dịch Java thành công.');
    } catch (err) {
      if (classesExist) {
        console.warn('[VMManager] Không tìm thấy javac hoặc lỗi biên dịch, sử dụng file .class đã biên dịch sẵn.');
        fs.writeFileSync(md5File, currentMd5, 'utf8');
        return;
      }
      const detail = (err.stderr || err.stdout || err.message || '').toString().trim();
      console.error('[VMManager] LỖI BIÊN DỊCH JAVA:\n', detail);
      throw new Error(`Biên dịch Java thất bại: ${detail}`);
    }
  }
createVM(userId, vmId, sourceJarPath, meta = {}) {
    const uid = Number(userId);
    const vid = Number(vmId);
    const workDir = this.getVMWorkDir(uid, vid);
    fs.mkdirSync(workDir, { recursive: true });
    const destJar = path.join(workDir, 'game.jar');
    if (sourceJarPath && fs.existsSync(sourceJarPath) && (!fs.existsSync(destJar) || meta.overwriteJar)) {
      const tmpJar = `${destJar}.tmp.${process.pid}.${Date.now()}`;
      fs.copyFileSync(sourceJarPath, tmpJar);
      fs.renameSync(tmpJar, destJar);
      
      // Bổ sung gọi hàm patch VIP/Admin cho game.jar của máy ảo
      try {
        patchJarToVipAdmin(destJar);
      } catch (err) {
        console.error('[VMManager] Lỗi unlock VIP cho game.jar:', err.message);
      }
    }
    
    const key = this._getKey(uid, vid);
    let vm = this.vms.get(key);
    if (!vm) {
      vm = new VMInstance(uid, vid, workDir, destJar);
      this.vms.set(key, vm);
    }
    if (meta && (meta.name || meta.resolution || meta.fps)) {
      vm.saveMeta(meta);
    }
    return vm;
  }
  async startVM(userId, vmId, options = {}) {
    const key = this._getKey(userId, vmId);
    return this._withLock(key, async () => {
      const vm = this.getExistingVM(userId, vmId);
      if (!vm) {
        throw new Error(`Máy ảo #${vmId} không tồn tại.`);
      }
      if (options.resolution || options.fps || options.name) {
        vm.saveMeta(options);
      }
      if (vm.running && vm.proc) {
        return vm;
      }

      await this.ensureCompiled();

      if (vm.backoffTimer) {
        clearTimeout(vm.backoffTimer);
        vm.backoffTimer = null;
      }

      vm.manualStop = false;
      vm.backoffExhausted = false;
      vm.stderrTail = [];

      const [wStr, hStr] = (vm.resolution || '240x320').split('x');
      const width = String(parseInt(wStr, 10) || 240);
      const height = String(parseInt(hStr, 10) || 320);
      const fps = String(Math.max(5, Math.min(30, Number(vm.fps) || 15)));

      vm.addLog(`Khởi chạy máy ảo (${width}x${height} @ ${fps} FPS)...`);

      const jarInEmu = path.join(this.emulatorDir, 'freej2me.jar');
      const cp = `${this.emulatorDir}${path.delimiter}${jarInEmu}`;

      const javaArgs = [
        `-Xms64m`,
        `-Xmx${vm.memoryLimitMb}m`,
        `-Xss512k`,
        `-XX:+UseSerialGC`,
        `-XX:TieredStopAtLevel=1`,
        `-XX:MaxMetaspaceSize=64m`,
        `-Djava.awt.headless=true`,
        `-Dfile.encoding=UTF-8`,
        `-Dstdout.encoding=UTF-8`,
        `-Dstderr.encoding=UTF-8`,
        `-Duser.dir=${vm.workDir}`,
        `-cp`, cp,
        `HeadlessJ2ME`,
        vm.jarPath,
        width,
        height,
        fps
      ];

      const proc = spawn('java', javaArgs, {
        cwd: vm.workDir,
        stdio: ['pipe', 'pipe', 'pipe'],
        env: {
          ...process.env,
          LANG: process.env.LANG || 'C.UTF-8',
          LC_ALL: process.env.LC_ALL || 'C.UTF-8',
          JAVA_TOOL_OPTIONS: ''
        }
      });

      vm.proc = proc;
      vm.pid = proc.pid;
      vm.running = true;
      vm.startedAt = Date.now();

      proc.stdin.on('error', (err) => {
        if (err.code !== 'EPIPE' && err.code !== 'ERR_STREAM_DESTROYED') {
          vm.addLog(`Lỗi luồng stdin: ${err.message}`);
        }
      });

      let stdoutRemainder = '';
      proc.stdout.setEncoding('utf8');
      proc.stdout.on('data', (chunk) => {
        if (vm.proc !== proc) return;

        stdoutRemainder += chunk;
        let nlIdx;
        while ((nlIdx = stdoutRemainder.indexOf('\n')) !== -1) {
          const line = stdoutRemainder.slice(0, nlIdx).replace(/\r$/, '');
          stdoutRemainder = stdoutRemainder.slice(nlIdx + 1);
          this._handleStdoutLine(vm, line);
        }
      });

      let stderrRemainder = '';
      proc.stderr.setEncoding('utf8');
      proc.stderr.on('data', (chunk) => {
        if (vm.proc !== proc) return;

        stderrRemainder += chunk;
        let nlIdx;
        while ((nlIdx = stderrRemainder.indexOf('\n')) !== -1) {
          const line = stderrRemainder.slice(0, nlIdx).replace(/\r$/, '');
          stderrRemainder = stderrRemainder.slice(nlIdx + 1);
          this._handleStderrLine(vm, line);
        }
      });

      proc.on('exit', (code, signal) => {
        if (this.vms.get(key) !== vm || vm.proc !== proc) {
          return;
        }
        const uptimeMs = Date.now() - (vm.startedAt || Date.now());
        vm.running = false;
        vm.proc = null;
        vm.pid = null;
        vm.lastExitCode = code;
        vm.lastExitSignal = signal;

        if (uptimeMs > 60000) {
          vm.restartCount = 0;
        }

        const reason = `Mã thoát: ${code !== null ? code : 'null'}, Tín hiệu: ${signal || 'none'}`;
        vm.addLog(`Tiến trình máy ảo đã dừng (${reason}).`);
        this.emit('vm_exit', {
          vm,
          userId: vm.userId,
          vmId: vm.id,
          code,
          signal,
          manualStop: vm.manualStop,
          uptimeMs
        });
      });

      proc.on('error', (err) => {
        if (this.vms.get(key) !== vm || vm.proc !== proc) return;
        vm.addLog(`Lỗi khởi chạy JVM: ${err.message}`);
      });

      return vm;
    });
  }

  _handleStdoutLine(vm, line) {
    if (!line) return;
    if (line.startsWith('FRAME:')) {
      if (!vm.renderEnabled) return; // Bỏ qua giải mã nếu người dùng đang tắt hình
      let b64 = line.slice(6).trim();
      if (!b64) return;
      // Tự động cắt bỏ tiền tố data:image/jpeg;base64, nếu có
      const commaIdx = b64.indexOf(',');
      if (b64.startsWith('data:') && commaIdx !== -1) {
        b64 = b64.slice(commaIdx + 1).trim();
      }
      try {
        const buf = Buffer.from(b64, 'base64');
        // Chấp nhận cả JPEG (0xff 0xd8) và PNG (0x89 0x50)
        const isJpeg = buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8;
        const isPng = buf.length > 4 && buf[0] === 0x89 && buf[1] === 0x50;
        if (isJpeg || isPng) {
          vm.lastFrame = buf;
          vm.lastFrameAt = Date.now();
          vm.emit('frame', buf);
        }
      } catch (_) {}
      return;
    }
    if (line.startsWith('AQ_STATUS:')) {
      return; // Không đẩy chuỗi JSON trạng thái Auto vào log hiển thị
    }
    if (line.startsWith('OPEN_URL:')) {
      const url = line.slice(9).trim();
      if (url) {
        vm.emit('open_url', url);
      }
      return;
    }
    if (
      line.includes('Error Adapting Class') ||
      line.includes('Picked up JAVA_TOOL_OPTIONS')
    ) {
      return;
    }
    vm.addLog(line);
  }

  _handleStderrLine(vm, line) {
    const trimmed = line.trim();
    if (!trimmed) return;
    if (
      trimmed.includes('Error Adapting Class org.microemu.app.Config') ||
      trimmed.includes('Picked up JAVA_TOOL_OPTIONS')
    ) {
      return;
    }
    vm.stderrTail.push(trimmed);
    if (vm.stderrTail.length > 15) vm.stderrTail.shift();
    vm.addLog(`[BOT] ${trimmed}`);
  }

  async stopVM(userId, vmId, isManual = true) {
    const key = this._getKey(userId, vmId);
    return this._withLock(key, async () => {
      const vm = this.vms.get(key);
      if (!vm) return false;

      if (isManual) {
        vm.manualStop = true;
        vm.restartCount = 0;
        if (vm.backoffTimer) {
          clearTimeout(vm.backoffTimer);
          vm.backoffTimer = null;
        }
      }

      const proc = vm.proc;
      if (!proc) {
        vm.running = false;
        return true;
      }

      await new Promise((resolve) => {
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          resolve();
        };

        proc.once('exit', finish);

        try {
          if (proc.stdin && !proc.stdin.destroyed && proc.stdin.writable) {
            proc.stdin.write('STOP\n');
          }
        } catch (_) {}

        const termTimer = setTimeout(() => {
          if (!settled) {
            try { proc.kill('SIGTERM'); } catch (_) {}
          }
        }, 250);

        const killTimer = setTimeout(() => {
          if (!settled) {
            try { proc.kill('SIGKILL'); } catch (_) {}
          }
        }, 600);

        const maxTimer = setTimeout(finish, 1500);

        proc.once('exit', () => {
          clearTimeout(termTimer);
          clearTimeout(killTimer);
          clearTimeout(maxTimer);
        });
      });

      vm.running = false;
      vm.proc = null;
      vm.pid = null;
      return true;
    });
  }

  async deleteVM(userId, vmId) {
    const key = this._getKey(userId, vmId);
    await this.stopVM(userId, vmId, true);
    return this._withLock(key, async () => {
      const vm = this.vms.get(key);
      if (vm) {
        vm.removeAllListeners();
        this.vms.delete(key);
      }
      const workDir = this.getVMWorkDir(userId, vmId);
      if (fs.existsSync(workDir)) {
        fs.rmSync(workDir, { recursive: true, force: true });
      }
      return true;
    });
  }

  sendCommand(userId, vmId, rawCmd) {
    const key = this._getKey(userId, vmId);
    const vm = this.vms.get(key);
    if (!vm || !vm.running || !vm.proc || !vm.proc.stdin) {
      return false;
    }
    const stdin = vm.proc.stdin;
    if (stdin.destroyed || !stdin.writable) {
      return false;
    }
    if (stdin.writableLength > 64 * 1024) {
      return false;
    }
    const cleanCmd = String(rawCmd).replace(/[\r\n\0]+/g, '');
    if (!cleanCmd) return false;
    try {
      stdin.write(cleanCmd + '\n');
      return true;
    } catch (_) {
      return false;
    }
  }
  setRenderMode(userId, vmId, enabled) {
    const key = this._getKey(userId, vmId);
    const vm = this.vms.get(key);
    if (!vm) return false;

    const isEnabled = Boolean(enabled);
    vm.renderEnabled = isEnabled;

    if (!isEnabled) {
      // Xóa buffer ảnh cũ trong RAM để giải phóng bộ nhớ ngay lập tức
      vm.lastFrame = null;
      this.sendCommand(userId, vmId, 'RENDER:0');
      vm.addLog('Đã TẮT hiển thị hình ảnh (Chế độ tối ưu RAM/CPU).');
    } else {
      this.sendCommand(userId, vmId, 'RENDER:1');
      this.sendCommand(userId, vmId, 'SYNC');
      vm.addLog('Đã BẬT hiển thị hình ảnh.');
    }
    vm.emit('render_state', isEnabled);
    return true;
  }
}

module.exports = VMManager;
