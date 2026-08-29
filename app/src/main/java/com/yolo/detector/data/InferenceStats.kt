package com.yolo.detector.data

/**
 * Real-time performance metrics emitted by [com.yolo.detector.ui.MainViewModel]
 * and shown in the LiveFragment stats bar.
 */
data class InferenceStats(
    /** Smoothed inference frames per second (exponential moving average). */
    val fps: Float = 0f,
    /** Duration of the last TFLite inference call in milliseconds. */
    val inferenceMs: Long = 0L,
    /** Number of tracked objects in the most recent frame. */
    val objectCount: Int = 0,
)
