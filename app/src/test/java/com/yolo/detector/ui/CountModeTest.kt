package com.yolo.detector.ui

import android.graphics.RectF
import com.yolo.detector.data.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for count-mode helpers in ViewModeEffects: the cumulative
 * [CountTally], the [formatCountStats] HUD formatter, and the pure Sobel
 * edge-mask ([sobelEdgesMasked]).
 */
class CountModeTest {

    private fun box(l: Float, t: Float, r: Float, b: Float): RectF =
        RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }

    private fun det(trackId: Int, classId: Int) =
        Detection(trackId, classId, 0.9f, box(0f, 0f, 0.5f, 0.5f), 100L)

    // ── CountTally ─────────────────────────────────────────────────────────

    @Test
    fun tally_countsEachTrackOnce() {
        val tally = CountTally()
        assertTrue(tally.update(listOf(det(1, 2), det(2, 2))))
        assertEquals(2, tally.total())
        // Same tracks next frame: no change.
        assertFalse(tally.update(listOf(det(1, 2), det(2, 2))))
        assertEquals(2, tally.total())
    }

    @Test
    fun tally_ignoresUntrackedDetections() {
        val tally = CountTally()
        assertFalse(tally.update(listOf(det(-1, 2))))
        assertEquals(0, tally.total())
    }

    @Test
    fun tally_groupsByClassAndSortsDescending() {
        val tally = CountTally()
        tally.update(listOf(det(1, 0), det(2, 2), det(3, 2)))
        val snap = tally.snapshot()
        assertEquals(2, snap.size)
        assertEquals(2 to 2, snap[0]) // car x2 first
        assertEquals(0 to 1, snap[1])
    }

    @Test
    fun tally_clearResetsCounts() {
        val tally = CountTally()
        tally.update(listOf(det(1, 2)))
        tally.clear()
        assertEquals(0, tally.total())
        assertTrue(tally.snapshot().isEmpty())
        // Previously seen track IDs are forgotten, so they count again.
        assertTrue(tally.update(listOf(det(1, 2))))
    }

    // ── formatCountStats ───────────────────────────────────────────────────

    @Test
    fun format_showsHeaderOnlyWhenEmpty() {
        val text = formatCountStats(10f, 25L, emptyList())
        assertTrue(text.startsWith("FPS: "))
        assertTrue(text.contains("Latency: 25ms"))
        assertTrue(text.contains("Total: 0"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun format_listsPerClassCounts() {
        val text = formatCountStats(8.5f, 40L, listOf(2 to 3, 0 to 1))
        assertTrue(text.contains("Total: 4"))
        assertTrue(text.contains("car: 3"))
        assertTrue(text.contains("person: 1"))
    }

    // ── sobelEdgesMasked ───────────────────────────────────────────────────

    @Test
    fun sobel_emptyBoxesProducesAllBlack() {
        val w = 8
        val h = 8
        // Vertical white/black split: strong edge at x=4, but no boxes → masked out.
        val lum = IntArray(w * h) { i -> if ((i % w) < 4) 255 else 0 }
        val out = sobelEdgesMasked(lum, w, h, emptyList())
        assertTrue(out.all { it == android.graphics.Color.BLACK })
    }

    @Test
    fun sobel_detectsEdgeInsideBoxOnly() {
        val w = 9
        val h = 9
        // Vertical split: white left half, black right half → edge at the boundary.
        val lum = IntArray(w * h) { i -> if ((i % w) < 4) 255 else 0 }
        // Box covers only the left flat region (no edge inside).
        val flatBox = listOf(box(0f, 0f, 0.3f, 1f))
        assertTrue(sobelEdgesMasked(lum, w, h, flatBox).all { it == android.graphics.Color.BLACK })
        // Box covers the boundary → edges appear inside, nowhere else.
        val edgeBox = listOf(box(0.3f, 0.3f, 0.7f, 0.7f))
        val out = sobelEdgesMasked(lum, w, h, edgeBox)
        val edgeColor = (255 shl 24) or 0x00E676
        assertTrue(out.any { it == edgeColor })
        // A pixel far outside the box stays black even though an edge exists there.
        assertEquals(android.graphics.Color.BLACK, out[8 * w + 4])
    }

    @Test
    fun sobel_flatImageProducesNoEdges() {
        val out = sobelEdgesMasked(IntArray(25) { 128 }, 5, 5, listOf(box(0f, 0f, 1f, 1f)))
        assertTrue(out.all { it == android.graphics.Color.BLACK })
    }
}
