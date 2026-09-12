package com.yolo.detector.inference

/**
 * Road-zone geometry helpers used by driver mode to decide whether an object is
 * "on the road".
 *
 * Road lanes occupy the lower portion of a forward-facing dash-cam frame. By
 * default the road zone is the bottom [ROAD_ZONE_FRACTION] of the frame; a person
 * whose bounding box overlaps that zone is treated as being on the road.
 *
 * Takes plain [bottom]/[top] coordinates rather than a `RectF` so it is trivially
 * unit-testable without Android stubs.
 */
object RoadZone {

    /** Default vertical fraction of the frame treated as the road. */
    private const val ROAD_ZONE_FRACTION = 0.6f

    /** True when a box spanning [top]→[bottom] overlaps the road zone at the bottom. */
    fun overlapsRoad(bottom: Float, top: Float, roadZoneFraction: Float = ROAD_ZONE_FRACTION): Boolean {
        val roadTop = 1f - roadZoneFraction.coerceIn(0f, 1f)
        return bottom > roadTop && top < 1f
    }
}