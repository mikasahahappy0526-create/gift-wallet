(function(){
  if (window.__gwHelpersBase) return;
  window.__gwHelpersBase = true;

  function injectBackBtn(){
    try {
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

  function saveErr(userId, error, at){
    try {
      if (!userId || !window.GiftWallet || !window.GiftWallet.saveErrorUserId) return;
      window.GiftWallet.saveErrorUserId(
        String(userId),
        String(error || 'failed'),
        String(at || new Date().toISOString())
      );
    } catch (e) {}
  }

  function lookFailedItem(item){
    try {
      if (!item || typeof item !== 'object') return;
      var status = String(item.status || item.state || item.result || '').toLowerCase();
      var failed = status.indexOf('fail') >= 0 || status.indexOf('error') >= 0 ||
        status === 'ng' || item.failed === true || item.success === false;
      if (!failed) return;
      var userId = item.username || item.userName || item.user_id || item.userId ||
        item.tiktok_username || item.tiktokUsername || item.account || item.name || '';
      if (!userId) return;
      var err = item.error || item.message || item.reason || item.status || 'failed';
      var at = item.updated_at || item.updatedAt || item.created_at || item.at || new Date().toISOString();
      saveErr(userId, err, at);
    } catch (e) {}
  }

  function scanPayload(data){
    try {
      if (!data) return;
      if (Array.isArray(data)) {
        for (var i = 0; i < data.length; i++) lookFailedItem(data[i]);
        return;
      }
      if (typeof data !== 'object') return;
      lookFailedItem(data);
      var keys = ['tasks','items','sessions','data','results','list'];
      for (var k = 0; k < keys.length; k++) {
        if (Array.isArray(data[keys[k]])) scanPayload(data[keys[k]]);
      }
    } catch (e) {}
  }

  function wrapFetch(){
    try {
      if (window.__gwFetchWrapped || typeof window.fetch !== 'function') return;
      window.__gwFetchWrapped = true;
      var orig = window.fetch.bind(window);
      window.fetch = function(){
        var args = arguments;
        var url = '';
        try {
          if (typeof args[0] === 'string') url = args[0];
          else if (args[0] && args[0].url) url = args[0].url;
        } catch (e) {}
        return orig.apply(null, args).then(function(res){
          try {
            var u = String(url || (res && res.url) || '');
            if (u.indexOf('/api/tasks') >= 0 || u.indexOf('/api/') >= 0) {
              res.clone().json().then(function(data){ scanPayload(data); }).catch(function(){});
            }
          } catch (e) {}
          return res;
        });
      };
    } catch (e) {}
  }

  function scanDomFailed(){
    try {
      var cards = document.querySelectorAll(
        '.qr-session-card, .session-card, [class*="session"], [class*="task"], [class*="failed"], [class*="error"]'
      );
      for (var i = 0; i < cards.length; i++) {
        var el = cards[i];
        var text = (el.innerText || el.textContent || '');
        var low = text.toLowerCase();
        if (low.indexOf('fail') < 0 && low.indexOf('error') < 0 &&
            text.indexOf('失敗') < 0 && text.indexOf('エラー') < 0) continue;
        var userId = '';
        var userEl = el.querySelector('[class*="user"], [class*="name"], .username, .tiktok-username');
        if (userEl) userId = (userEl.innerText || userEl.textContent || '').trim();
        if (!userId) {
          var m = text.match(/@([A-Za-z0-9._]{2,64})/);
          if (m) userId = m[1];
        }
        if (!userId) {
          var lines = text.split(/\n+/).map(function(s){ return s.trim(); }).filter(Boolean);
          for (var j = 0; j < lines.length; j++) {
            if (/^[A-Za-z0-9._]{3,64}$/.test(lines[j]) && lines[j].indexOf('http') < 0) {
              userId = lines[j];
              break;
            }
          }
        }
        if (!userId) continue;
        var err = 'failed';
        if (text.indexOf('失敗') >= 0) err = '失敗';
        else if (text.indexOf('エラー') >= 0) err = 'エラー';
        else if (low.indexOf('error') >= 0) err = 'error';
        saveErr(userId, err, new Date().toISOString());
      }
    } catch (e) {}
  }

  function startErrorWatcher(){
    if (window.__gwErrorWatchStarted) return;
    window.__gwErrorWatchStarted = true;
    wrapFetch();
    function tick(){
      scanDomFailed();
      setTimeout(tick, 2500);
    }
    try {
      var mo = new MutationObserver(function(){ scanDomFailed(); });
      mo.observe(document.documentElement, { childList:true, subtree:true });
    } catch (e) {}
    tick();
  }

  function ensure(){
    injectBackBtn();
    startErrorWatcher();
  }
  ensure();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ensure);
  }
  setTimeout(ensure, 800);
  setTimeout(ensure, 2000);
})();
