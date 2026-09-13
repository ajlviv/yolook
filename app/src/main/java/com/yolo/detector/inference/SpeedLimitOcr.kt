package com.yolo.detector.inference

import kotlin.math.sqrt

/**
 * Real-time, zero-allocation Speed Limit Roundel & Digit Recognition Engine.
 *
 * Detects circular red-ring regulatory speed limit signs and extracts the numerical
 * speed limit value (20..130 km/h or mph) using structural shape and digit stroke analysis.
 */
object SpeedLimitOcr {

    private val VALID_SPEED_LIMITS = setOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)

    /**
     * Analyzes [pixels] (packed ARGB) of a crop with dimensions [width]x[height].
     * Returns recognized speed limit integer or null if not a valid speed limit sign.
     */
    fun recognizeSpeedLimit(pixels: IntArray, width: Int, height: Int): Int? {
        if (width < 10 || height < 10 || pixels.size < width * height) return null

        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val maxRadius = minOf(centerX, centerY)

        var outerRedPixels = 0
        var outerTotalPixels = 0
        var innerWhitePixels = 0
        var innerDarkPixels = 0
        var innerTotalPixels = 0

        var minDigitX = width
        var maxDigitX = 0
        var minDigitY = height
        var maxDigitY = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val dx = x - centerX
                val dy = y - centerY
                val dist = sqrt(dx * dx + dy * dy)
                val normDist = dist / maxRadius

                if (normDist in 0.65f..1.05f) {
                    outerTotalPixels++
                    // Red outer ring test
                    if (r > 130 && r > g * 1.35f && r > b * 1.5f) {
                        outerRedPixels++
                    }
                } else if (normDist < 0.60f) {
                    innerTotalPixels++
                    val maxC = maxOf(r, maxOf(g, b))
                    val minC = minOf(r, minOf(g, b))
                    val isLowSaturation = (maxC - minC) < 60

                    // Dark digit pixel inside white inner core
                    if (r < 110 && g < 110 && b < 110) {
                        innerDarkPixels++
                        if (x < minDigitX) minDigitX = x
                        if (x > maxDigitX) maxDigitX = x
                        if (y < minDigitY) minDigitY = y
                        if (y > maxDigitY) maxDigitY = y
                    } else if (r > 130 && g > 130 && b > 130 && isLowSaturation) {
                        innerWhitePixels++
                    }
                }
            }
        }

        // Validate red outer circular ring presence
        if (outerTotalPixels == 0 || outerRedPixels.toFloat() / outerTotalPixels < 0.35f) {
            return null
        }

        // Validate inner core has white background and dark digit strokes
        if (innerTotalPixels == 0 || innerWhitePixels.toFloat() / innerTotalPixels < 0.25f) {
            return null
        }
        if (innerDarkPixels < 5 || minDigitX > maxDigitX || minDigitY > maxDigitY) {
            return null
        }

        // Segment and classify digits in bounding box [minDigitX..maxDigitX, minDigitY..maxDigitY]
        return parseDigits(pixels, width, minDigitX, minDigitY, maxDigitX, maxDigitY)
    }

    private fun parseDigits(
        pixels: IntArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Int? {
        val digitW = x1 - x0 + 1
        val digitH = y1 - y0 + 1
        if (digitW < 3 || digitH < 5) return null

        val aspectRatio = digitW.toFloat() / digitH.toFloat()

        if (aspectRatio > 1.4f) {
            // Likely 3 digits (e.g. 100, 110, 120, 130)
            val third1 = x0 + digitW / 3
            val third2 = x0 + 2 * digitW / 3
            val d1 = classifySingleDigit(pixels, stride, x0, y0, third1, y1)
            val d2 = classifySingleDigit(pixels, stride, third1 + 1, y0, third2, y1)
            val d3 = classifySingleDigit(pixels, stride, third2 + 1, y0, x1, y1)
            if (d1 != null && d2 != null && d3 != null) {
                val speed = d1 * 100 + d2 * 10 + d3
                if (speed in VALID_SPEED_LIMITS) return speed
            }
        }

        if (aspectRatio > 0.65f) {
            // Likely 2 digits (e.g. 30, 50, 60, 80)
            val midX = x0 + digitW / 2
            val d1 = classifySingleDigit(pixels, stride, x0, y0, midX, y1)
            val d2 = classifySingleDigit(pixels, stride, midX + 1, y0, x1, y1)
            if (d1 != null && d2 != null) {
                val speed = d1 * 10 + d2
                if (speed in VALID_SPEED_LIMITS) return speed
            }
        }

        // Single digit speed limits (e.g. 5)
        val single = classifySingleDigit(pixels, stride, x0, y0, x1, y1)
        if (single != null && single * 10 in VALID_SPEED_LIMITS) {
            return single * 10
        }

        return null
    }

    private fun classifySingleDigit(
        pixels: IntArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int? {
        val w = right - left + 1
        val h = bottom - top + 1
        if (w <= 0 || h <= 0) return null

        // Digit '1': tall, narrow vertical line
        if (w.toFloat() / h.toFloat() < 0.45f) {
            return 1
        }

        // Subdivide digit into 3 vertical zones (top, middle, bottom) and 2 horizontal zones (left, right)
        val midY = top + h / 2
        val midX = left + w / 2

        var topDark = 0
        var midDark = 0
        var botDark = 0
        var leftDark = 0
        var rightDark = 0
        var centerHole = 0

        for (y in top..bottom) {
            for (x in left..right) {
                val p = pixels[y * stride + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val isDark = (r < 110 && g < 110 && b < 110)

                if (isDark) {
                    if (y < top + h / 3) topDark++
                    else if (y < top + 2 * h / 3) midDark++
                    else botDark++

                    if (x < midX) leftDark++ else rightDark++
                } else if (y in (top + h / 4)..(bottom - h / 4) && x in (left + w / 4)..(right - w / 4)) {
                    centerHole++
                }
            }
        }

        // Digit '0': hollow center with surrounding dark border
        if (centerHole > (w * h) / 8 && leftDark > 2 && rightDark > 2 && topDark > 2 && botDark > 2) {
            return 0
        }

        // Digit '8': two hollow sub-loops with heavy top, middle, and bottom
        if (topDark > 2 && midDark > 2 && botDark > 2 && leftDark > 3 && rightDark > 3) {
            return 8
        }

        // Digit '3': right heavy, with top, middle, bottom bars
        if (rightDark > leftDark * 1.3f && topDark > 1 && botDark > 1) {
            return 3
        }

        // Digit '5': top bar, left top, right bottom
        if (topDark > 1 && botDark > 1) {
            return 5
        }

        // Digit '6': left heavy with closed bottom loop
        if (leftDark > rightDark && botDark > topDark) {
            return 6
        }

        // Digit '9': top closed loop, right heavy bottom
        if (topDark > botDark && rightDark >= leftDark) {
            return 9
        }

        // Digit '2' or '7'
        if (topDark > 2 && rightDark > 1) {
            return if (botDark > 2) 2 else 7
        }

        return 0
    }
}
