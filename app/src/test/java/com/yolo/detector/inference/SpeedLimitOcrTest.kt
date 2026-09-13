package com.yolo.detector.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class SpeedLimitOcrTest {

    private fun createRoundelGrid(size: Int, drawDigit: (x: Int, y: Int) -> Boolean): IntArray {
        val pixels = IntArray(size * size)
        val center = size / 2.0f
        val maxR = center

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = sqrt(dx * dx + dy * dy)
                val norm = dist / maxR

                val color = when {
                    norm in 0.62f..1.02f -> 0xFFFF0000.toInt() // Red outer ring
                    norm < 0.60f -> {
                        if (drawDigit(x, y)) 0xFF000000.toInt() // Black digit stroke
                        else 0xFFFFFFFF.toInt() // White inner core
                    }
                    else -> 0xFF444444.toInt()
                }
                pixels[y * size + x] = color
            }
        }
        return pixels
    }

    @Test
    fun returnsNull_whenNoRedRing() {
        val blankWhite = IntArray(24 * 24) { 0xFFFFFFFF.toInt() }
        val result = SpeedLimitOcr.recognizeSpeedLimit(blankWhite, 24, 24)
        assertNull(result)
    }

    @Test
    fun recognizesSpeedLimit50_syntheticRoundel() {
        val size = 32
        val grid = createRoundelGrid(size) { x, y ->
            // Digit 1: '5' (x 10..15, y 10..22)
            val isFive = (x in 10..15 && (y == 10 || y == 16 || y == 22)) ||
                    (x == 10 && y in 10..16) || (x == 15 && y in 16..22)
            // Digit 2: '0' (x 18..23, y 10..22)
            val isZero = (x in 18..23 && (y == 10 || y == 22)) ||
                    (x == 18 && y in 10..22) || (x == 23 && y in 10..22)
            isFive || isZero
        }
        val result = SpeedLimitOcr.recognizeSpeedLimit(grid, size, size)
        assertNotNull(result)
        assertEquals(50, result)
    }
}
