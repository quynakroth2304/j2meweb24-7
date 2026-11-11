// src/commerceStore.js — Hệ Thống Đồng Bộ Tài Khoản, Ví Tiền, QR Techcombank, Thuê 24/7 & Up Thuê, Sự Kiện Admin (Duy Trường)
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const BANK_CONFIG = {
  bankId: 'TCB',
  bankBin: '970407',
  bankName: 'Techcombank (Ngân hàng TMCP Kỹ Thương Việt Nam)',
  accountNo: '9823042007',
  accountName: 'NGUYEN DUC DUY TRUONG',
  accountNameDisplay: 'NGUYỄN ĐỨC DUY TRƯỜNG',
  template: 'compact2'
};

// Danh sách gói cước chuẩn (Theo Ngày, Theo Tuần, Theo Tháng — Tối đa 3 tháng / 1 lần thuê)
// Bảng giá chuẩn: 1 ngày = 3k (3.000đ) | 1 tuần = 15k (15.000đ) | 1 tháng = 40k (40.000đ)
const RENTAL_PLANS = [
  { id: 'day_1', name: 'Gói 1 Ngày', durationLabel: '1 Ngày', unit: 'day', count: 1, days: 1, basePriceVnd: 3000, badge: '3k / Ngày' },
  { id: 'day_3', name: 'Gói 3 Ngày', durationLabel: '3 Ngày', unit: 'day', count: 3, days: 3, basePriceVnd: 9000, badge: 'Ngắn hạn' },
  { id: 'week_1', name: 'Gói 1 Tuần (7 Ngày)', durationLabel: '1 Tuần (7 Ngày)', unit: 'week', count: 1, days: 7, basePriceVnd: 15000, badge: '15k / Tuần' },
  { id: 'week_2', name: 'Gói 2 Tuần (14 Ngày)', durationLabel: '2 Tuần (14 Ngày)', unit: 'week', count: 2, days: 14, basePriceVnd: 30000, badge: 'Tiết kiệm' },
  { id: 'month_1', name: 'Gói 1 Tháng (30 Ngày)', durationLabel: '1 Tháng (30 Ngày)', unit: 'month', count: 1, days: 30, basePriceVnd: 40000, badge: '40k / Tháng' },
  { id: 'month_2', name: 'Gói 2 Tháng (60 Ngày)', durationLabel: '2 Tháng (60 Ngày)', unit: 'month', count: 2, days: 60, basePriceVnd: 80000, badge: 'Ưu đãi lớn' },
  { id: 'month_3', name: 'Gói 3 Tháng (90 Ngày - Tối Đa)', durationLabel: '3 Tháng (Tối Đa 90 Ngày)', unit: 'month', count: 3, days: 90, basePriceVnd: 120000, badge: 'VIP Tối Đa 3 Tháng' }
];

const SERVICE_TYPES = [
  {
    id: 'TREO_247',
    name: 'Thuê Bật Treo 24/7 & Key VIP ADMIN',
    description: 'Mở khóa Key VIP ADMIN Duy Trường + Treo giữ mạng 24/7 trên Server & App + Admin hỗ trợ cấu hình tận nơi'
  },
  {
    id: 'UP_LEVEL',
    name: 'Up Thuê Level / Cày Cấp 24/7',
    description: 'Nhập TK & MK Game để Admin Duy Trường trực tiếp treo cày cấp độ, khóa bãi chuẩn theo level 24/7'
  },
  {
    id: 'UP_QUEST',
    name: 'Làm Thuê Nhiệm Vụ & Phó Bản',
    description: 'Admin hỗ trợ chạy chuỗi nhiệm vụ chính tuyến, nhiệm vụ hằng ngày, vượt phó bản & săn nguyên liệu'
  },
  {
    id: 'UP_CUSTOM',
    name: 'Combo Treo 24/7 + Up Thuê Toàn Diện',
    description: 'Bao trọn gói vừa cấp Key VIP sử dụng trên Web/App vừa được Admin chăm sóc acc game 24/7'
  }
];

class CommerceStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.filePath = path.join(this.dataDir, 'commerce_hub.json');
    fs.mkdirSync(this.dataDir, { recursive: true });
    this._state = this._load();
  }

  _defaultState() {
    return {
      eventConfig: {
        active: true,
        title: '🎉 SỰ KIỆN TRI ÂN KHÁCH HÀNG DUY TRƯỜNG',
        bannerText: '🔥 Bảng giá chuẩn: 1 Ngày 3k • 1 Tuần 15k • 1 Tháng 40k (Tối đa 3 tháng/lần) & Khuyến mãi X2 giá trị nạp qua QR Techcombank!',
        discountPercent: 0,
        topupMultiplier: 2.0,
        updatedAt: new Date().toISOString()
      },
      wallets: {},
      topupOrders: [],
      serviceOrders: [],
      clientHeartbeats: {}
    };
  }

  _load() {
    if (!fs.existsSync(this.filePath)) {
      return this._defaultState();
    }
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
      return {
        ...this._defaultState(),
        ...parsed,
        eventConfig: {
          ...this._defaultState().eventConfig,
          ...(parsed.eventConfig || {})
        }
      };
    } catch (err) {
      console.error('[CommerceStore] Lỗi đọc commerce_hub.json:', err.message);
      return this._defaultState();
    }
  }

  _saveAtomic() {
    const tmp = `${this.filePath}.tmp.${process.pid}.${Date.now()}`;
    fs.writeFileSync(tmp, JSON.stringify(this._state, null, 2), 'utf8');
    fs.renameSync(tmp, this.filePath);
  }

  getBankQrUrl(amountVnd, transferContent) {
    const amt = Math.max(0, Math.round(Number(amountVnd) || 0));
    const info = encodeURIComponent(String(transferContent || 'NAP DUYTRUONG').trim());
    const accName = encodeURIComponent(BANK_CONFIG.accountName);
    return `https://img.vietqr.io/image/${BANK_CONFIG.bankId}-${BANK_CONFIG.accountNo}-${BANK_CONFIG.template}.png?amount=${amt}&addInfo=${info}&accountName=${accName}`;
  }

  getWallet(userId, username = '') {
    const key = String(userId);
    if (!this._state.wallets[key]) {
      const isRootAdmin = Number(userId) === 1 || username === 'admin';
      this._state.wallets[key] = {
        userId: Number(userId),
        username: username || `user_${userId}`,
        balanceVnd: isRootAdmin ? 999999000 : 0,
        totalTopupVnd: isRootAdmin ? 999999000 : 0,
        vipKey: isRootAdmin ? 'DT-ADMIN-LIFETIME-247' : '',
        vipExpiresAt: isRootAdmin ? new Date(Date.now() + 365 * 24 * 3600 * 1000).toISOString() : null,
        active247Enabled: isRootAdmin,
        lastPlatform: 'Web / App',
        lastActiveAt: new Date().toISOString(),
        gameAccountPreview: ''
      };
      this._saveAtomic();
    } else if (username && this._state.wallets[key].username !== username) {
      this._state.wallets[key].username = username;
    }
    const w = this._state.wallets[key];
    const now = Date.now();
    const expMs = w.vipExpiresAt ? Date.parse(w.vipExpiresAt) : 0;
    const isVipActive = Boolean(Number(userId) === 1 || (expMs && expMs > now));
    return {
      ...w,
      isVipActive,
      remainingDays: expMs > now ? Math.ceil((expMs - now) / (24 * 3600 * 1000)) : 0
    };
  }

  recordClientSync(userId, username, payload = {}) {
    const w = this.getWallet(userId, username);
    const key = String(userId);
    const target = this._state.wallets[key];
    if (payload.platform) target.lastPlatform = String(payload.platform);
    if (payload.gameAccount) target.gameAccountPreview = String(payload.gameAccount);
    target.lastActiveAt = new Date().toISOString();

    const prevHb = this._state.clientHeartbeats[key] || {};
    const frameDataUrl = (typeof payload.frameDataUrl === 'string' && payload.frameDataUrl.startsWith('data:image/'))
      ? payload.frameDataUrl
      : (prevHb.frameDataUrl || '');

    this._state.clientHeartbeats[key] = {
      userId: Number(userId),
      username: username || w.username,
      platform: String(payload.platform || target.lastPlatform || 'Android App'),
      activeSlots: Number(payload.activeSlots || payload.activeSlot || 1),
      activeSlot: Number(payload.activeSlot || 1),
      autoQuest: Boolean(payload.autoQuest ?? payload.autoQuestActive ?? true),
      mapSummary: String(payload.mapSummary || payload.mapName || 'Map 17: Hang Dơi (Lv.39)'),
      mapName: String(payload.mapName || payload.mapSummary || 'Map 17: Hang Dơi'),
      level: Number(payload.level || 39),
      gameUsername: String(payload.gameUsername || target.gameAccountPreview || ''),
      frameDataUrl,
      updatedAt: new Date().toISOString()
    };
    if (frameDataUrl) {
      this.setLiveFrame(Number(payload.activeSlot || 1), {
        slotId: Number(payload.activeSlot || 1),
        frameDataUrl,
        username: username || w.username,
        charName: payload.charName || payload.gameUsername || 'HiệpSĩ_Alpha',
        level: Number(payload.level || 39),
        mapName: String(payload.mapName || payload.mapSummary || 'Hang Dơi'),
        stateText: String(payload.stateText || 'Đang cắm bãi 24/7'),
        updatedAt: Date.now()
      });
    }
    this._saveAtomic();
    return this.getWallet(userId, username);
  }

  setLiveFrame(slotId, data = {}) {
    if (!this._liveFrames) this._liveFrames = new Map();
    const sid = Number(slotId || 1);
    this._liveFrames.set(sid, {
      ...(this._liveFrames.get(sid) || {}),
      ...data,
      slotId: sid,
      updatedAt: Date.now()
    });
  }

  getLiveFrame(slotId = 1) {
    if (!this._liveFrames) this._liveFrames = new Map();
    return this._liveFrames.get(Number(slotId || 1)) || null;
  }

  getEventConfig() {
    return this._state.eventConfig;
  }

  updateEventConfig(patch = {}) {
    const cur = this._state.eventConfig;
    if (patch.active !== undefined) cur.active = Boolean(patch.active);
    if (typeof patch.title === 'string' && patch.title.trim()) cur.title = patch.title.trim();
    if (typeof patch.bannerText === 'string' && patch.bannerText.trim()) cur.bannerText = patch.bannerText.trim();
    if (patch.discountPercent !== undefined) {
      cur.discountPercent = Math.max(0, Math.min(90, Math.round(Number(patch.discountPercent) || 0)));
    }
    if (patch.topupMultiplier !== undefined) {
      cur.topupMultiplier = Math.max(1, Math.min(10, Number(patch.topupMultiplier) || 1));
    }
    cur.updatedAt = new Date().toISOString();
    this._saveAtomic();
    return cur;
  }

  getCatalogWithPricing() {
    const ev = this._state.eventConfig;
    const discount = ev.active ? (Number(ev.discountPercent) || 0) : 0;
    const plans = RENTAL_PLANS.map((p) => {
      const finalPriceVnd = Math.round((p.basePriceVnd * (100 - discount)) / 100);
      return {
        ...p,
        originalPriceVnd: p.basePriceVnd,
        discountPercent: discount,
        finalPriceVnd
      };
    });
    return {
      bankConfig: BANK_CONFIG,
      eventConfig: ev,
      plans,
      serviceTypes: SERVICE_TYPES,
      maxMonthsPerOrder: 3
    };
  }

  createTopupOrder(userId, username, amountVnd, clientPlatform = 'Web') {
    const rawAmount = Math.round(Number(amountVnd) || 0);
    if (rawAmount < 3000 || rawAmount > 50000000) {
      throw new Error('Số tiền nạp phải từ 3.000đ đến 50.000.000đ.');
    }
    const ev = this._state.eventConfig;
    const multiplier = ev.active ? Math.max(1, Number(ev.topupMultiplier) || 1) : 1;
    const creditedAmountVnd = Math.round(rawAmount * multiplier);
    const code = `DT${userId}${Math.floor(1000 + Math.random() * 9000)}`;
    const transferContent = `NAP ${username.toUpperCase()} ${code}`;
    const qrImageUrl = this.getBankQrUrl(rawAmount, transferContent);

    const order = {
      id: `topup_${Date.now()}_${crypto.randomBytes(3).toString('hex')}`,
      code,
      userId: Number(userId),
      username,
      amountVnd: rawAmount,
      multiplier,
      creditedAmountVnd,
      transferContent,
      qrImageUrl,
      bankName: BANK_CONFIG.bankName,
      accountNo: BANK_CONFIG.accountNo,
      accountName: BANK_CONFIG.accountNameDisplay,
      platform: clientPlatform,
      status: 'PENDING', // PENDING | APPROVED | REJECTED
      createdAt: new Date().toISOString(),
      processedAt: null,
      adminNote: ''
    };

    this._state.topupOrders.unshift(order);
    if (this._state.topupOrders.length > 500) {
      this._state.topupOrders.length = 500;
    }
    this._saveAtomic();
    return order;
  }

  processTopupOrder(orderId, action, adminNote = '') {
    const order = this._state.topupOrders.find((o) => o.id === orderId || o.code === orderId);
    if (!order) throw new Error('Không tìm thấy đơn nạp tiền.');
    if (order.status !== 'PENDING') {
      throw new Error(`Đơn nạp này đã được xử lý trước đó (${order.status}).`);
    }

    const act = String(action || '').toUpperCase();
    if (act === 'APPROVE' || act === 'APPROVED') {
      order.status = 'APPROVED';
      order.processedAt = new Date().toISOString();
      order.adminNote = adminNote || 'Admin Duy Trường đã duyệt nạp tiền';

      const w = this.getWallet(order.userId, order.username);
      const key = String(order.userId);
      this._state.wallets[key].balanceVnd = (this._state.wallets[key].balanceVnd || 0) + order.creditedAmountVnd;
      this._state.wallets[key].totalTopupVnd = (this._state.wallets[key].totalTopupVnd || 0) + order.amountVnd;
      this._saveAtomic();
      return { order, wallet: this.getWallet(order.userId, order.username) };
    } else {
      order.status = 'REJECTED';
      order.processedAt = new Date().toISOString();
      order.adminNote = adminNote || 'Từ chối đơn nạp';
      this._saveAtomic();
      return { order, wallet: this.getWallet(order.userId, order.username) };
    }
  }

  adminAdjustBalance(userId, username, deltaVnd, grantDays = 0, note = '') {
    this.getWallet(userId, username);
    const key = String(userId);
    const target = this._state.wallets[key];
    if (Number.isFinite(Number(deltaVnd)) && Number(deltaVnd) !== 0) {
      target.balanceVnd = Math.max(0, (target.balanceVnd || 0) + Math.round(Number(deltaVnd)));
    }
    if (Number(grantDays) > 0) {
      const days = Math.min(90, Math.round(Number(grantDays))); // Tối đa 3 tháng (90 ngày) / lần
      const now = Date.now();
      const baseMs = target.vipExpiresAt && Date.parse(target.vipExpiresAt) > now
        ? Date.parse(target.vipExpiresAt)
        : now;
      target.vipExpiresAt = new Date(baseMs + days * 24 * 3600 * 1000).toISOString();
      if (!target.vipKey) {
        target.vipKey = `DT-VIP-${crypto.randomBytes(3).toString('hex').toUpperCase()}`;
      }
      target.active247Enabled = true;
    }
    this._saveAtomic();
    return this.getWallet(userId, username);
  }

  createServiceOrKeyOrder(userId, username, payload = {}) {
    const planId = String(payload.planId || 'month_1');
    const serviceType = String(payload.serviceType || 'TREO_247');
    const gameUsername = String(payload.gameUsername || '').trim();
    const gamePassword = String(payload.gamePassword || '').trim();
    const gameServer = String(payload.gameServer || 'Server 1 - Chiến Thần').trim();
    const charName = String(payload.charName || '').trim();
    const customerNote = String(payload.note || '').trim();
    const platform = String(payload.platform || 'Web / App');

    const plan = RENTAL_PLANS.find((p) => p.id === planId);
    if (!plan) {
      throw new Error('Gói thời hạn không hợp lệ.');
    }
    if (plan.days > 90) {
      throw new Error('Chỉ được thuê tối đa 3 tháng (90 ngày) cho mỗi lần đăng ký.');
    }

    const svc = SERVICE_TYPES.find((s) => s.id === serviceType) || SERVICE_TYPES[0];
    if (!gameUsername || !gamePassword) {
      throw new Error('Vui lòng nhập đầy đủ Tài khoản Game và Mật khẩu Game để Admin hỗ trợ bật 24/7 & Up thuê!');
    }

    const ev = this._state.eventConfig;
    const discount = ev.active ? (Number(ev.discountPercent) || 0) : 0;
    const finalPriceVnd = Math.round((plan.basePriceVnd * (100 - discount)) / 100);

    const w = this.getWallet(userId, username);
    if (w.balanceVnd < finalPriceVnd) {
      throw new Error(
        `Số dư không đủ (Cần ${finalPriceVnd.toLocaleString('vi-VN')}đ, hiện có ${w.balanceVnd.toLocaleString('vi-VN')}đ). Vui lòng nạp thêm qua QR Techcombank!`
      );
    }

    // Trừ tiền & cấp Key VIP + kích hoạt 24/7 ngay lập tức
    const key = String(userId);
    const target = this._state.wallets[key];
    target.balanceVnd -= finalPriceVnd;

    const now = Date.now();
    const currentExp = target.vipExpiresAt && Date.parse(target.vipExpiresAt) > now
      ? Date.parse(target.vipExpiresAt)
      : now;

    // Kiểm tra tổng thời gian cộng dồn không vượt quá 90 ngày (3 tháng) tính từ hiện tại nếu người dùng thuê tiếp
    const maxAllowedExp = now + 90 * 24 * 3600 * 1000;
    const newExp = Math.min(maxAllowedExp, currentExp + plan.days * 24 * 3600 * 1000);
    target.vipExpiresAt = new Date(newExp).toISOString();
    if (!target.vipKey) {
      target.vipKey = `DT-VIP-${crypto.randomBytes(4).toString('hex').toUpperCase()}`;
    }
    target.active247Enabled = true;
    target.gameAccountPreview = `${gameUsername} (${gameServer})`;

    const order = {
      id: `srv_${Date.now()}_${crypto.randomBytes(3).toString('hex')}`,
      userId: Number(userId),
      username,
      serviceType: svc.id,
      serviceName: svc.name,
      planId: plan.id,
      planName: plan.name,
      days: plan.days,
      basePriceVnd: plan.basePriceVnd,
      discountPercent: discount,
      pricePaidVnd: finalPriceVnd,
      vipKeyIssued: target.vipKey,
      expiresAt: target.vipExpiresAt,
      // Thông tin tài khoản game để Admin hỗ trợ trực tiếp
      gameUsername,
      gamePassword,
      gameServer,
      charName,
      customerNote,
      platform,
      status: 'ACTIVE_247', // ACTIVE_247 | IN_PROGRESS | COMPLETED | PAUSED
      adminResponse: 'Đã kích hoạt Key VIP 24/7 tự động. Admin Duy Trường đã nhận TK/MK game để hỗ trợ!',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    this._state.serviceOrders.unshift(order);
    if (this._state.serviceOrders.length > 500) {
      this._state.serviceOrders.length = 500;
    }
    this._saveAtomic();

    return {
      order,
      wallet: this.getWallet(userId, username)
    };
  }

  updateServiceOrderStatus(orderId, status, adminResponse = '') {
    const order = this._state.serviceOrders.find((o) => o.id === orderId);
    if (!order) throw new Error('Không tìm thấy đơn đăng ký dịch vụ.');
    if (status) order.status = String(status).toUpperCase();
    if (adminResponse !== undefined) order.adminResponse = String(adminResponse);
    order.updatedAt = new Date().toISOString();
    this._saveAtomic();
    return order;
  }

  getUserTransactions(userId) {
    const uid = Number(userId);
    return {
      topupOrders: this._state.topupOrders.filter((o) => o.userId === uid),
      serviceOrders: this._state.serviceOrders.filter((o) => o.userId === uid)
    };
  }

  getAdminFullSnapshot(allUsers = []) {
    const enrichedUsers = allUsers.map((u) => {
      const w = this.getWallet(u.id, u.username);
      const hb = this._state.clientHeartbeats[String(u.id)] || null;
      return {
        ...u,
        wallet: w,
        liveStatus: hb
      };
    });
    return {
      bankConfig: BANK_CONFIG,
      eventConfig: this._state.eventConfig,
      users: enrichedUsers,
      topupOrders: this._state.topupOrders,
      serviceOrders: this._state.serviceOrders,
      clientHeartbeats: Object.values(this._state.clientHeartbeats)
    };
  }
}

module.exports = {
  CommerceStore,
  BANK_CONFIG,
  RENTAL_PLANS,
  SERVICE_TYPES
};
