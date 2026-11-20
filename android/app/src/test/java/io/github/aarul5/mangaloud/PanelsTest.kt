package io.github.aarul5.mangaloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val width = 100

    /** A strip 100 px wide: rows in a gutter are white, rows in a panel are a busy pattern. */
    private fun strip(height: Int, vararg gutterRows: IntRange): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val inGutter = gutterRows.any { y in it }
            pixels[y * width + x] = if (inGutter) white else if ((x / 7 + y / 5) % 2 == 0) 0xFF202020.toInt() else 0xFFC08040.toInt()
        }
        return pixels
    }

    private fun assertRanges(expected: List<DoubleArray>, actual: List<DoubleArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a, 1e-9) }
    }

    @Test
    fun findsTheWhiteBandBetweenTwoPanels() {
        val gutters = PanelFinder.gutters(strip(400, 190 until 210), width, 400)
        assertRanges(listOf(doubleArrayOf(190 / 400.0, 210 / 400.0)), gutters)
    }

    @Test
    fun aThinLineInsideAPanelIsNotAGutter() {
        assertTrue(PanelFinder.gutters(strip(400, 200 until 201), width, 400).isEmpty())
    }

    @Test
    fun aSliverBetweenTwoGuttersFoldsIntoOne() {
        val gutters = PanelFinder.gutters(strip(400, 150 until 160, 165 until 175), width, 400)
        assertRanges(listOf(doubleArrayOf(150 / 400.0, 175 / 400.0)), gutters)
    }

    @Test
    fun aBubbleIsGivenItsWholePanel() {
        val map = PanelMap()
        map.set(0, listOf(doubleArrayOf(0.30, 0.35), doubleArrayOf(0.70, 0.75)))
        assertArrayEquals(doubleArrayOf(0.35, 0.70), map.panelAround(0.40, 0.45), 1e-9)
    }

    @Test
    fun aPanelCarriesOnIntoTheNextImage() {
        val map = PanelMap()
        map.set(0, listOf(doubleArrayOf(0.50, 0.55)))
        map.set(1, listOf(doubleArrayOf(0.20, 0.25)))
        assertArrayEquals(doubleArrayOf(0.55, 1.20), map.panelAround(0.80, 0.85), 1e-9)
    }

    @Test
    fun aBubbleOnAGutterIsShownWithThePictureAbove() {
        val map = PanelMap()
        map.set(0, listOf(doubleArrayOf(0.40, 0.60)))
        assertArrayEquals(doubleArrayOf(0.0, 0.55), map.panelAround(0.45, 0.55), 1e-9)
    }

    @Test
    fun aBubbleAloneInABandIsShownWithThePictureAbove() {
        val map = PanelMap()
        // Picture 0.05..0.40, a black band with the bubble (0.45..0.55) between two gutters.
        map.set(0, listOf(doubleArrayOf(0.0, 0.05), doubleArrayOf(0.40, 0.44), doubleArrayOf(0.56, 0.70)))
        assertArrayEquals(doubleArrayOf(0.05, 0.55), map.panelAround(0.45, 0.55), 1e-9)
    }

    @Test
    fun aBubbleOnAGutterWithNothingAboveGetsJustItself() {
        val map = PanelMap()
        map.set(0, listOf(doubleArrayOf(0.0, 0.60)))
        assertArrayEquals(doubleArrayOf(0.45, 0.55), map.panelAround(0.45, 0.55), 1e-9)
    }

    @Test
    fun artPanelsBetweenTwoBubblesAreListed() {
        val map = PanelMap()
        // Image 0: panels 0..0.3 (bubble), 0.35..0.6 (art), 0.65..1 carrying on to image 1 (art).
        map.set(0, listOf(doubleArrayOf(0.30, 0.35), doubleArrayOf(0.60, 0.65)))
        // Image 1: gutter at 0.4, then the next bubble's panel.
        map.set(1, listOf(doubleArrayOf(0.40, 0.45)))
        val after = map.panelAround(0.10, 0.20)[1]
        val before = map.panelAround(1.60, 1.70)[0]
        val art = map.artBetween(after, before)
        assertRanges(listOf(doubleArrayOf(0.35, 0.60), doubleArrayOf(0.65, 1.40)), art)
    }

    @Test
    fun bubblesInOnePanelHaveNoArtBetween() {
        val map = PanelMap()
        map.set(0, listOf(doubleArrayOf(0.50, 0.55)))
        val after = map.panelAround(0.10, 0.15)[1]
        val before = map.panelAround(0.30, 0.35)[0]
        assertTrue(map.artBetween(after, before).isEmpty())
    }

    @Test
    fun anImageNotLookedAtCountsAsOnePanel() {
        val map = PanelMap()
        map.set(0, listOf(doubleArrayOf(0.90, 1.0)))
        map.set(2, listOf(doubleArrayOf(0.0, 0.10)))
        val art = map.artBetween(0.90, 2.10)
        assertRanges(listOf(doubleArrayOf(1.0, 2.0)), art)
    }
}
