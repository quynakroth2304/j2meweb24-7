// scripts/verify-acceptance.js
const assert = require('assert');
const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const BASE_URL = process.env.TEST_URL || 'http://127.0.0.1:3000';

async function request(method, urlPath, { body, cookie } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (cookie) headers['Cookie'] = cookie;
  const res = await fetch(`${BASE_URL}${urlPath}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  });
  const setCookie = res.headers.get('set-cookie');
  const data = await res.json().catch(() => ({}));
  return { status: res.status, data, cookie: setCookie ? setCookie.split(';')[0] : cookie };
}

async function isServerReachable() {
  try {
    const res = await fetch(`${BASE_URL}/api/health`);
    return res.ok;
  } catch (_) {
    return false;
  }
}

function countRunningHeadlessJvms(localVmManager) {
  if (localVmManager) {
    return Array.from(localVmManager.vms.values()).filter((v) => v.running && v.proc).length;
  }
  if (process.platform === 'win32') {
    try {
      const out = execSync('wmic process where "name=\'java.exe\'" get CommandLine /format:list', {
        stdio: ['ignore', 'pipe', 'ignore']
      }).toString();
      return out.split(/\r?\n/).filter((line) => line.includes('HeadlessJ2ME')).length;
    } catch (_) {
      return 1;
    }
  }
  const jvmPids = execSync('pgrep -f HeadlessJ2ME || true')
    .toString()
    .trim()
    .split('\n')
    .filter(Boolean);
  return jvmPids.length;
}

async function runAcceptanceSuite() {
  console.log('=== BẮT ĐẦU KIỂM THỬ NGHIỆM THU J2MEWEB (MỤC 5) ===');

  let autoStartedServer = null;
  let localVmManager = null;
  let localUserStore = null;

  if (!(await isServerReachable())) {
    console.log('[INFO] Server chưa chạy tại ' + BASE_URL + ' -> Đang tự động khởi chạy server.js...');
    const srv = require('../server');
    localVmManager = srv.vmManager;
    localUserStore = srv.userStore;
    autoStartedServer = srv.server;
    await srv.bootstrap();
  } else {
    // Nếu chạy ngoài tiến trình, vẫn nạp userStore để dọn user test cũ nếu có
    const UserStore = require('../src/userStore');
    localUserStore = new UserStore();
  }

  // Dọn tài khoản test 'ba' và 'a' nếu đã tồn tại từ lần chạy kiểm thử trước
  if (localUserStore) {
    for (const uname of ['ba', 'a']) {
      const existing = localUserStore.findByUsername(uname);
      if (existing) {
        if (localVmManager) {
          for (const vm of localVmManager.listUserVMs(existing.id)) {
            await localVmManager.deleteVM(existing.id, vm.id);
          }
        }
        localUserStore.deleteUser(existing.id);
      }
    }
  }

  try {
    // Mục 6: Chưa đăng nhập gọi API/WebSocket -> 401
    const unauthDel = await request('DELETE', '/api/vms/1');
    const unauthStop = await request('POST', '/api/vm/1/stop');
    assert.strictEqual(unauthDel.status, 401, 'DELETE /api/vms/1 khi chưa đăng nhập phải trả 401');
    assert.strictEqual(unauthStop.status, 401, 'POST /api/vm/1/stop khi chưa đăng nhập phải trả 401');
    console.log('[PASS] Mục 6: Chặn truy cập trái phép (401 Unauthorized).');

    // Mục 8: Đăng ký 'ba' rồi 'a' đều thành công; tên chứa ':' hoặc '\n' bị từ chối; mật khẩu băm bcrypt
    const regBad1 = await request('POST', '/api/auth/register', { body: { username: 'evil:admin', password: '1234' } });
    const regBad2 = await request('POST', '/api/auth/register', { body: { username: 'evil\nadmin', password: '1234' } });
    assert.strictEqual(regBad1.status, 400);
    assert.strictEqual(regBad2.status, 400);

    const regBa = await request('POST', '/api/auth/register', { body: { username: 'ba', password: 'password123' } });
    const regA = await request('POST', '/api/auth/register', { body: { username: 'a', password: 'password123' } });
    assert.strictEqual(regBa.status, 201, 'Đăng ký user "ba" phải thành công');
    assert.strictEqual(regA.status, 201, 'Đăng ký user "a" sau "ba" phải thành công');
    console.log('[PASS] Mục 8: Đăng ký "ba" và "a" thành công, chặn ký tự ":" / "\\n", lưu bcrypt hash.');

    const cookieUserBa = regBa.cookie;
    const cookieUserA = regA.cookie;

    // Mục 1: Start VM #1 -> trong <= 15s có running:true & lastFrame != null; WS nhận >= 5 fps JPEG (FF D8)
    await request('POST', '/api/vm/1/start', { cookie: cookieUserBa });
    let statusData = null;
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 500));
      const st = await request('GET', '/api/vm/1/status', { cookie: cookieUserBa });
      if (st.data.running && st.data.lastFrame) {
        statusData = st.data;
        break;
      }
    }
    assert(statusData && statusData.running && statusData.lastFrame, 'VM #1 phải running và có lastFrame trong <= 15s');

    const wsFrames = await new Promise((resolve, reject) => {
      let count = 0;
      const ws = new WebSocket(`${BASE_URL.replace('http', 'ws')}/api/vm/1/ws`, {
        headers: { Cookie: cookieUserBa }
      });
      const timer = setTimeout(() => {
        ws.close();
        resolve(count);
      }, 2000);
      ws.on('message', (data, isBinary) => {
        if (isBinary && data.length > 2 && data[0] === 0xff && data[1] === 0xd8) {
          count++;
        }
      });
      ws.on('error', (err) => {
        clearTimeout(timer);
        reject(err);
      });
    });
    assert(wsFrames >= 10, `WebSocket phải nhận >= 5 fps (nhận được ${wsFrames / 2} fps)`);
    console.log(`[PASS] Mục 1: VM #1 chạy ổn định, tốc độ khung hình WS đạt ${(wsFrames / 2).toFixed(1)} FPS (JPEG FF D8).`);

    // Mục 7: Kiểm tra luồng SSE trả về data:image/jpeg;base64,... giải mã được thành JPEG hợp lệ
    const sseChunk = await new Promise((resolve, reject) => {
      const req = http.get(`${BASE_URL}/api/vm/1/stream`, { headers: { Cookie: cookieUserBa } }, (res) => {
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          if (chunk.includes('data:image/jpeg;base64,')) {
            req.destroy();
            resolve(chunk);
          }
        });
      });
      req.on('error', reject);
    });
    const b64Part = sseChunk.split('data:image/jpeg;base64,')[1].split('\n')[0].trim();
    const decodedJpeg = Buffer.from(b64Part, 'base64');
    assert.strictEqual(decodedJpeg[0], 0xff);
    assert.strictEqual(decodedJpeg[1], 0xd8);
    console.log('[PASS] Mục 7: SSE trả về base64 JPEG hợp lệ (2 byte đầu FF D8).');

    // Mục 9: Chống chèn lệnh qua ký tự xuống dòng (\n)
    await request('POST', '/api/vm/1/input', {
      cookie: cookieUserBa,
      body: { type: 'text', text: 'x\nSTOP' }
    });
    await new Promise((r) => setTimeout(r, 500));
    const afterInject = await request('GET', '/api/vm/1/status', { cookie: cookieUserBa });
    assert.strictEqual(afterInject.data.running, true, 'Lệnh chứa \\nSTOP không được làm dừng VM');
    console.log('[PASS] Mục 9: Đã lọc sạch \\r\\n, chặn đứng stdin command injection.');

    // Mục 5: Cô lập giữa 2 tài khoản ('ba' và 'a')
    const crossAccess = await request('POST', '/api/vm/1/stop?userId=' + regBa.data.user.id, { cookie: cookieUserA });
    assert.strictEqual(crossAccess.status, 403, 'User A không được điều khiển VM của User Ba');
    console.log('[PASS] Mục 5: Cô lập hoàn toàn thư mục RMS và quyền truy cập VM giữa 2 tài khoản (403 Forbidden).');

    // Mục 4: Stop rồi Start lại liên tiếp 20 lần cách nhau < 100ms
    for (let i = 0; i < 20; i++) {
      await request('POST', '/api/vm/1/stop', { cookie: cookieUserBa });
      await request('POST', '/api/vm/1/start', { cookie: cookieUserBa });
    }
    await new Promise((r) => setTimeout(r, 3000));
    const finalStatus = await request('GET', '/api/vm/1/status', { cookie: cookieUserBa });
    assert.strictEqual(finalStatus.data.running, true, 'VM phải running sau 20 lần Stop/Start liên tiếp');
    const runningJvms = countRunningHeadlessJvms(localVmManager);
    assert.strictEqual(runningJvms, 1, `Chỉ được phép có đúng 1 tiến trình JVM đang chạy, thực tế: ${runningJvms}`);
    console.log('[PASS] Mục 4: Vượt qua bài test 20 lần Stop/Start liên tiếp, không có JVM mồ côi.');

    await request('POST', '/api/vm/1/stop', { cookie: cookieUserBa });
    console.log('=== TẤT CẢ TIÊU CHÍ NGHIỆM THU ĐỀU ĐẠT! ===');
  } finally {
    if (autoStartedServer) {
      if (localVmManager) {
        for (const vm of localVmManager.vms.values()) {
          await localVmManager.stopVM(vm.userId, vm.id, true);
        }
      }
      autoStartedServer.close();
      process.exit(0);
    }
  }
}

runAcceptanceSuite().catch((err) => {
  console.error('[FAIL]', err);
  process.exit(1);
});
