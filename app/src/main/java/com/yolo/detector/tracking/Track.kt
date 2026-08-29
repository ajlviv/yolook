package com.yolo.detector.tracking

import android.graphics.RectF
import com.yolo.detector.data.Detection

/** Lifecycle state of a [Track] in ByteTrack. */
enum class TrackState {
    /** Newly created — not yet associated for [ByteTracker.MIN_HITS] consecutive frames. */
    TENTATIVE,
    /** Stably tracked — has been associated enough times to be trusted. */
    CONFIRMED,
    /** Not matched in recent frames — kept alive for re-identification. */
    LOST,
}

/**
 * A single tracked object managed by [ByteTracker].
 *
 * Wraps a [KalmanFilter] to predict and smooth the bounding box over time,
 * and tracks association bookkeeping (age, hits, frames since last update).
 */
class Track(
    /** Globally unique track identifier assigned at creation. */
    val trackId: Int,
    initialDetection: Detection,
) {
    val kalman = KalmanFilter()

    var state: TrackState = TrackState.TENTATIVE
        private set

    /** Total number of frames this track has been alive. */
    var age: Int = 1
        private set

    /** Number of consecutive frames this track has been matched. */
    var hits: Int = 1
        private set

    /** Number of frames since this track was last matched to a detection. */
    var framesSinceUpdate: Int = 0
        private set

    /** The class ID this track was initialised with (does not change). */
    val classId: Int = initialDetection.classId

    init {
        val (cx, cy, w, h) = initialDetection.bbox.toCxCyWH()
        kalman.init(cx, cy, w, h)
    }

    // ── Per-frame lifecycle ────────────────────────────────────────────────────

    /** Advances the Kalman filter prediction by one frame without a new measurement. */
    fun predict() {
        kalman.predict()
        age++
        framesSinceUpdate++
    }

    /**
     * Updates the Kalman filter with a new matched [detection] and resets the
     * "frames since update" counter. Promotes TENTATIVE → CONFIRMED after
     * [ByteTracker.MIN_HITS] consecutive matches.
     */
    fun update(detection: Detection) {
        val (cx, cy, w, h) = detection.bbox.toCxCyWH()
        kalman.update(cx, cy, w, h)
        framesSinceUpdate = 0
        hits++
        if (state == TrackState.TENTATIVE && hits >= ByteTracker.MIN_HITS) {
            state = TrackState.CONFIRMED
        }
    }

    /** Marks this track as [TrackState.LOST]. */
    fun markLost() {
        state = TrackState.LOST
    }

    /**
     * Emits a [Detection] from the current Kalman state with this track's [trackId].
     *
     * The bounding box comes from the Kalman filter (smoothed) rather than the raw
     * detector, so it remains stable even in noisy frames.
     */
    fun toDetection(timestampMs: Long): Detection {
        val (cx, cy, w, h) = kalman.stateToBbox()
        val left   = (cx - w / 2f).coerceIn(0f, 1f)
        val top    = (cy - h / 2f).coerceIn(0f, 1f)
        val right  = (cx + w / 2f).coerceIn(0f, 1f)
        val bottom = (cy + h / 2f).coerceIn(0f, 1f)
        return Detection(
            trackId = trackId,
            classId = classId,
            confidence = 1f,  // confidence is replaced by tracker state; always show confirmed tracks
            bbox = RectF(left, top, right, bottom),
            timestampMs = timestampMs,
        )
    }
}

// ── RectF helpers ─────────────────────────────────────────────────────────────

/** Converts a normalised [RectF] (left, top, right, bottom) to (cx, cy, w, h). */
internal fun RectF.toCxCyWH(): FloatArray {
    val w = right - left
    val h = bottom - top
    return floatArrayOf(left + w / 2f, top + h / 2f, w, h)
}

/** Destructuring component extension for float arrays of length ≥ 4. */
internal operator fun FloatArray.component1() = this[0]
internal operator fun FloatArray.component2() = this[1]
internal operator fun FloatArray.component3() = this[2]
internal operator fun FloatArray.component4() = this[3]
