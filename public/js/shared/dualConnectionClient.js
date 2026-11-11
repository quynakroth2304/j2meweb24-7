// public/js/shared/dualConnectionClient.js — Engine Mạng Kép (Dual-Connection), Hàng Đợi Offline, Refresh Token & Phân Quyền RBAC Dùng Chung
(function (global) {
  'use strict';

  const STORAGE_KEYS = {
    ACCESS_TOKEN: 'duytruong_auth_token',
    REFRESH_TOKEN: 'duytruong_refresh_token',
    USER_PROFILE: 'duytruong_user_profile',
    WALLET_CACHE: 'duytruong_wallet_cache',
    OFFLINE_QUEUE: 'duytruong_offline_queue_v1'
  };

  const IDB_NAME = 'DuyTruongDualNetDB';
  const IDB_STORE = 'session_kv';

  class DualConnectionClient {
    constructor(options = {}) {
      this.onNetworkChange = options.onNetworkChange || (() => {});
      this.onAuthSynced = options.onAuthSynced || (() => {});
      this.onQueueFlushed = options.onQueueFlushed || (() => {});
      this.onToast = options.onToast || (() => {});

      this.isOnline = global.navigator ? Boolean(global.navigator.onLine) : true;
      this.backoffAttempt = 0;
      this.reconnectTimer = null;
      this.flushingQueue = false;

      this.accessToken = this._readLocal(STORAGE_KEYS.ACCESS_TOKEN) || '';
      this.refreshToken = this._readLocal(STORAGE_KEYS.REFRESH_TOKEN) || '';
      this.user = this._readJson(STORAGE_KEYS.USER_PROFILE, null);
      this.wallet = this._readJson(STORAGE_KEYS.WALLET_CACHE, null);
      this.offlineQueue = this._readJson(STORAGE_KEYS.OFFLINE_QUEUE, []);

      this._initIndexedDbMirror();
      this._ensureStatusBannerDom();
      this._bindNetworkEvents();
      this._updateBannerUi();
    }

    _readLocal(key) {
      try {
        return global.localStorage ? global.localStorage.getItem(key) : null;
      } catch (_) {
        return null;
      }
    }

    _writeLocal(key, val) {
      try {
        if (global.localStorage) {
          if (val === null || val === undefined) {
            global.localStorage.removeItem(key);
          } else {
            global.localStorage.setItem(key, String(val));
          }
        }
      } catch (_) {}
      this._writeIdb(key, val);
    }

    _readJson(key, fallback) {
      const raw = this._readLocal(key);
      if (!raw) return fallback;
      try {
        return JSON.parse(raw);
      } catch (_) {
        return fallback;
      }
    }

    _writeJson(key, obj) {
      try {
        this._writeLocal(key, JSON.stringify(obj));
      } catch (_) {}
    }

    _initIndexedDbMirror() {
      if (!global.indexedDB) return;
      try {
        const req = global.indexedDB.open(IDB_NAME, 1);
        req.onupgradeneeded = (e) => {
          const db = e.target.result;
          if (!db.objectStoreNames.contains(IDB_STORE)) {
            db.createObjectStore(IDB_STORE);
          }
        };
        req.onsuccess = (e) => {
          this.idb = e.target.result;
          // Khôi phục từ IndexedDB nếu localStorage bị trống
          if (!this.accessToken) {
            this._restoreFromIdb();
          }
        };
      } catch (_) {}
    }

    _writeIdb(key, val) {
      if (!this.idb) return;
      try {
        const tx = this.idb.transaction(IDB_STORE, 'readwrite');
        const store = tx.objectStore(IDB_STORE);
        if (val === null || val === undefined) {
          store.delete(key);
        } else {
          store.put(String(val), key);
        }
      } catch (_) {}
    }

    _restoreFromIdb() {
      if (!this.idb) return;
      try {
        const tx = this.idb.transaction(IDB_STORE, 'readonly');
        const store = tx.objectStore(IDB_STORE);
        const getKey = (k, cb) => {
          const r = store.get(k);
          r.onsuccess = () => {
            if (r.result) cb(r.result);
          };
        };
        getKey(STORAGE_KEYS.ACCESS_TOKEN, (v) => { this.accessToken = v; });
        getKey(STORAGE_KEYS.REFRESH_TOKEN, (v) => { this.refreshToken = v; });
        getKey(STORAGE_KEYS.USER_PROFILE, (v) => {
          try {
            this.user = JSON.parse(v);
            this.onAuthSynced(this.user, this.wallet);
          } catch (_) {}
        });
      } catch (_) {}
    }

    // ==================== LƯU & ĐỒNG BỘ PHIÊN ĐĂNG NHẬP ====================

    saveSession({ accessToken, token, refreshToken, user, wallet }) {
      const finalAccess = accessToken || token || this.accessToken;
      if (finalAccess) {
        this.accessToken = finalAccess;
        this._writeLocal(STORAGE_KEYS.ACCESS_TOKEN, finalAccess);
      }
      if (refreshToken) {
        this.refreshToken = refreshToken;
        this._writeLocal(STORAGE_KEYS.REFRESH_TOKEN, refreshToken);
      }
      if (user) {
        this.user = user;
        this._writeJson(STORAGE_KEYS.USER_PROFILE, user);
        this._writeJson('j2me_user', user); // Tương thích ngược
      }
      if (wallet) {
        this.wallet = wallet;
        this._writeJson(STORAGE_KEYS.WALLET_CACHE, wallet);
      }
      this.onAuthSynced(this.user, this.wallet);
    }

    clearSessionOnlyOnExplicitRevoke() {
      this.accessToken = '';
      this.refreshToken = '';
      this.user = null;
      this.wallet = null;
      this._writeLocal(STORAGE_KEYS.ACCESS_TOKEN, null);
      this._writeLocal(STORAGE_KEYS.REFRESH_TOKEN, null);
      this._writeLocal(STORAGE_KEYS.USER_PROFILE, null);
      this._writeLocal(STORAGE_KEYS.WALLET_CACHE, null);
      this._writeLocal('j2me_user', null);
      this.onAuthSynced(null, null);
    }

    isLoggedIn() {
      return Boolean(this.accessToken || this.refreshToken || this.user);
    }

    isAdmin() {
      return Boolean(this.user && this.user.role === 'admin');
    }

    /**
     * Chặn phân quyền (RBAC Guard) phía Frontend:
     * - Nếu trang yêu cầu quyền 'admin' mà tài khoản là 'user' -> chặn ngay lập tức và chuyển về '/app' (/play.html)
     */
    enforceRouteGuard(requiredRole = 'user') {
      if (requiredRole === 'admin') {
        if (!this.user) {
          return { allowed: false, reason: 'UNAUTHENTICATED', redirectTo: '/play.html?login=1' };
        }
        if (this.user.role !== 'admin') {
          return { allowed: false, reason: 'FORBIDDEN_USER_ROLE', redirectTo: '/play.html?error=forbidden_admin' };
        }
      }
      return { allowed: true };
    }

    buildHeaders(extra = {}) {
      const headers = {
        'Content-Type': 'application/json',
        ...extra
      };
      if (this.accessToken) {
        headers.Authorization = `Bearer ${this.accessToken}`;
      }
      if (this.refreshToken) {
        headers['X-Refresh-Token'] = this.refreshToken;
      }
      return headers;
    }

    // ==================== GIAO TIẾP API VỚI TỰ ĐỘNG REFRESH TOKEN & HÀNG ĐỢI OFFLINE ====================

    async fetchWithDualAuth(url, options = {}) {
      const headers = this.buildHeaders(options.headers || {});
      try {
        const res = await fetch(url, { ...options, headers });
        const refreshedToken = res.headers.get('X-Refreshed-Access-Token');
        if (refreshedToken) {
          this.accessToken = refreshedToken;
          this._writeLocal(STORAGE_KEYS.ACCESS_TOKEN, refreshedToken);
        }

        if (res.status === 401 && this.refreshToken) {
          // Thử dùng Refresh Token cấp lại Access Token mà không bắt đăng nhập lại
          const renewed = await this.tryRefreshAccessToken();
          if (renewed) {
            const retryHeaders = this.buildHeaders(options.headers || {});
            return await fetch(url, { ...options, headers: retryHeaders });
          }
        }

        if (!this.isOnline) {
          this._setOnlineState(true);
        }
        return res;
      } catch (networkErr) {
        // Mất mạng -> Chuyển sang chế độ Offline, tuyệt đối KHÔNG logout
        this._setOnlineState(false);
        throw networkErr;
      }
    }

    async tryRefreshAccessToken() {
      if (!this.refreshToken) return false;
      try {
        const res = await fetch('/api/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: this.refreshToken })
        });
        if (!res.ok) {
          if (res.status === 401 || res.status === 403) {
            // Chỉ khi cả Refresh Token thật sự hết hạn hoặc bị thu hồi mới yêu cầu đăng nhập lại
            this.clearSessionOnlyOnExplicitRevoke();
          }
          return false;
        }
        const data = await res.json();
        if (data && data.accessToken) {
          this.saveSession({
            accessToken: data.accessToken,
            refreshToken: data.refreshToken || this.refreshToken,
            user: data.user || this.user,
            wallet: data.wallet || this.wallet
          });
          return true;
        }
        return false;
      } catch (_) {
        // Đang mất mạng -> giữ nguyên phiên cục bộ, không xóa token
        this._setOnlineState(false);
        return false;
      }
    }

    // ==================== HÀNG ĐỢI OFFLINE (OFFLINE QUEUE) & ĐỒNG BỘ THỨ TỰ THỜI GIAN ====================

    enqueueOfflineAction(type, payload = {}) {
      const action = {
        actionId: `act_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
        type: String(type).toUpperCase(),
        payload,
        clientTimestamp: Date.now()
      };
      this.offlineQueue.push(action);
      if (this.offlineQueue.length > 300) {
        this.offlineQueue.shift();
      }
      this._writeJson(STORAGE_KEYS.OFFLINE_QUEUE, this.offlineQueue);
      this._updateBannerUi();
      return action;
    }

    async flushOfflineQueue() {
      if (this.flushingQueue || !this.isOnline || this.offlineQueue.length === 0) {
        return;
      }
      if (!this.accessToken && !this.refreshToken) {
        return;
      }
      this.flushingQueue = true;
      this._updateBannerUi('syncing');

      try {
        const batch = [...this.offlineQueue];
        const res = await this.fetchWithDualAuth('/api/sync/offline-queue', {
          method: 'POST',
          body: JSON.stringify({ actions: batch })
        });
        if (res.ok) {
          const data = await res.json();
          const syncedIds = new Set(batch.map((b) => b.actionId));
          this.offlineQueue = this.offlineQueue.filter((item) => !syncedIds.has(item.actionId));
          this._writeJson(STORAGE_KEYS.OFFLINE_QUEUE, this.offlineQueue);
          if (data.wallet) {
            this.saveSession({ wallet: data.wallet });
          }
          this.onQueueFlushed(data);
          this.onToast(`☁️ Đã đồng bộ ${batch.length} thao tác Offline lên Server thành công!`);
        }
      } catch (_) {
        // Sẽ thử lại ở chu kỳ Exponential Backoff tiếp theo
      } finally {
        this.flushingQueue = false;
        this._updateBannerUi();
      }
    }

    // ==================== THEO DÕI MẠNG & RECONNECT EXPONENTIAL BACKOFF (1s -> 30s) ====================

    _bindNetworkEvents() {
      if (!global.addEventListener) return;
      global.addEventListener('online', () => {
        this.backoffAttempt = 0;
        this._verifyRealConnectivityNow();
      });
      global.addEventListener('offline', () => {
        this._setOnlineState(false);
      });

      // Kiểm tra nhịp tim định kỳ mỗi 10s
      setInterval(() => {
        if (global.navigator && global.navigator.onLine === false) {
          this._setOnlineState(false);
          return;
        }
        this._verifyRealConnectivityNow();
      }, 10000);
    }

    async _verifyRealConnectivityNow() {
      try {
        const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
        const timer = controller ? setTimeout(() => controller.abort(), 3500) : null;
        const res = await fetch('/api/health', {
          method: 'GET',
          cache: 'no-store',
          signal: controller ? controller.signal : undefined
        });
        if (timer) clearTimeout(timer);
        if (res.ok) {
          const wasOffline = !this.isOnline;
          this._setOnlineState(true);
          this.backoffAttempt = 0;
          if (wasOffline) {
            if (this.refreshToken) {
              await this.tryRefreshAccessToken();
            }
            await this.flushOfflineQueue();
          } else if (this.offlineQueue.length > 0) {
            await this.flushOfflineQueue();
          }
          return true;
        }
      } catch (_) {}

      this._setOnlineState(false);
      this._scheduleExponentialReconnect();
      return false;
    }

    _scheduleExponentialReconnect() {
      if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
      // Exponential backoff: 1s, 2s, 4s, 8s, 16s, tối đa 30s
      const delaySec = Math.min(30, Math.pow(2, this.backoffAttempt));
      this.backoffAttempt = Math.min(6, this.backoffAttempt + 1);
      this._updateBannerUi('reconnecting', delaySec);

      this.reconnectTimer = setTimeout(() => {
        this._verifyRealConnectivityNow();
      }, delaySec * 1000);
    }

    _setOnlineState(online) {
      const changed = this.isOnline !== Boolean(online);
      this.isOnline = Boolean(online);
      this._updateBannerUi();
      if (changed) {
        this.onNetworkChange(this.isOnline);
        if (!this.isOnline) {
          this.onToast('📡 Đang offline, dữ liệu và tiến trình game sẽ tự đồng bộ khi có mạng!');
          this._scheduleExponentialReconnect();
        }
      }
    }

    // ==================== THANH THÔNG BÁO MẠNG KÉP (NON-BLOCKING BANNER) ====================

    _ensureStatusBannerDom() {
      if (!global.document || !global.document.body) return;
      if (global.document.getElementById('dualNetStatusBanner')) return;
      const bar = global.document.createElement('div');
      bar.id = 'dualNetStatusBanner';
      bar.style.cssText = [
        'position:fixed',
        'bottom:12px',
        'left:12px',
        'z-index:9998',
        'padding:6px 12px',
        'border-radius:999px',
        'font-size:11px',
        'font-weight:700',
        'display:none',
        'align-items:center',
        'gap:8px',
        'box-shadow:0 8px 20px rgba(0,0,0,0.55)',
        'pointer-events:none',
        'transition:all 0.25s ease'
      ].join(';');
      global.document.body.appendChild(bar);
    }

    _updateBannerUi(mode = '', retrySec = 1) {
      if (!global.document) return;
      this._ensureStatusBannerDom();
      const bar = global.document.getElementById('dualNetStatusBanner');
      if (!bar) return;

      const qCount = this.offlineQueue.length;
      if (!this.isOnline) {
        bar.style.display = 'flex';
        bar.style.background = 'rgba(180, 83, 9, 0.95)';
        bar.style.border = '1px solid #fbbf24';
        bar.style.color = '#fef3c7';
        const retryText = mode === 'reconnecting' ? ` • Thử kết nối lại sau ${retrySec}s` : '';
        bar.textContent = `📡 Đang offline, dữ liệu sẽ đồng bộ khi có mạng (Hàng đợi: ${qCount})${retryText}`;
      } else if (mode === 'syncing' || qCount > 0) {
        bar.style.display = 'flex';
        bar.style.background = 'rgba(3, 105, 161, 0.95)';
        bar.style.border = '1px solid #38bdf8';
        bar.style.color = '#e0f2fe';
        bar.textContent = `🔄 Đang đồng bộ ${qCount} thao tác Offline lên Server...`;
      } else {
        bar.style.display = 'none';
      }
    }
  }

  global.DualConnectionClient = DualConnectionClient;
})(typeof window !== 'undefined' ? window : globalThis);
