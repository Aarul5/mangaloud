package io.github.aarul5.mangaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boxes come from the OCR cache of the chapter where a bubble was read twice. Slice 0 covers
 * 0..0.3 of the image and slice 1 covers 0.225..0.525, so they share 0.225..0.3.
 */
class SeamTwinsTest {
    private fun entry(slice: Int, order: Int, text: String, x0: Double, x1: Double, sliceY0: Double, sliceY1: Double): Entry {
        val top = slice * 0.225
        val bottom = top + 0.3
        val bubble = Bubble(0, text, x0, top + sliceY0 * 0.3, x1, top + sliceY1 * 0.3)
        return Entry(slice * 1_000L + order, bubble, top, bottom)
    }

    private fun read(vararg entries: Entry): List<String> {
        val store = ChapterStore()
        store.add(entries.toList(), 0.0, 1.0)
        val texts = mutableListOf<String>()
        var next = store.firstAfter(-1L)
        while (next != null) {
            texts += next.bubble.text
            next = store.firstAfter(next.key)
        }
        return texts
    }

    @Test
    fun bubbleCutBetweenTwoImagesIsOneBubble() {
        val whole = "In that case, I have no real reason to refuse. I'm going to allow you to enter the dungeon."
        // Image 4 ends with the bubble's top; image 5 starts with its last line.
        val upper = Entry(4_000_000L + 3_000L, Bubble(4, whole, 0.1, 0.9, 0.62, 1.0, speaker = "woman"), 0.7, 1.0)
        val lower = Entry(5_000_000L, Bubble(5, "the dungeon.", 0.12, 0.0, 0.6, 0.05), 0.0, 0.3)
        val store = ChapterStore()
        store.add(listOf(upper, lower), 4.7, 5.3)
        val only = store.firstAfter(-1L)!!
        assertEquals(whole, only.bubble.text)
        assertEquals("woman", only.bubble.speaker)
        assertEquals(4, only.bubble.imageIndex)
        assertEquals(0.05, only.bubble.spill, 1e-9)
        assertEquals(5.05, only.bottom, 1e-9)
        assertEquals(null, store.firstAfter(only.key))
    }

    @Test
    fun bubblesAtTwoImageEdgesWithOtherTextStayApart() {
        val upper = Entry(4_003_000L, Bubble(4, "We must leave now.", 0.1, 0.9, 0.6, 1.0), 0.7, 1.0)
        val lower = Entry(5_000_000L, Bubble(5, "Where is everyone?", 0.12, 0.0, 0.6, 0.05), 0.0, 0.3)
        val store = ChapterStore()
        store.add(listOf(upper, lower), 4.7, 5.3)
        assertEquals("Where is everyone?", store.firstAfter(store.firstAfter(-1L)!!.key)?.bubble?.text)
    }

    @Test
    fun cutBubbleIsReadOnceWithTheWholeLine() {
        val whole = "Dexterity isn't the only important stat. I need to have high strength and stamina as well!"
        val texts = read(
            entry(0, 0, whole, 0.29, 0.97, 0.6, 0.99),
            entry(1, 0, "The only important stat. I need to have high strength and stamina as well!", 0.29, 0.97, 0.0, 0.25),
            entry(1, 1, "Phew... Should I rest a bit?", 0.17, 0.55, 0.64, 0.87),
        )
        assertEquals(listOf(whole, "Phew... Should I rest a bit?"), texts)
    }

    @Test
    fun cutLineInTheUpperSliceLosesToTheWholeLineBelow() {
        val whole = "There are a few dozens of production methods that Khan passed onto me as his successor."
        val texts = read(
            entry(0, 0, "There are a few dozens of production methods that Khan passed onto me as his...", 0.02, 0.75, 0.74, 1.0),
            entry(1, 0, whole, 0.04, 0.74, 0.0, 0.38),
        )
        assertEquals(listOf(whole), texts)
    }

    @Test
    fun sameLineWithRoughBoxesIsReadOnce() {
        val texts = read(
            entry(0, 0, "What.. what do I do?!!", 0.37, 0.84, 0.51, 0.79),
            entry(1, 0, "What.. what do I do?!!", 0.37, 0.84, 0.0, 0.25),
        )
        assertEquals(listOf("What.. what do I do?!!"), texts)
    }

    @Test
    fun differentBubblesNearTheCutAreBothRead() {
        val texts = read(
            entry(0, 0, "I'm fucked!!!", 0.38, 0.99, 0.68, 1.0),
            entry(1, 0, "This game system is broken!!", 0.3, 0.97, 0.0, 0.27),
        )
        assertEquals(2, texts.size)
    }

    @Test
    fun repeatedLineFarFromTheCutIsReadTwice() {
        val texts = read(
            entry(0, 0, "Huh?", 0.2, 0.5, 0.1, 0.2),
            entry(1, 0, "Huh?", 0.2, 0.5, 0.8, 0.9),
        )
        assertEquals(listOf("Huh?", "Huh?"), texts)
    }

    @Test
    fun sideBySideBubblesAreNotMerged() {
        val texts = read(
            entry(0, 0, "No!", 0.05, 0.4, 0.8, 1.0),
            entry(1, 0, "No!", 0.6, 0.95, 0.0, 0.2),
        )
        assertEquals(2, texts.size)
    }

    @Test
    fun textMatching() {
        assertTrue(SeamTwins.sameText("the only important stat", "dexterity isn t the only important stat"))
        assertTrue(SeamTwins.sameText("dexterity isn t the only important", "the only important stat i need"))
        assertFalse(SeamTwins.sameText("i m fucked", "this game system is broken"))
        assertFalse(SeamTwins.sameText("", "anything"))
    }

    @Test
    fun mergedCopyKeepsTheSpeakerFromEitherHalf() {
        val half = entry(0, 0, "Yes. It feels like", 0.29, 0.97, 0.8, 0.99)
        val whole = entry(1, 0, "Yes. It feels like I finally finished sprinting.", 0.29, 0.97, 0.0, 0.25)
        val named = Entry(half.key, half.bubble.copy(character = "Mira", speaker = "girl"), half.sliceTop, half.sliceBottom)
        val merged = SeamTwins.merged(named, whole)
        assertEquals(whole.bubble.text, merged.text)
        assertEquals("Mira", merged.character)
        assertEquals("girl", merged.speaker)
    }
}
