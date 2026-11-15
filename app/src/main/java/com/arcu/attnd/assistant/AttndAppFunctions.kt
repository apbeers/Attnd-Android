package com.arcu.attnd.assistant

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventFact
import com.arcu.attnd.domain.EventFacts
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.SearchKind
import java.time.Instant
import java.util.Locale

/** One item of the event the user has joined in Attnd. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AttndEventItem(
    /** What the item is: "schedule_item", "location", "update" (an organizer announcement) or "event". */
    val kind: String,
    /** The item's id within the event. */
    val id: Int,
    /** The item's name (schedule item, location, update title or event name). */
    val title: String,
    /** A short human-readable summary, e.g. "Sat 7:30–8:30 PM · Main Stage" or "Food · Street tacos". */
    val detail: String,
    /** For schedule items: "now" (in progress), "next" (starts within 2 hours) or "ended"; otherwise empty. */
    val status: String?,
    /** Start time (schedule items, the event) or time sent (updates), ISO 8601 with the event's UTC offset. */
    val startTime: String?,
    /** End time, ISO 8601 with the event's UTC offset, if known. */
    val endTime: String?,
    /** Where a schedule item happens ("General" when it has no place), or the location's own name. */
    val locationName: String?,
    /** Longer description or the update's full text. */
    val description: String,
    /** Street address, if known. */
    val address: String?,
    /** Latitude of the place, if known. */
    val latitude: Double?,
    /** Longitude of the place, if known. */
    val longitude: Double?,
    /** Opens this item in the Attnd app. */
    val openInApp: PendingIntent,
)

/**
 * AppFunctions (Android 16+) that let assistants such as Gemini answer questions about the event the
 * user joined in Attnd, from the copy stored on the phone (works offline; nothing is sent to Attnd's
 * servers). The KSP compiler generates `AttndAppFunctionService` and `assets/attnd_app_functions.xml`.
 *
 * All times are in the event's own time zone. Each result has an `openInApp` intent that opens that
 * item in the app.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "AttndAppFunctionService",
    appFunctionXmlFileName = "attnd_app_functions",
)
abstract class BaseAttndAppFunctionService : AppFunctionService() {

    /**
     * Searches the joined event: schedule items, locations (stages, food, booths…), organizer updates
     * and the event itself. Understands time words in the event's time zone, e.g. "music tonight",
     * "food 6pm", "saturday", "now", "up next".
     *
     * @param query What to look for, e.g. "tacos", "main stage", "jazz tonight".
     * @param limit Maximum number of results (default 10).
     * @return Matching items, best match first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchEvent(query: String, limit: Int = EventFacts.DEFAULT_LIMIT): List<AttndEventItem> {
        if (query.isBlank()) throw AppFunctionInvalidArgumentException("The query is empty.")
        val bundle = bundle()
        return EventFacts.search(bundle, query, Instant.now(), limit = EventFacts.limit(limit), locale = Locale.getDefault())
            .map { it.toItem(bundle) }
    }

    /**
     * Lists what is happening at the event right now and what starts within the next two hours.
     *
     * @return Schedule items in progress first, then upcoming ones, in time order.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getHappeningNow(): List<AttndEventItem> {
        val bundle = bundle()
        return EventFacts.happeningNow(bundle, Instant.now()).map { it.toItem(bundle) }
    }

    /**
     * Finds when something on the event's schedule happens ("when is the fireworks show?").
     *
     * @param name The name or part of the name of the performance, session or activity.
     * @return Matching schedule items with their start and end times and place.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun findScheduleItems(name: String): List<AttndEventItem> {
        if (name.isBlank()) throw AppFunctionInvalidArgumentException("The name is empty.")
        val bundle = bundle()
        return EventFacts.search(bundle, name, Instant.now(), kinds = setOf(SearchKind.SCHEDULE)).map { it.toItem(bundle) }
    }

    /**
     * Finds where a place at the event is ("where is the taco truck?"): stages, food, restrooms, booths.
     *
     * @param name The name, category or part of the name of the place.
     * @return Matching locations with their address and coordinates when known.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun findLocations(name: String): List<AttndEventItem> {
        if (name.isBlank()) throw AppFunctionInvalidArgumentException("The name is empty.")
        val bundle = bundle()
        return EventFacts.search(bundle, name, Instant.now(), kinds = setOf(SearchKind.LOCATION)).map { it.toItem(bundle) }
    }

    /**
     * Gets the latest announcements from the event's organizers, newest first.
     *
     * @param limit Maximum number of updates (default 10).
     * @return The organizer updates.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getLatestUpdates(limit: Int = EventFacts.DEFAULT_LIMIT): List<AttndEventItem> {
        val bundle = bundle()
        return EventFacts.latestUpdates(bundle, EventFacts.limit(limit)).map { it.toItem(bundle) }
    }

    /**
     * Gets the joined event's name, dates, address and description.
     *
     * @return The event.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getEventInfo(): AttndEventItem {
        val bundle = bundle()
        return EventFacts.eventInfo(bundle).toItem(bundle)
    }

    /** The joined event's bundle from disk (the app may not be running). */
    private suspend fun bundle(): EventBundle =
        (application as AttndApplication).graph.repository.restore()
            ?: throw AppFunctionElementNotFoundException(
                "The user hasn't joined an event in Attnd. Ask them to open Attnd and join one (scan its QR code or enter the event code)."
            )

    private fun EventFact.toItem(bundle: EventBundle): AttndEventItem = toAttndItem(this, applicationContext, bundle.event.id)
}

internal fun toAttndItem(fact: EventFact, context: Context, eventId: Int): AttndEventItem = AttndEventItem(
    kind = when (fact.kind) {
        SearchKind.SCHEDULE -> "schedule_item"
        SearchKind.LOCATION -> "location"
        SearchKind.NOTIFICATION -> "update"
        SearchKind.EVENT -> "event"
    },
    id = fact.id,
    title = fact.title,
    detail = fact.detail,
    status = fact.status,
    startTime = fact.startTime,
    endTime = fact.endTime,
    locationName = fact.locationName,
    description = fact.description,
    address = fact.address,
    latitude = fact.latitude,
    longitude = fact.longitude,
    openInApp = OpenIntents.pendingIntent(context, OpenRequest(eventId, fact.target)),
)
