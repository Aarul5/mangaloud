package io.github.aarul5.mangaloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Base64
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A bubble as the OCR returns it: box is [left, top, right, bottom] as fractions of one slice. */
data class RawBubble(
    val text: String,
    val type: String,
    val box: List<Double>,
    val tone: String = VoiceStyle.NEUTRAL,
    val speaker: String = VoiceStyle.UNKNOWN,
    /** Who says it: a name, a short look ("black-haired swordsman"), or blank when unclear. */
    val character: String = "",
    /** How strongly the tone shows: 1 mild, 2 clear, 3 extreme. */
    val intensity: Int = 2,
    /** A new character's lasting look ("young man, spiky white hair"), on their first line only. */
    val look: String = "",
    /** For a reaction, the sound made: one of [TextKind.SOUNDS], or blank. */
    val sound: String = "",
)

/**
 * What kind of text a bubble holds, as Claude labels it. Older replies only had dialogue,
 * narration and sfx.
 */
object TextKind {
    const val DIALOGUE = "dialogue"
    const val THOUGHT = "thought"
    const val NARRATION = "narration"

    /** A game-style window: quest, skill, item, stat, level up. */
    const val SYSTEM = "system"

    /** A sound a character makes instead of words: "Cough!", "Kyaa!", "Agh!". */
    const val REACTION = "reaction"
    const val SFX = "sfx"
    val ALL = listOf(DIALOGUE, THOUGHT, NARRATION, SYSTEM, REACTION, SFX)

    /** The sounds a reaction can be. Matches the relay's list. */
    val SOUNDS = listOf("cough", "shriek", "groan", "sigh", "gasp", "laugh", "sob", "pant", "grunt")

    /** Sounds the phone voice cannot make: it would say the word ("cough"), so it pauses instead. */
    val UNSPOKEN_SOUNDS = setOf("cough", "sigh", "gasp", "pant", "sob", "grunt")
}

/** Longest look description kept for a character. */
private const val MAX_LOOK = 80

/** A bubble ready to read: box is in fractions of the whole page image. */
data class Bubble(
    val imageIndex: Int,
    val text: String,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val tone: String = VoiceStyle.NEUTRAL,
    val speaker: String = VoiceStyle.UNKNOWN,
    val character: String = "",
    val intensity: Int = 2,
    val look: String = "",
    /** One of [TextKind.ALL] other than sfx. */
    val kind: String = TextKind.DIALOGUE,
    /** For a reaction, the sound made, or blank. */
    val sound: String = "",
    /**
     * How far the bubble runs on into the next image, as a fraction of that image, when the
     * image's bottom edge cuts it in two (see [SeamTwins]). 0 for a bubble inside one image.
     */
    val spill: Double = 0.0,
)

/**
 * One screen-sized piece of a page image. Neighbouring slices overlap so a bubble on a cut
 * line is whole in one of them. Each slice "owns" the band between the overlap midpoints, which
 * marks how far a batch has read.
 *
 * Every bubble a slice lists is kept, even one whose centre lies in its neighbour's band: Claude
 * often lists a bubble in the overlap only once, under the upper slice, and keeping it only in
 * the slice that owned its centre lost it. A bubble listed by both slices is merged into one by
 * [ChapterStore], which matches the two copies by place and text.
 */
class Slice(
    val imageIndex: Int,
    val sliceIndex: Int,
    val sourceUrl: String,
    val jpeg: ByteArray,
    val topFrac: Double,
    val bottomFrac: Double,
    val ownTopFrac: Double,
    val ownBottomFrac: Double,
) {
    val cacheKey get() = "$sourceUrl#$sliceIndex#${ImageSlicer.VERSION}"

    fun toPageBubbles(raw: List<RawBubble>): List<Bubble> = raw.mapNotNull { bubble ->
        if (bubble.type == TextKind.SFX || bubble.box.size != 4) return@mapNotNull null
        // "...", "!!", "?" and the like: nothing to say, and the voice reads them out as "dot dot dot".
        if (!SpeechText.hasWords(bubble.text)) return@mapNotNull null
        val (left, top, right, bottom) = bubble.box.map { it.coerceIn(0.0, 1.0) }
        val height = bottomFrac - topFrac
        val pageTop = topFrac + top * height
        val pageBottom = topFrac + bottom * height
        // Narration and game windows have no character behind them.
        val narration = bubble.type == TextKind.NARRATION || bubble.type == TextKind.SYSTEM
        val speaker = if (narration) VoiceStyle.NARRATOR else bubble.speaker
        val kind = if (bubble.type in TextKind.ALL) bubble.type else TextKind.DIALOGUE
        val character = if (narration) "" else SeriesCast.cleanName(bubble.character)
        Bubble(
            imageIndex, bubble.text.trim(), left, pageTop, right, pageBottom, bubble.tone, speaker, character,
            intensity = bubble.intensity.coerceIn(1, 3),
            look = if (narration) "" else bubble.look.trim().take(MAX_LOOK),
            kind = kind,
            sound = if (kind == TextKind.REACTION && bubble.sound in TextKind.SOUNDS) bubble.sound else "",
        )
    }
}

object ImageFetcher {
    /** Downloads with the page's cookies and referer, since many image hosts check both. */
    fun fetch(src: String, pageUrl: String, userAgent: String): ByteArray {
        if (src.startsWith("data:image/")) {
            return Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
        }
        val connection = URL(src).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Referer", pageUrl)
        connection.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(src)?.let { connection.setRequestProperty("Cookie", it) }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

object ImageSlicer {
    const val VERSION = 1
    private const val SLICE_HEIGHT_PER_WIDTH = 1.4
    private const val OVERLAP_PER_WIDTH = 0.35
    private const val MIN_WIDTH = 200
    private const val MAX_OUTPUT_WIDTH = 1000
    private const val JPEG_QUALITY = 85

    /** True when the bytes decode as a picture: a host under load may answer with an error page instead. */
    fun isImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    fun slice(bytes: ByteArray, imageIndex: Int, sourceUrl: String): List<Slice> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < MIN_WIDTH || height <= 0) return emptyList()

        val bands = bandsFor(width, height)
        val decoder = openRegionDecoder(bytes)
        val sampleSize = if (width >= MAX_OUTPUT_WIDTH * 2) 2 else 1
        try {
            return bands.mapIndexed { i, band ->
                val bitmap = decodeBand(bytes, decoder, width, band, sampleSize)
                val jpeg = toJpeg(bitmap)
                Slice(
                    imageIndex = imageIndex,
                    sliceIndex = i,
                    sourceUrl = sourceUrl,
                    jpeg = jpeg,
                    topFrac = band.top.toDouble() / height,
                    bottomFrac = band.bottom.toDouble() / height,
                    ownTopFrac = band.ownTop.toDouble() / height,
                    ownBottomFrac = if (i == bands.lastIndex) Double.MAX_VALUE else band.ownBottom.toDouble() / height,                )
            }
        } finally {
            decoder?.recycle()
        }
    }

    private class Band(val top: Int, val bottom: Int, var ownTop: Int = 0, var ownBottom: Int = 0)

    private fun bandsFor(width: Int, height: Int): List<Band> {
        val sliceHeight = (width * SLICE_HEIGHT_PER_WIDTH).toInt()
        if (height <= sliceHeight * 1.15) return listOf(Band(0, height, 0, height))

        val step = sliceHeight - (width * OVERLAP_PER_WIDTH).toInt()
        val bands = mutableListOf<Band>()
        var top = 0
        while (true) {
            if (top + sliceHeight >= height) {
                bands += Band(height - sliceHeight, height)
                break
            }
            bands += Band(top, top + sliceHeight)
            top += step
        }
        for (i in bands.indices) {
            bands[i].ownTop = if (i == 0) 0 else (bands[i - 1].bottom + bands[i].top) / 2
            bands[i].ownBottom = if (i == bands.lastIndex) height else (bands[i].bottom + bands[i + 1].top) / 2
        }
        return bands
    }

    @Suppress("DEPRECATION")
    private fun openRegionDecoder(bytes: ByteArray): BitmapRegionDecoder? = try {
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    } catch (e: IOException) {
        null // GIF and some odd formats: fall back to a full decode.
    }

    private fun decodeBand(bytes: ByteArray, decoder: BitmapRegionDecoder?, width: Int, band: Band, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        if (decoder != null) {
            return decoder.decodeRegion(Rect(0, band.top, width, band.bottom), options)
                ?: throw IOException("Could not decode image region")
        }
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Could not decode image")
        val top = band.top / sampleSize
        val bandHeight = ((band.bottom - band.top) / sampleSize).coerceAtMost(full.height - top)
        val cropped = Bitmap.createBitmap(full, 0, top, full.width, bandHeight)
        if (cropped !== full) full.recycle()
        return cropped
    }

    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val scaled = if (bitmap.width > MAX_OUTPUT_WIDTH) {
            val height = (bitmap.height.toDouble() * MAX_OUTPUT_WIDTH / bitmap.width).toInt()
            Bitmap.createScaledBitmap(bitmap, MAX_OUTPUT_WIDTH, height, true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }
}
