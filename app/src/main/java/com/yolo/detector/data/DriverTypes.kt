package com.yolo.detector.data

/** Lit signal shown by a traffic light. [OFF]/[UNKNOWN] map to no warning. */
enum class TrafficLightSignal {
    RED,
    YELLOW,
    GREEN,
    OFF,
    UNKNOWN,
}

/** Recognized roadside sign. */
enum class SignType {
    STOP,
    SPEED_LIMIT,
    YIELD,
    NO_ENTRY,
    PEDESTRIAN_CROSSING,
    UNKNOWN,
}

/** Forward collision risk severity levels. */
enum class CollisionAlertLevel {
    SAFE,
    TAILGATING,
    COLLISION_WARNING,
}

/** Pedestrian hazard classification. */
enum class PedestrianThreatType {
    SAFE_SIDEWALK,
    CROSSING_PATH,
    IN_LANE,
}

/** Driver-mode warning categories, used to gate audio alerts. */
enum class WarningType {
    RED_LIGHT,
    YELLOW_LIGHT,
    SPEED_LIMIT,
    PERSON_ON_ROAD,
    FORWARD_COLLISION,
    TAILGATING,
    PEDESTRIAN_CROSSING,
}

/** One traffic light shown in the top-right HUD. */
data class TrafficLightHud(
    val trackId: Int,
    val signal: TrafficLightSignal,
    val laneIndex: Int,
)

/** One recognized sign shown above the traffic-light HUD. */
data class SignHud(
    val type: SignType,
    val label: String,
)

/** Lead vehicle directly ahead in ego-lane. */
data class LeadVehicleHud(
    val trackId: Int,
    val distanceMeters: Float,
    val timeToCollisionSec: Float?,
    val alertLevel: CollisionAlertLevel,
)

/** Pedestrian hazard tracking entry. */
data class PedestrianAlert(
    val trackId: Int,
    val threatType: PedestrianThreatType,
    val timeToCrossingSec: Float? = null,
)

/**
 * Aggregated driver-aid scene for a single analyzed frame.
 *
 * Built from raw [Detection]s + the source frame by [com.yolo.detector.ui.DriverSceneBuilder]
 * and consumed by the [com.yolo.detector.ui.overlay.DriverHudOverlay] and the
 * [com.yolo.detector.audio.WarningSoundManager].
 */
data class DriverScene(
    val trafficLights: List<TrafficLightHud> = emptyList(),
    val signs: List<SignHud> = emptyList(),
    val activeSpeedLimit: Int? = null,
    val peopleOnRoad: Int = 0,
    val leadVehicle: LeadVehicleHud? = null,
    val pedestrianAlerts: List<PedestrianAlert> = emptyList(),
    val warnings: Set<WarningType> = emptySet(),
)