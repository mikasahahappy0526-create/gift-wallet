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
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
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
                maybeInjectAutoTap(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                maybeInjectAutoTap(view, url)
            }
        }
    }

    private fun maybeInjectAutoTap(view: WebView?, url: String?) {
        if (view == null || url.isNullOrBlank()) return
        if (!url.contains("cushintools.net")) return
        if (!url.contains("/dashboard/quick-withdraw") && !url.contains("auto-new-session")) return
        view.evaluateJavascript(AUTO_TAP_JS, null)
    }

    companion object {
        private const val HOME_URL = "https://gift-wallet.pages.dev/"

        private val AUTO_TAP_JS = """
            (function(){
              if (window.__gwAutoTapStarted) return;
              window.__gwAutoTapStarted = true;
              var maxMs = 45000;
              var started = Date.now();
              function clearHash(){
                try {
                  if (location.hash && location.hash.indexOf('auto-new-session') >= 0) {
                    history.replaceState(null, '', location.pathname + location.search);
                  }
                } catch (e) {}
              }
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
            })();
        """.trimIndent()
    }
}
