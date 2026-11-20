package io.github.aarul5.mangaloud

/**
 * Turns a bubble's tone and speaker into pitch, speed and volume for the phone's voice.
 * Android's built-in engines cannot act, but changing these per bubble is enough to tell
 * a shout from a whisper and an old man from a girl.
 */
object VoiceStyle {
    const val NEUTRAL = "neutral"
    const val UNKNOWN = "unknown"
    const val NARRATOR = "narrator"

    /** pitch and rate are multipliers; volume is 0 to 1. */
    data class Style(val pitch: Float, val rate: Float, val volume: Float)

    private val toneStyles = linkedMapOf(
        NEUTRAL to Style(1.00f, 1.00f, 1.00f),
        "happy" to Style(1.08f, 1.05f, 1.00f),
        "excited" to Style(1.12f, 1.15f, 1.00f),
        "shouting" to Style(1.10f, 1.12f, 1.00f),
        "angry" to Style(0.94f, 1.10f, 1.00f),
        "sad" to Style(0.92f, 0.85f, 0.85f),
        "scared" to Style(1.10f, 1.12f, 0.90f),
        "whispering" to Style(1.00f, 0.90f, 0.50f),
        "thinking" to Style(0.98f, 0.92f, 0.75f),
        "serious" to Style(0.95f, 0.95f, 1.00f),
        "laughing" to Style(1.10f, 1.08f, 1.00f),
        "teasing" to Style(1.05f, 1.00f, 0.95f),
        "cold" to Style(0.92f, 0.94f, 0.90f),
        "crying" to Style(1.05f, 0.85f, 0.85f),
        "desperate" to Style(1.10f, 1.10f, 1.00f),
        "pained" to Style(0.95f, 0.88f, 0.90f),
        "tired" to Style(0.93f, 0.85f, 0.80f),
        "embarrassed" to Style(1.08f, 1.05f, 0.80f),
    )

    private val speakerStyles = linkedMapOf(
        NARRATOR to Style(1.00f, 0.95f, 1.00f),
        "man" to Style(0.85f, 1.00f, 1.00f),
        "woman" to Style(1.15f, 1.00f, 1.00f),
        "boy" to Style(1.25f, 1.05f, 1.00f),
        "girl" to Style(1.35f, 1.05f, 1.00f),
        "old man" to Style(0.75f, 0.90f, 1.00f),
        "old woman" to Style(1.05f, 0.90f, 1.00f),
        "creature" to Style(0.60f, 0.95f, 1.00f),
        UNKNOWN to Style(1.00f, 1.00f, 1.00f),
    )

    val TONES: List<String> = toneStyles.keys.toList()
    val SPEAKERS: List<String> = speakerStyles.keys.toList()

    private val plain = Style(1f, 1f, 1f)

    fun styleFor(bubble: Bubble, baseRate: Float, expressive: Boolean): Style {
        if (!expressive) return Style(1f, baseRate, 1f)
        val tone = (toneStyles[bubble.tone] ?: plain).scaled(bubble.intensity)
        val speaker = speakerStyles[bubble.speaker] ?: plain
        // Game windows sound a little brighter than the narrator; calm thoughts a little quieter.
        val kindPitch = if (bubble.kind == TextKind.SYSTEM) 1.12f else 1f
        val kindVolume = if (isQuietThought(bubble)) THOUGHT_VOLUME else 1f
        return Style(
            pitch = (tone.pitch * speaker.pitch * kindPitch * characterPitch(bubble.character)).coerceIn(0.5f, 2.0f),
            rate = (baseRate * tone.rate * speaker.rate).coerceIn(0.4f, 3.0f),
            volume = (tone.volume * speaker.volume * kindVolume).coerceIn(0.1f, 1.0f),
        )
    }

    /** How loud a calm thought is next to speech, on the phone voice and PC clips alike. */
    const val THOUGHT_VOLUME = 0.75f

    private val urgentTones = setOf("shouting", "angry", "scared", "desperate", "excited")

    /** A thought said inwardly. An urgent one, or one ending in "!" ("It's him!"), keeps full voice. */
    fun isQuietThought(bubble: Bubble): Boolean =
        bubble.kind == TextKind.THOUGHT && bubble.tone !in urgentTones && !bubble.text.trimEnd().endsWith("!")

    /** 1 halves the tone's change from a plain read, 2 keeps it, 3 makes it half as big again. */
    private fun Style.scaled(intensity: Int): Style {
        val amount = when (intensity) { 1 -> 0.5f; 3 -> 1.5f; else -> 1f }
        fun scale(value: Float) = 1f + (value - 1f) * amount
        return Style(scale(pitch), scale(rate), scale(volume).coerceAtMost(1f))
    }

    /** A small fixed pitch shift per named character, so two men on the phone voice differ a little. */
    private fun characterPitch(character: String): Float {
        if (character.isEmpty()) return 1f
        val step = Math.floorMod(character.lowercase().hashCode(), 5) - 2 // -2..2
        return 1f + step * 0.05f
    }
}
