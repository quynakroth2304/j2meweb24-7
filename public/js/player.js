// public/js/player.js — Thin wrapper delegating to /js/user/userApp.js (tránh trùng lặp code)
(function () {
  'use strict';
  if (!document.querySelector('script[src*="/js/user/userApp.js"]')) {
    const s = document.createElement('script');
    s.src = '/js/user/userApp.js';
    document.body.appendChild(s);
  }
})();