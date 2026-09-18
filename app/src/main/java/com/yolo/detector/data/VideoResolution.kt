package com.yolo.detector.data

/**
 * Recorded-video output height in pixels. The width follows the screen aspect
 * ratio, since the app is locked to portrait.
 *
 * Persisted in DataStore by name; ordinal order must match the
 * `video_resolutions` string array in `strings.xml`. Append new entries at the
 * end so persisted ordinals stay valid.
 */
enum class VideoResolution(val height: Int) {
    SD(480),
    HD(720),
    FHD(1080),
}