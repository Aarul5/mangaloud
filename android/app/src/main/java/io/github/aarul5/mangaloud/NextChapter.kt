package io.github.aarul5.mangaloud

import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the next chapter's images without opening it, from the chapter page's HTML. Works for
 * sites that put the image addresses in the page (most manga sites do, often as data-src for
 * lazy loading). Sites that add their images by script give no list, and nothing is read early.
 */
object NextChapter {
    private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    // Lazy-load attributes first, as in reader.js: src is often only a placeholder.
    private val SOURCE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-srcset", "src")

    /** Downloads the chapter page with the browser's cookies, like the images are. */
    fun fetchHtml(url: String, referer: String, userAgent: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "text/html")
        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
            return String(connection.inputStream.use { it.readAtMost(MAX_HTML_BYTES) }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The chapter images in page order: every <img> on the same site as [imageHost], the host
     * this chapter's own images came from, which leaves out logos, ads and covers. Numbered
     * image servers (cdn1., cdn2.) count as the same site.
     */
    fun imagesOf(html: String, pageUrl: String, imageHost: String): List<String> {
        val site = siteOf(imageHost)
        val base = URL(pageUrl)
        val found = LinkedHashSet<String>()
        for (tag in IMG_TAG.findAll(html)) {
            val source = SOURCE_ATTRS.firstNotNullOfOrNull { attributeOf(tag.value, it) } ?: continue
            val first = source.trim().split(Regex("""\s+""")).first().replace("&amp;", "&")
            val absolute = runCatching { URL(base, first) }.getOrNull() ?: continue
            if (absolute.protocol != "http" && absolute.protocol != "https") continue
            if (siteOf(absolute.host) != site) continue
            found += absolute.toString()
        }
        return found.toList()
    }

    /** The host most of this chapter's images come from, or null for a chapter of data: images. */
    fun hostOf(images: List<PageImage>): String? =
        images.mapNotNull { runCatching { URL(it.src).host }.getOrNull() }
            .groupingBy { it.lowercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

    private fun siteOf(host: String) = host.lowercase().split('.').takeLast(2).joinToString(".")

    private fun attributeOf(tag: String, name: String): String? {
        val match = Regex("""\s${Regex.escape(name)}\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
            .find(tag) ?: return null
        val value = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: return null
        // A placeholder (a data: pixel or an empty value) is not the image.
        return value.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    /** Reads at most [limit] bytes, so a huge page cannot use up the phone's memory. */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (read < 0) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }
}
