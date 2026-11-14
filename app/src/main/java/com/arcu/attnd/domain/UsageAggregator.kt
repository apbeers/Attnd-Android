package com.arcu.attnd.domain

import java.time.Instant
import java.time.format.DateTimeFormatter

/** One aggregated usage count (API-005): [at] is the ISO-8601 UTC start of the 5-minute window it fell in. */
data class UsageEntry(
    val name: String,
    val detail: String?,
    val count: Int,
    val at: String,
)

/** Usage metric names and tab details sent to `POST events/<id>/usage/` (see API_V2.md). */
object UsageMetric {
    const val APP_OPEN = "app_open"
    const val TAB_VIEW = "tab_view"
    const val LOCATION_VIEW = "location_view"
    const val SCHEDULE_ITEM_VIEW = "schedule_item_view"
    const val NOTIFICATION_OPEN = "notification_open"
    const val SEARCH = "search"
    const val REFRESH = "refresh"

    /** `tab_view` detail for the Options screen (it is not a [Tab]). */
    const val TAB_OPTIONS = "options"
}

/**
 * In-memory usage counts keyed by (name, detail, UTC 5-minute window). Counts only: nothing here identifies
 * the device or the person, and callers must never pass search text as a detail.
 *
 * Thread-safe; [drain] hands out everything collected so far and starts over.
 */
class UsageAggregator {

    private data class Key(val name: String, val detail: String?, val bucket: Instant)

    private val counts = LinkedHashMap<Key, Int>()

    @Synchronized
    fun add(name: String, detail: String? = null, at: Instant, count: Int = 1) {
        if (name.isBlank() || count <= 0) return
        val key = Key(name, detail?.takeIf { it.isNotBlank() }, bucketStart(at))
        counts[key] = (counts[key] ?: 0) + count
    }

    @get:Synchronized
    val isEmpty: Boolean get() = counts.isEmpty()

    /** Total number of entries (distinct keys) waiting to be sent. */
    @get:Synchronized
    val size: Int get() = counts.size

    /** Returns every entry, oldest window first, and clears the aggregator. */
    @Synchronized
    fun drain(): List<UsageEntry> {
        val entries = counts.entries
            .sortedBy { it.key.bucket }
            .map { (key, count) -> UsageEntry(key.name, key.detail, count, formatBucket(key.bucket)) }
        counts.clear()
        return entries
    }

    companion object {
        /** Width of one aggregation window. */
        const val BUCKET_SECONDS = 5 * 60L

        /** Start of the UTC 5-minute window containing [at] (16:07:42 → 16:05:00). */
        fun bucketStart(at: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(at.epochSecond, BUCKET_SECONDS) * BUCKET_SECONDS)

        /** e.g. `2026-11-14T16:05:00Z`. */
        fun formatBucket(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(bucketStart(at))
    }
}
