package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficLightColorEstimatorTest {

    private val red = IntArray(10) { 0xFFFF0000.toInt() }      // pure red
    private val yellow = IntArray(10) { 0xFFFFDD00.toInt() }   // amber
    private val green = IntArray(10) { 0xFF00FF00.toInt() }    // pure green
    private val dark = IntArray(10) { 0xFF000000.toInt() }     // off / housing

    @Test
    fun testRedDominant() {
        assertEquals(TrafficLightSignal.RED, TrafficLightColorEstimator.classify(red))
    }

    @Test
    fun testYellowDominant() {
        assertEquals(TrafficLightSignal.YELLOW, TrafficLightColorEstimator.classify(yellow))
    }

    @Test
    fun testGreenDominant() {
        assertEquals(TrafficLightSignal.GREEN, TrafficLightColorEstimator.classify(green))
    }

    @Test
    fun testAllDarkIsOff() {
        assertEquals(TrafficLightSignal.OFF, TrafficLightColorEstimator.classify(dark))
    }

    @Test
    fun testMixedMajorityWins() {
        // 7 red pixels + 3 green → red.
        val mixed = IntArray(10) { i -> if (i < 7) 0xFFFF0000.toInt() else 0xFF00FF00.toInt() }
        assertEquals(TrafficLightSignal.RED, TrafficLightColorEstimator.classify(mixed))
    }

    @Test
    fun testEmptyIsOff() {
        assertEquals(TrafficLightSignal.OFF, TrafficLightColorEstimator.classify(IntArray(0)))
    }
}