(function(){
  if (window.__gwVatonBalance) return;
  window.__gwVatonBalance = true;

  function toast(msg){
    try {
      if (window.GiftWallet && window.GiftWallet.showToast) window.GiftWallet.showToast(String(msg));
    } catch (e) {}
  }

  function saveBalance(digits){
    try {
      if (!digits) return;
      if (window.GiftWallet && window.GiftWallet.setWalletBalance) {
        window.GiftWallet.setWalletBalance(String(digits));
      }
      window.__gwWalletBalance = String(digits);
    } catch (e) {}
  }

  function scrapeBalance(){
    try {
      var NUM = /([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,})\s*(?:ポイント|pt|P)/;
      var LABEL = /(保有ポイント|所持ポイント|ポイント残高|現在のポイント|利用可能|残高)/;
      var ROW = /(20[0-9]{2}[\/\-年.][0-9]{1,2}|[0-9]{1,2}[\/月][0-9]{1,2}|[+＋\-−－]\s*[0-9])/;
      function txt(el){ return ((el && (el.innerText || el.textContent)) || '').replace(/\s+/g, ' ').trim(); }
      function num(t){ var m = String(t || '').match(NUM); if (!m) return null; var n = parseInt(m[1].replace(/,/g, ''), 10); return isNaN(n) ? null : String(n); }
      var best = null;
      // 1) number next to a balance label
      var all = document.body ? document.body.getElementsByTagName('*') : [];
      for (var i = 0; i < all.length && !best; i++) {
        var el = all[i];
        var own = '';
        for (var c = el.firstChild; c; c = c.nextSibling) if (c.nodeType === 3) own += c.nodeValue;
        if (!LABEL.test(own) || own.length > 40) continue;
        var cands = [el, el.nextElementSibling, el.parentElement, el.parentElement && el.parentElement.nextElementSibling];
        for (var k = 0; k < cands.length && !best; k++) {
          var t = txt(cands[k]);
          if (!t || t.length > 120 || ROW.test(t)) continue;
          best = num(t);
        }
      }
      // 2) first "N ポイント" in document order, skipping history rows
      if (!best) {
        var lines = ((document.body && (document.body.innerText || document.body.textContent)) || '').split(/\n+/);
        for (var j = 0; j < lines.length && !best; j++) {
          if (ROW.test(lines[j])) continue;
          best = num(lines[j]);
        }
      }
      if (best) saveBalance(best);
      return best;
    } catch (e) { return null; }
  }

  window.__gwScrapeBalance = scrapeBalance;

  function tick(){
    scrapeBalance();
    setTimeout(tick, 2500);
  }
  var scrapeTimer = null;
  function scheduleScrape(){
    if (scrapeTimer) return;
    scrapeTimer = setTimeout(function(){
      scrapeTimer = null;
      scrapeBalance();
    }, 300);
  }
  scrapeBalance();
  setTimeout(scrapeBalance, 800);
  setTimeout(scrapeBalance, 2000);
  setTimeout(scrapeBalance, 5000);
  try {
    var mo = new MutationObserver(function(){ scheduleScrape(); });
    mo.observe(document.documentElement, { childList:true, subtree:true, characterData:true });
  } catch (e) {}
  tick();
})();
