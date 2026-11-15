package com.arcu.attnd.ui.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.core.net.toUri
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.CalendarEntry
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel

/** The OS sheets the assistant hands off to (no permissions needed: the user confirms in the other app). */
object AssistantIntents {

    /** `add_to_calendar`: the calendar app's insert screen, prefilled (CalendarContract; no calendar permission). */
    fun addToCalendar(context: Context, entry: CalendarEntry) {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, entry.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, entry.start.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, entry.end.toEpochMilli())
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, entry.timeZone)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, entry.location)
            .putExtra(CalendarContract.Events.DESCRIPTION, entry.description)
        start(context, intent, "assistant.calendar")
    }

    /** `share_plan` / `share_item`: the system share sheet with the link. */
    fun share(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_TITLE, title)
        start(context, Intent.createChooser(send, title), "assistant.share")
    }

    /** `directions`: walking directions in Google Maps (the app itself never navigates). */
    fun directions(context: Context, point: GeoPoint) {
        val uri = "https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}&travelmode=walking".toUri()
        start(context, Intent(Intent.ACTION_VIEW, uri), "assistant.directions")
    }

    private fun start(context: Context, intent: Intent, where: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLog.caught(LogCategory.UI, where, e, LogLevel.WARNING)
        } catch (e: SecurityException) {
            AppLog.caught(LogCategory.UI, where, e, LogLevel.WARNING)
        }
    }
}
