package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightSignal
import java.util.ArrayDeque

/**
 * Temporal signal stabilizer (per traffic-light track).
 *
 * Reduces flicker / misclassification by waiting until a signal has been observed
 * consistently for [requiredConsecutive] consecutive frames before reporting it.
 * Each track keeps a small ring of recent signals; only stable, non-[TrafficLightSignal.UNKNOWN]
 * readings advance the reported value.
 *
 * State is keyed by the detection trackId so multiple traffic lights on screen are
 * tracked independently. Pure and thread-confined (consumed on the camera analysis
 * thread via [com.yolo.detector.camera.CameraManager]), so it is trivially unit-testable.
 */
class SignalStabilizer(
    private val requiredConsecutive: Int = 3,
    private val historyCap: Int = 6,
) {
    private data class TrackState(val seed: Int = 0) {
        val recent = ArrayDeque<TrafficLightSignal>()
        var lastSignal: TrafficLightSignal = TrafficLightSignal.UNKNOWN
        var stable: TrafficLightSignal = TrafficLightSignal.UNKNOWN
        var streak: Int = 0
    }

    private val tracks = mutableMapOf<Int, TrackState>()

    /** Feeds one observed signal for [trackId]; returns the reported (stable) signal. */
    fun update(trackId: Int, signal: TrafficLightSignal): TrafficLightSignal {
        val state = tracks.getOrPut(trackId) { TrackState() }

        // Maintain the recent ring.
        if (state.recent.size >= historyCap) state.recent.pollFirst()
        state.recent.addLast(signal)

        if (signal == TrafficLightSignal.UNKNOWN || signal == TrafficLightSignal.OFF) {
            // A dark/ambiguous lamp is inconclusive: keep the last stable signal but
            // force the next clear reading to re-establish itself.
            state.streak = 0
            state.lastSignal = signal
            return state.stable
        }

        if (signal == state.lastSignal) {
            state.streak++
        } else {
            state.streak = 1
            state.lastSignal = signal
        }

        if (state.streak >= requiredConsecutive) {
            state.stable = signal
        }
        return state.stable
    }

    fun forget(trackId: Int) {
        tracks.remove(trackId)
    }

    fun reset() {
        tracks.clear()
    }
}