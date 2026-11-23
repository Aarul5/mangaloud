package io.github.aarul5.mangaloud

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * Says one bubble at a time: with the phone's own voice, or with a clip from a cloud or PC voice
 * (see [ClipVoice]). When a clip fails or is slow, the phone voice reads the line instead, unless
 * that is turned off in Settings. Main thread only.
 *
 * [ReadAloud] decides what to say and when; this decides how it sounds.
 */
class Speaker(
    private val context: Context,
    private val settings: Settings,
    private val main: Handler,
    /** A line has been said, or passed over, and the next can start. Called with its id. */
    private val onFinished: (String) -> Unit,
    /** The phone has no working text-to-speech. */
    private val onUnavailable: (String) -> Unit,
) {
    /** A cloud or PC voice for this read: null when the phone's own voice reads. */
    private var clipVoice: ClipVoice? = null
    private var player: MediaPlayer? = null
    private var problemShown = false

    /** When the chosen voice fails, the phone voice reads; off stops reading instead. */
    private var fallbackToPhone = true

    /** The phone's voice engine has started. */
    var isReady = false
        private set

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        isReady = status == TextToSpeech.SUCCESS
        if (!isReady) onUnavailable("Text-to-speech is not available on this phone")
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = main.post { onFinished(utteranceId) }.let {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = main.post { onFinished(utteranceId) }.let {}
        })
    }

    /**
     * Sets up the voice chosen for [series] for a new read. Returns the reason reading cannot
     * start, or null. A voice that is not set up (no key, no relay) leaves the phone voice
     * reading, with a message, unless falling back is turned off.
     */
    fun prepare(series: String): String? {
        problemShown = false
        fallbackToPhone = VoiceSetup(context).fallbackToPhone
        clipVoice = try {
            createVoice(series)
        } catch (e: IllegalArgumentException) {
            if (!fallbackToPhone) return e.message ?: "Check Settings"
            showProblem(e.message ?: "The chosen voice is not set up, so the phone voice reads")
            null
        }
        return null
    }

    /** The voice's note on its last clip ("Voice: 1.4s"), for the details line, or null. */
    val details: String? get() = clipVoice?.lastDetails

    /** True when lines come as clips, which are worth asking for ahead of time. */
    val usesClips get() = clipVoice != null

    /** Starts making a line's clip before it is needed. */
    fun prefetch(bubble: Bubble) {
        clipVoice?.prefetch(bubble)
    }

    /**
     * Says [bubble] as line [id], then calls [onFinished] with [id]. [stillWanted] says whether this
     * is still the line to say (not paused, skipped or stopped while its clip was on the way).
     * [onFailed] gets the reason when the voice failed and the phone voice may not fill in.
     */
    fun say(bubble: Bubble, id: String, stillWanted: () -> Boolean, onFailed: (String) -> Unit) {
        val voice = clipVoice
        if (voice == null) {
            sayWithPhone(bubble, id)
        } else {
            waitForClip(voice, bubble, id, stillWanted, onFailed, SystemClock.uptimeMillis() + CLIP_WAIT_MS)
        }
    }

    /** Silences whichever voice is talking. */
    fun silence() {
        tts.stop()
        releasePlayer()
    }

    /** The read is over: lets go of the cloud or PC voice. */
    fun endRead() {
        clipVoice?.close()
        clipVoice = null
    }

    fun shutdown() {
        tts.shutdown()
    }

    /** The voice chosen for this series: a cloud voice, the PC, or null for the phone's own. */
    private fun createVoice(series: String): ClipVoice? {
        val setup = VoiceSetup(context)
        val provider = setup.providerFor(series)
        CloudTts.of(provider)?.let { return CloudVoice(context, it, setup, series, settings.speechRate) }
        if (provider != VoiceProviderId.PC) return null
        val engine = settings.voiceEngine.takeIf { it != VoiceEngine.PHONE } ?: VoiceEngine.PC_NORMAL
        require(settings.relayUrl.isNotBlank() && settings.relayToken.isNotBlank()) {
            "The PC voice needs a PC relay. Add its address and token in Settings, or choose the phone voice."
        }
        require(settings.allowPlainWifiRelay || RelayOcr.isEncryptedRoute(settings.relayUrl)) {
            "The relay address is not a Tailscale address, so the token would go over Wi-Fi unencrypted. " +
                "Use the 100.x address, or allow plain Wi-Fi in Settings."
        }
        return PcVoice(context, settings.relayUrl, settings.relayToken, engine.quality, settings.speechRate, settings.pcVoiceModel.id, series)
    }

    private fun sayWithPhone(bubble: Bubble, id: String) {
        // The phone voice would say "cough" or "sigh" as a word: leave a beat instead.
        if (bubble.kind == TextKind.REACTION && bubble.sound in TextKind.UNSPOKEN_SOUNDS) {
            main.postDelayed({ onFinished(id) }, REACTION_PAUSE_MS)
            return
        }
        val style = VoiceStyle.styleFor(bubble, settings.speechRate, settings.expressiveVoice)
        tts.setPitch(style.pitch)
        tts.setSpeechRate(style.rate)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, style.volume) }
        tts.speak(SpeechText.forSpeech(bubble.text), TextToSpeech.QUEUE_FLUSH, params, id)
    }

    /** Plays the clip once it arrives. A clip that fails, or is too slow, uses the phone voice (if allowed). */
    private fun waitForClip(
        voice: ClipVoice,
        bubble: Bubble,
        id: String,
        stillWanted: () -> Boolean,
        onFailed: (String) -> Unit,
        deadline: Long,
    ) {
        if (!stillWanted()) return
        if (voice.isUnreachable) {
            voiceFailed("Can't reach ${voice.name}", bubble, id, onFailed)
            return
        }
        val clip = try {
            voice.clipOrNull(bubble)
        } catch (e: Exception) {
            val reason = (e.cause ?: e).message ?: e.javaClass.simpleName
            Log.w(TAG, "${voice.name} failed: $reason")
            voiceFailed("${voice.name} failed: $reason", bubble, id, onFailed)
            return
        }
        when {
            clip != null -> play(clip, bubble, id, stillWanted)
            // Without the phone voice to fill in, a slow line is waited for.
            SystemClock.uptimeMillis() >= deadline && fallbackToPhone -> {
                showProblem("${voice.name} is slow, so the phone voice reads some lines")
                sayWithPhone(bubble, id)
            }
            else -> main.postDelayed({ waitForClip(voice, bubble, id, stillWanted, onFailed, deadline) }, CLIP_POLL_MS)
        }
    }

    /** The phone voice reads the line, or reading stops with the reason when that is turned off. */
    private fun voiceFailed(reason: String, bubble: Bubble, id: String, onFailed: (String) -> Unit) {
        if (fallbackToPhone) {
            showProblem("$reason. The phone voice reads for now.")
            sayWithPhone(bubble, id)
            return
        }
        onFailed(reason)
    }

    private fun play(clip: File, bubble: Bubble, id: String, stillWanted: () -> Boolean) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(clip.path)
            if (VoiceStyle.isQuietThought(bubble)) mp.setVolume(VoiceStyle.THOUGHT_VOLUME, VoiceStyle.THOUGHT_VOLUME)
            mp.setOnCompletionListener {
                if (player === mp) releasePlayer()
                onFinished(id)
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Clip playback failed: $what/$extra")
                if (player === mp) releasePlayer()
                if (stillWanted()) sayWithPhone(bubble, id)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not play clip: ${e.message}")
            if (player === mp) releasePlayer()
            clip.delete()
            sayWithPhone(bubble, id)
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    /** Says once per read why the phone voice is filling in, instead of switching silently. */
    private fun showProblem(message: String) {
        if (problemShown) return
        problemShown = true
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "Speaker"
        /** The beat left for a cough or sigh the phone voice cannot make. */
        private const val REACTION_PAUSE_MS = 600L
        /** How long a bubble waits for its clip before the phone voice reads it. */
        private const val CLIP_WAIT_MS = 8_000L
        private const val CLIP_POLL_MS = 100L
    }
}
