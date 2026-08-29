package com.yolo.detector.tracking

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

class HungarianMatcherTest {

    @Test
    fun testEmptyInputs() {
        val res = HungarianMatcher.match(emptyArray(), 0.5f)
        assertEquals(0, res.size)
    }

    @Test
    fun testIdentityMatching() {
        // Diagonal cost 0 (perfect match), off-diagonal 1 (no match)
        val costMatrix = arrayOf(
            floatArrayOf(0f, 1f),
            floatArrayOf(1f, 0f)
        )
        val res = HungarianMatcher.match(costMatrix, 0.5f)
        assertEquals(2, res.size)
        assertEquals(0, res[0])
        assertEquals(1, res[1])
    }

    @Test
    fun testThresholdRejection() {
        // High costs above threshold should result in -1 (unmatched)
        val costMatrix = arrayOf(
            floatArrayOf(0.9f, 0.8f)
        )
        val res = HungarianMatcher.match(costMatrix, 0.5f)
        assertEquals(1, res.size)
        assertEquals(-1, res[0])
    }

    @Test
    fun testIouCostMatrix() {
        val boxA = listOf(RectF(0f, 0f, 0.5f, 0.5f))
        val boxB = listOf(RectF(0f, 0f, 0.5f, 0.5f), RectF(0.6f, 0.6f, 1.0f, 1.0f))

        val cost = HungarianMatcher.iouCostMatrix(boxA, boxB)
        assertEquals(1, cost.size)
        assertEquals(2, cost[0].size)
        assertEquals(0f, cost[0][0], 1e-4f) // identical box -> IoU=1 -> cost=0
        assertEquals(1f, cost[0][1], 1e-4f) // non-overlapping -> IoU=0 -> cost=1
    }
}
