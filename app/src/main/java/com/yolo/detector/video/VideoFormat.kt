package com.yolo.detector.video

/**
 * Pure helpers for the frame-video pipeline. Kept free of Android types so the
 * resolution/bitrate math is unit-testable on the JVM.
 */

/**
 * Even recording width for a portrait-locked screen whose aspect is [aspect]
 * (width / height), given a target output [height]. H.264 requires even
 * dimensions.
 */
fun videoWidthForHeight(height: Int, aspect: Float): Int {
    var w = (height * aspect).toInt()
    if (w % 2 != 0) w++
    return w.coerceAtLeast(2)
}

/**
 * A reasonable H.264 average bitrate for the given output [height] and [fps].
 * Scales with both so quality stays roughly constant across resolutions.
 */
fun videoBitrateFor(height: Int, fps: Int): Int {
    val mbps = when {
        height >= 1080 -> if (fps >= 30) 18 else 12
        height >= 720 -> if (fps >= 30) 10 else 7
        else -> if (fps >= 30) 5 else 3
    }
    return mbps * 1_000_000
}