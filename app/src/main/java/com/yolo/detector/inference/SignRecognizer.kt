package com.yolo.detector.inference

import android.graphics.Bitmap
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.Detection
import com.yolo.detector.data.SignHud
import com.yolo.detector.data.SignType

/**
 * Recognizes and tracks roadside traffic signs and active speed limits.
 *
 * Combines COCO direct sign detections with structural template OCR and
 * morphological pattern classification on candidate sign regions.
 */
class SignRecognizer {

    private val stopSignClass: Int get() = COCO_LABELS.indexOf("stop sign").coerceAtLeast(0)

    /** Persisted active speed limit (e.g., 50, 80, 100) shown on the Driver HUD. */
    var activeSpeedLimit: Int? = null
        private set

    private var speedLimitConfirmationCount = 0
    private var candidateSpeedLimit: Int? = null

    /**
     * Identifies signs from [detections] and [frame] crops.
     */
    fun recognize(detections: List<Detection>, frame: Bitmap? = null): List<SignHud> {
        val signs = mutableListOf<SignHud>()

        // 1. Direct COCO Stop Signs
        for (detection in detections) {
            if (detection.classId == stopSignClass) {
                signs.add(SignHud(SignType.STOP, "STOP"))
            }
        }

        // 2. Image-based Crop Classification for Speed Limits, Yield, No Entry, etc.
        if (frame != null && !frame.isRecycled) {
            val candidateBoxes = detections.filter { det ->
                val w = det.bbox.right - det.bbox.left
                val h = det.bbox.bottom - det.bbox.top
                // Aspect ratio between 0.7 and 1.4, located in upper 65% of screen
                det.bbox.top < 0.65f && (w / h.coerceAtLeast(0.01f)) in 0.7f..1.4f
            }

            for (cand in candidateBoxes) {
                val cropPixels = extractCropPixels(frame, cand.bbox) ?: continue
                val sign = RoadSignClassifier.classify(cropPixels, 24, 24)
                if (sign != null) {
                    if (signs.none { it.type == sign.type && it.label == sign.label }) {
                        signs.add(sign)
                    }

                    if (sign.type == SignType.SPEED_LIMIT) {
                        val parsedSpeed = sign.label.toIntOrNull()
                        if (parsedSpeed != null) {
                            updateActiveSpeedLimit(parsedSpeed)
                        }
                    }
                }
            }
        }

        return signs
    }

    private fun updateActiveSpeedLimit(speed: Int) {
        if (speed == candidateSpeedLimit) {
            speedLimitConfirmationCount++
            if (speedLimitConfirmationCount >= 2) {
                activeSpeedLimit = speed
            }
        } else {
            candidateSpeedLimit = speed
            speedLimitConfirmationCount = 1
        }
    }

    /** Resets temporal speed limit state. */
    fun reset() {
        activeSpeedLimit = null
        candidateSpeedLimit = null
        speedLimitConfirmationCount = 0
    }

    private fun extractCropPixels(frame: Bitmap, bbox: android.graphics.RectF): IntArray? {
        val targetSize = 24
        if (frame.isRecycled) return null
        val left = (bbox.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (bbox.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (bbox.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (bbox.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)

        val result = IntArray(targetSize * targetSize)
        val single = IntArray(1)
        var idx = 0
        for (r in 0 until targetSize) {
            val y = top + ((bottom - top - 1) * r / (targetSize - 1))
            for (c in 0 until targetSize) {
                val x = left + ((right - left - 1) * c / (targetSize - 1))
                frame.getPixels(single, 0, 1, x, y, 1, 1)
                result[idx++] = single[0]
            }
        }
        return result
    }
}