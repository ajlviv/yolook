package com.yolo.detector.util

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class ValueSnapTest {

    private val eps = 1e-6f

    @Test
    fun `raw drag values snap onto grid`() {
        assertEquals(0.35f, snapToStep(0.36666664f, 0.1f, 0.9f, 0.05f), eps)
        assertEquals(0.80f, snapToStep(0.7933333f, 0.1f, 0.9f, 0.05f), eps)
    }

    @Test
    fun `already aligned values unchanged`() {
        assertEquals(0.40f, snapToStep(0.40f, 0.1f, 0.9f, 0.05f), eps)
        assertEquals(0.45f, snapToStep(0.45f, 0.1f, 0.9f, 0.05f), eps)
    }

    @Test
    fun `values clamped to range`() {
        assertEquals(0.1f, snapToStep(0.02f, 0.1f, 0.9f, 0.05f), eps)
        assertEquals(0.9f, snapToStep(1.5f, 0.1f, 0.9f, 0.05f), eps)
    }

    @Test
    fun `result passes BaseSlider validation semantics`() {
        // (value - from) / step must be (near-)integer for every snapped value
        listOf(0.36666664f, 0.7933333f, 0.1f, 0.9f, 0.5f).forEach { raw ->
            val snapped = snapToStep(raw, 0.1f, 0.9f, 0.05f)
            val steps = (snapped - 0.1f) / 0.05f
            assertEquals(steps.roundToInt().toFloat(), steps, 1e-4f)
        }
    }
}
