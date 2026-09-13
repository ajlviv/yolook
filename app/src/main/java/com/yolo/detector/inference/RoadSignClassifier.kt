package com.yolo.detector.inference

import com.yolo.detector.data.SignHud
import com.yolo.detector.data.SignType
import kotlin.math.sqrt

/**
 * Classifies priority, regulatory, and warning roadside traffic signs from image crops.
 *
 * Recognizes:
 * - STOP: Octagonal red sign with white lettering.
 * - YIELD / Give Way: Inverted red triangular border with white center.
 * - NO ENTRY: Red circular disc with horizontal white rectangular bar.
 * - PEDESTRIAN CROSSING: Blue square or yellow warning diamond.
 */
object RoadSignClassifier {

    fun classify(pixels: IntArray, width: Int, height: Int): SignHud? {
        if (width < 10 || height < 10 || pixels.size < width * height) return null

        // 1. Check for Speed Limit roundel first
        val speed = SpeedLimitOcr.recognizeSpeedLimit(pixels, width, height)
        if (speed != null) {
            return SignHud(SignType.SPEED_LIMIT, "$speed")
        }

        // 2. Check for NO ENTRY sign (Red disc + horizontal white bar across middle)
        if (isNoEntrySign(pixels, width, height)) {
            return SignHud(SignType.NO_ENTRY, "NO ENTRY")
        }

        // 3. Check for YIELD / Give Way sign (Inverted red triangle)
        if (isYieldSign(pixels, width, height)) {
            return SignHud(SignType.YIELD, "YIELD")
        }

        // 4. Check for Pedestrian Crossing sign (Blue square / Yellow diamond)
        if (isPedestrianCrossingSign(pixels, width, height)) {
            return SignHud(SignType.PEDESTRIAN_CROSSING, "PED XING")
        }

        return null
    }

    /**
     * NO ENTRY: Solid red circular disc with a horizontal white stripe across the center.
     */
    private fun isNoEntrySign(pixels: IntArray, width: Int, height: Int): Boolean {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(centerX, centerY)

        var topRed = 0
        var botRed = 0
        var centerWhite = 0
        var totalDiscPixels = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val dx = x - centerX
                val dy = y - centerY
                val dist = sqrt(dx * dx + dy * dy)

                if (dist <= radius * 0.9f) {
                    totalDiscPixels++
                    val isRed = r > 140 && r > g * 1.35f && r > b * 1.5f
                    val isWhite = r > 140 && g > 140 && b > 140

                    if (y in (height * 0.40f).toInt()..(height * 0.60f).toInt() && x in (width * 0.20f).toInt()..(width * 0.80f).toInt()) {
                        if (isWhite) centerWhite++
                    } else {
                        if (isRed) {
                            if (y < centerY) topRed++ else botRed++
                        }
                    }
                }
            }
        }

        if (totalDiscPixels < 25) return false
        val redFrac = (topRed + botRed).toFloat() / totalDiscPixels
        val whiteFrac = centerWhite.toFloat() / (totalDiscPixels * 0.25f)

        return redFrac > 0.40f && whiteFrac > 0.35f
    }

    /**
     * YIELD: Red triangular border narrowing from top to bottom vertex with white interior.
     */
    private fun isYieldSign(pixels: IntArray, width: Int, height: Int): Boolean {
        var topRedBorder = 0
        var centerWhite = 0
        var bottomRedTip = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val isRed = r > 130 && r > g * 1.3f && r > b * 1.4f
                val isWhite = r > 130 && g > 130 && b > 130

                if (y < height * 0.25f && isRed) topRedBorder++
                if (y in (height * 0.30f).toInt()..(height * 0.65f).toInt() && isWhite) centerWhite++
                if (y > height * 0.75f && x in (width * 0.35f).toInt()..(width * 0.65f).toInt() && isRed) bottomRedTip++
            }
        }

        val total = width * height
        return topRedBorder > total * 0.05f && centerWhite > total * 0.08f && bottomRedTip > 2
    }

    /**
     * PEDESTRIAN CROSSING: Blue square with white/dark center motif.
     */
    private fun isPedestrianCrossingSign(pixels: IntArray, width: Int, height: Int): Boolean {
        var bluePixels = 0
        var darkCenterPixels = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // Blue dominant check
                if (b > 120 && b > r * 1.3f && b > g * 1.15f) {
                    bluePixels++
                }

                // Dark silhouette in center
                if (y in (height * 0.25f).toInt()..(height * 0.75f).toInt() &&
                    x in (width * 0.25f).toInt()..(width * 0.75f).toInt()
                ) {
                    if (r < 90 && g < 90 && b < 90) {
                        darkCenterPixels++
                    }
                }
            }
        }

        val total = width * height
        return bluePixels > total * 0.25f && darkCenterPixels > total * 0.03f
    }
}
