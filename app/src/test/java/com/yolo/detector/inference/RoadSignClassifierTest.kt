package com.yolo.detector.inference

import com.yolo.detector.data.SignType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class RoadSignClassifierTest {

    @Test
    fun classifiesNoEntrySign() {
        val size = 30
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = sqrt(dx * dx + dy * dy)

                if (dist <= radius * 0.85f) {
                    // White horizontal bar across middle
                    if (y in (size * 0.42f).toInt()..(size * 0.58f).toInt() && x in (size * 0.22f).toInt()..(size * 0.78f).toInt()) {
                        pixels[y * size + x] = 0xFFFFFFFF.toInt()
                    } else {
                        pixels[y * size + x] = 0xFFFF0000.toInt()
                    }
                } else {
                    pixels[y * size + x] = 0xFF555555.toInt()
                }
            }
        }

        val result = RoadSignClassifier.classify(pixels, size, size)
        assertNotNull(result)
        assertEquals(SignType.NO_ENTRY, result!!.type)
        assertEquals("NO ENTRY", result.label)
    }

    @Test
    fun classifiesPedestrianCrossingSign() {
        val size = 30
        val pixels = IntArray(size * size) { 0xFF0055FF.toInt() } // Blue background

        // Dark silhouette in center
        for (y in 10..20) {
            for (x in 12..18) {
                pixels[y * size + x] = 0xFF000000.toInt()
            }
        }

        val result = RoadSignClassifier.classify(pixels, size, size)
        assertNotNull(result)
        assertEquals(SignType.PEDESTRIAN_CROSSING, result!!.type)
    }

    @Test
    fun returnsNull_onPlainBackground() {
        val grey = IntArray(24 * 24) { 0xFF666666.toInt() }
        val result = RoadSignClassifier.classify(grey, 24, 24)
        assertNull(result)
    }
}
