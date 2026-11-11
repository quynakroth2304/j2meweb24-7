// public/js/admin.js — Thin wrapper delegating to /js/admin/adminApp.js (tránh trùng lặp code)
(function () {
  'use strict';
  if (!document.querySelector('script[src*="/js/admin/adminApp.js"]')) {
    const s = document.createElement('script');
    s.src = '/js/admin/adminApp.js';
    document.body.appendChild(s);
  }
})();