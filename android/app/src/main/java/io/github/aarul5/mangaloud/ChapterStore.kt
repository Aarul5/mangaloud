package io.github.aarul5.mangaloud

import android.util.Log

/**
 * A place in the chapter: image index plus the fraction down that image.
 * 3.25 is a quarter of the way down the fourth image.
 */
typealias ChapterPos = Double

/**
 * A bubble with its place in reading order: image, then slice, then order inside the slice.
 * sliceTop and sliceBottom are the slice it was read from, as fractions of the image.
 */
class Entry(val key: Long, val bubble: Bubble, val sliceTop: Double, val sliceBottom: Double) {
    val top: ChapterPos get() = bubble.imageIndex + bubble.y0
    val bottom: ChapterPos get() = if (bubble.spill > 0) bubble.imageIndex + 1 + bubble.spill else bubble.imageIndex + bubble.y1
    val centre: ChapterPos get() = (top + bottom) / 2
    val sliceIndex: Int get() = ((key / 1_000L) % 1_000L).toInt()

    companion object {
        fun keyOf(slice: Slice, order: Int): Long =
            slice.imageIndex * 1_000_000L + slice.sliceIndex * 1_000L + order
    }
}

/**
 * Spots one bubble read from both sides of a cut. A bubble on a slice cut has a box that stops at
 * the slice edge, so the centre rule in [Slice] can let both slices keep it. A bubble on the cut
 * between two images (its top at the bottom of one picture, the rest at the top of the next) is
 * read once from each image. The OCR boxes are too rough near an edge to tell from position
 * alone, so this also compares the text: one copy is usually the whole line and the other a piece.
 */
object SeamTwins {
    private const val MIN_SHARED = 0.6
    private const val MIN_CHARS_FOR_PARTIAL = 8
    /** How close to an image's top or bottom edge a box must reach to count as cut by it. */
    private const val IMAGE_EDGE = 0.04
    private val notWord = Regex("""[^\p{L}\p{N}]+""")

    fun same(a: Entry, b: Entry): Boolean {
        if (a.bubble.imageIndex != b.bubble.imageIndex) return acrossImages(a.bubble, b.bubble)
        val (upper, lower) = if (a.sliceIndex < b.sliceIndex) a to b else b to a
        if (lower.sliceIndex - upper.sliceIndex != 1) return false
        if (!nearSeam(a, lower.sliceTop, upper.sliceBottom) || !nearSeam(b, lower.sliceTop, upper.sliceBottom)) return false
        val overlapX = minOf(a.bubble.x1, b.bubble.x1) - maxOf(a.bubble.x0, b.bubble.x0)
        val narrower = minOf(a.bubble.x1 - a.bubble.x0, b.bubble.x1 - b.bubble.x0)
        if (narrower <= 0 || overlapX < narrower / 2) return false
        return sameText(normal(a.bubble.text), normal(b.bubble.text))
    }

    /** One bubble cut by the edge between two images: at the bottom of one, the top of the next. */
    private fun acrossImages(a: Bubble, b: Bubble): Boolean {
        val (upper, lower) = if (a.imageIndex < b.imageIndex) a to b else b to a
        if (lower.imageIndex - upper.imageIndex != 1) return false
        if (upper.y1 < 1 - IMAGE_EDGE || lower.y0 > IMAGE_EDGE) return false
        val overlapX = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)
        val narrower = minOf(a.x1 - a.x0, b.x1 - b.x0)
        if (narrower <= 0 || overlapX < narrower / 2) return false
        return sameText(normal(a.text), normal(b.text))
    }

    /** The copy to keep: the one with more of the line. */
    fun fuller(a: Entry, b: Entry): Entry =
        if (normal(b.bubble.text).length > normal(a.bubble.text).length) b else a

    /**
     * The fuller copy's text, with who says it taken from whichever copy knows: the two halves of
     * a cut bubble are often read in different batches, and one may have lost track of the speaker.
     */
    fun merged(a: Entry, b: Entry): Bubble {
        val kept = fuller(a, b).bubble
        val other = (if (kept === a.bubble) b else a).bubble
        val character = kept.character.ifEmpty { other.character }
        val speaker = when {
            kept.speaker != VoiceStyle.UNKNOWN -> kept.speaker
            else -> other.speaker
        }
        val joined = kept.copy(
            character = character,
            speaker = speaker,
            look = kept.look.ifEmpty { other.look },
            sound = kept.sound.ifEmpty { other.sound },
        )
        if (a.bubble.imageIndex == b.bubble.imageIndex) return joined
        // Cut by the edge between two images: one bubble from the upper half's top to how far
        // it runs into the next image, so the page frames and highlights all of it.
        val (upper, lower) = if (a.bubble.imageIndex < b.bubble.imageIndex) a.bubble to b.bubble else b.bubble to a.bubble
        return joined.copy(
            imageIndex = upper.imageIndex,
            x0 = minOf(upper.x0, lower.x0),
            y0 = upper.y0,
            x1 = maxOf(upper.x1, lower.x1),
            y1 = upper.y1,
            spill = lower.y1,
        )
    }

    /** Both slices only see a bubble that reaches into the band they share. */
    private fun nearSeam(entry: Entry, seamTop: Double, seamBottom: Double): Boolean {
        val margin = (seamBottom - seamTop) / 4
        return entry.bubble.y1 >= seamTop - margin && entry.bubble.y0 <= seamBottom + margin
    }

    private fun normal(text: String) = text.lowercase().replace(notWord, " ").trim()

    internal fun sameText(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.isEmpty()) return false
        if (long.contains(short)) return true
        return short.length >= MIN_CHARS_FOR_PARTIAL && longestShared(short, long) >= short.length * MIN_SHARED
    }

    private fun longestShared(a: String, b: String): Int {
        var best = 0
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            for (j in 1..b.length) {
                if (a[i - 1] == b[j - 1]) {
                    row[j] = prev[j - 1] + 1
                    if (row[j] > best) best = row[j]
                }
            }
            prev = row
        }
        return best
    }
}

/**
 * Everything read so far in this chapter: the bubbles, and the stretches of the chapter the
 * OCR has covered. Kept for the whole read, so scrolling back or forward over a stretch that
 * was already read costs nothing. Shared by the worker and the main thread.
 */
class ChapterStore {
    private val entries = ArrayList<Entry>()
    private val keys = HashSet<Long>()
    private val covered = ArrayList<DoubleArray>() // sorted, merged [from, to] stretches

    @Synchronized
    fun clear() {
        entries.clear()
        keys.clear()
        covered.clear()
    }

    /** Adds a batch's bubbles and marks the stretch it covered as read, in one step. */
    @Synchronized
    fun add(newEntries: List<Entry>, from: ChapterPos, to: ChapterPos) {
        for (entry in newEntries) {
            if (!keys.add(entry.key)) continue
            val twin = entries.indexOfFirst { SeamTwins.same(it, entry) }
            if (twin < 0) {
                entries += entry
                continue
            }
            // Keep one copy, with the fuller text, at the earlier place in reading order.
            val old = entries[twin]
            val first = if (entry.key < old.key) entry else old
            val kept = SeamTwins.merged(old, entry)
            Log.d(TAG, "Same bubble from two slices, keeping \"${kept.text.take(40)}\"")
            entries[twin] = Entry(first.key, kept, first.sliceTop, first.sliceBottom)
        }
        entries.sortBy { it.key }
        if (!from.isFinite() || !to.isFinite() || to < from) return
        covered += doubleArrayOf(from, to)
        covered.sortBy { it[0] }
        val merged = ArrayList<DoubleArray>()
        for (range in covered) {
            val last = merged.lastOrNull()
            if (last != null && range[0] <= last[1] + EPS) last[1] = maxOf(last[1], range[1])
            else merged += doubleArrayOf(range[0], range[1])
        }
        covered.clear()
        covered.addAll(merged)
    }

    /** End of the read stretch that holds pos, or null when pos has not been read yet. */
    @Synchronized
    fun coveredUntil(pos: ChapterPos): ChapterPos? =
        covered.firstOrNull { pos >= it[0] - EPS && pos <= it[1] + EPS }?.get(1)

    @Synchronized
    fun firstAfter(key: Long): Entry? = entries.firstOrNull { it.key > key }

    /** The first bubble that is not wholly above pos. */
    @Synchronized
    fun firstFrom(pos: ChapterPos): Entry? = entries.firstOrNull { it.bottom >= pos - EPS }

    /** How many read bubbles wait after the reading position, within its read stretch. */
    @Synchronized
    fun countAhead(afterKey: Long?, fromPos: ChapterPos?, stretchEnd: ChapterPos): Int = entries.count {
        val after = if (fromPos != null) it.bottom >= fromPos - EPS else it.key > (afterKey ?: -1L)
        after && it.centre <= stretchEnd + EPS
    }

    companion object {
        const val EPS = 1e-6
        private const val TAG = "ChapterStore"
    }
}
