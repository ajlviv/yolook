package com.yolo.detector.alerts

import android.graphics.RectF
import com.yolo.detector.data.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class DetectionThrottlerTest {

    private fun det(classId: Int = 0) = Detection(
        trackId = -1,
        classId = classId,
        confidence = 0.9f,
        bbox = RectF(0.1f, 0.1f, 0.9f, 0.9f),
        timestampMs = System.currentTimeMillis(),
    )

    @Test
    fun singleFrameDoesNotTrigger() {
        val throttle = DetectionThrottler()
        assertNull(throttle.onDetections(listOf(det()), 1_000))
        assertEquals(DetectionThrottler.State.Detecting(1), throttle.currentState)
    }

    @Test
    fun confirmsAfterRequiredConsecutiveFrames() {
        val throttle = DetectionThrottler(confirmFrames = 3)
        assertNull(throttle.onDetections(listOf(det(2)), 1))
        assertNull(throttle.onDetections(listOf(det(2)), 2))
        val trigger = throttle.onDetections(listOf(det(2), det(5)), 3)
        assertNotNull(trigger)
        assertEquals(setOf(2, 5), trigger!!.detectedClassIds)
        // Moved straight into cooldown with the configured quiet period.
        assertEquals(DetectionThrottler.State.Cooldown(3 + 60_000), throttle.currentState)
    }

    @Test
    fun honorsCooldownAndResumesAfterExpiry() {
        val throttle = DetectionThrottler(initialCooldownMs = 1_000, confirmFrames = 2)
        assertNull(throttle.onDetections(listOf(det()), 1))
        assertNotNull(throttle.onDetections(listOf(det()), 2)) // cooldown until 1002

        // Still in cooldown: ignored.
        assertNull(throttle.onDetections(listOf(det()), 100))
        assertEquals(DetectionThrottler.State.Cooldown(1002), throttle.currentState)

        // Timer not yet expired: still ignored.
        assertNull(throttle.onDetections(listOf(det()), 1001))

        // Expired: transitions back to Idle on the same call (no trigger that instant).
        assertNull(throttle.onDetections(listOf(det()), 1002))
        assertEquals(DetectionThrottler.State.Idle, throttle.currentState)

        // First found-frame after cooldown starts a fresh confirm window.
        assertNull(throttle.onDetections(listOf(det()), 1003)) // Detecting(1)
        assertNotNull(throttle.onDetections(listOf(det()), 1004)) // Detecting(2) triggers
    }

    @Test
    fun lostFramesResetABackToIdleBeforeConfirm() {
        val throttle = DetectionThrottler(confirmFrames = 3, resetLostFrames = 2)
        assertNull(throttle.onDetections(listOf(det()), 1)) // Detecting(1)
        assertNull(throttle.onDetections(emptyList(), 2))    // empty #1
        assertNull(throttle.onDetections(emptyList(), 3))    // empty #2 -> Idle
        assertEquals(DetectionThrottler.State.Idle, throttle.currentState)

        // Must re-accumulate confirm frames from scratch.
        assertNull(throttle.onDetections(listOf(det()), 4)) // Detecting(1)
        assertNull(throttle.onDetections(listOf(det()), 5)) // Detecting(2)
        assertNotNull(throttle.onDetections(listOf(det()), 6))
    }

    @Test
    fun emptyEveryFrameStaysIdle() {
        val throttle = DetectionThrottler()
        assertNull(throttle.onDetections(emptyList(), 1))
        assertNull(throttle.onDetections(emptyList(), 2))
        assertEquals(DetectionThrottler.State.Idle, throttle.currentState)
    }

    @Test
    fun resetForcesIdle() {
        val throttle = DetectionThrottler()
        assertNull(throttle.onDetections(listOf(det()), 1))
        throttle.reset()
        assertEquals(DetectionThrottler.State.Idle, throttle.currentState)
    }

    @Test
    fun zeroCooldownDefaultsWork() {
        val throttle = DetectionThrottler(initialCooldownMs = 0, confirmFrames = 3)
        assertNull(throttle.onDetections(listOf(det()), 0))
        assertNull(throttle.onDetections(listOf(det()), 1))
        assertNotNull(throttle.onDetections(listOf(det()), 2))
    }

    @Test
    fun rejectsConfirmFramesBelowTwo() {
        try {
            DetectionThrottler(confirmFrames = 1)
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}