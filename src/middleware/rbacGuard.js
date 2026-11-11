// src/middleware/rbacGuard.js — Middleware Phân Quyền 2 Lớp (ADMIN vs USER) & Tự Động Gia Hạn Token Mạng Kép
'use strict';

const jwt = require('jsonwebtoken');
const config = require('../../config');

function extractAccessToken(req) {
  if (req.cookies && req.cookies[config.COOKIE_NAME]) {
    return req.cookies[config.COOKIE_NAME];
  }
  const authHeader = req.headers.authorization;
  if (authHeader && authHeader.startsWith('Bearer ')) {
    return authHeader.slice(7).trim();
  }
  return null;
}

function extractRefreshToken(req) {
  if (req.headers['x-refresh-token']) {
    return String(req.headers['x-refresh-token']).trim();
  }
  if (req.cookies && req.cookies.j2me_refresh_token) {
    return req.cookies.j2me_refresh_token;
  }
  return null;
}

function createRbacMiddlewares({ userStore, dualSessionStore }) {
  function verifyUserFromRequest(req, res = null) {
    const token = extractAccessToken(req);
    if (token) {
      try {
        const payload = jwt.verify(token, config.JWT_SECRET);
        const user = userStore.findById(payload.id);
        if (user && !user.locked) {
          dualSessionStore.touchSessionByUser(user.id);
          return userStore.sanitize(user);
        }
      } catch (_) {
        // Nếu Access Token hết hạn, tự động kiểm tra Refresh Token bên dưới
      }
    }

    const rtk = extractRefreshToken(req);
    if (rtk) {
      const refreshed = dualSessionStore.refreshAccessToken(rtk, userStore);
      if (refreshed && refreshed.user) {
        if (res && typeof res.cookie === 'function') {
          res.cookie(config.COOKIE_NAME, refreshed.accessToken, {
            httpOnly: true,
            sameSite: 'lax',
            secure: config.IS_PROD,
            maxAge: 7 * 24 * 3600 * 1000
          });
          res.setHeader('X-Refreshed-Access-Token', refreshed.accessToken);
        }
        return refreshed.user;
      }
    }

    return null;
  }

  function requireAuth(req, res, next) {
    const user = verifyUserFromRequest(req, res);
    if (!user) {
      return res.status(401).json({
        error: 'Phiên đăng nhập đã hết hạn hoặc bị thu hồi. Vui lòng đăng nhập lại.',
        code: 'AUTH_REQUIRED'
      });
    }
    req.user = user;
    next();
  }

  function requireAdmin(req, res, next) {
    const user = req.user || verifyUserFromRequest(req, res);
    if (!user) {
      return res.status(401).json({
        error: 'Vui lòng đăng nhập tài khoản Quản trị viên (Admin).',
        code: 'AUTH_REQUIRED'
      });
    }
    if (user.role !== 'admin') {
      return res.status(403).json({
        error: '403 Forbidden: Tài khoản User không được phép truy cập API hoặc chức năng của Admin.',
        code: 'FORBIDDEN_ADMIN_ONLY',
        redirectTo: '/app'
      });
    }
    req.user = user;
    next();
  }

  function guardAdminPageRoute(req, res, next) {
    const user = verifyUserFromRequest(req, res);
    if (user && user.role !== 'admin') {
      // Nếu là user thường cố tình truy cập /admin hoặc /admin.html -> chặn ngay và đẩy về trang người chơi /app
      return res.redirect(302, '/play.html?error=forbidden_admin');
    }
    next();
  }

  return {
    extractTokenFromReq: extractAccessToken,
    extractAccessToken,
    extractRefreshToken,
    verifyUserFromRequest,
    requireAuth,
    requireAdmin,
    guardAdminPageRoute
  };
}

module.exports = {
  createRbacMiddlewares,
  extractTokenFromReq: extractAccessToken,
  extractAccessToken,
  extractRefreshToken
};
