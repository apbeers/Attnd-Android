package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "When does it start?" as short relative text (shared spec with iOS; fixes "in thousands of hours").
 * Calendar days are counted in the EVENT's time zone; clock times are shown in it too.
 *
 * - A missing end, or one not after the start, means start + 1 h. Ending exactly now is ended.
 * - ended: "Ended"
 * - started: "Started just now" (< 1 min), "Started 20 min ago" (< 1 h), then "Now"; with [showEnd]
 *   these add " · ends 8:30 PM" ("· ends Sun 1:00 AM" when the end is on another day)
 * - future: "Starting now" (< 1 min), "in 12 min" (< 1 h), "in 3 h 20 min" / "in 3 h" (< 6 h),
 *   "today 7:30 PM", "tomorrow 7:30 PM", "in 3 days" (< 7 calendar days), "in 1 week" / "in 2 weeks"
 *   (days / 7, < 5 weeks), "in 4 months" (calendar months, < 12), "in 1 year" / "in 2 years". Never
 *   hours ≥ 6. Minutes always round down.
 */
object RelativeTime {

    const val ENDED = "Ended"
    const val NOW = "Now"
    const val STARTING_NOW = "Starting now"
    const val STARTED_JUST_NOW = "Started just now"

    private val HOURS_LIMIT: Duration = Duration.ofHours(6)
    private val DEFAULT_LENGTH: Duration = Duration.ofHours(1)

    fun relativeStart(
        start: Instant,
        end: Instant?,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
        showEnd: Boolean = false,
    ): String {
        val effectiveEnd = end?.takeIf { it.isAfter(start) } ?: start.plus(DEFAULT_LENGTH)
        if (!effectiveEnd.isAfter(now)) return ENDED
        if (!start.isAfter(now)) {
            val minutes = Duration.between(start, now).toMinutes()
            val started = when {
                minutes < 1 -> STARTED_JUST_NOW
                minutes < 60 -> "Started $minutes min ago"
                else -> NOW
            }
            return if (showEnd) "$started · ends ${endTime(start, effectiveEnd, zone, locale)}" else started
        }
        val until = Duration.between(now, start)
        val minutes = until.toMinutes()
        if (minutes < 1) return STARTING_NOW
        if (minutes < 60) return "in $minutes min"
        if (until < HOURS_LIMIT) {
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest == 0L) "in $hours h" else "in $hours h $rest min"
        }
        val today = now.atZone(zone).toLocalDate()
        val day = start.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, day)
        return when {
            days <= 0L -> "today ${clock(start, zone, locale)}"
            days == 1L -> "tomorrow ${clock(start, zone, locale)}"
            days < 7 -> "in $days days"
            days < 35 -> plural(days / 7, "week")
            else -> {
                val months = ChronoUnit.MONTHS.between(today, day).coerceAtLeast(1)
                if (months < 12) plural(months, "month")
                else plural(ChronoUnit.YEARS.between(today, day).coerceAtLeast(1), "year")
            }
        }
    }

    /** A schedule item; null without a start time. */
    fun forItem(
        item: ScheduleItem,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
        showEnd: Boolean = false,
    ): String? {
        val start = item.startTime?.toInstant() ?: return null
        return relativeStart(start, item.endTime?.toInstant(), now, zone, locale, showEnd)
    }

    /**
     * A nearby event on the launch screen: its own start when known, otherwise the server's
     * `starts_in_hours` turned into a date (never printed as hours). Without an end it lasts until the
     * end of its first day (event time zone).
     */
    fun forNearby(nearby: NearbyEvent, now: Instant, locale: Locale = Locale.getDefault()): String? {
        val event = nearby.event
        val zone = event.zone
        val start = event.startTime?.toInstant()
            ?: nearby.startsInHours?.takeIf { it.isFinite() }?.let { now.plusSeconds((it * 3600).toLong()) }
            ?: return null
        val end = event.endTime?.toInstant()?.takeIf { it.isAfter(start) }
            ?: start.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return relativeStart(start, end, now, zone, locale)
    }

    private fun plural(count: Long, unit: String): String = "in $count $unit" + if (count == 1L) "" else "s"

    private fun clock(time: Instant, zone: ZoneId, locale: Locale): String =
        time.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", locale))

    /** "8:30 PM", or "Sun 1:00 AM" when the end is on another day than the start. */
    private fun endTime(start: Instant, end: Instant, zone: ZoneId, locale: Locale): String =
        if (start.atZone(zone).toLocalDate() == end.atZone(zone).toLocalDate()) clock(end, zone, locale)
        else end.atZone(zone).format(DateTimeFormatter.ofPattern("EEE h:mm a", locale))
}
