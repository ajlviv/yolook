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

    /** How detections are drawn on the live overlay and frame. */
    val detectionView: DetectionView = DetectionView.LABELS,

    // ── Per-view-mode rendering quality ────────────────────────────────────

    /**
     * Edge detection sensitivity: minimum gradient magnitude to draw an edge.
     * Higher = fewer, stronger edges; lower = specklier, finer detail. Range [30, 200].
     */
    val edgeThreshold: Int = 100,

    /**
     * Edge render detail level in [1, 3]: higher = finer grid but more work.
     * Maps to the bake downscale (1 → 4×, 2 → 3×, 3 → 2× downscale).
     */
    val edgeDetail: Int = 3,

    /**
     * Heatmap render detail level in [1, 3]: higher = finer grid but more work.
     * Maps to the bake downscale (1 → 6×, 2 → 4×, 3 → 2× downscale).
     */
    val heatmapDetail: Int = 3,

    /**
     * Matrix glyph-grid size as a detail level in [1, 10]: higher = smaller
     * (finer) glyph cells, i.e. more recognizable but slightly more work.
     * Maps to cell size 16-down to 6 px.
     */
    val matrixDetail: Int = 8,

    /** Matrix shadow brightness gamma in [0.5, 1.0]: lower = brighter shadows. */
    val matrixGamma: Float = 0.74f,

    // ── Capture (photo snapshot / video recording) ─────────────────────────

    /** What the Live-tab action button does: photo snapshot or video recording. */
    val captureMode: CaptureMode = CaptureMode.PHOTO,

    /**
     * Recorded-video output height in pixels ([VideoResolution.height]); the
     * width follows the screen aspect so the file matches the live view.
     */
    val videoResolution: VideoResolution = VideoResolution.HD,

    /** Recorded-video frame rate in [15, 30] FPS. */
    val videoFps: Int = 30,
)
