package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something in progress at a location, for the map's "Now playing" strip. */
data class NowPlaying(val location: EventLocation, val item: ScheduleItem) {
    val label: String get() = "${location.name} · ${item.name}"
}

/**
 * Glanceable facts about the schedule (shared spec with iOS): live pins, "Next 7:30" pin subtitles,
 * the "Now playing" strip, the "Now: X · Next 8:30 Y" line on location rows and the progress of an
 * item in progress. Times are in the event's time zone.
 */
object Glance {

    /** Pins get a "Next 7:30" subtitle when something starts within this window. */
    val PIN_NEXT_WINDOW: Duration = Duration.ofHours(3)

    private const val SEPARATOR = " · "

    /** Items in progress at [locationId], the earliest-starting first ("Now" shows the first). */
    fun nowAt(bundle: EventBundle, locationId: Int, now: Instant): List<ScheduleItem> =
        bundle.scheduleAt(locationId)
            .filter { ScheduleGrouping.isHappeningNow(it, now) }
            .sortedWith(compareBy<ScheduleItem> { it.startTime?.toInstant() }.thenBy { it.id })

    /** The earliest item at [locationId] that hasn't started yet (starts after [now]), or null. */
    fun nextAt(bundle: EventBundle, locationId: Int, now: Instant): ScheduleItem? =
        bundle.scheduleAt(locationId)
            .filter { it.startTime?.toInstant()?.isAfter(now) == true }
            .minWithOrNull(compareBy<ScheduleItem> { it.startTime!!.toInstant() }.thenBy { it.id })

    fun isLive(bundle: EventBundle, locationId: Int, now: Instant): Boolean = nowAt(bundle, locationId, now).isNotEmpty()

    /** "Next 7:30" when an item at the location starts within 3 hours; otherwise null. */
    fun pinSubtitle(bundle: EventBundle, locationId: Int, now: Instant, locale: Locale = Locale.getDefault()): String? {
        val next = nextAt(bundle, locationId, now) ?: return null
        val start = next.startTime!!.toInstant()
        if (start.isAfter(now.plus(PIN_NEXT_WINDOW))) return null
        return "Next ${clock(start, bundle.event.zone, locale)}"
    }

    /**
     * The extra line on a location row: "Now: Prairie Lights · Next 8:30 Rodeo Band", "Now: X",
     * "Next 8:30 Y" ("Next Sun 8:30 Y" when it isn't today). Null when the location has no schedule
     * or nothing is on or coming up.
     */
    fun locationLine(bundle: EventBundle, locationId: Int, now: Instant, locale: Locale = Locale.getDefault()): String? {
        val current = nowAt(bundle, locationId, now).firstOrNull()
        val next = nextAt(bundle, locationId, now)
        val parts = buildList {
            if (current != null) add("Now: ${current.name}")
            if (next != null) add("Next ${nextTime(next.startTime!!.toInstant(), now, bundle.event.zone, locale)} ${next.name}")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /**
     * Items in progress at a location with coordinates (it can be selected on the map), by start time,
     * then the organizer's location order, then id, for the "Now playing" strip. Empty when nothing is on.
     */
    fun nowPlaying(bundle: EventBundle, now: Instant): List<NowPlaying> =
        bundle.schedule
            .filter { ScheduleGrouping.isHappeningNow(it, now) }
            .mapNotNull { item -> bundle.location(item.locationId)?.takeIf { it.coordinate != null }?.let { NowPlaying(it, item) } }
            .sortedWith(
                compareBy<NowPlaying>({ it.item.startTime?.toInstant() }, { it.location.sortOrder }, { it.location.id }, { it.item.id })
            )

    /** How far through an item in progress is, 0–1; null when it isn't in progress. */
    fun progress(item: ScheduleItem, now: Instant): Float? {
        if (!ScheduleGrouping.isHappeningNow(item, now)) return null
        val start = item.startTime!!.toInstant()
        val end = TimeMatcher.effectiveEnd(item) ?: return null
        val total = Duration.between(start, end).toMillis().toDouble()
        if (total <= 0) return null
        return (Duration.between(start, now).toMillis() / total).coerceIn(0.0, 1.0).toFloat()
    }

    /** "7:30" (no AM/PM) in the event's zone, as on pins. */
    fun clock(time: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        time.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm", locale))

    private fun nextTime(start: Instant, now: Instant, zone: ZoneId, locale: Locale): String =
        if (start.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) clock(start, zone, locale)
        else start.atZone(zone).format(DateTimeFormatter.ofPattern("EEE h:mm", locale))
}

/**
 * Unread organizer updates (stored per event on the device): the Updates tab's badge counts the
 * updates sent after the tab was last seen (every update with a send time before it was ever seen;
 * updates without one never count); the newest unread one is shown as a dismissible banner on the
 * other tabs for 24 hours after it was sent.
 */
object UnreadUpdates {

    val BANNER_WINDOW: Duration = Duration.ofHours(24)

    fun isUnread(notification: EventNotification, lastSeen: Instant?): Boolean {
        val sent = notification.sendTime?.toInstant() ?: return false
        return lastSeen == null || sent.isAfter(lastSeen)
    }

    fun unreadCount(notifications: List<EventNotification>, lastSeen: Instant?): Int =
        notifications.count { isUnread(it, lastSeen) }

    fun banner(
        notifications: List<EventNotification>,
        lastSeen: Instant?,
        dismissedId: Int?,
        now: Instant,
    ): EventNotification? =
        notifications
            .filter { it.sendTime != null && isUnread(it, lastSeen) }
            .maxWithOrNull(compareBy<EventNotification> { it.sendTime!!.toInstant() }.thenBy { it.id })
            ?.takeIf { it.id != dismissedId }
            ?.takeIf { !it.sendTime!!.toInstant().isBefore(now.minus(BANNER_WINDOW)) }

    /** What to store when the tab is seen: now, or the newest update's time if the device clock is behind. */
    fun seenAt(notifications: List<EventNotification>, now: Instant): Instant =
        (notifications.mapNotNull { it.sendTime?.toInstant() } + now).max()
}
