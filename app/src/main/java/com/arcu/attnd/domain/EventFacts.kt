package com.arcu.attnd.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * One item of the joined event as structured data for assistants (AppFunctions, see
 * `assistant/AttndAppFunctions.kt`) and for the on-device model's context. Built only from the
 * local bundle. Times are ISO 8601 with the event's UTC offset; [detail] is the result row's line.
 */
data class EventFact(
    val kind: SearchKind,
    val id: Int,
    val title: String,
    val detail: String,
    /** "now", "next" or "ended" for schedule items; null otherwise. */
    val status: String?,
    val startTime: String?,
    val endTime: String?,
    val locationName: String?,
    val description: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val target: OpenTarget,
    /** The event's website (API-016), for the event only. */
    val website: String? = null,
)

object EventFacts {

    const val DEFAULT_LIMIT = 10
    const val MAX_LIMIT = 50

    /** Clamps an assistant-supplied limit (0 or missing → [DEFAULT_LIMIT]). */
    fun limit(requested: Int): Int = if (requested <= 0) DEFAULT_LIMIT else requested.coerceAtMost(MAX_LIMIT)

    /**
     * Local search for an assistant's [query], optionally restricted to [kinds]: the query's keywords
     * ([SearchQuery.keywords], assistants often send whole questions) with the search screen's rules,
     * falling back to any-keyword matches when no item matches them all.
     */
    fun search(
        bundle: EventBundle,
        query: String,
        now: Instant,
        kinds: Set<SearchKind>? = null,
        limit: Int = DEFAULT_LIMIT,
        locale: Locale = Locale.getDefault(),
    ): List<EventFact> {
        val keywords = SearchQuery.keywords(query)
        fun run(mode: SearchMode) = EventSearch.search(bundle, keywords, now, mode).filter { kinds == null || it.kind in kinds }
        return run(SearchMode.ALL_TOKENS).ifEmpty { run(SearchMode.ANY_TOKEN) }
            .take(limit)
            .mapNotNull { of(it, bundle, now, locale) }
    }

    /** Schedule items happening now, then those starting within the next two hours. */
    fun happeningNow(bundle: EventBundle, now: Instant, limit: Int = DEFAULT_LIMIT, locale: Locale = Locale.getDefault()): List<EventFact> =
        ScheduleGrouping.sortChronologically(bundle.schedule)
            .mapNotNull { item -> EventSearch.timing(item, now)?.let { item to it } }
            .filter { (_, timing) -> timing != ItemTiming.ENDED }
            .sortedBy { (_, timing) -> timing.ordinal }
            .take(limit)
            .map { (item, _) -> schedule(item, bundle, now, locale) }

    /** Hearted schedule items (in time order) then hearted locations (in the organizer's order). */
    fun hearted(bundle: EventBundle, hearts: Hearts, now: Instant, locale: Locale = Locale.getDefault()): List<EventFact> =
        EventSearch.hearted(bundle, hearts, now).mapNotNull { of(it, bundle, now, locale) }

    /** Organizer updates, newest first. */
    fun latestUpdates(bundle: EventBundle, limit: Int = DEFAULT_LIMIT, locale: Locale = Locale.getDefault()): List<EventFact> =
        bundle.notifications
            .sortedWith(compareByDescending<EventNotification> { it.sendTime?.toInstant() ?: Instant.MIN }.thenByDescending { it.id })
            .take(limit)
            .map { notification(it, bundle, locale) }

    fun eventInfo(bundle: EventBundle, locale: Locale = Locale.getDefault()): EventFact {
        val event = bundle.event
        val zone = event.zone
        return EventFact(
            kind = SearchKind.EVENT,
            id = event.id,
            title = event.name,
            detail = EventSearch.eventDetail(event, locale),
            status = if (event.isArchived) "ended" else null,
            startTime = iso(event.startTime, zone),
            endTime = iso(event.endTime, zone),
            locationName = null,
            description = event.description,
            address = event.address.takeIf { it.isNotBlank() },
            latitude = event.coordinate?.latitude,
            longitude = event.coordinate?.longitude,
            target = OpenTarget.EventInfo,
            website = event.websiteUrl,
        )
    }

    fun of(result: SearchResult, bundle: EventBundle, now: Instant, locale: Locale = Locale.getDefault()): EventFact? =
        when (result.kind) {
            SearchKind.SCHEDULE -> bundle.scheduleItem(result.id)?.let { schedule(it, bundle, now, locale) }
            SearchKind.LOCATION -> bundle.location(result.id)?.let { location(it, bundle, locale) }
            SearchKind.NOTIFICATION -> bundle.notifications.firstOrNull { it.id == result.id }?.let { notification(it, bundle, locale) }
            SearchKind.EVENT -> eventInfo(bundle, locale)
        }

    fun schedule(item: ScheduleItem, bundle: EventBundle, now: Instant, locale: Locale = Locale.getDefault()): EventFact {
        val zone = bundle.event.zone
        val location = bundle.location(item.locationId)
        return EventFact(
            kind = SearchKind.SCHEDULE,
            id = item.id,
            title = item.name,
            detail = EventSearch.scheduleDetail(item, bundle, locale),
            status = EventSearch.timing(item, now)?.name?.lowercase(Locale.ROOT),
            startTime = iso(item.startTime, zone),
            endTime = iso(item.endTime, zone),
            locationName = item.locationName?.takeIf { it.isNotBlank() } ?: location?.name ?: ScheduleGrouping.GENERAL_TITLE,
            description = item.description,
            address = location?.address?.takeIf { it.isNotBlank() },
            latitude = location?.coordinate?.latitude,
            longitude = location?.coordinate?.longitude,
            target = OpenTarget.ScheduleItem(item.id),
        )
    }

    fun location(location: EventLocation, bundle: EventBundle, locale: Locale = Locale.getDefault()): EventFact {
        val result = SearchResult(SearchKind.LOCATION, location.id, location.name, emptyList(), 0.0)
        return EventFact(
            kind = SearchKind.LOCATION,
            id = location.id,
            title = location.name,
            detail = EventSearch.detail(result, bundle, locale),
            status = null,
            startTime = null,
            endTime = null,
            locationName = location.name,
            description = location.description,
            address = location.address.takeIf { it.isNotBlank() },
            latitude = location.coordinate?.latitude,
            longitude = location.coordinate?.longitude,
            target = OpenTarget.Location(location.id),
        )
    }

    fun notification(notification: EventNotification, bundle: EventBundle, locale: Locale = Locale.getDefault()): EventFact {
        val result = SearchResult(SearchKind.NOTIFICATION, notification.id, notification.title, emptyList(), 0.0)
        return EventFact(
            kind = SearchKind.NOTIFICATION,
            id = notification.id,
            title = notification.title,
            detail = EventSearch.detail(result, bundle, locale),
            status = null,
            startTime = iso(notification.sendTime, bundle.event.zone),
            endTime = null,
            locationName = null,
            description = notification.body,
            address = null,
            latitude = null,
            longitude = null,
            target = OpenTarget.Notification(notification.id),
        )
    }

    /** ISO 8601 with the event zone's offset, e.g. `2026-11-14T19:00-06:00`. */
    fun iso(time: OffsetDateTime?, zone: ZoneId): String? = time?.atZoneSameInstant(zone)?.toOffsetDateTime()?.toString()
}
