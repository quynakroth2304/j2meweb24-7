// networkRelay.js — Hệ Thống Server Cung Cấp Mạng 24/7 & Giữ Phiên Bất Tử (WebSocket-to-TCP Relay + 60s/120s Grace Period + 64KB Ring Buffer)
'use strict';

const net = require('net');
const dns = require('dns').promises;
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const WebSocket = require('ws');

const DEFAULT_ALLOWED_UPSTREAMS = [
  '27.0.14.78:19129',
  '27.0.14.78:19130',
  '115.84.182.34:19129',
  'knightageonline.com:80',
  'knightageonline.com:443',
  '127.0.0.1:19129'
];

const RING_BUFFER_MAX_BYTES = 64 * 1024; // 64KB Ring Buffer cố định cho mỗi phiên TCP (không bao giờ tràn RAM dù giữ 24/7)
const DEFAULT_GRACE_PERIOD_MS = 24 * 60 * 60 * 1000; // 24 giờ (24/7) giữ mạng bất tử trên Server cho mỗi phiên
const VIP_GRACE_PERIOD_MS = 24 * 60 * 60 * 1000; // 24 giờ (24/7) ưu tiên giữ phiên bất tử + Auto Heartbeat cho VIP
const OFFLINE_HEARTBEAT_INTERVAL_MS = 20 * 1000; // Bơm nhịp tim giữ kết nối mỗi 20s khi máy người chơi mất mạng

/**
 * Bộ đệm vòng (Circular Ring Buffer) tối đa 64KB giữ gói tin từ máy chủ game
 * trong thời gian trình duyệt người chơi bị ngắt WebSocket tạm thời (chuyển Wi-Fi/4G, F5, khóa màn hình).
 */
class SessionRingBuffer {
  constructor(maxBytes = RING_BUFFER_MAX_BYTES) {
    this.maxBytes = maxBytes;
    this.chunks = [];
    this.totalBytes = 0;
  }

  push(buffer) {
    if (!Buffer.isBuffer(buffer) || buffer.length === 0) return;
    if (buffer.length >= this.maxBytes) {
      this.chunks = [Buffer.from(buffer.slice(buffer.length - this.maxBytes))];
      this.totalBytes = this.maxBytes;
      return;
    }
    this.chunks.push(Buffer.from(buffer));
    this.totalBytes += buffer.length;
    while (this.totalBytes > this.maxBytes && this.chunks.length > 0) {
      const oldest = this.chunks[0];
      const overflow = this.totalBytes - this.maxBytes;
      if (oldest.length <= overflow) {
        this.chunks.shift();
        this.totalBytes -= oldest.length;
      } else {
        this.chunks[0] = oldest.slice(overflow);
        this.totalBytes -= overflow;
      }
    }
  }

  drain() {
    if (this.chunks.length === 0) return Buffer.alloc(0);
    const out = Buffer.concat(this.chunks, this.totalBytes);
    this.chunks = [];
    this.totalBytes = 0;
    return out;
  }

  size() {
    return this.totalBytes;
  }
}

/**
 * Kiểm tra IP nội bộ / Loopback / Link-local để chống SSRF khi chạy Production
 */
function isPrivateOrLoopbackIp(ip, allowLocalDebug = false) {
  if (!ip) return true;
  const clean = ip.replace(/^::ffff:/i, '');
  if (clean === '127.0.0.1' || clean === '::1') {
    return !allowLocalDebug;
  }
  if (/^10\./.test(clean)) return true;
  if (/^192\.168\./.test(clean)) return true;
  if (/^169\.254\./.test(clean)) return true;
  if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(clean)) return true;
  if (/^(fc|fd|fe80):/i.test(clean)) return true;
  return false;
}

class NetworkRelayGateway {
  constructor(options = {}) {
    this.userStore = options.userStore || null;
    this.jwtVerify = options.jwtVerify || null;
    this.allowLocalDebug = process.env.ALLOW_LOCAL_RELAY === '1' || process.env.NODE_ENV !== 'production';

    const rawAllowlist = process.env.RELAY_ALLOWED_UPSTREAMS || DEFAULT_ALLOWED_UPSTREAMS.join(',');
    this.allowedUpstreams = new Set(
      rawAllowlist
        .split(',')
        .map((s) => s.trim().toLowerCase())
        .filter(Boolean)
    );

    // Map<sessionId, RelaySession>
    this.sessions = new Map();

    // Thống kê băng thông & độ trễ thời gian thực
    this.metrics = {
      totalBytesTx: 0, // Client -> Game Server
      totalBytesRx: 0, // Game Server -> Client
      windowBytesTx: 0,
      windowBytesRx: 0,
      txRateKBps: 0,
      rxRateKBps: 0,
      avgPingMs: 18,
      activeTcpSockets: 0,
      graceHoldingSessions: 0,
      resumedSessionCount: 0,
      rejectedSsrfCount: 0,
      rejectedUpstreamCount: 0
    };

    this.wss = new WebSocket.Server({
      noServer: true,
      maxPayload: 256 * 1024
    });

    this.wss.on('connection', (ws, req, authInfo) => {
      this._handleWsConnection(ws, req, authInfo);
    });

    // Cập nhật tốc độ băng thông KB/s mỗi giây
    this._rateTimer = setInterval(() => {
      this.metrics.txRateKBps = Number((this.metrics.windowBytesTx / 1024).toFixed(2));
      this.metrics.rxRateKBps = Number((this.metrics.windowBytesRx / 1024).toFixed(2));
      this.metrics.windowBytesTx = 0;
      this.metrics.windowBytesRx = 0;

      let activeCount = 0;
      let graceCount = 0;
      for (const sess of this.sessions.values()) {
        if (sess.tcpSocket && !sess.tcpSocket.destroyed) activeCount++;
        if (sess.inGracePeriod) graceCount++;
      }
      this.metrics.activeTcpSockets = activeCount;
      this.metrics.graceHoldingSessions = graceCount;
    }, 1000);
    if (this._rateTimer.unref) this._rateTimer.unref();
  }

  isUpstreamAllowed(host, port) {
    const key = `${String(host).trim().toLowerCase()}:${Number(port)}`;
    if (this.allowedUpstreams.has('*')) return true;
    return this.allowedUpstreams.has(key);
  }

  async resolveSafeHost(host) {
    if (net.isIP(host)) {
      if (isPrivateOrLoopbackIp(host, this.allowLocalDebug)) {
        this.metrics.rejectedSsrfCount++;
        throw new Error(`SSRF Guard: Chặn kết nối tới IP nội bộ (${host})`);
      }
      return host;
    }
    const records = await dns.lookup(host, { all: true });
    if (!records || records.length === 0) {
      throw new Error(`Không phân giải được DNS cho ${host}`);
    }
    for (const r of records) {
      if (isPrivateOrLoopbackIp(r.address, this.allowLocalDebug)) {
        this.metrics.rejectedSsrfCount++;
        throw new Error(`SSRF Guard: DNS ${host} trỏ về IP nội bộ (${r.address})`);
      }
    }
    return records[0].address;
  }

  /**
   * Kiểm tra xem request upgrade có thuộc về `/ws-tcp-relay` (hoặc `/relay`) không
   */
  canHandleUpgrade(pathname) {
    return pathname === '/ws-tcp-relay' || pathname === '/relay';
  }

  handleUpgrade(req, socket, head, authInfo = {}) {
    this.wss.handleUpgrade(req, socket, head, (ws) => {
      this.wss.emit('connection', ws, req, authInfo);
    });
  }

  _handleWsConnection(ws, req, authInfo) {
    const urlObj = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    let sessionId = urlObj.searchParams.get('sessionId') || urlObj.searchParams.get('session') || '';
    const upstreamParam = urlObj.searchParams.get('upstream') || '27.0.14.78:19129';
    const slotId = urlObj.searchParams.get('slotId') || '1';
    const isVip = Boolean(authInfo && (authInfo.isVip || authInfo.role === 'admin' || authInfo.vipTier > 0));
    const gracePeriodMs = isVip ? VIP_GRACE_PERIOD_MS : DEFAULT_GRACE_PERIOD_MS;

    ws.isAlive = true;
    ws.lastPingAt = Date.now();

    // Nếu client truyền sẵn sessionId đang trong thời gian giữ phiên 60s/120s -> Khôi phục ngay
    if (sessionId && this.sessions.has(sessionId)) {
      this._resumeExistingSession(ws, sessionId, gracePeriodMs);
    } else if (urlObj.searchParams.get('autoconnect') === '1') {
      sessionId = sessionId || `sess_${crypto.randomBytes(8).toString('hex')}`;
      this._createOrAttachSession(ws, sessionId, upstreamParam, slotId, isVip, gracePeriodMs).catch((err) => {
        this._sendJsonSafe(ws, { type: 'RELAY_ERROR', message: err.message });
      });
    } else {
      // Gửi thông báo chào mừng & chờ lệnh OPEN_TCP hoặc RESUME_SESSION từ keepAliveEngine.js / Connector.java
      this._sendJsonSafe(ws, {
        type: 'RELAY_HELLO',
        serverTime: Date.now(),
        gracePeriodSec: gracePeriodMs / 1000,
        isVip
      });
    }

    ws.on('message', (data, isBinary) => {
      if (!isBinary) {
        let ctrl = null;
        try {
          const text = data.toString('utf8');
          if (text.startsWith('{')) {
            ctrl = JSON.parse(text);
          }
        } catch (_) {}

        if (ctrl && ctrl.type) {
          this._handleControlFrame(ws, ctrl, { upstreamParam, slotId, isVip, gracePeriodMs });
          return;
        }
      }

      // Khung nhị phân -> Đẩy thẳng xuống tcpSocket của phiên hiện tại
      const boundSession = ws._relaySession;
      if (boundSession && boundSession.tcpSocket && !boundSession.tcpSocket.destroyed) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        boundSession.bytesTx += buf.length;
        boundSession.lastActiveAt = Date.now();
        this.metrics.totalBytesTx += buf.length;
        this.metrics.windowBytesTx += buf.length;

        // Tự động ghi nhận gói tin nhịp tim (Heartbeat / Keep-Alive nhỏ 1..16 bytes) của client
        // để Server tự động bơm giữ kết nối 24/7 khi máy người chơi bị mất mạng
        if (buf.length >= 1 && buf.length <= 16) {
          boundSession.lastHeartbeatPacket = Buffer.from(buf);
        }

        const canContinue = boundSession.tcpSocket.write(buf);
        if (!canContinue && ws._socket && typeof ws._socket.pause === 'function') {
          ws._socket.pause();
          boundSession.tcpSocket.once('drain', () => {
            if (ws._socket && typeof ws._socket.resume === 'function') {
              ws._socket.resume();
            }
          });
        }
      }
    });

    ws.on('close', () => {
      this._enterGracePeriodForWs(ws);
    });

    ws.on('error', () => {
      this._enterGracePeriodForWs(ws);
    });
  }

  _handleControlFrame(ws, ctrl, meta) {
    switch (ctrl.type) {
      case 'HEARTBEAT_PING': {
        const now = Date.now();
        const clientTs = Number(ctrl.ts) || now;
        const rtt = Math.max(1, Math.min(999, now - clientTs));
        this.metrics.avgPingMs = Math.round(this.metrics.avgPingMs * 0.7 + rtt * 0.3);
        const sess = ws._relaySession;
        if (sess) sess.lastActiveAt = now;
        this._sendJsonSafe(ws, {
          type: 'HEARTBEAT_PONG',
          ts: ctrl.ts,
          serverTs: now,
          pingMs: this.metrics.avgPingMs,
          txKBps: this.metrics.txRateKBps,
          rxKBps: this.metrics.rxRateKBps,
          activeSockets: this.metrics.activeTcpSockets,
          sessionId: sess ? sess.sessionId : null
        });
        break;
      }

      case 'RESUME_SESSION': {
        const targetSid = String(ctrl.sessionId || '').trim();
        if (targetSid && this.sessions.has(targetSid)) {
          this._resumeExistingSession(ws, targetSid, meta.gracePeriodMs);
        } else {
          const newSid = targetSid || `sess_${crypto.randomBytes(8).toString('hex')}`;
          const targetUpstream = ctrl.upstream || meta.upstreamParam || '27.0.14.78:19129';
          this._createOrAttachSession(ws, newSid, targetUpstream, ctrl.slotId || meta.slotId, meta.isVip, meta.gracePeriodMs)
            .catch((err) => this._sendJsonSafe(ws, { type: 'RELAY_ERROR', message: err.message }));
        }
        break;
      }

      case 'OPEN_TCP': {
        const targetSid = String(ctrl.sessionId || `sess_${crypto.randomBytes(8).toString('hex')}`);
        const targetUpstream = `${ctrl.host || '27.0.14.78'}:${ctrl.port || 19129}`;
        this._createOrAttachSession(ws, targetSid, targetUpstream, ctrl.slotId || meta.slotId, meta.isVip, meta.gracePeriodMs)
          .catch((err) => this._sendJsonSafe(ws, { type: 'RELAY_ERROR', message: err.message }));
        break;
      }

      case 'CLOSE_TCP': {
        const sess = ws._relaySession;
        if (sess) {
          this._destroySession(sess.sessionId, 'CLIENT_REQUEST_CLOSE');
        }
        break;
      }

      default:
        break;
    }
  }

  async _createOrAttachSession(ws, sessionId, upstream, slotId, isVip, gracePeriodMs) {
    if (this.sessions.has(sessionId)) {
      return this._resumeExistingSession(ws, sessionId, gracePeriodMs);
    }

    const parts = String(upstream || '27.0.14.78:19129').split(':');
    const host = parts[0].trim();
    const port = Number(parts[1] || 19129);

    if (!this.isUpstreamAllowed(host, port)) {
      this.metrics.rejectedUpstreamCount++;
      throw new Error(`Đích ${host}:${port} không nằm trong danh sách cho phép (RELAY_ALLOWED_UPSTREAMS)`);
    }

    const safeIp = await this.resolveSafeHost(host);

    const tcpSocket = new net.Socket();
    tcpSocket.setNoDelay(true);
    tcpSocket.setKeepAlive(true, 15000);

    const session = {
      sessionId,
      slotId,
      upstream: `${host}:${port}`,
      resolvedIp: safeIp,
      isVip,
      gracePeriodMs,
      tcpSocket,
      ws,
      ringBuffer: new SessionRingBuffer(RING_BUFFER_MAX_BYTES),
      inGracePeriod: false,
      graceTimer: null,
      offlineHeartbeatTimer: null,
      lastHeartbeatPacket: null,
      createdAt: Date.now(),
      lastActiveAt: Date.now(),
      bytesTx: 0,
      bytesRx: 0
    };

    this.sessions.set(sessionId, session);
    ws._relaySession = session;

    tcpSocket.connect(port, safeIp, () => {
      this._sendJsonSafe(ws, {
        type: 'SESSION_READY',
        sessionId,
        slotId,
        upstream: session.upstream,
        gracePeriodSec: gracePeriodMs / 1000,
        resumed: false
      });
    });

    tcpSocket.on('data', (chunk) => {
      session.bytesRx += chunk.length;
      session.lastActiveAt = Date.now();
      this.metrics.totalBytesRx += chunk.length;
      this.metrics.windowBytesRx += chunk.length;

      const activeWs = session.ws;
      if (activeWs && activeWs.readyState === WebSocket.OPEN) {
        if (activeWs.bufferedAmount > 1024 * 1024) {
          tcpSocket.pause();
          setTimeout(() => {
            if (!tcpSocket.destroyed) tcpSocket.resume();
          }, 50);
        }
        activeWs.send(chunk, { binary: true });
      } else {
        // Đang trong Grace Period 60s/120s (Client rớt mạng tạm thời) -> Lưu vào Ring Buffer 64KB
        session.ringBuffer.push(chunk);
      }
    });

    tcpSocket.on('close', () => {
      if (session.ws && session.ws.readyState === WebSocket.OPEN) {
        this._sendJsonSafe(session.ws, {
          type: 'TCP_CLOSED',
          sessionId
        });
      }
      this._destroySession(sessionId, 'UPSTREAM_TCP_CLOSED');
    });

    tcpSocket.on('error', (err) => {
      if (session.ws && session.ws.readyState === WebSocket.OPEN) {
        this._sendJsonSafe(session.ws, {
          type: 'RELAY_ERROR',
          sessionId,
          message: `Lỗi TCP (${session.upstream}): ${err.message}`
        });
      }
      this._destroySession(sessionId, 'UPSTREAM_TCP_ERROR');
    });
  }

  _resumeExistingSession(ws, sessionId, gracePeriodMs) {
    const session = this.sessions.get(sessionId);
    if (!session || !session.tcpSocket || session.tcpSocket.destroyed) {
      this.sessions.delete(sessionId);
      this._sendJsonSafe(ws, {
        type: 'SESSION_EXPIRED',
        sessionId
      });
      return;
    }

    if (session.graceTimer) {
      clearTimeout(session.graceTimer);
      session.graceTimer = null;
    }
    if (session.offlineHeartbeatTimer) {
      clearInterval(session.offlineHeartbeatTimer);
      session.offlineHeartbeatTimer = null;
    }

    if (session.ws && session.ws !== ws) {
      try {
        session.ws._relaySession = null;
        session.ws.close(4001, 'Replaced by resumed connection');
      } catch (_) {}
    }

    session.ws = ws;
    session.inGracePeriod = false;
    session.gracePeriodMs = gracePeriodMs || session.gracePeriodMs;
    session.lastActiveAt = Date.now();
    ws._relaySession = session;
    this.metrics.resumedSessionCount++;

    const bufferedBytes = session.ringBuffer.drain();
    this._sendJsonSafe(ws, {
      type: 'SESSION_RESUMED',
      sessionId: session.sessionId,
      slotId: session.slotId,
      upstream: session.upstream,
      flushedBytes: bufferedBytes.length,
      gracePeriodSec: session.gracePeriodMs / 1000,
      hold247: true
    });

    if (bufferedBytes.length > 0 && ws.readyState === WebSocket.OPEN) {
      ws.send(bufferedBytes, { binary: true });
    }
  }

  _enterGracePeriodForWs(ws) {
    const session = ws._relaySession;
    if (!session) return;
    if (session.ws !== ws) return;

    session.ws = null;
    session.inGracePeriod = true;

    if (session.graceTimer) {
      clearTimeout(session.graceTimer);
    }
    if (session.offlineHeartbeatTimer) {
      clearInterval(session.offlineHeartbeatTimer);
    }

    // Tự động bơm nhịp tim (Heartbeat) định kỳ lên máy chủ game khi người chơi mất mạng
    // giúp giữ nhân vật đứng Online 24/7 chỉ với ~64KB RAM và ~0% CPU trên Server
    session.offlineHeartbeatTimer = setInterval(() => {
      if (!session.tcpSocket || session.tcpSocket.destroyed) {
        clearInterval(session.offlineHeartbeatTimer);
        session.offlineHeartbeatTimer = null;
        return;
      }
      if (session.lastHeartbeatPacket && session.lastHeartbeatPacket.length > 0) {
        try {
          session.tcpSocket.write(session.lastHeartbeatPacket);
          session.bytesTx += session.lastHeartbeatPacket.length;
          session.lastActiveAt = Date.now();
        } catch (_) {}
      }
    }, OFFLINE_HEARTBEAT_INTERVAL_MS);

    session.graceTimer = setTimeout(() => {
      this._destroySession(session.sessionId, 'GRACE_PERIOD_EXPIRED');
    }, session.gracePeriodMs);
  }

  _destroySession(sessionId, reason) {
    const session = this.sessions.get(sessionId);
    if (!session) return;
    this.sessions.delete(sessionId);

    if (session.graceTimer) {
      clearTimeout(session.graceTimer);
      session.graceTimer = null;
    }
    if (session.offlineHeartbeatTimer) {
      clearInterval(session.offlineHeartbeatTimer);
      session.offlineHeartbeatTimer = null;
    }
    if (session.tcpSocket && !session.tcpSocket.destroyed) {
      try {
        session.tcpSocket.destroy();
      } catch (_) {}
    }
    if (session.ws) {
      session.ws._relaySession = null;
    }
  }

  _sendJsonSafe(ws, payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(payload));
      } catch (_) {}
    }
  }

  getStatsSnapshot() {
    const activeSessions = [];
    for (const s of this.sessions.values()) {
      activeSessions.push({
        sessionId: s.sessionId,
        slotId: s.slotId,
        upstream: s.upstream,
        isVip: s.isVip,
        inGracePeriod: s.inGracePeriod,
        bufferedBytes: s.ringBuffer.size(),
        bytesTx: s.bytesTx,
        bytesRx: s.bytesRx,
        uptimeSec: Math.round((Date.now() - s.createdAt) / 1000)
      });
    }
    return {
      ...this.metrics,
      sessions: activeSessions
    };
  }
}

/**
 * Middleware Express xử lý `/api/net-proxy` (HTTP CORS Proxy cho J2ME Client tải listServer & tài nguyên)
 */
function createHttpNetProxyHandler(relayGateway) {
  return async function netProxyHandler(req, res) {
    try {
      const targetUrl = String(req.query.url || (req.body && req.body.url) || '').trim();
      if (!targetUrl || !/^https?:\/\//i.test(targetUrl)) {
        return res.status(400).json({ error: 'Thiếu hoặc sai định dạng tham số url (http/https).' });
      }

      const parsed = new URL(targetUrl);
      const port = Number(parsed.port) || (parsed.protocol === 'https:' ? 443 : 80);
      await relayGateway.resolveSafeHost(parsed.hostname);

      const clientLib = parsed.protocol === 'https:' ? https : http;
      const proxyReq = clientLib.request(
        targetUrl,
        {
          method: req.method === 'POST' ? 'POST' : 'GET',
          headers: {
            'User-Agent': 'Profile/MIDP-2.0 Configuration/CLDC-1.1',
            'Accept': '*/*',
            'Connection': 'close'
          },
          timeout: 12000
        },
        (proxyRes) => {
          res.status(proxyRes.statusCode || 200);
          res.setHeader('Access-Control-Allow-Origin', '*');
          res.setHeader('Content-Type', proxyRes.headers['content-type'] || 'text/plain; charset=utf-8');
          proxyRes.pipe(res);
        }
      );

      proxyReq.on('error', (err) => {
        if (!res.headersSent) {
          res.status(502).json({ error: `Lỗi kết nối upstream HTTP: ${err.message}` });
        }
      });

      proxyReq.on('timeout', () => {
        proxyReq.destroy();
        if (!res.headersSent) {
          res.status(504).json({ error: 'Upstream HTTP quá thời gian chờ (12s).' });
        }
      });

      proxyReq.end();
    } catch (err) {
      return res.status(403).json({ error: err.message });
    }
  };
}

module.exports = {
  NetworkRelayGateway,
  SessionRingBuffer,
  createHttpNetProxyHandler,
  DEFAULT_ALLOWED_UPSTREAMS
};
