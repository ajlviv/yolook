package com.yolo.detector.data

/**
 * Visual filter applied to the live camera preview.
 *
 * [NORMAL] shows the raw camera stream in `PreviewView`. The remaining modes hide
 * the raw stream and render each inference frame through a colored `ImageView`:
 * - [BLACK_AND_WHITE] — grayscale luminance mapping (GPU color-matrix filter).
 * - [INVERT]          — RGB channel inversion (GPU color-matrix filter).
 * - [HEATMAP]         — intensity → hot-metal colormap (baked per pixel).
 * - [EDGE]            — Sobel edge detection masked to detection boxes, so only
 *   detected objects are drawn (green edges on black).
 * - [MATRIX]          — Matrix-style "digital rain": the frame is rendered as a
 *   grid of bright-green glyphs whose density follows local brightness.
 *
 * Object counting is a separate Live-tab toggle, not a view mode: it draws
 * per-object running numbers inside boxes with per-frame class counts.
 *
 * Enum ordinal order must match the `view_modes` string array in `strings.xml`.
 * Append new entries at the end so persisted ordinals stay valid.
 */
enum class ViewMode {
    NORMAL,
    BLACK_AND_WHITE,
    INVERT,
    HEATMAP,
    EDGE,
    MATRIX,
}