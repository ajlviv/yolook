package com.yolo.detector.data

/**
 * How detections are rendered on the live overlay and frame.
 *
 * - [LABELS]       — box with class name, track ID and confidence (current default).
 * - [BOX_ONLY]     — box outline only, no labels.
 * - [COUNT]        — box with a per-object running number ("1", "2", …) inside it
 *   (same visuals as the Live-tab count toggle) plus per-frame class counts
 *   in the stats HUD. Numbers are per-frame, never cumulative.
 * - [OBJECTS_ONLY] — only the pixels inside detection boxes are shown, everything
 *   else is black. Forces the filtered-frame path even in [ViewMode.NORMAL].
 *
 * Persisted in DataStore by name; ordinal order must match the
 * `detection_views` string array in `strings.xml`. Append new entries at the
 * end so persisted ordinals stay valid.
 */
enum class DetectionView {
    LABELS,
    BOX_ONLY,
    COUNT,
    OBJECTS_ONLY,
}
