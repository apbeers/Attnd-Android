package com.arcu.attnd.domain

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The home-screen widget "Now / Next in my plan" (assistant spec §6): what it says, pure so it is
 * unit tested. Items = the plan's (minus checked-off), else today's liked items, else the event's own
 * items today ([LiveFollow.items] rules, on the event-zone date of now); now = the earliest-starting
 * item in progress; next = the first item starting after now; walk = the straight-line estimate from
 * now's location (else none) to next's ("6 min walk", "You're here" under 20 m).
 */
data class PlanWidgetContent(
    val title: String,
    val nowLine: String?,
    val nextLine: String?,
    val walkLine: String?,
    /** The item a tap opens (now, else next). */
    val focusItemId: Int?,
) {
    companion object {
        const val NO_EVENT = "Join an event in Attnd"
        const val NOTHING_LEFT = "Nothing else today"

        fun of(bundle: EventBundle?, plan: Plan?, hearts: Hearts, now: Instant, locale: Locale = Locale.getDefault()): PlanWidgetContent {
            bundle ?: return PlanWidgetContent(NO_EVENT, null, null, null, null)
            val zone = bundle.event.zone
            val today = now.atZone(zone).toLocalDate()
            val usable = plan?.takeIf { !it.isEmpty }
            val (source, items) = if (usable != null) {
                LiveSource.PLAN to usable.items.filter { it.itemId !in usable.checkedOff }.mapNotNull { bundle.scheduleItem(it.itemId) }.sortedWith(Planner.ITEM_ORDER)
            } else {
                LiveFollow.items(bundle, null, hearts, today, emptySet())
            }
            val title = when (source) {
                LiveSource.PLAN -> "My plan"
                LiveSource.HEARTS -> "Liked today"
                LiveSource.EVENT -> bundle.event.name
            }
            val current = items.filter { ScheduleGrouping.isHappeningNow(it, now) }.minWithOrNull(Planner.ITEM_ORDER)
            val next = items.firstOrNull { it.startTime?.toInstant()?.isAfter(now) == true }
            val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
            fun place(item: ScheduleItem) = bundle.location(item.locationId)?.name ?: item.locationName
            val nowLine = current?.let { item ->
                val end = TimeMatcher.effectiveEnd(item)!!
                "Now: ${item.name} · until ${end.atZone(zone).format(clock)}${place(item)?.let { " · $it" }.orEmpty()}"
            }
            val nextLine = next?.let { item ->
                val start = item.startTime!!.toInstant()
                val day = if (start.atZone(zone).toLocalDate() == today) "" else start.atZone(zone).format(DateTimeFormatter.ofPattern("EEE ", locale))
                "Next $day${start.atZone(zone).format(clock)}: ${item.name}${place(item)?.let { " · $it" }.orEmpty()}"
            } ?: if (current == null) NOTHING_LEFT else null
            val walk = if (current != null && next != null) {
                val from = bundle.location(current.locationId)?.coordinate
                val to = bundle.location(next.locationId)?.coordinate
                if (from != null && to != null && current.locationId != next.locationId) Walking.rowText(Geo.distanceMeters(from, to)) else null
            } else {
                null
            }
            return PlanWidgetContent(title, nowLine, nextLine, walk, (current ?: next)?.id)
        }
    }
}
