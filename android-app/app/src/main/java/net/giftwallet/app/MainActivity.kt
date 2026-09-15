package net.giftwallet.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
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
import java.util.ArrayDeque
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: android.content.SharedPreferences
    private val syncHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingCsvText: String? = null
    private var csvSyncInFlight = false
    private val chargeQueue: ArrayDeque<String> = ArrayDeque()
    private var currentChargeUrl: String? = null
    private var chargeBusy = false

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
                view.evaluateJavascript(CUSHIN_GIFT_JS, null)
            }
            url.contains("wallet.vaton.jp") -> {
                view.evaluateJavascript(VATON_CSV_JS, null)
                view.evaluateJavascript(VATON_BALANCE_JS, null)
                if (chargeBusy || isVatonChargePath(url)) {
                    view.evaluateJavascript(CHARGE_AUTO_JS, null)
                }
            }
            isGiftChargeHost(url) -> {
                view.evaluateJavascript(CHARGE_AUTO_JS, null)
            }
            url.contains("gift-wallet.pages.dev") -> {
                view.evaluateJavascript(HOME_INJECT_JS, null)
            }
        }
    }

    private fun isGiftChargeHost(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("giftee.biz") ||
            u.contains("g4b.giftee") ||
            u.contains("giftee.co") ||
            u.contains("eraberu")
    }

    private fun isVatonChargePath(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/lp/convert_to_point") ||
            u.contains("/lp/charge_serial_code") ||
            u.contains("/lp/merge_to_point") ||
            u.contains("/point/charge")
    }

    private fun normalizeGiftUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            val u = URL(t)
            if (u.protocol != "http" && u.protocol != "https") "" else u.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadChargedSet(): MutableSet<String> {
        val out = linkedSetOf<String>()
        try {
            val arr = JSONArray(prefs.getString(KEY_CHARGED_URLS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveChargedSet(set: Set<String>) {
        val arr = JSONArray()
        val list = set.toList()
        val start = (list.size - 300).coerceAtLeast(0)
        for (i in start until list.size) arr.put(list[i])
        prefs.edit().putString(KEY_CHARGED_URLS, arr.toString()).apply()
    }

    private fun isUrlCharged(url: String): Boolean = loadChargedSet().contains(url)

    private fun markUrlCharged(url: String) {
        val set = loadChargedSet()
        set.add(url)
        saveChargedSet(set)
    }

    private fun enqueueChargeUrl(raw: String) {
        val url = normalizeGiftUrl(raw)
        if (url.isEmpty()) return
        if (isUrlCharged(url)) return
        if (url == currentChargeUrl || chargeQueue.contains(url)) return
        chargeQueue.addLast(url)
        if (!chargeBusy) pumpChargeQueue()
    }

    private fun pumpChargeQueue() {
        if (chargeBusy) return
        val next = chargeQueue.pollFirst()
        if (next == null) {
            currentChargeUrl = null
            return
        }
        chargeBusy = true
        currentChargeUrl = next
        Toast.makeText(this, "チャージ中", Toast.LENGTH_SHORT).show()
        webView.loadUrl(next)
        syncHandler.postDelayed({
            if (chargeBusy && currentChargeUrl == next) {
                markUrlCharged(next)
                chargeBusy = false
                currentChargeUrl = null
                if (chargeQueue.isNotEmpty()) pumpChargeQueue()
                else webView.loadUrl(HOME_URL_NOSPLASH)
            }
        }, 180_000L)
    }

    private fun finishCurrentCharge(success: Boolean) {
        val cur = currentChargeUrl
        if (cur != null) markUrlCharged(cur)
        chargeBusy = false
        currentChargeUrl = null
        if (success) {
            Toast.makeText(this, "チャージしました", Toast.LENGTH_SHORT).show()
        }
        if (chargeQueue.isNotEmpty()) pumpChargeQueue()
        else webView.loadUrl(HOME_URL_NOSPLASH)
    }

    private fun abortChargeForLogin() {
        chargeQueue.clear()
        chargeBusy = false
        currentChargeUrl = null
        Toast.makeText(this, "ギフトウォレットにログインしてください", Toast.LENGTH_LONG).show()
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

        @JavascriptInterface
        fun setWalletBalance(points: String) {
            try {
                val cleaned = points.trim()
                if (cleaned.isEmpty() || cleaned == "—" || cleaned == "-") return
                val digits = cleaned.filter { it.isDigit() }
                if (digits.isEmpty()) return
                prefs.edit().putString(KEY_WALLET_BALANCE, digits).apply()
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun getWalletBalance(): String {
            return try {
                prefs.getString(KEY_WALLET_BALANCE, "") ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("gift-link", t))
                    Toast.makeText(this@MainActivity, "リンクをコピーしました", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun chargeGiftUrl(url: String) {
            runOnUiThread { enqueueChargeUrl(url) }
        }

        @JavascriptInterface
        fun onChargeComplete() {
            runOnUiThread { finishCurrentCharge(success = true) }
        }

        @JavascriptInterface
        fun notifyChargeLoginRequired() {
            runOnUiThread { abortChargeForLogin() }
        }

        @JavascriptInterface
        fun isGiftUrlCharged(url: String): Boolean {
            val u = normalizeGiftUrl(url)
            if (u.isEmpty()) return true
            return isUrlCharged(u)
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
        private const val KEY_WALLET_BALANCE = "wallet_balance"
        private const val KEY_CHARGED_URLS = "charged_gift_urls"
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
                pushBalance();
              }
              rewrite();
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', rewrite);
              setTimeout(rewrite, 500);
              setTimeout(rewrite, 1500);
              setInterval(pushBalance, 2000);
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
                      if (window.__gwScrapeBalance) try { window.__gwScrapeBalance(); } catch (e0) {}
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

              function clickHistoryTab(){
                try {
                  var nodes = document.querySelectorAll('button, a, [role="tab"], [role="button"], div, span, li');
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    var s = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
                    if (s === 'ポイント履歴' || (s.indexOf('ポイント履歴') >= 0 && s.indexOf('有効期限') < 0 && s.length < 20)) {
                      el.click();
                      return true;
                    }
                  }
                } catch (e) {}
                return false;
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
                    // Select 「ポイント履歴」 before CSV download UI (default is often 「ポイント有効期限」)
                    var openBtn = findBtn('利用実績CSVダウンロード');
                    if (!openBtn) {
                      if (tries >= 30) {
                        notifyLogin();
                        clearAutoHash();
                        return;
                      }
                      if (clickHistoryTab()) {
                        setTimeout(tick, 450);
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

              injectBackBtn();
              setTimeout(injectBackBtn, 800);
              setTimeout(injectBackBtn, 2000);
              try {
                var mo = new MutationObserver(function(){ injectBackBtn(); });
                mo.observe(document.documentElement, { childList:true, subtree:true });
              } catch (e) {}

              autoCsv();
              setTimeout(autoCsv, 1200);
              setTimeout(autoCsv, 3000);
            })();
        """.trimIndent()

        private val VATON_BALANCE_JS = """
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
              scrapeBalance();
              setTimeout(scrapeBalance, 800);
              setTimeout(scrapeBalance, 2000);
              setTimeout(scrapeBalance, 5000);
              try {
                var mo = new MutationObserver(function(){ scrapeBalance(); });
                mo.observe(document.documentElement, { childList:true, subtree:true, characterData:true });
              } catch (e) {}
              tick();
            })();
        """.trimIndent()

        private val CUSHIN_GIFT_JS = """
            (function(){
              if (window.__gwCushinGift) return;
              window.__gwCushinGift = true;
              var seen = {};
              var fetchTried = {};

              function toast(msg){
                try {
                  if (window.GiftWallet && window.GiftWallet.showToast) window.GiftWallet.showToast(String(msg));
                } catch (e) {}
              }

              function isGiftUrl(u){
                try {
                  var s = String(u || '');
                  if (s.indexOf('http') !== 0) return false;
                  var low = s.toLowerCase();
                  return low.indexOf('giftee') >= 0 || low.indexOf('g4b.') >= 0 ||
                    low.indexOf('eraberu') >= 0 || low.indexOf('giftee_boxes') >= 0 ||
                    low.indexOf('gift_url') >= 0;
                } catch (e) { return false; }
              }

              function alreadyCharged(url){
                try {
                  if (window.GiftWallet && window.GiftWallet.isGiftUrlCharged) {
                    return !!window.GiftWallet.isGiftUrlCharged(String(url));
                  }
                } catch (e) {}
                return !!seen[url];
              }

              function processLinks(links){
                if (!links || !links.length) return;
                for (var i = 0; i < links.length; i++) {
                  var url = String(links[i] || '').trim();
                  if (!isGiftUrl(url)) continue;
                  if (seen[url] || alreadyCharged(url)) continue;
                  seen[url] = true;
                  try {
                    if (window.GiftWallet && window.GiftWallet.copyText) {
                      window.GiftWallet.copyText(url);
                    } else if (navigator.clipboard && navigator.clipboard.writeText) {
                      navigator.clipboard.writeText(url);
                      toast('リンクをコピーしました');
                    }
                  } catch (e) {}
                  try {
                    if (window.GiftWallet && window.GiftWallet.chargeGiftUrl) {
                      window.GiftWallet.chargeGiftUrl(url);
                    }
                  } catch (e) {}
                }
              }

              function lookItem(item){
                try {
                  if (!item || typeof item !== 'object') return;
                  var status = String(item.status || item.state || item.result || item.status_group || '').toLowerCase();
                  var ok = status === 'success' || status === 'completed' || status.indexOf('success') >= 0;
                  var links = item.links || item.gift_links || item.giftLinks || item.gift_urls || [];
                  if (links && typeof links === 'string') links = [links];
                  if (ok && links && links.length) {
                    processLinks(links);
                    return;
                  }
                  if (ok && item.id && !fetchTried[item.id]) {
                    fetchTried[item.id] = 1;
                    // Prefer UI button; also try same-origin SPA API with logged-in token
                    setTimeout(function(){ clickFetchForId(item.id); }, 200);
                    setTimeout(function(){ apiFetchLinks(item.id); }, 600);
                  }
                } catch (e) {}
              }

              function scanPayload(data){
                try {
                  if (!data) return;
                  if (Array.isArray(data)) {
                    for (var i = 0; i < data.length; i++) {
                      lookItem(data[i]);
                      if (data[i] && data[i].item) lookItem(data[i].item);
                      if (data[i] && data[i].task_item) lookItem(data[i].task_item);
                    }
                    return;
                  }
                  if (typeof data !== 'object') return;
                  lookItem(data);
                  if (data.links && Array.isArray(data.links)) processLinks(data.links);
                  var keys = ['tasks','items','sessions','data','results','list','task_items','details'];
                  for (var k = 0; k < keys.length; k++) {
                    if (data[keys[k]]) scanPayload(data[keys[k]]);
                  }
                } catch (e) {}
              }

              function authHeaders(){
                var h = { 'Accept': 'application/json', 'Content-Type': 'application/json' };
                try {
                  var tok = localStorage.getItem('kantan-token') || '';
                  if (tok) h['Authorization'] = 'Bearer ' + tok;
                } catch (e) {}
                return h;
              }

              function apiFetchLinks(id){
                try {
                  if (!id) return;
                  fetch('/api/task-items/' + encodeURIComponent(id) + '/gift-links', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: authHeaders(),
                    body: '{}'
                  }).then(function(res){ return res.json().catch(function(){ return {}; }); })
                    .then(function(data){
                      if (data && data.links && data.links.length) processLinks(data.links);
                      scanPayload(data);
                    }).catch(function(){});
                } catch (e) {}
              }

              function clickFetchForId(id){
                try {
                  var btn = document.querySelector('button[data-action="fetch-links"][data-item-id="' + id + '"]');
                  if (btn) { btn.click(); return true; }
                } catch (e) {}
                return false;
              }

              function clickAllFetchButtons(){
                try {
                  var btns = document.querySelectorAll('button[data-action="fetch-links"]');
                  for (var i = 0; i < btns.length; i++) {
                    var id = btns[i].getAttribute('data-item-id') || ('x'+i);
                    if (fetchTried['dom:'+id]) continue;
                    fetchTried['dom:'+id] = 1;
                    try { btns[i].click(); } catch (e) {}
                  }
                } catch (e) {}
              }

              function scanDomLinks(){
                try {
                  // Success badges on QR cards → try fetch via nearby history buttons later
                  var cards = document.querySelectorAll('.qr-session-card, .history-link-list, [class*="history"]');
                  for (var i = 0; i < cards.length; i++) {
                    var text = (cards[i].innerText || '').toLowerCase();
                    if (text.indexOf('success') < 0 && text.indexOf('成功') < 0) continue;
                  }
                  var anchors = document.querySelectorAll('a.table-link, a[href*="giftee"], a[href*="g4b."], a[href*="eraberu"]');
                  var urls = [];
                  for (var j = 0; j < anchors.length; j++) {
                    var href = anchors[j].href || anchors[j].getAttribute('href') || '';
                    if (isGiftUrl(href)) urls.push(href);
                  }
                  // data-gift-link attributes
                  var boxes = document.querySelectorAll('[data-gift-link]');
                  for (var k = 0; k < boxes.length; k++) {
                    var g = boxes[k].getAttribute('data-gift-link') || '';
                    if (isGiftUrl(g)) urls.push(g);
                  }
                  if (urls.length) processLinks(urls);
                  clickAllFetchButtons();
                } catch (e) {}
              }

              function wrapFetch(){
                try {
                  if (window.__gwGiftFetchWrapped || typeof window.fetch !== 'function') return;
                  window.__gwGiftFetchWrapped = true;
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
                        if (u.indexOf('/api/') >= 0) {
                          res.clone().json().then(function(data){
                            scanPayload(data);
                            if (u.indexOf('gift-links') >= 0 && data && data.links) processLinks(data.links);
                          }).catch(function(){});
                        }
                      } catch (e) {}
                      return res;
                    });
                  };
                } catch (e) {}
              }

              wrapFetch();
              function tick(){
                scanDomLinks();
                setTimeout(tick, 2000);
              }
              try {
                var mo = new MutationObserver(function(){ scanDomLinks(); });
                mo.observe(document.documentElement, { childList:true, subtree:true });
              } catch (e) {}
              tick();
              setTimeout(scanDomLinks, 1000);
              setTimeout(scanDomLinks, 3000);
            })();
        """.trimIndent()

        private val CHARGE_AUTO_JS = """
            (function(){
              if (window.__gwChargeAuto) return;
              window.__gwChargeAuto = true;

              function toast(msg){
                try {
                  if (window.GiftWallet && window.GiftWallet.showToast) window.GiftWallet.showToast(String(msg));
                } catch (e) {}
              }

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

              function findBtnByTexts(texts){
                var nodes = document.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]');
                for (var t = 0; t < texts.length; t++) {
                  var want = texts[t];
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if (el.disabled) continue;
                    var s = (el.innerText || el.textContent || el.value || '').replace(/\s+/g,' ').trim();
                    if (s.indexOf(want) >= 0) return el;
                  }
                }
                return null;
              }

              function autoCheckTerms(){
                try {
                  var cbs = document.querySelectorAll('input[type="checkbox"]');
                  for (var i = 0; i < cbs.length; i++) {
                    var cb = cbs[i];
                    if (cb.checked) continue;
                    var label = '';
                    try {
                      if (cb.id) {
                        var lab = document.querySelector('label[for="'+cb.id+'"]');
                        if (lab) label = lab.innerText || '';
                      }
                      if (!label && cb.closest) {
                        var wrap = cb.closest('label, div, li, section, form') || cb.parentElement;
                        if (wrap) label = wrap.innerText || '';
                      }
                    } catch (e) {}
                    var blob = (label || '') + ' ' + (cb.getAttribute('data-testid') || '');
                    if (blob.indexOf('利用規約') >= 0 || blob.indexOf('同意') >= 0 ||
                        blob.indexOf('terms') >= 0 || blob.indexOf('privacy') >= 0 ||
                        blob.indexOf('個人情報') >= 0 || true) {
                      // On charge/LP pages, checking visible unchecked boxes near agree text is intended.
                      // Prefer boxes whose surrounding text mentions terms/agree; otherwise skip bare unrelated boxes.
                      var near = blob.indexOf('利用規約') >= 0 || blob.indexOf('同意') >= 0 ||
                        blob.indexOf('terms') >= 0 || blob.indexOf('プライバシー') >= 0 ||
                        blob.indexOf('個人情報') >= 0 || (cb.getAttribute('data-testid') || '').indexOf('terms') >= 0;
                      if (!near) continue;
                      try {
                        cb.click();
                        if (!cb.checked) {
                          cb.checked = true;
                          cb.dispatchEvent(new Event('change', { bubbles:true }));
                          cb.dispatchEvent(new Event('input', { bubbles:true }));
                        }
                      } catch (e) {}
                    }
                  }
                } catch (e) {}
              }

              function looksLoggedOut(){
                try {
                  var path = (location.pathname || '').toLowerCase();
                  if (path.indexOf('login') >= 0 || path.indexOf('sign_in') >= 0 || path.indexOf('signin') >= 0 || path.indexOf('/sign-in') >= 0) return true;
                  var t = (document.body && (document.body.innerText || '')) || '';
                  if (document.querySelector('input[type="password"]') && (t.indexOf('ログイン') >= 0 || t.indexOf('メール') >= 0)) return true;
                  // Vaton LP may show 新規登録・ログインする as primary CTA when logged out
                  if (t.indexOf('新規登録・ログインする') >= 0 && t.indexOf('ギフトをポイントに移行する') < 0 && t.indexOf('ポイントをチャージする') < 0) {
                    // still allow if convert button exists elsewhere
                    if (!findBtnByTexts(['ギフトをポイントに移行する','ポイントをチャージする','ポイントチャージ','ポイントに移行する'])) return true;
                  }
                } catch (e) {}
                return false;
              }

              function isCompletion(){
                try {
                  var path = (location.pathname || '');
                  if (path.indexOf('/point/charge/completion') >= 0) return true;
                  if (path.indexOf('/serial_code/completion') >= 0) return true;
                  var t = (document.body && (document.body.innerText || '')) || '';
                  if (t.indexOf('ギフトを移行しました') >= 0) return true;
                  if (t.indexOf('チャージが完了') >= 0 || t.indexOf('ポイントチャージが完了') >= 0) return true;
                } catch (e) {}
                return false;
              }

              var completed = false;
              var loginNotified = false;
              var lastTapAt = 0;

              function onComplete(){
                if (completed) return;
                completed = true;
                try { if (window.__gwScrapeBalance) window.__gwScrapeBalance(); } catch (e) {}
                try {
                  if (window.GiftWallet && window.GiftWallet.onChargeComplete) {
                    window.GiftWallet.onChargeComplete();
                  }
                } catch (e) {}
              }

              function autoTap(){
                try {
                  if (completed) return;
                  if (isCompletion()) { onComplete(); return; }

                  // Only auto-drive on gift/vaton charge flows
                  var host = (location.hostname || '').toLowerCase();
                  var path = (location.pathname || '');
                  var onVaton = host.indexOf('wallet.vaton.jp') >= 0;
                  var onGiftee = host.indexOf('giftee') >= 0 || host.indexOf('eraberu') >= 0;
                  var chargePath = path.indexOf('/lp/convert_to_point') >= 0 ||
                    path.indexOf('/lp/charge_serial_code') >= 0 ||
                    path.indexOf('/lp/merge_to_point') >= 0 ||
                    path.indexOf('/point/charge') >= 0 ||
                    path.indexOf('/lp/') >= 0 ||
                    path.indexOf('giftee_boxes') >= 0 ||
                    path.indexOf('/gift') >= 0;

                  if (!onVaton && !onGiftee) return;
                  // Always show back button on these pages
                  injectBackBtn();

                  if (!(chargePath || onGiftee)) return;

                  if (onVaton && looksLoggedOut()) {
                    if (!loginNotified) {
                      loginNotified = true;
                      try {
                        if (window.GiftWallet && window.GiftWallet.notifyChargeLoginRequired) {
                          window.GiftWallet.notifyChargeLoginRequired();
                        } else {
                          toast('ギフトウォレットにログインしてください');
                        }
                      } catch (e) {}
                    }
                    return;
                  }

                  autoCheckTerms();
                  var now = Date.now();
                  if (now - lastTapAt < 1200) return;
                  // Prefer convert-to-point over PayPay etc.
                  var btn = findBtnByTexts([
                    'ギフトをポイントに移行する',
                    'ポイントに移行する',
                    'ポイントをチャージする',
                    'ポイントチャージ',
                    'ギフトをえらぶ'
                  ]);
                  if (btn) {
                    lastTapAt = now;
                    try { btn.click(); } catch (e) {}
                  }
                } catch (e) {}
              }

              function tick(){
                autoTap();
                setTimeout(tick, 900);
              }
              injectBackBtn();
              setTimeout(injectBackBtn, 800);
              setTimeout(injectBackBtn, 2000);
              try {
                var mo = new MutationObserver(function(){ injectBackBtn(); autoTap(); });
                mo.observe(document.documentElement, { childList:true, subtree:true });
              } catch (e) {}
              tick();
            })();
        """.trimIndent()
    }
}
