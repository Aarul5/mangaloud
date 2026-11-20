package io.github.aarul5.mangaloud

import android.graphics.BitmapFactory

/**
 * Finds the gaps between panels in a page image: full-width bands of one plain colour, the
 * white or black strips a webtoon leaves between scenes. Runs on the image the phone already
 * downloaded for reading, so it costs no requests.
 */
object PanelFinder {
    private const val TARGET_WIDTH = 240
    private const val MAX_PIXELS = 4_000_000L
    private const val SIDE_MARGIN = 0.02
    private const val OUTLIER_LUMA = 20
    private const val OUTLIER_SHARE = 0.02
    private const val MIN_GUTTER_PER_WIDTH = 0.02
    private const val MIN_PANEL_PER_WIDTH = 0.15

    /** The gutters as [top, bottom] fractions of the image height, top to bottom. */
    fun gutters(bytes: ByteArray): List<DoubleArray> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        // A very tall strip could still be huge at that width: keep the decode within a budget.
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return emptyList()
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return gutters(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun gutters(pixels: IntArray, width: Int, height: Int): List<DoubleArray> {
        if (width <= 0 || height <= 0) return emptyList()
        val plain = BooleanArray(height) { isPlainRow(pixels, it * width, width) }
        val minGutter = maxOf(2, (width * MIN_GUTTER_PER_WIDTH).toInt())
        val minPanel = maxOf(4, (width * MIN_PANEL_PER_WIDTH).toInt())

        // Runs of plain rows, in pixels.
        val runs = mutableListOf<IntArray>()
        var y = 0
        while (y < height) {
            if (!plain[y]) { y++; continue }
            val start = y
            while (y < height && plain[y]) y++
            if (y - start >= minGutter || start == 0 || y == height) runs += intArrayOf(start, y)
        }

        // A sliver of picture between two gutters is not a panel worth stopping on: fold it in.
        // Picture at the top or bottom edge is kept, since it may carry on in the next image.
        val merged = mutableListOf<IntArray>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run[0] - last[1] < minPanel) last[1] = run[1]
            else merged += intArrayOf(run[0], run[1])
        }

        return merged.map { doubleArrayOf(it[0].toDouble() / height, it[1].toDouble() / height) }
    }

    /** One colour across the row, give or take a few stray pixels and the side edges. */
    private fun isPlainRow(pixels: IntArray, offset: Int, width: Int): Boolean {
        val margin = (width * SIDE_MARGIN).toInt()
        val from = offset + margin
        val to = offset + width - margin
        if (to <= from) return false
        val luma = IntArray(to - from) { luma(pixels[from + it]) }
        val sorted = luma.sortedArray()
        val median = sorted[sorted.size / 2]
        val allowed = maxOf(1, (luma.size * OUTLIER_SHARE).toInt())
        var outliers = 0
        for (l in luma) if (kotlin.math.abs(l - median) > OUTLIER_LUMA && ++outliers > allowed) return false
        return true
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/**
 * The panels of the chapter, from each image's gutters. A panel can run over the join between
 * two images, since webtoon sites cut the strip wherever they like. Shared by the worker,
 * which adds images as it downloads them, and the main thread.
 */
class PanelMap {
    private val gutters = HashMap<Int, List<DoubleArray>>()

    @Synchronized
    fun clear() = gutters.clear()

    @Synchronized
    fun set(imageIndex: Int, imageGutters: List<DoubleArray>) {
        gutters[imageIndex] = imageGutters.map { doubleArrayOf(imageIndex + it[0], imageIndex + it[1]) }
    }

    /**
     * The panel to show while a bubble from top to bottom is read: the one holding its centre,
     * widened to the whole bubble. A bubble floating between panels (on a gutter, or alone in a
     * band no bigger than itself) is a line about the picture above it, so it gets that panel too.
     */
    @Synchronized
    fun panelAround(top: ChapterPos, bottom: ChapterPos): DoubleArray {
        val centre = (top + bottom) / 2
        val image = centre.toInt()
        val nearby = (image - 1..image + 1).flatMap { gutters[it].orEmpty() }
        val floor = if (gutters.containsKey(image - 1)) image - 1.0 else image.toDouble()
        val ceiling = if (gutters.containsKey(image + 1)) image + 2.0 else image + 1.0
        nearby.firstOrNull { centre > it[0] && centre < it[1] }?.let { gutter ->
            return withPictureAbove(gutter[0], top, bottom, nearby, floor)
        }
        val gutterAbove = nearby.filter { it[1] <= centre }.maxByOrNull { it[1] }
        val start = gutterAbove?.get(1) ?: floor
        val end = nearby.filter { it[0] >= centre }.minOfOrNull { it[0] } ?: ceiling
        val panelTop = minOf(top, maxOf(start, floor))
        val panelBottom = maxOf(bottom, minOf(end, ceiling))
        if (gutterAbove != null && panelBottom - panelTop < (bottom - top) * FLOATING_FILL) {
            return withPictureAbove(gutterAbove[0], top, bottom, nearby, floor)
        }
        return doubleArrayOf(panelTop, panelBottom)
    }

    /** The bubble with the panel that ends at [gutterTop], or just the bubble if there is none. */
    private fun withPictureAbove(
        gutterTop: ChapterPos,
        top: ChapterPos,
        bottom: ChapterPos,
        nearby: List<DoubleArray>,
        floor: ChapterPos,
    ): DoubleArray {
        val pictureTop = nearby.filter { it[1] <= gutterTop + 1e-9 }.maxOfOrNull { it[1] } ?: floor
        if (gutterTop - pictureTop < MIN_PANEL) return doubleArrayOf(top, bottom)
        return doubleArrayOf(minOf(top, pictureTop), bottom)
    }

    /** Panels that lie wholly between two places, so hold only art. An image not looked at counts as one panel. */
    @Synchronized
    fun artBetween(after: ChapterPos, before: ChapterPos): List<DoubleArray> {
        if (before - after <= MIN_PANEL) return emptyList()
        val cuts = mutableListOf<DoubleArray>()
        for (image in after.toInt()..before.toInt()) {
            val imageGutters = gutters[image]
            if (imageGutters != null) cuts += imageGutters
            else cuts += listOf(doubleArrayOf(image.toDouble(), image.toDouble()), doubleArrayOf(image + 1.0, image + 1.0))
        }
        cuts.sortBy { it[0] }
        val panels = mutableListOf<DoubleArray>()
        var from = after
        for (cut in cuts) {
            if (cut[1] <= from) continue
            if (cut[0] >= before) break
            if (cut[0] - from > MIN_PANEL) panels += doubleArrayOf(from, cut[0])
            from = maxOf(from, cut[1])
        }
        if (before - from > MIN_PANEL) panels += doubleArrayOf(from, before)
        return panels.take(MAX_ART_STOPS)
    }

    companion object {
        /** In chapter positions: about 2% of an image. */
        private const val MIN_PANEL = 0.02
        private const val MAX_ART_STOPS = 20
        /** A band less than this many times the bubble's height holds only the bubble. */
        private const val FLOATING_FILL = 1.6
    }
}
