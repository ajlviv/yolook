package com.yolo.detector.util

import kotlin.math.roundToLong

/**
 * Snaps [value] onto the discrete grid defined by [from] + n * [step], clamped to [[from], [to]].
 *
 * Material [com.google.android.material.slider.Slider] throws IllegalStateException when
 * assigned a value that is not on the step grid, and its change listener can emit raw
 * un-snapped touch values. Use this before persisting or re-assigning slider values.
 *
 * Rounding is done in Double precision to avoid float drift (e.g. 0.1 + 5 * 0.05 != 0.35f).
 */
fun snapToStep(value: Float, from: Float, to: Float, step: Float): Float {
    if (step <= 0f) return value.coerceIn(from, to)
    val clamped = value.coerceIn(from, to)
    val steps = ((clamped - from).toDouble() / step.toDouble()).roundToLong()
    val snapped = from.toDouble() + steps * step.toDouble()
    return snapped.toFloat().coerceIn(from, to)
}
