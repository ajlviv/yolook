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
    UNKNOWN,
}

/** Driver-mode warning categories, used to gate audio alerts. */
enum class WarningType {
    RED_LIGHT,
    YELLOW_LIGHT,
    SPEED_LIMIT,
    PERSON_ON_ROAD,
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
    val peopleOnRoad: Int = 0,
    val warnings: Set<WarningType> = emptySet(),
)