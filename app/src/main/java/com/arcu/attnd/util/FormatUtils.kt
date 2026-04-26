package com.arcu.attnd.util

import java.util.Locale

object FormatUtils {
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
}
