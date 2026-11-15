package com.arcu.attnd.domain

import java.time.Instant

/**
 * One calendar event to insert (Android `CalendarContract` insert intent, iOS EventKit), in the
 * event's time zone, with the location name (and address) as the place.
 */
data class CalendarEntry(
    val itemId: Int,
    val title: String,
    val start: Instant,
    val end: Instant,
    /** IANA zone of the event, e.g. "America/Chicago". */
    val timeZone: String,
    /** "Main Stage · Panther Island, Fort Worth, TX"; the event's address for items without a location. */
    val location: String,
    /** The item's description, then the event's name. */
    val description: String,
)

/**
 * `add_to_calendar(items | plan)` (assistant spec §7). End = the item's end, or start + 1 h
 * ([TimeMatcher.effectiveEnd]); items without a start are skipped. Location = the location's name and
 * its address (else the event's address) joined by " · ", or the event's address alone.
 */
object CalendarEntries {

    fun forItem(itemId: Int, bundle: EventBundle): CalendarEntry? {
        val item = bundle.scheduleItem(itemId) ?: return null
        val start = item.startTime?.toInstant() ?: return null
        val event = bundle.event
        val location = bundle.location(item.locationId)
        val address = location?.address?.trim()?.takeIf { it.isNotEmpty() } ?: event.address.trim()
        val place = listOfNotNull(location?.name ?: item.locationName?.takeIf { it.isNotBlank() }, address.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val description = listOf(item.description.trim(), event.name).filter { it.isNotEmpty() }.joinToString("\n\n")
        return CalendarEntry(item.id, item.name, start, TimeMatcher.effectiveEnd(item)!!, event.zone.id, place, description)
    }

    fun forItems(ids: List<Int>, bundle: EventBundle): List<CalendarEntry> = ids.distinct().mapNotNull { forItem(it, bundle) }

    fun forPlan(plan: Plan, bundle: EventBundle): List<CalendarEntry> = forItems(plan.itemIds, bundle)
}
