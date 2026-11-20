package io.github.aarul5.mangaloud

/**
 * A bubble's words as each cloud voice wants them, with its tone and any sound (a cough, a
 * sigh) written in that voice's own way: bracket cues for Fish, audio tags for ElevenLabs, and
 * a style plus inline tags for Gemini. Kept free of Android so it can be tested on its own.
 */
object VoiceText {

    /** A line split into the sound a character makes and the words after it. */
    data class Split(val sound: String, val said: String, val rest: String)

    val SOUNDS = listOf("cough", "shriek", "groan", "sigh", "gasp", "laugh", "sob", "pant", "grunt")

    // Each sound as words, for lines with no sound label: every word must be one of these.
    private val SOUND_WORDS = mapOf(
        "cough" to Regex("""^(cough|coughs|koff|kof|cof|khh?|kehh?|ahem)$"""),
        "shriek" to Regex("""^(k+y+a+h*|ki+ya+h*|e{2,}k+|hi{2,}e*h*|i+ya{2,}h*)$"""),
        "groan" to Regex("""^(a*r*g+h+|a+g+h*|u+g+h+|gu+h+|ga+h+|n+g+h+|ku+h+|kugh|urk)$"""),
        "sigh" to Regex("""^(ha{2,}h*|ho{2,}h*|phew|sigh|sighs|fu{2,}h*|hu{2,}h*)$"""),
        "gasp" to Regex("""^(gasp|gasps|gulp)$"""),
        "laugh" to Regex("""^(a?(ha){2,}h*|(he){2,}h*|(hi){2,}h*|(fu){2,}h*|(ku){2,}h*|(ho){2,}h*|(ke){2,}h*)$"""),
        "sob" to Regex("""^(sob|sobs|hic|hik|sniff|sniffs|sniffle|uwa+h*|wa{3,}h*)$"""),
        "pant" to Regex("""^(huff|puff|pant|pants|hff)$"""),
        "grunt" to Regex("""^(hmph|humph|tch|tsk|grr+|hn+|nn+)$"""),
    )
    private val NOT_LETTERS = Regex("""[^a-z]+""")
    private val LEADING_SOUND = Regex("""^\s*([A-Za-z' -]+?)([!?.…,~]+)\s*(.*\p{L}.*)$""")
    private val URGENT_TONES = setOf("shouting", "angry", "scared", "desperate", "excited")

    private fun soundOfWords(words: String): String {
        val tokens = words.lowercase().split(NOT_LETTERS).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return SOUNDS.firstOrNull { name -> tokens.all { SOUND_WORDS.getValue(name).matches(it) } }.orEmpty()
    }

    /** "Cough! I'm fine" is a cough, then "I'm fine". A sound from the reading (it saw the picture) wins. */
    fun splitSound(text: String, sound: String = ""): Split {
        if (sound in SOUNDS) return Split(sound, text, "")
        val whole = soundOfWords(text)
        if (whole.isNotEmpty()) return Split(whole, text, "")
        val lead = LEADING_SOUND.find(text)
        if (lead != null) {
            val leading = soundOfWords(lead.groupValues[1])
            if (leading.isNotEmpty()) return Split(leading, lead.groupValues[1] + lead.groupValues[2], lead.groupValues[3])
        }
        return Split("", "", text)
    }

    /** A thought said with feeling ("It's him!") is said aloud, not inwardly. */
    fun isUrgent(tone: String, text: String) = tone in URGENT_TONES || text.trimEnd().endsWith("!")

    /**
     * Brackets in a bubble become pauses: cloud voices read bracketed words as cues, and
     * "(Approaching Doom (Hidden))" in a game window came out as 41 seconds of noise from Fish.
     * Skill names often come in other brackets ("⟨Attack Memorial Song⟩!"), so all kinds count.
     */
    private val BRACKETS = Regex("""[\[\](){}<>|⟨⟩〈〉《》「」『』【】〔〕［］（）｛｝‹›«»〖〗〘〙⦅⦆⟦⟧⟪⟫❨❩❪❫❬❭❮❯]""")

    fun plainWords(text: String): String {
        val cleaned = text
            .replace(BRACKETS, " , ")
            .replace(Regex("""\s*,(\s*,)*\s*"""), ", ")
            .replace(Regex(""",\s*([.!?…])"""), "$1")
            .replace(Regex("""^[,\s]+|[,\s]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifEmpty { text.replace(BRACKETS, " ").replace(Regex("""\s+"""), " ").trim() }
    }

    private fun soundFor(bubble: Bubble) = if (bubble.kind == TextKind.REACTION) bubble.sound else ""
    private fun calmSpeaker(bubble: Bubble) = bubble.speaker == VoiceStyle.NARRATOR || bubble.kind == TextKind.SYSTEM
    private fun quietThought(bubble: Bubble, words: String) =
        bubble.kind == TextKind.THOUGHT && !isUrgent(bubble.tone, words) && bubble.tone != "whispering"

    // Fish Audio: "[very angry] Don't touch him!"

    private val FISH_CUES = mapOf(
        "happy" to "happy", "excited" to "excited", "angry" to "angry", "sad" to "sad", "scared" to "scared",
        "thinking" to "thoughtful", "serious" to "serious", "teasing" to "teasing", "cold" to "cold",
        "desperate" to "desperate", "pained" to "in pain", "tired" to "tired", "embarrassed" to "embarrassed",
    )
    private val FISH_FIXED_CUES = mapOf("shouting" to "shouting", "whispering" to "whispering", "laughing" to "laughing", "crying" to "sobbing")
    private fun strength(intensity: Int) = when (intensity) { 1 -> "slightly "; 3 -> "very "; else -> "" }

    // Listening tests with Fish: "[coughing] Uh..." sounded like a cough (the word "Cough!" was read
    // out); the scream cue beat others for "Kyaa!"; a plain "Agh!" beat "[in pain]".
    private fun fishSound(sound: String, said: String, tone: String) = when (sound) {
        "cough" -> "[coughing] Uh..."
        "shriek" -> "[screaming] $said"
        "groan" -> if (tone == "angry") "[frustrated] $said" else said
        "sigh" -> "[sighing] $said"
        "gasp" -> "[gasping] Ah!"
        "laugh" -> "[laughing] $said"
        "sob" -> "[sobbing] $said"
        "pant" -> "[panting] $said"
        "grunt" -> "[grunting] $said"
        else -> said
    }

    fun fish(bubble: Bubble): String {
        val text = plainWords(bubble.text)
        val split = splitSound(text, soundFor(bubble))
        val parts = mutableListOf<String>()
        if (split.sound == "cough" && split.rest.isNotEmpty()) parts += "[coughing]"
        else if (split.sound.isNotEmpty()) parts += fishSound(split.sound, split.said, bubble.tone)
        if (split.rest.isNotEmpty()) {
            val cues = mutableListOf<String>()
            val tone = bubble.tone
            when {
                tone in FISH_FIXED_CUES -> cues += FISH_FIXED_CUES.getValue(tone)
                tone in FISH_CUES -> cues += strength(bubble.intensity) + FISH_CUES.getValue(tone)
                calmSpeaker(bubble) -> cues += "calm"
            }
            if (quietThought(bubble, split.rest)) cues += "quietly, to self"
            val prefix = cues.joinToString("") { "[$it]" }
            parts += if (prefix.isEmpty()) split.rest else "$prefix ${split.rest}"
        }
        return parts.joinToString(" ")
    }

    // ElevenLabs: audio tags such as [whispers], [laughs] and [sighs] (Eleven v3 and v4 only).

    private val ELEVEN_TAGS = mapOf(
        "happy" to "happy", "excited" to "excited", "shouting" to "shouting", "angry" to "angry", "sad" to "sad",
        "scared" to "scared", "whispering" to "whispers", "thinking" to "thoughtful", "serious" to "serious",
        "laughing" to "laughs", "teasing" to "mischievously", "cold" to "cold", "crying" to "crying",
        "desperate" to "desperate", "pained" to "in pain", "tired" to "tired", "embarrassed" to "embarrassed",
    )

    private fun elevenSound(sound: String, said: String, tone: String) = when (sound) {
        "cough" -> "[coughs]"
        "shriek" -> "[screams] $said"
        "groan" -> if (tone == "angry") "[frustrated] $said" else "[groans] $said"
        "sigh" -> "[sighs] $said"
        "gasp" -> "[gasps]"
        "laugh" -> "[laughs] $said"
        "sob" -> "[crying] $said"
        "pant" -> "[breathing heavily] $said"
        "grunt" -> "[grunts] $said"
        else -> said
    }

    /** With [tags] off (Flash models read tags out loud), only the words go. */
    fun eleven(bubble: Bubble, tags: Boolean): String {
        val text = plainWords(bubble.text)
        if (!tags) return text
        val split = splitSound(text, soundFor(bubble))
        val parts = mutableListOf<String>()
        if (split.sound.isNotEmpty()) parts += elevenSound(split.sound, split.said, bubble.tone)
        if (split.rest.isNotEmpty()) {
            val tags2 = mutableListOf<String>()
            ELEVEN_TAGS[bubble.tone]?.let { tags2 += if (bubble.intensity == 3 && bubble.tone !in setOf("whispering", "laughing")) "very $it" else it }
            if (quietThought(bubble, split.rest)) tags2 += "quietly"
            val prefix = tags2.joinToString(" ") { "[$it]" }
            parts += if (prefix.isEmpty()) split.rest else "$prefix ${split.rest}"
        }
        return parts.joinToString(" ")
    }

    // Gemini: the words with inline tags like <sigh>, and the delivery as a short style.

    data class Styled(val text: String, val style: String)

    private val GEMINI_STYLES = mapOf(
        "happy" to "cheerful", "excited" to "excited", "shouting" to "shouting loudly", "angry" to "angry",
        "sad" to "sad", "scared" to "scared and shaky", "whispering" to "whispered", "thinking" to "thoughtful",
        "serious" to "serious", "laughing" to "laughing", "teasing" to "teasing", "cold" to "cold and flat",
        "crying" to "crying", "desperate" to "desperate", "pained" to "in pain", "tired" to "tired",
        "embarrassed" to "embarrassed and flustered",
    )

    private fun geminiSound(sound: String, said: String) = when (sound) {
        "cough" -> "<cough>"
        "sigh" -> "<sigh> $said"
        "gasp" -> "<gasp>"
        "laugh" -> "<laugh> $said"
        "pant" -> "<breath> $said"
        else -> said
    }

    fun gemini(bubble: Bubble): Styled {
        val text = plainWords(bubble.text)
        val split = splitSound(text, soundFor(bubble))
        val parts = mutableListOf<String>()
        if (split.sound.isNotEmpty()) parts += geminiSound(split.sound, split.said)
        if (split.rest.isNotEmpty()) parts += split.rest
        val styles = mutableListOf<String>()
        GEMINI_STYLES[bubble.tone]?.let {
            styles += it
            if (bubble.intensity == 3) styles += "intense" else if (bubble.intensity == 1) styles += "restrained"
        }
        if (styles.isEmpty() && calmSpeaker(bubble)) styles += "calm, even narration"
        if (split.sound == "shriek") styles += "screaming"
        if (split.sound == "sob") styles += "sobbing"
        if (quietThought(bubble, split.rest.ifEmpty { text })) styles += "quietly, to themself"
        return Styled(parts.joinToString(" ").trim(), styles.joinToString(", "))
    }
}
