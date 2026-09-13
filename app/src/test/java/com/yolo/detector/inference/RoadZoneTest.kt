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
        assertFalse(RoadZone.overlapsRoad(bottom = 0.4f, top = 0.3f, roadZoneFraction = 0.5f))
        assertTrue(RoadZone.overlapsRoad(bottom = 0.6f, top = 0.3f, roadZoneFraction = 0.5f))
    }

    @Test
    fun testEgoCorridorPerspectiveTrapezoid() {
        // Near horizon (y = 0.50): center x = 0.50 should be in corridor
        assertTrue(RoadZone.isInEgoCorridor(0.50f, 0.50f))

        // Near horizon (y = 0.50): far left x = 0.20 should be outside corridor
        assertFalse(RoadZone.isInEgoCorridor(0.20f, 0.50f))

        // Near hood (y = 0.90): x = 0.25 is in wide corridor
        assertTrue(RoadZone.isInEgoCorridor(0.25f, 0.90f))

        // Near hood (y = 0.90): x = 0.05 is far sidewalk outside corridor
        assertFalse(RoadZone.isInEgoCorridor(0.05f, 0.90f))
    }

    @Test
    fun testIsBoxInEgoCorridor() {
        // Vehicle centered in lane ahead
        assertTrue(RoadZone.isBoxInEgoCorridor(left = 0.40f, top = 0.55f, right = 0.60f, bottom = 0.75f))

        // Vehicle parked on far left curb
        assertFalse(RoadZone.isBoxInEgoCorridor(left = 0.02f, top = 0.55f, right = 0.12f, bottom = 0.70f))
    }
}