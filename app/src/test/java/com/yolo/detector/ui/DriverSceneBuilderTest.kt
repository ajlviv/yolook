package com.yolo.detector.ui

import android.graphics.RectF
import com.yolo.detector.data.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverSceneBuilderTest {

    private fun box(): RectF =
        RectF().apply {
            left = 0.3f
            top = 0.1f
            right = 0.36f
            bottom = 0.4f
        }

    @Test
    fun testTrafficLightWithoutFrameYieldsNoLane() {
        // class 9 = traffic light; without a frame there is no pixel sample → UNKNOWN → no lane shown.
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
        // Place the person entirely in the upper third of the frame (well above the
        // default road zone at y > 0.4) so it must never be counted on-road.
        val upperBox = RectF()
        upperBox.left = 0.3f
        upperBox.top = 0.05f
        upperBox.right = 0.36f
        upperBox.bottom = 0.2f
        val scene = DriverSceneBuilder().build(
            listOf(Detection(-1, 0, 0.9f, upperBox, 100L)), // person
            null,
        )
        assertEquals(0, scene.peopleOnRoad)
    }
}