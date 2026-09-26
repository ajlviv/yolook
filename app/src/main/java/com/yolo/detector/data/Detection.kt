package com.yolo.detector.data

import android.graphics.RectF
import com.yolo.detector.inference.SegmentationMask

/**
 * Represents a single object detection, optionally associated with a ByteTrack track.
 *
 * @param trackId  Assigned by [com.yolo.detector.tracking.ByteTracker]. -1 means untracked
 *                 (raw detector output before the tracking stage runs).
 * @param classId  Class index within the *active model profile's* label list
 *                 (e.g. 0–79 for COCO, 0–2 for YOLOE).
 * @param confidence Detector confidence score in [0, 1].
 * @param bbox     Bounding box in normalised image coordinates [0, 1] (left, top, right, bottom).
 * @param timestampMs [System.currentTimeMillis] at the time this detection was produced.
 * @param mask     Instance mask for segmentation models; null for detection models and
 *                 whenever mask decoding produced no foreground pixels.
 */
data class Detection(
    val trackId: Int,
    val classId: Int,
    val confidence: Float,
    val bbox: RectF,
    val timestampMs: Long,
    val mask: SegmentationMask? = null,
)
