package io.github.aarul5.mangaloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs a chapter read-through. A worker thread downloads, slices and OCRs the images just ahead
 * of the voice; the main thread speaks one bubble at a time and glides the page to it.
 *
 * Everything read is kept in a [ChapterStore] for the whole read. Scrolling by hand only moves
 * the reading position: a stretch already read is spoken straight away, and the worker is sent
 * to a stretch that is not, so nothing is downloaded or sent to Claude twice.
 */
class ReadAloud(
    private val context: Context,
    private val page: PageScript,
    private val settings: Settings,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: State)
        fun onStatus(text: String)

        /** The bubble being read now, for showing who speaks; null when none is. */
        fun onLine(bubble: Bubble?) {}

        /** Which model read the last batch and how long it took, or null before the first. */
        fun onDetails(text: String?) {}

        /** The voice read the chapter to its end. [nextUrl] is the page's "Next" link, if it has one. */
        fun onChapterFinished(nextUrl: String?) {}
    }

    enum class State { IDLE, PREPARING, READING, PAUSED }

    var state = State.IDLE
        private set(value) {
            field = value
            listener.onStateChanged(value)
        }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Reasons reading is held without the user pausing it: [HOLD_AWAY] while the app is off
     * screen, [HOLD_AUDIO] while a call or another app has the sound. Reading carries on by
     * itself once every hold is gone, if it was going when the first one came.
     */
    private val holds = mutableSetOf<String>()
    private var resumeWhenFree = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Started during a call: wait for it to end instead of failing.
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change -> onAudioFocusChange(change) }, main)
            .build()
    private var hasFocus = false
    private val store = ChapterStore()
    private val panels = PanelMap()
    private var imageCount = 0

    // Panels with no bubbles between the last bubble and the current one, shown before it is read.
    private val artStops = ArrayDeque<DoubleArray>()
    /** Bumped whenever the art stops start again or are dropped, so an older timer does nothing. */
    private var artToken = 0

    // Reading position. After a seek, seekPos says where; otherwise it follows lastSpoken.
    private var lastSpoken: Entry? = null
    private var seekPos: ChapterPos? = null
    private var current: Entry? = null
    private var currentUtterance: String? = null
    private var utteranceCounter = 0
    private var waitingForBubbles = false
    private var failure: String? = null
    private var workerFailed = false
    /** Images that would not load and were passed over, 0-based, named when the chapter ends. */
    private val skippedImages = sortedSetOf<Int>()

    /** The chapter's "Next" link, for going on when it ends. */
    private var chapterNext: String? = null

    private var userScrolling = false
    private val afterUserScroll = Runnable { onUserScrollSettled() }

    // Shared with the worker.
    @Volatile
    private var workerTarget: ChapterPos = 0.0
    private val bubblesAhead = AtomicInteger(0)

    /** Bumped on every stop, so work from an older run drops itself. */
    @Volatile
    private var session = 0

    /**
     * Bumped on every stop, so reading the next chapter early stops too. Kept apart from
     * [session], which also ends when the voice finishes the chapter: the early read should
     * carry on then, since that is when the next chapter is about to be opened.
     */
    @Volatile
    private var earlyRun = 0

    /**
     * Slices a request is reading right now, by [OcrCache.keyOf]. Another request that wants one
     * of them waits for that answer instead of paying for the same slice twice: pressing Read
     * while the page-open read is still going, say.
     */
    private val readingNow = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    /** The last batch's model and time, passed on with each status for the screen to show. */
    @Volatile
    private var readingDetails: String? = null

    private fun status(text: String) {
        listener.onStatus(text)
        listener.onDetails(readingDetails)
    }

    // A cloud or PC voice: null when the phone's own voice reads.
    private var clipVoice: ClipVoice? = null
    private var player: MediaPlayer? = null
    private var voiceProblemShown = false

    /** When the chosen voice fails, the phone voice reads; off stops reading instead. */
    private var fallbackToPhone = true

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) listener.onStatus("Text-to-speech is not available on this phone")
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = main.post { onUtteranceFinished(utteranceId) }.let {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = main.post { onUtteranceFinished(utteranceId) }.let {}
        })
    }

    fun start(pageUrl: String, userAgent: String) {
        stop()
        if (!ttsReady) {
            listener.onStatus("Voice engine is still starting. Try again in a moment.")
            return
        }
        val provider = try {
            OcrProviders.create(context, settings)
        } catch (e: IllegalArgumentException) {
            listener.onStatus(e.message ?: "Check Settings")
            return
        }
        val cast = SeriesCast(context, SeriesCast.seriesKeyOf(pageUrl))
        voiceProblemShown = false
        fallbackToPhone = VoiceSetup(context).fallbackToPhone
        clipVoice = try {
            createVoice(cast.seriesKey)
        } catch (e: IllegalArgumentException) {
            if (!fallbackToPhone) {
                listener.onStatus(e.message ?: "Check Settings")
                return
            }
            // No key or no relay: read with the phone voice, and say why.
            showVoiceProblem(e.message ?: "The chosen voice is not set up, so the phone voice reads")
            null
        }
        val run = session
        state = State.PREPARING
        requestFocus()
        // Read pressed just as the app went to the background: start once it is back.
        if (holds.isNotEmpty()) resumeWhenFree = true
        listener.onStatus("Finding the chapter images…")
        page.collectChapter { chapter ->
            if (run != session) return@collectChapter
            if (chapter.images.isEmpty()) {
                listener.onStatus("No comic images found on this page")
                state = State.IDLE
                return@collectChapter
            }
            listener.onStatus("Found ${chapter.images.size} images. Reading from image ${chapter.startIndex + 1}…")
            imageCount = chapter.images.size
            chapterNext = chapter.next
            val startPos = clampPos(chapter.startIndex + chapter.startFrac)
            seekPos = startPos
            workerTarget = startPos
            waitingForBubbles = true
            val rightToLeft = settings.rightToLeft
            thread(name = "ocr-worker", isDaemon = true) {
                runWorker(run, chapter.images, provider, rightToLeft, pageUrl, userAgent, cast, chapter.next)
            }
        }
    }

    /**
     * Starts reading by itself when the page is a chapter: enough images, and a "Next" link or a
     * chapter-like address. Otherwise [onNotChapter] runs, so the page can still be read ahead.
     */
    fun startIfChapter(pageUrl: String, userAgent: String, onNotChapter: () -> Unit) {
        if (state != State.IDLE) return
        val early = earlyRun
        page.collectChapter { chapter ->
            if (early != earlyRun || state != State.IDLE) return@collectChapter
            val isChapter = chapter.images.size >= MIN_NEXT_IMAGES &&
                (chapter.next != null || SeriesCast.looksLikeChapter(pageUrl))
            if (isChapter) start(pageUrl, userAgent) else onNotChapter()
        }
    }

    /**
     * Reads the start of a chapter's text when its page opens, so pressing Read starts at once.
     * Only for pages that look like a chapter, and stops when reading starts or the page changes.
     */
    fun readOnOpen(pageUrl: String, userAgent: String) {
        if (!settings.readOnOpen || state != State.IDLE) return
        val provider = try {
            OcrProviders.create(context, settings)
        } catch (e: IllegalArgumentException) {
            return // Not set up yet; Read will say what is missing.
        }
        val early = earlyRun
        page.collectChapter { chapter ->
            if (early != earlyRun || state != State.IDLE) return@collectChapter
            if (chapter.images.size < MIN_NEXT_IMAGES) return@collectChapter
            if (chapter.next == null && !SeriesCast.looksLikeChapter(pageUrl)) return@collectChapter
            val cast = SeriesCast(context, SeriesCast.seriesKeyOf(pageUrl))
            val sources = chapter.images.drop(chapter.startIndex).map { it.src }
            val rightToLeft = settings.rightToLeft
            val batchSlices = settings.batchSlices
            thread(name = "read-on-open", isDaemon = true) {
                try {
                    val read = readStart(early, sources, pageUrl, userAgent, provider, rightToLeft, cast, batchSlices, OPEN_SLICES)
                    Log.d(TAG, "Page open: read $read slices early from $pageUrl")
                } catch (e: InterruptedException) {
                    // App closing.
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the chapter on open: ${e.message}")
                }
            }
        }
    }

    /** Holds reading for [reason] (see [holds]); it pauses now if it was going. */
    fun hold(reason: String) {
        holds += reason
        when (state) {
            State.READING -> {
                pause()
                resumeWhenFree = true
            }
            State.PREPARING -> resumeWhenFree = true
            else -> Unit
        }
    }

    /** Lifts one hold; with none left, reading carries on if a hold paused it. */
    fun release(reason: String) {
        if (!holds.remove(reason)) return
        // Back in the app after another app took the sound for good: ask for it again.
        if (reason == HOLD_AWAY && HOLD_AUDIO in holds && state != State.IDLE) requestFocus()
        if (holds.isNotEmpty() || !resumeWhenFree) return
        resumeWhenFree = false
        resume()
    }

    private fun requestFocus() {
        when (audioManager.requestAudioFocus(focusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                holds -= HOLD_AUDIO
            }
            // In a call: reading waits, and starts when the call ends.
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> hold(HOLD_AUDIO)
            else -> Unit // Refused: read anyway rather than not at all.
        }
    }

    private fun abandonFocus() {
        if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        holds -= HOLD_AUDIO
    }

    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                release(HOLD_AUDIO)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app is playing now. Pause until the reader comes back to this app.
                hasFocus = false
                hold(HOLD_AUDIO)
            }
            // A call, an alarm, a voice note: pause until it is over.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> hold(HOLD_AUDIO)
            // A notification or navigation prompt: Android lowers the volume for it, no pause.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
        }
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

    /** Silences whichever voice is talking. */
    private fun stopSpeaking() {
        tts.stop()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    fun pause() {
        if (state != State.READING) return
        state = State.PAUSED
        currentUtterance = null
        stopSpeaking()
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.READING
        if (current != null) speakCurrent() else speakNext()
    }

    /** Drops the bubble being read and moves to the next one. Also resumes if paused. */
    fun skip() {
        if (state != State.READING && state != State.PAUSED) return
        // No current bubble means the move to the next one is already under way.
        if (current == null) return
        // Showing the art before a bubble: skip goes straight to the bubble.
        if (artStops.isNotEmpty()) {
            dropArtStops()
            state = State.READING
            speakCurrent()
            return
        }
        currentUtterance = null
        current = null
        stopSpeaking()
        state = State.READING
        speakNext()
    }

    fun stop() {
        session++
        earlyRun++
        readingDetails = null
        currentUtterance = null
        stopSpeaking()
        clipVoice?.close()
        clipVoice = null
        store.clear()
        panels.clear()
        dropArtStops()
        imageCount = 0
        lastSpoken = null
        seekPos = null
        current = null
        bubblesAhead.set(0)
        waitingForBubbles = false
        failure = null
        workerFailed = false
        skippedImages.clear()
        userScrolling = false
        main.removeCallbacks(afterUserScroll)
        page.clear()
        resumeWhenFree = false
        abandonFocus()
        if (state != State.IDLE) state = State.IDLE
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    // Manual scrolling: the finger stops the glide, and once the page settles somewhere the
    // reading is not, the reading moves to the screen.

    fun onUserTouch(fingerUp: Boolean) {
        if (state != State.READING) return
        if (!fingerUp) {
            userScrolling = true
            page.stopGlide()
        }
        waitForScrollToSettle()
    }

    fun onPageScrolled() {
        if (userScrolling) waitForScrollToSettle()
    }

    private fun waitForScrollToSettle() {
        main.removeCallbacks(afterUserScroll)
        main.postDelayed(afterUserScroll, SCROLL_SETTLE_MS)
    }

    private fun onUserScrollSettled() {
        if (!userScrolling) return
        userScrolling = false
        if (state != State.READING) return
        val reference = current ?: lastSpoken
        if (reference == null) {
            seekToScreen()
            return
        }
        val run = session
        page.isOnScreen(reference.bubble) { onScreen ->
            if (run != session || state != State.READING) return@isOnScreen
            when {
                !onScreen -> seekToScreen()
                // The bubble finished while the finger was down; carry on from it.
                current == null -> speakNext()
                // Scrolled down to the bubble while its art was showing: read it now.
                artStops.isNotEmpty() -> {
                    dropArtStops()
                    speakCurrent()
                }
            }
        }
    }

    private fun seekToScreen() {
        val run = session
        page.screenPosition { pos ->
            if (pos == null || run != session || state != State.READING) return@screenPosition
            val safePos = clampPos(pos)
            currentUtterance = null
            current = null
            dropArtStops()
            stopSpeaking()
            seekPos = safePos
            workerTarget = safePos
            speakNext()
        }
    }

    /** The page reports positions, and a page can lie: keep them inside the chapter. */
    private fun clampPos(pos: ChapterPos): ChapterPos =
        if (pos.isFinite()) pos.coerceIn(0.0, imageCount.toDouble()) else 0.0

    // Worker thread

    private fun runWorker(
        run: Int,
        images: List<PageImage>,
        provider: OcrProvider,
        rightToLeft: Boolean,
        pageUrl: String,
        userAgent: String,
        cast: SeriesCast,
        nextUrl: String?,
    ) {
        val cache = OcrCache(context)
        val end = images.size.toDouble()
        // Read once per Read press, so a Settings change applies from the next one.
        val batchSlices = settings.batchSlices
        val readNextEarly = settings.readNextChapterEarly
        var nextChapterStarted = false
        // The image being worked through, kept so the next batch does not download it again.
        var slicedIndex = -1
        var slices: List<Slice> = emptyList()
        var target = Double.NaN
        var next = 0.0
        // Batches sent to the relay and not back yet, oldest first. Two at once roughly doubles
        // how fast the text is read, since most of each request is spent waiting on Claude.
        val inFlight = ArrayDeque<InFlight>()
        // The last lines read, sent with the next batch so Claude knows who was just talking.
        val recent = ArrayDeque<Bubble>()
        val pool = Executors.newFixedThreadPool(PARALLEL_BATCHES) { r -> Thread(r, "ocr").apply { isDaemon = true } }
        // Images that would not load, one after another. One is passed over; a run of them stops
        // the read there, so a broken stretch is not taken for the end of the chapter.
        val failedRun = mutableListOf<Int>()
        var failedReason = ""

        // Adds the oldest batch's bubbles, so they still reach the store in reading order.
        fun finishOldest() {
            val job = inFlight.removeFirst()
            val entries = try {
                job.result.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception) ?: e
            }
            if (run != session) return
            store.add(entries, job.from, job.to)
            remember(recent, entries.map { it.bubble })
            main.post { onStoreChanged(run) }
        }

        try {
            while (run == session) {
                val wanted = workerTarget
                if (wanted != target) {
                    target = wanted
                    next = wanted
                    recent.clear() // A jump: the lines before are not the ones just read.
                }
                next = store.coveredUntil(next) ?: next
                val atEnd = next >= end - ChapterStore.EPS
                if (inFlight.isNotEmpty() &&
                    (atEnd || inFlight.size >= PARALLEL_BATCHES || bubblesAhead.get() > MAX_BUBBLES_AHEAD)
                ) {
                    finishOldest()
                    continue
                }
                // This chapter's text is all read: start on the next chapter while the voice catches up.
                if (atEnd && !nextChapterStarted && readNextEarly && nextUrl != null) {
                    nextChapterStarted = true
                    val early = earlyRun
                    thread(name = "next-chapter", isDaemon = true) {
                        readNextChapterEarly(early, nextUrl, images, provider, rightToLeft, pageUrl, userAgent, cast, batchSlices)
                    }
                }
                if (atEnd || bubblesAhead.get() > MAX_BUBBLES_AHEAD) {
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }

                // Each request has a fixed start-up cost, so bigger batches read more per second.
                // With nothing ready to speak, a smaller one gets the voice going sooner.
                val batchSize = if (bubblesAhead.get() == 0 && inFlight.isEmpty()) minOf(FIRST_BATCH_SIZE, batchSlices) else batchSlices
                val batch = mutableListOf<Slice>()
                var from = next
                var to = next
                while (batch.size < batchSize && to < end - ChapterStore.EPS) {
                    val imageIndex = to.toInt()
                    if (imageIndex != slicedIndex) {
                        val image = images[imageIndex]
                        slices = try {
                            val bytes = fetchImage(image.src, pageUrl, userAgent)
                            failedRun.clear()
                            try {
                                val gutters = PanelFinder.gutters(bytes)
                                panels.set(imageIndex, gutters)
                                Log.d(TAG, "Image $imageIndex: ${gutters.size} gaps between panels")
                            } catch (e: Exception) {
                                Log.w(TAG, "No panels for image $imageIndex: ${e.message}")
                            } catch (e: OutOfMemoryError) {
                                // Panels are a nicety; reading the text matters more.
                                Log.w(TAG, "No panels for image $imageIndex: out of memory")
                            }
                            ImageSlicer.slice(bytes, image.index, image.src)
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Image $imageIndex would not load (${e.message}): ${image.src}")
                            failedRun += imageIndex
                            failedReason = e.message ?: e.javaClass.simpleName
                            if (failedRun.size >= MAX_FAILED_IMAGES_IN_A_ROW) break
                            main.post { if (run == session) skippedImages += imageIndex }
                            emptyList()
                        }
                        slicedIndex = imageIndex
                    }
                    val frac = to - imageIndex
                    val slice = slices.firstOrNull { ownedBottom(it) > frac + ChapterStore.EPS }
                    if (slice == null) {
                        to = imageIndex + 1.0 // Nothing (left) to read in this image.
                        continue
                    }
                    if (batch.isEmpty()) from = minOf(from, imageIndex + slice.ownTopFrac)
                    batch += slice
                    to = imageIndex + ownedBottom(slice)
                }

                if (failedRun.size >= MAX_FAILED_IMAGES_IN_A_ROW) {
                    // Read what came before the broken stretch, then stop and say why.
                    if (batch.isNotEmpty() && to > next) {
                        val before = recent.toList()
                        inFlight.addLast(InFlight(pool.submit<List<Entry>> { recognize(batch, provider, cache, rightToLeft, cast, before) }, from, to))
                    }
                    while (inFlight.isNotEmpty()) finishOldest()
                    throw ImagesNotLoaded("Stopped at image ${failedRun.first() + 1}: ${imageList(failedRun)} would not load ($failedReason). Scroll past them and press Read to go on.")
                }
                if (!(to > next)) {
                    // No progress (bad position): wait instead of spinning.
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }
                val before = recent.toList()
                val result = pool.submit<List<Entry>> {
                    if (batch.isEmpty()) emptyList() else recognize(batch, provider, cache, rightToLeft, cast, before)
                }
                inFlight.addLast(InFlight(result, from, to))
                next = to
            }
        } catch (e: InterruptedException) {
            // App closing.
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            main.post {
                if (run != session) return@post
                failure = if (e is ImagesNotLoaded) e.message else "Could not read the text: ${e.message}"
                workerFailed = true
                if (waitingForBubbles) speakNext()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private class InFlight(val result: Future<List<Entry>>, val from: ChapterPos, val to: ChapterPos)

    private class ImagesNotLoaded(message: String) : Exception(message)

    private fun ownedBottom(slice: Slice) = minOf(slice.ownBottomFrac, 1.0)

    /**
     * Reads the start of the next chapter into the text cache, so pressing Read there starts
     * at once. Runs once this chapter's text is all read, stops after [EARLY_SLICES] slices to
     * spare the plan if the reader stops here, and stops when reading is stopped. A site that
     * adds its images by script gives no image list, and nothing is read.
     */
    private fun readNextChapterEarly(
        early: Int,
        nextUrl: String,
        images: List<PageImage>,
        provider: OcrProvider,
        rightToLeft: Boolean,
        pageUrl: String,
        userAgent: String,
        cast: SeriesCast,
        batchSlices: Int,
    ) {
        try {
            val host = NextChapter.hostOf(images) ?: return
            val sources = NextChapter.imagesOf(NextChapter.fetchHtml(nextUrl, pageUrl, userAgent), nextUrl, host)
            if (sources.size < MIN_NEXT_IMAGES) {
                Log.d(TAG, "Next chapter: ${sources.size} images found in its page, not reading it early")
                return
            }
            val nextKey = SeriesCast.seriesKeyOf(nextUrl)
            val nextCast = if (nextKey == cast.seriesKey) cast else SeriesCast(context, nextKey)
            val read = readStart(early, sources, nextUrl, userAgent, provider, rightToLeft, nextCast, batchSlices, EARLY_SLICES)
            Log.d(TAG, "Next chapter: read $read slices early from $nextUrl")
        } catch (e: InterruptedException) {
            // App closing.
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the next chapter early: ${e.message}")
        }
    }

    /**
     * Reads the text of the first [limit] slices of a chapter's images into the text cache,
     * skipping slices read before. Stops when [earlyRun] moves on. Returns the slices read.
     */
    private fun readStart(
        early: Int,
        sources: List<String>,
        referer: String,
        userAgent: String,
        provider: OcrProvider,
        rightToLeft: Boolean,
        cast: SeriesCast,
        batchSlices: Int,
        limit: Int,
    ): Int {
        val cache = OcrCache(context)
        val batch = mutableListOf<Slice>()
        val recent = ArrayDeque<Bubble>()
        var read = 0
        fun send() {
            if (batch.isEmpty() || early != earlyRun) return
            val entries = recognize(batch.toList(), provider, cache, rightToLeft, cast, recent.toList())
            remember(recent, entries.map { it.bubble })
            read += batch.size
            batch.clear()
        }
        for ((index, src) in sources.withIndex()) {
            if (early != earlyRun || read >= limit) break
            val slices = ImageSlicer.slice(fetchImage(src, referer, userAgent), index, src)
            for (slice in slices) {
                if (read + batch.size >= limit) break
                if (cache.get(slice, rightToLeft) != null) continue
                batch += slice
                if (batch.size >= batchSlices) send()
            }
        }
        send()
        return read
    }

    /** Keeps the last [RECENT_LINES] bubbles, oldest first. */
    private fun remember(recent: ArrayDeque<Bubble>, bubbles: List<Bubble>) {
        recent.addAll(bubbles)
        while (recent.size > RECENT_LINES) recent.removeFirst()
    }

    /** One line of what was just read, as Claude is shown it: who said it, and the words. */
    private fun lineFor(bubble: Bubble): String = when {
        bubble.speaker == VoiceStyle.NARRATOR -> "narration: \"${bubble.text}\""
        bubble.character.isEmpty() -> "unknown speaker (${bubble.speaker}): \"${bubble.text}\""
        else -> "${bubble.character} (${bubble.speaker}): \"${bubble.text}\""
    }

    private fun recognize(
        slices: List<Slice>,
        provider: OcrProvider,
        cache: OcrCache,
        rightToLeft: Boolean,
        cast: SeriesCast,
        before: List<Bubble>,
    ): List<Entry> {
        val keys = slices.map { cache.keyOf(it, rightToLeft) }
        // Slices another request is reading now: wait for its answer rather than ask again.
        for (i in slices.indices) {
            if (cache.get(slices[i], rightToLeft) != null) continue
            readingNow[keys[i]]?.let { runCatching { it.get(WAIT_FOR_OTHER_READ_S, TimeUnit.SECONDS) } }
        }
        val results = slices.map { cache.get(it, rightToLeft) }.toMutableList()
        val missing = slices.indices.filter { results[it] == null }
        val done = CompletableFuture<Unit>()
        val claimed = missing.map { keys[it] }.filter { readingNow.putIfAbsent(it, done) == null }
        if (missing.isNotEmpty()) try {
            val batch = missing.map { slices[it] }
            val ocrContext = OcrContext(cast.promptList(), before.map(::lineFor))
            val started = SystemClock.elapsedRealtime()
            val fresh = retryOnce { provider.recognize(batch, rightToLeft, ocrContext) }
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            readingDetails = "Text: ${batch.size} in ${"%.1f".format(seconds)}s · ${provider.lastModel.ifBlank { "model unknown" }}"
            missing.forEachIndexed { i, sliceIndex ->
                results[sliceIndex] = fresh[i]
                cache.put(slices[sliceIndex], rightToLeft, fresh[i])
            }
        } finally {
            claimed.forEach { readingNow.remove(it, done) }
            done.complete(Unit)
        }
        val bubbles = slices.mapIndexed { i, slice -> slice.toPageBubbles(results[i].orEmpty()) }
        // Only fresh pages count towards who speaks most, so reading a chapter again changes nothing.
        cast.record(missing.flatMap { bubbles[it] })
        val readSystem = settings.readSystemBoxes
        return slices.flatMapIndexed { i, slice ->
            bubbles[i].mapIndexed { order, bubble ->
                Entry(Entry.keyOf(slice, order), cast.settle(bubble), slice.topFrac, slice.bottomFrac)
            }.filter { readSystem || it.bubble.kind != TextKind.SYSTEM }
        }
    }

    private fun <T> retryOnce(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "OCR batch failed, retrying once: ${e.message}")
        block()
    }

    // Main thread

    private fun onStoreChanged(run: Int) {
        if (run != session) return
        updateBubblesAhead()
        if (waitingForBubbles) speakNext() else prefetchClips()
    }

    /** Asks the PC for the clips of the bubble being read and the next few, in reading order. */
    private fun prefetchClips() {
        val voice = clipVoice ?: return
        var entry: Entry? = current
            ?: seekPos?.let { store.firstFrom(it) }
            ?: store.firstAfter(lastSpoken?.key ?: -1L)
        repeat(CLIPS_AHEAD + 1) {
            val e = entry ?: return
            voice.prefetch(e.bubble)
            entry = store.firstAfter(e.key)
        }
    }

    private val readingFrom: ChapterPos get() = seekPos ?: lastSpoken?.centre ?: 0.0

    /** Lets the worker read ahead of the voice, but not the whole chapter, to save requests on a pause. */
    private fun updateBubblesAhead() {
        val stretchEnd = store.coveredUntil(readingFrom)
        bubblesAhead.set(if (stretchEnd == null) 0 else store.countAhead(lastSpoken?.key, seekPos, stretchEnd))
    }

    private fun speakNext() {
        if (state == State.IDLE || state == State.PAUSED) return
        // Gliding to the next bubble now would drag the page away from the finger.
        // When scrolling settles, reading carries on from the screen.
        if (userScrolling) return

        val from = readingFrom
        // Scrolled past the last image: there is nothing left to read.
        if (from >= imageCount - ChapterStore.EPS) {
            finish()
            return
        }
        val stretchEnd = store.coveredUntil(from)
        val seek = seekPos
        val candidate = if (seek != null) store.firstFrom(seek) else store.firstAfter(lastSpoken?.key ?: -1L)
        val ready = candidate != null && stretchEnd != null && candidate.centre <= stretchEnd + ChapterStore.EPS
        if (!ready) {
            val readToEnd = stretchEnd != null && stretchEnd >= imageCount - ChapterStore.EPS
            if (readToEnd || workerFailed) {
                finish()
            } else {
                waitingForBubbles = true
                workerTarget = from
                updateBubblesAhead()
                status("Reading ahead…")
            }
            return
        }
        val next = candidate!!
        waitingForBubbles = false
        dropArtStops()
        val previous = lastSpoken
        // Moving on in the story (not after a jump): stop on any art-only panels on the way.
        if (seek == null && previous != null && settings.artPauseSeconds > 0f) {
            val after = panels.panelAround(previous.top, previous.bottom)[1]
            val before = panels.panelAround(next.top, next.bottom)[0]
            artStops.addAll(panels.artBetween(after, before))
            if (artStops.isNotEmpty()) Log.d(TAG, "Showing ${artStops.size} art panels before the next bubble")
        }
        seekPos = null
        lastSpoken = next
        current = next
        updateBubblesAhead()
        prefetchClips()
        if (holds.isNotEmpty()) {
            // The first bubble is ready but the app is away or in a call: wait on it.
            state = State.PAUSED
            resumeWhenFree = true
            return
        }
        state = State.READING
        speakCurrent()
    }

    private fun dropArtStops() {
        artStops.clear()
        artToken++
    }

    /** Shows one part of the next art-only panel, waits so it can be seen, then moves on. */
    private fun showArt(entry: Entry, run: Int, panel: DoubleArray, part: Int) {
        val token = ++artToken
        page.showArt(panel, part) { glideMs, parts ->
            main.postDelayed({
                val stillHere = token == artToken && run == session && state == State.READING &&
                    current === entry && artStops.firstOrNull() === panel
                // A finger on the page wins; reading picks up once it lets go.
                if (!stillHere || userScrolling) return@postDelayed
                if (part + 1 < parts) {
                    showArt(entry, run, panel, part + 1)
                } else {
                    artStops.removeFirst()
                    speakCurrent()
                }
            }, glideMs + (settings.artPauseSeconds * 1000).toLong())
        }
    }

    private fun speakCurrent() {
        val entry = current ?: return
        val bubble = entry.bubble
        val run = session
        artStops.firstOrNull()?.let { panel ->
            showArt(entry, run, panel, 0)
            return
        }
        page.show(bubble, panels.panelAround(entry.top, entry.bottom)) { glideMs ->
            if (run != session || state != State.READING || current !== entry) return@show
            listener.onLine(bubble)
            status(bubble.text)
            // Start talking once the page has mostly arrived at the bubble.
            main.postDelayed({
                if (run != session || state != State.READING || current !== entry) return@postDelayed
                val id = "u${++utteranceCounter}"
                currentUtterance = id
                val voice = clipVoice
                if (voice == null) {
                    speakWithPhone(bubble, id)
                } else {
                    waitForClip(voice, entry, run, id, SystemClock.uptimeMillis() + CLIP_WAIT_MS)
                }
            }, (glideMs * 0.6).toLong())
        }
    }

    private fun speakWithPhone(bubble: Bubble, id: String) {
        // The phone voice would say "cough" or "sigh" as a word: leave a beat instead.
        if (bubble.kind == TextKind.REACTION && bubble.sound in TextKind.UNSPOKEN_SOUNDS) {
            main.postDelayed({ onUtteranceFinished(id) }, REACTION_PAUSE_MS)
            return
        }
        val style = VoiceStyle.styleFor(bubble, settings.speechRate, settings.expressiveVoice)
        tts.setPitch(style.pitch)
        tts.setSpeechRate(style.rate)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, style.volume) }
        tts.speak(SpeechText.forSpeech(bubble.text), TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun isSpeaking(entry: Entry, run: Int, id: String) =
        run == session && state == State.READING && current === entry && currentUtterance == id

    /** Plays the clip once it arrives. A clip that fails, or is too slow, uses the phone voice (if allowed). */
    private fun waitForClip(voice: ClipVoice, entry: Entry, run: Int, id: String, deadline: Long) {
        if (!isSpeaking(entry, run, id)) return
        if (voice.isUnreachable) {
            voiceFailed("Can't reach ${voice.name}", entry, id)
            return
        }
        val clip = try {
            voice.clipOrNull(entry.bubble)
        } catch (e: Exception) {
            val reason = (e.cause ?: e).message ?: e.javaClass.simpleName
            Log.w(TAG, "${voice.name} failed: $reason")
            voiceFailed("${voice.name} failed: $reason", entry, id)
            return
        }
        when {
            clip != null -> play(clip, entry.bubble, id)
            // Without the phone voice to fill in, a slow line is waited for.
            SystemClock.uptimeMillis() >= deadline && fallbackToPhone -> {
                showVoiceProblem("${voice.name} is slow, so the phone voice reads some lines")
                speakWithPhone(entry.bubble, id)
            }
            else -> main.postDelayed({ waitForClip(voice, entry, run, id, deadline) }, CLIP_POLL_MS)
        }
    }

    /** The phone voice reads the line, or reading stops with the reason when that is turned off. */
    private fun voiceFailed(reason: String, entry: Entry, id: String) {
        if (fallbackToPhone) {
            showVoiceProblem("$reason. The phone voice reads for now.")
            speakWithPhone(entry.bubble, id)
            return
        }
        failure = reason
        finish()
    }

    private fun play(clip: File, bubble: Bubble, id: String) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(clip.path)
            if (VoiceStyle.isQuietThought(bubble)) mp.setVolume(VoiceStyle.THOUGHT_VOLUME, VoiceStyle.THOUGHT_VOLUME)
            mp.setOnCompletionListener {
                if (player === mp) releasePlayer()
                onUtteranceFinished(id)
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Clip playback failed: $what/$extra")
                if (player === mp) releasePlayer()
                if (currentUtterance == id) speakWithPhone(bubble, id)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not play clip: ${e.message}")
            if (player === mp) releasePlayer()
            clip.delete()
            speakWithPhone(bubble, id)
        }
    }

    /** Says once per read why the phone voice is filling in, instead of switching silently. */
    private fun showVoiceProblem(message: String) {
        if (voiceProblemShown) return
        voiceProblemShown = true
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun onUtteranceFinished(utteranceId: String) {
        if (utteranceId != currentUtterance) return
        currentUtterance = null
        current = null
        val run = session
        main.postDelayed({ if (run == session) speakNext() }, PAUSE_BETWEEN_BUBBLES_MS)
    }

    private fun finish() {
        session++ // Ends the worker.
        clipVoice?.close()
        clipVoice = null
        page.clear()
        resumeWhenFree = false
        abandonFocus()
        state = State.IDLE
        listener.onLine(null)
        // A failed read would otherwise end on "Finished", hiding why nothing was spoken.
        val skipped = if (skippedImages.isEmpty()) "" else " (skipped ${imageList(skippedImages)}, which would not load)"
        listener.onStatus(failure ?: "Finished this chapter$skipped")
        // Only a chapter read to its end goes on; a failed read stops there with its reason.
        if (failure == null) listener.onChapterFinished(chapterNext)
    }

    /** "image 4" or "images 4, 5 and 9", counted from 1 as the reader would. */
    private fun imageList(indexes: Collection<Int>): String {
        val numbers = indexes.map { (it + 1).toString() }
        return if (numbers.size == 1) "image ${numbers[0]}"
        else "images ${numbers.dropLast(1).joinToString(", ")} and ${numbers.last()}"
    }

    /** Downloads an image, trying again after a pause: image hosts often drop a request in a burst. */
    private fun fetchImage(src: String, pageUrl: String, userAgent: String): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until IMAGE_TRIES) {
            if (attempt > 0) Thread.sleep(IMAGE_RETRY_MS * attempt)
            try {
                val bytes = ImageFetcher.fetch(src, pageUrl, userAgent)
                if (ImageSlicer.isImage(bytes)) return bytes
                last = IOException("not a picture (${bytes.size} bytes)")
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no tries")
    }

    companion object {
        private const val TAG = "ReadAloud"
        /** Holds: the app is off screen, or a call or another app has the sound. */
        const val HOLD_AWAY = "away"
        const val HOLD_AUDIO = "audio"
        /** Slices in the first request when nothing is ready to speak: small, so the voice starts sooner. */
        private const val FIRST_BATCH_SIZE = 2
        /** Page open: slices read before Read is pressed, about the first few screens. */
        private const val OPEN_SLICES = 8
        /** How long a request waits for another one already reading the same slice. */
        private const val WAIT_FOR_OTHER_READ_S = 90L
        /** Batches sent to the relay at once. The relay runs up to two claude calls together. */
        private const val PARALLEL_BATCHES = 2
        /** Next chapter: slices read before it is opened, about the first few screens. */
        private const val EARLY_SLICES = 16
        /** Next chapter: fewer images than this in its page means the list is not the chapter. */
        private const val MIN_NEXT_IMAGES = 3
        private const val MAX_BUBBLES_AHEAD = 25
        /** Lines read just before a batch that Claude is shown, so conversations carry over. */
        private const val RECENT_LINES = 6
        private const val PAUSE_BETWEEN_BUBBLES_MS = 250L

        /** The beat left for a cough or sigh the phone voice cannot make. */
        private const val REACTION_PAUSE_MS = 600L
        private const val SCROLL_SETTLE_MS = 700L
        private const val IDLE_POLL_MS = 250L
        // Image downloads: tries per image, the pause before a retry (longer each time), and how
        // many images in a row may fail before the read stops instead of passing them over.
        private const val IMAGE_TRIES = 3
        private const val IMAGE_RETRY_MS = 1_500L
        private const val MAX_FAILED_IMAGES_IN_A_ROW = 2

        /** PC voice: clips asked for beyond the bubble being read. */
        private const val CLIPS_AHEAD = 4
        /** PC voice: how long a bubble waits for its clip before the phone voice reads it. */
        private const val CLIP_WAIT_MS = 8_000L
        private const val CLIP_POLL_MS = 100L
    }
}
