package io.github.aarul5.mangaloud

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

interface BrowserEvents {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onProgress(percent: Int)
    fun onBlockedJump(url: String)

    /** The page's title, which often arrives after the page has loaded. */
    fun onTitle(url: String, title: String) {}

    /** The site's icon, for its bookmark and history rows. */
    fun onIcon(url: String, icon: android.graphics.Bitmap) {}
}

class ReaderWebViewClient(
    private val adBlocker: AdBlocker,
    private val events: BrowserEvents,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host ?: return null
        if (!adBlocker.isBlocked(host)) return null
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * Ad scripts on these sites hijack taps to jump to other sites. Same-site navigation
     * (next chapter, chapter list) goes through; anything else is held for the user to allow.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val target = request.url
        if (target.scheme != "http" && target.scheme != "https") return true
        val targetHost = target.host ?: return true
        if (adBlocker.isBlocked(targetHost)) return true

        val currentHost = view.url?.let { Uri.parse(it).host } ?: return false
        if (siteOf(currentHost) == siteOf(targetHost)) return false

        events.onBlockedJump(target.toString())
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        events.onPageStarted(url)
    }

    /** Runs as soon as the new page is drawn, so the guard is there before most ad scripts. */
    override fun onPageCommitVisible(view: WebView, url: String) {
        injectAdDefences(view)
    }

    override fun onPageFinished(view: WebView, url: String) {
        injectAdDefences(view)
        events.onPageFinished(url)
    }

    private fun injectAdDefences(view: WebView) {
        injectCosmeticCss(view)
        view.evaluateJavascript(adBlocker.overlayGuardJs, null)
    }

    private fun injectCosmeticCss(view: WebView) {
        val css = org.json.JSONObject.quote(adBlocker.cosmeticCss)
        view.evaluateJavascript(
            """
            (function () {
              if (document.getElementById('__mr_cosmetic')) return;
              var style = document.createElement('style');
              style.id = '__mr_cosmetic';
              style.textContent = $css;
              (document.head || document.documentElement).appendChild(style);
            })();
            """.trimIndent(),
            null,
        )
    }

    companion object {
        /** Rough registrable domain: "w12.example-manga.org" -> "example-manga.org", "a.b.co.uk" -> "b.co.uk". */
        fun siteOf(host: String): String {
            val parts = host.lowercase().split('.')
            if (parts.size <= 2) return host.lowercase()
            val secondLast = parts[parts.size - 2]
            val last = parts.last()
            val keep = if (last.length == 2 && secondLast.length <= 3) 3 else 2
            return parts.takeLast(keep).joinToString(".")
        }
    }
}

class ReaderChromeClient(private val events: BrowserEvents) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        val url = view.url ?: return
        if (!title.isNullOrBlank()) events.onTitle(url, title)
    }

    override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
        val url = view.url ?: return
        if (icon != null) events.onIcon(url, icon)
    }

    /** Blocks every pop-up window. The site's own links still open in the same tab. */
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        false

    /** Swallows alert() spam. */
    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }
}
