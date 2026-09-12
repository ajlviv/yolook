package com.yolo.detector.audio

import com.yolo.detector.data.DriverScene
import com.yolo.detector.data.WarningType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningSoundManagerTest {

    private class RecordingPlayer : SoundPlayer {
        val calls = mutableListOf<WarningType>()

        override fun play(warning: WarningType, volume: Float) {
            calls.add(warning)
        }
    }

    @Test
    fun testPlaysWhenEnabled() {
        val player = RecordingPlayer()
        val manager = WarningSoundManager(player, AlertScheduler(cooldownMs = 0L))
        manager.handleScene(DriverScene(warnings = setOf(WarningType.RED_LIGHT, WarningType.PERSON_ON_ROAD)))
        assertTrue(WarningType.RED_LIGHT in player.calls)
        assertTrue(WarningType.PERSON_ON_ROAD in player.calls)
    }

    @Test
    fun testDoesNotPlayWhenDisabled() {
        val player = RecordingPlayer()
        val manager = WarningSoundManager(player, AlertScheduler(cooldownMs = 0L))
        manager.setEnabled(false)
        manager.handleScene(DriverScene(warnings = setOf(WarningType.RED_LIGHT)))
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun testCooldownSuppressesRapidRepeats() {
        val player = RecordingPlayer()
        val manager = WarningSoundManager(player, AlertScheduler(cooldownMs = 10000L))
        manager.handleScene(DriverScene(warnings = setOf(WarningType.RED_LIGHT)))
        manager.handleScene(DriverScene(warnings = setOf(WarningType.RED_LIGHT)))
        assertEquals(1, player.calls.size)
    }

    @Test
    fun testResetAllowsRetrigger() {
        val player = RecordingPlayer()
        val manager = WarningSoundManager(player, AlertScheduler(cooldownMs = 10000L))
        manager.handleScene(DriverScene(warnings = setOf(WarningType.RED_LIGHT)))
        manager.reset()
        manager.handleScene(DriverScene(warnings = setOf(WarningType.RED_LIGHT)))
        assertEquals(2, player.calls.size)
    }
}