package com.yolo.detector.inference

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a CameraX [ImageProxy] to an RGB [Bitmap] with orientation rotation applied.
 *
 * Uses CameraX's native YUV-to-RGB conversion to avoid buffer index errors on varying hardware strides.
 */
fun ImageProxy.toRgbBitmap(): Bitmap {
    val raw = this.toBitmap()
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
