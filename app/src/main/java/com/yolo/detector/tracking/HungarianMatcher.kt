package com.yolo.detector.tracking

import android.graphics.RectF

/**
 * O(n³) Hungarian algorithm for minimum-cost bipartite matching.
 *
 * Operates on a cost matrix where cost[i][j] is the cost of assigning
 * detection i to track j. Lower cost = better match.
 *
 * Returns an [IntArray] `assignment` where `assignment[i] = j` means
 * detection i is matched to track j, or `assignment[i] = -1` if unmatched.
 *
 * For ByteTrack, cost = 1 − IoU (higher IoU → lower cost).
 */
object HungarianMatcher {

    private const val INF = Float.MAX_VALUE / 2

    /**
     * Runs the Hungarian algorithm on a [costMatrix] of shape [numDetections × numTracks].
     *
     * @param costMatrix Row = detection, Column = track. Values in [0, 1].
     * @param threshold  Maximum cost to allow a match (detections exceeding this are unmatched).
     * @return [IntArray] of length [numDetections]. Entry i = matched track index, or -1.
     */
    fun match(costMatrix: Array<FloatArray>, threshold: Float): IntArray {
        val numRows = costMatrix.size
        if (numRows == 0) return IntArray(0)
        val numCols = costMatrix[0].size
        if (numCols == 0) return IntArray(numRows) { -1 }

        // Pad to square
        val n = maxOf(numRows, numCols)
        val cost = Array(n) { i -> FloatArray(n) { j -> costMatrix.getOrNull(i)?.getOrElse(j) { INF } ?: INF } }

        // u[i] = potential for row i, v[j] = potential for column j
        val u = FloatArray(n + 1)
        val v = FloatArray(n + 1)
        val p = IntArray(n + 1)   // p[j] = row matched to column j (1-indexed)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minDist = FloatArray(n + 1) { INF }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = INF
                var j1 = -1
                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minDist[j]) {
                            minDist[j] = cur
                            way[j] = j0
                        }
                        if (minDist[j] < delta) {
                            delta = minDist[j]
                            j1 = j
                        }
                    }
                }
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minDist[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                p[j0] = p[way[j0]]
                j0 = way[j0]
            } while (j0 != 0)
        }

        // Extract assignment for original rows only
        val assignment = IntArray(numRows) { -1 }
        for (j in 1..numCols) {
            val row = p[j] - 1
            if (row < numRows) {
                val col = j - 1
                if (costMatrix[row][col] <= threshold) {
                    assignment[row] = col
                }
            }
        }
        return assignment
    }

    /**
     * Computes a cost matrix where cost[i][j] = 1 − IoU(detection[i], track[j]).
     *
     * @param detectionBoxes Normalised bounding boxes [left, top, right, bottom] for each detection.
     * @param trackBoxes     Normalised bounding boxes for each track (from Kalman state).
     */
    fun iouCostMatrix(detectionBoxes: List<RectF>, trackBoxes: List<RectF>): Array<FloatArray> {
        return Array(detectionBoxes.size) { i ->
            FloatArray(trackBoxes.size) { j ->
                1f - iou(detectionBoxes[i], trackBoxes[j])
            }
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val iw = (interRight - interLeft).coerceAtLeast(0f)
        val ih = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = iw * ih

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection

        return if (union <= 0f) 0f else intersection / union
    }
}
