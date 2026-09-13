package com.yolo.detector.ui

import android.graphics.RectF
import com.yolo.detector.data.CollisionAlertLevel
import com.yolo.detector.data.Detection
import com.yolo.detector.data.WarningType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverSceneBuilderTest {

    private fun box(l: Float = 0.3f, t: Float = 0.1f, r: Float = 0.36f, b: Float = 0.4f): RectF =
        RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }

    @Test
    fun testTrafficLightWithoutFrameYieldsNoLane() {
        val scene = DriverSceneBuilder().build(
            listOf(Detection(-1, 9, 0.85f, box(), 100L)),
            null,
        )
        assertTrue(scene.trafficLights.isEmpty())
    }

    @Test
    fun testEmptyFrameYieldsEmptyScene() {
        val scene = DriverSceneBuilder().build(emptyList(), null)
        assertEquals(0, scene.peopleOnRoad)
        assertTrue(scene.trafficLights.isEmpty())
        assertTrue(scene.warnings.isEmpty())
    }

    @Test
    fun testPersonClassWithStubBBoxIsNotFalselyOnRoad() {
        val upperBox = box(l = 0.3f, t = 0.05f, r = 0.36f, b = 0.2f)
        val scene = DriverSceneBuilder().build(
            listOf(Detection(-1, 0, 0.9f, upperBox, 100L)), // person
            null,
        )
        assertEquals(0, scene.peopleOnRoad)
    }

    @Test
    fun testLeadCarForwardCollisionWarningInScene() {
        val builder = DriverSceneBuilder()
        // Car straight ahead in ego corridor
        val carAhead = Detection(1, 2, 0.95f, box(l = 0.40f, t = 0.60f, r = 0.60f, b = 0.85f), 100L)
        val scene = builder.build(listOf(carAhead), null)

        assertNotNull(scene.leadVehicle)
        assertEquals(1, scene.leadVehicle!!.trackId)
    }
}