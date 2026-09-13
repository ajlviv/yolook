package com.yolo.detector.ui

import android.graphics.RectF
import com.yolo.detector.data.Detection
import com.yolo.detector.ui.overlay.countLabelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Live-tab count toggle ([countByClass],
 * [formatCountStats], [countLabelFor]) and the pure Sobel edge-mask
 * ([sobelEdgesMasked]) behind the Edge Detection view mode.
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

    // ── countByClass (per-frame, not cumulative) ─────────────────────────────

    @Test
    fun countByClass_reflectsOnlyCurrentFrame() {
        val frame1 = listOf(det(1, 2), det(2, 2), det(3, 0))
        assertEquals(listOf(2 to 2, 0 to 1), countByClass(frame1))
        // Next frame with different objects: no memory of the previous frame.
        val frame2 = listOf(det(4, 0))
        assertEquals(listOf(0 to 1), countByClass(frame2))
        assertTrue(countByClass(emptyList()).isEmpty())
    }

    @Test
    fun countByClass_countsUntrackedToo() {
        // Per-frame counting includes every detection, tracked or not.
        val counts = countByClass(listOf(det(-1, 2), det(-1, 2)))
        assertEquals(listOf(2 to 2), counts)
    }

    // ── formatCountStats ───────────────────────────────────────────────────

    @Test
    fun format_showsHeaderOnlyWhenEmpty() {
        val text = formatCountStats(10f, 25L, emptyList())
        assertTrue(text.startsWith("FPS: "))
        assertTrue(text.contains("Latency: 25ms"))
        assertTrue(text.contains("Objects: 0"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun format_listsPerClassCounts() {
        val text = formatCountStats(8.5f, 40L, listOf(2 to 3, 0 to 1))
        assertTrue(text.contains("Objects: 4"))
        assertTrue(text.contains("car: 3"))
        assertTrue(text.contains("person: 1"))
    }

    // ── countLabelFor (in-box running numbers) ─────────────────────────────

    @Test
    fun countLabel_showsRunningNumber() {
        assertEquals("1", countLabelFor(1))
        assertEquals("2", countLabelFor(2))
        assertEquals("12", countLabelFor(12))
        // Defensive clamp: never blank.
        assertEquals("1", countLabelFor(0))
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
