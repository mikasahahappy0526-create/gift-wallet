// ==UserScript==
// @name         Cushin 新しいセッション自動タップ
// @namespace    https://gift-wallet.pages.dev/
// @version      1.0.0
// @description  クイック引き出し画面で「+ 新しいセッション」を自動タップ（#auto-new-session があるとき）
// @author       gift-wallet
// @match        https://cushintools.net/*
// @match        https://www.cushintools.net/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

(function () {
  'use strict';

  const HASH = '#auto-new-session';
  const MAX_WAIT_MS = 45000;
  const POLL_MS = 300;

  function wantsAuto() {
    return location.hash === HASH || location.hash.startsWith(HASH + '&') || location.hash.startsWith(HASH + '?');
  }

  function clearHash() {
    try {
      history.replaceState(null, '', location.pathname + location.search);
    } catch (_) {}
  }

  function findButton() {
    return document.querySelector('button[data-action="new-qr"]:not([disabled])');
  }

  function onQuickWithdraw() {
    const path = (location.pathname || '').replace(/\/$/, '') || '/';
    return path === '/dashboard/quick-withdraw';
  }

  async function tryClick() {
    if (!wantsAuto()) return;
    if (!onQuickWithdraw()) return;

    const started = Date.now();
    while (Date.now() - started < MAX_WAIT_MS) {
      const btn = findButton();
      if (btn) {
        clearHash();
        btn.click();
        return;
      }
      await new Promise((r) => setTimeout(r, POLL_MS));
    }
  }

  // SPA: hash / path 変化にも追従
  const mo = new MutationObserver(() => {
    if (wantsAuto() && onQuickWithdraw() && findButton()) {
      tryClick();
    }
  });
  mo.observe(document.documentElement, { childList: true, subtree: true });

  window.addEventListener('hashchange', tryClick);
  tryClick();
})();
