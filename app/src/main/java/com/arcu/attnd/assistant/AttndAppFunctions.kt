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
import com.arcu.attnd.AppGraph
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventFact
import com.arcu.attnd.domain.EventFacts
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.Redact
import com.arcu.attnd.domain.SearchKind
import kotlinx.coroutines.CancellationException
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
    suspend fun searchEvent(query: String, limit: Int = EventFacts.DEFAULT_LIMIT): List<AttndEventItem> = appFunction("searchEvent", query) {
        if (query.isBlank()) throw AppFunctionInvalidArgumentException("The query is empty.")
        val bundle = bundle()
        EventFacts.search(bundle, query, Instant.now(), limit = EventFacts.limit(limit), locale = Locale.getDefault())
            .map { it.toItem(bundle) }
    }

    /**
     * Lists what is happening at the event right now and what starts within the next two hours.
     *
     * @return Schedule items in progress first, then upcoming ones, in time order.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getHappeningNow(): List<AttndEventItem> = appFunction("getHappeningNow", null) {
        val bundle = bundle()
        EventFacts.happeningNow(bundle, Instant.now()).map { it.toItem(bundle) }
    }

    /**
     * Finds when something on the event's schedule happens ("when is the fireworks show?").
     *
     * @param name The name or part of the name of the performance, session or activity.
     * @return Matching schedule items with their start and end times and place.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun findScheduleItems(name: String): List<AttndEventItem> = appFunction("findScheduleItems", name) {
        if (name.isBlank()) throw AppFunctionInvalidArgumentException("The name is empty.")
        val bundle = bundle()
        EventFacts.search(bundle, name, Instant.now(), kinds = setOf(SearchKind.SCHEDULE)).map { it.toItem(bundle) }
    }

    /**
     * Finds where a place at the event is ("where is the taco truck?"): stages, food, restrooms, booths.
     *
     * @param name The name, category or part of the name of the place.
     * @return Matching locations with their address and coordinates when known.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun findLocations(name: String): List<AttndEventItem> = appFunction("findLocations", name) {
        if (name.isBlank()) throw AppFunctionInvalidArgumentException("The name is empty.")
        val bundle = bundle()
        EventFacts.search(bundle, name, Instant.now(), kinds = setOf(SearchKind.LOCATION)).map { it.toItem(bundle) }
    }

    /**
     * Gets the latest announcements from the event's organizers, newest first.
     *
     * @param limit Maximum number of updates (default 10).
     * @return The organizer updates.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getLatestUpdates(limit: Int = EventFacts.DEFAULT_LIMIT): List<AttndEventItem> = appFunction("getLatestUpdates", null) {
        val bundle = bundle()
        EventFacts.latestUpdates(bundle, EventFacts.limit(limit)).map { it.toItem(bundle) }
    }

    /**
     * Gets the joined event's name, dates, address and description.
     *
     * @return The event.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getEventInfo(): AttndEventItem = appFunction("getEventInfo", null) {
        val bundle = bundle()
        EventFacts.eventInfo(bundle).toItem(bundle)
    }

    /**
     * Lists what the user has hearted (saved) in the joined event: schedule items first, in time order,
     * then places.
     *
     * @return The hearted items; empty when nothing is hearted.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getHeartedItems(): List<AttndEventItem> = appFunction("getHeartedItems", null) {
        val bundle = bundle()
        val hearts = graph().hearts.hearts(bundle.event.id)
        EventFacts.hearted(bundle, hearts, Instant.now()).map { it.toItem(bundle) }
    }

    /**
     * Hearts (saves) or un-hearts a schedule item or a place in the joined event, e.g. "heart the
     * fireworks show". Find the item's kind and id with the other functions first.
     *
     * @param kind "schedule_item" or "location", as in the item's kind.
     * @param id The item's id.
     * @param hearted True to heart the item, false to remove the heart.
     * @return The item.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun setHeart(kind: String, id: Int, hearted: Boolean): AttndEventItem = appFunction("setHeart", null) {
        val bundle = bundle()
        val searchKind = when (kind.trim().lowercase(Locale.ROOT)) {
            "schedule_item", "schedule" -> SearchKind.SCHEDULE
            "location", "place" -> SearchKind.LOCATION
            else -> throw AppFunctionInvalidArgumentException("Only schedule items and locations can be hearted.")
        }
        val fact = when (searchKind) {
            SearchKind.SCHEDULE -> bundle.scheduleItem(id)?.let { EventFacts.schedule(it, bundle, Instant.now()) }
            else -> bundle.location(id)?.let { EventFacts.location(it, bundle) }
        } ?: throw AppFunctionElementNotFoundException("There is no $kind with id $id in this event.")
        val graph = graph()
        if (graph.hearts.set(bundle.event.id, searchKind, id, hearted)) {
            Hearts.metric(searchKind, hearted)?.let { graph.usage.track(bundle.event.id, it, id.toString()) }
        }
        fact.toItem(bundle)
    }

    private fun graph(): AppGraph = (application as AttndApplication).graph

    /**
     * `assistant.request` (kind `appfunction`): name, ms, result count, ok; the query's length only (dev
     * builds may log the text). Failures also log `assistant.error`.
     */
    private inline fun <T> appFunction(name: String, text: String?, block: () -> T): T {
        val started = System.nanoTime()
        try {
            val result = block()
            AppLog.info(
                LogCategory.ASSISTANT, LogEvent.ASSISTANT_REQUEST, "AppFunction $name: ok",
                mapOf(
                    "kind" to "appfunction", "name" to name, "ms" to (System.nanoTime() - started) / 1_000_000, "ok" to true,
                    "results" to ((result as? List<*>)?.size ?: 1),
                ) + (if (text != null) Redact.textFields("query", text, AppLog.allowText) else emptyMap()),
            )
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warning(
                LogCategory.ASSISTANT, LogEvent.ASSISTANT_REQUEST, "AppFunction $name failed (${e.javaClass.simpleName})",
                mapOf("kind" to "appfunction", "name" to name, "ms" to (System.nanoTime() - started) / 1_000_000, "ok" to false),
            )
            AppLog.warning(
                LogCategory.ASSISTANT, LogEvent.ASSISTANT_ERROR, "AppFunction $name failed",
                mapOf("kind" to "appfunction", "error" to AppLog.errorKind(e), "domain" to e.javaClass.simpleName),
            )
            throw e
        }
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
