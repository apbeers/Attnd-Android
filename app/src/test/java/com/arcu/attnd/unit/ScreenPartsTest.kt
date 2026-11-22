package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.ContextFacts
import com.arcu.attnd.domain.ContextFact
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.HomeHeaderText
import com.arcu.attnd.domain.HomeUrgent
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LocationContext
import com.arcu.attnd.domain.NotificationPriority
import com.arcu.attnd.domain.PhotoAttribution
import com.arcu.attnd.domain.PlaceEvents
import com.arcu.attnd.domain.PlaceWalkMap
import com.arcu.attnd.domain.SavedBundleETag
import com.arcu.attnd.domain.ScheduleItemContext
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.domain.ScreenLayout
import com.arcu.attnd.domain.SectionCatalogue
import com.arcu.attnd.unit.GlanceFixture.item
import com.arcu.attnd.unit.GlanceFixture.sat
import com.arcu.attnd.unit.GlanceFixture.sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/**
 * Phase 5b's pure rules (`ScreenParts.kt`): iOS `DisplayLogicTests` (PlaceEvents, PlaceWalkMap),
 * `ModelDecodingTests.testPhotoAttribution_API031`, Home's header and urgent card, the saved ETag's catalogue,
 * and a server layout decoded with the bundle (`ApiMapper` → `EventBundle.layout` → `ScreenLayout.resolve`).
 */
class ScreenPartsTest {

    // A place's events (Places disclosure)

    @Test
    fun placeEvents_upcomingInStartOrder() {
        val now = Instant.parse("2026-11-14T20:00:00Z") // Sat 2:00 PM Chicago
        val schedule = listOf(
            item(1, "A", sat("19:00"), locationId = 5),
            item(2, "B", sat("10:00"), sat("11:00"), locationId = 5), // ended
            item(3, "C", sat("13:30"), sat("15:00"), locationId = 5), // now
            item(4, "D", null, locationId = 5), // no time
            item(5, "E", sat("15:00"), locationId = 6), // elsewhere
        )
        val events = PlaceEvents.make(5, schedule, now)
        assertEquals(listOf(3, 1, 4), events?.items?.map { it.id })
        assertEquals(false, events?.allEnded)
        assertEquals("3 events", events?.toggleTitle)
        assertEquals("1 event", PlaceEvents.make(6, schedule, now)?.toggleTitle)
    }

    @Test
    fun placeEvents_endedOnlyWhenNothingIsAhead() {
        val now = Instant.parse("2026-11-16T05:00:00Z") // Sun 11:00 PM Chicago
        val schedule = listOf(item(2, "B", sat("12:00"), locationId = 5), item(1, "A", sat("10:00"), locationId = 5))
        val events = PlaceEvents.make(5, schedule, now)
        assertEquals(listOf(1, 2), events?.items?.map { it.id })
        assertEquals(true, events?.allEnded)
        assertEquals("2 ended", events?.toggleTitle)
        assertNull("no items → no disclosure", PlaceEvents.make(9, schedule, now))
    }

    // Walking map (place detail)

    @Test
    fun placeWalkMap_onlyWithinWalkingRange() {
        assertTrue(PlaceWalkMap.shows(600.0, screenshotMode = false))
        assertTrue(PlaceWalkMap.shows(8_000.0, screenshotMode = false))
        assertFalse("farther: the button", PlaceWalkMap.shows(8_001.0, screenshotMode = false))
        assertFalse("here: no walk", PlaceWalkMap.shows(10.0, screenshotMode = false))
        assertFalse("no permission or fix", PlaceWalkMap.shows(null, screenshotMode = false))
        assertFalse(PlaceWalkMap.shows(600.0, screenshotMode = true))
    }

    // Home

    @Test
    fun homeHeader_dayInTheEventsZone_thenTwoAddressParts() {
        val event = GlanceFixture.bundle.event
        assertEquals("Sat Nov 14 · Sundance Square, Fort Worth", HomeHeaderText.line(event, Locale.US))
        assertEquals("Sat Nov 14", HomeHeaderText.line(event.copy(address = " , "), Locale.US))
        assertNull(HomeHeaderText.line(event.copy(address = "", startTime = null), Locale.US))
    }

    @Test
    fun homeUrgent_newestOfTheLast12Hours_notDismissed() {
        val now = GlanceFixture.now // Sat 2:00 PM
        fun urgent(id: Int, sent: java.time.OffsetDateTime?) = EventNotification(id, "U$id", "", sent, NotificationPriority.URGENT)
        val list = listOf(
            urgent(1, sat("09:00")),
            urgent(2, sat("13:00")),
            urgent(3, sat("01:00")), // 13 h ago
            EventNotification(4, "Normal", "", sat("13:30")),
            urgent(5, sat("14:10")), // 10 min ahead of the device: not yet
            urgent(6, null),
        )
        assertEquals(2, HomeUrgent.pick(list, emptySet(), now)?.id)
        assertEquals(1, HomeUrgent.pick(list, setOf(2), now)?.id)
        assertNull(HomeUrgent.pick(list, setOf(1, 2), now))
        assertEquals(7, HomeUrgent.pick(list + urgent(7, sat("14:03")), emptySet(), now)?.id) // 3 min ahead is fine
        assertNull(HomeUrgent.pick(list, emptySet(), sun("12:00").toInstant()))
    }

    // Good to know

    @Test
    fun contextFacts_inOrder_emptyOnesLeftOut() {
        assertEquals(
            listOf(ContextFact("Price", "$5"), ContextFact("Ages", "21+"), ContextFact("Accessibility", "Ramp")),
            ContextFacts.item(ScheduleItemContext(price = "$5", ageLimit = "21+", accessibility = "Ramp")),
        )
        assertEquals(listOf(ContextFact("Ages", "All ages")), ContextFacts.place(LocationContext(ageLimit = "All ages")))
        assertTrue(ContextFacts.item(null).isEmpty())
    }

    // API-031

    @Test
    fun photoAttribution_api031() {
        val json = """
            {"url":"https://lh3.example/p.jpg","attributions":[{"name":"Ana Ruiz","uri":"https://maps.google.com/u/1"},
             {"name":" "},{"name":"Bo","uri":"http://insecure"}],"credit":"Photo: Ana Ruiz, Bo · Google Maps"}
        """.trimIndent()
        val a = PhotoAttribution.decode(json)
        assertNotNull(a)
        assertEquals("Photo: Ana Ruiz, Bo · Google Maps", a!!.credit)
        assertEquals(listOf("Ana Ruiz", "Bo"), a.authors.map { it.name })
        assertEquals("https://maps.google.com/u/1", a.authors[0].uri)
        assertNull(a.authors[1].uri)
        assertEquals(listOf(Triple(7, 15, "https://maps.google.com/u/1")), a.links)
        // No credit: made from the names; nothing usable: null.
        assertEquals("Photo: Cy · Google Maps", PhotoAttribution.decode("""{"attributions":[{"name":"Cy"}]}""")?.credit)
        assertNull(PhotoAttribution.decode("""{"attributions":[]}"""))
        assertNull(PhotoAttribution.decode("nope"))
    }

    // The saved bundle's ETag (iOS `SavedEvent.usableETag`)

    @Test
    fun savedETag_onlyForABodyOfThisCatalogue() {
        assertEquals("\"v3-s2\"", SavedBundleETag.usable("\"v3-s2\"", savedCatalogue = SectionCatalogue.VERSION))
        assertNull("saved before X-Attnd-Sections", SavedBundleETag.usable("\"v3\"", savedCatalogue = 0))
        assertNull(SavedBundleETag.usable(null, savedCatalogue = SectionCatalogue.VERSION))
    }

    // A server layout, decoded with the bundle

    @Test
    fun serverLayout_decodedWithTheBundle_andResolved() {
        val json = """
            {"event": {"id": 3, "name": "Fest", "timezone": "America/Chicago",
              "context": {"faq": [{"q": "Bags?", "a": "Clear only."}]}},
             "schedule": [{"id": 12, "name": "Set", "start_time": "2026-11-14T19:00:00-06:00",
               "context": {"performers": [{"name": "Prairie Lights", "apple_music_url": "https://music.apple.com/us/artist/1"}]}}],
             "layout": {"catalogue": 2, "screens": {
               "home": {"sections": [{"type": "faq", "title": "Questions", "limit": 2}, {"type": "liked"}, {"type": "nope"}]},
               "item_detail": {"sections": [{"type": "header", "show": ["time"]}, {"type": "performers"}],
                 "overrides": [{"match": "tag:quiet", "sections": [{"type": "header"}]}]},
               "unknown_screen": {"sections": [{"type": "header"}]}}}}
        """.trimIndent()
        val bundle = ApiMapper.parseBundle(json)!!
        val layout = bundle.layout
        assertNotNull(layout)
        assertEquals(2, layout!!.catalogue)
        assertEquals(setOf(LayoutScreen.HOME, LayoutScreen.ITEM_DETAIL), layout.screens.keys)

        val state = ScreenFacts.State(now = GlanceFixture.now)
        val home = ScreenLayout.resolve(LayoutScreen.HOME, layout, ScreenFacts.event(bundle, state))
        // `liked` maps to `my_schedule`; unknown types are dropped; Home always ends with `website` (none here: hidden).
        assertEquals(listOf("faq", "my_schedule"), home.map { it.type })
        assertEquals("Questions", home[0].title)
        assertEquals(2, home[0].limit)

        val set = bundle.schedule.single()
        assertEquals("https://music.apple.com/us/artist/1", set.context?.performers?.single()?.appleMusicUrl)
        val detail = ScreenLayout.resolve(LayoutScreen.ITEM_DETAIL, layout, ScreenFacts.item(set, bundle, state), ScreenFacts.subject(set, bundle))
        assertEquals(listOf("header", "performers"), detail.map { it.type })
        assertEquals(listOf("time"), detail[0].show)

        // No layout (an older server, or screenshot mode): the built-in screens.
        val builtIn = ScreenLayout.resolve(LayoutScreen.ITEM_DETAIL, null, ScreenFacts.item(set, bundle, state))
        assertEquals(listOf("header", "performers", "place_link"), builtIn.map { it.type })
        assertNull(ApiMapper.parseBundle("""{"event": {"id": 3}, "layout": null}""")!!.layout)
    }
}
