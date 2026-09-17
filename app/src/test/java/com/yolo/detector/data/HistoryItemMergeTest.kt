package com.yolo.detector.data

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryItemMergeTest {

    private fun entry(trackId: Int, lastSeenMs: Long) = HistoryEntry(
        trackId = trackId,
        classId = 0,
        count = 1,
        firstSeenMs = lastSeenMs - 1000,
        lastSeenMs = lastSeenMs,
        bestConfidence = 0.9f,
        lastBbox = RectF(0f, 0f, 1f, 1f),
    )

    private fun alert(atMs: Long, emailSent: Boolean = true) =
        HistoryItem.Alert(atMs, setOf(0, 1), emailSent)

    @Test
    fun `alert rows are included in the merged history`() {
        val items = mergeHistoryItems(
            objects = listOf(entry(1, 1_000L)),
            alerts = listOf(alert(500L)),
            maxEntries = 500,
        )

        assertEquals(2, items.size)
        assertTrue(items.any { it is HistoryItem.Alert })
        assertTrue(items.any { it is HistoryItem.Detection })
    }

    @Test
    fun `merged list is sorted newest-first across types`() {
        val items = mergeHistoryItems(
            objects = listOf(entry(2, 2_000L), entry(1, 1_000L)),
            alerts = listOf(alert(3_000L), alert(500L)),
            maxEntries = 500,
        )

        assertEquals(
            items.sortedByDescending { it.timestampMs },
            items,
        )
        assertTrue(items.first() is HistoryItem.Alert)      // 3_000 ms alert
        assertTrue(items.last() is HistoryItem.Alert)       // 500 ms alert
        assertEquals(3_000L, items.first().timestampMs)
    }

    @Test
    fun `result is capped at maxEntries`() {
        val items = mergeHistoryItems(
            objects = listOf(entry(1, 1_000L), entry(2, 2_000L), entry(3, 3_000L)),
            alerts = listOf(alert(4_000L), alert(5_000L)),
            maxEntries = 3,
        )

        assertEquals(3, items.size)
        assertEquals(5_000L, items.first().timestampMs)
    }

    @Test
    fun `alert emailSent flag mutates without affecting list placement`() {
        val a = alert(1_000L, emailSent = false)
        val items = mergeHistoryItems(objects = emptyList(), alerts = listOf(a), maxEntries = 500)

        a.emailSent = true
        assertEquals(1, items.size)
        assertEquals(true, (items.first() as HistoryItem.Alert).emailSent)
    }
}