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
    /** API-016: the event's website (Attnd Studio), only an absolute `https` URL; null when there is none. */
    val websiteUrl: String? = null,
    /** API-018: about, FAQ, amenities, policies, getting there…; null when none (only `bundle/` sends it). */
    val context: EventContext? = null,
    /** API-023: the organizer's colours; null without a theme (then `accent_color`, else the brand). */
    val theme: EventTheme? = null,
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
    /**
     * API-022 / API-031: who an automatically found photo is by ("Photo: Google Maps" for a live
     * Google photo); null for the organizer's own images.
     */
    val imageCredit: String? = null,
    /** API-031: https JSON with a live Google photo's authors; null otherwise. */
    val imageAttributionUrl: String? = null,
    /** API-018: about, menu, hours, accessibility, age limit, links; null when none. */
    val context: LocationContext? = null,
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
    /** API-018: about, performers (API-030 Apple Music links), price, age limit, accessibility, links; null when none. */
    val context: ScheduleItemContext? = null,
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
    /** API-027 `live_activity`: what the Live Update follows; null = the defaults. */
    val liveActivity: LiveActivitySettings? = null,
    /** API-024 / API-027 `layout`: the server-driven screens (null = the built-in ones, see [ScreenLayout]). */
    val layout: ScreenLayouts? = null,
) {
    fun location(id: Int?): EventLocation? = id?.let { wanted -> locations.firstOrNull { it.id == wanted } }
    fun scheduleItem(id: Int?): ScheduleItem? = id?.let { wanted -> schedule.firstOrNull { it.id == wanted } }
    fun scheduleAt(locationId: Int): List<ScheduleItem> = schedule.filter { it.locationId == locationId }
}
