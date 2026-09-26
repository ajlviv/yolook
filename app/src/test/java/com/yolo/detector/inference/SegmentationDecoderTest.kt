package com.yolo.detector.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationDecoderTest {

    @Test
    fun sigmoidIsIdentityAtZero() {
        assertEquals(0.5f, SegmentationDecoder.sigmoid(0f), 1e-6f)
    }

    @Test
    fun sigmoidSaturatesAtExtremes() {
        assertEquals(1f, SegmentationDecoder.sigmoid(50f), 1e-6f)
        assertEquals(0f, SegmentationDecoder.sigmoid(-50f), 1e-6f)
    }

    @Test
    fun sigmoidIsMonotonic() {
        var previous = -1f
        for (v in -8..8) {
            val s = SegmentationDecoder.sigmoid(v.toFloat())
            assertTrue("sigmoid must increase at $v", s > previous)
            previous = s
        }
    }

    @Test
    fun positivePrototypeSelectsEveryPixel() {
        // One channel, 2x2 grid, strongly positive logits -> sigmoid ~ 1 everywhere.
        val protos = floatArrayOf(8f, 8f, 8f, 8f)

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = protos,
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        assertEquals(2, mask.width)
        assertEquals(2, mask.height)
        assertEquals(4, mask.pixelCount)
        for (y in 0 until 2) for (x in 0 until 2) assertTrue(mask[x, y])
    }

    @Test
    fun negativePrototypeRejectsEveryPixel() {
        val protos = floatArrayOf(-8f, -8f, -8f, -8f)

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = protos,
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        assertEquals(0, mask.pixelCount)
    }

    @Test
    fun maskIsCroppedToTheBox() {
        // All-positive prototypes, but the box only covers the bottom-right quarter.
        val protos = FloatArray(16) { 8f }

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = protos,
            channels = 1,
            protoWidth = 4,
            protoHeight = 4,
            boxLeft = 0.5f, boxTop = 0.5f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        // Only the 2x2 block at (2..3, 2..3) survives.
        assertEquals(4, mask.pixelCount)
        for (y in 0 until 2) for (x in 0 until 2) assertFalse(mask[x, y])
        for (y in 2 until 4) for (x in 0 until 2) assertFalse(mask[x, y])
        for (y in 2 until 4) for (x in 2 until 4) assertTrue(mask[x, y])
    }

    @Test
    fun maskCarriesTheFrameRectItCovers() {
        val protos = FloatArray(4) { 8f }

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = protos,
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0.25f, rectTop = 0.5f, rectRight = 0.75f, rectBottom = 1f,
        )

        assertEquals(0.25f, mask.left, 0f)
        assertEquals(0.5f, mask.top, 0f)
        assertEquals(0.75f, mask.right, 0f)
        assertEquals(1f, mask.bottom, 0f)
    }

    @Test
    fun coefficientsAreCombinedAcrossChannels() {
        // Channel 0 strongly positive, channel 1 strongly negative. With both
        // coefficients at 1 the logits cancel to 0 -> sigmoid 0.5, which is not > 0.5.
        val protos = floatArrayOf(8f, 8f, 8f, 8f, -8f, -8f, -8f, -8f)

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f, 1f),
            prototypes = protos,
            channels = 2,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        assertEquals(0, mask.pixelCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoefficientChannelMismatch() {
        SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f, 1f),
            prototypes = FloatArray(4) { 8f },
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrototypeBufferTooSmall() {
        SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = FloatArray(2) { 8f },
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )
    }

    // ── FloatBuffer overload (the interpreter's direct output) ───────────────

    @Test
    fun floatBufferOverloadMatchesFloatArrayOverload() {
        val protos = floatArrayOf(8f, 8f, -8f, -8f)

        fun via(buffer: java.nio.FloatBuffer) = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = buffer,
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        assertEquals(via(java.nio.FloatBuffer.wrap(protos)), via(java.nio.FloatBuffer.wrap(protos)))
        // Two positive prototypes of 8f clear the threshold; the two -8f ones do not.
        assertEquals(2, via(java.nio.FloatBuffer.wrap(protos)).pixelCount)
    }

    @Test
    fun floatBufferOverloadReadsFromTheBufferPosition() {
        // The decoder indexes absolutely, so a buffer that has already been consumed
        // (a position left behind by an earlier pass) must still decode the whole grid
        // rather than reading from the offset.
        val buffer = java.nio.FloatBuffer.wrap(floatArrayOf(8f, 8f, -8f, -8f))
        buffer.position(2)

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = buffer,
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        assertTrue(mask[0, 0])
        assertTrue(mask[1, 0])
        assertFalse(mask[0, 1])
        assertFalse(mask[1, 1])
    }

    @Test
    fun floatBufferOverloadIgnoresTrailingFloats() {
        // The prototype output is allocated from the tensor's element count; extra
        // capacity (or a larger allocation) must not disturb indexing.
        val buffer = java.nio.FloatBuffer.wrap(floatArrayOf(8f, 8f, -8f, -8f, 99f, 99f))

        val mask = SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = buffer,
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )

        assertEquals(2, mask.pixelCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFloatBufferTooSmall() {
        SegmentationDecoder.buildMask(
            coefficients = floatArrayOf(1f),
            prototypes = java.nio.FloatBuffer.wrap(FloatArray(3) { 8f }),
            channels = 1,
            protoWidth = 2,
            protoHeight = 2,
            boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
            rectLeft = 0f, rectTop = 0f, rectRight = 1f, rectBottom = 1f,
        )
    }
}
