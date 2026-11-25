package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A titled run of schedule items. An empty [title] means "no header" (the timeline style). */
data class ScheduleSection(
    val key: String,
    val title: String,
    val items: List<ScheduleItem>,
)

/**
 * Pure grouping logic for the Schedule tab and the map's secondary pane.
 * All day boundaries are computed in the event's time zone.
 */
object ScheduleGrouping {

    const val GENERAL_TITLE = "General"
    const val TIME_TBA_TITLE = "Time TBA"
    const val NOW_TITLE = "Happening now"
    const val NEXT_TITLE = "Up next"
    const val LATER_TITLE = "Later"

    /** How far ahead "Up next" looks. */
    val UP_NEXT_WINDOW: Duration = Duration.ofHours(2)

    /** Assumed length of an item that has no end time. */
    val DEFAULT_ITEM_DURATION: Duration = Duration.ofHours(1)

    fun group(
        style: ScheduleStyle,
        items: List<ScheduleItem>,
        locations: List<EventLocation>,
        zone: ZoneId,
        now: Instant,
        locale: Locale = Locale.getDefault(),
    ): List<ScheduleSection> = when (style) {
        ScheduleStyle.TIMELINE -> timeline(items)
        ScheduleStyle.BY_DAY -> byDay(items, zone, locale)
        ScheduleStyle.BY_LOCATION -> byLocation(items, locations)
        ScheduleStyle.NOW_NEXT -> nowNext(items, now)
    }

    /** One chronological list; untimed items go last. */
    fun timeline(items: List<ScheduleItem>): List<ScheduleSection> {
        val sorted = sortChronologically(items)
        return if (sorted.isEmpty()) emptyList() else listOf(ScheduleSection("timeline", "", sorted))
    }

    /** One section per calendar day in [zone], in date order, then a "Time TBA" section. */
    fun byDay(items: List<ScheduleItem>, zone: ZoneId, locale: Locale = Locale.getDefault()): List<ScheduleSection> {
        val (timed, untimed) = items.partition { it.startTime != null }
        val byDate: Map<LocalDate, List<ScheduleItem>> = sortChronologically(timed)
            .groupBy { EventTime.localDate(it.startTime!!, zone) }
        val sections = byDate.keys.sorted().map { date ->
            ScheduleSection(
                key = "day-$date",
                title = EventTime.formatDayHeader(date, locale),
                items = byDate.getValue(date),
            )
        }
        return if (untimed.isEmpty()) sections else sections + ScheduleSection("day-tba", TIME_TBA_TITLE, untimed)
    }

    /**
     * One section per location, in the organizer's location order. Items whose location is not in
     * [locations] are grouped by their `location_name`; items with no location go under "General".
     */
    fun byLocation(items: List<ScheduleItem>, locations: List<EventLocation>): List<ScheduleSection> {
        val sorted = sortChronologically(items)
        val known = locations.associateBy { it.id }
        val sections = mutableListOf<ScheduleSection>()

        for (location in locations) {
            val atLocation = sorted.filter { it.locationId == location.id }
            if (atLocation.isNotEmpty()) {
                sections += ScheduleSection("loc-${location.id}", location.name, atLocation)
            }
        }

        val orphaned = sorted.filter { it.locationId != null && it.locationId !in known }
        orphaned.groupBy { it.locationName?.takeIf { name -> name.isNotBlank() } ?: GENERAL_TITLE }
            .forEach { (name, group) ->
                if (name != GENERAL_TITLE) sections += ScheduleSection("loc-name-$name", name, group)
            }

        val general = sorted.filter { item ->
            item.locationId == null ||
                (item.locationId !in known && item.locationName.isNullOrBlank())
        }
        if (general.isNotEmpty()) sections += ScheduleSection("loc-general", GENERAL_TITLE, general)
        return sections
    }

    /**
     * "Happening now" (started and not yet ended), "Up next" (starting within the next two hours)
     * and "Later". Ended items are left out; untimed items are listed under "Later".
     */
    fun nowNext(items: List<ScheduleItem>, now: Instant): List<ScheduleSection> {
        val horizon = now.plus(UP_NEXT_WINDOW)
        val current = mutableListOf<ScheduleItem>()
        val next = mutableListOf<ScheduleItem>()
        val later = mutableListOf<ScheduleItem>()
        for (item in sortChronologically(items)) {
            val start = item.startTime?.toInstant()
            when {
                start == null -> later += item
                !start.isAfter(now) -> if (effectiveEnd(item)!!.isAfter(now)) current += item
                !start.isAfter(horizon) -> next += item
                else -> later += item
            }
        }
        return buildList {
            if (current.isNotEmpty()) add(ScheduleSection("now", NOW_TITLE, current))
            if (next.isNotEmpty()) add(ScheduleSection("next", NEXT_TITLE, next))
            if (later.isNotEmpty()) add(ScheduleSection("later", LATER_TITLE, later))
        }
    }

    /** True once the item's end (or start + one hour when it has no end) has passed. */
    fun hasEnded(item: ScheduleItem, now: Instant): Boolean {
        val end = effectiveEnd(item) ?: return false
        return !end.isAfter(now)
    }

    fun isHappeningNow(item: ScheduleItem, now: Instant): Boolean {
        val start = item.startTime?.toInstant() ?: return false
        return !start.isAfter(now) && !hasEnded(item, now)
    }

    /** Case-insensitive search over name, description, location name and tags. */
    fun filter(items: List<ScheduleItem>, query: String): List<ScheduleItem> {
        val needle = query.trim()
        if (needle.isEmpty()) return items
        return items.filter { item ->
            item.name.contains(needle, ignoreCase = true) ||
                item.description.contains(needle, ignoreCase = true) ||
                (item.locationName?.contains(needle, ignoreCase = true) == true) ||
                item.tags.any { it.contains(needle, ignoreCase = true) }
        }
    }

    fun sortChronologically(items: List<ScheduleItem>): List<ScheduleItem> =
        items.sortedWith(
            compareBy<ScheduleItem>({ it.startTime == null }, { it.startTime?.toInstant() }, { it.id })
        )

    private fun effectiveEnd(item: ScheduleItem): Instant? {
        val start = item.startTime?.toInstant() ?: return null
        val end = item.endTime?.toInstant()
        return if (end != null && end.isAfter(start)) end else start.plus(DEFAULT_ITEM_DURATION)
    }
}
