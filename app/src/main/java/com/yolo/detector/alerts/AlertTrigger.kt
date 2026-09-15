package com.yolo.detector.alerts

/**
 * Emitted by [DetectionThrottler] when a detection alert should be dispatched.
 *
 * @param timestampMs monotonic trigger time on the same clock as the throttler input
 *                    (elapsed-realtime base) — for cooldown bookkeeping, not display.
 * @param detectedClassIds the distinct COCO class IDs seen in the confirmed frame.
 */
data class AlertTrigger(
    val timestampMs: Long,
    val detectedClassIds: Set<Int>,
)