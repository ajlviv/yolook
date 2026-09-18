package com.yolo.detector.inference

/**
 * Pure bounding-box geometry shared by the NMS passes.
 *
 * Kept float-only (no android types) so the core math is JVM-unit-testable.
 */
object BboxMetrics {

    /** Intersection over union of two axis-aligned boxes. 0 when they don't overlap. */
    fun iou(
        leftA: Float, topA: Float, rightA: Float, bottomA: Float,
        leftB: Float, topB: Float, rightB: Float, bottomB: Float,
    ): Float {
        val interLeft = maxOf(leftA, leftB)
        val interTop = maxOf(topA, topB)
        val interRight = minOf(rightA, rightB)
        val interBottom = minOf(bottomA, bottomB)
        val w = (interRight - interLeft).coerceAtLeast(0f)
        val h = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = w * h
        val areaA = (rightA - leftA) * (bottomA - topA)
        val areaB = (rightB - leftB) * (bottomB - topB)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /**
     * Intersection over the *smaller* area.
     *
     * Used for fusing sliced detections: a seam-split duplicate is a partial box
     * that sits inside the larger full-object box. Its IoU with that box can be
     * well below the NMS threshold (the partial box is tiny), but its IOS is ~1
     * precisely because it is contained — so IOS correctly merges it.
     */
    fun ios(
        leftA: Float, topA: Float, rightA: Float, bottomA: Float,
        leftB: Float, topB: Float, rightB: Float, bottomB: Float,
    ): Float {
        val interLeft = maxOf(leftA, leftB)
        val interTop = maxOf(topA, topB)
        val interRight = minOf(rightA, rightB)
        val interBottom = minOf(bottomA, bottomB)
        val w = (interRight - interLeft).coerceAtLeast(0f)
        val h = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = w * h
        val areaA = (rightA - leftA) * (bottomA - topA)
        val areaB = (rightB - leftB) * (bottomB - topB)
        val smaller = minOf(areaA, areaB)
        return if (smaller <= 0f) 0f else intersection / smaller
    }
}