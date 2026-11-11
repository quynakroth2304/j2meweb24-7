let isRegisterMode = false;
let currentUser = null;

const alertBox = document.getElementById('alertBox');
const authSection = document.getElementById('authSection');
const dashboardSection = document.getElementById('dashboardSection');
const navUserSection = document.getElementById('navUserSection');
const adminLinkBtn = document.getElementById('adminLinkBtn');

function showAlert(message, isError = false) {
  alertBox.textContent = message;
  alertBox.style.borderColor = isError ? '#ef4444' : '#10b981';
  alertBox.style.color = isError ? '#fca5a5' : '#a7f3d0';
  alertBox.classList.remove('hidden');
  setTimeout(() => alertBox.classList.add('hidden'), 5000);
}

function formatBytes(bytes) {
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

async function apiFetch(url, options = {}) {
  const headers = options.headers || {};
  headers['X-Requested-With'] = 'XMLHttpRequest';
  if (!(options.body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(url, { ...options, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || 'Đã xảy ra lỗi kết nối máy chủ.');
  }
  return data;
}

// Chuyển đổi giữa chế độ Đăng nhập và Đăng ký
document.getElementById('authSwitchBtn').addEventListener('click', (e) => {
  e.preventDefault();
  isRegisterMode = !isRegisterMode;
  document.getElementById('authFormTitle').textContent = isRegisterMode
    ? 'Đăng ký tài khoản mới'
    : 'Đăng nhập tài khoản';
  document.getElementById('authSubmitBtn').textContent = isRegisterMode ? 'Đăng ký ngay' : 'Đăng nhập';
  document.getElementById('authSwitchText').textContent = isRegisterMode
    ? 'Đã có tài khoản?'
    : 'Chưa có tài khoản?';
  document.getElementById('authSwitchBtn').textContent = isRegisterMode ? 'Đăng nhập' : 'Đăng ký ngay';
});

// Xử lý submit form Đăng nhập / Đăng ký
document.getElementById('authForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  const endpoint = isRegisterMode ? '/api/auth/register' : '/api/auth/login';

  try {
    const res = await apiFetch(endpoint, {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    showAlert(res.message, false);
    await checkSessionAndLoadDashboard();
  } catch (err) {
    showAlert(err.message, true);
  }
});

// Đăng xuất
document.getElementById('logoutBtn').addEventListener('click', async () => {
  try {
    await apiFetch('/api/auth/logout', { method: 'POST' });
    window.location.reload();
  } catch (err) {
    showAlert(err.message, true);
  }
});

// Lưu cài đặt cá nhân
document.getElementById('settingsForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    const payload = {
      screenSize: document.getElementById('prefScreenSize').value,
      sound: document.getElementById('prefSound').checked,
      vibration: document.getElementById('prefVibration').checked,
      keypadScale: parseFloat(document.getElementById('prefKeypadScale').value) || 1.0
    };
    const res = await apiFetch('/api/auth/settings', {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
    showAlert(res.message, false);
  } catch (err) {
    showAlert(err.message, true);
  }
});

// Tải lên game .jar/.jad mới
document.getElementById('uploadGameForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const jarInput = document.getElementById('jarFile');
  const jadInput = document.getElementById('jadFile');
  const uploadBtn = document.getElementById('uploadBtn');

  if (!jarInput.files[0]) {
    return showAlert('Vui lòng chọn file .jar cần tải lên.', true);
  }

  const formData = new FormData();
  formData.append('jarFile', jarInput.files[0]);
  if (jadInput.files[0]) {
    formData.append('jadFile', jadInput.files[0]);
  }
  formData.append('title', document.getElementById('gameTitle').value.trim());
  formData.append('resolution', document.getElementById('gameResolution').value);

  uploadBtn.disabled = true;
  uploadBtn.textContent = '⏳ Đang kiểm tra & tải lên...';

  try {
    const res = await apiFetch('/api/games/upload', {
      method: 'POST',
      body: formData
    });
    showAlert(res.message, false);
    document.getElementById('uploadGameForm').reset();
    await checkSessionAndLoadDashboard();
  } catch (err) {
    showAlert(err.message, true);
  } finally {
    uploadBtn.disabled = false;
    uploadBtn.textContent = '🚀 Tải lên Kho Game Cá Nhân';
  }
});

// Tải danh sách game của user
async function loadUserGames() {
  const container = document.getElementById('gamesList');
  try {
    const { games } = await apiFetch('/api/games');
    if (!games || games.length === 0) {
      container.innerHTML = `<p style="color: var(--text-muted);">Kho game của bạn đang trống. Hãy tải lên một file .jar ở khung phía trên để bắt đầu chơi!</p>`;
      return;
    }

    container.innerHTML = '';
    games.forEach((game) => {
      const card = document.createElement('div');
      card.className = 'game-item';

      const saveBadge = game.has_save
        ? `<span class="badge badge-save">💾 Đã có Save (RMS)</span>`
        : `<span class="badge">Chưa có Save</span>`;

      card.innerHTML = `
        <div>
          <div class="game-title">${escapeHtml(game.title)}</div>
          <div class="game-meta" style="margin-top: 6px;">
            <span>Nhà phát triển: ${escapeHtml(game.vendor)} (v${escapeHtml(game.version)})</span>
            <span>Dung lượng: ${(game.file_size_bytes / 1024).toFixed(1)} KB | Màn hình: <strong>${escapeHtml(game.resolution)}</strong></span>
            <div style="margin-top: 4px;">${saveBadge}</div>
          </div>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px;">
          <a href="/play.html?id=${game.id}" class="btn btn-success btn-sm" style="flex: 1;">▶️ Chơi ngay</a>
          <button class="btn btn-outline btn-sm" onclick="changeGameResolution(${game.id}, '${game.resolution}')">📐 Đổi cỡ</button>
          <button class="btn btn-danger btn-sm" onclick="deleteGame(${game.id}, '${escapeHtml(game.title)}')">🗑️</button>
        </div>
      `;
      container.appendChild(card);
    });
  } catch (err) {
    showAlert(err.message, true);
  }
}

window.changeGameResolution = async function (gameId, currentRes) {
  const newRes = prompt(
    'Nhập độ phân giải màn hình cho game (128x128, 128x160, 176x208, 240x320, 320x240, 360x640):',
    currentRes
  );
  if (!newRes || newRes === currentRes) return;
  try {
    const res = await apiFetch(`/api/games/${gameId}/resolution`, {
      method: 'PATCH',
      body: JSON.stringify({ resolution: newRes.trim() })
    });
    showAlert(res.message, false);
    await loadUserGames();
  } catch (err) {
    showAlert(err.message, true);
  }
};

window.deleteGame = async function (gameId, title) {
  if (!confirm(`Bạn có chắc muốn xóa game "${title}" cùng toàn bộ dữ liệu Save (RMS) của game này không?`)) {
    return;
  }
  try {
    const res = await apiFetch(`/api/games/${gameId}`, { method: 'DELETE' });
    showAlert(res.message, false);
    await checkSessionAndLoadDashboard();
  } catch (err) {
    showAlert(err.message, true);
  }
};

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

async function checkSessionAndLoadDashboard() {
  try {
    const { user } = await apiFetch('/api/auth/me');
    currentUser = user;

    authSection.classList.add('hidden');
    dashboardSection.classList.remove('hidden');
    navUserSection.classList.remove('hidden');

    document.getElementById('welcomeUser').textContent = `Xin chào, ${user.username}`;
    if (user.role === 'admin') {
      adminLinkBtn.classList.remove('hidden');
    } else {
      adminLinkBtn.classList.add('hidden');
    }

    // Hiển thị hạn mức
    document.getElementById('quotaGamesText').textContent = `${user.usedGames} / ${user.maxGames} game`;
    document.getElementById('quotaStorageText').textContent = `${formatBytes(user.usedStorageBytes)} / ${formatBytes(user.maxStorageBytes)}`;
    const percent = Math.min(100, Math.round((user.usedStorageBytes / user.maxStorageBytes) * 100));
    document.getElementById('quotaBarFill').style.width = `${percent}%`;

    // Hiển thị cài đặt cá nhân
    if (user.settings) {
      document.getElementById('prefScreenSize').value = user.settings.screenSize || '240x320';
      document.getElementById('gameResolution').value = user.settings.screenSize || '240x320';
      document.getElementById('prefSound').checked = user.settings.sound !== false;
      document.getElementById('prefVibration').checked = user.settings.vibration !== false;
      document.getElementById('prefKeypadScale').value = user.settings.keypadScale || 1.0;
    }

    await loadUserGames();
  } catch (_) {
    authSection.classList.remove('hidden');
    dashboardSection.classList.add('hidden');
    navUserSection.classList.add('hidden');
  }
}

checkSessionAndLoadDashboard();