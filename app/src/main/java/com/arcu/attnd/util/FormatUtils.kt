package com.arcu.attnd.util

import java.util.Locale
import kotlin.math.round

/** Formatting that does not depend on time zones. Event times live in domain/EventTime. */
object FormatUtils {

    fun formatDistance(miles: Double): String = when {
        miles < 0.1 -> "Very close"
        miles < 1.0 -> String.format(Locale.US, "%.1f miles", miles)
        else -> "${round(miles).toLong()} miles"
    }

    fun formatHoursRemaining(hours: Double): String = when {
        hours < 0 -> "Ended"
        hours < 1 -> "Starting soon"
        hours < 24 -> "${round(hours).toLong()}h left"
        else -> "${round(hours / 24).toLong()}d left"
    }
}
