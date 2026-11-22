package com.arcu.attnd.unit

import com.arcu.attnd.domain.AssistantPrompt
import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.ItemTiming
import com.arcu.attnd.domain.OpenLinks
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SearchMode
import com.arcu.attnd.domain.SearchQuery
import com.arcu.attnd.domain.SearchResult
import com.arcu.attnd.domain.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.util.Locale

/**
 * The shared search spec (same cases and the same open choices as iOS: weights name 3 / tags,
 * location name, category, snippet, address, event code 2 / descriptions, bodies 1; quality exact 4,
 * prefix 3, substring 2, typo 1; boosts now +8, next +4, ended −1000). The event is in America/Chicago (CST, UTC−6) on
 * Sat Nov 14 – Sun Nov 15, 2026; "now" is fixed at Sat 1:00 PM local (19:00 UTC).
 */
class EventSearchTest {

    private val now: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private val locale = Locale.US

    /** A local Chicago time on Nov 14 (Sat) or 15 (Sun), as stored by the API (with offset). */
    private fun sat(time: String) = EventTime.parse("2026-11-14T$time:00-06:00")
    private fun sun(time: String) = EventTime.parse("2026-11-15T$time:00-06:00")

    private fun item(
        id: Int,
        name: String,
        start: java.time.OffsetDateTime?,
        end: java.time.OffsetDateTime? = null,
        locationId: Int? = null,
        locationName: String? = null,
        tags: List<String> = emptyList(),
        description: String = "",
    ) = ScheduleItem(id, name, description, start, end, locationId, locationName, tags)

    private fun location(
        id: Int,
        name: String,
        category: String? = null,
        snippet: String = "",
        coordinate: GeoPoint? = null,
        address: String = "",
    ) = EventLocation(id, name, snippet, "", category, null, null, address, coordinate, id)

    private val bundle = EventBundle(
        schemaVersion = 2,
        contentVersion = 1,
        updatedAt = null,
        event = Event(
            id = 77, name = "Fort Worth Food & Music Festival", eventCode = "FWFM2026",
            description = "Food and music downtown.", address = "Sundance Square, Fort Worth, TX",
            coordinate = GeoPoint(32.75, -97.33), startTime = sat("10:00"), endTime = sun("22:00"),
            timezone = "America/Chicago", status = EventStatus.PUBLISHED, paid = true, isDemo = false,
            flyerUrl = null, accentColor = null, logoUrl = null, contentVersion = 1, joinUrl = null,
        ),
        display = DisplayConfig.resolve(
            null, null, null, null, null, null, null, null, null, null, null, null, hasFlyer = false,
        ),
        categories = listOf("Stage", "Food"),
        locations = listOf(
            location(10, "Main Stage", category = "Stage", snippet = "Live music", coordinate = GeoPoint(32.751, -97.331)),
            location(11, "Taco Truck", category = "Food", snippet = "Street tacos", coordinate = GeoPoint(32.752, -97.332)),
            // No coordinates: still searchable.
            location(12, "Café Olé", category = "Food", snippet = "Coffee & pastries", coordinate = null),
        ),
        schedule = listOf(
            item(1, "Roaming Mariachi", sat("12:30"), sat("14:00"), 10, "Main Stage", listOf("Music")), // now
            item(2, "Taco Tasting", sat("14:30"), sat("15:30"), 11, "Taco Truck", listOf("Food")), // next
            item(3, "Jazz Night", sat("19:00"), sat("21:00"), 10, "Main Stage", listOf("Music")), // later, tonight
            item(4, "Morning Yoga", sat("08:00"), sat("09:00")), // ended, no location
            item(5, "Sunday Brunch", sun("10:00"), sun("12:00"), 12, "Café Olé", listOf("Food")),
            item(6, "Late set", sat("21:30"), null, 10, "Main Stage", listOf("Music")), // later, no end
            item(7, "Sound Check", sat("09:00"), sat("10:00"), 10, "Main Stage", listOf("Music")), // ended
            item(8, "Drum Circle", sat("14:00"), sat("14:45"), tags = listOf("Music")), // next, no location
        ),
        notifications = listOf(
            EventNotification(20, "Gates open", "Come on in through the north entrance", sat("09:55")),
            EventNotification(21, "Weather update", "Rain expected around 4pm, bring a poncho", sat("11:00")),
        ),
    )

    private fun search(query: String, mode: SearchMode = SearchMode.ALL_TOKENS) = EventSearch.search(bundle, query, now, mode)
    private fun ids(results: List<SearchResult>, kind: SearchKind = SearchKind.SCHEDULE) =
        results.filter { it.kind == kind }.map { it.id }

    // ── Normalisation and matching ──────────────────────────────────────────

    @Test
    fun normalize_foldsAccentsCaseAndPunctuation() {
        assertEquals("cafe ole", SearchText.normalize("Café  Olé!"))
        assertEquals("food music festival", SearchText.normalize("Food & Music - Festival"))
        assertEquals("rock n roll", SearchText.normalize("Rock-'n'-Roll"))
    }

    @Test
    fun accentFolding_cafeFindsCafe() {
        val results = search("cafe")
        assertEquals(SearchKind.LOCATION to 12, results.first().kind to results.first().id)
        // The schedule item at Café Olé matches through its location name, ranked lower.
        assertTrue(5 in ids(results))
        // The highlight covers the accented original characters.
        assertEquals(listOf(0..3), results.first().titleHighlights)
    }

    @Test
    fun prefix_mariFindsRoamingMariachi() {
        val results = search("mari")
        assertEquals(SearchKind.SCHEDULE to 1, results.first().kind to results.first().id)
        assertEquals(listOf(8..11), results.first().titleHighlights)
        assertEquals("Mari", results.first().title.substring(8..11))
    }

    @Test
    fun andAcrossFields_tacoFood() {
        val results = search("taco food")
        assertEquals(setOf(SearchKind.SCHEDULE to 2, SearchKind.LOCATION to 11), results.map { it.kind to it.id }.toSet())
        // "Sunday Brunch" is Food but not taco; "Jazz Night" is neither.
        assertFalse(5 in ids(results))
    }

    @Test
    fun typo_mariachyFindsMariachi() {
        assertEquals(listOf(1), ids(search("mariachy")))
        // Short tokens never match by typo, and a transposition is two edits, not a typo.
        assertTrue(search("jazx").isEmpty())
        assertTrue(search("amriachi").isEmpty())
    }

    @Test
    fun withinOneEdit() {
        assertTrue(EventSearch.withinOneEdit("mariachy", "mariachi"))
        assertTrue(EventSearch.withinOneEdit("resturant", "restaurant"))
        assertTrue(EventSearch.withinOneEdit("brunchh", "brunch"))
        assertFalse(EventSearch.withinOneEdit("mariaxhy", "mariachi"))
        assertFalse(EventSearch.withinOneEdit("brunch", "lunch!!"))
        assertFalse(EventSearch.withinOneEdit("amriachi", "mariachi"))
    }

    // ── Time words (event time zone) ────────────────────────────────────────

    @Test
    fun timeWords_now() {
        val results = search("now")
        assertEquals(listOf(1), ids(results))
        assertTrue(results.all { it.kind == SearchKind.SCHEDULE })
        assertEquals(ids(results), ids(search("happening now")))
    }

    @Test
    fun timeWords_upNext() {
        assertEquals(setOf(2, 8), ids(search("up next")).toSet())
        assertEquals(setOf(2, 8), ids(search("soon")).toSet())
    }

    @Test
    fun timeWords_tonight() {
        assertEquals(setOf(3, 6), ids(search("tonight")).toSet())
    }

    @Test
    fun timeWords_clock7pm() {
        assertEquals(listOf(3), ids(search("7pm")))
        assertEquals(listOf(3), ids(search("7 pm")))
        assertEquals(listOf(3), ids(search("19:00")))
        // 1:30 means 1:30 AM or PM: the mariachi is in progress at 1:30 PM, the drum circle starts
        // within 30 minutes after it.
        assertEquals(setOf(1, 8), ids(search("1:30")).toSet())
        // Noon: the mariachi starts within 30 minutes after it.
        assertEquals(listOf(1), ids(search("noon")))
    }

    @Test
    fun timeWords_weekday() {
        val saturday = setOf(1, 2, 3, 4, 6, 7, 8)
        assertEquals(saturday, ids(search("saturday")).toSet())
        assertEquals(saturday, ids(search("sat")).toSet())
        assertEquals(listOf(5), ids(search("sunday")))
        assertEquals(listOf(5), ids(search("tomorrow")))
    }

    @Test
    fun timeWordsOnly_excludeOtherKinds() {
        // The weather update mentions 4pm, but a time-only query searches the schedule only.
        assertTrue(search("4pm").none { it.kind != SearchKind.SCHEDULE })
    }

    @Test
    fun mixed_musicTonight() {
        val results = search("music tonight")
        assertEquals(setOf(3, 6), ids(results).toSet())
        // Time words filter only schedule items: the stage whose snippet says "Live music" matches too.
        assertTrue(SearchKind.LOCATION to 10 in results.map { it.kind to it.id })
        assertEquals(listOf(5), ids(search("food sunday")))
        // Mixed queries still find other kinds by their text.
        assertTrue(SearchKind.LOCATION to 11 in search("taco 2:30pm").map { it.kind to it.id })
        assertEquals(listOf(2), ids(search("taco 2:30pm")))
    }

    @Test
    fun parse_splitsTimeWordsFromText() {
        // The search field keeps every word (AND), stop words included.
        assertEquals(listOf("where", "s", "the", "food", "at"), SearchQuery.parse("Where's the food at 6 p.m. tomorrow?").tokens)
        // Questions (assistant, AppFunctions) go through the keywords helper first.
        assertEquals("food 6 p.m. tomorrow?", SearchQuery.keywords("Where's the food at 6 p.m. tomorrow?"))
        assertEquals("the who", SearchQuery.keywords("the who"))
        val parsed = SearchQuery.parse(SearchQuery.keywords("Where's the food at 6 p.m. tomorrow?"))
        assertEquals(listOf("food"), parsed.tokens)
        assertEquals(listOf(LocalTime.of(18, 0)), parsed.time.clocks)
        assertEquals(1, parsed.time.days.size)
        // A weekday abbreviation that is a word in the content stays text.
        assertEquals(listOf("sun"), SearchQuery.parse("sun stage", vocabulary = setOf("sun", "stage")).tokens.take(1))
        assertTrue(SearchQuery.parse("sun stage").time.days.isNotEmpty())
        // A bare number is not a time; 3:30 without am/pm is both 03:30 and 15:30.
        assertTrue(SearchQuery.parse("7").time.isEmpty)
        assertEquals(listOf("7"), SearchQuery.parse("7").tokens)
        assertEquals(listOf(LocalTime.of(3, 30), LocalTime.of(15, 30)), SearchQuery.parse("3:30").time.clocks)
    }

    // ── Ranking ─────────────────────────────────────────────────────────────

    @Test
    fun ranking_nowThenNextThenLaterThenEnded() {
        val results = search("music").filter { it.kind == SearchKind.SCHEDULE }
        // (The event itself also matches "music", by name.) Mariachi (now) > Drum Circle (next) > Jazz Night, Late set (later, by name) > Sound Check (ended).
        assertEquals(listOf(1, 8, 3, 6, 7), ids(results))
        assertEquals(listOf(ItemTiming.NOW, ItemTiming.NEXT, null, null, ItemTiming.ENDED), results.map { it.timing })
    }

    @Test
    fun scores_weightsQualityAndBoosts() {
        // "jazz": exact name word, 3 × 4.
        assertEquals(12.0, search("jazz").single().score, 0.0)
        // "mari": prefix of the name, 3 × 3, + 8 (happening now).
        assertEquals(17.0, search("mari").first().score, 0.0)
        // "drum": 3 × 4 + 4 (next).
        assertEquals(16.0, search("drum").first().score, 0.0)
        // "check": 3 × 4 − 1000 (ended).
        assertEquals(-988.0, search("check").single().score, 0.0)
        // The event code is a secondary field: 2 × 4.
        assertEquals(8.0, search("fwfm2026").single().score, 0.0)
    }

    @Test
    fun itemsWithoutLocation_areSearchableAsGeneral() {
        assertEquals(listOf(8, 4), ids(search("general")))
    }

    @Test
    fun ranking_endedItemsComeLastEvenWithBetterMatches() {
        // "Sound Check" matches by name (weight 3) but has ended; "Main Stage" items match by location.
        val results = search("sound")
        assertEquals(7, results.last().id)
    }

    @Test
    fun emptyQuery_noResults() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
        assertTrue(search("&-!").isEmpty())
    }

    @Test
    fun suggestions_forEmptyQuery() {
        val labels = EventSearch.suggestions(bundle, now).map { it.label to it.query }
        assertEquals(listOf("Happening now" to "now", "Up next" to "next", "Stage" to "Stage", "Food" to "Food"), labels)
    }

    // ── Result rows ─────────────────────────────────────────────────────────

    @Test
    fun detail_scheduleInEventTimeZone() {
        val jazz = search("jazz").first()
        assertEquals("Sat 7:00–9:00 PM · Main Stage", EventSearch.detail(jazz, bundle, locale))
        val mariachi = search("mariachi").first()
        assertEquals("Sat 12:30–2:00 PM · Main Stage", EventSearch.detail(mariachi, bundle, locale))
        assertEquals("Sat 9:30 PM · Main Stage", EventSearch.detail(search("late set").first(), bundle, locale))
    }

    @Test
    fun itemsWithoutLocation_showGeneral() {
        val drum = search("drum").first()
        assertEquals("Sat 2:00–2:45 PM · General", EventSearch.detail(drum, bundle, locale))
        assertEquals(ItemTiming.NEXT, drum.timing)
    }

    @Test
    fun locationsWithoutCoordinates_areSearchable() {
        val cafe = search("pastries").single()
        assertEquals(SearchKind.LOCATION to 12, cafe.kind to cafe.id)
        assertEquals("Food · Coffee & pastries", EventSearch.detail(cafe, bundle, locale))
    }

    @Test
    fun notifications_matchBody() {
        val results = search("poncho")
        assertEquals(listOf(SearchKind.NOTIFICATION to 21), results.map { it.kind to it.id })
        assertEquals("Sat 11:00 AM · Rain expected around 4pm, bring a poncho", EventSearch.detail(results.single(), bundle, locale))
    }

    @Test
    fun event_matchesNameCodeAndShowsDates() {
        val event = search("fwfm2026").single()
        assertEquals(SearchKind.EVENT, event.kind)
        assertEquals("Nov 14–15 · Sundance Square, Fort Worth, TX", EventSearch.detail(event, bundle, locale))
    }

    // ── Assistant context, facts and open links ─────────────────────────────

    @Test
    fun anyTokenMode_findsItemsForQuestions() {
        // "Where can I get tacos?": stop words dropped, "tacos" matches "Street tacos" (and is a typo of nothing else).
        val results = search(SearchQuery.keywords("where can I get tacos"), SearchMode.ANY_TOKEN)
        assertTrue(SearchKind.LOCATION to 11 in results.map { it.kind to it.id })
        // ALL_TOKENS would need every token.
        assertTrue(search("tacos jazz").isEmpty())
        assertTrue(search("tacos jazz", SearchMode.ANY_TOKEN).isNotEmpty())
    }

    @Test
    fun assistantPrompt_containsContextAndParsesCitations() {
        val question = "When is the jazz?"
        val context = AssistantPrompt.context(bundle, question, now)
        assertEquals(SearchKind.SCHEDULE to 3, context.first().kind to context.first().id)
        val prompt = AssistantPrompt.build(bundle, question, context, now, locale)
        assertTrue(prompt.contains("[S3] Jazz Night — Sat 7:00–9:00 PM · Main Stage"))
        assertTrue(prompt.contains("It is now Saturday, November 14, 1:00 PM."))
        assertTrue(prompt.endsWith("Question: When is the jazz?\nAnswer:"))

        val answer = "Jazz Night starts at 7:00 PM on the Main Stage [S3]. See also [S99] and [E]."
        val cited = AssistantPrompt.citations(answer, context, bundle)
        assertEquals(listOf(SearchKind.SCHEDULE to 3, SearchKind.EVENT to 77), cited.map { it.kind to it.id })
        assertEquals("Jazz Night starts at 7:00 PM on the Main Stage. See also and.", AssistantPrompt.displayText(answer))
    }

    @Test
    fun openLinks_roundTrip() {
        val requests = listOf(
            OpenRequest(77, OpenTarget.Location(5)),
            OpenRequest(77, OpenTarget.ScheduleItem(3)),
            OpenRequest(77, OpenTarget.Notification(9)),
            OpenRequest(77, OpenTarget.Notification(null)),
            OpenRequest(77, OpenTarget.EventInfo),
            OpenRequest(77, OpenTarget.MapLocation(5)),
            OpenRequest(77, OpenTarget.MyPlan),
            OpenRequest(null, OpenTarget.Search("music tonight & más")),
        )
        for (request in requests) assertEquals(request, OpenLinks.parse(OpenLinks.uri(request)))
        assertEquals("attnd-open://77/location/5", OpenLinks.uri(requests.first()))
        assertNull(OpenLinks.parse("https://attnd.app/join/DEMO/"))
        assertNull(OpenLinks.parse("attnd-open://77/location/"))
        assertNull(OpenLinks.parse("attnd-open://x/event"))
        assertNull(OpenLinks.parse(null))
    }

    @Test
    fun locationTags_matchWithWeight2_api010() {
        val tagged = bundle.copy(
            locations = bundle.locations.map { if (it.id == 11) it.copy(tags = listOf("bbq", "kid-friendly")) else it },
        )
        val results = EventSearch.search(tagged, "kid friendly", now)
        assertEquals(listOf(SearchKind.LOCATION to 11), results.map { it.kind to it.id })
        // Tag weight 2 × exact 4 per token: same as a snippet match.
        assertEquals(16.0, results.single().score, 1e-9)
    }
}
