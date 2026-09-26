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

    /** Whether to attempt GPU delegate acceleration (falls back to the CPU). */
    val enableGpuDelegate: Boolean = true,

    /**
     * SAHI-style sliced detection: tiles the frame into overlapping model-sized crops,
     * detects each at full input resolution, and fuses the results. Improves
     * small/distant-object recall but multiplies inference cost (~6× on a
     * 1280×720 feed). Default off; use only when accuracy matters more than FPS.
     */
    val slicedInference: Boolean = false,

    /** Id of the [com.yolo.detector.inference.ModelProfile] to run. */
    val modelProfileId: String = DEFAULT_MODEL_PROFILE_ID,

    /**
     * Class IDs whose detections are rendered and tracked, keyed by
     * [modelProfileId].
     *
     * Class IDs are only meaningful inside one model's label space, so a filter
     * cannot be shared across models: profile A's ID 0 says nothing about profile
     * B's ID 0. Each profile falls back to all of *its own* classes until the user
     * narrows it, and its choice is remembered across model switches.
     */
    val classFilters: Map<String, Set<Int>> = emptyMap(),

    /** Visual filter applied to the live camera preview. */
    val viewMode: ViewMode = ViewMode.NORMAL,

    /** How detections are drawn on the live overlay and frame. */
    val detectionView: DetectionView = DetectionView.LABELS,

    /**
     * Monitoring mode: keeps the screen awake and dims brightness to a minimum so
     * detection and alerts keep running on a left-open device. Applies app-wide
     * regardless of the active tab.
     */
    val monitoringMode: Boolean = false,

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
) {
    /**
     * Class IDs enabled for [modelProfileId], given that profile's class count.
     *
     * An absent entry means "all of this profile's classes are enabled", so a fresh
     * profile — or one whose labels outnumber the stored IDs — is never silently
     * blanked out.
     */
    fun classFilterFor(numClasses: Int): Set<Int> =
        classFilters[modelProfileId]?.takeIf { it.isNotEmpty() } ?: (0 until numClasses).toSet()
}
