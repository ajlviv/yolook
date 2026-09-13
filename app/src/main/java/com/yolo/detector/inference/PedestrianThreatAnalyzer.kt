package com.yolo.detector.inference

import com.yolo.detector.data.Detection
import com.yolo.detector.data.PedestrianAlert
import com.yolo.detector.data.PedestrianThreatType
import kotlin.math.abs

/**
 * Pedestrian Trajectory & Crossing Threat Analyzer.
 *
 * Distinguishes safe sidewalk pedestrians from pedestrians in the ego-lane or
 * actively stepping across into the vehicle's driving corridor.
 */
class PedestrianThreatAnalyzer {

    private data class PersonHistory(
        val timestampMs: Long,
        val centerX: Float,
        val bottomY: Float,
    )

    private val personHistories = mutableMapOf<Int, PersonHistory>()

    /**
     * Evaluates detected persons and returns threat classifications for each.
     */
    fun analyze(persons: List<Detection>): List<PedestrianAlert> {
        val alerts = mutableListOf<PedestrianAlert>()

        for (person in persons) {
            val left = person.bbox.left
            val right = person.bbox.right
            val top = person.bbox.top
            val bottom = person.bbox.bottom
            val centerX = (left + right) / 2f
            val bottomY = bottom.coerceIn(0f, 1.0f)

            // Direct in-lane hazard
            if (RoadZone.isBoxInEgoCorridor(left, top, right, bottom)) {
                alerts.add(PedestrianAlert(person.trackId, PedestrianThreatType.IN_LANE))
                continue
            }

            val corridorLeft = RoadZone.getEgoCorridorLeft(bottomY)
            val corridorRight = RoadZone.getEgoCorridorRight(bottomY)

            val prev = personHistories[person.trackId]
            personHistories[person.trackId] = PersonHistory(person.timestampMs, centerX, bottomY)

            if (prev == null || person.timestampMs <= prev.timestampMs) {
                // If in road zone vertically but outside corridor horizontally
                if (RoadZone.overlapsRoad(bottom, top)) {
                    alerts.add(PedestrianAlert(person.trackId, PedestrianThreatType.SAFE_SIDEWALK))
                }
                continue
            }

            val dt = (person.timestampMs - prev.timestampMs) / 1000f
            if (dt <= 0.02f || dt > 1.0f) {
                alerts.add(PedestrianAlert(person.trackId, PedestrianThreatType.SAFE_SIDEWALK))
                continue
            }

            // Crossing velocity (positive = moving right, negative = moving left)
            val vx = (centerX - prev.centerX) / dt

            var isCrossingTowardsCorridor = false
            var timeToCrossing: Float? = null

            if (centerX < corridorLeft && vx > 0.02f) {
                // Moving from left sidewalk into corridor
                val distToLane = corridorLeft - centerX
                val tti = distToLane / vx
                if (tti in 0.0f..3.0f) {
                    isCrossingTowardsCorridor = true
                    timeToCrossing = tti
                }
            } else if (centerX > corridorRight && vx < -0.02f) {
                // Moving from right sidewalk into corridor
                val distToLane = centerX - corridorRight
                val tti = distToLane / abs(vx)
                if (tti in 0.0f..3.0f) {
                    isCrossingTowardsCorridor = true
                    timeToCrossing = tti
                }
            }

            if (isCrossingTowardsCorridor) {
                alerts.add(
                    PedestrianAlert(
                        trackId = person.trackId,
                        threatType = PedestrianThreatType.CROSSING_PATH,
                        timeToCrossingSec = timeToCrossing,
                    )
                )
            } else if (RoadZone.overlapsRoad(bottom, top)) {
                alerts.add(PedestrianAlert(person.trackId, PedestrianThreatType.SAFE_SIDEWALK))
            }
        }

        return alerts
    }

    /** Clears historical tracking state. */
    fun reset() {
        personHistories.clear()
    }
}
