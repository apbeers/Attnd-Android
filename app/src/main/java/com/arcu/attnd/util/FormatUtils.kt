package com.arcu.attnd.util

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale

object FormatUtils {
    private val isoFormatter = DateTimeFormatter.ISO_DATE_TIME
    private val notificationFormatter = DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.US)

    fun formatDistance(miles: Double): String {
        return when {
            miles < 0.1 -> "Very close"
            miles < 1.0 -> String.format(Locale.US, "%.1f miles", miles)
            else -> "${kotlin.math.round(miles).toLong()} miles"
        }
    }

    fun formatHoursRemaining(hours: Double): String {
        return when {
            hours < 0 -> "Ended"
            hours < 1 -> "Starting soon"
            hours < 24 -> "${kotlin.math.round(hours).toLong()}h left"
            else -> "${kotlin.math.round(hours / 24).toLong()}d left"
        }
    }

    /**
     * Formats an ISO 8601 string to "DayOfWeek Hour:Minute AM/PM" in the local timezone.
     * Example: "2026-06-15T18:00:00+00:00" -> "Monday 1:00 PM" (if CDT)
     */
    fun formatNotificationTime(isoString: String): String {
        return try {
            val zdt = ZonedDateTime.parse(isoString, isoFormatter)
            val localZdt = zdt.withZoneSameInstant(ZoneId.systemDefault())
            localZdt.format(notificationFormatter)
        } catch (_: Exception) {
            "Just now"
        }
    }

    /**
     * Formats an ISO 8601 string to "h:mm a" (today) or "EEE h:mm a" (other days)
     * in the device's local timezone.
     */
    fun formatScheduleTime(isoString: String): String {
        return try {
            val zdt = ZonedDateTime.parse(isoString, isoFormatter)
            val localZdt = zdt.withZoneSameInstant(ZoneId.systemDefault())
            val today = LocalDate.now(ZoneId.systemDefault())
            val pattern = if (localZdt.toLocalDate() == today) "h:mm a" else "EEE h:mm a"
            localZdt.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
        } catch (_: Exception) {
            isoString
        }
    }

    /**
     * Returns "Starts in Xh Ym" for future items, null if already started.
     * Items started within the last hour are still shown (grace period).
     */
    fun startsInText(isoString: String): String? {
        return try {
            val zdt = ZonedDateTime.parse(isoString, isoFormatter)
            val secondsUntil = Duration.between(ZonedDateTime.now(), zdt).seconds
            if (secondsUntil <= 0) return null
            val totalMinutes = (secondsUntil / 60).toInt()
            if (totalMinutes < 1) return "Starting now"
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            when {
                hours == 0 -> "Starts in ${minutes}m"
                minutes == 0 -> "Starts in ${hours}h"
                else -> "Starts in ${hours}h ${minutes}m"
            }
        } catch (_: Exception) { null }
    }

    /**
     * Returns true if this ISO 8601 datetime started more than [graceMinutes] ago.
     */
    fun hasEnded(isoString: String, graceMinutes: Long = 60): Boolean {
        return try {
            val zdt = ZonedDateTime.parse(isoString, isoFormatter)
            val secondsUntil = Duration.between(ZonedDateTime.now(), zdt).seconds
            secondsUntil < -(graceMinutes * 60)
        } catch (_: Exception) { false }
    }
}
