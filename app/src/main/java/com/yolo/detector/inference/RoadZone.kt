package com.yolo.detector.inference

/**
 * Road-zone perspective geometry helpers used by Driver Mode for ego-lane corridor
 * identification, forward collision detection, and pedestrian hazard assessment.
 *
 * Models a forward-facing perspective trapezoid where the vehicle's driving lane
 * narrows toward the vanishing horizon and widens towards the vehicle hood.
 */
object RoadZone {

    /** Default vertical fraction of the frame treated as road zone. */
    const val ROAD_ZONE_FRACTION = 0.6f

    /** Horizon level where the road reaches vanishing point (normalized 0..1). */
    const val HORIZON_Y = 0.45f

    /** Left boundary of ego-corridor at horizon. */
    const val HORIZON_LEFT_X = 0.38f

    /** Right boundary of ego-corridor at horizon. */
    const val HORIZON_RIGHT_X = 0.62f

    /** Left boundary of ego-corridor at vehicle hood (bottom Y = 1.0). */
    const val HOOD_LEFT_X = 0.16f

    /** Right boundary of ego-corridor at vehicle hood (bottom Y = 1.0). */
    const val HOOD_RIGHT_X = 0.84f

    /** Returns the interpolated left boundary X of the ego-corridor at vertical position [y]. */
    fun getEgoCorridorLeft(y: Float): Float {
        if (y <= HORIZON_Y) return HORIZON_LEFT_X
        val t = ((y - HORIZON_Y) / (1.0f - HORIZON_Y)).coerceIn(0f, 1f)
        return HORIZON_LEFT_X + t * (HOOD_LEFT_X - HORIZON_LEFT_X)
    }

    /** Returns the interpolated right boundary X of the ego-corridor at vertical position [y]. */
    fun getEgoCorridorRight(y: Float): Float {
        if (y <= HORIZON_Y) return HORIZON_RIGHT_X
        val t = ((y - HORIZON_Y) / (1.0f - HORIZON_Y)).coerceIn(0f, 1f)
        return HORIZON_RIGHT_X + t * (HOOD_RIGHT_X - HORIZON_RIGHT_X)
    }

    /** Returns true if point ([x], [y]) is inside the perspective ego-lane driving corridor. */
    fun isInEgoCorridor(x: Float, y: Float): Boolean {
        if (y < HORIZON_Y || y > 1.0f) return false
        val leftX = getEgoCorridorLeft(y)
        val rightX = getEgoCorridorRight(y)
        return x in leftX..rightX
    }

    /**
     * Checks whether a bounding box intersects the ego-corridor.
     * Uses the bottom contact point (ground contact) for vehicles and pedestrians.
     */
    fun isBoxInEgoCorridor(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (bottom < HORIZON_Y) return false
        val centerX = (left + right) / 2f
        val bottomY = bottom.coerceIn(HORIZON_Y, 1.0f)
        val leftX = getEgoCorridorLeft(bottomY)
        val rightX = getEgoCorridorRight(bottomY)

        // Check if center is within corridor or if box overlaps horizontally
        val centerIn = centerX in leftX..rightX
        val overlapIn = right >= leftX && left <= rightX
        return centerIn || (overlapIn && (right - left) > (rightX - leftX) * 0.3f)
    }

    /** True when a box spanning [top]→[bottom] overlaps the road zone vertically. */
    fun overlapsRoad(bottom: Float, top: Float, roadZoneFraction: Float = ROAD_ZONE_FRACTION): Boolean {
        val roadTop = 1f - roadZoneFraction.coerceIn(0f, 1f)
        return bottom > roadTop && top < 1f
    }
}