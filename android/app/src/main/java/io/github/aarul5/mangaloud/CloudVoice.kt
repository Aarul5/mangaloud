package io.github.aarul5.mangaloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A voice that makes a clip for each bubble, fetched a few bubbles ahead of reading. */
interface ClipVoice {
    /** What to call it in a message: "Fish Audio", "PC voice". */
    val name: String

    /** True for a while after it could not be reached, so lines go to the phone voice at once. */
    val isUnreachable: Boolean

    /** A short note on the last clip, such as how long it took, or null. */
    val lastDetails: String? get() = null

    fun prefetch(bubble: Bubble)

    /** The clip when it is ready, or null while it is on the way. Throws if making it failed. */
    fun clipOrNull(bubble: Bubble): File?

    fun close()
}

/**
 * A cloud voice (Fish Audio, ElevenLabs, Gemini) called straight from the phone. Each line's
 * voice comes from [VoiceSetup]: the character's, the series', or your usual one. Clips are kept
 * on the phone, so reading a chapter again costs nothing.
 */
class CloudVoice(
    context: Context,
    private val tts: CloudTts,
    private val setup: VoiceSetup,
    private val seriesKey: String,
    private val speed: Float,
) : ClipVoice {
    private val key = setup.key(tts.provider)
    private val model = setup.model(tts.provider)
    private val dir = File(context.cacheDir, "cloud-voice").apply { mkdirs() }
    // Three at a time: well ahead of the voice, under Fish's limit of five at once.
    private val executor = Executors.newFixedThreadPool(3) { r -> Thread(r, "cloud-voice").apply { isDaemon = true } }
    private val jobs = ConcurrentHashMap<String, Future<File>>()

    @Volatile
    private var closed = false

    @Volatile
    private var unreachableAt = 0L

    /** Finding default voices in the library, while it runs. */
    @Volatile
    private var defaults: Future<*>? = null

    override val name get() = tts.provider.label

    /** How long the last clip took to make, for the details line under the reading bar. */
    @Volatile
    override var lastDetails: String? = null
        private set

    override val isUnreachable: Boolean
        get() = unreachableAt != 0L && SystemClock.elapsedRealtime() - unreachableAt < RETRY_AFTER_MS

    init {
        require(key.isNotEmpty()) { "${tts.provider.label} needs an API key. Add it in Settings › Voice, or choose another voice." }
        // ElevenLabs voices differ per account: pick defaults from the library before the first line.
        if (tts.defaultVoice(VoiceStyle.NARRATOR) == null && SpeakerRows.TYPES.any { setup.pickedUsual(tts.provider, it) == null }) {
            defaults = executor.submit { runCatching { fillDefaults() }.onFailure { Log.w(TAG, "No default voices yet: ${it.message}") } }
        }
    }

    private fun fillDefaults() {
        val library = tts.library(key, "")
        if (tts is ElevenLabsTts) setup.fillMissingUsual(tts.provider, tts.chooseDefaults(library))
    }

    override fun prefetch(bubble: Bubble) {
        if (closed || isUnreachable) return
        val voice = voiceOf(bubble) ?: return
        val id = keyOf(bubble, voice)
        jobs.computeIfAbsent(id) { executor.submit(Callable { fetch(bubble, voice, id) }) }
    }

    override fun clipOrNull(bubble: Bubble): File? {
        val voice = voiceOf(bubble) ?: run {
            // Default voices are still being found in the library: wait for them.
            if (defaults?.isDone == false) return null
            throw IOException("No ${tts.provider.label} voice is chosen for ${SpeakerRows.label(SpeakerRows.typeOf(bubble))}. Pick one in Settings › Voice.")
        }
        val id = keyOf(bubble, voice)
        val job = jobs[id]
        if (job == null) {
            prefetch(bubble)
            return null
        }
        if (!job.isDone) return null
        return try {
            job.get()
        } catch (e: ExecutionException) {
            jobs.remove(id, job)
            throw e
        }
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
        jobs.clear()
    }

    private fun voiceOf(bubble: Bubble) = setup.voiceFor(seriesKey, tts.provider, bubble)

    private fun fetch(bubble: Bubble, voice: VoicePick, id: String): File {
        dir.listFiles { f -> f.name.startsWith("$id.") && !f.name.endsWith(".part") }?.firstOrNull { it.length() > 0 }?.let {
            it.setLastModified(System.currentTimeMillis())
            return it
        }
        if (isUnreachable) throw IOException("${tts.provider.label} cannot be reached")
        val (audio, ext) = try {
            speakChecked(bubble, voice)
        } catch (e: VoiceServiceException) {
            throw e
        } catch (e: IOException) {
            // No connection at all: the next lines go to the phone voice for a while.
            if (unreachableAt == 0L) Log.w(TAG, "${tts.provider.label} unreachable: ${e.message}")
            unreachableAt = SystemClock.elapsedRealtime()
            throw e
        }
        unreachableAt = 0L
        if (audio.isEmpty()) throw IOException("${tts.provider.label} sent an empty clip")
        val part = File(dir, "$id.part")
        part.writeBytes(audio)
        val file = File(dir, "$id.$ext")
        if (!part.renameTo(file)) throw IOException("Could not save the voice clip")
        prune()
        return file
    }

    /**
     * A clip far longer than its line is the voice wandering into noise or a stuck loop (it once
     * made 41 seconds for a three-word game window). Try once more, steadier (for Fish, the plain
     * words with no cues); if that runs away too, fail so the phone voice reads the line.
     */
    private fun speakChecked(bubble: Bubble, voice: VoicePick): Pair<ByteArray, String> {
        val limit = Wav.limitSeconds(bubble.text, speed)
        val started = SystemClock.elapsedRealtime()
        // A runaway clip is stopped as soon as it passes the limit, instead of waiting for all of
        // it to arrive while the next lines queue behind it.
        val first = try {
            tts.speak(key, model, voice, bubble, speed, maxSeconds = limit)
        } catch (e: ClipTooLong) {
            null
        }
        if (first != null && Wav.seconds(first.first, first.second) <= limit) return timed(first, started)
        Log.w(TAG, "${tts.provider.label} ran on past ${"%.0f".format(limit)}s for a ${bubble.text.length}-character line; trying again")
        val again = try {
            tts.speak(key, model, voice, bubble, speed, steady = true, maxSeconds = limit)
        } catch (e: ClipTooLong) {
            null
        }
        if (again != null && Wav.seconds(again.first, again.second) <= limit) return timed(again, started)
        throw VoiceServiceException(0, "${tts.provider.label} kept running on past the line")
    }

    private fun timed(clip: Pair<ByteArray, String>, started: Long): Pair<ByteArray, String> {
        lastDetails = "Voice: ${"%.1f".format((SystemClock.elapsedRealtime() - started) / 1000.0)}s"
        return clip
    }

    private fun keyOf(bubble: Bubble, voice: VoicePick): String {
        val id = listOf(CLIP_VERSION, tts.provider.id, model, voice.id, bubble.text, bubble.tone, bubble.intensity, bubble.speaker, bubble.kind, bubble.sound, speed)
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }

    @Volatile
    private var savedSincePrune = 0

    private fun prune() {
        if (++savedSincePrune < 50) return
        savedSincePrune = 0
        val clips = dir.listFiles { f -> !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        if (clips.size > MAX_CLIPS) clips.take(clips.size - KEEP_CLIPS).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "CloudVoice"
        private const val RETRY_AFTER_MS = 30_000L
        /** Bumped when the text sent to cloud voices changes, so clips made the old way are made again. */
        private const val CLIP_VERSION = 4
        private const val MAX_CLIPS = 800
        private const val KEEP_CLIPS = 600
    }
}
