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
      var best = null;
      var bestVal = -1;
      // Prefer explicit large point labels: "270,720 ポイント"
      var body = (document.body && (document.body.innerText || document.body.textContent)) || '';
      var re = /([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,})\s*ポイント/g;
      var m;
      while ((m = re.exec(body)) !== null) {
        var n = parseInt(String(m[1]).replace(/,/g, ''), 10);
        if (isNaN(n) || n < 0) continue;
        // Ignore tiny labels; keep the largest plausible balance
        if (n > bestVal) { bestVal = n; best = String(n); }
      }
      // Also scan prominent numeric nodes
      var nodes = document.querySelectorAll('h1,h2,h3,strong,b,[class*="point"],[class*="Point"],[class*="balance"],[data-testid]');
      for (var i = 0; i < nodes.length; i++) {
        var t = (nodes[i].innerText || nodes[i].textContent || '').replace(/\s+/g, ' ').trim();
        var mm = t.match(/^([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,})\s*ポイント?$/);
        if (!mm) mm = t.match(/([0-9]{1,3}(?:,[0-9]{3})+)\s*ポイント/);
        if (!mm) continue;
        var nn = parseInt(String(mm[1]).replace(/,/g, ''), 10);
        if (!isNaN(nn) && nn > bestVal) { bestVal = nn; best = String(nn); }
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
