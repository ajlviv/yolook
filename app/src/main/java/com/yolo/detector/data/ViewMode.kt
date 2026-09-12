package com.yolo.detector.data

/**
 * Visual filter applied to the live camera preview.
 *
 * [NORMAL] shows the raw camera stream in `PreviewView`. The remaining modes hide
 * the raw stream and render each inference frame through a colored `ImageView`:
 * - [BLACK_AND_WHITE] — grayscale luminance mapping (GPU color-matrix filter).
 * - [INVERT]          — RGB channel inversion (GPU color-matrix filter).
 * - [HEATMAP]         — intensity → hot-metal colormap (baked per pixel).
 * - [DRIVER]          — driver-aid HUD: icon-only objects (no bounding boxes),
 *   a top-right traffic-light / sign panel, and optional audio warnings.
 *
 * Enum ordinal order must match the `view_modes` string array in `strings.xml`.
 * New values are appended at the end so persisted enum names stay valid.
 */
enum class ViewMode {
    NORMAL,
    BLACK_AND_WHITE,
    INVERT,
    HEATMAP,
    DRIVER,
}