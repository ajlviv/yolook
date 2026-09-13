package com.yolo.detector.tracking

import android.graphics.RectF
import com.yolo.detector.data.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackerAsyncTest {

    private fun createBox(l: Float, t: Float, r: Float, b: Float): RectF {
        return RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun det(left: Float, top: Float, right: Float, bottom: Float, conf: Float = 0.9f) =
        Detection(-1, 0, conf, createBox(left, top, right, bottom), 1000L)

    @Test
    fun getActiveDetections_returnsConfirmedTracksWithoutDropping() {
        val tracker = ByteTracker()
        val d1 = det(0.1f, 0.1f, 0.3f, 0.3f)

        // 3 consecutive hits promotes track to CONFIRMED
        tracker.update(listOf(d1), 1000L)
        tracker.update(listOf(d1), 1033L)
        val confirmed = tracker.update(listOf(d1), 1066L)
        assertEquals(1, confirmed.size)

        // Fast path query between keyframes
        val fastResult1 = tracker.getActiveDetections(1080L)
        val fastResult2 = tracker.getActiveDetections(1090L)

        assertEquals(1, fastResult1.size)
        assertEquals(1, fastResult2.size)
        assertEquals(confirmed[0].trackId, fastResult1[0].trackId)
    }

    @Test
    fun getActiveDetections_emptyWhenNoConfirmedTracks() {
        val tracker = ByteTracker()
        val d1 = det(0.1f, 0.1f, 0.3f, 0.3f)

        // Only 1 hit -> TENTATIVE
        tracker.update(listOf(d1), 1000L)
        val fastResult = tracker.getActiveDetections(1033L)
        assertTrue(fastResult.isEmpty())
    }
}
