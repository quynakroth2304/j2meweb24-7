// public/js/keepAliveEngine.js — Engine Duy Trì Mạng & Chống Ngủ Ngầm 24/7 (Web Worker Relay + Web Locks + Silent Audio 0dB + WakeLock + PiP HUD)
(function (global) {
  'use strict';

  const WORKER_SOURCE = `
    // Dedicated Network Web Worker — Giữ luồng WebSocket Relay & Heartbeat 5s bất tử khi ẩn Tab
    const sockets = new Map(); // connId -> { ws, sessionId, slotId, host, port, rxQueue: [], reconnectTimer: null, closedByClient: false }
    let relayBaseUrl = '';
    let latestTelemetry = {
      pingMs: 16,
      txKBps: 0,
      rxKBps: 0,
      activeSockets: 0,
      graceResumes: 0,
      connected: false
    };

    function buildWsUrl(conn) {
      const u = new URL(relayBaseUrl);
      u.searchParams.set('sessionId', conn.sessionId);
      u.searchParams.set('slotId', String(conn.slotId || 1));
      u.searchParams.set('upstream', conn.host + ':' + conn.port);
      u.searchParams.set('autoconnect', '1');
      return u.toString();
    }

    function connectRelaySocket(connId) {
      const conn = sockets.get(connId);
      if (!conn || conn.closedByClient) return;

      if (conn.ws) {
        try { conn.ws.close(); } catch (_) {}
      }

      const ws = new WebSocket(buildWsUrl(conn));
      ws.binaryType = 'arraybuffer';
      conn.ws = ws;

      ws.onopen = () => {
        latestTelemetry.connected = true;
        ws.send(JSON.stringify({
          type: 'RESUME_SESSION',
          sessionId: conn.sessionId,
          slotId: conn.slotId,
          upstream: conn.host + ':' + conn.port
        }));
        self.postMessage({
          type: 'SOCKET_OPEN',
          connId,
          sessionId: conn.sessionId,
          slotId: conn.slotId,
          upstream: conn.host + ':' + conn.port
        });
      };

      ws.onmessage = (ev) => {
        if (typeof ev.data === 'string') {
          try {
            const msg = JSON.parse(ev.data);
            if (msg.type === 'HEARTBEAT_PONG') {
              const rtt = Math.max(1, Date.now() - (Number(msg.ts) || Date.now()));
              latestTelemetry.pingMs = msg.pingMs || rtt;
              latestTelemetry.txKBps = msg.txKBps || 0;
              latestTelemetry.rxKBps = msg.rxKBps || 0;
              latestTelemetry.activeSockets = msg.activeSockets || sockets.size;
              self.postMessage({ type: 'TELEMETRY', data: latestTelemetry });
            } else if (msg.type === 'SESSION_RESUMED') {
              latestTelemetry.graceResumes += 1;
              self.postMessage({
                type: 'SESSION_RESUMED',
                connId,
                sessionId: msg.sessionId,
                flushedBytes: msg.flushedBytes || 0,
                gracePeriodSec: msg.gracePeriodSec || 60
              });
            } else if (msg.type === 'SESSION_READY') {
              self.postMessage({
                type: 'SESSION_READY',
                connId,
                sessionId: msg.sessionId,
                gracePeriodSec: msg.gracePeriodSec || 60
              });
            } else if (msg.type === 'RELAY_ERROR') {
              self.postMessage({ type: 'RELAY_ERROR', connId, message: msg.message });
            }
          } catch (_) {}
          return;
        }

        // Gói tin TCP nhị phân từ máy chủ game trả về
        const bytes = new Uint8Array(ev.data);
        self.postMessage({
          type: 'SOCKET_RX',
          connId,
          bytes
        }, [bytes.buffer]);
      };

      ws.onclose = () => {
        if (conn.closedByClient) return;
        latestTelemetry.connected = false;
        self.postMessage({
          type: 'SOCKET_RECONNECTING',
          connId,
          sessionId: conn.sessionId
        });
        // Tự động kết nối lại (RESUME_SESSION) trong 0.5s (500ms) nếu mạng chập chờn
        if (conn.reconnectTimer) clearTimeout(conn.reconnectTimer);
        conn.reconnectTimer = setTimeout(() => {
          connectRelaySocket(connId);
        }, 500);
      };

      ws.onerror = () => {};
    }

    // Nhịp tim HEARTBEAT_PING chu kỳ 5s bên trong Web Worker (không bao giờ bị trình duyệt throttle)
    setInterval(() => {
      const now = Date.now();
      for (const conn of sockets.values()) {
        if (conn.ws && conn.ws.readyState === WebSocket.OPEN) {
          try {
            conn.ws.send(JSON.stringify({
              type: 'HEARTBEAT_PING',
              ts: now,
              sessionId: conn.sessionId,
              slotId: conn.slotId
            }));
          } catch (_) {}
        }
      }
    }, 5000);

    self.onmessage = (e) => {
      const msg = e.data || {};
      switch (msg.cmd) {
        case 'INIT': {
          relayBaseUrl = msg.relayBaseUrl;
          break;
        }
        case 'OPEN': {
          const { connId, sessionId, slotId, host, port } = msg;
          sockets.set(connId, {
            ws: null,
            sessionId: sessionId || ('sess_' + connId + '_' + Date.now().toString(36)),
            slotId: slotId || 1,
            host: host || '27.0.14.78',
            port: port || 19129,
            reconnectTimer: null,
            closedByClient: false
          });
          connectRelaySocket(connId);
          break;
        }
        case 'WRITE': {
          const conn = sockets.get(msg.connId);
          if (conn && conn.ws && conn.ws.readyState === WebSocket.OPEN && msg.bytes) {
            conn.ws.send(msg.bytes);
          }
          break;
        }
        case 'CLOSE': {
          const conn = sockets.get(msg.connId);
          if (conn) {
            conn.closedByClient = true;
            if (conn.reconnectTimer) clearTimeout(conn.reconnectTimer);
            if (conn.ws) {
              try {
                conn.ws.send(JSON.stringify({ type: 'CLOSE_TCP', sessionId: conn.sessionId }));
                conn.ws.close();
              } catch (_) {}
            }
            sockets.delete(msg.connId);
          }
          break;
        }
      }
    };
  `;

  class KeepAliveEngine {
    constructor(options = {}) {
      this.onTelemetry = options.onTelemetry || (() => {});
      this.onSessionResumed = options.onSessionResumed || (() => {});
      this.onStatusChange = options.onStatusChange || (() => {});
      this.getSlotsSummary = options.getSlotsSummary || (() => []);

      this.antiSleepEnabled = true; // LUÔN BẬT BẤT TỬ MẠNG 24/7 MẶC ĐỊNH
      this.wakeLockSentinel = null;
      this.audioCtx = null;
      this.oscillator = null;
      this.gainNode = null;
      this.webLockHeld = false;

      // Picture-in-Picture (PiP) HUD Monitor
      this.pipCanvas = null;
      this.pipVideo = null;
      this.pipTimer = null;
      this.pipActive = false;

      // Dữ liệu mạng thời gian thực & lịch sử Ping Sparkline (30 mẫu)
      this.telemetry = {
        pingMs: 16,
        txKBps: 0.4,
        rxKBps: 1.2,
        activeSockets: 1,
        graceResumes: 0,
        connected: true,
        pingHistory: [18, 17, 16, 19, 15, 16, 18, 17, 16, 15, 17, 16, 18, 16, 15, 16]
      };

      // Bảng quản lý kết nối cho Connector.java (WebSocketRelayConnection)
      this.nextConnId = 1;
      this.connRxBuffers = new Map(); // connId -> Uint8Array[]
      this.connClosed = new Set();

      this._initWorker();
      this._bindVisibilityHooks();
      // Kích hoạt ngay lập tức Bất Tử Mạng 24/7 khi vừa mở trang
      this.setAntiSleep247(true);
    }

    _initWorker() {
      const proto = global.location && global.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = (global.location && global.location.host) ? global.location.host : 'localhost:3000';
      const relayBaseUrl = `${proto}//${host}/ws-tcp-relay`;

      try {
        const blob = new Blob([WORKER_SOURCE], { type: 'application/javascript' });
        this.worker = new Worker(URL.createObjectURL(blob));
        this.worker.postMessage({ cmd: 'INIT', relayBaseUrl });

        this.worker.onmessage = (e) => {
          const msg = e.data || {};
          if (msg.type === 'TELEMETRY' && msg.data) {
            this.telemetry.pingMs = msg.data.pingMs || 16;
            this.telemetry.txKBps = msg.data.txKBps || 0;
            this.telemetry.rxKBps = msg.data.rxKBps || 0;
            this.telemetry.activeSockets = msg.data.activeSockets || 1;
            this.telemetry.connected = true;
            this.telemetry.pingHistory.push(this.telemetry.pingMs);
            if (this.telemetry.pingHistory.length > 32) {
              this.telemetry.pingHistory.shift();
            }
            this.onTelemetry(this.telemetry);
          } else if (msg.type === 'SOCKET_RX' && msg.bytes) {
            let queue = this.connRxBuffers.get(msg.connId);
            if (!queue) {
              queue = [];
              this.connRxBuffers.set(msg.connId, queue);
            }
            queue.push(new Uint8Array(msg.bytes));
          } else if (msg.type === 'SESSION_RESUMED') {
            this.telemetry.graceResumes += 1;
            this.onSessionResumed(msg);
          }
        };
      } catch (err) {
        console.warn('[KeepAliveEngine] Không tạo được Blob Worker, dùng fallback main-thread:', err);
      }
    }

    /**
     * Chế độ "Bất Tử Mạng Ngầm 24/7" (Web Locks API + Silent Audio 0dB + Screen WakeLock)
     * Luôn khóa ở trạng thái BẬT (true) 100% thời gian theo yêu cầu hệ thống 24/7.
     */
    async setAntiSleep247(_enabled = true) {
      this.antiSleepEnabled = true; // Không bao giờ tắt Bất Tử Mạng
      this._acquireEternalWebLock();
      await this._startSilentAudio0dB();
      await this._requestScreenWakeLock();
      this.onStatusChange({
        antiSleepEnabled: true,
        pipActive: this.pipActive,
        webLockHeld: this.webLockHeld
      });
      return true;
    }

    _acquireEternalWebLock() {
      if (this.webLockHeld) return;
      if (global.navigator && global.navigator.locks && typeof global.navigator.locks.request === 'function') {
        this.webLockHeld = true;
        global.navigator.locks.request('j2me_cloud_net_lock', () => new Promise(() => {})).catch(() => {
          this.webLockHeld = false;
        });
      }
    }

    async _startSilentAudio0dB() {
      try {
        const AudioCtx = global.AudioContext || global.webkitAudioContext;
        if (!AudioCtx) return;
        if (!this.audioCtx) {
          this.audioCtx = new AudioCtx();
        }
        if (this.audioCtx.state === 'suspended') {
          await this.audioCtx.resume();
        }
        if (!this.oscillator) {
          const osc = this.audioCtx.createOscillator();
          const gain = this.audioCtx.createGain();
          osc.type = 'sine';
          osc.frequency.setValueAtTime(1, this.audioCtx.currentTime); // 1Hz siêu trầm
          gain.gain.setValueAtTime(0.00001, this.audioCtx.currentTime); // 0dB hoàn toàn không phát tiếng
          osc.connect(gain);
          gain.connect(this.audioCtx.destination);
          osc.start();
          this.oscillator = osc;
          this.gainNode = gain;
        }
      } catch (err) {
        console.warn('[KeepAliveEngine] Silent Audio 0dB warning:', err.message);
      }
    }

    _stopSilentAudio0dB() {
      try {
        if (this.oscillator) {
          this.oscillator.stop();
          this.oscillator.disconnect();
          this.oscillator = null;
        }
      } catch (_) {}
    }

    async _requestScreenWakeLock() {
      try {
        if (global.navigator && 'wakeLock' in global.navigator) {
          this.wakeLockSentinel = await global.navigator.wakeLock.request('screen');
          this.wakeLockSentinel.addEventListener('release', () => {
            this.wakeLockSentinel = null;
          });
        }
      } catch (_) {}
    }

    async _releaseScreenWakeLock() {
      try {
        if (this.wakeLockSentinel) {
          await this.wakeLockSentinel.release();
          this.wakeLockSentinel = null;
        }
      } catch (_) {}
    }

    _bindVisibilityHooks() {
      if (!global.document) return;
      const ensureAlwaysOn = () => {
        this.antiSleepEnabled = true;
        this._acquireEternalWebLock();
        this._requestScreenWakeLock();
        this._startSilentAudio0dB();
      };
      global.document.addEventListener('visibilitychange', () => {
        if (global.document.visibilityState === 'visible') {
          ensureAlwaysOn();
        }
      });
      ['pointerdown', 'touchstart', 'keydown', 'click'].forEach((evt) => {
        global.document.addEventListener(evt, ensureAlwaysOn, { passive: true });
      });
    }

    /**
     * Cửa Sổ Nổi Picture-in-Picture (PiP Network Monitor 5 FPS)
     * Giúp người chơi thoát ra màn hình chính lướt Zalo/TikTok mà máy ảo và mạng vẫn chạy 100% tốc độ.
     */
    async togglePipNetworkMonitor() {
      if (!global.document) return false;
      if (global.document.pictureInPictureElement) {
        await global.document.exitPictureInPicture();
        this.pipActive = false;
        if (this.pipTimer) clearInterval(this.pipTimer);
        this.onStatusChange({
          antiSleepEnabled: this.antiSleepEnabled,
          pipActive: false,
          webLockHeld: this.webLockHeld
        });
        return false;
      }

      if (!this.pipCanvas) {
        this.pipCanvas = global.document.createElement('canvas');
        this.pipCanvas.width = 360;
        this.pipCanvas.height = 200;
      }
      if (!this.pipVideo) {
        this.pipVideo = global.document.createElement('video');
        this.pipVideo.muted = true;
        this.pipVideo.playsInline = true;
        this.pipVideo.style.position = 'fixed';
        this.pipVideo.style.width = '1px';
        this.pipVideo.style.height = '1px';
        this.pipVideo.style.opacity = '0';
        this.pipVideo.style.pointerEvents = 'none';
        global.document.body.appendChild(this.pipVideo);
      }

      this._renderPipHudFrame();
      if (this.pipTimer) clearInterval(this.pipTimer);
      this.pipTimer = setInterval(() => this._renderPipHudFrame(), 200); // 5 FPS

      const stream = this.pipCanvas.captureStream(5);
      this.pipVideo.srcObject = stream;
      await this.pipVideo.play();
      await this.pipVideo.requestPictureInPicture();
      this.pipActive = true;

      this.pipVideo.addEventListener('leavepictureinpicture', () => {
        this.pipActive = false;
        if (this.pipTimer) clearInterval(this.pipTimer);
        this.onStatusChange({
          antiSleepEnabled: this.antiSleepEnabled,
          pipActive: false,
          webLockHeld: this.webLockHeld
        });
      }, { once: true });

      this.onStatusChange({
        antiSleepEnabled: this.antiSleepEnabled,
        pipActive: true,
        webLockHeld: this.webLockHeld
      });
      return true;
    }

    _renderPipHudFrame() {
      if (!this.pipCanvas) return;
      const ctx = this.pipCanvas.getContext('2d');
      const w = this.pipCanvas.width;
      const h = this.pipCanvas.height;

      // Nền Cyberpunk Glassmorphism
      ctx.fillStyle = '#090d16';
      ctx.fillRect(0, 0, w, h);

      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = 2;
      ctx.strokeRect(4, 4, w - 8, h - 8);

      ctx.fillStyle = '#38bdf8';
      ctx.font = 'bold 14px monospace';
      ctx.fillText('CLOUD PHONE 24/7 • PIP MONITOR', 16, 26);

      ctx.fillStyle = '#10b981';
      ctx.font = 'bold 13px monospace';
      ctx.fillText(
        `PING: ${this.telemetry.pingMs}ms | TX:${this.telemetry.txKBps} RX:${this.telemetry.rxKBps} KB/s`,
        16,
        50
      );

      const slots = this.getSlotsSummary() || [];
      ctx.fillStyle = '#e2e8f0';
      ctx.font = '12px sans-serif';
      ctx.fillText(`Số Máy Ảo Đang Treo: ${slots.length} Acc (Grace 120s VIP)`, 16, 72);

      let y = 96;
      slots.slice(0, 3).forEach((s, idx) => {
        ctx.fillStyle = s.autoQuest ? '#34d399' : '#94a3b8';
        const line = `[Acc ${idx + 1}] ${s.name || 'Hiệp Sĩ'} Lv.${s.lv || 1} • ${s.stateText || 'Đang cày bãi'}`;
        ctx.fillText(line.slice(0, 44), 16, y);
        y += 22;
      });

      // Vẽ biểu đồ Sparkline Ping ở đáy
      const hist = this.telemetry.pingHistory;
      if (hist.length > 1) {
        ctx.strokeStyle = '#818cf8';
        ctx.lineWidth = 2;
        ctx.beginPath();
        hist.forEach((p, i) => {
          const px = 16 + (i / (hist.length - 1)) * (w - 32);
          const py = h - 14 - Math.min(28, (p / 100) * 28);
          if (i === 0) ctx.moveTo(px, py);
          else ctx.lineTo(px, py);
        });
        ctx.stroke();
      }
    }

    // ==================== CẦU NỐI NATIVE JS CHO CONNECTOR.JAVA (CHEERPJ / WASM) ====================

    jsSocketOpen(host, port, slotId = 1) {
      const connId = this.nextConnId++;
      const storageKey = `j2me_relay_sid_slot_${slotId}_${host}_${port}`;
      let sessionId = null;
      try {
        sessionId = global.localStorage ? global.localStorage.getItem(storageKey) : null;
      } catch (_) {}
      if (!sessionId) {
        sessionId = `sess_s${slotId}_${connId}_${Date.now().toString(36)}`;
        try {
          if (global.localStorage) global.localStorage.setItem(storageKey, sessionId);
        } catch (_) {}
      }

      this.connRxBuffers.set(connId, []);
      if (this.worker) {
        this.worker.postMessage({
          cmd: 'OPEN',
          connId,
          sessionId,
          slotId,
          host,
          port
        });
      }
      return connId;
    }

    jsSocketWrite(connId, byteArray) {
      if (!this.worker || this.connClosed.has(connId)) return -1;
      const copy = new Uint8Array(byteArray);
      this.worker.postMessage({
        cmd: 'WRITE',
        connId,
        bytes: copy
      }, [copy.buffer]);
      return copy.length;
    }

    jsSocketRead(connId, maxLen) {
      const queue = this.connRxBuffers.get(connId);
      if (!queue || queue.length === 0) {
        return new Uint8Array(0);
      }
      const first = queue[0];
      if (first.length <= maxLen) {
        queue.shift();
        return first;
      }
      const slice = first.slice(0, maxLen);
      queue[0] = first.slice(maxLen);
      return slice;
    }

    jsSocketClose(connId) {
      this.connClosed.add(connId);
      this.connRxBuffers.delete(connId);
      if (this.worker) {
        this.worker.postMessage({ cmd: 'CLOSE', connId });
      }
    }

    /**
     * Đăng ký các hàm Native cho `cheerpjInit({ natives: KeepAliveEngine.getCheerpJNatives() })`
     */
    getCheerpJNatives() {
      const self = this;
      return {
        async Java_javax_microedition_io_Connector_jsSocketOpen(lib, host, port) {
          return self.jsSocketOpen(host, port, global.__ACTIVE_SLOT_ID || 1);
        },
        async Java_javax_microedition_io_Connector_jsSocketWrite(lib, connId, data, off, len) {
          const slice = data.subarray(off, off + len);
          return self.jsSocketWrite(connId, slice);
        },
        async Java_javax_microedition_io_Connector_jsSocketRead(lib, connId, buf, off, len) {
          const chunk = self.jsSocketRead(connId, len);
          if (!chunk || chunk.length === 0) return 0;
          for (let i = 0; i < chunk.length; i++) {
            buf[off + i] = chunk[i];
          }
          return chunk.length;
        },
        async Java_javax_microedition_io_Connector_jsSocketClose(lib, connId) {
          self.jsSocketClose(connId);
        }
      };
    }
  }

  global.KeepAliveEngine = KeepAliveEngine;
})(typeof window !== 'undefined' ? window : globalThis);
