package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightHud
import com.yolo.detector.data.TrafficLightSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class LaneClustererTest {

    private fun light(trackId: Int, signal: TrafficLightSignal) =
        TrafficLightHud(trackId, signal, 0)

    @Test
    fun testSingleLightSingleLane() {
        val result = LaneClusterer.assign(
            listOf(light(1, TrafficLightSignal.RED)),
            listOf(0.5f),
        )
        assertEquals(1, result.size)
        assertEquals(0, result[0].laneIndex)
    }

    @Test
    fun testTwoDistinctLanes() {
        val result = LaneClusterer.assign(
            listOf(light(1, TrafficLightSignal.RED), light(2, TrafficLightSignal.GREEN)),
            listOf(0.3f, 0.7f),
        )
        assertEquals(2, result.size)
        // Two well-separated lights → two lanes, indexed left → right as 0 and 1.
        assertEquals(0, result[0].laneIndex)
        assertEquals(1, result[1].laneIndex)
        assertEquals(1, result.map { it.laneIndex }.max())
    }

    @Test
    fun testCloseLightsClusterIntoSameLane() {
        val result = LaneClusterer.assign(
            listOf(light(1, TrafficLightSignal.RED), light(2, TrafficLightSignal.GREEN)),
            listOf(0.30f, 0.36f),
        )
        assertEquals(result[0].laneIndex, result[1].laneIndex)
    }

    @Test
    fun testLaneAssignmentIsLeftToRight() {
        // Discovered out of spatial order: light B is left of light A.
        val result = LaneClusterer.assign(
            listOf(light(1, TrafficLightSignal.RED), light(2, TrafficLightSignal.GREEN)),
            listOf(0.8f, 0.2f),
        )
        // Item 0 (x=0.8) should get the highest lane, item 1 (x=0.2) lane 0.
        assertEquals(1, result[0].laneIndex)
        assertEquals(0, result[1].laneIndex)
    }

    @Test
    fun testEmptyReturnsEmpty() {
        assertEquals(0, LaneClusterer.assign(emptyList(), emptyList()).size)
    }
}