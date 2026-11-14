package com.arcu.attnd.integration

// The live DEMO event (attnd-web `load_demo_event`) decoded by the app's own models. The command
// regenerates it in place with times relative to when it ran, so it is always "happening now".
// If these facts and the server's demo data disagree, attnd-web's own test of load_demo_event is
// the source of truth: update the iOS, Android and web suites together.

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.SecondaryPane
import com.arcu.attnd.domain.Tab
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class DemoEventTest {

    private class Fetched(val raw: JsonObject, val bundle: EventBundle)

    @Before
    fun requireServer() = LiveServer.assumeConfigured()

    private fun fetch(): Fetched = runBlocking {
        val response = LiveServer.api().bundle(LiveServer.demo.id, etag = null, deviceId = null)
        assertEquals(200, response.code)
        val json = response.json!!
        val bundle = ApiMapper.parseBundle(json)
        assertNotNull("bundle decodes", bundle)
        Fetched(LiveServer.parse(json).jsonObject, bundle!!)
    }

    private fun JsonObject.arraySize(key: String): Int = (this[key] as? JsonArray)?.size ?: -1

    // ── 8. decoding: nothing the server sent is dropped ─────────────────────

    @Test
    fun bundle_decodesWithoutDroppingElements() {
        val (raw, bundle) = fetch().let { it.raw to it.bundle }
        assertEquals("locations", raw.arraySize("locations"), bundle.locations.size)
        assertEquals("schedule", raw.arraySize("schedule"), bundle.schedule.size)
        assertEquals("notifications", raw.arraySize("notifications"), bundle.notifications.size)
        assertEquals("categories", raw.arraySize("categories"), bundle.categories.size)
        val rawTabs = raw["display"]!!.jsonObject["tabs"]!!.jsonArray.map { (it as JsonPrimitive).content }
        assertEquals("tabs", rawTabs.mapNotNull { Tab.fromKey(it) }, bundle.display.tabs)
        assertEquals("every tab the server sends is one the app knows", rawTabs.size, bundle.display.tabs.size)
        assertEquals(raw["content_version"]!!.let { (it as JsonPrimitive).intOrNull }, bundle.contentVersion)
    }

    // ── DEMO facts ──────────────────────────────────────────────────────────

    @Test
    fun event_matchesDemoFacts() {
        val (raw, bundle) = fetch().let { it.raw to it.bundle }
        val event = bundle.event
        assertEquals("DEMO", event.eventCode)
        assertEquals("Demo: Fort Worth Food & Music Festival", event.name)
        assertEquals("America/Chicago", event.timezone)
        assertEquals(EventStatus.PUBLISHED, event.status)
        assertTrue("paid", event.paid)
        assertFalse("a paid event is not a demo event", event.isDemo)
        assertEquals("Panther Island, Fort Worth, TX 76102", event.address)
        val centre = event.coordinate
        assertNotNull("event has coordinates", centre)
        assertTrue("latitude ${centre!!.latitude}", abs(centre.latitude - 32.7495) <= 0.001)
        assertTrue("longitude ${centre.longitude}", abs(centre.longitude - -97.3316) <= 0.001)

        val now = Instant.now()
        val start = event.startTime?.toInstant()
        val end = event.endTime?.toInstant()
        assertNotNull(start)
        assertNotNull(end)
        assertTrue("happening now: starts $start", !start!!.isAfter(now))
        assertTrue("happening now: ends $end (regenerate with load_demo_event?)", !end!!.isBefore(now))

        assertTrue("content_version ${bundle.contentVersion}", bundle.contentVersion >= 1)
        assertEquals(bundle.contentVersion, event.contentVersion)

        val display = raw["display"]!!.jsonObject
        assertEquals("festival", (display["resolved_preset"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(SecondaryPane.NOW_NEXT, bundle.display.secondaryPane)
        assertTrue(
            "tabs ${bundle.display.tabs}",
            bundle.display.tabs.containsAll(listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS)),
        )
        assertTrue(bundle.display.defaultTab in bundle.display.tabs)
    }

    @Test
    fun locations_matchDemoFacts() {
        val bundle = fetch().bundle
        val locations = bundle.locations
        assertEquals(18, locations.size)
        val expectedNames = setOf(
            "Main Stage", "Trinity Stage", "Panther Island Pavilion", "Chef Demo Kitchen", "Taco Row", "BBQ Pit Lane",
            "Sweet Spot", "Craft Beer Garden", "Lemonade Stand", "Makers Market", "Kids Zone", "First Aid", "Info Booth",
            "Restrooms North", "Restrooms South", "Rideshare Pickup", "Overflow Parking", "Roaming Mariachi",
        )
        assertEquals(expectedNames, locations.map { it.name }.toSet())
        assertEquals(
            setOf("Stage", "Food", "Drinks", "Vendor", "Family", "Services", "Parking"),
            locations.mapNotNull { it.category }.toSet(),
        )
        assertEquals(bundle.categories.toSet(), locations.mapNotNull { it.category }.toSet())

        val mariachi = locations.single { it.name == "Roaming Mariachi" }
        assertEquals("Stage", mariachi.category)
        assertNull("Roaming Mariachi has no coordinates (never pinned)", mariachi.coordinate)

        val centre = bundle.event.coordinate!!
        val pinned = locations.filter { it.coordinate != null }
        assertEquals(17, pinned.size)
        pinned.forEach {
            val point = it.coordinate!!
            assertTrue(
                "${it.name} is near the centre",
                abs(point.latitude - centre.latitude) <= 0.01 && abs(point.longitude - centre.longitude) <= 0.01,
            )
        }

        val main = locations.single { it.name == "Main Stage" }
        assertEquals("Stage", main.category)
        assertEquals("Headliners every night", main.snippet)
        assertTrue(main.description, main.description.startsWith("The big one: headliners each night"))
        assertEquals("ids are unique", locations.size, locations.map { it.id }.toSet().size)
    }

    @Test
    fun schedule_matchesDemoFacts() {
        val bundle = fetch().bundle
        val schedule = bundle.schedule
        assertEquals(51, schedule.size)

        val general = schedule.filter { it.locationId == null }
        assertEquals(3, general.size)
        assertEquals(listOf("Gates open", "Gates open", "Opening ceremony"), general.map { it.name }.sorted())
        general.forEach { assertEquals(listOf("General"), it.tags) }

        val stages = setOf("Main Stage", "Trinity Stage", "Panther Island Pavilion")
            .map { name -> bundle.locations.single { it.name == name }.id }.toSet()
        val music = schedule.filter { "Music" in it.tags }
        assertEquals(36, music.size)
        assertTrue("music is on the three stages", music.all { stages.contains(it.locationId ?: -1) })

        val kitchen = bundle.locations.single { it.name == "Chef Demo Kitchen" }.id
        val demos = schedule.filter { it.locationId == kitchen }
        assertEquals(9, demos.size)
        assertTrue(demos.all { it.tags.containsAll(listOf("Food", "Demo")) })

        val kidsZone = bundle.locations.single { it.name == "Kids Zone" }.id
        val family = schedule.filter { it.locationId == kidsZone }
        assertEquals(3, family.size)
        assertTrue(family.all { it.name == "Face painting & games" && "Family" in it.tags })

        schedule.forEach {
            val start = it.startTime?.toInstant()
            val end = it.endTime?.toInstant()
            assertNotNull("${it.name} has a start", start)
            assertNotNull("${it.name} has an end", end)
            assertTrue("${it.name}: start < end", start!!.isBefore(end))
            it.locationId?.let { id -> assertNotNull("${it.name} points at a location in the bundle", bundle.location(id)) }
        }
        val starts = schedule.map { it.startTime!!.toInstant() }
        assertEquals("sorted by start_time", starts.sorted(), starts)

        val nowNext = ScheduleGrouping.nowNext(schedule, Instant.now())
        // Now / Next / Later only lists items that haven't ended: something is happening now or still to come.
        assertTrue("every item has ended (regenerate with load_demo_event?)", nowNext.isNotEmpty())
    }

    @Test
    fun notifications_areTheFourSentOnes() {
        val notifications = fetch().bundle.notifications
        // load_demo_event sends four at once and schedules two more ("Headliner soon" +90 min, "Day 2" +24 h)
        // that the scheduler sends later, so 4–6 depending on how long ago the demo was loaded.
        val titles = notifications.map { it.title }.toSet()
        assertTrue("the four sent at load: $titles",
            titles.containsAll(setOf("Welcome!", "Taco Row is open", "Shade & water", "Set time change")))
        assertTrue("only the two scheduled ones may join them: $titles",
            (titles - setOf("Welcome!", "Taco Row is open", "Shade & water", "Set time change")).all { it in setOf("Headliner soon", "Day 2") })
        assertEquals("no duplicates", titles.size, notifications.size)
        val times = notifications.map { it.sendTime!!.toInstant() }
        assertEquals("newest first", times.sortedDescending(), times)
        assertTrue("no scheduled (future) notification is in the bundle", times.all { !it.isAfter(Instant.now()) })
    }
}
