package com.yolo.detector.inference

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a CameraX [ImageProxy] (YUV_420_888) to an RGB [Bitmap].
 *
 * Performs direct YUV→ARGB conversion without JPEG encoding/decoding,
 * reducing memory allocations and avoiding quality loss.
 */
fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val yRowStride = planes[0].rowStride
    val uvRowStride = planes[1].rowStride
    val uvPixelStride = planes[1].pixelStride

    val w = width
    val h = height
    val argb = IntArray(w * h)

    for (row in 0 until h) {
        for (col in 0 until w) {
            val yIndex = row * yRowStride + col
            val y = (yBuffer.get(yIndex).toInt() and 0xFF) - 16

            val uvRow = row shr 1
            val uvCol = col shr 1
            val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride
            val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
            val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

            val r = (1.164 * y + 1.596 * v).toInt().coerceIn(0, 255)
            val g = (1.164 * y - 0.392 * u - 0.813 * v).toInt().coerceIn(0, 255)
            val b = (1.164 * y + 2.017 * u).toInt().coerceIn(0, 255)

            argb[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    val raw = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)

    val rotationDegrees = imageInfo.rotationDegrees
    return if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        raw.recycle()
        rotated
    } else {
        raw
    }
}

/**
 * Scales this [Bitmap] to [inputSize]×[inputSize] and converts it to a
 * direct [ByteBuffer] suitable as TFLite FLOAT32 input.
 *
 * Pixel values are normalised to [0, 1] (divide by 255).
 *
 * @param inputSize Target square size (640 for YOLOv8m).
 * @return Direct ByteBuffer containing [inputSize × inputSize × 3 × 4] bytes.
 */
fun Bitmap.toByteBuffer(inputSize: Int): ByteBuffer {
    val scaled = Bitmap.createScaledBitmap(this, inputSize, inputSize, true)

    val buffer = ByteBuffer
        .allocateDirect(1 * inputSize * inputSize * 3 * 4)
        .apply { order(ByteOrder.nativeOrder()) }

    val pixels = IntArray(inputSize * inputSize)
    scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
    if (scaled !== this) scaled.recycle()

    for (pixel in pixels) {
        buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
        buffer.putFloat(((pixel shr  8) and 0xFF) / 255f)
        buffer.putFloat(( pixel         and 0xFF) / 255f)
    }

    buffer.rewind()
    return buffer
}
