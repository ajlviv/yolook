package com.yolo.detector.data

/**
 * One row in the History screen: either a seen object or a dispatched alert.
 *
 * @param timestampMs wall-clock timestamp used for display and sorting (newest first).
 */
sealed interface HistoryItem {
    val timestampMs: Long

    /** A single seen object, aggregated by track. See [HistoryEntry]. */
    data class Detection(val entry: HistoryEntry) : HistoryItem {
        override val timestampMs: Long get() = entry.lastSeenMs
    }

    /**
     * A dispatched detection alert (e.g. email) for [classIds].
     *
     * The entry is recorded the moment the alert is confirmed — before the actual
     * email send — so the notification survives and appears immediately regardless
     * of network latency. [emailSent] is false at creation and flipped to its final
     * value once the send attempt (if any) settles.
     *
     * @param timestampMs when the alert was dispatched (wall-clock).
     * @param classIds    distinct COCO class IDs that triggered the alert.
     * @param emailSent   whether the alert email was actually sent; false when
     *                    unconfigured, in flight, or the provider send failed.
     */
    data class Alert(
        override val timestampMs: Long,
        val classIds: Set<Int>,
        var emailSent: Boolean,
    ) : HistoryItem
}

/**
 * Builds the newest-first history list from the per-track [objects] and the [alerts]
 * log, capped at [maxEntries]. Pure (no threading) so it can be unit-tested.
 */
fun mergeHistoryItems(
    objects: Collection<HistoryEntry>,
    alerts: Collection<HistoryItem.Alert>,
    maxEntries: Int,
): List<HistoryItem> = buildList {
    objects.forEach { add(HistoryItem.Detection(it)) }
    addAll(alerts)
}
    .sortedByDescending { it.timestampMs }
    .take(maxEntries)