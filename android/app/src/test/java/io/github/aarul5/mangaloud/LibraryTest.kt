package io.github.aarul5.mangaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    @Test
    fun chapterAndSeriesComeFromThePageTitle() {
        val title = "Iron Smith Chapter 146 – Read Iron Smith Manga Online"
        val url = "https://w12.example-manga.org/iron-smith-chapter-146/"
        assertEquals("Ch. 146", Library.chapterLabel(title, url))
        assertEquals("Iron Smith", Library.seriesName(title, url))
    }

    @Test
    fun chapterFallsBackToTheAddress() {
        assertEquals("Ch. 12.5", Library.chapterLabel("Solo Hunter", "https://site.com/manga/solo-hunter/chapter-12.5"))
    }

    @Test
    fun bookmarkTitlesLoseTheSiteTail() {
        assertEquals("Iron Smith Chapter 228", Library.cleanTitle("Iron Smith Chapter 228 – Read Iron Smith Manga Online"))
        assertEquals("Webtoon", Library.cleanTitle("Webtoon"))
    }

    @Test
    fun aSiteTailIsDroppedFromTheSeriesName() {
        assertEquals("Solo Hunter", Library.seriesName("Solo Hunter | Read manga online", "https://site.com/solo/12"))
    }
}
