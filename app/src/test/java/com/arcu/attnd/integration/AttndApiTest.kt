package com.arcu.attnd.integration

// Integration tests that hit the live attnd.app API.
// All test data matches the sample_data.json fixture loaded on the production server.
//
// Event fixture summary:
//   id=1  "Fort Worth Food & Music Festival"  code=FWFM2026  10 locations  2 sent notifications
//   id=2  "Austin Tech & Innovation Summit"   code=ATIS2026  10 locations  2 sent notifications
//   id=3  "Dallas Arts & Culture Night"       code=DACN2026  10 locations  2 sent notifications

import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.JoinRequest
import com.arcu.attnd.data.NetworkModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import java.util.UUID

class AttndApiTest {

    private val api = NetworkModule.api

    // ── Event Lookup by Code ──────────────────────────────────────────────────

    @Test
    fun testLookupByCode_FortWorth() = runBlocking {
        val event = api.lookupEvent("FWFM2026")
        assertEquals(1, event.id)
        assertEquals("Fort Worth Food & Music Festival", event.name)
        assertEquals("FWFM2026", event.eventCode)
        assertEquals("Sundance Square, 420 Main St, Fort Worth, TX 76102", event.address)
        assertEquals(32.7555, event.latitude, 0.001)
        assertEquals(-97.3308, event.longitude, 0.001)
        assertFalse(event.paid)
    }

    @Test
    fun testLookupByCode_Austin() = runBlocking {
        val event = api.lookupEvent("ATIS2026")
        assertEquals(2, event.id)
        assertEquals("Austin Tech & Innovation Summit", event.name)
        assertEquals("ATIS2026", event.eventCode)
    }

    @Test
    fun testLookupByCode_Dallas() = runBlocking {
        val event = api.lookupEvent("DACN2026")
        assertEquals(3, event.id)
        assertEquals("Dallas Arts & Culture Night", event.name)
        assertEquals("DACN2026", event.eventCode)
    }

    // ── Event Lookup by Name ──────────────────────────────────────────────────

    @Test
    fun testLookupByName_FortWorth() = runBlocking {
        val event = api.lookupEvent("Fort Worth Food & Music Festival")
        assertEquals(1, event.id)
        assertEquals("FWFM2026", event.eventCode)
    }

    @Test
    fun testLookupByName_Austin() = runBlocking {
        val event = api.lookupEvent("Austin Tech & Innovation Summit")
        assertEquals(2, event.id)
    }

    // ── Lookup Not Found ──────────────────────────────────────────────────────

    @Test
    fun testLookupNotFound() = runBlocking {
        try {
            api.lookupEvent("DOESNOTEXIST99999")
            fail("Expected HttpException 404")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
    }

    // ── Fetch Event by ID ─────────────────────────────────────────────────────

    @Test
    fun testFetchEventById_FortWorth() = runBlocking {
        val event = api.getEvent(1)
        assertEquals(1, event.id)
        assertEquals("Fort Worth Food & Music Festival", event.name)
    }

    @Test
    fun testFetchEventById_Austin() = runBlocking {
        val event = api.getEvent(2)
        assertEquals(2, event.id)
        assertEquals("Austin Tech & Innovation Summit", event.name)
    }

    @Test
    fun testFetchEventById_NotFound() = runBlocking {
        try {
            api.getEvent(99999)
            fail("Expected HttpException 404")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
    }

    // ── Locations List ────────────────────────────────────────────────────────

    @Test
    fun testLocations_FortWorth_Count() = runBlocking {
        val response = api.getLocations(1)
        assertEquals(10, response.count)
        assertEquals(10, response.results.size)
    }

    @Test
    fun testLocations_FortWorth_Names() = runBlocking {
        val names = api.getLocations(1).results.map { it.name }
        assertTrue(names.contains("Cattlemen's Steakhouse"))
        assertTrue(names.contains("Sundance Square Plaza"))
        assertTrue(names.contains("Billy Bob's Texas"))
        assertTrue(names.contains("Kimbell Art Museum"))
    }

    @Test
    fun testLocations_Austin_Count() = runBlocking {
        val response = api.getLocations(2)
        assertEquals(10, response.count)
        val names = response.results.map { it.name }
        assertTrue(names.contains("Palmer Events Center"))
        assertTrue(names.contains("Capital Factory"))
        assertTrue(names.contains("Stubb's Amphitheater"))
    }

    @Test
    fun testLocations_Dallas_Count() = runBlocking {
        val response = api.getLocations(3)
        assertEquals(10, response.count)
        val names = response.results.map { it.name }
        assertTrue(names.contains("Dallas Museum of Art"))
        assertTrue(names.contains("Deep Ellum"))
        assertTrue(names.contains("The Bomb Factory"))
    }

    // ── Single Location ───────────────────────────────────────────────────────

    @Test
    fun testFetchLocation_CattleSteak() = runBlocking {
        val loc = api.getLocationDetails(1, 1)
        assertEquals(1, loc.id)
        assertEquals("Cattlemen's Steakhouse", loc.name)
        assertEquals("Award-winning steakhouse since 1947.", loc.snippet)
        assertEquals("2458 N Main St, Fort Worth, TX 76106", loc.address)
        assertEquals(32.755, loc.latitude, 0.001)
        assertEquals(-97.332, loc.longitude, 0.001)
        assertNull(loc.imageUrl)
        assertNull(loc.website)
        assertTrue(loc.description.isNotEmpty())
    }

    @Test
    fun testFetchLocation_PalmerEvents() = runBlocking {
        val loc = api.getLocationDetails(2, 11)
        assertEquals(11, loc.id)
        assertEquals("Palmer Events Center", loc.name)
    }

    @Test
    fun testFetchLocation_NotFound() = runBlocking {
        try {
            api.getLocationDetails(1, 99999)
            fail("Expected HttpException 404")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
    }

    // ── Notifications (API only returns sent=true) ────────────────────────────

    @Test
    fun testNotifications_FortWorth() = runBlocking {
        val response = api.getNotifications(1)
        assertEquals(2, response.count)
        assertEquals(2, response.results.size)
        assertTrue(response.results.all { it.sent })
        val titles = response.results.map { it.title }
        assertTrue(titles.contains("Festival kicks off tomorrow!"))
        assertTrue(titles.contains("Lineup announced for Saturday night"))
    }

    @Test
    fun testNotifications_Austin() = runBlocking {
        val response = api.getNotifications(2)
        assertEquals(2, response.count)
        val titles = response.results.map { it.title }
        assertTrue(titles.contains("Early bird registration closes tonight"))
        assertTrue(titles.contains("Speaker spotlight: keynote revealed"))
    }

    @Test
    fun testNotifications_Dallas() = runBlocking {
        val response = api.getNotifications(3)
        assertEquals(2, response.count)
        val titles = response.results.map { it.title }
        assertTrue(titles.contains("Venue map now available"))
        assertTrue(titles.contains("Free parking at Klyde Warren Park"))
    }

    @Test
    fun testNotifications_AllSent() = runBlocking {
        for (eventId in listOf(1, 2, 3)) {
            val response = api.getNotifications(eventId)
            assertTrue(
                "Event $eventId returned an unsent notification",
                response.results.all { it.sent }
            )
        }
    }

    // ── Device Join / Leave ───────────────────────────────────────────────────

    @Test
    fun testJoinAndLeaveEvent() = runBlocking {
        val deviceId = "android-test-${UUID.randomUUID()}"
        val joinResult = api.joinEvent(1, deviceId, JoinRequest())
        assertNotNull(joinResult)
        val leaveResult = api.leaveEvent(1, deviceId)
        assertNotNull(leaveResult)
    }

    @Test
    fun testJoinEvent_UnknownEvent() = runBlocking {
        try {
            api.joinEvent(99999, "android-test-404", JoinRequest())
            fail("Expected HttpException 404")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
    }

    // ── Nearby Events ─────────────────────────────────────────────────────────

    @Test
    fun testNearbyEvents_FortWorth_ContainsEvent1() = runBlocking {
        val response = api.getNearbyEvents(32.7555, -97.3308)
        assertTrue(response.count > 0)
        val ids = response.results.map { it.event.id }
        assertTrue("Fort Worth event should appear near its own coordinates", ids.contains(1))
    }

    @Test
    fun testNearbyEvents_Austin_ContainsEvent2() = runBlocking {
        val response = api.getNearbyEvents(30.2672, -97.7431)
        assertTrue(response.count > 0)
        val ids = response.results.map { it.event.id }
        assertTrue(ids.contains(2))
    }

    @Test
    fun testNearbyEvents_Dallas_ContainsEvent3() = runBlocking {
        val response = api.getNearbyEvents(32.7767, -96.7970)
        assertTrue(response.count > 0)
        val ids = response.results.map { it.event.id }
        assertTrue(ids.contains(3))
    }

    @Test
    fun testNearbyEvents_ResultShape() = runBlocking {
        val response = api.getNearbyEvents(32.7555, -97.3308)
        val first = response.results.firstOrNull()
        assertNotNull("Expected at least one nearby event", first)
        first!!
        assertTrue(first.distanceMiles >= 0.0)
        assertTrue(first.event.name.isNotEmpty())
        assertTrue(first.event.eventCode.isNotEmpty())
        assertTrue(first.event.address.isNotEmpty())
        assertEquals(response.count, response.results.size)
    }

    // ── Response Envelope ─────────────────────────────────────────────────────

    @Test
    fun testEnvelope_CountMatchesResults() = runBlocking {
        val locations = api.getLocations(1)
        assertEquals(locations.count, locations.results.size)

        val notifications = api.getNotifications(1)
        assertEquals(notifications.count, notifications.results.size)
    }
}
