package com.yolo.detector.inference

import android.graphics.Bitmap
import com.yolo.detector.data.Detection
import com.yolo.detector.data.InferenceSettings
import java.io.Closeable

interface Detector : Closeable {
    val profile: ModelProfile

    var settings: InferenceSettings

    fun detect(bitmap: Bitmap): List<Detection>
}
