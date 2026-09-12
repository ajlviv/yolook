package com.yolo.detector.audio

import com.yolo.detector.data.WarningType

/**
 * Debounces sound triggers so a persistent condition (e.g. a red light held for many
 * frames) does not re-trigger audio on every frame. Each [WarningType] can fire again
 * only after [cooldownMs] has elapsed since its last trigger.
 *
 * Pure and unit-testable.
 */
class AlertScheduler(
    private val cooldownMs: Long = 2000L,
) {
    private val lastTriggered = mutableMapOf<WarningType, Long>()

    /**
     * Returns true if [warning] should play now. Repeats are emitted only after the
     * cooldown has elapsed since the last trigger of that type.
     */
    fun shouldTrigger(warning: WarningType, nowMs: Long): Boolean {
        val last = lastTriggered[warning]
        if (last != null && nowMs - last < cooldownMs) return false
        lastTriggered[warning] = nowMs
        return true
    }

    fun reset() {
        lastTriggered.clear()
    }
}