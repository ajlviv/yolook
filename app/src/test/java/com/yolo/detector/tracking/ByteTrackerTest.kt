package com.yolo.detector.tracking

import android.graphics.RectF
import com.yolo.detector.data.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackerTest {

    @Test
    fun testTrackConfirmationAfterMinHits() {
        val tracker = ByteTracker()
        val box = RectF(0.1f, 0.1f, 0.3f, 0.3f)

        val det1 = listOf(Detection(-1, 2, 0.9f, box, 100L))
        val res1 = tracker.update(det1, 100L)
        // Hit 1: Tentative, not yet confirmed
        assertTrue(res1.isEmpty())

        val res2 = tracker.update(det1, 200L)
        // Hit 2: Tentative
        assertTrue(res2.isEmpty())

        val res3 = tracker.update(det1, 300L)
        // Hit 3: Confirmed (MIN_HITS = 3)
        assertEquals(1, res3.size)
        assertEquals(2, res3[0].classId)
        assertTrue(res3[0].trackId >= 1)
    }

    @Test
    fun testTrackTrackingContinuity() {
        val tracker = ByteTracker()
        val box1 = RectF(0.1f, 0.1f, 0.3f, 0.3f)
        val box2 = RectF(0.12f, 0.12f, 0.32f, 0.32f)

        tracker.update(listOf(Detection(-1, 2, 0.9f, box1, 100L)), 100L)
        tracker.update(listOf(Detection(-1, 2, 0.9f, box1, 200L)), 200L)
        val res3 = tracker.update(listOf(Detection(-1, 2, 0.9f, box1, 300L)), 300L)
        val trackId = res3[0].trackId

        // Move box slightly in frame 4
        val res4 = tracker.update(listOf(Detection(-1, 2, 0.9f, box2, 400L)), 400L)
        assertEquals(1, res4.size)
        assertEquals(trackId, res4[0].trackId) // same track ID maintained
    }
}
