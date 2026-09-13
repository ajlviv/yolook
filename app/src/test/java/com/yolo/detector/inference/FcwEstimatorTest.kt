package com.yolo.detector.inference

import android.graphics.RectF
import com.yolo.detector.data.CollisionAlertLevel
import com.yolo.detector.data.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FcwEstimatorTest {

    private fun box(l: Float, t: Float, r: Float, b: Float): RectF {
        return RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun car(l: Float, t: Float, r: Float, b: Float, timeMs: Long = 1000L, trackId: Int = 1) =
        Detection(trackId, 2, 0.9f, box(l, t, r, b), timeMs)

    @Test
    fun returnsNull_whenNoVehicles() {
        val estimator = FcwEstimator()
        val result = estimator.estimate(emptyList())
        assertNull(result)
    }

    @Test
    fun returnsNull_whenVehicleOutsideEgoCorridor() {
        val estimator = FcwEstimator()
        // Car on far left parking lane
        val car = car(0.01f, 0.50f, 0.12f, 0.70f)
        val result = estimator.estimate(listOf(car))
        assertNull(result)
    }

    @Test
    fun detectsLeadVehicle_andComputesDistance() {
        val estimator = FcwEstimator()
        // Car straight ahead in ego lane
        val carAhead = car(0.40f, 0.55f, 0.60f, 0.75f)
        val result = estimator.estimate(listOf(carAhead))

        assertNotNull(result)
        assertEquals(1, result!!.trackId)
        assert(result.distanceMeters in 5f..35f)
    }

    @Test
    fun triggersCollisionWarning_onRapidApproach() {
        val estimator = FcwEstimator()
        // Frame 1: Car at distance
        val f1 = car(0.42f, 0.52f, 0.58f, 0.65f, timeMs = 1000L, trackId = 5)
        estimator.estimate(listOf(f1))

        // Frame 2: Car much closer rapidly (approaching fast)
        val f2 = car(0.35f, 0.65f, 0.65f, 0.92f, timeMs = 1200L, trackId = 5)
        val result2 = estimator.estimate(listOf(f2))

        assertNotNull(result2)
        assertEquals(CollisionAlertLevel.COLLISION_WARNING, result2!!.alertLevel)
    }
}
