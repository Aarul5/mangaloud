package io.github.aarul5.mangaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextTest {
    private fun bubble(
        text: String,
        tone: String = "neutral",
        speaker: String = "man",
        kind: String = TextKind.DIALOGUE,
        sound: String = "",
        intensity: Int = 2,
        character: String = "",
    ) = Bubble(0, text, 0.0, 0.0, 0.0, 0.0, tone = tone, speaker = speaker, kind = kind, sound = sound, intensity = intensity, character = character)

    @Test
    fun fishCuesMatchTheRelay() {
        assertEquals("[very angry] Don't touch him!", VoiceText.fish(bubble("Don't touch him!", "angry", intensity = 3)))
        assertEquals("[calm] The forge was cold.", VoiceText.fish(bubble("The forge was cold.", speaker = "narrator", kind = TextKind.NARRATION)))
        assertEquals("[coughing] I'm fine.", VoiceText.fish(bubble("Cough! I'm fine.")))
        assertEquals("[screaming] Kyaa!", VoiceText.fish(bubble("Kyaa!", kind = TextKind.REACTION, sound = "shriek")))
        assertEquals("Agh!", VoiceText.fish(bubble("Agh!")))
        assertEquals("Tsk", VoiceText.fish(bubble("Tsk")))
        assertEquals("Hmph.", VoiceText.fish(bubble("Hmph.", "angry")))
        assertEquals("[quietly, to self] Strange...", VoiceText.fish(bubble("Strange...", kind = TextKind.THOUGHT)))
        assertEquals("[thoughtful] It's him!", VoiceText.fish(bubble("It's him!", "thinking", kind = TextKind.THOUGHT)))
    }

    @Test
    fun bracketsBecomePauses() {
        assertEquals("[calm] Approaching Doom, Hidden", VoiceText.fish(bubble("(Approaching Doom (Hidden))", kind = TextKind.SYSTEM, speaker = "narrator")))
        assertEquals("Level up, 31", VoiceText.plainWords("[Level up] 31"))
    }

    @Test
    fun everyBracketStyleIsAPause() {
        assertEquals("Attack Memorial Song!", VoiceText.plainWords("⟨Attack Memorial Song⟩!"))
        assertEquals("Attack Memorial Song!", VoiceText.plainWords("〈Attack Memorial Song〉!"))
        assertEquals("Sword Dance", VoiceText.plainWords("《Sword Dance》"))
        assertEquals("Hidden, quest", VoiceText.plainWords("「Hidden」 quest"))
        assertEquals("[calm] Attack Memorial Song!", VoiceText.fish(bubble("⟨Attack Memorial Song⟩!", speaker = "narrator", kind = TextKind.SYSTEM)))
    }

    @Test
    fun clipLengthCheck() {
        // A 128 kbit/s MP3 of 160,000 bytes plays for 10 seconds.
        assertEquals(10.0, Wav.seconds(ByteArray(160_000), "mp3"), 0.01)
        // 24 kHz 16-bit mono: 48,000 bytes a second.
        assertEquals(2.0, Wav.seconds(Wav.fromPcm16(ByteArray(96_000), 24_000), "wav"), 0.01)
        // A 22-character line gets under 6 seconds at normal speed, so 41 seconds is caught.
        val limit = Wav.limitSeconds("Attack Memorial Song!!", 1f)
        assertTrue(limit in 5.0..6.5)
        assertTrue(41.0 > limit)
        // A short sound gets 4 seconds: a 7-second "Haa, haa" is a runaway.
        assertEquals(4.0, Wav.limitSeconds("Haa, haa", 1.15f), 0.001)
        // A long line still has room for a slow read.
        assertTrue(Wav.limitSeconds("x".repeat(150), 1f) > 20.0)
    }

    @Test
    fun onlySoundLines() {
        assertTrue(VoiceText.isOnlySound(bubble("Haa, haa")))
        assertTrue(VoiceText.isOnlySound(bubble("Kyaa!", kind = TextKind.REACTION, sound = "shriek")))
        assertFalse(VoiceText.isOnlySound(bubble("Cough! I'm fine.")))
        assertFalse(VoiceText.isOnlySound(bubble("Over here.")))
    }

    @Test
    fun elevenLabsTags() {
        assertEquals("[whispers] Over here.", VoiceText.eleven(bubble("Over here.", "whispering"), tags = true))
        assertEquals("[very angry] Stop!", VoiceText.eleven(bubble("Stop!", "angry", intensity = 3), tags = true))
        assertEquals("[sighs] Haah...", VoiceText.eleven(bubble("Haah...", kind = TextKind.REACTION, sound = "sigh"), tags = true))
        assertEquals("[coughs] [sad] I'm fine.", VoiceText.eleven(bubble("Cough! I'm fine.", "sad"), tags = true))
        // Flash models would read tags out loud.
        assertEquals("Over here.", VoiceText.eleven(bubble("Over here.", "whispering"), tags = false))
    }

    @Test
    fun geminiStyleAndTags() {
        val angry = VoiceText.gemini(bubble("Stop right there!", "shouting", intensity = 3))
        assertEquals("Stop right there!", angry.text)
        assertEquals("shouting loudly, intense", angry.style)
        val sigh = VoiceText.gemini(bubble("Haah... fine.", kind = TextKind.REACTION, sound = "sigh"))
        assertEquals("<sigh> Haah... fine.", sigh.text)
        assertEquals("calm, even narration", VoiceText.gemini(bubble("Night fell.", speaker = "narrator")).style)
        assertTrue(VoiceText.gemini(bubble("Strange...", kind = TextKind.THOUGHT)).style.contains("quietly"))
    }

    @Test
    fun speakerRows() {
        assertEquals(SpeakerRows.SYSTEM, SpeakerRows.typeOf(bubble("Quest complete", speaker = "narrator", kind = TextKind.SYSTEM)))
        assertEquals("narrator", SpeakerRows.typeOf(bubble("Night fell.", speaker = "narrator", kind = TextKind.NARRATION)))
        assertEquals("girl", SpeakerRows.typeOf(bubble("Hi!", speaker = "girl")))
        assertEquals("man", SpeakerRows.typeOf(bubble("Hm?", speaker = "unknown")))
    }

    @Test
    fun seriesKeepsOnlyChanges() {
        val fish = VoiceProviderId.FISH
        val empty = SeriesVoices("site/iron-smith", "Iron Smith", null, emptyMap())
        assertTrue(empty.isEmpty)
        val changed = empty.withSpeaker(fish, "woman", VoicePick("a1", "Aria")).withCharacter(fish, "Mira", VoicePick("h2", "Hana"))
        assertFalse(changed.isEmpty)
        assertEquals("Aria", changed.on(fish).speakers["woman"]?.name)
        assertEquals("Hana", changed.on(fish).characters["mira"]?.pick?.name)
        assertNull(changed.on(VoiceProviderId.GEMINI).speakers["woman"])
        val reset = changed.withSpeaker(fish, "woman", null).withCharacter(fish, "MIRA", null)
        assertTrue(reset.isEmpty)
        assertEquals("ElevenLabs", changed.withProvider(VoiceProviderId.ELEVENLABS).summary(fish))
        assertEquals("Usual provider · 1 speaker voice · 1 character", changed.summary(fish))
    }

    @Test
    fun elevenLabsDefaultsFollowLabels() {
        val voices = listOf(
            LibraryVoice("m1", "Adam", "", "male", "middle-aged", null),
            LibraryVoice("m2", "Liam", "", "male", "young", null),
            LibraryVoice("f1", "Sarah", "", "female", "young", null),
            LibraryVoice("o1", "Bill", "", "male", "old", null),
        )
        val picks = ElevenLabsTts.chooseDefaults(voices)
        assertEquals("m2", picks["man"]?.id)
        assertEquals("f1", picks["woman"]?.id)
        assertEquals("o1", picks["old man"]?.id)
        assertEquals("m1", picks["narrator"]?.id)
        assertEquals("f1", picks["old woman"]?.id)
        assertEquals(SpeakerRows.TYPES.toSet(), picks.keys)
    }
}
