package com.yolo.detector.data

/**
 * All tunable parameters for the inference and tracking pipeline.
 */
data class InferenceSettings(
    /** Minimum detector confidence to retain a detection. Range [0.1, 0.9]. */
    val confidenceThreshold: Float = 0.35f,

    /** IoU threshold used for NMS (deduplication) and ByteTrack association. Range [0.1, 0.9]. */
    val iouThreshold: Float = 0.45f,

    /** Maximum number of detections passed to the tracker per frame. Range [1, 100]. */
    val maxObjects: Int = 50,

    /** Target inference rate. Analysis frames are skipped to enforce this cap. Range [1, 30]. */
    val inferenceRateFps: Int = 10,

    /** Whether to attempt GPU delegate acceleration (falls back to NNAPI, then CPU). */
    val enableGpuDelegate: Boolean = true,

    /**
     * Set of COCO class IDs whose detections are rendered and tracked.
     * Defaults to all 80 COCO classes.
     */
    val classFilter: Set<Int> = COCO_LABELS.indices.toSet(),

    /** Visual filter applied to the live camera preview. */
    val viewMode: ViewMode = ViewMode.NORMAL,
)
