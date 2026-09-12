package com.yolo.detector.audio

import com.yolo.detector.data.DriverScene

/**
 * Routes driver-scene warnings to audio, honouring the sound-on/off setting and
 * volume, and debouncing repeats with an [AlertScheduler].
 *
 * This is the single entry point the UI calls per scene update. It is cheap and
 * idempotent, so it can be invoked from the main UI thread on every frame.
 */
class WarningSoundManager(
    private val player: SoundPlayer,
    private val scheduler: AlertScheduler = AlertScheduler(),
) {
    @Volatile private var enabled: Boolean = true
    @Volatile private var volume: Float = 1f

    /** Reacts to [scene], playing any due warnings. */
    fun handleScene(scene: DriverScene) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        for (warning in scene.warnings) {
            if (scheduler.shouldTrigger(warning, now)) {
                player.play(warning, volume)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    fun reset() {
        scheduler.reset()
    }
}