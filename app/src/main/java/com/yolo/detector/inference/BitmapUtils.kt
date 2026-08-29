package com.yolo.detector.inference

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a CameraX [ImageProxy] (YUV_420_888) to an RGB [Bitmap].
 *
 * The returned bitmap is in ARGB_8888 format and is rotated to match the
 * display orientation using [ImageProxy.imageInfo.rotationDegrees].
 */
fun ImageProxy.toBitmap(): Bitmap {
    val yuvBytes = yuv420ToNv21()
    val yuvImage = YuvImage(yuvBytes, ImageFormat.NV21, width, height, null)

    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()

    val raw = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

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
 * Converts the YUV_420_888 planes of this [ImageProxy] to NV21 byte array
 * suitable for [YuvImage].
 */
private fun ImageProxy.yuv420ToNv21(): ByteArray {
    val yPlane  = planes[0]
    val uPlane  = planes[1]
    val vPlane  = planes[2]

    val ySize = yPlane.buffer.remaining()
    val uSize = uPlane.buffer.remaining()
    val vSize = vPlane.buffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yPlane.buffer.get(nv21, 0, ySize)
    // CameraX gives U and V planes separately; NV21 needs V then U interleaved
    vPlane.buffer.get(nv21, ySize, vSize)
    uPlane.buffer.get(nv21, ySize + vSize, uSize)

    return nv21
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
        .allocateDirect(1 * inputSize * inputSize * 3 * 4) // FLOAT32 = 4 bytes
        .apply { order(ByteOrder.nativeOrder()) }

    val pixels = IntArray(inputSize * inputSize)
    scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
    if (scaled !== this) scaled.recycle()

    for (pixel in pixels) {
        buffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
        buffer.putFloat(((pixel shr  8) and 0xFF) / 255f) // G
        buffer.putFloat(( pixel         and 0xFF) / 255f) // B
    }

    buffer.rewind()
    return buffer
}
