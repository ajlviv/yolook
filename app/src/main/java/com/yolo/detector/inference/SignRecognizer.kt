package com.yolo.detector.inference

import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.Detection
import com.yolo.detector.data.SignHud
import com.yolo.detector.data.SignType

/**
 * Recognizes roadside signs from [Detection]s.
 *
 * The stock COCO model exposes **stop sign** (class 11) directly. It has *no*
 * speed-limit class, so [recognize] maps class 11 → [SignType.STOP] and delegates
 * speed-limit detection to the injectable [speedLimitRecognizer], which is a no-op
 * by default. Swapping in a detector trained on speed-limit signs (or a crop
 * classifier) later is a drop-in change — see [SpeedLimitSignRecognizer].
 */
class SignRecognizer(
    private val speedLimitRecognizer: SpeedLimitSignRecognizer = NoOpSpeedLimitRecognizer,
) {

    /** Builds the list of recognized signs for the current detection set. */
    fun recognize(detections: List<Detection>): List<SignHud> {
        val signs = mutableListOf<SignHud>()

        for (detection in detections) {
            if (detection.classId == COCO_LABELS.indexOf("stop sign")) {
                signs.add(SignHud(SignType.STOP, "STOP"))
            }
        }

        signs.addAll(speedLimitRecognizer.recognize(detections))
        return signs
    }
}

/** Extension hook for recognising speed-limit signs (value + presence). */
fun interface SpeedLimitSignRecognizer {
    fun recognize(detections: List<Detection>): List<SignHud>
}

/** Default: the stock model cannot detect speed-limit signs, so none are emitted. */
object NoOpSpeedLimitRecognizer : SpeedLimitSignRecognizer {
    override fun recognize(detections: List<Detection>): List<SignHud> = emptyList()
}