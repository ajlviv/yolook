package com.yolo.detector.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.yolo.detector.data.WarningType

/**
 * Plays an audible alert for a [WarningType]. Implementations are optional; the
 * [NoOpSoundPlayer] makes the driver mode safe even when no audio device is present.
 */
fun interface SoundPlayer {
    fun play(warning: WarningType, volume: Float)
}

/**
 * Best-effort [SoundPlayer] using the Jetpack `android.media.MediaPlayer`.
 *
 * Each bundled WAV asset is loaded lazily (once) and re-used for repeated alerts.
 * Playback is fully guarded: if the platform cannot produce media (missing module,
 * unsupported sample, missing asset), the alert is dropped silently rather than
 * crashing the pipeline.
 */
class JetpackSoundPlayer(private val context: Context) : SoundPlayer {

    private val players = mutableMapOf<WarningType, MediaPlayer>()

    override fun play(warning: WarningType, volume: Float) {
        runCatching {
            val player = players.getOrPut(warning) {
                val uri = soundUri(warning) ?: return
                MediaPlayer.create(context, uri)
            } ?: return

            if (volume > 0f) {
                runCatching { player.setVolume(volume, volume) }
            }
            runCatching { player.start() }
        }
    }

    private fun soundUri(warning: WarningType): Uri? {
        val scheme = "android-resource:///"
        val name = when (warning) {
            WarningType.RED_LIGHT -> "sounds/warning_danger.wav"
            WarningType.YELLOW_LIGHT -> "sounds/warning_caution.wav"
            WarningType.SPEED_LIMIT -> "sounds/sign_notice.wav"
            WarningType.PERSON_ON_ROAD -> "sounds/warning_danger.wav"
        }
        return try {
            Uri.parse(scheme + name)
        } catch (_: Exception) {
            null
        }
    }
}

/** Plays nothing; keeps driver mode functional without audio. */
object NoOpSoundPlayer : SoundPlayer {
    override fun play(warning: WarningType, volume: Float) {}
}