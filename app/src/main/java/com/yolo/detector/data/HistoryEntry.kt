package com.yolo.detector.data

import android.graphics.RectF

/**
 * One uniquely seen object, aggregated across the frames it appeared in.
 *
 * History is grouped by track identity:
 * the same tracked object (or a run of untracked detections of the same class)
 * collapses into a single entry carrying aggregate info.
 *
 * @param trackId ByteTrack ID, or -1 when the object never reached a confirmed track.
 * @param classId COCO class index (0-79). See [COCO_LABELS].
 * @param count Number of frames this object appeared in.
 * @param firstSeenMs [Detection.timestampMs] of the first frame.
 * @param lastSeenMs [Detection.timestampMs] of the most recent frame.
 * @param bestConfidence Highest detector confidence seen for this object.
 * @param lastBbox Normalised bounding box of the most recent frame.
 */
data class HistoryEntry(
    val trackId: Int,
    var classId: Int,
    var count: Int,
    var firstSeenMs: Long,
    var lastSeenMs: Long,
    var bestConfidence: Float,
    var lastBbox: RectF,
)