package net.giftwallet.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        setupWebView()
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
        }

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
            }
        }
    }

    private fun maybeInjectHelpers(view: WebView?, url: String?) {
        if (view == null || url.isNullOrBlank()) return
        if (!url.contains("cushintools.net")) return
        if (!url.contains("/dashboard/quick-withdraw") && !url.contains("auto-new-session")) return
        view.evaluateJavascript(HELPER_JS, null)
    }

    companion object {
        private const val HOME_URL = "https://gift-wallet.pages.dev/"

        private val HELPER_JS = """
            (function(){
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
    }
}
