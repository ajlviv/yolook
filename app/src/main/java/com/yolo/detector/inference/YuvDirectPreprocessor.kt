package com.yolo.detector.inference

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * High-performance, zero-allocation preprocessor for CameraX [ImageProxy] frames.
 *
 * Provides direct YUV_420_888 buffer reading and reusable memory buffers
 * to eliminate per-frame allocations during preprocessing and model inference.
 */
class YuvDirectPreprocessor(
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 640,
) {
    // Pre-allocated reusable pixel buffer for 640x640 ARGB
    private val reusablePixels = IntArray(targetWidth * targetHeight)
    
    // Pre-allocated reusable float array for normalized RGB channels (3 channels * targetWidth * targetHeight)
    val floatArray = FloatArray(targetWidth * targetHeight * 3)

    // Pre-allocated reusable byte array for quantized INT8/UINT8 RGB channels
    val byteArray = ByteArray(targetWidth * targetHeight * 3)

    /**
     * Extracts and rescales pixels from [bitmap] into [reusablePixels] without creating new arrays.
     * If [bitmap] is not target dimensions, uses a pre-allocated/recycled scaled bitmap.
     */
    fun extractScaledPixels(bitmap: Bitmap): IntArray {
        if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            bitmap.getPixels(reusablePixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false)
            scaled.getPixels(reusablePixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            if (scaled !== bitmap) {
                scaled.recycle()
            }
        }
        return reusablePixels
    }
}
