package io.github.aarul5.mangaloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Acted speech from the voice server on the PC, through the relay's /speak. Clips are fetched one at
 * a time in reading order, a few bubbles ahead of the voice, and kept on the phone so a
 * chapter read again plays straight away.
 */
class PcVoice(
    context: Context,
    private val relayUrl: String,
    private val token: String,
    private val quality: String,
    private val speed: Float,
    /** The speech model on the PC, like "qwen3-tts". */
    private val model: String,
    /** The series' name, so the relay gives each of its characters a voice of their own. */
    private val series: String,
) : ClipVoice {
    override val name = "PC voice"
    private val dir = File(context.cacheDir, "pc-voice").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pc-voice").apply { isDaemon = true } }
    private val jobs = ConcurrentHashMap<String, Future<File>>()

    @Volatile
    private var closed = false

    /** When the relay last could not be reached (elapsedRealtime), or 0 while it can. */
    @Volatile
    private var unreachableAt = 0L

    /**
     * True for a while after the relay could not be reached (PC off, Tailscale down), so each
     * line goes to the phone voice at once instead of waiting for a clip that will not come.
     * After [RETRY_AFTER_MS] the next line tries the PC again.
     */
    override val isUnreachable: Boolean
        get() = unreachableAt != 0L && SystemClock.elapsedRealtime() - unreachableAt < RETRY_AFTER_MS

    init {
        // Finds a missing relay before the first line, instead of on it.
        executor.execute { checkReachable() }
    }

    /** Starts fetching a bubble's clip, unless it is already here or on the way. */
    override fun prefetch(bubble: Bubble) {
        if (closed || isUnreachable) return
        val key = keyOf(bubble)
        jobs.computeIfAbsent(key) { executor.submit(Callable { fetch(bubble, key) }) }
    }

    /** The clip when it is ready, or null while it is on the way. Throws if fetching it failed. */
    override fun clipOrNull(bubble: Bubble): File? {
        val key = keyOf(bubble)
        val job = jobs[key]
        if (job == null) {
            prefetch(bubble)
            return null
        }
        if (!job.isDone) return null
        return try {
            job.get()
        } catch (e: ExecutionException) {
            // Forget the failure, so the line can be tried again once the PC is back.
            jobs.remove(key, job)
            throw e
        }
    }

    private fun markUnreachable(e: Exception) {
        if (unreachableAt == 0L) Log.w(TAG, "Relay unreachable, using the phone voice for now: ${e.message}")
        unreachableAt = SystemClock.elapsedRealtime()
    }

    private fun checkReachable() {
        val connection = URL(relayUrl.trimEnd('/') + "/health").openConnection() as HttpURLConnection
        connection.connectTimeout = HEALTH_TIMEOUT_MS
        connection.readTimeout = HEALTH_TIMEOUT_MS
        try {
            connection.responseCode // Any answer at all means the relay is there.
            unreachableAt = 0L
        } catch (e: IOException) {
            markUnreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
        jobs.clear()
    }

    private fun fetch(bubble: Bubble, key: String): File {
        val file = File(dir, "$key.wav")
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        // Lines queued before the relay went missing fail at once instead of each timing out.
        if (isUnreachable) throw IOException("The PC relay cannot be reached")
        val body = JSONObject()
            .put("text", textFor(bubble))
            .put("tone", bubble.tone)
            .put("intensity", bubble.intensity)
            .put("speaker", bubble.speaker)
            .put("character", bubble.character)
            .put("series", series)
            .put("quality", quality)
            .put("model", model)
            .put("kind", bubble.kind)
            .put("sound", bubble.sound)
            .put("speed", speed.toDouble())
            .toString()
            .toByteArray()

        val connection = URL(relayUrl.trimEnd('/') + "/speak").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            try {
                connection.connect()
            } catch (e: IOException) {
                markUnreachable(e)
                throw IOException("The PC relay cannot be reached: ${e.message}", e)
            }
            unreachableAt = 0L
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != 200) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(detail).getString("error") }.getOrDefault(detail)
                throw IOException("PC voice error $code: ${message.take(200)}")
            }
            val part = File(dir, "$key.part")
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(file)) throw IOException("Could not save the voice clip")
            pruneNow()
            return file
        } finally {
            connection.disconnect()
        }
    }

    /** The voice server uses punctuation for pauses and feeling, so it gets the bubble's own text. */
    private fun textFor(bubble: Bubble) = bubble.text.replace(SPACES, " ").trim()

    private fun keyOf(bubble: Bubble): String {
        val character = if (bubble.character.isEmpty()) "" else "$series#${bubble.character.lowercase()}"
        val id = listOf(textFor(bubble), bubble.tone, bubble.intensity, bubble.speaker, character, quality, speed, relayUrl, model, bubble.kind, bubble.sound).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private var fetchedSincePrune = 0

    private fun pruneNow() {
        if (++fetchedSincePrune < 50) return
        fetchedSincePrune = 0
        val clips = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.lastModified() } ?: return
        if (clips.size > MAX_CLIPS) clips.take(clips.size - KEEP_CLIPS).forEach { it.delete() }
    }

    companion object {
        /** Deletes the clips saved on the phone, after a character's voice changes on the relay. */
        fun clearSavedClips(context: Context) {
            File(context.cacheDir, "pc-voice").listFiles()?.forEach { it.delete() }
        }

        private const val TAG = "PcVoice"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HEALTH_TIMEOUT_MS = 3_000
        /** How long lines stay on the phone voice before the PC is tried again. */
        private const val RETRY_AFTER_MS = 30_000L
        private val SPACES = Regex("""\s+""")
        private const val MAX_CLIPS = 600
        private const val KEEP_CLIPS = 450
    }
}
