(function(){
  if (window.__gwHomeInject) return;
  window.__gwHomeInject = true;

  function injectErrorUserIdsBackBtn(){
    try {
      var href = String(location.href || '');
      var path = String(location.pathname || '');
      if (href.indexOf('error-userids') < 0 && path.indexOf('error-userids') < 0) return;
      if (document.getElementById('gw-back-btn')) return;
      var btn = document.createElement('a');
      btn.id = 'gw-back-btn';
      btn.href = 'https://gift-wallet.pages.dev/?nosplash=1';
      btn.textContent = '← ギフトウォレット';
      btn.setAttribute('style', [
        'position:fixed','left:12px','bottom:18px','z-index:2147483647',
        'background:#d4a843','color:#111','font-weight:800','font-size:13px',
        'text-decoration:none','padding:10px 14px','border-radius:999px',
        'box-shadow:0 4px 16px rgba(0,0,0,.35)','font-family:system-ui,sans-serif',
        'letter-spacing:.02em','-webkit-tap-highlight-color:transparent'
      ].join(';'));
      (document.body || document.documentElement).appendChild(btn);
    } catch (e) {}
  }
  injectErrorUserIdsBackBtn();
  setTimeout(injectErrorUserIdsBackBtn, 500);
  setTimeout(injectErrorUserIdsBackBtn, 1500);

  function pushBalance(){
    try {
      if (window.GiftWallet && window.GiftWallet.getWalletBalance) {
        var b = window.GiftWallet.getWalletBalance();
        if (b !== undefined && b !== null) window.__gwWalletBalance = b;
        if (typeof window.__gwSetWalletBalanceDisplay === 'function' && b) {
          window.__gwSetWalletBalanceDisplay(b);
        }
      }
    } catch (e) {}
  }
  function wireCushin(a){
    try {
      if (!a || a.getAttribute('data-gw-cushin-bound') === '1') return;
      a.setAttribute('data-gw-cushin', '1');
      a.setAttribute('data-gw-cushin-bound', '1');
      a.setAttribute('target', '_self');
      a.addEventListener('click', function(ev){
        try {
          if (window.GiftWallet && window.GiftWallet.openCushinQuick) {
            ev.preventDefault();
            ev.stopPropagation();
            window.GiftWallet.openCushinQuick();
          }
        } catch (e) {}
      }, true);
    } catch (e) {}
  }
  function rewrite(){
    try {
      // Cushin / クイック引出 — never rewrite to vaton / CSV
      var cushin = document.querySelectorAll(
        'a[data-gw-cushin="1"], a[href*="cushintools.net"][href*="quick-withdraw"]'
      );
      for (var c = 0; c < cushin.length; c++) wireCushin(cushin[c]);

      var links = document.querySelectorAll('a[href*="wallet.vaton.jp/point/point_logs"]');
      for (var i = 0; i < links.length; i++) {
        var a = links[i];
        if (a.getAttribute('data-gw-cushin') === '1') continue;
        // Guard: rewrite only once per element
        if (a.getAttribute('data-gw-csv') === '1') continue;
        a.setAttribute('data-gw-csv', '1');
        a.setAttribute('target', '_self');
        a.removeAttribute('rel');
        // Open history page only — no #gw-auto-csv, no preventDefault auto sync
        a.setAttribute('href', 'https://wallet.vaton.jp/point/point_logs');
      }
    } catch (e) {}
    pushBalance();
  }
  // No MutationObserver — setAttribute fired observer → infinite rewrite froze UI
  rewrite();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', rewrite);
  setTimeout(rewrite, 500);
  setTimeout(rewrite, 1500);
  setInterval(pushBalance, 2000);
})();
