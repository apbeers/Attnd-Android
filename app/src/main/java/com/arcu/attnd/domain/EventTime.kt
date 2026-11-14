package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parsing and formatting of API timestamps. Every user-visible time is rendered in the
 * event's own time zone (`event.timezone`), never the device's.
 */
object EventTime {

    /** Parses an ISO 8601 timestamp with a UTC offset (or zone id). Returns null on anything else. */
    fun parse(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toOffsetDateTime()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Resolves an IANA zone name, falling back to the device zone when it is missing or invalid. */
    fun zoneOf(timezone: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId {
        if (timezone.isNullOrBlank()) return fallback
        return try {
            ZoneId.of(timezone)
        } catch (_: Exception) {
            fallback
        }
    }

    fun localDate(time: OffsetDateTime, zone: ZoneId): LocalDate = time.atZoneSameInstant(zone).toLocalDate()

    /** "6:30 PM" */
    fun formatTime(time: OffsetDateTime?, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        time ?: return "Time TBA"
        return time.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("h:mm a", locale))
    }

    /** "6:30 – 8:00 PM" style range; just the start time when there is no end. */
    fun formatTimeRange(
        start: OffsetDateTime?,
        end: OffsetDateTime?,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String {
        start ?: return "Time TBA"
        val startText = formatTime(start, zone, locale)
        if (end == null || !end.isAfter(start)) return startText
        val sameDay = localDate(start, zone) == localDate(end, zone)
        val endText = if (sameDay) formatTime(end, zone, locale) else formatDayTime(end, zone, locale)
        return "$startText – $endText"
    }

    /** "Sat 6:30 PM" */
    fun formatDayTime(time: OffsetDateTime?, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        time ?: return "Time TBA"
        return time.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEE h:mm a", locale))
    }

    /** "Saturday, November 14" */
    fun formatDayHeader(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", locale))

    /** "Sat, Nov 14 · 6:30 PM" */
    fun formatDateTime(time: OffsetDateTime?, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        time ?: return ""
        return time.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", locale))
    }

    /** Short zone label for headers, e.g. "CST". */
    fun zoneLabel(zone: ZoneId, at: Instant = Instant.now(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("zzz", locale).format(at.atZone(zone))

    /** "Starts in 1h 20m" for items starting within the next day; null otherwise. */
    fun startsInText(start: OffsetDateTime?, now: Instant): String? {
        start ?: return null
        val seconds = Duration.between(now, start.toInstant()).seconds
        if (seconds <= 0 || seconds > Duration.ofDays(1).seconds) return null
        val totalMinutes = seconds / 60
        if (totalMinutes < 1) return "Starting now"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "Starts in ${minutes}m"
            minutes == 0L -> "Starts in ${hours}h"
            else -> "Starts in ${hours}h ${minutes}m"
        }
    }

    /**
     * True when "now" falls on an event day in the event's zone: between the start date and the
     * end date (or the start date when there is no end), inclusive. Unknown dates count as event days.
     */
    fun isEventDay(event: Event, now: Instant): Boolean {
        val start = event.startTime ?: return true
        val zone = event.zone
        val today = now.atZone(zone).toLocalDate()
        val first = localDate(start, zone)
        val last = event.endTime?.let { localDate(it, zone) }?.takeIf { !it.isBefore(first) } ?: first
        return !today.isBefore(first) && !today.isAfter(last)
    }
}
