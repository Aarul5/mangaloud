package io.github.aarul5.mangaloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class OcrMode { CLAUDE_API, PC_RELAY }

/** PC_NORMAL sounds best but can pause between lines; PC_FAST keeps up with reading. */
enum class VoiceEngine(val quality: String) { PHONE(""), PC_NORMAL("normal"), PC_FAST("fast") }

/** The PC voice server's speech model. */
enum class PcVoiceModel(val id: String, val label: String, val summary: String) {
    OMNIVOICE("omnivoice", "OmniVoice", "The default. Each line is acted from its tone, with fine control."),
    QWEN("qwen3-tts", "Qwen3-TTS", "Clones each character's voice and acts the tone with an expression preset."),
    VIBEVOICE("vibevoice", "VibeVoice", "Clones each character's voice; delivery follows the voice, not the tone."),
    FISH("fish", "Fish Audio (cloud)", "Fish Audio's cloud voice clones each character's voice and acts the tone. Fast; needs a Fish Audio key on the PC."),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OMNIVOICE
    }
}

/** Light or dark screens: follow the phone, or always one of them. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The Claude API key and the relays (with their tokens) are kept encrypted, not in [prefs]. */
    private val secrets = SecretStore(context)

    init {
        moveSecretsOutOfPrefs()
    }

    /** Installs from before kept the Claude key and relay tokens as plain text: encrypt them once. */
    private fun moveSecretsOutOfPrefs() {
        val plainKey = prefs.getString("apiKey", null)
        val hasPlainRelays = prefs.contains("relays") || prefs.contains("relayToken") || prefs.contains("relayUrl")
        if (plainKey == null && !hasPlainRelays) return
        try {
            if (plainKey != null) secrets.set(SECRET_API_KEY, plainKey)
            if (hasPlainRelays) secrets.set(SECRET_RELAYS, Relay.listToJson(plainRelays()))
            // Only once they are saved encrypted does the plain text go.
            prefs.edit().remove("apiKey").remove("relays").remove("relayUrl").remove("relayToken").remove("relayModel").apply()
        } catch (e: Exception) {
            android.util.Log.w("Settings", "Could not encrypt the saved keys yet: ${e.javaClass.simpleName}")
        }
    }

    private fun plainRelays(): List<Relay> {
        val saved = prefs.getString("relays", null)
        if (saved != null) return Relay.listFrom(saved)
        // Installs from before the list had one relay in separate keys.
        return listOf(
            Relay(
                name = "PC",
                url = prefs.getString("relayUrl", "") ?: "",
                token = prefs.getString("relayToken", "") ?: "",
                model = prefs.getString("relayModel", "") ?: "",
            ),
        )
    }

    var ocrMode: OcrMode
        get() = OcrMode.valueOf(prefs.getString("ocrMode", OcrMode.PC_RELAY.name)!!)
        set(value) = prefs.edit().putString("ocrMode", value.name).apply()

    var apiKey: String
        get() = secrets.get(SECRET_API_KEY).ifEmpty { prefs.getString("apiKey", "")!! }
        set(value) = secrets.set(SECRET_API_KEY, value)

    var apiModel: String
        get() = prefs.getString("apiModel", DEFAULT_API_MODEL)!!
        set(value) = prefs.edit().putString("apiModel", value.trim().ifEmpty { DEFAULT_API_MODEL }).apply()

    /** Saved relays, so you can switch PCs or Tailscale networks without retyping. */
    var relays: List<Relay>
        get() {
            val saved = secrets.get(SECRET_RELAYS)
            if (saved.isNotEmpty()) return Relay.listFrom(saved)
            // Not encrypted yet (the Keystore failed once): what is still in plain settings.
            return plainRelays()
        }
        set(value) = secrets.set(SECRET_RELAYS, Relay.listToJson(value))

    var activeRelayIndex: Int
        get() = prefs.getInt("activeRelay", 0)
        set(value) = prefs.edit().putInt("activeRelay", value).apply()

    val activeRelay: Relay
        get() = relays.let { it.getOrNull(activeRelayIndex) ?: it.firstOrNull() ?: Relay.blank() }

    val relayUrl get() = activeRelay.url
    val relayToken get() = activeRelay.token
    val relayModel get() = activeRelay.model
    val relayEffort get() = activeRelay.effort

    /** Slices sent to Claude per request once reading is under way. Bigger reads faster per second. */
    var batchSlices: Int
        get() = prefs.getInt("batchSlices", DEFAULT_BATCH_SLICES).let { if (it in BATCH_SLICE_CHOICES) it else DEFAULT_BATCH_SLICES }
        set(value) = prefs.edit().putInt("batchSlices", if (value in BATCH_SLICE_CHOICES) value else DEFAULT_BATCH_SLICES).apply()

    /** Off by default: on plain Wi-Fi the relay token travels unencrypted. */
    var allowPlainWifiRelay: Boolean
        get() = prefs.getBoolean("allowPlainWifiRelay", false)
        set(value) = prefs.edit().putBoolean("allowPlainWifiRelay", value).apply()

    var speechRate: Float
        get() = prefs.getFloat("speechRate", DEFAULT_SPEECH_RATE)
        set(value) = prefs.edit().putFloat("speechRate", value.coerceIn(0.5f, 2.5f)).apply()

    /** Seconds a panel with no bubbles stays on screen while reading. 0 glides straight past. */
    var artPauseSeconds: Float
        get() = prefs.getFloat("artPauseSeconds", DEFAULT_ART_PAUSE_SECONDS)
        set(value) = prefs.edit().putFloat("artPauseSeconds", value.coerceIn(0f, MAX_ART_PAUSE_SECONDS)).apply()

    /** The PC voice server's quality (normal or fast); PHONE is from before the voice list. */
    var voiceEngine: VoiceEngine
        get() = runCatching { VoiceEngine.valueOf(prefs.getString("voiceEngine", VoiceEngine.PHONE.name)!!) }
            .getOrDefault(VoiceEngine.PHONE)
        set(value) = prefs.edit().putString("voiceEngine", value.name).apply()

    /** Which speech model the PC voice uses. */
    var pcVoiceModel: PcVoiceModel
        get() = PcVoiceModel.of(prefs.getString("pcVoiceModel", null))
        set(value) = prefs.edit().putString("pcVoiceModel", value.id).apply()

    /** Changes pitch, speed and volume per bubble to match its tone and speaker. */
    var expressiveVoice: Boolean
        get() = prefs.getBoolean("expressiveVoice", true)
        set(value) = prefs.edit().putBoolean("expressiveVoice", value).apply()

    /** Reads the start of the next chapter's text once this one's is done, so Read there starts at once. */
    var readNextChapterEarly: Boolean
        get() = prefs.getBoolean("readNextChapterEarly", true)
        set(value) = prefs.edit().putBoolean("readNextChapterEarly", value).apply()

    /** Reads the start of a chapter's text as soon as its page opens, so Read starts at once. */
    /** Starts reading by itself when a chapter page opens. */
    var autoRead: Boolean
        get() = prefs.getBoolean("autoRead", false)
        set(value) = prefs.edit().putBoolean("autoRead", value).apply()

    /** At the end of a chapter, opens the next one (from the page's "Next" link) and reads it. */
    var autoNextChapter: Boolean
        get() = prefs.getBoolean("autoNextChapter", false)
        set(value) = prefs.edit().putBoolean("autoNextChapter", value).apply()

    /** Reads game-style windows (quests, skills, stats). Off skips them. */
    var readSystemBoxes: Boolean
        get() = prefs.getBoolean("readSystemBoxes", true)
        set(value) = prefs.edit().putBoolean("readSystemBoxes", value).apply()

    var readOnOpen: Boolean
        get() = prefs.getBoolean("readOnOpen", true)
        set(value) = prefs.edit().putBoolean("readOnOpen", value).apply()

    /** A small line in the reading bar: the model that read the last batch and how long it took. */
    var showReadingDetails: Boolean
        get() = prefs.getBoolean("showReadingDetails", true)
        set(value) = prefs.edit().putBoolean("showReadingDetails", value).apply()

    /** The reading bar shows the line being read; off keeps the bar to one row. */
    var showLineText: Boolean
        get() = prefs.getBoolean("showLineText", true)
        set(value) = prefs.edit().putBoolean("showLineText", value).apply()

    /** Japanese manga reads right to left inside a row. */
    var rightToLeft: Boolean
        get() = prefs.getBoolean("rightToLeft", false)
        set(value) = prefs.edit().putBoolean("rightToLeft", value).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString("themeMode", value.name).apply()

    /** Android 12+: take the colours from the wallpaper instead of the app's amber. */
    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamicColor", false)
        set(value) = prefs.edit().putBoolean("dynamicColor", value).apply()

    var lastUrl: String
        get() = prefs.getString("lastUrl", "https://duckduckgo.com")!!
        set(value) = prefs.edit().putString("lastUrl", value).apply()

    companion object {
        private const val SECRET_API_KEY = "claude.apiKey"
        private const val SECRET_RELAYS = "relays"
        const val DEFAULT_API_MODEL = "claude-opus-5-5"
        const val DEFAULT_SPEECH_RATE = 1.15f
        const val DEFAULT_ART_PAUSE_SECONDS = 2.5f
        const val MAX_ART_PAUSE_SECONDS = 10f
        const val DEFAULT_BATCH_SLICES = 4
        // The relay takes up to 8 slices a request (MAX_IMAGES in relay/server.mjs).
        val BATCH_SLICE_CHOICES = listOf(4, 6, 8)
    }
}

/**
 * One PC relay. A blank model or effort means the relay leaves it to whatever `claude`
 * defaults to on that PC.
 */
data class Relay(val name: String, val url: String, val token: String, val model: String, val effort: String = "") {
    val label get() = name.ifBlank { url.ifBlank { "New relay" } }

    companion object {
        /** Aliases `claude --model` takes for the latest of each model. Blank is the PC's default. */
        val MODELS = listOf("", "fable", "opus", "sonnet", "haiku")
        /** Levels `claude --effort` takes. Blank is the PC's default. */
        val EFFORTS = listOf("", "low", "medium", "high", "xhigh", "max")

        fun blank() = Relay(name = "", url = "", token = "", model = "")

        fun listFrom(json: String): List<Relay> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Relay(o.optString("name"), o.optString("url"), o.optString("token"), o.optString("model"), o.optString("effort"))
            }
        }.getOrDefault(emptyList())

        fun listToJson(relays: List<Relay>): String = JSONArray(
            relays.map { r ->
                JSONObject()
                    .put("name", r.name.trim())
                    .put("url", r.url.trim())
                    .put("token", r.token.trim())
                    .put("model", r.model.trim())
                    .put("effort", r.effort.trim())
            },
        ).toString()
    }
}
