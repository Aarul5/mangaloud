package io.github.aarul5.mangaloud

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Who reads the bubbles. */
enum class VoiceProviderId(val id: String, val label: String, val summary: String, val keyUrl: String = "") {
    PHONE("phone", "Phone voice", "Free, works offline. Plain but quick."),
    FISH("fish", "Fish Audio", "Cloud. Acts tones and sounds. Has a free tier.", "https://fish.audio/app/api-keys/"),
    ELEVENLABS("elevenlabs", "ElevenLabs", "Cloud. Most expressive. Paid per character.", "https://elevenlabs.io/app/settings/api-keys"),
    GEMINI("gemini", "Gemini", "Cloud. Google's voices, styled from each line's tone.", "https://aistudio.google.com/apikey"),
    PC("pc", "PC voice server", "OmniVoice and others on your own PC, through the relay."),
    ;

    val isCloud get() = this == FISH || this == ELEVENLABS || this == GEMINI

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** A chosen voice: the provider's id for it, and a name to show. */
data class VoicePick(val id: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    companion object {
        fun from(o: JSONObject?): VoicePick? = o?.optString("id")?.takeIf { it.isNotEmpty() }?.let { VoicePick(it, o.optString("name", it)) }
    }
}

/** A voice in a provider's library. [gender] is "male", "female" or blank; [age] "child", "teen", "young", "middle-aged", "old" or blank. */
data class LibraryVoice(
    val id: String,
    val name: String,
    val about: String,
    val gender: String,
    val age: String,
    val previewUrl: String?,
) {
    val pick get() = VoicePick(id, name)
}

/** The kinds of speaker that each get a voice, in the order the screens list them. */
object SpeakerRows {
    const val SYSTEM = "system"
    val TYPES = listOf(VoiceStyle.NARRATOR, SYSTEM, "man", "woman", "boy", "girl", "old man", "old woman", "creature")

    fun label(type: String) = when (type) {
        VoiceStyle.NARRATOR -> "Narrator"
        SYSTEM -> "Game windows"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** The row a bubble's voice comes from. Someone with no clear type reads as a man, as before. */
    fun typeOf(bubble: Bubble): String = when {
        bubble.kind == TextKind.SYSTEM -> SYSTEM
        bubble.kind == TextKind.NARRATION || bubble.speaker == VoiceStyle.NARRATOR -> VoiceStyle.NARRATOR
        bubble.speaker in TYPES -> bubble.speaker
        else -> "man"
    }
}

/** A cloud voice service, called straight from the phone. Every call is blocking: use a worker thread. */
interface CloudTts {
    val provider: VoiceProviderId

    /** Model ids and their labels; the first is the default. */
    val models: List<Pair<String, String>>

    /** The voice a speaker type gets until one is picked, or null when it has to be found in the library. */
    fun defaultVoice(type: String): VoicePick?

    /** Checks the key; returns a short line for the screen, or throws with the reason. */
    fun test(key: String): String

    /** The voices to choose from; [search] blank lists the recommended ones. */
    fun library(key: String, search: String): List<LibraryVoice>

    /** One line of speech, and the file extension of its audio. [steady] asks for a calmer, more predictable take. */
    fun speak(
        key: String,
        model: String,
        voice: VoicePick,
        bubble: Bubble,
        speed: Float,
        steady: Boolean = false,
        /** Stop the download once the clip is longer than this ([ClipTooLong]). */
        maxSeconds: Double = Double.MAX_VALUE,
    ): Pair<ByteArray, String>

    companion object {
        fun of(provider: VoiceProviderId): CloudTts? = when (provider) {
            VoiceProviderId.FISH -> FishTts
            VoiceProviderId.ELEVENLABS -> ElevenLabsTts
            VoiceProviderId.GEMINI -> GeminiTts
            else -> null
        }
    }
}

/** A cloud voice answered with an error, with the service's own reason. */
class VoiceServiceException(val code: Int, message: String) : IOException(message)

/** A clip that was still coming after it was already far longer than its line; stopped part way. */
class ClipTooLong : IOException("The clip ran on far past the line")

internal object Http {
    /** [maxBytes] stops a download that is already bigger than the answer can sensibly be ([ClipTooLong]). */
    fun call(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        readTimeoutMs: Int = 60_000,
        maxBytes: Long = Long.MAX_VALUE,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw VoiceServiceException(code, reason(code, detail))
            }
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw ClipTooLong()
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    fun json(url: String, method: String, headers: Map<String, String>, body: JSONObject? = null, readTimeoutMs: Int = 60_000): JSONObject {
        val all = if (body != null) headers + ("Content-Type" to "application/json") else headers
        return JSONObject(String(call(url, method, all, body?.toString()?.toByteArray(), readTimeoutMs)))
    }

    fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    /** The service's own words for an error, short enough for a message on screen. */
    private fun reason(code: Int, detail: String): String {
        val said = runCatching {
            val o = JSONObject(detail)
            o.optJSONObject("detail")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("detail").takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull() ?: detail.take(160)
        val plain = when (code) {
            401, 403 -> "The key was not accepted"
            402 -> "Out of credit"
            429 -> "Too many requests or out of quota"
            else -> "Error $code"
        }
        return if (said.isNullOrBlank()) plain else "$plain: ${said.take(160)}"
    }
}

/** Fish Audio, with voices from its library (no voice design on a PC needed). */
object FishTts : CloudTts {
    override val provider = VoiceProviderId.FISH
    override val models = listOf("s2.1-pro-free" to "S2.1 Pro (free tier)")

    // Fish's own "voice-factory" voices: generic English voices made by Fish, not clones of anyone.
    private val defaults = mapOf(
        VoiceStyle.NARRATOR to VoicePick("8d5d5e17e2fa4b6cb587ece7198be0d9", "Alastair"),
        SpeakerRows.SYSTEM to VoicePick("08b4100b4c3747fe84ed8b57d26a95ba", "James"),
        "man" to VoicePick("274434b3256f4e4cb35f8fc9fd54c1b8", "Caleb"),
        "woman" to VoicePick("cbc3add1f54546e6a4a1964bacb8b122", "Naomi"),
        "boy" to VoicePick("13c8817b96094b229832897ce6432f86", "Nolan"),
        "girl" to VoicePick("0c2f0c6fd7a54b5f8e3f3ed571d7c4ac", "Mabel"),
        "old man" to VoicePick("88db76d4fab54ed494ce4da746d1027f", "Thaddeus"),
        "old woman" to VoicePick("7e9224dacd1c4de7918aa46c82009a70", "Pearl"),
        "creature" to VoicePick("e58a6b7e06f048d0b83836d55d226b90", "Gareth"),
    )

    override fun defaultVoice(type: String) = defaults[type] ?: defaults["man"]

    private fun auth(key: String) = mapOf("Authorization" to "Bearer $key")

    override fun test(key: String): String {
        Http.json("https://api.fish.audio/wallet/self/api-credit", "GET", auth(key))
        return "Key works"
    }

    override fun library(key: String, search: String): List<LibraryVoice> {
        val query = if (search.isBlank()) "&tag=voice-factory" else "&title=${Http.enc(search.trim())}"
        val json = Http.json("https://api.fish.audio/model?page_size=60&page_number=1&sort_by=score&language=en$query", "GET", auth(key))
        val items = json.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }.map { m ->
            val tags = m.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it).lowercase() } }.orEmpty()
            LibraryVoice(
                id = m.getString("_id"),
                name = m.optString("title").trim(),
                about = tags.filter { it !in GENDERS && it !in AGES && !it.startsWith("en-") && it != "voice-factory" && !it.contains('_') }.take(4).joinToString(" · "),
                gender = tags.firstOrNull { it in GENDERS }.orEmpty(),
                age = tags.firstOrNull { it in AGES }?.let { if (it == "kid") "child" else it }.orEmpty(),
                previewUrl = m.optJSONArray("samples")?.optJSONObject(0)?.optString("audio")?.takeIf { it.startsWith("http") },
            )
        }
    }

    override fun speak(key: String, model: String, voice: VoicePick, bubble: Bubble, speed: Float, steady: Boolean, maxSeconds: Double): Pair<ByteArray, String> {
        // A retry after a clip ran on sends only the words: cues in front of short lines are what
        // set Fish off.
        val body = JSONObject()
            .put("text", if (steady) VoiceText.plainWords(bubble.text) else VoiceText.fish(bubble))
            .put("reference_id", voice.id)
            .put("format", "mp3")
            .put("mp3_bitrate", 128)
            .put("latency", "normal")
            .put("normalize", true)
            .put("prosody", JSONObject().put("speed", speed.coerceIn(0.5f, 2f).toDouble()).put("volume", 0))
        // A lower temperature makes Fish less likely to wander off into noise. A line that is only
        // a sound ("Haa, haa", "Kyaa!") is the likeliest to, so it is always read steadily.
        if (steady || VoiceText.isOnlySound(bubble)) body.put("temperature", 0.5)
        val headers = auth(key) + mapOf("Content-Type" to "application/json", "model" to model)
        return Http.call("https://api.fish.audio/v1/tts", "POST", headers, body.toString().toByteArray(), 30_000, mp3Bytes(maxSeconds)) to "mp3"
    }

    private val GENDERS = setOf("male", "female")
    private val AGES = setOf("child", "kid", "teen", "young", "middle-aged", "old")
}

/** ElevenLabs: the voices in the account's "My voices", which includes its default voices. */
object ElevenLabsTts : CloudTts {
    override val provider = VoiceProviderId.ELEVENLABS
    override val models = listOf("eleven_v4" to "Expressive", "eleven_flash_v2_5" to "Fast, cheaper")

    /** ElevenLabs voice ids differ by account, so defaults are found in the library. */
    override fun defaultVoice(type: String): VoicePick? = null

    /** Picks a library voice for each speaker type by its gender and age labels. */
    fun chooseDefaults(voices: List<LibraryVoice>): Map<String, VoicePick> {
        fun best(gender: String, ages: List<String>): VoicePick? =
            ages.firstNotNullOfOrNull { age -> voices.firstOrNull { it.gender == gender && it.age == age } }?.pick
                ?: voices.firstOrNull { it.gender == gender }?.pick
        val man = best("male", listOf("young", "middle-aged"))
        val woman = best("female", listOf("young", "middle-aged"))
        val picks = mapOf(
            VoiceStyle.NARRATOR to (best("male", listOf("middle-aged", "old")) ?: man),
            SpeakerRows.SYSTEM to man,
            "man" to man,
            "woman" to woman,
            "boy" to (best("male", listOf("child", "teen", "young")) ?: man),
            "girl" to (best("female", listOf("child", "teen", "young")) ?: woman),
            "old man" to (best("male", listOf("old", "middle-aged")) ?: man),
            "old woman" to (best("female", listOf("old", "middle-aged")) ?: woman),
            "creature" to (best("male", listOf("old", "middle-aged")) ?: man),
        )
        val any = voices.firstOrNull()?.pick
        return picks.mapNotNull { (type, pick) -> (pick ?: any)?.let { type to it } }.toMap()
    }

    private fun auth(key: String) = mapOf("xi-api-key" to key)

    override fun test(key: String): String = try {
        val sub = Http.json("https://api.elevenlabs.io/v1/user/subscription", "GET", auth(key))
        val left = sub.optLong("character_limit") - sub.optLong("character_count")
        "Key works · ${"%,d".format(left.coerceAtLeast(0))} characters left this month"
    } catch (e: VoiceServiceException) {
        // A key limited to speech can't read the subscription: try the voice list instead.
        if (e.code != 401 && e.code != 403) throw e
        Http.json("https://api.elevenlabs.io/v2/voices?page_size=1", "GET", auth(key))
        "Key works"
    }

    override fun library(key: String, search: String): List<LibraryVoice> {
        val query = if (search.isBlank()) "" else "&search=${Http.enc(search.trim())}"
        val json = Http.json("https://api.elevenlabs.io/v2/voices?page_size=100$query", "GET", auth(key))
        val list = json.optJSONArray("voices") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }.map { v ->
            val labels = v.optJSONObject("labels") ?: JSONObject()
            val age = labels.optString("age").lowercase().let {
                when {
                    it.contains("old") -> "old"
                    it.contains("middle") -> "middle-aged"
                    it.contains("young") -> "young"
                    it.contains("child") || it.contains("kid") -> "child"
                    it.contains("teen") -> "teen"
                    else -> ""
                }
            }
            LibraryVoice(
                id = v.getString("voice_id"),
                name = v.optString("name").substringBefore(" - ").trim(),
                about = listOf(labels.optString("accent"), labels.optString("descriptive"), labels.optString("use_case"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                gender = labels.optString("gender").lowercase().takeIf { it == "male" || it == "female" }.orEmpty(),
                age = age,
                previewUrl = v.optString("preview_url").takeIf { it.startsWith("http") },
            )
        }
    }

    override fun speak(key: String, model: String, voice: VoicePick, bubble: Bubble, speed: Float, steady: Boolean, maxSeconds: Double): Pair<ByteArray, String> {
        val tags = !model.startsWith("eleven_flash") && !model.startsWith("eleven_multilingual")
        val body = JSONObject()
            .put("text", VoiceText.eleven(bubble, tags))
            .put("model_id", model)
            .put(
                "voice_settings",
                JSONObject().put("stability", if (steady) 0.8 else 0.5).put("similarity_boost", 0.75).put("speed", speed.coerceIn(0.7f, 1.2f).toDouble()),
            )
        val url = "https://api.elevenlabs.io/v1/text-to-speech/${Http.enc(voice.id)}?output_format=mp3_44100_128"
        val headers = auth(key) + ("Content-Type" to "application/json")
        return Http.call(url, "POST", headers, body.toString().toByteArray(), 30_000, mp3Bytes(maxSeconds)) to "mp3"
    }
}

/** Gemini's speech models, with Google's 30 built-in voices. */
object GeminiTts : CloudTts {
    override val provider = VoiceProviderId.GEMINI
    override val models = listOf("gemini-3.8-flash-tts" to "Expressive", "gemini-3.8-flash-lite-tts" to "Fast, cheaper")

    // Name, how Google describes it, and gender (from Google's Chirp 3 HD voice list).
    private val VOICES = listOf(
        Triple("Zephyr", "Bright", "female"), Triple("Puck", "Upbeat", "male"), Triple("Charon", "Informative", "male"),
        Triple("Kore", "Firm", "female"), Triple("Fenrir", "Excitable", "male"), Triple("Leda", "Youthful", "female"),
        Triple("Orus", "Firm", "male"), Triple("Aoede", "Breezy", "female"), Triple("Callirrhoe", "Easy-going", "female"),
        Triple("Autonoe", "Bright", "female"), Triple("Enceladus", "Breathy", "male"), Triple("Iapetus", "Clear", "male"),
        Triple("Umbriel", "Easy-going", "male"), Triple("Algieba", "Smooth", "male"), Triple("Despina", "Smooth", "female"),
        Triple("Erinome", "Clear", "female"), Triple("Algenib", "Gravelly", "male"), Triple("Rasalgethi", "Informative", "male"),
        Triple("Laomedeia", "Upbeat", "female"), Triple("Achernar", "Soft", "female"), Triple("Alnilam", "Firm", "male"),
        Triple("Schedar", "Even", "male"), Triple("Gacrux", "Mature", "female"), Triple("Pulcherrima", "Forward", "female"),
        Triple("Achird", "Friendly", "male"), Triple("Zubenelgenubi", "Casual", "male"), Triple("Vindemiatrix", "Gentle", "female"),
        Triple("Sadachbia", "Lively", "male"), Triple("Sadaltager", "Knowledgeable", "male"), Triple("Sulafat", "Warm", "female"),
    )

    private val defaults = mapOf(
        VoiceStyle.NARRATOR to "Charon", SpeakerRows.SYSTEM to "Iapetus", "man" to "Orus", "woman" to "Kore",
        "boy" to "Fenrir", "girl" to "Leda", "old man" to "Algenib", "old woman" to "Gacrux", "creature" to "Algenib",
    )

    override fun defaultVoice(type: String) = (defaults[type] ?: "Orus").let { VoicePick(it, it) }

    private fun auth(key: String) = mapOf("x-goog-api-key" to key)

    override fun test(key: String): String {
        Http.json("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1", "GET", auth(key))
        return "Key works"
    }

    override fun library(key: String, search: String): List<LibraryVoice> {
        val q = search.trim().lowercase()
        return VOICES.filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            .map { (name, about, gender) -> LibraryVoice(name, name, about, gender, "", null) }
    }

    override fun speak(key: String, model: String, voice: VoicePick, bubble: Bubble, speed: Float, steady: Boolean, maxSeconds: Double): Pair<ByteArray, String> {
        val styled = VoiceText.gemini(bubble)
        val pace = when {
            speed >= 1.3f -> "speaking quickly"
            speed <= 0.8f -> "speaking slowly"
            else -> ""
        }
        val style = listOf(styled.style, pace).filter { it.isNotEmpty() }.joinToString(", ")
        val textPart = JSONObject().put("type", "text").put("text", styled.text)
        if (style.isNotEmpty()) {
            textPart.put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(textPart))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice.id))))
        val reply = Http.json("https://generativelanguage.googleapis.com/v1beta/interactions", "POST", auth(key), body, 90_000)
        val data = lastAudio(reply) ?: throw IOException("Gemini sent no audio")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return (if (isWav(bytes)) bytes else Wav.fromPcm16(bytes, 24_000)) to "wav"
    }

    /** The last audio in the reply, wherever the API put it. */
    private fun lastAudio(node: Any?): String? {
        var found: String? = null
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val type = n.optString("type")
                    val mime = n.optString("mime_type", n.optString("mimeType"))
                    val data = n.optString("data")
                    if (data.length > 100 && (type == "audio" || mime.startsWith("audio"))) found = data
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
        return found
    }

    private fun isWav(bytes: ByteArray) = bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE"
}

/** The most bytes a 128 kbit/s MP3 of [seconds] can take, with room for headers. */
internal fun mp3Bytes(seconds: Double): Long =
    if (seconds == Double.MAX_VALUE) Long.MAX_VALUE else (seconds * 16_000 + 32_000).toLong()

/** A WAV header for raw 16-bit mono PCM, so the player can read it. */
object Wav {
    /** Seconds of audio in a clip: from the WAV header, or from the size of a 128 kbit/s MP3. */
    fun seconds(audio: ByteArray, ext: String): Double {
        if (ext == "wav" && audio.size > 44) {
            val header = java.nio.ByteBuffer.wrap(audio, 0, 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val byteRate = header.getInt(28)
            if (byteRate > 0) return (audio.size - 44).toDouble() / byteRate
        }
        // Fish and ElevenLabs are asked for 128 kbit/s MP3.
        return audio.size * 8.0 / 128_000
    }

    /**
     * The longest a line should take: about twice its length at a normal 14 characters a second,
     * plus 2.5 seconds, and never under 4 seconds (a real laugh or sob takes a moment). "Haa, haa"
     * gets 4 seconds, a 150-character line about 24. A clip past this is the voice stuck in a loop
     * or making noise.
     */
    fun limitSeconds(text: String, speed: Float): Double = maxOf(4.0, 2.5 + text.length / (7 * speed.coerceAtLeast(0.5f)))

    fun fromPcm16(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }
}
