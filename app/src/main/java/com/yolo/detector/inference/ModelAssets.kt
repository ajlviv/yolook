package com.yolo.detector.inference

/**
 * Names and presence checks for the on-device TFLite model packaged under `src/main/assets`.
 */
object ModelAssets {
    const val FILE_NAME = "yolov8m.tflite"

    fun isListed(assetNames: Array<String>?): Boolean =
        assetNames?.contains(FILE_NAME) == true
}
