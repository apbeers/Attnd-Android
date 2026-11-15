package com.arcu.attnd.domain

import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Domain models used by the UI. They are produced from the v2 API DTOs by
 * [com.arcu.attnd.data.api.ApiMapper], which fills in a default for every
 * missing key, so nothing here is nullable unless the API says it can be null.
 *
 * Pure Kotlin (no Android types) so that they can be unit tested on the JVM.
 */

/** A latitude/longitude pair that is known to be a real location (never null, never 0,0). */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    companion object {
        /**
         * Returns a point only when both coordinates are present, finite, in range and not
         * the 0,0 "null island" placeholder. Callers must skip pins when this returns null.
         */
        fun of(latitude: Double?, longitude: Double?): GeoPoint? {
            if (latitude == null || longitude == null) return null
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            if (latitude == 0.0 && longitude == 0.0) return null
            return GeoPoint(latitude, longitude)
        }
    }
}

enum class EventStatus { PUBLISHED, ARCHIVED, UNKNOWN }

data class Event(
    val id: Int,
    val name: String,
    val eventCode: String,
    val description: String,
    val address: String,
    val coordinate: GeoPoint?,
    val startTime: OffsetDateTime?,
    val endTime: OffsetDateTime?,
    /** IANA time zone name as sent by the server, e.g. "America/Chicago". */
    val timezone: String?,
    val status: EventStatus,
    val paid: Boolean,
    val isDemo: Boolean,
    val flyerUrl: String?,
    val accentColor: String?,
    val logoUrl: String?,
    val contentVersion: Int,
    val joinUrl: String?,
) {
    /** The zone every time in this event is displayed in. */
    val zone: ZoneId get() = EventTime.zoneOf(timezone)
    val isArchived: Boolean get() = status == EventStatus.ARCHIVED
}

data class NearbyEvent(
    val event: Event,
    val distanceMiles: Double?,
    val startsInHours: Double?,
)

data class EventLocation(
    val id: Int,
    val name: String,
    val snippet: String,
    val description: String,
    val category: String?,
    val website: String?,
    val imageUrl: String?,
    val address: String,
    val coordinate: GeoPoint?,
    val sortOrder: Int,
    /** API-010: lower-case labels ("live music", "kid-friendly"); filter and search as sent, see [Tags.display]. */
    val tags: List<String> = emptyList(),
)

data class ScheduleItem(
    val id: Int,
    val name: String,
    val description: String,
    val startTime: OffsetDateTime?,
    val endTime: OffsetDateTime?,
    val locationId: Int?,
    val locationName: String?,
    val tags: List<String>,
)

data class EventNotification(
    val id: Int,
    val title: String,
    val body: String,
    val sendTime: OffsetDateTime?,
    /** API-011: urgent announcements (cancellations, safety) are highlighted. */
    val priority: NotificationPriority = NotificationPriority.NORMAL,
) {
    val isUrgent: Boolean get() = priority == NotificationPriority.URGENT
}

enum class NotificationPriority { NORMAL, URGENT }

/** Tags arrive lower case (API-010): kept as sent for filtering and search, capitalised only for display. */
object Tags {
    /** "live music" → "Live music". */
    fun display(tag: String): String = tag.trim().replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
}

/** Everything the app shows for one event, from `GET events/<id>/bundle/`. */
data class EventBundle(
    val schemaVersion: Int,
    val contentVersion: Int,
    val updatedAt: OffsetDateTime?,
    val event: Event,
    val display: DisplayConfig,
    val categories: List<String>,
    val locations: List<EventLocation>,
    val schedule: List<ScheduleItem>,
    val notifications: List<EventNotification>,
) {
    fun location(id: Int?): EventLocation? = id?.let { wanted -> locations.firstOrNull { it.id == wanted } }
    fun scheduleItem(id: Int?): ScheduleItem? = id?.let { wanted -> schedule.firstOrNull { it.id == wanted } }
    fun scheduleAt(locationId: Int): List<ScheduleItem> = schedule.filter { it.locationId == locationId }
}
