// config.js
const path = require('path');

const isProd = process.env.NODE_ENV === 'production' || Boolean(process.env.RENDER);
let jwtSecret = process.env.JWT_SECRET;

if (!jwtSecret || jwtSecret.trim().length < 16) {
  jwtSecret = 'j2me_cloud_default_secret_key_2026_secure_fallback';
  console.warn(
    '[WARN] Biến môi trường JWT_SECRET chưa được thiết lập hoặc ngắn hơn 16 ký tự. ' +
    'Đang sử dụng khóa mặc định. Khuyến nghị đặt JWT_SECRET riêng khi triển khai thực tế.'
  );
}

module.exports = {
  PORT: Number(process.env.PORT) || 3000,
  JWT_SECRET: jwtSecret,
  JWT_EXPIRES_IN: '7d',
  COOKIE_NAME: 'j2me_token',
  IS_PROD: isProd,
  DATA_DIR: process.env.DATA_DIR || path.join(__dirname, 'data'),

  // Giới hạn bảo mật cho Upload JAR & Chống Zip-Bomb (P1-6)
  MAX_SINGLE_FILE_BYTES: 15 * 1024 * 1024,        // 15 MB mỗi file .jar tải lên
  MAX_UNCOMPRESSED_JAR_BYTES: 50 * 1024 * 1024,   // 50 MB tổng dung lượng sau giải nén
  MAX_JAR_ENTRIES: 4000,                          // Tối đa 4.000 file bên trong .jar

  // Giới hạn mặc định cho mỗi tài khoản người dùng (P1-1)
  DEFAULT_MAX_VMS: Number(process.env.DEFAULT_MAX_VMS) || 5,
  DEFAULT_STORAGE_QUOTA_BYTES: 100 * 1024 * 1024, // 100 MB / tài khoản
  DEFAULT_MAX_CONCURRENT_SESSIONS: Number(process.env.MAX_CCU) || 50,

  // Giới hạn WebSocket & Watchdog (P0-3, P2-1)
  WS_MAX_PAYLOAD_BYTES: 8192,                     // 8 KB
  MAX_VIEWERS_PER_VM: 50,
  WATCHDOG_BACKOFF_MS: [5000, 10000, 30000, 60000],
  WATCHDOG_MAX_RETRIES: 5,
  WATCHDOG_STABLE_RESET_MS: 60000
};
