package io.github.aarul5.mangaloud

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One character in a series' cast list on the relay, as /cast describes them. */
data class CastEntry(
    val key: String,
    val name: String,
    val speaker: String,
    val variant: Int,
    val voice: String,
    val soft: Boolean,
    val playful: Boolean,
    val sameAs: String,
    /** A short description of how they sound, from the relay. */
    val sounds: String,
)

/** A series' characters, and the voices each speaker type can have (index 0 is the plain one). */
data class CastList(val series: String, val characters: List<CastEntry>, val voices: Map<String, List<String>>)

/** Reads and changes the relay's cast lists (PC voice). Call off the main thread. */
class RelayCast(private val relayUrl: String, private val token: String) {

    fun load(series: String): CastList {
        val json = JSONObject(request("GET", "/cast?series=" + URLEncoder.encode(series, "UTF-8"), null))
        val array = json.getJSONArray("characters")
        val characters = (0 until array.length()).map { entryFrom(array.getJSONObject(it)) }
        val voicesJson = json.getJSONObject("voices")
        val voices = voicesJson.keys().asSequence().associateWith { speaker ->
            val list = voicesJson.getJSONArray(speaker)
            (0 until list.length()).map { list.getString(it) }
        }
        return CastList(json.getString("series"), characters, voices)
    }

    /** Saves a character's voice; returns them as the relay now has them. */
    fun update(series: String, entry: CastEntry): CastEntry {
        val body = JSONObject()
            .put("series", series)
            .put("key", entry.key)
            .put("speaker", entry.speaker)
            .put("variant", entry.variant)
            .put("voice", entry.voice)
            .put("soft", entry.soft)
            .put("playful", entry.playful)
            .put("sameAs", entry.sameAs)
        return entryFrom(JSONObject(request("POST", "/cast", body.toString())))
    }

    /** A short line in the character's voice, saved to [into]. */
    fun sample(series: String, entry: CastEntry, quality: String, into: File): File {
        val body = JSONObject()
            .put("text", "Hey. This is how I sound. Do you like it?")
            .put("tone", "neutral")
            .put("speaker", entry.speaker)
            .put("character", entry.name)
            .put("series", series)
            .put("quality", quality.ifBlank { "normal" })
            .put("speed", 1.0)
        val connection = open("POST", "/speak")
        try {
            send(connection, body.toString())
            check(connection)
            connection.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
            return into
        } finally {
            connection.disconnect()
        }
    }

    private fun entryFrom(o: JSONObject) = CastEntry(
        key = o.getString("key"),
        name = o.optString("name", o.getString("key")),
        speaker = o.getString("speaker"),
        variant = o.optInt("variant", 0),
        voice = o.optString("voice", ""),
        soft = o.optBoolean("soft", false),
        playful = o.optBoolean("playful", false),
        sameAs = o.optString("sameAs", ""),
        sounds = o.optString("sounds", ""),
    )

    private fun request(method: String, path: String, body: String?): String {
        val connection = open(method, path)
        try {
            if (body != null) send(connection, body)
            check(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(relayUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun send(connection: HttpURLConnection, body: String) {
        val bytes = body.toByteArray()
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(bytes) }
    }

    private fun check(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code == 200) return
        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val message = runCatching { JSONObject(detail).getString("error") }.getOrDefault(detail)
        // A relay from before character voices has no /cast at all.
        if (code == 404 && message == "Not found") {
            throw IOException("This relay is too old for character voices. Update the relay on the PC.")
        }
        throw IOException("Relay error $code: ${message.take(200)}")
    }
}
