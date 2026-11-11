#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# J2ME CLOUD PHONE OS 24/7 (VIP ADMIN - DUY TRƯỜNG)
# Script khởi chạy tự động trên Android Termux + Public qua Cloudflare Tunnel
# Tự động khắc phục triệt để lỗi libicui18n.so / libicu mismatch trên Termux
# ==============================================================================

set -e

PORT="${PORT:-3000}"
CLOUDFLARE_TOKEN="${CLOUDFLARE_TOKEN:-}"

echo "================================================================"
echo "  👑 J2ME CLOUD PHONE OS & NETWORK GATEWAY 24/7 - DUY TRƯỜNG"
echo "================================================================"

# 1. Bật termux-wake-lock giữ mạng và CPU 24/7 trên điện thoại
if command -v termux-wake-lock >/dev/null 2>&1; then
  echo "[1/4] Đang bật termux-wake-lock giữ mạng 24/7..."
  termux-wake-lock || true
fi

# 2. Kiểm tra node có bị lỗi thư viện libicu/libicui18n.so không
NEED_FIX_NODE=0
if ! command -v node >/dev/null 2>&1; then
  NEED_FIX_NODE=1
elif ! node -v >/dev/null 2>&1; then
  echo "⚠️ Phát hiện Node.js bị lệch phiên bản thư viện ICU (libicui18n.so)!"
  NEED_FIX_NODE=1
fi

if [ "$NEED_FIX_NODE" -eq 1 ]; then
  echo "[2/4] Đang đồng bộ và cập nhật gói Termux (fix libicui18n.so)..."
  pkg update -y
  pkg install -y libicu nodejs-lts
  # Nếu vẫn bị thì thử gói nodejs thường
  if ! node -v >/dev/null 2>&1; then
    echo "[2/4] Đang chuyển sang nodejs..."
    pkg install -y nodejs
  fi
fi

# Kiểm tra cloudflared
if ! command -v cloudflared >/dev/null 2>&1; then
  echo "[2/4] Đang cài đặt cloudflared..."
  pkg install -y cloudflared || true
fi

echo "🟢 Phiên bản Node.js: $(node -v 2>/dev/null || echo 'Chưa nhận diện')"

# 3. Cài đặt thư viện npm (100% Pure JS, không cần biên dịch)
if [ ! -d "node_modules" ]; then
  echo "[3/4] Đang cài đặt thư viện npm cho Web Server..."
  npm install --omit=dev --no-audit --no-fund
fi

# 4. Khởi chạy Web Server giữ mạng 24/7
echo "[4/4] Đang khởi động Web Server giữ mạng 24/7 tại cổng ${PORT}..."
pkill -f "node server.js" >/dev/null 2>&1 || true
CLOUD_VM_MODE=0 PORT="${PORT}" nohup node server.js > termux-server.log 2>&1 &
SERVER_PID=$!
sleep 2

echo "----------------------------------------------------------------"
echo "✅ Web Server đã chạy (PID: ${SERVER_PID}) - Log: termux-server.log"
echo "🌐 Local URL: http://127.0.0.1:${PORT}"
echo "----------------------------------------------------------------"

# 5. Mở Cloudflare Tunnel ra Internet
if [ -n "${CLOUDFLARE_TOKEN}" ]; then
  echo "🚀 Đang mở Cloudflare Tunnel với Token tên miền riêng..."
  cloudflared tunnel --no-autoupdate run --token "${CLOUDFLARE_TOKEN}"
else
  echo "🚀 Đang mở Cloudflare Quick Tunnel miễn phí (sẽ in link https://....trycloudflare.com bên dưới)..."
  cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:${PORT}"
fi
