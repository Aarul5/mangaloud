package io.github.aarul5.mangaloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Turns comic images into bubbles: downloads an image, and asks Claude (through an [OcrProvider])
 * for the bubbles in its slices. Results are kept in the [OcrCache], so a slice is only ever sent
 * once, and two requests never ask for the same slice at the same time.
 *
 * [ReadAloud] decides which slices to read and when; this reads them. Its methods block, so call
 * them from a worker thread.
 */
class TextReader(private val context: Context, private val settings: Settings) {

    /** Slices being read right now, so a second request for one waits for the first answer. */
    private val readingNow = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    /** The last batch's size, time and model, for the screen to show, or null. */
    @Volatile
    var lastDetails: String? = null

    /**
     * The bubbles in [slices], in reading order: from the cache where it has them, and from
     * [provider] for the rest. [before] is the lines read just before, which Claude is shown so a
     * conversation carries on from one batch to the next.
     */
    fun recognize(
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
            lastDetails = "Text: ${batch.size} in ${"%.1f".format(seconds)}s · ${provider.lastModel.ifBlank { "model unknown" }}"
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

    /**
     * Reads the text of the first [limit] slices of a chapter's images into the text cache,
     * skipping slices read before. Stops as soon as [keepGoing] says no. Returns the slices read.
     */
    fun readStart(
        sources: List<String>,
        referer: String,
        userAgent: String,
        provider: OcrProvider,
        rightToLeft: Boolean,
        cast: SeriesCast,
        batchSlices: Int,
        limit: Int,
        keepGoing: () -> Boolean,
    ): Int {
        val cache = OcrCache(context)
        val batch = mutableListOf<Slice>()
        val recent = ArrayDeque<Bubble>()
        var read = 0
        fun send() {
            if (batch.isEmpty() || !keepGoing()) return
            val entries = recognize(batch.toList(), provider, cache, rightToLeft, cast, recent.toList())
            remember(recent, entries.map { it.bubble })
            read += batch.size
            batch.clear()
        }
        for ((index, src) in sources.withIndex()) {
            if (!keepGoing() || read >= limit) break
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

    /**
     * Reads the start of the next chapter into the text cache, so pressing Read there starts
     * at once. Stops after [EARLY_SLICES] slices to spare the plan if the reader stops here, and
     * when [keepGoing] says no. A site that adds its images by script gives no image list, and
     * nothing is read.
     */
    fun readNextChapterEarly(
        nextUrl: String,
        images: List<PageImage>,
        provider: OcrProvider,
        rightToLeft: Boolean,
        pageUrl: String,
        userAgent: String,
        cast: SeriesCast,
        batchSlices: Int,
        keepGoing: () -> Boolean,
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
            val read = readStart(sources, nextUrl, userAgent, provider, rightToLeft, nextCast, batchSlices, EARLY_SLICES, keepGoing)
            Log.d(TAG, "Next chapter: read $read slices early from $nextUrl")
        } catch (e: InterruptedException) {
            // App closing.
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the next chapter early: ${e.message}")
        }
    }

    /** Downloads an image, trying again after a pause: image hosts often drop a request in a burst. */
    fun fetchImage(src: String, pageUrl: String, userAgent: String): ByteArray {
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

    private fun <T> retryOnce(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "OCR batch failed, retrying once: ${e.message}")
        block()
    }

    companion object {
        private const val TAG = "TextReader"
        /** Fewer images than this in a page means the list is not a chapter. */
        const val MIN_NEXT_IMAGES = 3
        /** Next chapter: slices read before it is opened, about the first few screens. */
        private const val EARLY_SLICES = 16
        /** How long a request waits for another one already reading the same slice. */
        private const val WAIT_FOR_OTHER_READ_S = 90L
        /** Lines read just before a batch that Claude is shown, so conversations carry over. */
        private const val RECENT_LINES = 6
        // Image downloads: tries per image, and the pause before a retry (longer each time).
        private const val IMAGE_TRIES = 3
        private const val IMAGE_RETRY_MS = 1_500L

        /** Keeps the last [RECENT_LINES] bubbles, oldest first. */
        fun remember(recent: ArrayDeque<Bubble>, bubbles: List<Bubble>) {
            recent.addAll(bubbles)
            while (recent.size > RECENT_LINES) recent.removeFirst()
        }

        /** One line of what was just read, as Claude is shown it: who said it, and the words. */
        internal fun lineFor(bubble: Bubble): String = when {
            bubble.speaker == VoiceStyle.NARRATOR -> "narration: \"${bubble.text}\""
            bubble.character.isEmpty() -> "unknown speaker (${bubble.speaker}): \"${bubble.text}\""
            else -> "${bubble.character} (${bubble.speaker}): \"${bubble.text}\""
        }
    }
}
