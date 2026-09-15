(function(){
  if (window.__gwVatonCsv) return;
  window.__gwVatonCsv = true;

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

  function toast(msg){
    try {
      if (window.GiftWallet && window.GiftWallet.showToast) window.GiftWallet.showToast(String(msg));
    } catch (e) {}
  }

  function notifyLogin(){
    try {
      if (window.GiftWallet && window.GiftWallet.notifyCsvLoginRequired) {
        window.GiftWallet.notifyCsvLoginRequired();
      } else {
        toast('ギフトウォレットにログインしてください');
      }
    } catch (e) {}
  }

  function notifyFail(msg){
    try {
      if (window.GiftWallet && window.GiftWallet.notifyCsvSyncFailed) {
        window.GiftWallet.notifyCsvSyncFailed(String(msg || 'CSVの自動取得に失敗しました'));
      } else {
        toast(msg || 'CSVの自動取得に失敗しました');
      }
    } catch (e) {}
  }

  function sendB64(b64, name){
    try {
      if (window.__gwCsvCaptured) return;
      window.__gwCsvCaptured = true;
      if (window.__gwScrapeBalance) try { window.__gwScrapeBalance(); } catch (e0) {}
      clearAutoHash();
      window.__gwAutoCsvStarted = false;
      if (window.GiftWallet && window.GiftWallet.onCsvBase64) {
        window.GiftWallet.onCsvBase64(b64, name || 'ポイント履歴_今月.csv');
      }
    } catch (err) {}
  }

  function blobToB64(blob, name){
    try {
      var reader = new FileReader();
      reader.onload = function(){
        var dataUrl = String(reader.result || '');
        sendB64(dataUrl.split(',')[1] || '', name);
      };
      reader.readAsDataURL(blob);
    } catch (e) {
      toast('CSVの取得に失敗しました');
    }
  }

  function maybeCaptureBlob(blob, nameHint){
    try {
      if (!blob) return;
      var type = String(blob.type || '').toLowerCase();
      var name = nameHint || 'ポイント履歴_今月.csv';
      var looksCsv = type.indexOf('csv') >= 0 || type.indexOf('text') >= 0 ||
        /\.csv($|\?)/i.test(name) || type === '' || type === 'application/octet-stream';
      if (!looksCsv) return;
      // Only auto-capture during active sync
      if (!location.hash || location.hash.indexOf('gw-auto-csv') < 0) {
        if (!window.__gwAutoCsvStarted) return;
      }
      blobToB64(blob, name);
    } catch (e) {}
  }

  // Capture blob:<a download> clicks (vaton builds CSV client-side)
  document.addEventListener('click', function(e){
    try {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[download]') : null;
      if (!a) return;
      var href = a.href || '';
      var name = a.getAttribute('download') || 'ポイント履歴_今月.csv';
      if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
      e.preventDefault();
      e.stopPropagation();
      if (href.indexOf('data:') === 0) {
        var parts = String(href).split(',');
        sendB64(parts[1] || '', name);
        return false;
      }
      fetch(href).then(function(r){ return r.blob(); }).then(function(b){
        blobToB64(b, name);
      }).catch(function(){ toast('CSVの取得に失敗しました'); });
      return false;
    } catch (err) {}
  }, true);

  // Extra capture: when SPA creates object URL for CSV blob
  try {
    var _createObjectURL = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function(obj){
      var url = _createObjectURL(obj);
      try {
        if (obj && typeof Blob !== 'undefined' && obj instanceof Blob) {
          maybeCaptureBlob(obj, 'ポイント履歴_今月.csv');
        }
      } catch (e1) {}
      return url;
    };
  } catch (e) {}

  function normText(el){
    return ((el && (el.innerText || el.textContent)) || '').replace(/\s+/g,' ').trim();
  }

  function pageText(){
    try {
      return ((document.body && (document.body.innerText || document.body.textContent)) || '').replace(/\s+/g,' ');
    } catch (e) { return ''; }
  }

  function findClickables(preferExact, text, excludeTexts){
    excludeTexts = excludeTexts || [];
    var sel = 'button, a, [role="button"], [role="tab"], div, span, li, p';
    var nodes = document.querySelectorAll(sel);
    var exact = null;
    var short = null;
    var loose = null;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var s = normText(el);
      if (!s) continue;
      var skip = false;
      for (var j = 0; j < excludeTexts.length; j++) {
        if (s.indexOf(excludeTexts[j]) >= 0 && s.indexOf(text) < 0) { skip = true; break; }
        if (s === excludeTexts[j]) { skip = true; break; }
      }
      if (skip) continue;
      // Prefer short leaf-ish nodes (avoid huge containers / title-only wrappers)
      if (s === text) {
        if (!exact || s.length <= normText(exact).length) exact = el;
        continue;
      }
      if (s.indexOf(text) >= 0 && s.length < 40) {
        // Exclude title-only modal header nodes that contain the phrase but aren't the action
        if (text === 'ダウンロードする' && (s.indexOf('利用実績CSVダウンロード') >= 0 || s.indexOf('とじる') >= 0)) continue;
        if (!short || s.length < normText(short).length) short = el;
        continue;
      }
      if (!preferExact && s.indexOf(text) >= 0 && s.length < 80) {
        if (text === 'ダウンロードする' && (s.indexOf('利用実績CSVダウンロード') >= 0 || s.indexOf('とじる') >= 0)) continue;
        if (!loose || s.length < normText(loose).length) loose = el;
      }
    }
    return exact || short || loose;
  }

  function findBtn(text){
    var exclude = [];
    if (text === 'ダウンロードする') exclude = ['とじる'];
    return findClickables(true, text, exclude);
  }

  function fireClick(el){
    if (!el) return false;
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e0) {}
    try {
      var opts = { bubbles: true, cancelable: true, view: window };
      try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e1) {}
      try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e2) {}
      try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e3) {}
      try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e4) {}
      try { el.dispatchEvent(new MouseEvent('click', opts)); } catch (e5) {}
      try { el.click(); } catch (e6) {}
      return true;
    } catch (e) {
      try { el.click(); return true; } catch (e2) { return false; }
    }
  }

  function isModalOpen(){
    try {
      var t = pageText();
      if (t.indexOf('利用実績CSVダウンロード') >= 0 && t.indexOf('ダウンロードする') >= 0) {
        // Prefer detecting an actual download action node
        if (findBtn('ダウンロードする')) return true;
        // Modal title + close also indicate open dialog
        if (t.indexOf('とじる') >= 0) return true;
      }
      var dialogs = document.querySelectorAll('[role="dialog"], .modal, [class*="modal"], [class*="Modal"], [class*="dialog"]');
      for (var i = 0; i < dialogs.length; i++) {
        var s = normText(dialogs[i]);
        if (s.indexOf('利用実績CSVダウンロード') >= 0 && s.indexOf('ダウンロードする') >= 0) return true;
      }
    } catch (e) {}
    return false;
  }

  function clickCloseModal(){
    try {
      var closeBtn = findClickables(true, 'とじる', []);
      if (closeBtn) fireClick(closeBtn);
    } catch (e) {}
  }

  function clickHistoryTab(){
    try {
      var nodes = document.querySelectorAll('button, a, [role="tab"], [role="button"], div, span, li, p');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var s = normText(el);
        if (s === 'ポイント履歴' || (s.indexOf('ポイント履歴') >= 0 && s.indexOf('有効期限') < 0 && s.length < 20)) {
          fireClick(el);
          return true;
        }
      }
    } catch (e) {}
    return false;
  }

  function looksLoggedOut(){
    try {
      if (findBtn('利用実績CSVダウンロード') || isModalOpen()) return false;
      var path = (location.pathname || '').toLowerCase();
      if (path.indexOf('login') >= 0 || path.indexOf('sign_in') >= 0 || path.indexOf('signin') >= 0) return true;
      var t = pageText();
      if (t.indexOf('利用実績CSVダウンロード') >= 0) return false;
      if (document.querySelector('input[type="password"]') && (t.indexOf('ログイン') >= 0 || t.indexOf('メール') >= 0)) return true;
      if (t.indexOf('ログイン') >= 0 && t.indexOf('パスワード') >= 0) return true;
    } catch (e) {}
    return false;
  }

  function clearAutoHash(){
    try {
      if (location.hash && location.hash.indexOf('gw-auto-csv') >= 0) {
        history.replaceState(null, '', location.pathname + location.search);
      }
    } catch (e) {}
  }

  function pollDownload(deadline){
    if (window.__gwCsvCaptured) return;
    if (!location.hash || location.hash.indexOf('gw-auto-csv') < 0) {
      window.__gwAutoCsvStarted = false;
      return;
    }
    var now = Date.now();
    if (now >= deadline) {
      clickCloseModal();
      clearAutoHash();
      window.__gwAutoCsvStarted = false;
      notifyFail('CSVダウンロードに失敗しました。もう一度お試しください');
      return;
    }
    var dl = findBtn('ダウンロードする');
    if (dl) {
      fireClick(dl);
    }
    setTimeout(function(){ pollDownload(deadline); }, 500);
  }

  function autoCsv(){
    // Disabled: never auto-open CSV modal / never poll-click ダウンロードする.
    // Manual tap of 「ダウンロードする」 still imports via click/blob capture + DownloadListener.
    return;
  }

  injectBackBtn();
  setTimeout(injectBackBtn, 800);
  setTimeout(injectBackBtn, 2000);
  try {
    var mo = new MutationObserver(function(){ injectBackBtn(); });
    mo.observe(document.documentElement, { childList:true, subtree:true });
  } catch (e) {}

  // Do not call autoCsv — landing on point_logs must not open download modal
})();
