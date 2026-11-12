// src/shared/dualSessionStore.js — Quản Lý Phiên Mạng Kép (Dual-Connection), Refresh Token, Đồng Bộ Bản PC Desktop & Hàng Đợi Offline
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const jwt = require('jsonwebtoken');
const config = require('../../config');

const SESSION_GRACE_PERIOD_MS = 30 * 60 * 1000; // Giữ phiên sống 30 phút kể từ lần cuối thiết bị hoạt động
const REFRESH_TOKEN_TTL_MS = 30 * 24 * 3600 * 1000; // Refresh token sống 30 ngày

class DualSessionStore {
  constructor(dataDir = config.DATA_DIR) {
    this.dataDir = dataDir;
    this.filePath = path.join(this.dataDir, 'dual_sessions.json');
    fs.mkdirSync(this.dataDir, { recursive: true });
    this._state = this._load();
  }

  _load() {
    if (!fs.existsSync(this.filePath)) {
      return {
        refreshTokens: {},
        pcSessions: {},
        processedActions: {}
      };
    }
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
      return {
        refreshTokens: parsed.refreshTokens || {},
        pcSessions: parsed.pcSessions || {},
        processedActions: parsed.processedActions || {}
      };
    } catch (_) {
      return { refreshTokens: {}, pcSessions: {}, processedActions: {} };
    }
  }

  _saveAtomic() {
    try {
      const tmp = `${this.filePath}.tmp.${process.pid}.${Date.now()}`;
      fs.writeFileSync(tmp, JSON.stringify(this._state, null, 2), 'utf8');
      fs.renameSync(tmp, this.filePath);
    } catch (_) {}
  }

  _formatPlatform(deviceInfo) {
    if (!deviceInfo) return 'Web / Android / PC';
    if (typeof deviceInfo === 'string') return deviceInfo;
    if (typeof deviceInfo === 'object') {
      return String(deviceInfo.platform || deviceInfo.userAgent || 'Web / Android / PC').slice(0, 80);
    }
    return 'Web / Android / PC';
  }

  issueTokenPair(user, deviceInfo = '') {
    const accessToken = jwt.sign(
      { id: user.id, username: user.username, role: user.role },
      config.JWT_SECRET,
      { expiresIn: config.JWT_EXPIRES_IN || '7d' }
    );

    const refreshToken = `rtk_${user.id}_${crypto.randomBytes(24).toString('hex')}`;
    const now = Date.now();
    const platformStr = this._formatPlatform(deviceInfo);

    this._state.refreshTokens[refreshToken] = {
      userId: Number(user.id),
      username: user.username,
      role: user.role,
      platform: platformStr,
      deviceInfo: platformStr,
      issuedAt: now,
      lastSeenAt: now,
      graceUntil: now + SESSION_GRACE_PERIOD_MS,
      expiresAt: now + REFRESH_TOKEN_TTL_MS,
      revoked: false
    };
    this._saveAtomic();

    return {
      accessToken,
      refreshToken,
      gracePeriodMs: SESSION_GRACE_PERIOD_MS,
      gracePeriodSec: Math.round(SESSION_GRACE_PERIOD_MS / 1000),
      expiresAt: now + REFRESH_TOKEN_TTL_MS
    };
  }

  touchSessionByUser(userId, platformOverride = '') {
    const now = Date.now();
    for (const rec of Object.values(this._state.refreshTokens)) {
      if (rec.userId === Number(userId) && !rec.revoked) {
        rec.lastSeenAt = now;
        rec.graceUntil = now + SESSION_GRACE_PERIOD_MS;
        if (platformOverride) {
          rec.platform = String(platformOverride);
          rec.deviceInfo = String(platformOverride);
        }
      }
    }
  }

  /**
   * Ghi nhận & duy trì phiên Mạng Kép 24/7 cho người chơi Bản Máy Tính (PC Desktop Launcher)
   */
  recordPcClientSession(payload = {}) {
    if (!this._state.pcSessions) this._state.pcSessions = {};
    const now = Date.now();
    const pcId = String(payload.pcClientId || payload.gameUsername || payload.username || 'pc_default').replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 64);
    const prev = this._state.pcSessions[pcId] || {};

    this._state.pcSessions[pcId] = {
      pcClientId: pcId,
      userId: Number(payload.userId || prev.userId || 1),
      username: String(payload.username || payload.gameUsername || prev.username || `PC_${pcId}`),
      gameUsername: String(payload.gameUsername || prev.gameUsername || ''),
      charName: String(payload.charName || prev.charName || ''),
      level: Number(payload.level ?? prev.level ?? 1),
      mapName: String(payload.mapName || prev.mapName || 'Đang trong game'),
      autoQuest: Boolean(payload.autoQuest ?? prev.autoQuest ?? false),
      questScope: String(payload.questScope || prev.questScope || 'ALL'),
      cloudMaintain247: Boolean(payload.cloudMaintain247 ?? prev.cloudMaintain247 ?? true),
      stateText: String(payload.stateText || prev.stateText || 'Đang kết nối Mạng Kép PC 24/7'),
      platform: String(payload.platform || 'PC Desktop (Windows/Mac/Linux)'),
      role: String(payload.role || prev.role || 'user'),
      lastSeenAt: now,
      graceUntil: now + 24 * 60 * 60 * 1000 // Giữ phiên 24h cho máy tính
    };
    this._saveAtomic();
    return this._state.pcSessions[pcId];
  }

  listPcSessions() {
    if (!this._state.pcSessions) return [];
    const now = Date.now();
    return Object.values(this._state.pcSessions)
      .sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0))
      .map((s) => ({
        ...s,
        withinGracePeriod: (s.graceUntil || 0) > now,
        idleSeconds: Math.max(0, Math.round((now - (s.lastSeenAt || now)) / 1000))
      }));
  }

  getSessionStatus(userId) {
    const uid = Number(userId);
    const now = Date.now();
    let latestRec = null;
    for (const rec of Object.values(this._state.refreshTokens)) {
      if (rec.userId === uid && !rec.revoked && rec.expiresAt > now) {
        if (!latestRec || (rec.lastSeenAt || 0) > (latestRec.lastSeenAt || 0)) {
          latestRec = rec;
        }
      }
    }
    const graceUntil = latestRec ? (latestRec.graceUntil || (now + SESSION_GRACE_PERIOD_MS)) : (now + SESSION_GRACE_PERIOD_MS);
    return {
      userId: uid,
      online: true,
      dualNetworkActive: true,
      withinGracePeriod: graceUntil > now,
      idleSeconds: latestRec ? Math.max(0, Math.round((now - (latestRec.lastSeenAt || now)) / 1000)) : 0,
      lastSeenAt: latestRec ? new Date(latestRec.lastSeenAt).toISOString() : new Date(now).toISOString(),
      graceUntil: new Date(graceUntil).toISOString(),
      graceRemainingSec: Math.max(0, Math.round((graceUntil - now) / 1000)),
      gracePeriodMs: SESSION_GRACE_PERIOD_MS
    };
  }

  listAllActiveSessions() {
    const now = Date.now();
    const byKey = new Map();

    for (const rec of Object.values(this._state.refreshTokens)) {
      if (!rec || rec.revoked || (rec.expiresAt && rec.expiresAt <= now)) continue;
      const key = `user_${rec.userId}`;
      const prev = byKey.get(key);
      if (!prev || (rec.lastSeenAt || 0) > (prev.lastSeenAt || 0)) {
        const graceUntil = rec.graceUntil || ((rec.lastSeenAt || now) + SESSION_GRACE_PERIOD_MS);
        const withinGracePeriod = graceUntil > now;
        const platformStr = this._formatPlatform(rec.platform || rec.deviceInfo);
        byKey.set(key, {
          userId: rec.userId,
          username: rec.username,
          role: rec.role || 'user',
          platform: platformStr,
          deviceInfo: platformStr,
          withinGracePeriod,
          idleSeconds: Math.max(0, Math.round((now - (rec.lastSeenAt || now)) / 1000)),
          lastSeenAt: new Date(rec.lastSeenAt || now).toISOString(),
          graceUntil: new Date(graceUntil).toISOString(),
          graceRemainingSec: Math.max(0, Math.round((graceUntil - now) / 1000)),
          status: withinGracePeriod ? 'ONLINE_OR_GRACE' : 'IDLE_SAVED'
        });
      }
    }

    // Gộp cả các phiên Bản Máy Tính (PC Desktop Launcher) đang duy trì 24/7
    if (this._state.pcSessions) {
      for (const pc of Object.values(this._state.pcSessions)) {
        if (!pc) continue;
        const graceUntil = pc.graceUntil || ((pc.lastSeenAt || now) + 24 * 60 * 60 * 1000);
        const withinGracePeriod = graceUntil > now;
        const key = `pc_${pc.pcClientId}`;
        byKey.set(key, {
          userId: pc.userId || 1,
          username: pc.gameUsername ? `${pc.username} (TK Game: ${pc.gameUsername})` : pc.username,
          role: pc.role || 'pc-client',
          platform: `${pc.platform || 'PC Desktop'} • ${pc.mapName || 'In-Game'} • AutoNV: ${pc.autoQuest ? 'BẬT' : 'TẮT'}`,
          deviceInfo: pc.platform || 'PC Desktop',
          withinGracePeriod,
          idleSeconds: Math.max(0, Math.round((now - (pc.lastSeenAt || now)) / 1000)),
          lastSeenAt: new Date(pc.lastSeenAt || now).toISOString(),
          graceUntil: new Date(graceUntil).toISOString(),
          graceRemainingSec: Math.max(0, Math.round((graceUntil - now) / 1000)),
          status: withinGracePeriod ? 'PC_247_ACTIVE' : 'IDLE_SAVED'
        });
      }
    }

    return Array.from(byKey.values()).sort((a, b) => a.idleSeconds - b.idleSeconds);
  }

  refreshAccessToken(refreshToken, userStore) {
    if (!refreshToken || typeof refreshToken !== 'string') return null;
    const rec = this._state.refreshTokens[refreshToken];
    if (!rec || rec.revoked) return null;

    const now = Date.now();
    if (now > rec.expiresAt) {
      delete this._state.refreshTokens[refreshToken];
      this._saveAtomic();
      return null;
    }

    const user = userStore.findById(rec.userId);
    if (!user || user.locked) return null;

    rec.lastSeenAt = now;
    rec.graceUntil = now + SESSION_GRACE_PERIOD_MS;
    rec.role = user.role;
    this._saveAtomic();

    const accessToken = jwt.sign(
      { id: user.id, username: user.username, role: user.role },
      config.JWT_SECRET,
      { expiresIn: config.JWT_EXPIRES_IN || '7d' }
    );

    return {
      accessToken,
      refreshToken,
      user: userStore.sanitize(user),
      graceUntil: rec.graceUntil
    };
  }

  revokeRefreshToken(refreshToken) {
    if (refreshToken && this._state.refreshTokens[refreshToken]) {
      this._state.refreshTokens[refreshToken].revoked = true;
      this._saveAtomic();
    }
  }

  async processOfflineQueue(user, actions = [], handlers = {}) {
    if (!Array.isArray(actions) || actions.length === 0) {
      return { processedCount: 0, skippedDuplicateCount: 0, results: [] };
    }

    const sorted = [...actions].sort(
      (a, b) => (Number(a.clientTimestamp) || 0) - (Number(b.clientTimestamp) || 0)
    );

    let processedCount = 0;
    let skippedDuplicateCount = 0;
    const results = [];

    for (const item of sorted) {
      if (!item || typeof item !== 'object') continue;
      const actionId = String(item.actionId || '').trim();
      if (!actionId) continue;

      if (this._state.processedActions[actionId]) {
        skippedDuplicateCount++;
        results.push({
          actionId,
          status: 'DUPLICATE_SKIPPED',
          processedAt: this._state.processedActions[actionId].processedAt
        });
        continue;
      }

      try {
        const type = String(item.type || '').toUpperCase();
        const payload = item.payload || {};
        let outcome = { ok: true };

        if (typeof handlers[type] === 'function') {
          outcome = (await handlers[type](user, payload, item)) || { ok: true };
        }

        const nowIso = new Date().toISOString();
        this._state.processedActions[actionId] = {
          userId: user.id,
          type,
          clientTimestamp: Number(item.clientTimestamp) || Date.now(),
          processedAt: nowIso
        };
        processedCount++;
        results.push({ actionId, type, status: 'SYNCED', outcome });
      } catch (err) {
        results.push({ actionId, type: item.type, status: 'ERROR', error: err.message });
      }
    }

    const keys = Object.keys(this._state.processedActions);
    if (keys.length > 2000) {
      for (const k of keys.slice(0, keys.length - 2000)) {
        delete this._state.processedActions[k];
      }
    }
    this._saveAtomic();

    return {
      processedCount,
      skippedDuplicateCount,
      results
    };
  }
}

DualSessionStore.DualSessionStore = DualSessionStore;
DualSessionStore.SESSION_GRACE_PERIOD_MS = SESSION_GRACE_PERIOD_MS;
DualSessionStore.REFRESH_TOKEN_TTL_MS = REFRESH_TOKEN_TTL_MS;

module.exports = DualSessionStore;
