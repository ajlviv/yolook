package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalStabilizerTest {

    @Test
    fun testReturnsUnknownUntilStable() {
        val stabilizer = SignalStabilizer(requiredConsecutive = 3)
        assertEquals(TrafficLightSignal.UNKNOWN, stabilizer.update(1, TrafficLightSignal.RED))
        assertEquals(TrafficLightSignal.UNKNOWN, stabilizer.update(1, TrafficLightSignal.RED))
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.RED))
    }

    @Test
    fun testSingleGlitchDoesNotFlipSignal() {
        val stabilizer = SignalStabilizer(requiredConsecutive = 3)
        stabilizer.update(1, TrafficLightSignal.RED)
        stabilizer.update(1, TrafficLightSignal.RED)
        stabilizer.update(1, TrafficLightSignal.RED) // stable = RED
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.YELLOW))
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.RED))
    }

    @Test
    fun testSignalFlipsAfterSustainedChange() {
        val stabilizer = SignalStabilizer(requiredConsecutive = 3)
        stabilizer.update(1, TrafficLightSignal.RED)
        stabilizer.update(1, TrafficLightSignal.RED)
        stabilizer.update(1, TrafficLightSignal.RED) // RED
        stabilizer.update(1, TrafficLightSignal.GREEN)
        stabilizer.update(1, TrafficLightSignal.GREEN)
        assertEquals(TrafficLightSignal.GREEN, stabilizer.update(1, TrafficLightSignal.GREEN))
    }

    @Test
    fun testTracksAreIndependent() {
        val stabilizer = SignalStabilizer(requiredConsecutive = 3)
        // Track 1 → red, track 2 → green, interleaved.
        stabilizer.update(1, TrafficLightSignal.RED)
        stabilizer.update(1, TrafficLightSignal.RED)
        stabilizer.update(1, TrafficLightSignal.RED)
        assertEquals(TrafficLightSignal.UNKNOWN, stabilizer.update(2, TrafficLightSignal.GREEN))
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.RED))
    }

    @Test
    fun testAmbiguousDoesNotAdvance() {
        val stabilizer = SignalStabilizer(requiredConsecutive = 2)
        stabilizer.update(1, TrafficLightSignal.RED)
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.RED))
        // A single unknown resets the streak; a following red needs 2 fresh hits.
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.UNKNOWN))
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.RED))
        assertEquals(TrafficLightSignal.RED, stabilizer.update(1, TrafficLightSignal.RED))
    }

    @Test
    fun testResetClearsState() {
        val stabilizer = SignalStabilizer(requiredConsecutive = 1)
        stabilizer.update(1, TrafficLightSignal.GREEN)
        stabilizer.reset()
        assertEquals(TrafficLightSignal.GREEN, stabilizer.update(1, TrafficLightSignal.GREEN))
    }
}