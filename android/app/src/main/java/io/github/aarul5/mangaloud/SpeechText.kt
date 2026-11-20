package io.github.aarul5.mangaloud

/** Turns bubble text into what the voice should say. The subtitle keeps the original text. */
object SpeechText {
    private val trailingDots = Regex("""\.{2,}|…+""")
    private val repeatedMarks = Regex("""([!?])[!?]+""")
    private val commaBeforeMark = Regex(""",\s*([!?.,])""")
    private val spaces = Regex("""\s+""")

    fun hasWords(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /** "Well... I guess" -> "Well, I guess"; "What?!!" -> "What?". Dots become a short pause. */
    fun forSpeech(text: String): String = text
        .replace(trailingDots, ", ")
        .replace(repeatedMarks, "$1")
        .replace(commaBeforeMark, "$1")
        .replace(spaces, " ")
        .trim()
        .trimStart { !it.isLetterOrDigit() && it != '\'' && it != '"' }
        .trimEnd(',', ' ')
}
