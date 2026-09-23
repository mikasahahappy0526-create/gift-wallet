package net.giftwallet.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@registerForActivityResult
            val uris: Array<Uri>? =
                if (result.resultCode == Activity.RESULT_OK) {
                    val data = result.data
                    val clip = data?.clipData
                    when {
                        clip != null && clip.itemCount > 0 ->
                            Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                        data?.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                } else {
                    null
                }
            cb.onReceiveValue(uris)
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

        // CSV: manual only on vaton point_logs (ギフト opens history; user taps download). No auto sync.
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = try {
                    fileChooserParams?.createIntent()
                } catch (e: Exception) {
                    null
                } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                intent.putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "text/csv",
                        "text/comma-separated-values",
                        "text/plain",
                        "application/csv",
                        "application/vnd.ms-excel",
                        "*/*"
                    )
                )
                return try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "CSVを選択"))
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "ファイル選択を開けませんでした", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
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

    private fun startCsvSync() {
        // no-op: auto CSV sync disabled (manual download on point_logs only)
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
                // Auto-charge of gift links into wallet.vaton.jp removed (too heavy).
            }
            url.contains("wallet.vaton.jp") -> {
                view.evaluateJavascript(VATON_CSV_JS, null)
                view.evaluateJavascript(VATON_BALANCE_JS, null)
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

    // Auto-charge queue disabled — stubs kept so any leftover bridge calls are harmless.
    private fun enqueueChargeUrl(raw: String) {
        // no-op: do not open giftee URLs or auto-tap charge buttons
    }

    private fun pumpChargeQueue() {
        chargeQueue.clear()
        chargeBusy = false
        currentChargeUrl = null
    }

    private fun finishCurrentCharge(success: Boolean) {
        chargeBusy = false
        currentChargeUrl = null
        chargeQueue.clear()
    }

    private fun abortChargeForLogin() {
        chargeQueue.clear()
        chargeBusy = false
        currentChargeUrl = null
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
        fun notifyCsvSyncFailed(message: String) {
            runOnUiThread {
                csvSyncInFlight = false
                val msg = message.ifBlank { "CSVの自動取得に失敗しました" }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun startGiftWalletCsvSync() {
            // no-op stub — do not navigate to #gw-auto-csv / auto modal
        }

        @JavascriptInterface
        fun openCushinQuick() {
            runOnUiThread {
                csvSyncInFlight = false
                pendingCsvText = null
                webView.loadUrl(CUSHIN_QUICK_URL)
            }
        }

        @JavascriptInterface
        fun openAppUpdate() {
            runOnUiThread {
                try {
                    // 直APKではなくインストール案内ページへ（説明→ダウンロード）
                    val installUrl = "https://gift-wallet.pages.dev/install"
                    webView.loadUrl(installUrl)
                    Toast.makeText(
                        this@MainActivity,
                        "インストール案内を開きます",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "更新ページを開けませんでした",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
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
            // no-op: auto-charge removed
        }

        @JavascriptInterface
        fun onChargeComplete() {
            // no-op
        }

        @JavascriptInterface
        fun notifyChargeLoginRequired() {
            // no-op
        }

        @JavascriptInterface
        fun isGiftUrlCharged(url: String): Boolean {
            return true
        }

        @JavascriptInterface
        fun clearChargeQueue() {
            runOnUiThread { chargeQueue.clear() }
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
        private const val CUSHIN_QUICK_URL =
            "https://cushintools.net/dashboard/quick-withdraw#auto-new-session"
        private const val FIXED_CSV_NAME = "ポイント履歴_今月.csv"

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
        """.trimIndent()

        private val HELPER_JS_FULL = HELPER_JS_BASE + "\n" + HELPER_JS_QUICK

                private val HOME_INJECT_JS = """
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
                    /\.csv(${'$'}|\?)/i.test(name) || type === '' || type === 'application/octet-stream';
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
        """.trimIndent()

        // Removed: Cushin → wallet.vaton.jp auto-charge (was too heavy).
        private val CUSHIN_GIFT_JS = """(function(){ /* auto-charge removed */ })();""".trimIndent()

        // Removed: giftee/vaton charge auto-tap.
        private val CHARGE_AUTO_JS = """(function(){ /* charge auto-tap removed */ })();""".trimIndent()
    }
}
