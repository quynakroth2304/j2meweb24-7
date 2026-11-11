// public/js/admin/adminApp.js — Khu vực Quản Trị Viên (ADMIN PORTAL) độc lập + Chặn RBAC 2 Lớp + Quản lý Sự kiện, Đơn Nạp QR Techcombank, Đơn Thuê 24/7 & Phiên Mạng Kép
(function () {
  'use strict';

  const dualClient = window.DualConnectionClient
    ? new window.DualConnectionClient({
        onStatusChange: (st) => updateHeaderStatus(st)
      })
    : null;

  function showToast(message) {
    const stack = document.getElementById('toastStack');
    if (!stack) return;
    const item = document.createElement('div');
    item.className = 'cyber-toast';
    item.textContent = message;
    stack.appendChild(item);
    setTimeout(() => {
      item.remove();
    }, 3800);
  }

  function formatVnd(amount) {
    return Number(amount || 0).toLocaleString('vi-VN') + 'đ';
  }

  function formatBytes(bytes) {
    const b = Number(bytes) || 0;
    if (b < 1024) return `${b} B`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
    return `${(b / (1024 * 1024)).toFixed(2)} MB`;
  }

  function escapeHtml(str) {
    return String(str ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function authHeaders() {
    if (dualClient) return dualClient.authHeaders();
    const token = localStorage.getItem('duytruong_auth_token') || '';
    return {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    };
  }

  function updateHeaderStatus(st) {
    const el = document.getElementById('adminHeaderStatus');
    if (!el) return;
    const u = dualClient ? dualClient.getUser() : null;
    const online = st ? st.isOnline : (dualClient ? dualClient.isOnline : navigator.onLine !== false);
    el.innerHTML = u
      ? `👑 Đang đăng nhập: <b style="color:#fbbf24;">${escapeHtml(u.username)}</b> (Role: <b>${escapeHtml(u.role)}</b>) • Mạng Kép: <b style="color:${online ? '#10b981' : '#f59e0b'};">${online ? 'ONLINE (Grace Period 30 phút)' : 'OFFLINE'}</b>`
      : 'Đang xác thực quyền Quản trị viên...';
  }

  // ==================== CHẶN PHÂN QUYỀN FRONTEND (ROUTE GUARD) ====================
  async function verifyAdminAccessAndInit() {
    const forbiddenBox = document.getElementById('adminForbiddenBox');
    const workspace = document.getElementById('adminMainWorkspace');

    try {
      const res = dualClient
        ? await dualClient.fetchWithAuth('/api/auth/me')
        : await fetch('/api/auth/me', { headers: authHeaders(), credentials: 'include' });

      if (res.status === 401) {
        window.location.replace('/login.html');
        return;
      }

      const data = await res.json();
      const user = data && data.user ? data.user : (dualClient ? dualClient.getUser() : null);

      if (!user || user.role !== 'admin') {
        // Nếu là tài khoản user thường cố vào /admin -> hiện 403 và tự chuyển về /app
        if (forbiddenBox) forbiddenBox.style.display = 'block';
        if (workspace) workspace.style.display = 'none';
        setTimeout(() => {
          window.location.replace('/play.html?error=forbidden_admin');
        }, 1200);
        return;
      }

      if (dualClient && data.user) {
        dualClient.saveSession({
          user: data.user,
          wallet: data.wallet
        });
      }

      if (forbiddenBox) forbiddenBox.style.display = 'none';
      if (workspace) workspace.style.display = 'flex';
      updateHeaderStatus();
      await Promise.all([loadCommerceOverview(), loadSystemOverview()]);
    } catch (_) {
      // Nếu mất mạng tạm thời nhưng trong bộ nhớ bền vững đã xác nhận role === 'admin'
      const cachedUser = dualClient ? dualClient.getUser() : null;
      if (cachedUser && cachedUser.role === 'admin') {
        if (workspace) workspace.style.display = 'flex';
        updateHeaderStatus();
        showToast('📡 Đang Offline: Giữ nguyên phiên Admin cục bộ (Mạng Kép), dữ liệu sẽ làm mới khi có mạng!');
      } else {
        if (forbiddenBox) forbiddenBox.style.display = 'block';
      }
    }
  }

  // ==================== TẢI DỮ LIỆU THƯƠNG MẠI, ĐƠN NẠP QR, ĐƠN UP THUÊ & PHIÊN MẠNG KÉP ====================
  async function loadCommerceOverview() {
    try {
      const res = dualClient
        ? await dualClient.fetchWithAuth('/api/admin/commerce/overview')
        : await fetch('/api/admin/commerce/overview', { headers: authHeaders() });

      if (res.status === 403) {
        window.location.replace('/play.html?error=forbidden_admin');
        return;
      }
      const data = await res.json();
      if (!data || !data.success) return;

      // 1. Sự kiện khuyến mãi
      if (data.eventConfig) {
        const titleInput = document.getElementById('adminEventTitle');
        const bannerInput = document.getElementById('adminEventBanner');
        const discInput = document.getElementById('adminDiscountPct');
        const multInput = document.getElementById('adminTopupMult');
        const activeChk = document.getElementById('adminEventActive');
        if (titleInput) titleInput.value = data.eventConfig.title || '';
        if (bannerInput) bannerInput.value = data.eventConfig.bannerText || '';
        if (discInput) discInput.value = data.eventConfig.discountPercent ?? 0;
        if (multInput) multInput.value = data.eventConfig.topupMultiplier ?? 1;
        if (activeChk) activeChk.checked = data.eventConfig.active !== false;
      }

      // 2. Đơn nạp QR Techcombank
      const topupBox = document.getElementById('adminTopupOrdersBox');
      if (topupBox) {
        const list = data.topupOrders || [];
        if (list.length === 0) {
          topupBox.textContent = 'Chưa có đơn nạp QR Techcombank nào.';
        } else {
          topupBox.innerHTML = list
            .slice(0, 20)
            .map(
              (t) => `
              <div style="padding:8px; border-bottom:1px solid rgba(148,163,184,0.15); display:flex; justify-content:space-between; align-items:center; gap:8px;">
                <div>
                  <div style="color:#fbbf24; font-weight:700;">
                    #${escapeHtml(t.code || t.id)} • ${escapeHtml(t.username)} nạp ${formatVnd(t.amountVnd)}
                    (Thực nhận: ${formatVnd(t.creditedAmountVnd || t.creditedVnd)} • X${t.multiplierApplied || t.multiplier || 1})
                  </div>
                  <div style="color:#9be7ff; margin-top:2px;">
                    Nội dung CK: <b>${escapeHtml(t.transferContent)}</b> • Nguồn: ${escapeHtml(t.platform || 'Web/App')} • Trạng thái: <b>${escapeHtml(t.status)}</b>
                  </div>
                </div>
                ${
                  t.status === 'PENDING'
                    ? `<div style="display:flex; gap:6px;">
                        <button class="cyber-btn emerald" data-approve-topup="${escapeHtml(t.id)}" style="padding:4px 10px; font-size:11px;">✔ Duyệt Cộng Tiền</button>
                        <button class="cyber-btn danger" data-reject-topup="${escapeHtml(t.id)}" style="padding:4px 10px; font-size:11px;">✖ Từ Chối</button>
                      </div>`
                    : `<span style="font-size:11px; color:${t.status === 'APPROVED' ? '#10b981' : '#f87171'}; font-weight:700;">${escapeHtml(t.status)}</span>`
                }
              </div>`
            )
            .join('');

          topupBox.querySelectorAll('[data-approve-topup]').forEach((btn) => {
            btn.addEventListener('click', () => decideTopupOrder(btn.getAttribute('data-approve-topup'), true));
          });
          topupBox.querySelectorAll('[data-reject-topup]').forEach((btn) => {
            btn.addEventListener('click', () => decideTopupOrder(btn.getAttribute('data-reject-topup'), false));
          });
        }
      }

      // 3. Đơn thuê 24/7 & Up thuê (Kèm TK/MK Game)
      const srvBox = document.getElementById('adminServiceOrdersBox');
      if (srvBox) {
        const list = data.serviceOrders || [];
        if (list.length === 0) {
          srvBox.textContent = 'Chưa có đơn đăng ký Thuê 24/7 / Up thuê nào.';
        } else {
          srvBox.innerHTML = list
            .slice(0, 20)
            .map(
              (o) => `
              <div style="padding:8px; border-bottom:1px solid rgba(148,163,184,0.15);">
                <div style="display:flex; justify-content:space-between; color:#fbbf24; font-weight:700;">
                  <span>#${escapeHtml(String(o.id).slice(-6))} • User: ${escapeHtml(o.username)} • ${escapeHtml(o.serviceName)} (${escapeHtml(o.planName)} - ${o.days} ngày)</span>
                  <span style="color:#38bdf8;">${escapeHtml(o.status)}</span>
                </div>
                <div style="color:#69f0ae; font-weight:700; margin-top:2px;">
                  🎮 TK Game: ${escapeHtml(o.gameUsername)} | 🔑 MK Game: ${escapeHtml(o.gamePassword)} | 🌍 Server: ${escapeHtml(o.gameServer)}
                </div>
                <div style="color:#cbd5e1; margin-top:2px;">
                  Nhân vật / Ghi chú: ${escapeHtml(o.charName || o.customerNote || 'Không có')} • Key VIP: <code>${escapeHtml(o.vipKeyIssued)}</code>
                </div>
                <div style="display:flex; gap:6px; margin-top:6px;">
                  <button class="cyber-btn emerald" data-srv-status="${escapeHtml(o.id)}" data-st="RUNNING_247" style="padding:3px 8px; font-size:11px;">🟢 Đang Cắm 24/7</button>
                  <button class="cyber-btn primary" data-srv-status="${escapeHtml(o.id)}" data-st="COMPLETED" style="padding:3px 8px; font-size:11px;">✅ Hoàn Thành</button>
                  <button class="cyber-btn danger" data-srv-status="${escapeHtml(o.id)}" data-st="PAUSED" style="padding:3px 8px; font-size:11px;">⏸ Tạm Dừng</button>
                </div>
              </div>`
            )
            .join('');

          srvBox.querySelectorAll('[data-srv-status]').forEach((btn) => {
            btn.addEventListener('click', () =>
              updateServiceOrderStatus(btn.getAttribute('data-srv-status'), btn.getAttribute('data-st'))
            );
          });
        }
      }

      // 4. Phiên Mạng Kép (Dual-Connection Sessions)
      const dualBox = document.getElementById('adminDualSessionsBox');
      if (dualBox) {
        const sessions = data.dualSessions || [];
        if (sessions.length === 0) {
          dualBox.textContent = 'Chưa có phiên Mạng Kép nào được ghi nhận.';
        } else {
          dualBox.innerHTML = sessions
            .map(
              (s) => `
              <div style="padding:7px; border-bottom:1px solid rgba(148,163,184,0.15); display:flex; justify-content:space-between; align-items:center;">
                <div>
                  <b style="color:#f8fafc;">User #${s.userId}: ${escapeHtml(s.username)}</b> (${escapeHtml(s.role)})
                  <div style="color:#94a3b8; font-size:11px;">
                    Thiết bị: ${escapeHtml(s.platform)} • Còn trong Grace Period 30p: <b style="color:${s.withinGracePeriod ? '#10b981' : '#f59e0b'};">${s.withinGracePeriod ? 'CÓ (Giữ sống)' : 'Chờ Reconnect'}</b>
                  </div>
                </div>
                <span style="font-size:11px; color:#38bdf8;">Hoạt động ${s.idleSeconds}s trước</span>
              </div>`
            )
            .join('');
        }
      }

      // 5. Quản lý Ví & Cấp tiền nhanh
      const hbEl = document.getElementById('adminUsersHeartbeatList');
      if (hbEl) {
        const users = data.users || [];
        hbEl.innerHTML = users
          .map(
            (u) => `
            <div style="padding:7px; border-bottom:1px solid rgba(148,163,184,0.15); display:flex; justify-content:space-between; align-items:center;">
              <div>
                <b style="color:#fff;">#${u.id} ${escapeHtml(u.username)}</b> (${escapeHtml(u.role)}) • Số dư: <b style="color:#fbbf24;">${formatVnd(u.wallet ? u.wallet.balanceVnd : 0)}</b>
                <div style="color:#9be7ff; font-size:11px;">
                  Key VIP: <code>${escapeHtml((u.wallet && u.wallet.vipKey) || 'Chưa có')}</code> • Treo 24/7: ${(u.wallet && u.wallet.rent247Active) ? '🟢 Đang bật' : '⚪ Chưa thuê'}
                </div>
              </div>
              <div style="display:flex; gap:6px;">
                <button class="cyber-btn emerald" data-add-bal-user="${u.id}" data-amt="40000" style="padding:4px 8px; font-size:11px;">+40K (1 Tháng)</button>
                <button class="cyber-btn primary" data-add-bal-user="${u.id}" data-amt="15000" style="padding:4px 8px; font-size:11px;">+15K (1 Tuần)</button>
              </div>
            </div>`
          )
          .join('');

        hbEl.querySelectorAll('[data-add-bal-user]').forEach((btn) => {
          btn.addEventListener('click', async () => {
            const uid = btn.getAttribute('data-add-bal-user');
            const amt = Number(btn.getAttribute('data-amt') || 40000);
            await fetch(`/api/admin/commerce/users/${uid}/wallet`, {
              method: 'POST',
              headers: authHeaders(),
              body: JSON.stringify({ deltaVnd: amt, grantDays: amt >= 40000 ? 30 : 7 })
            });
            showToast(`👑 Đã cộng +${formatVnd(amt)} & gia hạn VIP 24/7 cho User #${uid}!`);
            loadCommerceOverview();
          });
        });
      }
    } catch (_) {}
  }

  async function decideTopupOrder(orderId, approve) {
    try {
      const res = await fetch(`/api/admin/commerce/topup/${orderId}/decision`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ action: approve ? 'APPROVE' : 'REJECT' })
      });
      const data = await res.json();
      if (data.success) {
        showToast(data.message || 'Đã xử lý đơn nạp QR!');
        loadCommerceOverview();
      }
    } catch (_) {}
  }

  async function updateServiceOrderStatus(orderId, status) {
    try {
      const res = await fetch(`/api/admin/commerce/service/${orderId}/status`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ status, adminResponse: `Admin Duy Trường cập nhật: ${status}` })
      });
      const data = await res.json();
      if (data.success) {
        showToast(`👑 Đã cập nhật đơn #${orderId} -> ${status}`);
        loadCommerceOverview();
      }
    } catch (_) {}
  }

  // ==================== TẢI TRẠNG THÁI HỆ THỐNG, CCU & DANH SÁCH TÀI KHOẢN ====================
  async function loadSystemOverview() {
    try {
      const res = dualClient
        ? await dualClient.fetchWithAuth('/api/admin/overview')
        : await fetch('/api/admin/overview', { headers: authHeaders() });
      if (!res.ok) return;
      const data = await res.json();
      const sys = data.system || {};

      const statsEl = document.getElementById('systemStats');
      if (statsEl) {
        statsEl.innerHTML = `
          <div class="stat-card">
            <div style="font-size:11px; color:#94a3b8;">Hệ Điều Hành Máy Chủ</div>
            <div style="font-size:15px; font-weight:700; color:#f8fafc; margin-top:4px;">${escapeHtml(sys.platform || 'Linux Cloud')}</div>
          </div>
          <div class="stat-card">
            <div style="font-size:11px; color:#94a3b8;">RAM Node.js (RSS)</div>
            <div style="font-size:16px; font-weight:800; color:#38bdf8; margin-top:4px;">${escapeHtml(sys.nodeRamRssMB || 0)} MB</div>
          </div>
          <div class="stat-card">
            <div style="font-size:11px; color:#94a3b8;">RAM Trống / Tổng RAM</div>
            <div style="font-size:16px; font-weight:800; color:#10b981; margin-top:4px;">${escapeHtml(sys.freeSystemRamMB || 0)} / ${escapeHtml(sys.totalSystemRamMB || 0)} MB</div>
          </div>
          <div class="stat-card">
            <div style="font-size:11px; color:#94a3b8;">Máy Ảo Đang Chạy / CCU</div>
            <div style="font-size:16px; font-weight:800; color:#fbbf24; margin-top:4px;">${escapeHtml(sys.activePlayersCount ?? data.totalRunningVMs ?? 0)} / ${escapeHtml(sys.maxConcurrentSessions ?? 50)}</div>
          </div>
        `;
      }

      const ccuInput = document.getElementById('maxConcurrentInput');
      if (ccuInput && sys.maxConcurrentSessions) {
        ccuInput.value = sys.maxConcurrentSessions;
      }

      const usersBody = document.getElementById('usersTableBody');
      if (usersBody) {
        const users = data.users || [];
        usersBody.innerHTML = users
          .map(
            (u) => `
            <tr>
              <td>#${u.id}</td>
              <td style="font-weight:700; color:#f8fafc;">${escapeHtml(u.username)}</td>
              <td><span class="vip-crown-badge" style="font-size:10px; padding:2px 6px;">${escapeHtml(u.role)}</span></td>
              <td>${u.runningVMs || 0} chạy / ${u.vmCount || 0} (Max ${u.maxVMs || 5})</td>
              <td>${formatBytes(u.storageUsedBytes)} / ${formatBytes(u.quotaBytes)}</td>
              <td style="color:${u.locked ? '#f87171' : '#10b981'}; font-weight:700;">${u.locked ? 'Đã Khóa' : 'Hoạt Động'}</td>
              <td>
                ${
                  u.role !== 'admin'
                    ? `<button class="cyber-btn ${u.locked ? 'emerald' : 'danger'}" data-toggle-lock="${u.id}" data-locked="${u.locked ? '1' : '0'}" style="padding:3px 8px; font-size:11px;">
                        ${u.locked ? 'Mở Khóa' : 'Khóa TK'}
                      </button>`
                    : '<span style="font-size:11px; color:#94a3b8;">Quản trị gốc</span>'
                }
              </td>
            </tr>`
          )
          .join('');

        usersBody.querySelectorAll('[data-toggle-lock]').forEach((btn) => {
          btn.addEventListener('click', async () => {
            const uid = btn.getAttribute('data-toggle-lock');
            const currentlyLocked = btn.getAttribute('data-locked') === '1';
            await fetch(`/api/admin/users/${uid}/lock`, {
              method: 'PUT',
              headers: authHeaders(),
              body: JSON.stringify({ locked: !currentlyLocked })
            });
            showToast(`Đã cập nhật trạng thái khóa cho User #${uid}`);
            loadSystemOverview();
          });
        });
      }
    } catch (_) {}
  }

  function bindAdminEvents() {
    const refreshBtn = document.getElementById('refreshAdminBtn');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', async () => {
        await Promise.all([loadCommerceOverview(), loadSystemOverview()]);
        showToast('🔄 Đã làm mới toàn bộ dữ liệu Quản trị Admin!');
      });
    }

    const saveEvBtn = document.getElementById('btnSaveAdminEvent');
    if (saveEvBtn) {
      saveEvBtn.addEventListener('click', async () => {
        const title = document.getElementById('adminEventTitle').value;
        const bannerText = document.getElementById('adminEventBanner').value;
        const discountPercent = Number(document.getElementById('adminDiscountPct').value || 0);
        const topupMultiplier = Number(document.getElementById('adminTopupMult').value || 1);
        const active = document.getElementById('adminEventActive').checked;

        const res = await fetch('/api/admin/commerce/events', {
          method: 'POST',
          headers: authHeaders(),
          body: JSON.stringify({ active, title, bannerText, discountPercent, topupMultiplier })
        });
        const data = await res.json();
        if (data && data.success) {
          showToast('👑 Đã cập nhật Sự kiện & Khuyến mãi trên toàn hệ thống Web + App!');
          loadCommerceOverview();
        }
      });
    }

    const ccuForm = document.getElementById('concurrencyForm');
    if (ccuForm) {
      ccuForm.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const maxConcurrentSessions = Number(document.getElementById('maxConcurrentInput').value || 50);
        const res = await fetch('/api/admin/settings/concurrency', {
          method: 'PUT',
          headers: authHeaders(),
          body: JSON.stringify({ maxConcurrentSessions })
        });
        const data = await res.json();
        if (data && data.success) {
          showToast(data.message || 'Đã cập nhật giới hạn CCU!');
          loadSystemOverview();
        }
      });
    }

    const logoutBtn = document.getElementById('btnAdminLogout');
    if (logoutBtn) {
      logoutBtn.addEventListener('click', async () => {
        await fetch('/api/auth/logout', {
          method: 'POST',
          headers: authHeaders(),
          body: JSON.stringify({ refreshToken: dualClient ? dualClient.getRefreshToken() : '' })
        }).catch(() => {});
        if (dualClient) dualClient.clearSessionOnExplicitLogout();
        window.location.replace('/login.html');
      });
    }
  }

  bindAdminEvents();
  verifyAdminAccessAndInit();
  setInterval(() => {
    if (!dualClient || dualClient.isOnline) {
      loadCommerceOverview();
    }
  }, 15000);
})();
