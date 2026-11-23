package io.github.aarul5.mangaloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs a chapter read-through. A worker thread downloads, slices and OCRs the images just ahead
 * of the voice; the main thread speaks one bubble at a time and glides the page to it.
 *
 * Everything read is kept in a [ChapterStore] for the whole read. Scrolling by hand only moves
 * the reading position: a stretch already read is spoken straight away, and the worker is sent
 * to a stretch that is not, so nothing is downloaded or sent to Claude twice.
 *
 * Reading the text from images is [TextReader]'s job, and saying each line is [Speaker]'s; this
 * class decides what to read and say, and when.
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

    private val textReader = TextReader(context, settings)

    private fun status(message: String) {
        listener.onStatus(message)
        val details = listOfNotNull(textReader.lastDetails, speaker.details).joinToString(" · ")
        listener.onDetails(details.ifEmpty { null })
    }

    private val speaker = Speaker(context, settings, main, onFinished = ::onUtteranceFinished, onUnavailable = { listener.onStatus(it) })

    fun start(pageUrl: String, userAgent: String) {
        stop()
        if (!speaker.isReady) {
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
        speaker.prepare(cast.seriesKey)?.let { reason ->
            listener.onStatus(reason)
            return
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
            val isChapter = chapter.images.size >= TextReader.MIN_NEXT_IMAGES &&
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
            if (chapter.images.size < TextReader.MIN_NEXT_IMAGES) return@collectChapter
            if (chapter.next == null && !SeriesCast.looksLikeChapter(pageUrl)) return@collectChapter
            val cast = SeriesCast(context, SeriesCast.seriesKeyOf(pageUrl))
            val sources = chapter.images.drop(chapter.startIndex).map { it.src }
            val rightToLeft = settings.rightToLeft
            val batchSlices = settings.batchSlices
            thread(name = "read-on-open", isDaemon = true) {
                try {
                    val read = textReader.readStart(sources, pageUrl, userAgent, provider, rightToLeft, cast, batchSlices, OPEN_SLICES) { early == earlyRun }
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

    /** Silences whichever voice is talking. */
    private fun stopSpeaking() = speaker.silence()

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
        textReader.lastDetails = null
        currentUtterance = null
        stopSpeaking()
        speaker.endRead()
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
        speaker.shutdown()
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
            TextReader.remember(recent, entries.map { it.bubble })
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
                        textReader.readNextChapterEarly(nextUrl, images, provider, rightToLeft, pageUrl, userAgent, cast, batchSlices) { early == earlyRun }
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
                            val bytes = textReader.fetchImage(image.src, pageUrl, userAgent)
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
                        inFlight.addLast(InFlight(pool.submit<List<Entry>> { textReader.recognize(batch, provider, cache, rightToLeft, cast, before) }, from, to))
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
                    if (batch.isEmpty()) emptyList() else textReader.recognize(batch, provider, cache, rightToLeft, cast, before)
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

    // Main thread

    private fun onStoreChanged(run: Int) {
        if (run != session) return
        updateBubblesAhead()
        if (waitingForBubbles) speakNext() else prefetchClips()
    }

    /** Asks the PC for the clips of the bubble being read and the next few, in reading order. */
    private fun prefetchClips() {
        if (!speaker.usesClips) return
        var entry: Entry? = current
            ?: seekPos?.let { store.firstFrom(it) }
            ?: store.firstAfter(lastSpoken?.key ?: -1L)
        repeat(CLIPS_AHEAD + 1) {
            val e = entry ?: return
            speaker.prefetch(e.bubble)
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
                speaker.say(bubble, id, stillWanted = { isSpeaking(entry, run, id) }) { reason ->
                    // The voice failed and the phone voice may not fill in: stop and say why.
                    failure = reason
                    finish()
                }
            }, (glideMs * 0.6).toLong())
        }
    }

    private fun isSpeaking(entry: Entry, run: Int, id: String) =
        run == session && state == State.READING && current === entry && currentUtterance == id

    private fun onUtteranceFinished(utteranceId: String) {
        if (utteranceId != currentUtterance) return
        currentUtterance = null
        current = null
        val run = session
        main.postDelayed({ if (run == session) speakNext() }, PAUSE_BETWEEN_BUBBLES_MS)
    }

    private fun finish() {
        session++ // Ends the worker.
        speaker.endRead()
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

    companion object {
        private const val TAG = "ReadAloud"
        /** Holds: the app is off screen, or a call or another app has the sound. */
        const val HOLD_AWAY = "away"
        const val HOLD_AUDIO = "audio"
        /** Slices in the first request when nothing is ready to speak: small, so the voice starts sooner. */
        private const val FIRST_BATCH_SIZE = 2
        /** Page open: slices read before Read is pressed, about the first few screens. */
        private const val OPEN_SLICES = 8
        /** Batches sent to the relay at once. The relay runs up to two claude calls together. */
        private const val PARALLEL_BATCHES = 2
        private const val MAX_BUBBLES_AHEAD = 25
        private const val PAUSE_BETWEEN_BUBBLES_MS = 250L
        private const val SCROLL_SETTLE_MS = 700L
        private const val IDLE_POLL_MS = 250L
        /** Images in a row that may fail to load before the read stops instead of passing them over. */
        private const val MAX_FAILED_IMAGES_IN_A_ROW = 2

        /** PC voice: clips asked for beyond the bubble being read. */
        private const val CLIPS_AHEAD = 6
    }
}
