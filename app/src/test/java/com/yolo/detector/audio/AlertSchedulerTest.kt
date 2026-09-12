package com.yolo.detector.audio

import com.yolo.detector.data.WarningType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSchedulerTest {

    @Test
    fun testFirstTriggerAlwaysPlays() {
        val scheduler = AlertScheduler(cooldownMs = 2000L)
        assertTrue(scheduler.shouldTrigger(WarningType.RED_LIGHT, 0L))
    }

    @Test
    fun testRepeatWithinCooldownIsSuppressed() {
        val scheduler = AlertScheduler(cooldownMs = 2000L)
        scheduler.shouldTrigger(WarningType.RED_LIGHT, 1000L)
        assertFalse(scheduler.shouldTrigger(WarningType.RED_LIGHT, 2500L))
    }

    @Test
    fun testRepeatAfterCooldownPlaysAgain() {
        val scheduler = AlertScheduler(cooldownMs = 2000L)
        scheduler.shouldTrigger(WarningType.RED_LIGHT, 1000L)
        assertFalse(scheduler.shouldTrigger(WarningType.RED_LIGHT, 2500L))
        assertTrue(scheduler.shouldTrigger(WarningType.RED_LIGHT, 3200L))
    }

    @Test
    fun testTypesAreTrackedIndependently() {
        val scheduler = AlertScheduler(cooldownMs = 5000L)
        scheduler.shouldTrigger(WarningType.RED_LIGHT, 0L)
        // A different warning type is not suppressed by the red light.
        assertTrue(scheduler.shouldTrigger(WarningType.PERSON_ON_ROAD, 100L))
    }

    @Test
    fun testResetClearsCooldown() {
        val scheduler = AlertScheduler(cooldownMs = 100000L)
        scheduler.shouldTrigger(WarningType.RED_LIGHT, 0L)
        scheduler.reset()
        assertTrue(scheduler.shouldTrigger(WarningType.RED_LIGHT, 0L))
    }
}