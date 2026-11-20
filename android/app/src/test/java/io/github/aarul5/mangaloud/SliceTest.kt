package io.github.aarul5.mangaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class SliceTest {
    // The upper of two overlapping slices: it covers 0..0.5 of the image and owns 0..0.4.
    private val upper = Slice(0, 0, "page", ByteArray(0), 0.0, 0.5, 0.0, 0.4)

    @Test
    fun aBubbleInTheOverlapListedOnlyByTheUpperSliceIsKept() {
        // Centre at 0.45 of the image: in the lower slice's band, but the lower slice did not list it.
        val raw = RawBubble("Oh, nice! Put two on her!", "dialogue", listOf(0.1, 0.85, 0.6, 0.95))
        val bubbles = upper.toPageBubbles(listOf(raw))
        assertEquals(1, bubbles.size)
        assertEquals(0.425, bubbles[0].y0, 1e-9)
    }

    @Test
    fun soundEffectsAndMarksAreStillLeftOut() {
        val raws = listOf(
            RawBubble("Boom", "sfx", listOf(0.1, 0.1, 0.3, 0.2)),
            RawBubble("...!!", "dialogue", listOf(0.1, 0.3, 0.3, 0.4)),
        )
        assertEquals(0, upper.toPageBubbles(raws).size)
    }

    @Test
    fun aGameWindowIsReadByTheNarratorWithItsKind() {
        val raw = RawBubble("Quest complete!", "system", listOf(0.1, 0.1, 0.9, 0.3), speaker = "woman", character = "Siren")
        val bubble = upper.toPageBubbles(listOf(raw)).single()
        assertEquals(TextKind.SYSTEM, bubble.kind)
        assertEquals(VoiceStyle.NARRATOR, bubble.speaker)
        assertEquals("", bubble.character)
    }

    @Test
    fun aReactionKeepsItsSoundAndOtherKindsDropIt() {
        val raws = listOf(
            RawBubble("Cough!", "reaction", listOf(0.1, 0.1, 0.3, 0.2), sound = "cough"),
            RawBubble("I have a cough.", "dialogue", listOf(0.1, 0.3, 0.3, 0.4), sound = "cough"),
            RawBubble("Bwah!", "reaction", listOf(0.1, 0.5, 0.3, 0.6), sound = "made-up"),
        )
        val bubbles = upper.toPageBubbles(raws)
        assertEquals(listOf("cough", "", ""), bubbles.map { it.sound })
        assertEquals(listOf(TextKind.REACTION, TextKind.DIALOGUE, TextKind.REACTION), bubbles.map { it.kind })
    }

    @Test
    fun anUnknownTypeIsReadAsDialogue() {
        val raw = RawBubble("Hello", "song", listOf(0.1, 0.1, 0.3, 0.2))
        assertEquals(TextKind.DIALOGUE, upper.toPageBubbles(listOf(raw)).single().kind)
    }

    @Test
    fun onlyCalmThoughtsAreQuiet() {
        val calm = Bubble(0, "I should be careful.", 0.0, 0.0, 1.0, 1.0, tone = "thinking", kind = TextKind.THOUGHT)
        assertEquals(true, VoiceStyle.isQuietThought(calm))
        assertEquals(false, VoiceStyle.isQuietThought(calm.copy(tone = "shouting")))
        assertEquals(false, VoiceStyle.isQuietThought(calm.copy(kind = TextKind.DIALOGUE)))
        assertEquals(false, VoiceStyle.isQuietThought(calm.copy(text = "It's him!", tone = "serious")))
    }
}
