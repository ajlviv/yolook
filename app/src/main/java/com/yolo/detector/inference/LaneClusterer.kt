package com.yolo.detector.inference

import com.yolo.detector.data.TrafficLightHud
import kotlin.math.abs

/**
 * Groups multiple on-screen traffic lights into horizontal "lanes" by their
 * normalized X-center. Road lanes run roughly vertically in the camera frame, so
 * lights belonging to the same lane share a similar X coordinate.
 *
 * A greedy 1-D clusterer sorts lights by X and merges neighbours whose centers are
 * within [xTolerance]. The number of clusters is capped at [maxLanes]; any lights
 * beyond the cap are folded into the nearest existing cluster.
 *
 * Pure and unit-testable.
 */
object LaneClusterer {

    /**
     * Assigns a `laneIndex` to each entry of [lights] based on [xCenters] (aligned
     * by position), returning a new list. Lane indices are ordered left → right.
     */
    fun assign(
        lights: List<TrafficLightHud>,
        xCenters: List<Float>,
        maxLanes: Int = 3,
        xTolerance: Float = 0.18f,
    ): List<TrafficLightHud> {
        if (lights.isEmpty()) return emptyList()
        require(xCenters.size == lights.size) { "xCenters must align with lights" }

        val laneOf = IntArray(lights.size) { 0 }
        val clusterCount = clusterIndices(laneOf, xCenters, maxLanes, xTolerance)

        // Lane indices should read left → right regardless of discovery order.
        val representatives = FloatArray(clusterCount) { 0f }
        val counts = IntArray(clusterCount) { 0 }
        for (i in laneOf.indices) {
            representatives[laneOf[i]] += xCenters[i]
            counts[laneOf[i]]++
        }
        for (lane in representatives.indices) {
            if (counts[lane] > 0) representatives[lane] /= counts[lane]
        }
        val sortedLanes = (0 until clusterCount).sortedBy { representatives[it] }
        val remap = IntArray(clusterCount)
        for (i in sortedLanes.indices) remap[sortedLanes[i]] = i

        return (0 until lights.size).map { i ->
            lights[i].let { TrafficLightHud(it.trackId, it.signal, remap[laneOf[i]]) }
        }
    }

    private fun clusterIndices(
        laneOf: IntArray,
        xCenters: List<Float>,
        maxLanes: Int,
        xTolerance: Float,
    ): Int {
        val n = xCenters.size
        val order = (0 until n).sortedBy { xCenters[it] }

        if (n == 0) return 0
        laneOf[order[0]] = 0
        var laneCount = 1
        var laneStartX = xCenters[order[0]]

        for (i in 1 until order.size) {
            val idx = order[i]
            val x = xCenters[idx]
            val diff = x - laneStartX
            if (diff <= xTolerance && laneCount < maxLanes) {
                laneOf[idx] = laneCount - 1
            } else if (diff > xTolerance && laneCount < maxLanes) {
                laneOf[idx] = laneCount
                laneCount++
                laneStartX = x
            } else {
                laneOf[idx] = nearestLaneIndex(x, xCenters, laneOf, laneCount)
            }
        }
        return laneCount
    }

    private fun nearestLaneIndex(x: Float, xCenters: List<Float>, laneOf: IntArray, laneCount: Int): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (lane in 0 until laneCount) {
            var sum = 0f
            var cnt = 0
            for (i in laneOf.indices) {
                if (laneOf[i] == lane) {
                    sum += xCenters[i]
                    cnt++
                }
            }
            if (cnt == 0) continue
            val repX = sum / cnt
            val d = abs(x - repX)
            if (d < bestDist) {
                bestDist = d
                best = lane
            }
        }
        return best
    }
}