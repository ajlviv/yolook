package com.yolo.detector.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadZoneTest {

    @Test
    fun testBoxInLowerHalfOverlapsRoad() {
        // Default road zone starts at y = 0.4 (bottom 60%).
        assertTrue(RoadZone.overlapsRoad(bottom = 0.8f, top = 0.55f))
    }

    @Test
    fun testBoxInUpperFrameDoesNot() {
        assertFalse(RoadZone.overlapsRoad(bottom = 0.2f, top = 0.05f))
    }

    @Test
    fun testBoxSpanningRoadTopEdgeOverlaps() {
        assertTrue(RoadZone.overlapsRoad(bottom = 0.5f, top = 0.35f))
    }

    @Test
    fun testCustomFraction() {
        // With fraction 0.5, roadTop = 0.5. A box bottom at 0.4 is outside.
        assertFalse(RoadZone.overlapsRoad(bottom = 0.4f, top = 0.3f, roadZoneFraction = 0.5f))
        // bottom at 0.6 is inside.
        assertTrue(RoadZone.overlapsRoad(bottom = 0.6f, top = 0.3f, roadZoneFraction = 0.5f))
    }
}