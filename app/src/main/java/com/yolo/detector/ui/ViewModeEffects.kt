package com.yolo.detector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.yolo.detector.data.Detection
import com.yolo.detector.data.labelFor

/**
 * Visual-effect helpers for the [com.yolo.detector.data.ViewMode] filters.
 */
object ViewModeEffects {

    /**
     * GPU color-matrix for grayscale (black & white).
     * Uses Rec.601 luminance weights so perceived brightness is preserved.
     */
    fun blackAndWhiteColorFilter(): ColorMatrixColorFilter {
        val m = ColorMatrix(
            floatArrayOf(
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        return ColorMatrixColorFilter(m)
    }

    /** GPU color-matrix that inverts the RGB channels (alpha untouched). */
    fun invertColorFilter(): ColorMatrixColorFilter {
        val m = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        return ColorMatrixColorFilter(m)
    }
}

/** Full alpha for baked pixels (255 shl 24 stays Int, unlike the Long literal 0xFF000000). */
private const val FULL_ALPHA: Int = 255 shl 24

/**
 * Lookup table mapping luminance [0,255] → packed ARGB heatmap color.
 * Built once lazily; avoids per-pixel HSV computation for a large speedup.
 */
private var heatmapLut: IntArray? = null

private fun heatmapLUT(): IntArray {
    var lut = heatmapLut
    if (lut != null) return lut
    lut = IntArray(256)
    val hsv = FloatArray(3)
    for (g in 0..255) {
        val t = g / 255f
        hsv[0] = (1f - t) * 240f        // 240 (blue) → 0 (red)
        hsv[1] = 1f
        hsv[2] = 0.35f + 0.65f * t      // dim → bright
        lut[g] = FULL_ALPHA or (Color.HSVToColor(hsv) and 0x00FFFFFF)
    }
    heatmapLut = lut
    return lut
}

/**
 * Returns a hot-metal heatmap copy of this bitmap.
 *
 * Each pixel's Rec.601 luminance [0,255] is mapped through a precomputed
 * blue→red lookup table, giving the classic "thermal" look. The frame is baked
 * at reduced resolution for speed and the result scaled up by the ImageView.
 *
 * `this` is not modified or recycled; the caller keeps ownership of it and owns
 * the newly returned bitmap.
 *
 * @param downscale  1 = full resolution, 2 = half resolution (4× fewer pixels).
 * @return a new bitmap with the heatmap colormap baked in.
 */
fun Bitmap.applyHeatmap(downscale: Int = 2): Bitmap {
    val tw = (width / downscale).coerceAtLeast(1)
    val th = (height / downscale).coerceAtLeast(1)
    val scaleDown = tw != width || th != height

    // The result is always a new, MUTABLE bitmap (a downscaled copy or a full-size
    // copy) because it is baked with setPixels below. `this` is left untouched,
    // so the caller controls its lifetime (and can recycle it even if canceled).
    val src = if (scaleDown) {
        Bitmap.createScaledBitmap(this, tw, th, true)
    } else {
        this.copy(Bitmap.Config.ARGB_8888, true)
    }
    val pixels = IntArray(tw * th)
    src.getPixels(pixels, 0, tw, 0, 0, tw, th)

    val lut = heatmapLUT()
    for (i in pixels.indices) {
        val p = pixels[i]
        val gray = (0.2126f * ((p shr 16) and 0xFF) +
                0.7152f * ((p shr 8) and 0xFF) +
                0.0722f * (p and 0xFF)).toInt()
        pixels[i] = lut[gray.coerceIn(0, 255)]
    }
    src.setPixels(pixels, 0, tw, 0, 0, tw, th)

    return src
}

/**
 * Edge-view threshold on the Sobel gradient magnitude (0-255 luminance
 * units). A pixel is an "edge" when |Gx| + |Gy| >= this value. 100 keeps
 * strong object contours while suppressing flat-region noise; tune up if the
 * overlay looks speckled, down if thin object outlines disappear.
 */
const val EDGE_THRESHOLD = 100

/** Edge-view colour: app accent green (#00E676), fully opaque. */
private const val EDGE_COLOR: Int = (255 shl 24) or 0x00E676

/**
 * Groups one frame's detections into per-class counts, sorted by count
 * descending. Pure function — reflects only the current frame (nothing
 * cumulative). Used by the Live-tab count toggle HUD.
 */
fun countByClass(detections: List<Detection>): List<Pair<Int, Int>> {
    if (detections.isEmpty()) return emptyList()
    val counts = mutableMapOf<Int, Int>()
    for (det in detections) {
        counts[det.classId] = (counts[det.classId] ?: 0) + 1
    }
    return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/**
 * Formats the Live HUD line(s) for the count toggle. Line 1 mirrors the
 * default stats line; following lines list this frame's per-class counts
 * ("label: n") sorted by count descending.
 */
fun formatCountStats(
    fps: Float,
    inferenceMs: Long,
    perClass: List<Pair<Int, Int>>,
): String {
    val objects = perClass.sumOf { it.second }
    val header = "FPS: ${"%.1f".format(fps)}  Latency: ${inferenceMs}ms  Objects: $objects"
    if (perClass.isEmpty()) return header
    val lines = perClass.joinToString("\n") { (classId, count) -> "${labelFor(classId)}: $count" }
    return "$header\n$lines"
}

/**
 * Converts this bitmap to a single-channel luminance array (row-major,
 * values 0-255). Pure function on the pixel data. Uses Rec.601 weights to
 * match the existing B&W/heatmap filters.
 */
fun Bitmap.toLuminance(): IntArray {
    val w = width
    val h = height
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)
    return IntArray(w * h) { i ->
        val p = pixels[i]
        (0.2126f * ((p shr 16) and 0xFF) +
                0.7152f * ((p shr 8) and 0xFF) +
                0.0722f * (p and 0xFF)).toInt().coerceIn(0, 255)
    }
}


/**
 * Returns a copy of this bitmap with everything outside the normalised
 * detection [boxes] blacked out ("objects only"). Baked at reduced resolution
 * for speed, scaled up by the ImageView.
 *
 * `this` is not modified or recycled; the caller owns the returned bitmap.
 *
 * @param downscale 1 = full resolution, 2 = half resolution (4x fewer pixels).
 */
fun Bitmap.applyObjectsOnlyMask(
    boxes: List<RectF>,
    downscale: Int = 2,
): Bitmap {
    val tw = (width / downscale).coerceAtLeast(1)
    val th = (height / downscale).coerceAtLeast(1)
    val small = if (tw != width || th != height) {
        Bitmap.createScaledBitmap(this, tw, th, true)
    } else {
        this.copy(Bitmap.Config.ARGB_8888, true)
    }
    val mask = boxesMask(tw, th, boxes)
    val pixels = IntArray(tw * th)
    small.getPixels(pixels, 0, tw, 0, 0, tw, th)
    for (i in pixels.indices) {
        if (!mask[i]) pixels[i] = Color.BLACK
    }
    small.setPixels(pixels, 0, tw, 0, 0, tw, th)
    return small
}

/**
 * Builds the normalised-boxes coverage mask used by [sobelEdgesMasked] and
 * [applyObjectsOnlyMask]: row-major booleans, true = pixel inside a box.
 * Pure function — unit-testable without a device.
 */
fun boxesMask(w: Int, h: Int, boxes: List<RectF>): BooleanArray {
    val inside = BooleanArray(w * h)
    for (box in boxes) {
        val left = (box.left * w).toInt().coerceIn(0, w - 1)
        val top = (box.top * h).toInt().coerceIn(0, h - 1)
        val right = (box.right * w).toInt().coerceIn(0, w - 1)
        val bottom = (box.bottom * h).toInt().coerceIn(0, h - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                inside[y * w + x] = true
            }
        }
    }
    return inside
}

/**
 * Sobel edge detection over a luminance image, masked to normalised detection
 * boxes when [boxes] is non-null: only pixels inside at least one box can be
 * edges; everything else is black. Pass null to run edges over the entire
 * screen (used by the Edge Detection view mode). Luminance is row-major
 * ([y * w + x]); boxes use normalised [0,1] coords. Pure function —
 * unit-testable without a device.
 *
 * @param threshold gradient-magnitude cutoff (see [EDGE_THRESHOLD]).
 * @return row-major packed ARGB pixels (green edge on opaque black).
 */
fun sobelEdgesMasked(
    luminance: IntArray,
    w: Int,
    h: Int,
    boxes: List<RectF>?,
    threshold: Int = EDGE_THRESHOLD,
): IntArray {
    val out = IntArray(w * h)
    if (w < 3 || h < 3) {
        out.fill(Color.BLACK)
        return out
    }
    // Null boxes = full screen: every pixel is a candidate edge.
    val inside = boxes?.let { boxesMask(w, h, it) }
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            if (inside != null && !inside[i]) {
                out[i] = Color.BLACK
                continue
            }
            // Clamped (edge-replicated) sampling: border pixels still get edges.
            val xm = (x - 1).coerceAtLeast(0)
            val xp = (x + 1).coerceAtMost(w - 1)
            val ym = (y - 1).coerceAtLeast(0)
            val yp = (y + 1).coerceAtMost(h - 1)
            val gx = -luminance[ym * w + xm] - 2 * luminance[y * w + xm] - luminance[yp * w + xm] +
                    luminance[ym * w + xp] + 2 * luminance[y * w + xp] + luminance[yp * w + xp]
            val gy = -luminance[ym * w + xm] - 2 * luminance[ym * w + x] - luminance[ym * w + xp] +
                    luminance[yp * w + xm] + 2 * luminance[yp * w + x] + luminance[yp * w + xp]
            // |Gx| + |Gy| is the cheap L1 approximation of gradient magnitude.
            val mag = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
            out[i] = if (mag >= threshold) EDGE_COLOR else Color.BLACK
        }
    }
    return out
}

/**
 * Returns an edge-rendered copy of this bitmap: Sobel edges in green on a
 * black background, baked at reduced resolution for speed and scaled up by
 * the ImageView.
 *
 * Pass null [boxes] for full-screen edges (Edge Detection view mode default),
 * or a box list to mask edges to detections only (used when "Detection view"
 * is "Only objects"). `this` is not modified or recycled; the caller owns
 * the returned bitmap.
 *
 * @param downscale 1 = full resolution, 2 = half resolution (4x fewer pixels).
 */
fun Bitmap.applyEdgeDetection(
    boxes: List<RectF>?,
    downscale: Int = 2,
    threshold: Int = EDGE_THRESHOLD,
): Bitmap {
    val tw = (width / downscale).coerceAtLeast(1)
    val th = (height / downscale).coerceAtLeast(1)
    val small = if (tw != width || th != height) {
        Bitmap.createScaledBitmap(this, tw, th, true)
    } else {
        this.copy(Bitmap.Config.ARGB_8888, true)
    }
    val gray = small.toLuminance()
    val pixels = sobelEdgesMasked(gray, tw, th, boxes, threshold)
    small.setPixels(pixels, 0, tw, 0, 0, tw, th)
    return small
}

/**
 * Side (px, in the baked reduced frame) of one Matrix glyph grid cell.
 * Smaller = a finer, more readable grid (more glyphs per frame). 8px keeps the
 * per-frame glyph count modest so the bake stays cheap on a background thread.
 */
const val MATRIX_CELL_SIZE: Int = 8

/**
 * Glyph ramp for the Matrix effect, ordered sparse (dim) → dense (bright) so a
 * cell's brightness maps monotonically to glyph density. The leading space keeps
 * near-black cells empty; ASCII tone characters give smooth mid-tones; katakana
 * at the bright end supplies the movie's recognizable "digital rain" glyphs.
 */
private val MATRIX_GLYPHS: String =
    " .'`:,-_=+*^/\\<>#%&@アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホ"

/** Gamma < 1 lifts mid-tones so shadowed regions still render readable glyphs. */
const val DEFAULT_MATRIX_GAMMA: Float = 0.74f

/**
 * Returns a Matrix-style rendition of this bitmap: the frame is broken into a
 * grid and each cell's brightness maps to a bright-green glyph drawn on an
 * opaque black background, emulating the movie's "digital rain" (CCTV) look.
 *
 * Per-cell brightness is a 50/50 blend of the cell's average and maximum
 * luminance. Blending in the max preserves thin bright features — limbs, text,
 * object edges — that a pure average would wash out, which is what makes the
 * resulting shapes readable. The blended value passes through an S-curve
 * contrast stage plus a mild gamma lift for crisp mid-tone separation, then maps
 * to glyph density and green brightness. Each glyph is monospace-sized and
 * FontMetrics-centered within its cell so rows tile cleanly into a crisp,
 * terminal-like ASCII render.
 *
 * Baked at reduced resolution for speed and scaled up by the ImageView.
 *
 * `this` is not modified or recycled; the caller owns the returned bitmap.
 *
 * @param downscale 1 = full resolution, 2 = half (4x fewer pixels), etc. The
 *   glyph grid size is [cellSize] in the baked frame, so a larger downscale
 *   yields chunkier glyphs and faster baking.
 * @param cellSize side in px of one glyph cell in the baked frame; smaller gives
 *   a finer, more readable grid but slightly more per-frame work.
 * @param gamma < 1 lifts mid-tones so shadowed regions stay readable; use 1f
 *   (or higher) for more contrast with darker shadows.
 */
fun Bitmap.applyMatrixEffect(
    downscale: Int = 2,
    cellSize: Int = MATRIX_CELL_SIZE,
    gamma: Float = DEFAULT_MATRIX_GAMMA,
): Bitmap {
    val tw = (width / downscale).coerceAtLeast(1)
    val th = (height / downscale).coerceAtLeast(1)
    val small = if (tw != width || th != height) {
        Bitmap.createScaledBitmap(this, tw, th, true)
    } else {
        this.copy(Bitmap.Config.ARGB_8888, true)
    }

    val srcPixels = IntArray(tw * th)
    small.getPixels(srcPixels, 0, tw, 0, 0, tw, th)
    // Rec.601 luminance per pixel, reused for the per-cell brightness below.
    val gray = IntArray(tw * th) { i ->
        val p = srcPixels[i]
        (0.2126f * ((p shr 16) and 0xFF) +
                0.7152f * ((p shr 8) and 0xFF) +
                0.0722f * (p and 0xFF)).toInt()
    }

    val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)

    val cell = cellSize.coerceAtLeast(1)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        // Monospace glyph sized to one row; glyphs are vertically centered per
        // cell below via FontMetrics so rows tile cleanly instead of bleeding
        // into each other (which made the effect look muddy before).
        textSize = cell.toFloat()
    }
    // Baseline that centers the glyph's ink within its cell, giving a crisp,
    // even "terminal" look rather than cramped, vertically-overlapping rows.
    val fm = paint.fontMetrics
    val baselineOffset = (cell - (fm.descent - fm.ascent)) * 0.5f - fm.ascent
    val glyphs = MATRIX_GLYPHS
    val glyphCount = glyphs.length - 1

    var gy = 0
    while (gy < th) {
        var gx = 0
        while (gx < tw) {
            val xEnd = (gx + cell).coerceAtMost(tw)
            val yEnd = (gy + cell).coerceAtMost(th)
            // Average AND max luminance in one pass; blending them keeps thin
            // bright features (limbs, text, edges) bled across the cell so fine
            // objects stay recognizable instead of averaging into flat blobs.
            var sum = 0L
            var max = 0
            for (y in gy until yEnd) {
                val row = y * tw
                for (x in gx until xEnd) {
                    val v = gray[row + x]
                    sum += v
                    if (v > max) max = v
                }
            }
            val n = (xEnd - gx) * (yEnd - gy)
            val avg = (sum / n).toInt()
            val lum = ((avg + max) / 2).coerceIn(0, 255)

            // S-curve (smoothstep) contrast for sharper mid-tone separation, then a
            // mild gamma lift so shadows stay visible. One extra multiply per cell.
            val t = lum / 255f
            val c = t * t * (3f - 2f * t)
            val v = (255.0 * Math.pow(c.toDouble(), gamma.toDouble())).toInt().coerceIn(0, 255)
            val gi = (v * glyphCount / 255).coerceIn(0, glyphCount)
            val g = 100 + (v * 150) / 255 // 100 (dim) .. 250 (bright), always readable
            // Slightly yellow-green like the film's #4AF626.
            paint.color = (255 shl 24) or (g * 2 / 5 shl 16) or (g shl 8) or (g * 2 / 5)

            canvas.drawText(glyphs[gi].toString(), (gx + cell * 0.5f), (gy + baselineOffset), paint)
            gx += cell
        }
        gy += cell
    }

    small.recycle()
    return out
}

