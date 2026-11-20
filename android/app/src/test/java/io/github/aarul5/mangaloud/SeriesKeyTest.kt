package io.github.aarul5.mangaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesKeyTest {
    private fun key(host: String, path: String) =
        SeriesCast.seriesKeyOf(host, path.split('/').filter { it.isNotEmpty() })

    @Test
    fun versionedChaptersShareOneSeries() {
        assertEquals("w12.example-manga.org/iron-smith", key("w12.example-manga.org", "/iron-smith-chapter-31-v1/"))
        assertEquals("w12.example-manga.org/iron-smith", key("w12.example-manga.org", "/iron-smith-chapter-32/"))
    }

    @Test
    fun chapterFolderAfterTheSeries() {
        assertEquals("site.com/manga/solo-hunter", key("site.com", "/manga/solo-hunter/chapter-12"))
        assertEquals("site.com/manga/solo-hunter", key("site.com", "/manga/solo-hunter/chapter-12-v2"))
        assertEquals("site.com/manga/solo-hunter", key("site.com", "/manga/solo-hunter/ch-5.5-raw.html"))
        assertEquals("site.com/manga/solo-hunter", key("site.com", "/manga/solo-hunter/13"))
    }

    @Test
    fun seriesNameIsLeftAlone() {
        assertEquals("site.com/manga/chapter-black", key("site.com", "/manga/chapter-black/chapter-3"))
    }

    private fun chapter(host: String, path: String) =
        SeriesCast.looksLikeChapter(host, path.split('/').filter { it.isNotEmpty() })

    @Test
    fun chapterPagesAreToldFromListPages() {
        assertTrue(chapter("w12.example-manga.org", "/iron-smith-chapter-31-v1/"))
        assertTrue(chapter("site.com", "/manga/solo-hunter/chapter-12"))
        assertTrue(chapter("site.com", "/manga/solo-hunter/13"))
        assertFalse(chapter("site.com", "/manga/solo-hunter"))
        assertFalse(chapter("site.com", "/manga/chapter-black"))
        assertFalse(chapter("site.com", "/"))
    }
}
