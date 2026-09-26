package com.yolo.detector.data

/**
 * Profile identity and label vocabularies.
 *
 * Class IDs are only meaningful inside one model's vocabulary, so nothing here is a
 * global lookup any more: the active profile's [labels] are threaded to every render
 * site (see [labelFor]). The IDs themselves live in the `data` package so
 * [com.yolo.detector.inference.ModelAssets] and `InferenceSettings` can share them
 * without the data layer depending on the inference layer.
 */

/** Profile id of the default COCO detection model. */
const val DEFAULT_MODEL_PROFILE_ID = "yolo11n-coco"

/** Profile id of the YOLOE 11s segmentation model. */
const val YOLOE_PROFILE_ID = "yoloe-11s-seg"

/**
 * Class vocabulary of `yoloe-11s-seg.tflite`, in the order the export emits class
 * channels. Confirmed against the model: 39 output channels = 4 box + 3 class + 32 mask.
 */
val YOLOE_LABELS: List<String> = listOf("eye", "smile", "nose")

/**
 * Returns the label for [classId] within [labels], or `"unknown"` when out of range.
 *
 * Always pass the *active* profile's labels — a class id from one model is meaningless
 * in another's vocabulary.
 */
fun labelFor(classId: Int, labels: List<String>): String = labels.getOrElse(classId) { "unknown" }
