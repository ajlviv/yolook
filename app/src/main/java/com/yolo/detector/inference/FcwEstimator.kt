package com.yolo.detector.inference

import com.yolo.detector.data.CollisionAlertLevel
import com.yolo.detector.data.Detection
import com.yolo.detector.data.LeadVehicleHud
import com.yolo.detector.data.VEHICLE_CLASS_IDS

/**
 * Forward Collision Warning (FCW) & Lead Vehicle Headway Estimator.
 *
 * Evaluates vehicles in the driver's perspective ego-lane, estimates monocular
 * distance in meters, tracks relative closure velocity ($V_{rel}$), and computes
 * Time-to-Collision (TTC) to trigger tailgating and imminent collision warnings.
 */
class FcwEstimator {

    private data class TrackHistory(
        val timestampMs: Long,
        val distance: Float,
        val area: Float,
    )

    private val trackHistories = mutableMapOf<Int, TrackHistory>()

    /**
     * Identifies the lead vehicle in the ego-lane corridor and assesses forward collision risk.
     */
    fun estimate(detections: List<Detection>): LeadVehicleHud? {
        val vehicles = detections.filter { it.classId in VEHICLE_CLASS_IDS }
        if (vehicles.isEmpty()) {
            return null
        }

        // Filter vehicles inside or intersecting the perspective ego-corridor
        val inLaneVehicles = vehicles.filter { det ->
            RoadZone.isBoxInEgoCorridor(det.bbox.left, det.bbox.top, det.bbox.right, det.bbox.bottom)
        }

        if (inLaneVehicles.isEmpty()) {
            return null
        }

        // Closest vehicle has the highest bottom Y (closest to bottom of screen / vehicle hood)
        val leadVehicle = inLaneVehicles.maxByOrNull { it.bbox.bottom } ?: return null

        val distance = estimateDistance(leadVehicle)
        val ttc = computeTtc(leadVehicle.trackId, distance, leadVehicle.bbox, leadVehicle.timestampMs)

        val alertLevel = when {
            ttc != null && ttc < 1.8f -> CollisionAlertLevel.COLLISION_WARNING
            distance < 7.0f -> CollisionAlertLevel.COLLISION_WARNING
            ttc != null && ttc < 2.8f -> CollisionAlertLevel.TAILGATING
            distance < 12.0f -> CollisionAlertLevel.TAILGATING
            else -> CollisionAlertLevel.SAFE
        }

        return LeadVehicleHud(
            trackId = leadVehicle.trackId,
            distanceMeters = distance,
            timeToCollisionSec = ttc,
            alertLevel = alertLevel,
        )
    }

    /**
     * Estimates distance in meters from monocular bounding box perspective and ground plane contact.
     */
    private fun estimateDistance(detection: Detection): Float {
        val bottomY = detection.bbox.bottom.coerceIn(RoadZone.HORIZON_Y + 0.01f, 1.0f)
        val boxHeight = (detection.bbox.bottom - detection.bbox.top).coerceAtLeast(0.01f)

        // Geometric ground-plane perspective distance formula: D = k / (y - y_horizon)
        val groundDist = 3.6f / (bottomY - RoadZone.HORIZON_Y)
        // Angular height distance formula: D = (f * H_vehicle) / h_box
        val heightDist = 1.6f / boxHeight

        // Weighted combination calibrated for typical automotive dashcam mounting
        val rawDist = 0.6f * groundDist + 0.4f * heightDist
        return rawDist.coerceIn(2.0f, 100.0f)
    }

    /**
     * Computes Time-to-Collision (TTC) using relative approach speed.
     */
    private fun computeTtc(trackId: Int, currentDist: Float, bbox: android.graphics.RectF, timestampMs: Long): Float? {
        val area = (bbox.right - bbox.left) * (bbox.bottom - bbox.top)
        val prev = trackHistories[trackId]
        trackHistories[trackId] = TrackHistory(timestampMs, currentDist, area)

        if (prev == null || timestampMs <= prev.timestampMs) {
            return null
        }

        val dt = (timestampMs - prev.timestampMs) / 1000.0f
        if (dt <= 0.02f || dt > 1.0f) {
            return null
        }

        // Relative approach speed (positive when closing in)
        val vRel = (prev.distance - currentDist) / dt
        return if (vRel > 0.8f) {
            (currentDist / vRel).coerceIn(0.2f, 10.0f)
        } else {
            null
        }
    }

    /** Clears historical tracking state. */
    fun reset() {
        trackHistories.clear()
    }
}
