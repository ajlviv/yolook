package com.yolo.detector.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RectF
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
 * Count-mode edge threshold on the Sobel gradient magnitude (0-255 luminance
 * units). A pixel is an "edge" when |Gx| + |Gy| >= this value. 100 keeps
 * strong object contours while suppressing flat-region noise; tune up if the
 * overlay looks speckled, down if thin object outlines disappear.
 */
const val COUNT_EDGE_THRESHOLD = 100

/** Count-mode edge colour: app accent green (#00E676), fully opaque. */
private const val COUNT_EDGE_COLOR: Int = (255 shl 24) or 0x00E676

/**
 * Count-mode cumulative tally: class ID -> number of distinct confirmed
 * track IDs ever seen for that class. Mutations and reads run on the main
 * thread (UI collectors), so no locking is needed.
 */
class CountTally {
    private val seenTracks = mutableSetOf<Int>()
    private val counts = mutableMapOf<Int, Int>()

    /**
     * Folds one frame's tracked detections into the tally. Only detections
     * with a real track ID (>= 0) count — untracked raw output (trackId = -1)
     * is skipped. A track ID is counted once, under its first-seen class.
     *
     * @return true if the tally changed (a new track ID was seen).
     */
    fun update(detections: List<Detection>): Boolean {
        var changed = false
        for (det in detections) {
            if (det.trackId < 0) continue
            if (seenTracks.add(det.trackId)) {
                counts[det.classId] = (counts[det.classId] ?: 0) + 1
                changed = true
            }
        }
        return changed
    }

    /** Per-class cumulative counts, sorted by count descending. */
    fun snapshot(): List<Pair<Int, Int>> =
        counts.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** Total objects counted across all classes. */
    fun total(): Int = counts.values.sum()

    /** Clears all counts (e.g. when leaving count mode). */
    fun clear() {
        seenTracks.clear()
        counts.clear()
    }
}

/**
 * Formats the Live HUD line(s) for count mode. Line 1 mirrors the default
 * stats line with a cumulative Total; following lines list per-class counts
 * ("label: n") sorted by count descending.
 */
fun formatCountStats(
    fps: Float,
    inferenceMs: Long,
    tally: List<Pair<Int, Int>>,
): String {
    val total = tally.sumOf { it.second }
    val header = "FPS: ${"%.1f".format(fps)}  Latency: ${inferenceMs}ms  Total: $total"
    if (tally.isEmpty()) return header
    val perClass = tally.joinToString("\n") { (classId, count) -> "${labelFor(classId)}: $count" }
    return "$header\n$perClass"
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
 * Sobel edge detection over a luminance image, masked to normalised detection
 * boxes: only pixels inside at least one box can be edges; everything else is
 * black. Luminance is row-major ([y * w + x]); boxes use normalised [0,1]
 * coords. Pure function — unit-testable without a device.
 *
 * @param threshold gradient-magnitude cutoff (see [COUNT_EDGE_THRESHOLD]).
 * @return row-major packed ARGB pixels (green edge on opaque black).
 */
fun sobelEdgesMasked(
    luminance: IntArray,
    w: Int,
    h: Int,
    boxes: List<RectF>,
    threshold: Int = COUNT_EDGE_THRESHOLD,
): IntArray {
    val out = IntArray(w * h)
    if (w < 3 || h < 3) {
        out.fill(Color.BLACK)
        return out
    }
    // Precompute the inside-box mask so Sobel runs only where it matters.
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
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            if (!inside[i]) {
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
            out[i] = if (mag >= threshold) COUNT_EDGE_COLOR else Color.BLACK
        }
    }
    return out
}

/**
 * Returns an "objects only" copy of this bitmap: Sobel edges inside detection
 * boxes drawn in green on a black background; everything outside the boxes is
 * black. Baked at reduced resolution for speed, scaled up by the ImageView.
 *
 * `this` is not modified or recycled; the caller owns the returned bitmap.
 *
 * @param downscale 1 = full resolution, 2 = half resolution (4x fewer pixels).
 */
fun Bitmap.applyCountEdges(
    detections: List<Detection>,
    downscale: Int = 2,
    threshold: Int = COUNT_EDGE_THRESHOLD,
): Bitmap {
    val tw = (width / downscale).coerceAtLeast(1)
    val th = (height / downscale).coerceAtLeast(1)
    val small = if (tw != width || th != height) {
        Bitmap.createScaledBitmap(this, tw, th, true)
    } else {
        this.copy(Bitmap.Config.ARGB_8888, true)
    }
    val gray = small.toLuminance()
    val pixels = sobelEdgesMasked(gray, tw, th, detections.map { it.bbox }, threshold)
    small.setPixels(pixels, 0, tw, 0, 0, tw, th)
    return small
}

