// src/userStore.js
const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');
const config = require('../config');

const USERNAME_REGEX = /^[a-zA-Z0-9_.-]{1,32}$/;

class UserStore {
  constructor(dataDir = config.DATA_DIR) {
    this.dataDir = dataDir;
    this.usersFile = path.join(this.dataDir, 'users.json');
    this.reportsFile = path.join(this.dataDir, 'reports.json');
    fs.mkdirSync(this.dataDir, { recursive: true });
    this._state = this._load();
    this._ensureDefaultAdmin();
  }

  _load() {
    if (!fs.existsSync(this.usersFile)) {
      return { nextId: 2, users: [] };
    }
    try {
      const raw = JSON.parse(fs.readFileSync(this.usersFile, 'utf8'));
      if (raw && Array.isArray(raw.users)) {
        return raw;
      }
    } catch (err) {
      console.error('[UserStore] Lỗi đọc users.json:', err.message);
    }
    return { nextId: 2, users: [] };
  }

  _saveAtomic() {
    const tmpPath = `${this.usersFile}.tmp.${process.pid}.${Date.now()}`;
    fs.writeFileSync(tmpPath, JSON.stringify(this._state, null, 2), 'utf8');
    fs.renameSync(tmpPath, this.usersFile);
  }

  _ensureDefaultAdmin() {
    let admin = this._state.users.find((u) => u.id === 1 || u.username === 'admin');
    if (!admin) {
      const initPass = process.env.ADMIN_INITIAL_PASSWORD || 'admin123456';
      admin = {
        id: 1,
        username: 'admin',
        passwordHash: bcrypt.hashSync(initPass, 10),
        role: 'admin',
        locked: false,
        maxVMs: 100,
        quotaBytes: 2 * 1024 * 1024 * 1024,
        createdAt: new Date().toISOString()
      };
      this._state.users.push(admin);
      if (this._state.nextId <= 1) this._state.nextId = 2;
      this._saveAtomic();
    }
    this.ensureUserDirs(admin.id);
  }

  ensureUserDirs(userId) {
    const base = path.join(this.dataDir, 'users', `user_${Number(userId)}`);
    fs.mkdirSync(path.join(base, 'games'), { recursive: true });
    fs.mkdirSync(path.join(base, 'saves'), { recursive: true });
    return base;
  }

  validateUsername(username) {
    if (typeof username !== 'string') return false;
    if (username.includes(':') || username.includes('\n') || username.includes('\r')) {
      return false;
    }
    return USERNAME_REGEX.test(username);
  }

  findByUsername(username) {
    if (typeof username !== 'string') return null;
    return this._state.users.find((u) => u.username === username) || null;
  }

  findById(id) {
    const uid = Number(id);
    return this._state.users.find((u) => u.id === uid) || null;
  }

  async createUser(username, password, role = 'user') {
    if (!this.validateUsername(username)) {
      throw new Error('Tên tài khoản không hợp lệ (chỉ gồm chữ, số, _, ., - từ 1-32 ký tự, cấm dấu : và xuống dòng).');
    }
    if (typeof password !== 'string' || password.length < 4 || password.length > 128) {
      throw new Error('Mật khẩu phải từ 4 đến 128 ký tự.');
    }
    if (this.findByUsername(username)) {
      throw new Error('Tên tài khoản đã tồn tại.');
    }

    const passwordHash = await bcrypt.hash(password, 10);
    const user = {
      id: this._state.nextId++,
      username,
      passwordHash,
      role: role === 'admin' ? 'admin' : 'user',
      locked: false,
      maxVMs: config.DEFAULT_MAX_VMS,
      quotaBytes: config.DEFAULT_STORAGE_QUOTA_BYTES,
      createdAt: new Date().toISOString()
    };

    this._state.users.push(user);
    this._saveAtomic();
    this.ensureUserDirs(user.id);
    return this.sanitize(user);
  }

  async verifyCredentials(username, password) {
    if (!this.validateUsername(username) || typeof password !== 'string') {
      return null;
    }
    const user = this.findByUsername(username);
    if (!user) return null;
    const ok = await bcrypt.compare(password, user.passwordHash);
    if (!ok) return null;
    return user;
  }

  setLocked(userId, locked) {
    const user = this.findById(userId);
    if (!user) return null;
    user.locked = Boolean(locked);
    this._saveAtomic();
    return this.sanitize(user);
  }

  setQuota(userId, { maxVMs, quotaBytes }) {
    const user = this.findById(userId);
    if (!user) return null;
    if (Number.isInteger(Number(maxVMs)) && Number(maxVMs) >= 0) {
      user.maxVMs = Number(maxVMs);
    }
    if (Number.isFinite(Number(quotaBytes)) && Number(quotaBytes) >= 0) {
      user.quotaBytes = Number(quotaBytes);
    }
    this._saveAtomic();
    return this.sanitize(user);
  }

  deleteUser(userId) {
    const uid = Number(userId);
    if (uid === 1) throw new Error('Không thể xóa tài khoản quản trị viên gốc.');
    const idx = this._state.users.findIndex((u) => u.id === uid);
    if (idx === -1) return false;
    this._state.users.splice(idx, 1);
    this._saveAtomic();
    return true;
  }

  listAll() {
    return this._state.users.map((u) => this.sanitize(u));
  }

  sanitize(user) {
    if (!user) return null;
    const { passwordHash, ...safe } = user;
    return safe;
  }
}

module.exports = UserStore;