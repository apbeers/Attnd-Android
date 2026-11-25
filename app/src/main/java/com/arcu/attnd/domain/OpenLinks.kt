package com.arcu.attnd.domain

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A place in the app that a notification, a Live Update link or a search result opens. */
sealed interface OpenTarget {
    data class Location(val id: Int) : OpenTarget
    data class ScheduleItem(val id: Int) : OpenTarget

    /** The Map tab with this location selected (Live Update "Show on map"; its detail when the map is hidden). */
    data class MapLocation(val id: Int) : OpenTarget

    /** Schedule → My plan (the Live Update's "plan changed"). */
    data object MyPlan : OpenTarget

    /** The Notifications (Updates) tab, scrolled to [id] and briefly highlighting it when set. */
    data class Notification(val id: Int?) : OpenTarget

    /** Event info (the event header in Options). */
    data object EventInfo : OpenTarget

    /** The search screen, with [query] filled in (may be empty). */
    data class Search(val query: String) : OpenTarget
}

/** [target] for event [eventId]; null = whatever event is joined. */
data class OpenRequest(val eventId: Int?, val target: OpenTarget)

/**
 * The app's internal open links, `attnd-open://<event id or "any">/<target>`, carried as the data of
 * explicit intents to MainActivity (the Live Update's and reminders' `PendingIntent`s). No intent
 * filter declares the scheme: other apps cannot resolve it, and the data keeps PendingIntents for
 * different targets distinct.
 *
 *     attnd-open://12/location/5    attnd-open://12/schedule/3    attnd-open://12/update/9
 *     attnd-open://12/updates       attnd-open://12/event         attnd-open://any/search?q=now
 *     attnd-open://12/map/5         attnd-open://12/plan
 */
object OpenLinks {
    const val SCHEME = "attnd-open"
    private const val ANY_EVENT = "any"
    private const val PREFIX = "$SCHEME://"

    fun uri(request: OpenRequest): String {
        val host = request.eventId?.toString() ?: ANY_EVENT
        val path = when (val target = request.target) {
            is OpenTarget.Location -> "location/${target.id}"
            is OpenTarget.ScheduleItem -> "schedule/${target.id}"
            is OpenTarget.MapLocation -> "map/${target.id}"
            OpenTarget.MyPlan -> "plan"
            is OpenTarget.Notification -> target.id?.let { "update/$it" } ?: "updates"
            OpenTarget.EventInfo -> "event"
            is OpenTarget.Search -> "search?q=" + URLEncoder.encode(target.query, StandardCharsets.UTF_8)
        }
        return "$PREFIX$host/$path"
    }

    /** Null for anything that is not a well-formed open link. */
    fun parse(uri: String?): OpenRequest? {
        if (uri == null || !uri.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = uri.substring(PREFIX.length)
        val queryStart = rest.indexOf('?')
        val pathPart = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val queryPart = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
        val segments = pathPart.split('/').filter { it.isNotEmpty() }
        val host = segments.firstOrNull() ?: return null
        val eventId = if (host.equals(ANY_EVENT, ignoreCase = true)) null else host.toIntOrNull() ?: return null
        val kind = segments.getOrNull(1)?.lowercase() ?: return null
        val id = segments.getOrNull(2)?.toIntOrNull()
        val target = when (kind) {
            "location" -> id?.let { OpenTarget.Location(it) }
            "schedule" -> id?.let { OpenTarget.ScheduleItem(it) }
            "map" -> id?.let { OpenTarget.MapLocation(it) }
            "plan" -> OpenTarget.MyPlan
            "update" -> id?.let { OpenTarget.Notification(it) }
            "updates" -> OpenTarget.Notification(null)
            "event" -> OpenTarget.EventInfo
            "search" -> OpenTarget.Search(queryParameter(queryPart, "q").orEmpty())
            else -> null
        } ?: return null
        return OpenRequest(eventId, target)
    }

    private fun queryParameter(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0 || pair.substring(0, eq) != name) null
            else try {
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}
