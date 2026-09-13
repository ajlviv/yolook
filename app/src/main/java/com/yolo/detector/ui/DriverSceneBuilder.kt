package com.yolo.detector.ui

import android.graphics.Bitmap
import com.yolo.detector.data.COCO_LABELS
import com.yolo.detector.data.CollisionAlertLevel
import com.yolo.detector.data.Detection
import com.yolo.detector.data.DriverScene
import com.yolo.detector.data.PedestrianThreatType
import com.yolo.detector.data.SignType
import com.yolo.detector.data.TrafficLightHud
import com.yolo.detector.data.TrafficLightSignal
import com.yolo.detector.data.WarningType
import com.yolo.detector.inference.FcwEstimator
import com.yolo.detector.inference.LaneClusterer
import com.yolo.detector.inference.PedestrianThreatAnalyzer
import com.yolo.detector.inference.RoadZone
import com.yolo.detector.inference.SignRecognizer
import com.yolo.detector.inference.SignalStabilizer
import com.yolo.detector.inference.TrafficLightColorEstimator

/**
 * Builds a [DriverScene] for one analyzed frame from the raw [Detection]s and the source [Bitmap].
 *
 * Coordinates:
 * - Traffic light color extraction and lane assignment.
 * - Lead vehicle selection, headway distance & Forward Collision Warning (FCW).
 * - Pedestrian trajectory & crossing hazard analysis.
 * - Sign recognition.
 * - Warning dispatching for HUD and Audio Alert managers.
 */
class DriverSceneBuilder(
    private val stabilizer: SignalStabilizer = SignalStabilizer(),
    private val signRecognizer: SignRecognizer = SignRecognizer(),
    private val fcwEstimator: FcwEstimator = FcwEstimator(),
    private val pedestrianThreatAnalyzer: PedestrianThreatAnalyzer = PedestrianThreatAnalyzer(),
) {

    private val trafficLightClass: Int get() = COCO_LABELS.indexOf("traffic light").coerceAtLeast(0)
    private val personClass: Int get() = COCO_LABELS.indexOf("person").coerceAtLeast(0)

    /**
     * Builds a [DriverScene]. [frame] may be null (color classification is then
     * skipped and signals default to [TrafficLightSignal.UNKNOWN]).
     */
    fun build(detections: List<Detection>, frame: Bitmap?): DriverScene {
        val lights = detections.filter { it.classId == trafficLightClass }
        val persons = detections.filter { it.classId == personClass }

        // ── Traffic lights: signal + lanes ────────────────────────────────────
        val hudLights = mutableListOf<TrafficLightHud>()
        val xCenters = mutableListOf<Float>()

        for (detection in lights) {
            val signal = if (frame != null && !frame.isRecycled) {
                val pixels = sampleRegion(frame, detection.bbox)
                TrafficLightColorEstimator.classify(pixels)
            } else {
                TrafficLightSignal.UNKNOWN
            }
            val stable = stabilizer.update(detection.trackId, signal)
            if (stable != TrafficLightSignal.UNKNOWN) {
                hudLights.add(TrafficLightHud(detection.trackId, stable, 0))
                xCenters.add((detection.bbox.left + detection.bbox.right) / 2f)
            }
        }

        val laneLights = LaneClusterer.assign(hudLights, xCenters)

        // ── Forward Collision Warning & Lead Vehicle ──────────────────────────
        val leadVehicle = fcwEstimator.estimate(detections)

        // ── Pedestrians & Crossing Hazards ────────────────────────────────────
        val pedAlerts = pedestrianThreatAnalyzer.analyze(persons)
        val peopleOnRoad = pedAlerts.count { it.threatType != PedestrianThreatType.SAFE_SIDEWALK }

        // ── Signs ─────────────────────────────────────────────────────────────
        val signs = signRecognizer.recognize(detections, frame)

        // ── Warnings ──────────────────────────────────────────────────────────
        val warnings = mutableSetOf<WarningType>()
        val hasRed = laneLights.any { it.signal == TrafficLightSignal.RED }
        val hasYellow = laneLights.any { it.signal == TrafficLightSignal.YELLOW }
        val hasSpeedLimit = signs.any { it.type == SignType.SPEED_LIMIT }

        if (hasRed) warnings.add(WarningType.RED_LIGHT)
        if (hasYellow) warnings.add(WarningType.YELLOW_LIGHT)
        if (hasSpeedLimit) warnings.add(WarningType.SPEED_LIMIT)
        if (peopleOnRoad > 0) warnings.add(WarningType.PERSON_ON_ROAD)

        if (leadVehicle?.alertLevel == CollisionAlertLevel.COLLISION_WARNING) {
            warnings.add(WarningType.FORWARD_COLLISION)
        } else if (leadVehicle?.alertLevel == CollisionAlertLevel.TAILGATING) {
            warnings.add(WarningType.TAILGATING)
        }

        if (pedAlerts.any { it.threatType == PedestrianThreatType.CROSSING_PATH }) {
            warnings.add(WarningType.PEDESTRIAN_CROSSING)
        }

        return DriverScene(
            trafficLights = laneLights,
            signs = signs,
            activeSpeedLimit = signRecognizer.activeSpeedLimit,
            peopleOnRoad = peopleOnRoad,
            leadVehicle = leadVehicle,
            pedestrianAlerts = pedAlerts,
            warnings = warnings,
        )
    }

    /** Reclaims per-track signal state (call when the camera pipeline restarts). */
    fun reset() {
        stabilizer.reset()
        fcwEstimator.reset()
        pedestrianThreatAnalyzer.reset()
        signRecognizer.reset()
    }

    /**
     * Down-samples the normalized [bbox] region of [frame] into an [IntArray] of
     * packed ARGB pixels for color classification.
     */
    private fun sampleRegion(frame: Bitmap, bbox: android.graphics.RectF): IntArray {
        val cols = 10
        val rows = 10
        if (frame.isRecycled) return IntArray(cols * rows) { 0xFF000000.toInt() }
        val left = (bbox.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (bbox.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (bbox.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (bbox.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)

        val result = IntArray(cols * rows)
        val single = IntArray(1)
        var idx = 0
        for (r in 0 until rows) {
            val y = top + ((bottom - top - 1) * r / (rows - 1))
            for (c in 0 until cols) {
                val x = left + ((right - left - 1) * c / (cols - 1))
                frame.getPixels(single, 0, 1, x, y, 1, 1)
                result[idx] = single[0]
                idx++
            }
        }
        return result
    }
}