package io.github.aarul5.mangaloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What Claude is told about a batch besides the images: the series' characters so far, as
 * "label" (speaker): look lines, so labels stay the same; and the last lines read before it,
 * so a conversation carries on across batches.
 */
data class OcrContext(val knownCast: List<String>, val recentLines: List<String>)

/** Turns a batch of slices into bubbles, one list per slice, in reading order. */
interface OcrProvider {
    fun recognize(slices: List<Slice>, rightToLeft: Boolean, context: OcrContext): List<List<RawBubble>>

    /** The model (and effort) that read the last batch, for the status line. */
    val lastModel: String get() = ""
}

object OcrProviders {
    fun create(context: Context, settings: Settings): OcrProvider = when (settings.ocrMode) {
        OcrMode.CLAUDE_API -> {
            require(settings.apiKey.isNotBlank()) { "Add your Claude API key in Settings" }
            ClaudeApiOcr(settings.apiKey, settings.apiModel, OcrPrompt.guide(context))
        }
        OcrMode.PC_RELAY -> {
            require(settings.relayUrl.isNotBlank()) { "Add the PC relay address in Settings" }
            require(settings.relayToken.isNotBlank()) { "Add the PC relay token in Settings" }
            require(settings.allowPlainWifiRelay || RelayOcr.isEncryptedRoute(settings.relayUrl)) {
                "The relay address is not a Tailscale address, so the token would go over Wi-Fi unencrypted. " +
                    "Use the 100.x address, or allow plain Wi-Fi in Settings."
            }
            RelayOcr(settings.relayUrl, settings.relayToken, settings.relayModel, settings.relayEffort)
        }
    }
}

/** The prompt and output shape, shared by both providers so they return the same thing. */
/** Short model names for the reading bar: "claude-sonnet-5-5" becomes "Sonnet 5.5". */
object ModelName {
    private val versioned = Regex("""^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$""")

    fun short(model: String): String {
        val match = versioned.find(model.trim().lowercase()) ?: return model.trim().replaceFirstChar { it.uppercase() }
        val (family, major, minor) = match.destructured
        val version = if (minor.isEmpty()) major else "$major.$minor"
        return "${family.replaceFirstChar { it.uppercase() }} $version"
    }
}

object OcrPrompt {
    /**
     * The reading guide: how to find every bubble, tell who speaks and judge the tone. The relay
     * keeps its own copy in relay/skills, so it can be improved without a new app; the app
     * bundles the same file for Claude API mode.
     */
    const val GUIDE = "read-comic"

    @Volatile
    private var guideText: String? = null

    fun guide(context: Context): String = guideText ?: context.assets.open("$GUIDE.md")
        .bufferedReader().use { it.readText() }.also { guideText = it }

    /** The part of the prompt about this batch; the guide comes before it. */
    fun text(sliceCount: Int, rightToLeft: Boolean, context: OcrContext): String {
        val rowOrder = if (rightToLeft) "right to left" else "left to right"
        val cast = if (context.knownCast.isEmpty()) {
            "No characters are known yet for this series."
        } else {
            "Known characters in this series (label (speaker type): look), most frequent first. " +
                "Use these labels exactly:\n" + context.knownCast.joinToString("\n") { "- $it" }
        }
        val before = if (context.recentLines.isEmpty()) {
            "No lines have been read just before these slices."
        } else {
            "The last lines read just before these slices, oldest first:\n" +
                context.recentLines.joinToString("\n") { "- $it" }
        }
        return "These are $sliceCount consecutive slices of a comic chapter, numbered 0 to ${sliceCount - 1} " +
            "in the order given. Within a row, read $rowOrder.\n\n$cast\n\n$before"
    }

    /** The guide and this batch's part together, for providers that send one prompt. */
    fun full(guide: String, sliceCount: Int, rightToLeft: Boolean, context: OcrContext): String =
        "$guide\n\n# This request\n\n" + text(sliceCount, rightToLeft, context)

    val schema: String = """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["slices"],
          "properties": {
            "slices": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["index", "bubbles"],
                "properties": {
                  "index": { "type": "integer" },
                  "bubbles": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["text", "type", "sound", "tone", "intensity", "speaker", "character", "look", "box"],
                      "properties": {
                        "text": { "type": "string" },
                        "type": { "type": "string", "enum": ${TextKind.ALL.toJsonArray()} },
                        "sound": { "type": "string", "enum": ${(listOf("") + TextKind.SOUNDS).toJsonArray()} },
                        "tone": { "type": "string", "enum": ${VoiceStyle.TONES.toJsonArray()} },
                        "intensity": { "type": "integer", "enum": [1, 2, 3] },
                        "speaker": { "type": "string", "enum": ${VoiceStyle.SPEAKERS.toJsonArray()} },
                        "character": { "type": "string" },
                        "look": { "type": "string" },
                        "box": { "type": "array", "items": { "type": "number" } }
                      }
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    fun parse(responseText: String, sliceCount: Int): List<List<RawBubble>> {
        val start = responseText.indexOf('{')
        val end = responseText.lastIndexOf('}')
        require(start >= 0 && end > start) { "OCR reply had no JSON" }
        return parse(JSONObject(responseText.substring(start, end + 1)), sliceCount)
    }

    fun parse(json: JSONObject, sliceCount: Int): List<List<RawBubble>> {
        val result = MutableList<List<RawBubble>>(sliceCount) { emptyList() }
        val slices = json.getJSONArray("slices")
        for (i in 0 until slices.length()) {
            val slice = slices.getJSONObject(i)
            val index = slice.getInt("index")
            if (index !in 0 until sliceCount) continue
            result[index] = parseBubbles(slice.getJSONArray("bubbles"))
        }
        return result
    }

    fun toJson(bubbles: List<RawBubble>): String = JSONArray(bubbles.map {
        JSONObject()
            .put("text", it.text)
            .put("type", it.type)
            .put("tone", it.tone)
            .put("speaker", it.speaker)
            .put("character", it.character)
            .put("intensity", it.intensity)
            .put("look", it.look)
            .put("sound", it.sound)
            .put("box", JSONArray(it.box))
    }).toString()

    fun parseBubbles(array: JSONArray): List<RawBubble> = (0 until array.length()).map { i ->
        val bubble = array.getJSONObject(i)
        val box = bubble.getJSONArray("box")
        RawBubble(
            text = bubble.getString("text"),
            type = bubble.optString("type", "dialogue"),
            box = (0 until box.length()).map { box.getDouble(it) },
            tone = bubble.optString("tone", VoiceStyle.NEUTRAL),
            speaker = bubble.optString("speaker", VoiceStyle.UNKNOWN),
            character = bubble.optString("character", ""),
            intensity = bubble.optInt("intensity", 2).coerceIn(1, 3),
            look = bubble.optString("look", ""),
            sound = bubble.optString("sound", ""),
        )
    }

    private fun List<String>.toJsonArray() = joinToString(", ", "[", "]") { "\"$it\"" }

    /** Bump when the prompt or schema changes what a reply holds, so cached pages are read again. */
    const val VERSION = 6
}

/** Keeps OCR results on the phone, so a chapter is only sent once. */
class OcrCache(context: Context) {
    private val dir = File(context.cacheDir, "ocr").apply { mkdirs() }

    fun get(slice: Slice, rightToLeft: Boolean): List<RawBubble>? {
        val file = fileFor(slice, rightToLeft)
        if (!file.exists()) return null
        return runCatching { OcrPrompt.parseBubbles(JSONArray(file.readText())) }.getOrNull()
    }

    fun put(slice: Slice, rightToLeft: Boolean, bubbles: List<RawBubble>) {
        fileFor(slice, rightToLeft).writeText(OcrPrompt.toJson(bubbles))
    }

    /** Names one slice's text, read one way, so two requests can tell they want the same thing. */
    fun keyOf(slice: Slice, rightToLeft: Boolean): String =
        slice.cacheKey + (if (rightToLeft) "#rtl" else "#ltr") + "#ocr${OcrPrompt.VERSION}"

    private fun fileFor(slice: Slice, rightToLeft: Boolean): File {
        val key = keyOf(slice, rightToLeft)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }
}
