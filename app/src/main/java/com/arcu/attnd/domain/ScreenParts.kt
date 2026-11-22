package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Small pure rules the server-driven screens (phase 5b) draw with, 1:1 ports of iOS:
 *   - `PlaceEvents` (DisplayLogic.swift): a place's "3 events" disclosure;
 *   - `PlaceWalkMap.shows` (DisplayLogic.swift): when a place's detail draws the small walking map;
 *   - `HomeHeaderText` (ScreenLayoutViews.swift): Home's "Sat Nov 21 · Panther Island, Fort Worth";
 *   - `HomeUrgent` (Glance.swift `UpdatesInbox.urgent`): Home's urgent card;
 *   - `ContextFacts`: the "Good to know" lines of a schedule item or a place (ContextViews.swift);
 *   - `PhotoAttribution` (Models.swift, API-031): the authors of a live Google place photo.
 * Tests: `ScreenPartsTest` (the iOS cases).
 */

/** A place's schedule for the Places disclosure: the items still ahead in start order, or every one greyed when all ended. */
data class PlaceEvents(val items: List<ScheduleItem>, val allEnded: Boolean) {
    /** "1 event", "3 events", "2 ended". */
    val toggleTitle: String
        get() = when {
            allEnded -> "${items.size} ended"
            items.size == 1 -> "1 event"
            else -> "${items.size} events"
        }

    companion object {
        /** Null when nothing is on at the place. Untimed items go last; ties by id. */
        fun make(locationId: Int, schedule: List<ScheduleItem>, now: Instant): PlaceEvents? {
            val here = schedule.filter { it.locationId == locationId }
            if (here.isEmpty()) return null
            val ahead = here.filter { !ScheduleGrouping.hasEnded(it, now) }
            val listed = ahead.ifEmpty { here }
            val sorted = listed.sortedWith(
                compareBy<ScheduleItem>({ it.startTime == null }, { it.startTime?.toInstant() }, { it.id }),
            )
            return PlaceEvents(sorted, allEnded = ahead.isEmpty())
        }
    }
}

/** The place detail's small walking map: with a position, not "here" and within walking range; never in screenshot mode. */
object PlaceWalkMap {
    fun shows(distanceMeters: Double?, screenshotMode: Boolean): Boolean {
        if (screenshotMode || distanceMeters == null) return false
        return distanceMeters >= Geo.HERE_METERS && distanceMeters <= Walking.NEARBY_METERS
    }
}

/** Home's header line: the event's day in its own zone, then the address's first two parts. */
object HomeHeaderText {
    fun line(event: Event, locale: Locale = Locale.getDefault()): String? {
        val parts = ArrayList<String>()
        event.startTime?.let { start ->
            parts += DateTimeFormatter.ofPattern("EEE MMM d", locale).format(start.atZoneSameInstant(event.zone))
        }
        val place = event.address.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ")
        if (place.isNotEmpty()) parts += place
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

/** Home's urgent card: the newest urgent announcement sent in the last 12 hours (up to 5 min ahead of the device) that wasn't dismissed. */
object HomeUrgent {
    private val AHEAD: Duration = Duration.ofMinutes(5)

    fun pick(notifications: List<EventNotification>, dismissed: Set<Int>, now: Instant): EventNotification? =
        notifications.filter { n ->
            if (!n.isUrgent || n.id in dismissed) return@filter false
            val sent = n.sendTime?.toInstant() ?: return@filter false
            val age = Duration.between(sent, now)
            age < UpdatesLayout.URGENT_WINDOW && age > AHEAD.negated()
        }.maxWithOrNull(compareBy<EventNotification>({ it.sendTime?.toInstant() }, { it.id }))
}

/** One labelled line of a detail screen's "Good to know" ("Price" · "$5"). */
data class ContextFact(val label: String, val value: String)

object ContextFacts {
    fun item(context: ScheduleItemContext?): List<ContextFact> {
        context ?: return emptyList()
        return listOfNotNull(
            context.price.takeIf { it.isNotEmpty() }?.let { ContextFact("Price", it) },
            context.ageLimit.takeIf { it.isNotEmpty() }?.let { ContextFact("Ages", it) },
            context.accessibility.takeIf { it.isNotEmpty() }?.let { ContextFact("Accessibility", it) },
        )
    }

    fun place(context: LocationContext?): List<ContextFact> {
        context ?: return emptyList()
        return listOfNotNull(
            context.ageLimit.takeIf { it.isNotEmpty() }?.let { ContextFact("Ages", it) },
            context.accessibility.takeIf { it.isNotEmpty() }?.let { ContextFact("Accessibility", it) },
        )
    }
}

/**
 * API-031: a live Google photo's `image_attribution_url` answer: the authors and the line shown on the
 * photo ("Photo: Ana Ruiz · Google Maps"). Lenient: authors without a name dropped, non-https links
 * ignored; no usable credit = null (keep `image_credit`).
 */
data class PhotoAttribution(val credit: String, val authors: List<Author>) {
    data class Author(val name: String, val uri: String?)

    /** Where each linked author's name sits in [credit] (names not found stay plain). */
    val links: List<Triple<Int, Int, String>>
        get() = authors.mapNotNull { author ->
            val uri = author.uri ?: return@mapNotNull null
            val start = credit.indexOf(author.name).takeIf { it >= 0 } ?: return@mapNotNull null
            Triple(start, start + author.name.length, uri)
        }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun decode(text: String): PhotoAttribution? {
            val obj = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val authors = (obj["attributions"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                val name = (e["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val uri = (e["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(WebLinks::httpsUrl)
                Author(name, uri)
            }
            var credit = (obj["credit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (credit.isEmpty() && authors.isNotEmpty()) credit = "Photo: " + authors.joinToString(", ") { it.name } + " · Google Maps"
            if (credit.isEmpty()) return null
            return PhotoAttribution(credit, authors)
        }
    }
}

/**
 * The saved bundle's ETag is sent again only for a body of this build's layout catalogue (iOS
 * `SavedEvent.usableETag`): a body saved before `X-Attnd-Sections` has no `layout`, and a 304 must not keep it.
 */
object SavedBundleETag {
    fun usable(etag: String?, savedCatalogue: Int, catalogue: Int = SectionCatalogue.VERSION): String? =
        etag?.takeIf { savedCatalogue == catalogue }
}
