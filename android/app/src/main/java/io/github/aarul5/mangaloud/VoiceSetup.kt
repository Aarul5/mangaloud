package io.github.aarul5.mangaloud

import android.content.Context
import org.json.JSONObject

/**
 * Which voice reads what. Your usual voices apply to every series; a series can change its
 * provider, any speaker type's voice, and give characters their own voice. A series keeps only
 * what was changed, so it follows later changes to your usual voices.
 *
 * Order for a line: the character's own voice, then the series' voice for that speaker type,
 * then your usual voice for it, then the provider's default.
 */
class VoiceSetup(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("voices", Context.MODE_PRIVATE)
    private val secrets = SecretStore(app)
    private val settings = Settings(app)

    /** The usual provider. Installs from before keep what they had: the phone voice or the PC. */
    var provider: VoiceProviderId
        get() = VoiceProviderId.of(prefs.getString("provider", null))
            ?: if (settings.voiceEngine == VoiceEngine.PHONE) VoiceProviderId.PHONE else VoiceProviderId.PC
        set(value) {
            prefs.edit().putString("provider", value.id).apply()
            // The PC voice reads its quality from voiceEngine, which must not say "phone".
            if (value == VoiceProviderId.PC && settings.voiceEngine == VoiceEngine.PHONE) settings.voiceEngine = VoiceEngine.PC_NORMAL
        }

    /** When a voice fails (no key, no signal, out of credit), the phone voice reads instead of stopping. */
    var fallbackToPhone: Boolean
        get() = prefs.getBoolean("fallbackToPhone", true)
        set(value) = prefs.edit().putBoolean("fallbackToPhone", value).apply()

    fun key(provider: VoiceProviderId): String = secrets.get("key.${provider.id}")
    fun setKey(provider: VoiceProviderId, key: String) = secrets.set("key.${provider.id}", key)
    fun hasKey(provider: VoiceProviderId) = key(provider).isNotEmpty()

    fun model(provider: VoiceProviderId): String {
        val models = CloudTts.of(provider)?.models.orEmpty()
        val saved = prefs.getString("model.${provider.id}", null)
        return models.firstOrNull { it.first == saved }?.first ?: models.firstOrNull()?.first.orEmpty()
    }

    fun setModel(provider: VoiceProviderId, model: String) = prefs.edit().putString("model.${provider.id}", model).apply()

    /** Ready to read: the phone always is; a cloud voice needs its key; the PC needs a relay. */
    fun isReady(provider: VoiceProviderId): Boolean = when {
        provider == VoiceProviderId.PHONE -> true
        provider == VoiceProviderId.PC -> settings.relayUrl.isNotBlank() && settings.relayToken.isNotBlank()
        else -> hasKey(provider)
    }

    // Usual voices

    /** Your usual voice for a speaker type, as picked, or null when none was picked. */
    fun pickedUsual(provider: VoiceProviderId, type: String): VoicePick? =
        VoicePick.from(usualJson(provider).optJSONObject(type))

    fun usual(provider: VoiceProviderId, type: String): VoicePick? =
        pickedUsual(provider, type) ?: CloudTts.of(provider)?.defaultVoice(type)

    fun setUsual(provider: VoiceProviderId, type: String, pick: VoicePick?) {
        val json = usualJson(provider)
        if (pick == null) json.remove(type) else json.put(type, pick.toJson())
        prefs.edit().putString("usual.${provider.id}", json.toString()).apply()
    }

    /** Fills speaker types with no voice yet, for providers whose defaults come from the library. */
    fun fillMissingUsual(provider: VoiceProviderId, picks: Map<String, VoicePick>) {
        val json = usualJson(provider)
        var changed = false
        for ((type, pick) in picks) if (!json.has(type)) {
            json.put(type, pick.toJson())
            changed = true
        }
        if (changed) prefs.edit().putString("usual.${provider.id}", json.toString()).apply()
    }

    private fun usualJson(provider: VoiceProviderId) =
        runCatching { JSONObject(prefs.getString("usual.${provider.id}", "{}")!!) }.getOrDefault(JSONObject())

    // Series

    fun series(key: String, name: String = ""): SeriesVoices {
        val json = runCatching { JSONObject(prefs.getString("series.$key", "{}")!!) }.getOrDefault(JSONObject())
        return SeriesVoices.from(key, json, name)
    }

    fun saveSeries(series: SeriesVoices) {
        val editor = prefs.edit()
        if (series.isEmpty) editor.remove("series.${series.key}") else editor.putString("series.${series.key}", series.toJson().toString())
        editor.apply()
    }

    /** Series with any voice of their own, by name. */
    fun seriesWithVoices(): List<SeriesVoices> = prefs.all.keys.filter { it.startsWith("series.") }
        .map { series(it.removePrefix("series.")) }.filter { !it.isEmpty }.sortedBy { it.name.lowercase() }

    fun providerFor(seriesKey: String): VoiceProviderId = series(seriesKey).provider ?: provider

    /** The voice for a line of a series, on [provider]. */
    fun voiceFor(seriesKey: String, provider: VoiceProviderId, bubble: Bubble): VoicePick? {
        val type = SpeakerRows.typeOf(bubble)
        val own = series(seriesKey).on(provider)
        if (bubble.character.isNotEmpty()) own.characters[bubble.character.lowercase()]?.let { return it.pick }
        return own.speakers[type] ?: usual(provider, type)
    }
}

/** A character's own voice in a series, with their name as first shown. */
data class CharacterVoice(val name: String, val pick: VoicePick)

/** One provider's voices in a series: by speaker type, and by character (keyed by lower-case name). */
data class ProviderVoices(val speakers: Map<String, VoicePick> = emptyMap(), val characters: Map<String, CharacterVoice> = emptyMap()) {
    val isEmpty get() = speakers.isEmpty() && characters.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("speakers", JSONObject().apply { speakers.forEach { (k, v) -> put(k, v.toJson()) } })
        .put("characters", JSONObject().apply { characters.forEach { (k, v) -> put(k, v.pick.toJson().put("character", v.name)) } })

    companion object {
        fun from(o: JSONObject?): ProviderVoices {
            if (o == null) return ProviderVoices()
            val speakers = o.optJSONObject("speakers")?.let { s -> s.keys().asSequence().mapNotNull { k -> VoicePick.from(s.optJSONObject(k))?.let { k to it } }.toMap() }.orEmpty()
            val characters = o.optJSONObject("characters")?.let { c ->
                c.keys().asSequence().mapNotNull { k ->
                    val entry = c.optJSONObject(k)
                    VoicePick.from(entry)?.let { k to CharacterVoice(entry!!.optString("character", k), it) }
                }.toMap()
            }.orEmpty()
            return ProviderVoices(speakers, characters)
        }
    }
}

/** A series' own voices. Only what was changed is kept. */
data class SeriesVoices(
    val key: String,
    val name: String,
    /** A provider for this series only, or null to use your usual one. */
    val provider: VoiceProviderId?,
    val voices: Map<VoiceProviderId, ProviderVoices>,
) {
    val isEmpty get() = provider == null && voices.values.all { it.isEmpty }

    fun on(provider: VoiceProviderId) = voices[provider] ?: ProviderVoices()

    fun withProvider(provider: VoiceProviderId?) = copy(provider = provider)

    fun withSpeaker(provider: VoiceProviderId, type: String, pick: VoicePick?): SeriesVoices {
        val now = on(provider)
        val speakers = if (pick == null) now.speakers - type else now.speakers + (type to pick)
        return copy(voices = voices + (provider to now.copy(speakers = speakers)))
    }

    fun withCharacter(provider: VoiceProviderId, character: String, pick: VoicePick?): SeriesVoices {
        val now = on(provider)
        val key = character.lowercase()
        val characters = if (pick == null) now.characters - key else now.characters + (key to CharacterVoice(character, pick))
        return copy(voices = voices + (provider to now.copy(characters = characters)))
    }

    /** "ElevenLabs · 2 speaker voices · 3 characters", for the list of series. */
    fun summary(usualProvider: VoiceProviderId): String {
        val p = provider ?: usualProvider
        val mine = on(p)
        val parts = mutableListOf(if (provider == null) "Usual provider" else p.label)
        if (mine.speakers.isNotEmpty()) parts += "${mine.speakers.size} speaker voice${if (mine.speakers.size == 1) "" else "s"}"
        if (mine.characters.isNotEmpty()) parts += "${mine.characters.size} character${if (mine.characters.size == 1) "" else "s"}"
        return parts.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("provider", provider?.id ?: "")
        .put("voices", JSONObject().apply { voices.filterValues { !it.isEmpty }.forEach { (p, v) -> put(p.id, v.toJson()) } })

    companion object {
        fun from(key: String, json: JSONObject, name: String = ""): SeriesVoices {
            val voices = json.optJSONObject("voices")?.let { v ->
                v.keys().asSequence().mapNotNull { id -> VoiceProviderId.of(id)?.let { it to ProviderVoices.from(v.optJSONObject(id)) } }.toMap()
            }.orEmpty()
            return SeriesVoices(
                key = key,
                name = name.ifBlank { json.optString("name") }.ifBlank { key.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() } },
                provider = VoiceProviderId.of(json.optString("provider")),
                voices = voices,
            )
        }
    }
}
