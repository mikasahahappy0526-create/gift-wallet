package net.giftwallet.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: android.content.SharedPreferences
    private val syncHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingCsvText: String? = null
    private var csvSyncInFlight = false

    private val hourlySyncRunnable = object : Runnable {
        override fun run() {
            startCsvSync(fromTimer = true)
            syncHandler.postDelayed(this, HOUR_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Clear legacy forever-splash flag from older APKs
        if (prefs.contains(KEY_SPLASH_DONE)) {
            prefs.edit().remove(KEY_SPLASH_DONE).apply()
        }
        webView = findViewById(R.id.webView)
        setupWebView()

        // Cold start / process recreate: always show splash (no persistent skip)
        webView.loadUrl(HOME_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){var o=document.getElementById('gw-qr-overlay'); if(o){o.remove(); return '1';} return '0';})();"
                ) { result ->
                    if (result != null && result.contains("1")) return@evaluateJavascript
                    runOnUiThread {
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
            }
        })

        // Hourly CSV sync while process alive; also once shortly after start if due
        syncHandler.postDelayed(hourlySyncRunnable, HOUR_MS)
        syncHandler.postDelayed({
            if (isCsvSyncDue()) startCsvSync(fromTimer = true)
        }, STARTUP_SYNC_DELAY_MS)
    }

    override fun onDestroy() {
        syncHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString.replace("; wv", "")
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            // Allow blob / data downloads via listener path
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.addJavascriptInterface(GiftWalletBridge(), "GiftWallet")

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                maybeInjectHelpers(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                maybeInjectHelpers(view, url)
                maybeFlushPendingCsv(url)
            }
        }
    }

    private fun handleDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.isNullOrBlank()) return
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val name = if (guessed.contains(".csv", ignoreCase = true) ||
            (mimeType != null && mimeType.contains("csv"))
        ) {
            FIXED_CSV_NAME
        } else {
            guessed
        }

        if (url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)) {
            // Blob downloads are handled by injected JS → onCsvBase64
            return
        }

        ioExecutor.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", userAgent ?: "")
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
                conn.connect()
                val bytes = conn.inputStream.use { it.readBytes() }
                val text = decodeCsvBytes(bytes)
                runOnUiThread { importCsvText(text, name) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "CSVダウンロードに失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeCsvBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // Try UTF-8 first; fall back to Shift_JIS common for JP CSVs
        return try {
            val s = String(bytes, Charsets.UTF_8)
            if (s.contains('\uFFFD')) String(bytes, Charset.forName("Shift_JIS")) else s
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun isCsvSyncDue(): Boolean {
        val last = prefs.getLong(KEY_LAST_CSV_SYNC_MS, 0L)
        return System.currentTimeMillis() - last >= HOUR_MS
    }

    private fun startCsvSync(fromTimer: Boolean) {
        if (csvSyncInFlight) return
        if (fromTimer && !isCsvSyncDue()) return
        csvSyncInFlight = true
        webView.loadUrl(POINT_LOGS_AUTO_URL)
        // Safety reset if download never arrives
        syncHandler.postDelayed({ csvSyncInFlight = false }, 120_000L)
    }

    private fun markCsvSynced() {
        prefs.edit().putLong(KEY_LAST_CSV_SYNC_MS, System.currentTimeMillis()).apply()
        csvSyncInFlight = false
    }

    private fun importCsvText(text: String, @Suppress("UNUSED_PARAMETER") filename: String) {
        val fixed = FIXED_CSV_NAME
        val payload = JSONObject.quote(text)
        val nameJs = JSONObject.quote(fixed)
        val current = webView.url.orEmpty()
        if (current.contains("gift-wallet.pages.dev")) {
            webView.evaluateJavascript(
                "(function(){try{if(typeof window.importGiftWalletCsvText==='function'){return window.importGiftWalletCsvText($payload,$nameJs)?'1':'0';}return 'missing';}catch(e){return 'err';}})();"
            ) { result ->
                if (result != null && result.contains("1")) {
                    markCsvSynced()
                    Toast.makeText(this, "CSVを同期しました", Toast.LENGTH_SHORT).show()
                } else {
                    // Function not ready yet — queue and retry on load
                    pendingCsvText = text
                    webView.loadUrl(HOME_URL_NOSPLASH)
                }
            }
        } else {
            pendingCsvText = text
            webView.loadUrl(HOME_URL_NOSPLASH)
        }
    }

    private fun maybeFlushPendingCsv(url: String?) {
        val pending = pendingCsvText ?: return
        if (url.isNullOrBlank() || !url.contains("gift-wallet.pages.dev")) return
        val payload = JSONObject.quote(pending)
        val nameJs = JSONObject.quote(FIXED_CSV_NAME)
        // Slight delay so page JS (parseCSV / slotData) is ready
        syncHandler.postDelayed({
            webView.evaluateJavascript(
                "(function(){try{if(typeof window.importGiftWalletCsvText==='function'){return window.importGiftWalletCsvText($payload,$nameJs)?'1':'0';}return 'missing';}catch(e){return 'err';}})();"
            ) { result ->
                if (result != null && result.contains("1")) {
                    pendingCsvText = null
                    markCsvSynced()
                    Toast.makeText(this, "CSVを同期しました", Toast.LENGTH_SHORT).show()
                }
            }
        }, 400)
    }

    private fun maybeInjectHelpers(view: WebView?, url: String?) {
        if (view == null || url.isNullOrBlank()) return
        when {
            url.contains("cushintools.net") -> {
                val isQuick =
                    url.contains("/dashboard/quick-withdraw") || url.contains("auto-new-session")
                val js = if (isQuick) HELPER_JS_FULL else HELPER_JS_BASE
                view.evaluateJavascript(js, null)
            }
            url.contains("wallet.vaton.jp") -> {
                view.evaluateJavascript(VATON_CSV_JS, null)
            }
            url.contains("gift-wallet.pages.dev") -> {
                view.evaluateJavascript(HOME_INJECT_JS, null)
            }
        }
    }

    inner class GiftWalletBridge {
        @JavascriptInterface
        fun saveErrorUserId(userId: String, error: String, at: String) {
            try {
                val id = userId.trim()
                if (id.isEmpty()) return
                val arr = loadErrorArray()
                var found = -1
                var existingPassword = ""
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("userId") == id) {
                        found = i
                        existingPassword = o.optString("password", "")
                        break
                    }
                }
                val obj = JSONObject()
                obj.put("userId", id)
                obj.put("error", error)
                obj.put("at", if (at.isBlank()) {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date())
                } else at)
                // Preserve password on re-save of the same userId
                obj.put("password", existingPassword)
                if (found >= 0) {
                    arr.put(found, obj)
                } else {
                    arr.put(obj)
                }
                prefs.edit().putString(KEY_ERROR_USERIDS, arr.toString()).apply()
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun setErrorUserPassword(userId: String, password: String) {
            try {
                val id = userId.trim()
                if (id.isEmpty()) return
                val arr = loadErrorArray()
                var found = -1
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("userId") == id) {
                        found = i
                        break
                    }
                }
                if (found >= 0) {
                    val o = arr.getJSONObject(found)
                    o.put("password", password)
                    arr.put(found, o)
                } else {
                    val obj = JSONObject()
                    obj.put("userId", id)
                    obj.put("error", "")
                    obj.put("at", "")
                    obj.put("password", password)
                    arr.put(obj)
                }
                prefs.edit().putString(KEY_ERROR_USERIDS, arr.toString()).apply()
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun getErrorUserIdsJson(): String {
            return try {
                prefs.getString(KEY_ERROR_USERIDS, "[]") ?: "[]"
            } catch (_: Exception) {
                "[]"
            }
        }

        @JavascriptInterface
        fun clearErrorUserIds() {
            try {
                prefs.edit().putString(KEY_ERROR_USERIDS, "[]").apply()
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onCsvBase64(base64: String, filename: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val text = decodeCsvBytes(bytes)
                runOnUiThread {
                    importCsvText(text, filename.ifBlank { FIXED_CSV_NAME })
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "CSVの読込に失敗しました", Toast.LENGTH_SHORT).show()
                    csvSyncInFlight = false
                }
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun notifyCsvLoginRequired() {
            runOnUiThread {
                csvSyncInFlight = false
                Toast.makeText(
                    this@MainActivity,
                    "ギフトウォレットにログインしてください",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        @JavascriptInterface
        fun startGiftWalletCsvSync() {
            runOnUiThread { startCsvSync(fromTimer = false) }
        }
    }

    private fun loadErrorArray(): JSONArray {
        return try {
            JSONArray(prefs.getString(KEY_ERROR_USERIDS, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    companion object {
        private const val PREFS_NAME = "gift_wallet"
        private const val KEY_SPLASH_DONE = "splash_done" // legacy, cleared on launch
        private const val KEY_ERROR_USERIDS = "error_userids"
        private const val KEY_LAST_CSV_SYNC_MS = "last_csv_sync_ms"
        private const val HOME_URL = "https://gift-wallet.pages.dev/"
        private const val HOME_URL_NOSPLASH = "https://gift-wallet.pages.dev/?nosplash=1"
        private const val POINT_LOGS_AUTO_URL =
            "https://wallet.vaton.jp/point/point_logs#gw-auto-csv"
        private const val FIXED_CSV_NAME = "ポイント履歴_今月.csv"
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val STARTUP_SYNC_DELAY_MS = 20_000L

        private val HELPER_JS_BASE = """
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
        """.trimIndent()

        private val HELPER_JS_QUICK = """
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
                  document.querySelectorAll('.qr-session-card .qr-image img, .qr-image img[alt="QR"], img[alt="QR"]')
                );
                for (var i = imgs.length - 1; i >= 0; i--) {
                  if (imgs[i] && imgs[i].src && imgs[i].src.indexOf('data:') === 0 || (imgs[i].naturalWidth || 1) > 0) {
                    if (imgs[i].src) return imgs[i];
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
        """.trimIndent()

        private val HELPER_JS_FULL = HELPER_JS_BASE + "\n" + HELPER_JS_QUICK

        private val HOME_INJECT_JS = """
            (function(){
              if (window.__gwHomeInject) return;
              window.__gwHomeInject = true;
              function rewrite(){
                try {
                  var links = document.querySelectorAll('a[href*="wallet.vaton.jp/point/point_logs"]');
                  for (var i = 0; i < links.length; i++) {
                    var a = links[i];
                    a.setAttribute('target', '_self');
                    a.removeAttribute('rel');
                    a.setAttribute('href', 'https://wallet.vaton.jp/point/point_logs#gw-auto-csv');
                    a.addEventListener('click', function(ev){
                      try {
                        if (window.GiftWallet && window.GiftWallet.startGiftWalletCsvSync) {
                          ev.preventDefault();
                          window.GiftWallet.startGiftWalletCsvSync();
                        }
                      } catch (e) {}
                    }, true);
                  }
                } catch (e) {}
              }
              rewrite();
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', rewrite);
              setTimeout(rewrite, 500);
              setTimeout(rewrite, 1500);
              try {
                var mo = new MutationObserver(function(){ rewrite(); });
                mo.observe(document.documentElement, { childList:true, subtree:true });
              } catch (e) {}
            })();
        """.trimIndent()

        private val VATON_CSV_JS = """
            (function(){
              if (window.__gwVatonCsv) return;
              window.__gwVatonCsv = true;

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
                  function sendB64(b64){
                    try {
                      if (window.GiftWallet && window.GiftWallet.onCsvBase64) {
                        window.GiftWallet.onCsvBase64(b64, name);
                      }
                    } catch (err) {}
                  }
                  if (href.indexOf('data:') === 0) {
                    var parts = String(href).split(',');
                    sendB64(parts[1] || '');
                    return false;
                  }
                  fetch(href).then(function(r){ return r.blob(); }).then(function(b){
                    var reader = new FileReader();
                    reader.onload = function(){
                      var dataUrl = String(reader.result || '');
                      sendB64(dataUrl.split(',')[1] || '');
                    };
                    reader.readAsDataURL(b);
                  }).catch(function(){ toast('CSVの取得に失敗しました'); });
                  return false;
                } catch (err) {}
              }, true);

              function findBtn(text){
                var nodes = document.querySelectorAll('button, a, [role="button"]');
                for (var i = 0; i < nodes.length; i++) {
                  var s = (nodes[i].innerText || nodes[i].textContent || '').replace(/\s+/g,' ').trim();
                  if (s.indexOf(text) >= 0) return nodes[i];
                }
                return null;
              }

              function looksLoggedOut(){
                try {
                  if (findBtn('利用実績CSVダウンロード')) return false;
                  var path = (location.pathname || '').toLowerCase();
                  if (path.indexOf('login') >= 0 || path.indexOf('sign_in') >= 0 || path.indexOf('signin') >= 0) return true;
                  var t = (document.body && (document.body.innerText || '')) || '';
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

              function autoCsv(){
                if (!location.hash || location.hash.indexOf('gw-auto-csv') < 0) return;
                if (window.__gwAutoCsvStarted) return;
                window.__gwAutoCsvStarted = true;
                var tries = 0;
                function tick(){
                  tries++;
                  try {
                    if (looksLoggedOut()) {
                      if (tries >= 10) {
                        notifyLogin();
                        clearAutoHash();
                        return;
                      }
                      setTimeout(tick, 700);
                      return;
                    }
                    var openBtn = findBtn('利用実績CSVダウンロード');
                    if (!openBtn) {
                      if (tries >= 30) {
                        notifyLogin();
                        clearAutoHash();
                        return;
                      }
                      setTimeout(tick, 500);
                      return;
                    }
                    openBtn.click();
                    setTimeout(function(){
                      // Default select is current month (YYYY-MM); click download
                      var dl = findBtn('ダウンロードする');
                      if (dl) {
                        dl.click();
                      } else {
                        toast('CSVダウンロードボタンが見つかりません');
                      }
                      clearAutoHash();
                    }, 700);
                  } catch (e) {
                    if (tries < 30) setTimeout(tick, 500);
                  }
                }
                setTimeout(tick, 400);
              }

              autoCsv();
              setTimeout(autoCsv, 1200);
              setTimeout(autoCsv, 3000);
            })();
        """.trimIndent()
    }
}
