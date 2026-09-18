package com.yolo.detector.data

/**
 * What the Live-tab action button does: capture a photo snapshot or record a
 * video.
 *
 * Toggled from the Live screen (the small icon button above the action FAB);
 * persisted in DataStore by name.
 */
enum class CaptureMode {
    PHOTO,
    VIDEO,
}