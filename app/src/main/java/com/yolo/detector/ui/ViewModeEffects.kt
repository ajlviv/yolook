package com.yolo.detector.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

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