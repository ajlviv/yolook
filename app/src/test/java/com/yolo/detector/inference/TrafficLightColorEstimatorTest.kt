package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficLightColorEstimatorTest {

    private val red = IntArray(10) { 0xFFFF0000.toInt() }      // pure red
    private val yellow = IntArray(10) { 0xFFFFDD00.toInt() }   // amber
    private val green = IntArray(10) { 0xFF00FF00.toInt() }    // pure green
    private val dark = IntArray(10) { 0xFF000000.toInt() }     // off / housing

    @Test
    fun testRedDominant() {
        assertEquals(TrafficLightSignal.RED, TrafficLightColorEstimator.classify(red))
    }

    @Test
    fun testYellowDominant() {
        assertEquals(TrafficLightSignal.YELLOW, TrafficLightColorEstimator.classify(yellow))
    }

    @Test
    fun testGreenDominant() {
        assertEquals(TrafficLightSignal.GREEN, TrafficLightColorEstimator.classify(green))
    }

    @Test
    fun testAllDarkIsOff() {
        assertEquals(TrafficLightSignal.OFF, TrafficLightColorEstimator.classify(dark))
    }

    @Test
    fun testMixedMajorityWins() {
        // 7 red pixels + 3 green → red.
        val mixed = IntArray(10) { i -> if (i < 7) 0xFFFF0000.toInt() else 0xFF00FF00.toInt() }
        assertEquals(TrafficLightSignal.RED, TrafficLightColorEstimator.classify(mixed))
    }

    @Test
    fun testEmptyIsOff() {
        assertEquals(TrafficLightSignal.OFF, TrafficLightColorEstimator.classify(IntArray(0)))
    }

    // ── Blob-aware regression tests ──────────────────────────────────────────
    //
    // These model the failure mode behind the reference image: a solid lit lamp
    // surrounded by blue sky and JPEG chroma fringes (magenta / yellow-green edge
    // artifacts). A naive whole-bbox pixel vote would pick up the fringe colours and
    // report a wrong signal; the blob detector must still report the real lamp.

    private val sky = 0xFF5274A5.toInt()          // muted blue (not saturated enough to qualify)
    private val magentaFringe = 0xFFFF506F.toInt() // 255,80,111 — classic chroma edge artifact
    private val yellowFringe = 0xFF99FF50.toInt()  // 153,255,80 — chroma edge artifact
    private val housing = 0xFF1A1A1A.toInt()       // dark housing / backplate

    private fun grid(width: Int, height: Int, fill: (Int, Int) -> Int): IntArray {
        val p = IntArray(width * height)
        for (r in 0 until height) {
            for (c in 0 until width) {
                p[r * width + c] = fill(r, c)
            }
        }
        return p
    }

    @Test
    fun testGreenLampAmidSkyAndFringes() {
        // A solid green lamp blob (bottom-centre) among blue sky and scattered fringes.
        val pixels = grid(10, 10) { r, c ->
            if (r >= 6 && r <= 9 && c >= 3 && c <= 6) 0xFF00FF00.toInt()
            else if ((r == 1 && c == 2) || (r == 4 && c == 7) || (r == 8 && c == 1)) magentaFringe
            else if ((r == 3 && c == 1) || (r == 1 && c == 8)) yellowFringe
            else sky
        }
        assertEquals(TrafficLightSignal.GREEN, TrafficLightColorEstimator.classify(pixels))
    }

    @Test
    fun testFringingWithoutLampIsUnknown() {
        // Scattered chroma fringes and sky, but no coherent lamp → must not guess a colour.
        val pixels = grid(10, 10) { r, c -> if ((r + c) % 4 == 0) magentaFringe else sky }
        assertEquals(TrafficLightSignal.UNKNOWN, TrafficLightColorEstimator.classify(pixels))
    }

    @Test
    fun testWarmGreenLampAmidSkyIsGreen() {
        // A warm/yellow-green lamp (0x96FF78 = 150,255,120) used to be classified YELLOW
        // because the old hue test checked YELLOW first and only required r > 1.2*b.
        // It must now be GREEN.
        val pixels = grid(10, 10) { r, c ->
            if (r >= 6 && r <= 9 && c >= 3 && c <= 6) 0xFF96FF78.toInt()
            else if (r >= 1 && r <= 3 && c >= 3 && c <= 6) 0xFF1A1A1A.toInt()
            else sky
        }
        assertEquals(TrafficLightSignal.GREEN, TrafficLightColorEstimator.classify(pixels))
    }

    @Test
    fun testAmberIsStillYellow() {
        // A true amber lamp (250,220,60) must remain YELLOW, not bleed into GREEN.
        val pixels = grid(10, 10) { r, c ->
            if (r >= 6 && r <= 9 && c >= 3 && c <= 6) 0xFFFADC3C.toInt()
            else housing
        }
        assertEquals(TrafficLightSignal.YELLOW, TrafficLightColorEstimator.classify(pixels))
    }

    @Test
    fun testRedLampOnDarkHousing() {
        // A compact red lamp at the top of a dark housing → red.
        val pixels = grid(10, 10) { r, c ->
            if (r <= 2 && c >= 3 && c <= 6) 0xFFFF2020.toInt() else housing
        }
        assertEquals(TrafficLightSignal.RED, TrafficLightColorEstimator.classify(pixels))
    }

    @Test
    fun testTwoCompetingBlobsIsUnknown() {
        // Two equal-sized, equally bright lamp blobs → ambiguous, must not pick one.
        val pixels = grid(10, 10) { r, c ->
            if (c < 5) 0xFFFF0000.toInt() else 0xFFFFDD00.toInt()
        }
        assertEquals(TrafficLightSignal.UNKNOWN, TrafficLightColorEstimator.classify(pixels))
    }

    @Test
    fun testScatteredFringesOnDarkIsUnknown() {
        // Scattered fringes that are too small/thin to form a lamp and no lamp → not a definite colour.
        val pixels = grid(10, 10) { r, c -> if ((r + c) % 4 == 0) magentaFringe else 0xFF000000.toInt() }
        assertEquals(TrafficLightSignal.UNKNOWN, TrafficLightColorEstimator.classify(pixels))
    }
}