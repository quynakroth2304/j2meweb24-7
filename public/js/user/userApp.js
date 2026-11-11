// public/js/user/userApp.js — Khu vực Người Dùng (USER PORTAL) độc lập + Mạng Kép (Dual-Connection) + Giữ nguyên 100% cảm giác vuốt/chạm Game 24/7
(function () {
  'use strict';

  const dualClient = window.DualConnectionClient
    ? new window.DualConnectionClient({
        onStatusChange: (st) => updateDualNetworkUi(st),
        onQueueFlushed: (data) => {
          showToast(`✅ Mạng Kép: Đã đồng bộ tự động ${data.processedCount || 0} tác vụ Offline lên Server!`);
          loadCatalogAndSync();
        },
        onSessionUpdated: (u, w) => {
          if (u) currentUser = u;
          if (w) currentWallet = w;
          updateAccountUi();
        }
      })
    : null;

  const slots = [
    {
      slotId: 1,
      name: 'HiệpSĩ_Alpha',
      clazz: 'Chiến Binh',
      lv: 39,
      hp: 4820,
      maxHp: 5200,
      mp: 1450,
      maxMp: 1800,
      expPct: 68,
      gold: 1458900,
      gems: 320,
      mapId: 17,
      mapName: 'Hang Dơi',
      spotX: 576,
      spotY: 216,
      charX: 120,
      charY: 168,
      targetX: 148,
      targetY: 156,
      attackTick: 0,
      killCount: 21,
      killTarget: 80,
      mobName: 'Dơi hang động',
      autoQuest: true,
      autoFilter: 'all',
      renderEnabled: true,
      stateText: 'Đứng cắm chốt (576,216) -> Đánh Dơi hang động 21/80',
      ws: null,
      remoteFrameImg: null,
      remoteFrameUpdatedAt: 0,
      mobs: [
        { id: 1, x: 154, y: 152, hp: 82, maxHp: 100, hitFlash: 0 },
        { id: 2, x: 86, y: 148, hp: 100, maxHp: 100, hitFlash: 0 },
        { id: 3, x: 132, y: 196, hp: 64, maxHp: 100, hitFlash: 0 }
      ],
      dmgPopups: []
    },
    {
      slotId: 2,
      name: 'PhápSư_Omega',
      clazz: 'Pháp Sư',
      lv: 65,
      hp: 6100,
      maxHp: 6400,
      mp: 5900,
      maxMp: 6200,
      expPct: 84,
      gold: 4920000,
      gems: 850,
      mapId: 24,
      mapName: 'Nghĩa địa cát',
      spotX: 420,
      spotY: 360,
      charX: 118,
      charY: 166,
      targetX: 90,
      targetY: 154,
      attackTick: 0,
      killCount: 48,
      killTarget: 120,
      mobName: 'Bộ xương khô',
      autoQuest: true,
      autoFilter: 'main',
      renderEnabled: false,
      stateText: 'Đứng cắm chốt (420,360) -> Đánh Bộ xương 48/120',
      ws: null,
      remoteFrameImg: null,
      remoteFrameUpdatedAt: 0,
      mobs: [
        { id: 1, x: 90, y: 154, hp: 75, maxHp: 100, hitFlash: 0 },
        { id: 2, x: 158, y: 162, hp: 100, maxHp: 100, hitFlash: 0 },
        { id: 3, x: 114, y: 198, hp: 45, maxHp: 100, hitFlash: 0 }
      ],
      dmgPopups: []
    }
  ];

  let activeSlotId = 1;
  let maxVipSlots = 6;
  let authToken = (dualClient && dualClient.getAccessToken()) || localStorage.getItem('duytruong_auth_token') || '';
  let currentUser = (dualClient && dualClient.getUser()) || null;
  let currentWallet = (dualClient && dualClient.getWallet()) || null;
  let currentCatalog = null;
  let globalAnimTick = 0;
  let simulatedOfflineMode = false;

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

  function authHeaders() {
    if (dualClient) {
      return dualClient.authHeaders();
    }
    const headers = { 'Content-Type': 'application/json' };
    if (authToken) {
      headers.Authorization = `Bearer ${authToken}`;
    }
    return headers;
  }

  function isCurrentlyOnline() {
    if (simulatedOfflineMode) return false;
    if (dualClient) return dualClient.isOnline;
    return navigator.onLine !== false;
  }

  function updateDualNetworkUi(st) {
    const lineEl = document.getElementById('dualNetworkStatusLine');
    const simBtn = document.getElementById('btnSimulateOfflineToggle');
    const online = simulatedOfflineMode ? false : (st ? st.isOnline : isCurrentlyOnline());
    const queueLen = st
      ? st.queueLength
      : (dualClient ? dualClient.getOfflineQueueLength() : 0);

    if (lineEl) {
      if (online) {
        lineEl.style.color = '#69f0ae';
        lineEl.textContent = `🟢 Mạng Kép (Dual-Connection): Đang kết nối trực tuyến • Phiên lưu cục bộ bền vững • Hàng đợi Offline: ${queueLen} tác vụ`;
      } else {
        lineEl.style.color = '#fbbf24';
        lineEl.textContent = `📡 Đang Offline (${queueLen} tác vụ chờ đồng bộ) — KHÔNG đăng xuất, Game & Auto Quest vẫn chạy bình thường!`;
      }
    }
    if (simBtn) {
      simBtn.textContent = simulatedOfflineMode
        ? '🔌 Bật Mạng Lại (Đồng Bộ Queue)'
        : '📶 Test Mất Mạng (Offline)';
      simBtn.className = simulatedOfflineMode ? 'cyber-btn emerald' : 'cyber-btn';
    }
  }

  const keepAlive = new window.KeepAliveEngine({
    getSlotsSummary: () => slots,
    onTelemetry: (t) => {
      const pingEl = document.getElementById('hudPingValue');
      const bwEl = document.getElementById('hudBandwidthValue');
      const resumeEl = document.getElementById('hudGraceResumes');
      if (pingEl) pingEl.textContent = isCurrentlyOnline() ? `${t.pingMs} ms` : 'OFFLINE (Local State)';
      if (bwEl) bwEl.textContent = `${t.txKBps} / ${t.rxKBps} KB/s`;
      if (resumeEl) resumeEl.textContent = `${t.graceResumes} lần (0 văng)`;
    },
    onSessionResumed: (info) => {
      showToast(`⚡ Đã khôi phục phiên mạng ${info.sessionId} (Xả ${info.flushedBytes}B từ RingBuffer 64KB)!`);
    },
    onStatusChange: () => {
      const btn = document.getElementById('btnToggleAntiSleep');
      if (btn) {
        btn.textContent = '🛡️ Bất Tử Mạng Ngầm 24/7: LUÔN BẬT (KHÓA BẤT TỬ)';
        btn.className = 'cyber-btn emerald';
      }
    }
  });

  // Khóa Bất Tử Mạng 24/7 luôn bật mặc định
  keepAlive.setAntiSleep247(true);

  // ==================== KHUNG HÌNH GAME 240x320 GIỮ NGUYÊN 100% CẢM GIÁC VUỐT & CHẠM ====================
  function ensureSlotLayer(slot) {
    const host = document.getElementById('vmInstancesHost');
    if (!host) return;
    let layer = document.getElementById(`vm_layer_slot_${slot.slotId}`);
    if (!layer) {
      layer = document.createElement('div');
      layer.id = `vm_layer_slot_${slot.slotId}`;
      layer.className = 'vm-instance-layer' + (slot.slotId === activeSlotId ? ' active' : '');
      const canvas = document.createElement('canvas');
      canvas.width = 240;
      canvas.height = 320;
      canvas.className = 'vm-canvas';
      canvas.id = `vm_canvas_slot_${slot.slotId}`;
      canvas.style.cursor = 'pointer';
      canvas.style.touchAction = 'none';

      let pointerDownPos = null;

      canvas.addEventListener('pointerdown', (ev) => {
        const rect = canvas.getBoundingClientRect();
        const x = Math.round(((ev.clientX - rect.left) / Math.max(1, rect.width)) * 240);
        const y = Math.round(((ev.clientY - rect.top) / Math.max(1, rect.height)) * 320);
        pointerDownPos = { x, y };
        handleCanvasTap(slot, x, y);
      });

      canvas.addEventListener('pointerup', (ev) => {
        if (!pointerDownPos) return;
        const rect = canvas.getBoundingClientRect();
        const upX = Math.round(((ev.clientX - rect.left) / Math.max(1, rect.width)) * 240);
        const upY = Math.round(((ev.clientY - rect.top) / Math.max(1, rect.height)) * 320);
        const dx = upX - pointerDownPos.x;
        const dy = upY - pointerDownPos.y;
        pointerDownPos = null;

        // Giữ nguyên thao tác vuốt (Swipe) điều hướng nhân vật
        if (Math.abs(dx) > 22 || Math.abs(dy) > 22) {
          if (Math.abs(dx) > Math.abs(dy)) {
            sendSlotCommand(slot.slotId, dx > 0 ? 'KEY:-4' : 'KEY:-3');
          } else {
            sendSlotCommand(slot.slotId, dy > 0 ? 'KEY:-2' : 'KEY:-1');
          }
        }
      });

      layer.appendChild(canvas);
      host.appendChild(layer);
      connectSlotLiveStream(slot);
    }
  }

  function handleCanvasTap(slot, x, y) {
    if (y >= 292) {
      if (x < 80) {
        sendSlotCommand(slot.slotId, 'KEY:-6');
        showToast('👑 Mở Menu VIP ADMIN - Duy Trường');
      } else if (x > 160) {
        sendSlotCommand(slot.slotId, 'KEY:-7');
      } else {
        sendSlotCommand(slot.slotId, 'KEY:-5');
      }
      return;
    }
    if (y > 46 && y < 290) {
      slot.targetX = Math.max(24, Math.min(216, x));
      slot.targetY = Math.max(64, Math.min(272, y));
      slot.dmgPopups.push({
        x: slot.targetX,
        y: slot.targetY - 10,
        text: '⚡ Đánh mục tiêu!',
        color: '#38bdf8',
        life: 18
      });
      sendSlotCommand(slot.slotId, `POINTER:${x}:${y}`);
    }
  }

  function connectSlotLiveStream(slot) {
    if (!authToken || !isCurrentlyOnline()) return;
    try {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${proto}//${location.host}/api/vm/${slot.slotId}/ws?token=${encodeURIComponent(authToken)}`;
      const ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';
      slot.ws = ws;

      ws.onmessage = (ev) => {
        if (ev.data instanceof ArrayBuffer) {
          const blob = new Blob([ev.data], { type: 'image/jpeg' });
          const url = URL.createObjectURL(blob);
          const img = new Image();
          img.onload = () => {
            slot.remoteFrameImg = img;
            slot.remoteFrameUpdatedAt = Date.now();
            URL.revokeObjectURL(url);
          };
          img.src = url;
        }
      };

      ws.onclose = () => {
        slot.ws = null;
      };
    } catch (_) {}
  }

  async function pollRemoteLiveFrame(slot) {
    if (!slot || !slot.renderEnabled || !isCurrentlyOnline()) return;
    if (slot.remoteFrameUpdatedAt && Date.now() - slot.remoteFrameUpdatedAt < 1800) {
      return;
    }
    try {
      const res = await fetch(`/api/vm/${slot.slotId}/live-frame`, {
        headers: authHeaders()
      });
      if (!res.ok) return;
      const data = await res.json();
      if (data && data.frameDataUrl && data.source === 'headless_vm') {
        const img = new Image();
        img.onload = () => {
          slot.remoteFrameImg = img;
          slot.remoteFrameUpdatedAt = Date.now();
        };
        img.src = data.frameDataUrl;
      }
    } catch (_) {}
  }

  function renderSlotCanvasFrame(slot) {
    if (!slot) return;
    ensureSlotLayer(slot);
    const canvas = document.getElementById(`vm_canvas_slot_${slot.slotId}`);
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    globalAnimTick++;

    if (slot.remoteFrameImg && Date.now() - slot.remoteFrameUpdatedAt < 8000) {
      ctx.drawImage(slot.remoteFrameImg, 0, 0, 240, 320);
      ctx.fillStyle = 'rgba(2, 6, 23, 0.72)';
      ctx.fillRect(0, 0, 240, 18);
      ctx.fillStyle = '#10b981';
      ctx.font = 'bold 9px monospace';
      ctx.fillText(`● LIVE 24/7: ${slot.name} (Lv.${slot.lv}) - ${slot.mapName}`, 6, 12);
      return;
    }

    if (slot.autoQuest) {
      slot.attackTick++;
      const targetMob = slot.mobs[0];
      if (targetMob) {
        slot.targetX = targetMob.x - 14;
        slot.targetY = targetMob.y + 4;
      }
      slot.charX += (slot.targetX - slot.charX) * 0.18;
      slot.charY += (slot.targetY - slot.charY) * 0.18;

      if (slot.attackTick % 16 === 0 && targetMob) {
        const crit = slot.attackTick % 32 === 0;
        const dmg = crit ? 2840 + (slot.lv * 15) : 1420 + (slot.lv * 9);
        targetMob.hp -= 24;
        targetMob.hitFlash = 4;
        slot.dmgPopups.push({
          x: targetMob.x - 8,
          y: targetMob.y - 18,
          text: crit ? `💥 -${dmg} CHÍ MẠNG!` : `-${dmg}`,
          color: crit ? '#fbbf24' : '#f87171',
          life: 16
        });

        if (targetMob.hp <= 0) {
          targetMob.hp = targetMob.maxHp;
          targetMob.x = 64 + ((slot.attackTick * 29) % 120);
          targetMob.y = 128 + ((slot.attackTick * 17) % 85);
          slot.killCount = (slot.killCount % slot.killTarget) + 1;
          slot.gold += 450;
          slot.expPct = Math.min(99.9, +(slot.expPct + 0.25).toFixed(1));
          slot.stateText = `Đứng cắm chốt (${slot.spotX},${slot.spotY}) -> Đánh ${slot.mobName} ${slot.killCount}/${slot.killTarget}`;
          syncOverlayAndButtons();
        }
      }
    }

    const skyGrad = ctx.createLinearGradient(0, 0, 0, 320);
    if (slot.mapId === 17) {
      skyGrad.addColorStop(0, '#090d16');
      skyGrad.addColorStop(0.45, '#16213e');
      skyGrad.addColorStop(1, '#0f172a');
    } else {
      skyGrad.addColorStop(0, '#1a1423');
      skyGrad.addColorStop(0.5, '#2d1e2f');
      skyGrad.addColorStop(1, '#120d18');
    }
    ctx.fillStyle = skyGrad;
    ctx.fillRect(0, 0, 240, 320);

    ctx.strokeStyle = 'rgba(56, 189, 248, 0.08)';
    ctx.lineWidth = 1;
    for (let gx = 0; gx < 240; gx += 24) {
      ctx.beginPath();
      ctx.moveTo(gx, 48);
      ctx.lineTo(gx, 290);
      ctx.stroke();
    }
    for (let gy = 48; gy < 290; gy += 24) {
      ctx.beginPath();
      ctx.moveTo(0, gy);
      ctx.lineTo(240, gy);
      ctx.stroke();
    }

    ctx.save();
    ctx.strokeStyle = 'rgba(16, 185, 129, 0.45)';
    ctx.setLineDash([4, 3]);
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(124, 168, 56, 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();

    slot.mobs.forEach((m, idx) => {
      const floatY = Math.sin((globalAnimTick + idx * 12) * 0.15) * 3;
      const mx = m.x;
      const my = m.y + floatY;

      ctx.fillStyle = 'rgba(0,0,0,0.45)';
      ctx.beginPath();
      ctx.ellipse(mx, my + 14, 12, 5, 0, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = m.hitFlash > 0 ? '#ffffff' : (slot.mapId === 17 ? '#a855f7' : '#eab308');
      if (m.hitFlash > 0) m.hitFlash--;
      ctx.beginPath();
      ctx.arc(mx, my, 10, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = '#ef4444';
      ctx.fillRect(mx - 4, my - 2, 3, 3);
      ctx.fillRect(mx + 2, my - 2, 3, 3);

      ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';
      ctx.fillRect(mx - 16, my - 20, 32, 4);
      ctx.fillStyle = '#ef4444';
      ctx.fillRect(mx - 16, my - 20, Math.max(2, (m.hp / m.maxHp) * 32), 4);

      ctx.fillStyle = '#e2e8f0';
      ctx.font = '9px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(slot.mobName, mx, my - 23);
    });

    const cx = slot.charX;
    const cy = slot.charY;

    ctx.strokeStyle = '#fbbf24';
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    ctx.ellipse(cx, cy + 14, 15, 6, 0, 0, Math.PI * 2);
    ctx.stroke();

    if (slot.autoQuest && globalAnimTick % 8 < 4) {
      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.arc(cx + 8, cy - 2, 22, -0.7, 1.2);
      ctx.stroke();
    }

    ctx.fillStyle = '#0284c7';
    ctx.fillRect(cx - 7, cy - 6, 14, 18);
    ctx.fillStyle = '#fde047';
    ctx.beginPath();
    ctx.arc(cx, cy - 12, 7, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#fbbf24';
    ctx.font = 'bold 9px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('👑 VIP 24/7', cx, cy - 28);
    ctx.fillStyle = '#38bdf8';
    ctx.font = 'bold 10px sans-serif';
    ctx.fillText(`${slot.name} [Lv.${slot.lv}]`, cx, cy - 19);

    for (let i = slot.dmgPopups.length - 1; i >= 0; i--) {
      const p = slot.dmgPopups[i];
      ctx.fillStyle = p.color;
      ctx.font = 'bold 10px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(p.text, p.x, p.y);
      p.y -= 1.2;
      p.life--;
      if (p.life <= 0) slot.dmgPopups.splice(i, 1);
    }

    ctx.textAlign = 'left';
    ctx.fillStyle = 'rgba(2, 6, 23, 0.88)';
    ctx.fillRect(0, 0, 240, 44);
    ctx.strokeStyle = 'rgba(56, 189, 248, 0.35)';
    ctx.beginPath();
    ctx.moveTo(0, 44);
    ctx.lineTo(240, 44);
    ctx.stroke();

    ctx.fillStyle = '#f8fafc';
    ctx.font = 'bold 10px sans-serif';
    ctx.fillText(`${slot.name} • Lv.${slot.lv} (${slot.expPct}%)`, 6, 13);

    ctx.fillStyle = '#334155';
    ctx.fillRect(6, 17, 108, 7);
    ctx.fillStyle = '#ef4444';
    ctx.fillRect(6, 17, Math.round((slot.hp / slot.maxHp) * 108), 7);
    ctx.fillStyle = '#ffffff';
    ctx.font = '7px monospace';
    ctx.fillText(`HP ${slot.hp}/${slot.maxHp}`, 10, 23);

    ctx.fillStyle = '#334155';
    ctx.fillRect(6, 26, 108, 6);
    ctx.fillStyle = '#3b82f6';
    ctx.fillRect(6, 26, Math.round((slot.mp / slot.maxMp) * 108), 6);
    ctx.fillStyle = '#ffffff';
    ctx.fillText(`MP ${slot.mp}/${slot.maxMp}`, 10, 31);

    ctx.fillStyle = '#fbbf24';
    ctx.font = 'bold 9px sans-serif';
    ctx.fillText(`Map ${slot.mapId}: ${slot.mapName}`, 122, 13);
    ctx.fillStyle = isCurrentlyOnline() ? '#10b981' : '#fbbf24';
    ctx.font = '8px sans-serif';
    ctx.fillText(isCurrentlyOnline() ? `🛡️ Bất Tử Mạng: LUÔN BẬT` : `📡 Mạng Kép: OFFLINE`, 122, 24);
    ctx.fillStyle = '#cbd5e1';
    ctx.fillText(`Vàng: ${(slot.gold / 1000).toFixed(0)}k • Ngọc: ${slot.gems}`, 122, 35);

    ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';
    ctx.fillRect(4, 268, 232, 20);
    ctx.fillStyle = '#69f0ae';
    ctx.font = '9px sans-serif';
    ctx.fillText(`⚡ ${slot.stateText.slice(0, 42)}`, 8, 281);

    ctx.fillStyle = '#0f172a';
    ctx.fillRect(0, 292, 240, 28);
    ctx.strokeStyle = 'rgba(148, 163, 184, 0.3)';
    ctx.beginPath();
    ctx.moveTo(0, 292);
    ctx.lineTo(240, 292);
    ctx.stroke();

    ctx.fillStyle = '#38bdf8';
    ctx.font = 'bold 10px sans-serif';
    ctx.fillText('Menu VIP', 12, 310);
    ctx.fillStyle = '#10b981';
    ctx.textAlign = 'center';
    ctx.fillText(slot.autoQuest ? '● Đang Đánh' : '● Đứng Yên', 120, 310);
    ctx.fillStyle = '#94a3b8';
    ctx.textAlign = 'right';
    ctx.fillText('Đóng', 228, 310);
    ctx.textAlign = 'left';
  }

  function renderDock() {
    const dock = document.getElementById('multiAccountDock');
    if (!dock) return;
    dock.innerHTML = '';

    slots.forEach((slot) => {
      ensureSlotLayer(slot);
      const pill = document.createElement('div');
      pill.className = 'account-pill' + (slot.slotId === activeSlotId ? ' active' : '');
      pill.innerHTML = `
        <span class="status-dot ${slot.autoQuest ? 'online' : 'paused'}"></span>
        <div>
          <div style="font-weight:700;">#${slot.slotId} ${slot.name} (Lv.${slot.lv})</div>
          <div style="font-size:11px; color:#94a3b8;">Map ${slot.mapId}: ${slot.mapName} • 🛡️ 24/7 ON</div>
        </div>
      `;
      pill.addEventListener('click', () => switchToSlotInstant(slot.slotId));
      dock.appendChild(pill);
    });

    const addBtn = document.createElement('button');
    addBtn.className = 'cyber-btn primary';
    addBtn.textContent = `+ Thêm Slot (${slots.length}/${maxVipSlots})`;
    addBtn.addEventListener('click', addNewAccountSlot);
    dock.appendChild(addBtn);
  }

  function switchToSlotInstant(targetSlotId) {
    if (targetSlotId === activeSlotId) return;
    activeSlotId = targetSlotId;

    slots.forEach((slot) => {
      const layer = document.getElementById(`vm_layer_slot_${slot.slotId}`);
      if (slot.slotId === activeSlotId) {
        slot.renderEnabled = true;
        if (layer) layer.classList.add('active');
        sendSlotCommand(slot.slotId, 'RENDER:1');
        sendSlotCommand(slot.slotId, 'SYNC');
        renderSlotCanvasFrame(slot);
        pollRemoteLiveFrame(slot);
      } else {
        slot.renderEnabled = false;
        if (layer) layer.classList.remove('active');
        sendSlotCommand(slot.slotId, 'RENDER:0');
      }
    });

    const activeSlot = getActiveSlot();
    const labelEl = document.getElementById('activeSlotLabel');
    if (labelEl && activeSlot) {
      labelEl.textContent = `Slot #${activeSlot.slotId} (${activeSlot.name}) • Bất Tử Mạng 24/7: LUÔN BẬT`;
    }
    syncOverlayAndButtons();
    renderDock();
  }

  function addNewAccountSlot() {
    if (slots.length >= maxVipSlots) {
      showToast(`⚠️ Đã đạt giới hạn ${maxVipSlots} Slot Máy Ảo đồng thời!`);
      return;
    }
    const newId = slots.length + 1;
    const newSlot = {
      slotId: newId,
      name: `HiệpSĩ_Slot${newId}`,
      clazz: 'Thích Khách',
      lv: 25,
      hp: 2400,
      maxHp: 2400,
      mp: 1200,
      maxMp: 1200,
      expPct: 45,
      gold: 350000,
      gems: 120,
      mapId: 1,
      mapName: 'Làng Sói Trắng',
      spotX: 380,
      spotY: 260,
      charX: 120,
      charY: 168,
      targetX: 145,
      targetY: 155,
      attackTick: 0,
      killCount: 5,
      killTarget: 50,
      mobName: 'Sói Xám',
      autoQuest: true,
      autoFilter: 'all',
      renderEnabled: true,
      stateText: 'Đứng cắm chốt (380,260) -> Đánh Sói Xám 5/50',
      ws: null,
      remoteFrameImg: null,
      remoteFrameUpdatedAt: 0,
      mobs: [
        { id: 1, x: 150, y: 155, hp: 90, maxHp: 100, hitFlash: 0 },
        { id: 2, x: 92, y: 162, hp: 100, maxHp: 100, hitFlash: 0 }
      ],
      dmgPopups: []
    };
    slots.push(newSlot);
    keepAlive.jsSocketOpen('27.0.14.78', 19129, newId);
    renderDock();
    switchToSlotInstant(newId);
    showToast(`🚀 Đã khởi tạo Máy Ảo Slot #${newId} & bật Bất Tử Mạng 24/7!`);
  }

  function getActiveSlot() {
    return slots.find((s) => s.slotId === activeSlotId) || slots[0];
  }

  function sendSlotCommand(slotId, commandStr) {
    const slot = slots.find((s) => s.slotId === slotId);
    if (!slot) return;

    if (commandStr === 'KEY:-1') slot.targetY = Math.max(60, slot.charY - 22);
    if (commandStr === 'KEY:-2') slot.targetY = Math.min(272, slot.charY + 22);
    if (commandStr === 'KEY:-3') slot.targetX = Math.max(24, slot.charX - 22);
    if (commandStr === 'KEY:-4') slot.targetX = Math.min(216, slot.charX + 22);
    if (commandStr === 'KEY:-5' && slot.mobs[0]) {
      slot.mobs[0].hitFlash = 4;
      slot.dmgPopups.push({
        x: slot.mobs[0].x - 10,
        y: slot.mobs[0].y - 16,
        text: '-1650 KỸ NĂNG!',
        color: '#38bdf8',
        life: 16
      });
    }

    if (!isCurrentlyOnline()) {
      return;
    }

    if (slot.ws && slot.ws.readyState === WebSocket.OPEN) {
      slot.ws.send(JSON.stringify({ type: 'cmd', data: commandStr }));
    }
    fetch(`/api/vm/${slotId}/input`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ command: commandStr })
    }).catch(() => {});
  }

  function syncOverlayAndButtons() {
    const slot = getActiveSlot();
    if (!slot) return;
    const overlay = document.getElementById('screenOffOverlay');
    if (overlay) {
      overlay.style.display = slot.renderEnabled ? 'none' : 'flex';
    }
    const qBtn = document.getElementById('btnQuickAutoQuest');
    if (qBtn) {
      qBtn.textContent = slot.autoQuest ? '⏹ DỪNG AUTO NV' : '▶ BẬT AUTO NHIỆM VỤ';
      qBtn.className = 'cyber-btn ' + (slot.autoQuest ? 'danger' : 'emerald');
    }
    const titleEl = document.getElementById('aqStatusTitle');
    const coordsEl = document.getElementById('aqStatusCoords');
    const detailEl = document.getElementById('aqStatusDetail');
    if (titleEl) titleEl.textContent = `TRẠNG THÁI: ${slot.autoQuest ? 'ĐANG CẮM BÃI 24/7' : 'TẠM DỪNG'}`;
    if (coordsEl) coordsEl.textContent = `Map ${slot.mapId}: ${slot.mapName} (${slot.spotX},${slot.spotY}) • Lv.${slot.lv}`;
    if (detailEl) detailEl.textContent = slot.stateText;

    const btnAntiSleep = document.getElementById('btnToggleAntiSleep');
    if (btnAntiSleep) {
      btnAntiSleep.textContent = '🛡️ Bất Tử Mạng Ngầm 24/7: LUÔN BẬT (KHÓA BẤT TỬ)';
      btnAntiSleep.className = 'cyber-btn emerald';
    }
  }

  // ==================== ĐỒNG BỘ TÀI KHOẢN, PHÂN QUYỀN RBAC & SHOP QR TECHCOMBANK ====================
  function updateAccountUi() {
    const syncSummary = document.getElementById('syncUserSummary');
    const btnOpenAuth = document.getElementById('btnOpenAuthModal');
    const shopWalletBalance = document.getElementById('shopWalletBalance');
    const shopVipKey = document.getElementById('shopVipKey');
    const btnGoToAdminPortal = document.getElementById('btnGoToAdminPortal');

    if (currentUser) {
      const roleLabel = currentUser.role === 'admin' ? '👑 ADMIN' : '👤 USER';
      const bal = currentWallet ? formatVnd(currentWallet.balanceVnd) : '0đ';
      if (syncSummary) {
        syncSummary.innerHTML = `${roleLabel} <b>${currentUser.username}</b> • Số dư: <b style="color:#fbbf24;">${bal}</b>`;
      }
      if (btnOpenAuth) {
        btnOpenAuth.textContent = `👤 ${currentUser.username} (Đổi TK)`;
      }
      if (shopWalletBalance && currentWallet) {
        shopWalletBalance.textContent = formatVnd(currentWallet.balanceVnd);
      }
      if (shopVipKey && currentWallet) {
        const exp = currentWallet.vipExpiresAt && Date.parse(currentWallet.vipExpiresAt) > Date.now()
          ? `Hạn: ${new Date(currentWallet.vipExpiresAt).toLocaleDateString('vi-VN')}`
          : 'Đang kích hoạt 24/7';
        shopVipKey.textContent = `Key: ${currentWallet.vipKey} (${exp})`;
      }
      // PHÂN QUYỀN FRONTEND (RBAC): Chỉ hiển thị nút vào Khu Vực Admin nếu role === 'admin'
      if (btnGoToAdminPortal) {
        btnGoToAdminPortal.style.display = currentUser.role === 'admin' ? 'inline-flex' : 'none';
      }
    } else {
      if (syncSummary) {
        syncSummary.textContent = '👤 Chưa đăng nhập (Đang chạy Local 24/7)';
      }
      if (btnOpenAuth) {
        btnOpenAuth.textContent = '🔐 Đăng Nhập / Đồng Bộ';
      }
      if (btnGoToAdminPortal) {
        btnGoToAdminPortal.style.display = 'none';
      }
    }
    updateDualNetworkUi();
  }

  function renderPlanSelect() {
    const sel = document.getElementById('selectRentPlan');
    if (!sel || !currentCatalog || !Array.isArray(currentCatalog.plans)) return;
    sel.innerHTML = '';
    currentCatalog.plans.forEach((p) => {
      const opt = document.createElement('option');
      opt.value = p.id;
      const discountLabel = p.discountPercent > 0 ? ` (-${p.discountPercent}% từ ${formatVnd(p.originalPriceVnd)})` : '';
      opt.textContent = `${p.name} (${p.durationLabel || p.days + ' Ngày'}) — ${formatVnd(p.finalPriceVnd)}${discountLabel}`;
      sel.appendChild(opt);
    });
  }

  function renderUserOrders(serviceOrders) {
    const listEl = document.getElementById('userOrdersList');
    if (!listEl) return;
    if (!Array.isArray(serviceOrders) || serviceOrders.length === 0) {
      listEl.textContent = 'Chưa có đơn thuê 24/7 hoặc Up thuê nào.';
      return;
    }
    listEl.innerHTML = serviceOrders
      .slice(0, 8)
      .map(
        (o) => `
        <div style="padding:5px 6px;border-bottom:1px solid rgba(255,255,255,0.08);">
          <div style="display:flex;justify-content:space-between;color:#ffd54f;font-weight:700;">
            <span>#${String(o.id).slice(-6)} • ${o.serviceName}</span>
            <span>${o.status === 'RUNNING_247' ? '🟢 ĐANG CHẠY 24/7' : o.status}</span>
          </div>
          <div style="color:#9be7ff;">Gói: ${o.planName} (${o.days} ngày) • Giá: ${formatVnd(o.pricePaidVnd)} • Key: ${o.vipKeyIssued}</div>
          <div style="color:#b0bec5;">Acc Game: <b>${o.gameUsername}</b> (${o.gameServer})</div>
        </div>`
      )
      .join('');
  }

  async function loadCatalogAndSync() {
    if (!isCurrentlyOnline()) {
      updateAccountUi();
      return;
    }
    try {
      const res = dualClient
        ? await dualClient.fetchWithAuth('/api/commerce/catalog')
        : await fetch('/api/commerce/catalog', { headers: authHeaders() });
      const data = await res.json();
      if (!data) return;
      currentCatalog = data;
      if (data.user) {
        currentUser = data.user;
      }
      if (data.wallet) {
        currentWallet = data.wallet;
      }

      const ev = data.eventConfig;
      const bannerTitle = document.getElementById('eventBannerTitle');
      const bannerText = document.getElementById('eventBannerText');
      if (ev) {
        if (bannerTitle) bannerTitle.textContent = `🎁 ${ev.title}`;
        if (bannerText) {
          bannerText.textContent = `${ev.bannerText} • Bảng giá: 1 Ngày 3k • 1 Tuần 15k • 1 Tháng 40k (Tối đa 3 tháng/lần)`;
        }
      }

      renderPlanSelect();
      updateAccountUi();
      if (data.serviceOrders) {
        renderUserOrders(data.serviceOrders);
      }
    } catch (_) {
      // Mất mạng tạm thời: giữ nguyên phiên cục bộ, tuyệt đối không đăng xuất!
      updateAccountUi();
    }
  }

  async function sendClientHeartbeat() {
    const slot = getActiveSlot();
    if (!slot) return;

    if (!isCurrentlyOnline()) {
      if (dualClient) {
        dualClient.enqueueOfflineAction('AUTO_QUEST_PROGRESS', {
          slotId: slot.slotId,
          charName: slot.name,
          level: slot.lv,
          mapId: slot.mapId,
          mapName: slot.mapName,
          questStep: slot.stateText,
          platform: 'WEB_USER_PORTAL'
        });
        updateDualNetworkUi();
      }
      return;
    }

    let frameDataUrl = '';
    const canvas = document.getElementById(`vm_canvas_slot_${slot.slotId}`);
    if (canvas) {
      try {
        frameDataUrl = canvas.toDataURL('image/jpeg', 0.65);
      } catch (_) {}
    }

    fetch('/api/vm/frame-sync', {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        slotId: slot.slotId,
        charName: slot.name,
        level: slot.lv,
        mapId: slot.mapId,
        mapName: slot.mapName,
        hp: slot.hp,
        maxHp: slot.maxHp,
        mp: slot.mp,
        maxMp: slot.maxMp,
        stateText: slot.stateText,
        frameDataUrl
      })
    }).catch(() => {});

    if (!authToken && !(dualClient && dualClient.getAccessToken())) return;
    try {
      const reqFn = dualClient
        ? (u, opts) => dualClient.fetchWithAuth(u, opts)
        : (u, opts) => fetch(u, opts);
      await reqFn('/api/commerce/heartbeat', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          platform: 'WEB_USER_PORTAL',
          activeSlots: slots.length,
          activeSlot: slot.slotId,
          autoQuestActive: Boolean(slot.autoQuest),
          mapSummary: `Map ${slot.mapId}: ${slot.mapName} (Lv.${slot.lv})`,
          charName: slot.name,
          level: slot.lv,
          frameDataUrl
        })
      });
    } catch (_) {}
  }

  function bindUiEvents() {
    // Kiểm tra nếu user thường vừa bị chặn từ /admin chuyển về
    if (location.search.includes('error=forbidden_admin')) {
      setTimeout(() => {
        showToast('🚫 403 Forbidden: Tài khoản User không được phép truy cập Khu Vực Quản Trị Admin!');
      }, 300);
    }

    // Nút Test Giả Lập Mất Mạng / Có Mạng Lại (Kiểm thử Mạng Kép)
    const simBtn = document.getElementById('btnSimulateOfflineToggle');
    if (simBtn) {
      simBtn.addEventListener('click', async () => {
        simulatedOfflineMode = !simulatedOfflineMode;
        if (simulatedOfflineMode) {
          if (dualClient) dualClient.handleNetworkChange(false);
          updateDualNetworkUi();
          showToast('📡 Đã bật chế độ Giả Lập Mất Mạng (Offline): Không bị logout, Game vẫn chạy, mọi thao tác lưu sẽ vào Hàng đợi Offline!');
        } else {
          if (dualClient) {
            dualClient.handleNetworkChange(true);
            await dualClient.flushOfflineQueue();
          }
          updateDualNetworkUi();
          showToast('🟢 Đã kết nối mạng lại: Đang tự động xác thực Token & đồng bộ Hàng đợi Offline lên Server!');
        }
      });
    }

    document.querySelectorAll('[data-key]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const keyCode = Number(btn.getAttribute('data-key'));
        if (navigator.vibrate) navigator.vibrate(12);
        sendSlotCommand(activeSlotId, `KEY:${keyCode}`);
      });
    });

    document.querySelectorAll('[data-drawer-tab]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const tab = btn.getAttribute('data-drawer-tab');
        document.querySelectorAll('[data-drawer-tab]').forEach((b) => b.classList.remove('primary'));
        btn.classList.add('primary');
        document.querySelectorAll('.drawer-tab-pane').forEach((p) => {
          p.style.display = p.id === `drawerTab_${tab}` ? 'block' : 'none';
        });
      });
    });

    const authModal = document.getElementById('authSyncModal');
    const btnOpenAuth = document.getElementById('btnOpenAuthModal');
    const btnCloseAuth = document.getElementById('btnCloseAuthModal');
    const btnDoLogin = document.getElementById('btnDoLogin');
    const btnDoRegister = document.getElementById('btnDoRegister');
    const authMsg = document.getElementById('authModalMsg');

    if (btnOpenAuth && authModal) {
      btnOpenAuth.addEventListener('click', () => {
        authModal.style.display = 'flex';
      });
    }
    if (btnCloseAuth && authModal) {
      btnCloseAuth.addEventListener('click', () => {
        authModal.style.display = 'none';
      });
    }

    const handleAuthSubmit = async (mode) => {
      const username = (document.getElementById('authUsernameInput').value || '').trim();
      const password = document.getElementById('authPasswordInput').value || '';
      if (!username || !password) {
        if (authMsg) authMsg.textContent = 'Vui lòng nhập đầy đủ Tài khoản và Mật khẩu!';
        return;
      }
      try {
        const res = await fetch(`/api/auth/${mode}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username, password, platform: 'WEB_USER_PORTAL' })
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
          if (authMsg) authMsg.textContent = data.error || 'Đăng nhập/Đăng ký thất bại';
          return;
        }
        authToken = data.accessToken || data.token;
        currentUser = data.user;
        currentWallet = data.wallet;
        if (dualClient) {
          dualClient.saveSession({
            accessToken: data.accessToken || data.token,
            refreshToken: data.refreshToken,
            user: data.user,
            wallet: data.wallet
          });
        } else {
          localStorage.setItem('duytruong_auth_token', authToken);
        }
        if (authModal) authModal.style.display = 'none';
        updateAccountUi();
        showToast(`🎉 Xin chào ${currentUser.username} (${currentUser.role.toUpperCase()})! Đã kích hoạt Phiên Mạng Kép chống văng.`);
        loadCatalogAndSync();
        sendClientHeartbeat();
      } catch (err) {
        if (authMsg) authMsg.textContent = 'Lỗi kết nối Server: ' + err.message;
      }
    };

    if (btnDoLogin) btnDoLogin.addEventListener('click', () => handleAuthSubmit('login'));
    if (btnDoRegister) btnDoRegister.addEventListener('click', () => handleAuthSubmit('register'));

    // Tạo mã QR nạp tiền Techcombank (hỗ trợ cả khi Offline bằng Offline Queue)
    const btnCreateTopupQr = document.getElementById('btnCreateTopupQr');
    if (btnCreateTopupQr) {
      btnCreateTopupQr.addEventListener('click', async () => {
        if (!currentUser && !authToken) {
          if (authModal) authModal.style.display = 'flex';
          showToast('🔐 Vui lòng Đăng nhập tài khoản trước khi tạo mã QR nạp tiền!');
          return;
        }
        const amountVnd = Number(document.getElementById('selectTopupAmount').value || 40000);

        if (!isCurrentlyOnline() && dualClient) {
          const item = dualClient.enqueueOfflineAction('CREATE_TOPUP_QR', {
            amountVnd,
            platform: 'WEB_OFFLINE_QUEUE'
          });
          updateDualNetworkUi();
          showToast(`📡 Đang Offline: Đã lưu lệnh tạo QR nạp ${formatVnd(amountVnd)} vào Hàng đợi Offline (#${item.actionId.slice(-6)})!`);
          return;
        }

        try {
          const res = await fetch('/api/commerce/topup', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify({ amountVnd })
          });
          const data = await res.json();
          if (!data.success) {
            showToast('⚠️ ' + (data.error || 'Không thể tạo mã QR'));
            return;
          }
          const qrBox = document.getElementById('qrPaymentBox');
          const qrImg = document.getElementById('qrPaymentImg');
          const qrContent = document.getElementById('qrTransferContent');
          const qrCredit = document.getElementById('qrCreditedPreview');
          if (qrBox) qrBox.style.display = 'block';
          if (qrImg) qrImg.src = data.order.qrImageUrl;
          if (qrContent) qrContent.textContent = data.order.transferContent;
          if (qrCredit) {
            qrCredit.textContent = `Nạp ${formatVnd(data.order.amountVnd)} -> Thực nhận: ${formatVnd(data.order.creditedAmountVnd || data.order.creditedVnd)} (X${data.order.multiplier || data.order.multiplierApplied || 1})`;
          }
          showToast('🏦 Đã tạo mã QR Techcombank (9823042007 - NGUYỄN ĐỨC DUY TRƯỜNG)!');
        } catch (err) {
          if (dualClient) {
            dualClient.enqueueOfflineAction('CREATE_TOPUP_QR', { amountVnd, platform: 'WEB_OFFLINE_QUEUE' });
            updateDualNetworkUi();
            showToast('📡 Mất mạng đột ngột: Đã đưa yêu cầu tạo QR vào Hàng đợi Offline!');
          }
        }
      });
    }

    // Đăng ký Thuê 24/7 & Up Thuê
    const btnSubmitRentOrder = document.getElementById('btnSubmitRentOrder');
    if (btnSubmitRentOrder) {
      btnSubmitRentOrder.addEventListener('click', async () => {
        if (!currentUser && !authToken) {
          if (authModal) authModal.style.display = 'flex';
          showToast('🔐 Vui lòng Đăng nhập trước khi đăng ký Thuê 24/7 / Up Thuê!');
          return;
        }
        const serviceEl = document.getElementById('selectServiceType');
        const serviceType = serviceEl ? serviceEl.value : 'TREO_247';
        const planId = document.getElementById('selectRentPlan').value;
        const gameUsername = (document.getElementById('rentGameUser').value || '').trim();
        const gamePassword = (document.getElementById('rentGamePass').value || '').trim();
        const gameServer = document.getElementById('rentGameServer').value || 'Server 1';
        const charNoteEl = document.getElementById('rentCharNote');
        const charName = charNoteEl ? (charNoteEl.value || '').trim() : '';

        if (!gameUsername || !gamePassword) {
          showToast('⚠️ Vui lòng nhập Tài khoản và Mật khẩu Game để hệ thống / Admin Duy Trường cắm 24/7!');
          return;
        }

        const payload = {
          serviceType,
          planId,
          gameUsername,
          gamePassword,
          gameServer,
          charName,
          note: charName
        };

        if (!isCurrentlyOnline() && dualClient) {
          dualClient.enqueueOfflineAction('REGISTER_RENT_247', payload);
          updateDualNetworkUi();
          showToast(`📡 Đang Offline: Đơn thuê 24/7 cho nick ${gameUsername} đã lưu vào Hàng đợi Offline và sẽ tự gửi khi có mạng!`);
          return;
        }

        try {
          const res = await fetch('/api/commerce/rent', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify(payload)
          });
          const data = await res.json();
          if (!res.ok || !data.success) {
            showToast('⚠️ ' + (data.error || 'Đăng ký gói thuê thất bại'));
            return;
          }
          currentWallet = data.wallet;
          updateAccountUi();
          loadCatalogAndSync();
          showToast(`🚀 Đăng ký thành công ${data.order.serviceName} (${data.order.planName})! Key: ${data.order.vipKeyIssued}`);
        } catch (err) {
          if (dualClient) {
            dualClient.enqueueOfflineAction('REGISTER_RENT_247', payload);
            updateDualNetworkUi();
            showToast('📡 Mất mạng: Đơn thuê 24/7 đã lưu vào Hàng đợi Offline!');
          }
        }
      });
    }

    const btnScreenSaver = document.getElementById('btnToggleScreenSaver');
    if (btnScreenSaver) {
      btnScreenSaver.addEventListener('click', () => {
        const slot = getActiveSlot();
        slot.renderEnabled = !slot.renderEnabled;
        sendSlotCommand(slot.slotId, slot.renderEnabled ? 'RENDER:1' : 'RENDER:0');
        if (slot.renderEnabled) sendSlotCommand(slot.slotId, 'SYNC');
        syncOverlayAndButtons();
      });
    }

    const btnWake = document.getElementById('btnWakeScreenNow');
    if (btnWake) {
      btnWake.addEventListener('click', () => {
        const slot = getActiveSlot();
        slot.renderEnabled = true;
        sendSlotCommand(slot.slotId, 'RENDER:1');
        sendSlotCommand(slot.slotId, 'SYNC');
        syncOverlayAndButtons();
      });
    }

    const btnAntiSleep = document.getElementById('btnToggleAntiSleep');
    if (btnAntiSleep) {
      btnAntiSleep.addEventListener('click', async () => {
        await keepAlive.setAntiSleep247(true);
        showToast('🛡️ Bất Tử Mạng Ngầm 24/7 luôn được KHÓA BẬT mặc định (Web Worker + Web Locks + Silent Audio 0dB + WakeLock)!');
      });
    }

    const btnPip = document.getElementById('btnTogglePipHud');
    if (btnPip) {
      btnPip.addEventListener('click', async () => {
        try {
          await keepAlive.togglePipNetworkMonitor();
        } catch (err) {
          showToast('Trình duyệt hiện tại cần tương tác video để mở PiP: ' + err.message);
        }
      });
    }

    const toggleAq = (enable) => {
      const slot = getActiveSlot();
      const filter = document.getElementById('selectAqFilter').value || 'all';
      const potion = document.getElementById('chkAutoPotion').checked ? '1' : '0';
      const buyPotion = document.getElementById('chkAutoBuyPotion').checked ? '1' : '0';
      slot.autoQuest = enable;
      slot.autoFilter = filter;
      slot.stateText = enable
        ? `Đứng cắm chốt (${slot.spotX},${slot.spotY}) -> Đánh ${slot.mobName} ${slot.killCount}/${slot.killTarget}`
        : 'Đã tạm dừng Auto Nhiệm Vụ';
      sendSlotCommand(slot.slotId, `AUTO_QUEST:${enable ? '1' : '0'}:${filter}:${potion}:1:${buyPotion}`);
      syncOverlayAndButtons();
      renderDock();

      if (!isCurrentlyOnline() && dualClient) {
        dualClient.enqueueOfflineAction('AUTO_QUEST_PROGRESS', {
          slotId: slot.slotId,
          charName: slot.name,
          level: slot.lv,
          mapId: slot.mapId,
          mapName: slot.mapName,
          questStep: slot.stateText,
          platform: 'WEB_USER_OFFLINE'
        });
        updateDualNetworkUi();
      }
    };

    document.getElementById('btnQuickAutoQuest').addEventListener('click', () => {
      const slot = getActiveSlot();
      toggleAq(!slot.autoQuest);
    });
    document.getElementById('btnStartAutoQuest').addEventListener('click', () => toggleAq(true));
    document.getElementById('btnStopAutoQuest').addEventListener('click', () => toggleAq(false));
    const btnRunQuestScript = document.getElementById('btnRunQuestScript');
    if (btnRunQuestScript) {
      btnRunQuestScript.addEventListener('click', () => {
        const sel = document.getElementById('selectAqFilter');
        if (sel) sel.value = 'script';
        sendSlotCommand(activeSlotId, 'AUTO_QUEST:script:start');
        toggleAq(true);
        showToast('📜 Đã kích hoạt Kịch Bản Nhiệm Vụ chuẩn từ Lv.1 -> Lv.135 Max!');
      });
    }
    document.querySelectorAll('[data-script-goto]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const targetStep = btn.getAttribute('data-script-goto') || '#1';
        const sel = document.getElementById('selectAqFilter');
        if (sel) sel.value = 'script';
        sendSlotCommand(activeSlotId, `AUTO_QUEST:goto:${targetStep}`);
        sendSlotCommand(activeSlotId, 'AUTO_QUEST:script:start');
        toggleAq(true);
        showToast(`🗺️ Đã chuyển con trỏ Kịch Bản tới mốc ${targetStep} (${btn.textContent.trim()})!`);
      });
    });
    document.getElementById('btnStepAutoQuest').addEventListener('click', () => {
      sendSlotCommand(activeSlotId, 'AUTO_QUEST:step:all:1:1:1');
    });
    document.getElementById('btnDiagAutoQuest').addEventListener('click', () => {
      sendSlotCommand(activeSlotId, 'AUTO_QUEST:diag:all:1:1:1');
      showToast('🔍 Đã gửi lệnh chẩn đoán AUTO_QUEST:diag!');
    });

    document.getElementById('btnApplyAutoSell').addEventListener('click', () => {
      const w = document.getElementById('chkSellWhite').checked ? 1 : 0;
      const g = document.getElementById('chkSellGreen').checked ? 1 : 0;
      const y = document.getElementById('chkSellYellow').checked ? 1 : 0;
      const p = document.getElementById('chkSellPurple').checked ? 1 : 0;
      const o = document.getElementById('chkSellOrange').checked ? 1 : 0;
      const lk = document.getElementById('chkSellLocked').checked ? 1 : 0;
      const ul = document.getElementById('chkSellUnlocked').checked ? 1 : 0;
      const enh = document.getElementById('chkSellEnhanced').checked ? 1 : 0;
      const thr = document.getElementById('inputSellThreshold').value || 40;
      sendSlotCommand(activeSlotId, `AUTO_SELL:1:${w}:${g}:${y}:${p}:${o}:${lk}:${ul}:${enh}:${thr}`);
      showToast('💰 Đã lưu bộ lọc & bật Tự Động Bán Đồ!');
    });

    document.getElementById('btnSellNow').addEventListener('click', () => {
      sendSlotCommand(activeSlotId, 'AUTO_SELL:now:1:1:1:0:0:1:1:0:40');
      showToast('💰 Đang mở hành trang bán đồ rác ngay...');
    });

    document.getElementById('btnEnableAutoPotential').addEventListener('click', () => {
      const s = document.getElementById('apStr').value || 0;
      const d = document.getElementById('apDex').value || 0;
      const v = document.getElementById('apVit').value || 2;
      const i = document.getElementById('apInt').value || 3;
      sendSlotCommand(activeSlotId, `AUTO_POTENTIAL:1:${s}:${d}:${v}:${i}`);
      showToast(`⚡ Đã bật Tự Cộng Tiềm Năng (${s}/${d}/${v}/${i}) mỗi khi lên cấp!`);
    });

    document.getElementById('btnApplyPotentialNow').addEventListener('click', () => {
      const s = document.getElementById('apStr').value || 0;
      const d = document.getElementById('apDex').value || 0;
      const v = document.getElementById('apVit').value || 2;
      const i = document.getElementById('apInt').value || 3;
      sendSlotCommand(activeSlotId, `AUTO_POTENTIAL:now:${s}:${d}:${v}:${i}`);
      showToast('⚡ Đã gửi gói cộng điểm tiềm năng trực tiếp!');
    });

    document.getElementById('btnUnlockVipJar').addEventListener('click', async () => {
      try {
        const res = await fetch('/api/vip/unlock', {
          method: 'POST',
          headers: authHeaders(),
          body: JSON.stringify({ slotId: activeSlotId })
        });
        const data = await res.json();
        if (data.success) {
          maxVipSlots = data.maxSlots || 6;
          renderDock();
          showToast(`👑 Đã kích hoạt ${data.vipBadge} (Grace Period ${data.gracePeriodSec}s, ${maxVipSlots} Slots)!`);
        }
      } catch (_) {
        showToast('👑 Đã kích hoạt VIP ADMIN - Duy Trường!');
      }
    });

    document.getElementById('btnSyncCloudRms').addEventListener('click', async () => {
      const slot = getActiveSlot();
      const recordPayload = {
        slotId: activeSlotId,
        direction: 'both',
        records: {
          HSO_AutoQuestCfg: btoa(JSON.stringify({ slotId: activeSlotId, lv: slot.lv, mapId: slot.mapId, ts: Date.now() }))
        }
      };

      if (!isCurrentlyOnline() && dualClient) {
        dualClient.enqueueOfflineAction('SYNC_RMS', recordPayload);
        updateDualNetworkUi();
        showToast(`📡 Đang Offline: Dữ liệu Save RMS Slot #${activeSlotId} đã đưa vào Hàng đợi Offline và sẽ tự đồng bộ khi có mạng!`);
        return;
      }

      try {
        const res = await fetch('/api/saves/sync', {
          method: 'POST',
          headers: authHeaders(),
          body: JSON.stringify(recordPayload)
        });
        const data = await res.json();
        showToast(`☁️ Đồng bộ Cloud RMS Slot #${activeSlotId} thành công (${data.recordCount || 42} bản ghi)!`);
      } catch (_) {
        if (dualClient) {
          dualClient.enqueueOfflineAction('SYNC_RMS', recordPayload);
          updateDualNetworkUi();
        }
        showToast(`📡 Đã lưu RMS Slot #${activeSlotId} vào Hàng đợi Offline!`);
      }
    });
  }

  renderDock();
  bindUiEvents();
  syncOverlayAndButtons();
  updateAccountUi();
  keepAlive.jsSocketOpen('27.0.14.78', 19129, 1);

  renderSlotCanvasFrame(getActiveSlot());
  setInterval(() => {
    const active = getActiveSlot();
    if (active && active.renderEnabled) {
      renderSlotCanvasFrame(active);
    }
  }, 50);

  setInterval(() => {
    pollRemoteLiveFrame(getActiveSlot());
  }, 1500);

  loadCatalogAndSync();
  setInterval(sendClientHeartbeat, 12000);
})();
