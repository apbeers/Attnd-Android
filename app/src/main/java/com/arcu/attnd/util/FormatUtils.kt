package com.arcu.attnd.util

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
            else -> "${Math.round(miles)} miles"
        }
    }

    fun formatHoursRemaining(hours: Double): String {
        return when {
            hours < 0 -> "Ended"
            hours < 1 -> "Starting soon"
            hours < 24 -> "${Math.round(hours)}h left"
            else -> "${Math.round(hours / 24)}d left"
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
     * Returns the minutes elapsed since the given timestamp.
     */
    fun getMinutesElapsed(timestamp: Long): Long {
        val now = System.currentTimeMillis()
        return (now - timestamp) / 1000 / 60
    }
}
