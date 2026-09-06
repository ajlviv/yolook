package com.yolo.detector.data

/**
 * Visual filter applied to the live camera preview.
 *
 * [NORMAL] shows the raw camera stream in `PreviewView`. The remaining modes hide
 * the raw stream and render each inference frame through a colored `ImageView`:
 * - [BLACK_AND_WHITE] — grayscale luminance mapping (GPU color-matrix filter).
 * - [INVERT]          — RGB channel inversion (GPU color-matrix filter).
 * - [HEATMAP]         — intensity → hot-metal colormap (baked per pixel).
 *
 * Enum ordinal order must match the `view_modes` string array in `strings.xml`.
 */
enum class ViewMode {
    NORMAL,
    BLACK_AND_WHITE,
    INVERT,
    HEATMAP,
}