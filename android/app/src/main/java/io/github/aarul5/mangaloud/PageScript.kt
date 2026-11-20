package io.github.aarul5.mangaloud

import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

data class PageImage(val index: Int, val src: String)

/**
 * The chapter's images, where on them the screen currently is, and the next chapter's address
 * when the page links to it.
 */
data class Chapter(val images: List<PageImage>, val startIndex: Int, val startFrac: Double, val next: String? = null) {
    companion object {
        val EMPTY = Chapter(emptyList(), 0, 0.0)
    }
}

/** Kotlin side of assets/reader.js. Must be called on the main thread. */
class PageScript(private val webView: WebView) {
    private val readerJs = webView.context.assets.open("reader.js").bufferedReader().use { it.readText() }

    fun collectChapter(callback: (Chapter) -> Unit) {
        webView.evaluateJavascript(readerJs) {
            webView.evaluateJavascript("window.__mr.collect()") { raw ->
                callback(parseChapter(raw))
            }
        }
    }

    /**
     * Highlights the bubble and glides so its panel (chapter positions [from, to]) is in view.
     * The callback gets the glide time in ms, 0 when the panel was on screen already.
     */
    fun show(bubble: Bubble, panel: DoubleArray, callback: (Long) -> Unit) {
        val js = "window.__mr ? window.__mr.show(${bubble.imageIndex}, ${bubble.x0}, ${bubble.y0}, " +
            "${bubble.x1}, ${bubble.y1}, ${panel[0]}, ${panel[1]}) : 0"
        webView.evaluateJavascript(js) { raw -> callback(raw?.toDoubleOrNull()?.toLong() ?: 0L) }
    }

    /** Glides to one part of a panel with no bubbles. The callback gets the glide time and the number of parts. */
    fun showArt(panel: DoubleArray, part: Int, callback: (glideMs: Long, parts: Int) -> Unit) {
        val js = "window.__mr ? window.__mr.showArt(${panel[0]}, ${panel[1]}, $part) : null"
        webView.evaluateJavascript(js) { raw ->
            val json = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            val result = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            callback(result?.optLong("ms") ?: 0L, result?.optInt("parts", 1) ?: 1)
        }
    }

    /** Where the top of the screen is in the chapter: image index plus fraction down it. */
    fun screenPosition(callback: (ChapterPos?) -> Unit) {
        webView.evaluateJavascript("window.__mr ? window.__mr.screenPosition() : null") { raw ->
            val json = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            val start = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            callback(start?.let { it.getInt("index") + it.getDouble("frac") })
        }
    }

    fun stopGlide() {
        webView.evaluateJavascript("window.__mr && window.__mr.stopGlide()", null)
    }

    fun isOnScreen(bubble: Bubble, callback: (Boolean) -> Unit) {
        val js = "window.__mr ? window.__mr.isOnScreen(${bubble.imageIndex}, ${bubble.y0}, ${bubble.y1}) : false"
        webView.evaluateJavascript(js) { raw -> callback(raw == "true") }
    }

    fun clear() {
        webView.evaluateJavascript("window.__mr && window.__mr.clear()", null)
    }

    private fun parseChapter(raw: String?): Chapter {
        if (raw == null || raw == "null") return Chapter.EMPTY
        // evaluateJavascript hands back the returned string JSON-encoded a second time.
        val json = JSONTokener(raw).nextValue() as? String ?: return Chapter.EMPTY
        val chapter = JSONObject(json)
        val array = chapter.getJSONArray("images")
        val images = (0 until array.length()).map {
            val item = array.getJSONObject(it)
            PageImage(item.getInt("index"), item.getString("src"))
        }
        val start = chapter.getJSONObject("start")
        val next = if (chapter.isNull("next")) null else chapter.optString("next").takeIf { it.startsWith("http") }
        return Chapter(images, start.getInt("index"), start.getDouble("frac"), next)
    }
}
