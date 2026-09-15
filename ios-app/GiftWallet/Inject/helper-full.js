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

(function(){
  if (window.__gwHelpersQuick) return;
  window.__gwHelpersQuick = true;

  function clearHash(){
    try {
      if (location.hash && location.hash.indexOf('auto-new-session') >= 0) {
        history.replaceState(null, '', location.pathname + location.search);
      }
    } catch (e) {}
  }

  function showCenteredQr(img){
    if (!img || !img.src) return;
    var existing = document.getElementById('gw-qr-overlay');
    if (existing) {
      var cur = existing.querySelector('img');
      if (cur && cur.src === img.src) return;
      existing.remove();
    }
    var overlay = document.createElement('div');
    overlay.id = 'gw-qr-overlay';
    overlay.setAttribute('style', [
      'position:fixed','inset:0','z-index:2147483646',
      'background:rgba(0,0,0,.82)','display:flex',
      'align-items:center','justify-content:center',
      'flex-direction:column','gap:16px','padding:24px',
      'box-sizing:border-box'
    ].join(';'));
    var wrap = document.createElement('div');
    wrap.setAttribute('style', [
      'background:#fff','border-radius:20px','padding:18px',
      'box-shadow:0 12px 40px rgba(0,0,0,.45)',
      'max-width:min(86vw,420px)','width:100%',
      'display:flex','align-items:center','justify-content:center'
    ].join(';'));
    var big = document.createElement('img');
    big.src = img.src;
    big.alt = 'QR';
    big.setAttribute('style', 'width:100%;height:auto;display:block;image-rendering:pixelated;');
    var label = document.createElement('div');
    label.textContent = 'スキャン待ち · タップで閉じる';
    label.setAttribute('style', 'color:#fff;font-size:14px;font-weight:700;letter-spacing:.04em;');
    wrap.appendChild(big);
    overlay.appendChild(wrap);
    overlay.appendChild(label);
    overlay.addEventListener('click', function(){ overlay.remove(); });
    document.documentElement.appendChild(overlay);
  }

  function findLatestQrImg(){
    var imgs = Array.prototype.slice.call(
      document.querySelectorAll('.qr-session-card .qr-image img, .qr-image img[alt="QR"], img[alt="QR"], img[src*="qr"], canvas')
    );
    for (var i = imgs.length - 1; i >= 0; i--) {
      var el = imgs[i];
      if (!el) continue;
      if (el.tagName === 'CANVAS') {
        try {
          if ((el.width || 0) < 40 || (el.height || 0) < 40) continue;
          var dataUrl = el.toDataURL('image/png');
          if (dataUrl && dataUrl.length > 200) return { src: dataUrl };
        } catch (e0) {}
        continue;
      }
      if (el.src && (el.src.indexOf('data:') === 0 || (el.naturalWidth || 0) > 0)) {
        return el;
      }
    }
    return null;
  }

  function watchQr(){
    if (window.__gwQrWatchStarted) return;
    window.__gwQrWatchStarted = true;
    var lastSrc = '';
    function tick(){
      try {
        var path = (location.pathname || '').replace(/\/$/, '') || '/';
        if (path === '/dashboard/quick-withdraw') {
          var img = findLatestQrImg();
          if (img && img.src && img.src !== lastSrc) {
            lastSrc = img.src;
            showCenteredQr(img);
          }
        }
      } catch (e) {}
      setTimeout(tick, 500);
    }
    try {
      var mo = new MutationObserver(function(){
        var img = findLatestQrImg();
        if (img && img.src && img.src !== lastSrc) {
          lastSrc = img.src;
          showCenteredQr(img);
        }
      });
      mo.observe(document.documentElement, { childList:true, subtree:true, attributes:true, attributeFilter:['src'] });
    } catch (e) {}
    tick();
  }

  function autoTap(){
    if (window.__gwAutoTapStarted) return;
    window.__gwAutoTapStarted = true;
    var maxMs = 45000;
    var started = Date.now();
    function tick(){
      try {
        var path = (location.pathname || '').replace(/\/$/, '') || '/';
        if (path !== '/dashboard/quick-withdraw') {
          if (Date.now() - started < maxMs) setTimeout(tick, 400);
          return;
        }
        var btn = document.querySelector('button[data-action="new-qr"]:not([disabled])');
        if (btn) {
          clearHash();
          btn.click();
          return;
        }
      } catch (e) {}
      if (Date.now() - started < maxMs) setTimeout(tick, 400);
    }
    tick();
  }

  watchQr();
  autoTap();
})();
