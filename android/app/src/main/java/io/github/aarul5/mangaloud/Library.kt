package io.github.aarul5.mangaloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved site or page. */
data class Bookmark(val title: String, val url: String)

/** A page that was opened, newest first in [Library.history]. */
data class Visit(val title: String, val url: String, val time: Long)

/** A series and the last chapter of it that was opened, for "Continue reading". */
data class ContinueItem(val series: String, val chapter: String, val url: String, val time: Long)

/**
 * Bookmarks, history and site icons, kept on the phone. Small enough for SharedPreferences;
 * the icons are PNG files named after each site.
 */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val iconDir = File(context.filesDir, "site-icons")
    private val iconCache = HashMap<String, Bitmap?>()

    var bookmarks: List<Bookmark>
        get() = read("bookmarks") { Bookmark(it.optString("title"), it.optString("url")) }
        private set(value) = write("bookmarks", value) { JSONObject().put("title", it.title).put("url", it.url) }

    var history: List<Visit>
        get() = read("history") { Visit(it.optString("title"), it.optString("url"), it.optLong("time")) }
        private set(value) = write("history", value.take(MAX_HISTORY)) {
            JSONObject().put("title", it.title).put("url", it.url).put("time", it.time)
        }

    fun isBookmarked(url: String) = bookmarks.any { same(it.url, url) }

    /** Adds the page, or takes it off when it is already saved. True when it is now saved. */
    fun toggleBookmark(title: String, url: String): Boolean {
        val saved = bookmarks
        if (saved.any { same(it.url, url) }) {
            bookmarks = saved.filterNot { same(it.url, url) }
            return false
        }
        bookmarks = saved + Bookmark(cleanTitle(title).ifBlank { hostOf(url) }, url)
        return true
    }

    fun renameBookmark(url: String, title: String) {
        bookmarks = bookmarks.map { if (same(it.url, url)) it.copy(title = title.trim().ifEmpty { it.title }) else it }
    }

    fun removeBookmark(url: String) {
        bookmarks = bookmarks.filterNot { same(it.url, url) }
    }

    /** Puts the page at the top of the history, replacing an older visit to the same address. */
    fun addVisit(title: String, url: String) {
        history = listOf(Visit(title, url, System.currentTimeMillis())) + history.filterNot { same(it.url, url) }
    }

    /** The page title often arrives after the page: fills it in on the latest visit. */
    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        val visits = history
        val first = visits.firstOrNull() ?: return
        if (same(first.url, url) && first.title != title) history = listOf(first.copy(title = title)) + visits.drop(1)
    }

    fun removeVisit(url: String) {
        history = history.filterNot { same(it.url, url) }
    }

    fun clearHistory() {
        history = emptyList()
    }

    /** The last chapter opened of each series, newest first. */
    fun continueReading(limit: Int = 12): List<ContinueItem> {
        val seen = HashSet<String>()
        val items = ArrayList<ContinueItem>()
        for (visit in history) {
            if (!SeriesCast.looksLikeChapter(visit.url)) continue
            if (!seen.add(SeriesCast.seriesKeyOf(visit.url))) continue
            items += ContinueItem(seriesName(visit), chapterLabel(visit), visit.url, visit.time)
            if (items.size >= limit) break
        }
        return items
    }

    /** The site's icon, saved when its pages were opened, or null. */
    fun icon(url: String): Bitmap? {
        val host = hostOf(url)
        return iconCache.getOrPut(host) {
            File(iconDir, "$host.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
    }

    fun saveIcon(url: String, icon: Bitmap) {
        val host = hostOf(url)
        if (host.isEmpty()) return
        iconDir.mkdirs()
        runCatching {
            File(iconDir, "$host.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            iconCache[host] = icon
        }
    }

    private fun <T> read(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun <T> write(key: String, items: List<T>, toJson: (T) -> JSONObject) {
        prefs.edit().putString(key, JSONArray(items.map(toJson)).toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY = 300
        private val CHAPTER = Regex("""(?i)\b(chapter|chap|ch|episode|ep)\.?[\s_-]*(\d+(?:\.\d+)?)""")
        private val TITLE_TAIL = Regex("""\s*[-–—|:]\s*(read|online|manga|manhwa|webtoon).*$""", RegexOption.IGNORE_CASE)

        /** "Iron Smith Chapter 228" from "Iron Smith Chapter 228 – Read Iron Smith Manga Online". */
        fun cleanTitle(title: String): String = title.replace(TITLE_TAIL, "").trim().ifEmpty { title.trim() }

        fun hostOf(url: String): String = Uri.parse(url).host?.lowercase()?.removePrefix("www.").orEmpty()

        private fun same(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

        /** "Ch. 146" from the title or the address, or the title itself when neither has a number. */
        internal fun chapterLabel(title: String, url: String): String {
            val match = CHAPTER.find(title) ?: CHAPTER.find(url.replace('-', ' ').replace('_', ' '))
            return match?.let { "Ch. ${it.groupValues[2]}" } ?: title
        }

        /** "Iron Smith" from "Iron Smith Chapter 146 – Read Iron Smith Manga Online". */
        internal fun seriesName(title: String, url: String): String {
            val before = CHAPTER.find(title)?.let { title.substring(0, it.range.first) } ?: title.replace(TITLE_TAIL, "")
            val name = before.trim().trimEnd('-', '–', '—', '|', ':', ',').trim()
            if (name.isNotEmpty()) return name
            // No usable title: the series part of the address, made readable.
            val key = SeriesCast.seriesKeyOf(url)
            return key.substringAfterLast('/').replace('-', ' ').replace('_', ' ')
                .replaceFirstChar { it.uppercase() }.ifEmpty { hostOf(url) }
        }

        private fun chapterLabel(visit: Visit) = chapterLabel(visit.title, visit.url)
        private fun seriesName(visit: Visit) = seriesName(visit.title, visit.url)
    }
}
