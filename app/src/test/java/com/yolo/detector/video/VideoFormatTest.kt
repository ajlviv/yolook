package com.yolo.detector.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure video-format helpers ([videoWidthForHeight],
 * [videoBitrateFor]) behind the video-recording pipeline.
 */
class VideoFormatTest {

    // ── videoWidthForHeight ─────────────────────────────────────────────────

    @Test
    fun width_forPortraitScreenAspect() {
        // Portrait 9:16 → width/height ≈ 0.5625. 720p → 405, rounded to even.
        assertEquals(406, videoWidthForHeight(720, 9f / 16f))
        // 480p → 270 even already.
        assertEquals(270, videoWidthForHeight(480, 9f / 16f))
        // 1080p → 607.5 → 608 even.
        assertEquals(608, videoWidthForHeight(1080, 9f / 16f))
    }

    @Test
    fun width_alwaysEven() {
        for (h in listOf(480, 720, 1080)) {
            for (aspect in listOf(9f / 16f, 3f / 4f, 1f, 16f / 9f)) {
                val w = videoWidthForHeight(h, aspect)
                assertEquals("Expected even width for h=$h aspect=$aspect", 0, w % 2)
            }
        }
    }

    @Test
    fun width_neverBelowTwo() {
        assertEquals(2, videoWidthForHeight(1, 0.1f))
    }

    // ── videoBitrateFor ─────────────────────────────────────────────────────

    @Test
    fun bitrate_scalesWithResolutionAndFps() {
        assertEquals(5_000_000, videoBitrateFor(480, 30))
        assertEquals(3_000_000, videoBitrateFor(480, 15))
        assertEquals(10_000_000, videoBitrateFor(720, 30))
        assertEquals(7_000_000, videoBitrateFor(720, 15))
        assertEquals(18_000_000, videoBitrateFor(1080, 30))
        assertEquals(12_000_000, videoBitrateFor(1080, 15))
    }

    @Test
    fun bitrate_monotonicInHeight() {
        assertTrue(videoBitrateFor(1080, 30) > videoBitrateFor(720, 30))
        assertTrue(videoBitrateFor(720, 30) > videoBitrateFor(480, 30))
    }
}