package com.yolo.detector.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BboxMetricsTest {

    @Test
    fun iou_overlappingBoxes() {
        // [0,0]-[10,10] vs [5,0]-[15,10]: overlap 5×10 = 50, union = 100+100−50 = 150.
        val value = BboxMetrics.iou(0f, 0f, 10f, 10f, 5f, 0f, 15f, 10f)
        assertEquals(1f / 3f, value, 1e-5f)
    }

    @Test
    fun iou_noOverlap_isZero() {
        assertEquals(0f, BboxMetrics.iou(0f, 0f, 5f, 5f, 10f, 10f, 15f, 15f), 1e-5f)
    }

    @Test
    fun ios_containedPartialBox_isOneEvenWhenIoUIsLow() {
        // The seam-duplicate case: a narrow strip (area 2 000) fully contained in
        // the full-object box (area 10 000). The strip overlaps 20×100 = 2 000 px.
        //   IoU  = 2000 / (10 000 + 2000 − 2000) = 0.20  → below 0.45 threshold
        //   IOS  = 2000 / min(10 000, 2000) = 1           → suppressed by fusion
        val full = BboxMetrics.iou(0f, 0f, 100f, 100f, 40f, 0f, 60f, 100f)
        val partial = BboxMetrics.ios(0f, 0f, 100f, 100f, 40f, 0f, 60f, 100f)

        assertEquals(0.2f, full, 1e-5f)
        assertEquals(1f, partial, 1e-5f)
    }

    @Test
    fun ios_twoHalfOverlappingBoxes_isPartialOverlapOverMinArea() {
        // [0,0]-[10,10] vs [5,5]-[15,15]: overlap 5×5 = 25, areas 100 each.
        //   IoU = 25/175 ≈ 0.143
        //   IOS = 25/100  = 0.25
        val overlapArea = 5f * 5f
        val areaEach = 10f * 10f
        assertEquals(overlapArea / (2 * areaEach - overlapArea), BboxMetrics.iou(0f, 0f, 10f, 10f, 5f, 5f, 15f, 15f), 1e-5f)
        assertEquals(overlapArea / areaEach, BboxMetrics.ios(0f, 0f, 10f, 10f, 5f, 5f, 15f, 15f), 1e-5f)
    }

    @Test
    fun ios_fullyContainedSmallBoxIsOne() {
        // Half-area box sharing the top-left corner: fully contained, IOS=1.
        assertEquals(
            1f,
            BboxMetrics.ios(0f, 0f, 20f, 20f, 0f, 0f, 10f, 20f),
            1e-5f,
        )
    }

    @Test
    fun ios_degenerateBox_isZero() {
        assertEquals(0f, BboxMetrics.ios(0f, 0f, 0f, 0f, 5f, 5f, 15f, 15f), 1e-5f)
        assertTrue(BboxMetrics.ios(0f, 0f, 10f, 10f, 5f, 5f, 15f, 15f) > 0f)
    }

    @Test
    fun ios_alwaysAtLeastAsLargeAsIoU() {
        // For any two boxes: union ≥ min(areaA, areaB) → IOS ≥ IoU.
        val boxes = listOf(
            floatArrayOf(0f, 0f, 10f, 10f),
            floatArrayOf(3f, 3f, 7f, 7f),
            floatArrayOf(-5f, 2f, 8f, 12f),
            floatArrayOf(0f, 0f, 1f, 1f),
        )
        for (a in boxes) {
            for (b in boxes) {
                val iou = BboxMetrics.iou(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])
                val ios = BboxMetrics.ios(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])
                assertTrue(
                    "IOS ($ios) must be >= IoU ($iou) for boxes ${a.toList()} vs ${b.toList()}",
                    ios + 1e-6f >= iou,
                )
            }
        }
    }
}