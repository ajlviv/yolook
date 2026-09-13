package com.yolo.detector.inference

import android.graphics.RectF
import com.yolo.detector.data.Detection
import com.yolo.detector.data.PedestrianThreatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianThreatAnalyzerTest {

    private fun box(l: Float, t: Float, r: Float, b: Float): RectF {
        return RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun person(l: Float, t: Float, r: Float, b: Float, timeMs: Long = 1000L, trackId: Int = 1) =
        Detection(trackId, 0, 0.9f, box(l, t, r, b), timeMs)

    @Test
    fun detectsInLanePedestrianHazard() {
        val analyzer = PedestrianThreatAnalyzer()
        // Person standing in the center of the lane
        val p = person(0.48f, 0.60f, 0.52f, 0.85f)
        val alerts = analyzer.analyze(listOf(p))

        assertEquals(1, alerts.size)
        assertEquals(PedestrianThreatType.IN_LANE, alerts[0].threatType)
    }

    @Test
    fun detectsCrossingPedestrianTrajectory() {
        val analyzer = PedestrianThreatAnalyzer()

        // Frame 1: Person on left sidewalk
        val p1 = person(0.10f, 0.60f, 0.15f, 0.80f, timeMs = 1000L, trackId = 10)
        analyzer.analyze(listOf(p1))

        // Frame 2: Person walking rapidly towards roadway corridor
        val p2 = person(0.18f, 0.60f, 0.23f, 0.80f, timeMs = 1200L, trackId = 10)
        val alerts2 = analyzer.analyze(listOf(p2))

        assertEquals(1, alerts2.size)
        assertEquals(PedestrianThreatType.CROSSING_PATH, alerts2[0].threatType)
    }

    @Test
    fun marksSafeSidewalkPedestrian() {
        val analyzer = PedestrianThreatAnalyzer()
        // Person on sidewalk outside road zone
        val p = person(0.01f, 0.10f, 0.05f, 0.25f)
        val alerts = analyzer.analyze(listOf(p))

        assertTrue(alerts.isEmpty())
    }
}
