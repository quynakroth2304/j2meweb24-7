// src/shared/dualSessionStore.js — Quản Lý Phiên Mạng Kép (Dual-Connection), Refresh Token & Đồng Bộ Hàng Đợi Offline Chống Ghi Trùng
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
        refreshTokens: {}, // refreshToken -> { userId, username, role, issuedAt, expiresAt, lastSeenAt, revoked }
        processedActions: {} // actionId -> { userId, type, processedAt, clientTimestamp }
      };
    }
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
      return {
        refreshTokens: parsed.refreshTokens || {},
        processedActions: parsed.processedActions || {}
      };
    } catch (_) {
      return { refreshTokens: {}, processedActions: {} };
    }
  }

  _saveAtomic() {
    try {
      const tmp = `${this.filePath}.tmp.${process.pid}.${Date.now()}`;
      fs.writeFileSync(tmp, JSON.stringify(this._state, null, 2), 'utf8');
      fs.renameSync(tmp, this.filePath);
    } catch (_) {}
  }

  issueTokenPair(user) {
    const accessToken = jwt.sign(
      { id: user.id, username: user.username, role: user.role },
      config.JWT_SECRET,
      { expiresIn: config.JWT_EXPIRES_IN || '7d' }
    );

    const refreshToken = `rtk_${user.id}_${crypto.randomBytes(24).toString('hex')}`;
    const now = Date.now();
    this._state.refreshTokens[refreshToken] = {
      userId: Number(user.id),
      username: user.username,
      role: user.role,
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
      gracePeriodSec: Math.round(SESSION_GRACE_PERIOD_MS / 1000),
      expiresAt: now + REFRESH_TOKEN_TTL_MS
    };
  }

  touchSessionByUser(userId) {
    const now = Date.now();
    for (const rec of Object.values(this._state.refreshTokens)) {
      if (rec.userId === Number(userId) && !rec.revoked) {
        rec.lastSeenAt = now;
        rec.graceUntil = now + SESSION_GRACE_PERIOD_MS;
      }
    }
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

  /**
   * Xử lý hàng đợi Offline (Offline Queue) gửi từ Client khi có mạng trở lại.
   * Sắp xếp theo clientTimestamp tăng dần và chống ghi trùng bằng actionId.
   */
  processOfflineQueue(user, actions = [], handlers = {}) {
    if (!Array.isArray(actions) || actions.length === 0) {
      return { processedCount: 0, skippedDuplicateCount: 0, results: [] };
    }

    // Sắp xếp đúng thứ tự thời gian người chơi thao tác lúc offline
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
          outcome = handlers[type](user, payload, item) || { ok: true };
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

    // Giới hạn lịch sử 2000 actionId gần nhất để tối ưu bộ nhớ
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

module.exports = DualSessionStore;
