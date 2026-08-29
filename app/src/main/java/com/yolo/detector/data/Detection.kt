package com.yolo.detector.data

import android.graphics.RectF

/**
 * Represents a single object detection, optionally associated with a ByteTrack track.
 *
 * @param trackId  Assigned by [com.yolo.detector.tracking.ByteTracker]. -1 means untracked
 *                 (raw detector output before the tracking stage runs).
 * @param classId  COCO class index (0–79). See [COCO_LABELS].
 * @param confidence Detector confidence score in [0, 1].
 * @param bbox     Bounding box in normalised image coordinates [0, 1] (left, top, right, bottom).
 * @param timestampMs [System.currentTimeMillis] at the time this detection was produced.
 */
data class Detection(
    val trackId: Int,
    val classId: Int,
    val confidence: Float,
    val bbox: RectF,
    val timestampMs: Long,
)
