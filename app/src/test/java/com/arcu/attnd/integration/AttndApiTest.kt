package com.arcu.attnd.integration

// Integration tests against the live attnd.app v2 API (API_V2.md).
// They need ATTND_API_KEY at build time (local.properties or the environment) and are skipped
// without it, so `./gradlew test` stays green offline and in forks.
//
// Fixture (sample_data.json on the server):
//   id=1  "Fort Worth Food & Music Festival"  code=FWFM2026
//   id=2  "Austin Tech & Innovation Summit"   code=ATIS2026
//   id=3  "Dallas Arts & Culture Night"       code=DACN2026

import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.AuthTokenProvider
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.api.LeaveRequest
import com.arcu.attnd.data.net.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AttndApiTest {

    private val api = AttndApi(object : AuthTokenProvider {
        override suspend fun token() = BuildConfig.ATTND_API_KEY
        override suspend fun reauthenticate(): String? = null
    })

    @Before
    fun requireApiKey() {
        assumeTrue("ATTND_API_KEY not configured; skipping live API tests", BuildConfig.ATTND_API_KEY.isNotBlank())
    }

    private fun lookupId(code: String): Int = runBlocking {
        val event = ApiMapper.event(api.lookup(code).event)
        assertNotNull("lookup($code) returned no event", event)
        event!!.id
    }

    @Test
    fun lookup_byCode_isCaseInsensitive() = runBlocking<Unit> {
        val event = ApiMapper.event(api.lookup("fwfm2026").event)!!
        assertEquals("FWFM2026", event.eventCode)
        assertEquals("Fort Worth Food & Music Festival", event.name)
    }

    @Test
    fun lookup_byExactName() {
        assertEquals(lookupId("FWFM2026"), lookupId("Fort Worth Food & Music Festival"))
    }

    @Test
    fun lookup_notFound_is404() = runBlocking<Unit> {
        try {
            api.lookup("DOESNOTEXIST99999")
            fail("Expected 404")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun bundle_decodes_andSupportsEtag() = runBlocking<Unit> {
        val eventId = lookupId("FWFM2026")
        val first = api.bundle(eventId, etag = null, deviceId = null)
        assertEquals(200, first.code)
        val bundle = ApiMapper.parseBundle(first.json!!)
        assertNotNull("bundle should decode", bundle)
        assertEquals(eventId, bundle!!.event.id)
        assertTrue(bundle.display.tabs.isNotEmpty())
        assertTrue("display.default_tab must be a visible tab", bundle.display.defaultTab in bundle.display.tabs)

        // A conditional request with the returned ETag should be answered with 304.
        val etag = first.etag
        if (etag != null) {
            val second = api.bundle(eventId, etag = etag, deviceId = null)
            assertEquals(304, second.code)
        }
    }

    @Test
    fun bundle_unknownEvent_is404() = runBlocking<Unit> {
        assertEquals(404, api.bundle(99_999_999, etag = null, deviceId = null).code)
    }

    @Test
    fun joinAndLeave() = runBlocking<Unit> {
        val eventId = lookupId("FWFM2026")
        val deviceId = "android-test-${UUID.randomUUID()}"
        assertEquals("ok", api.join(eventId, JoinRequest(deviceId = deviceId, appVersion = "test")).status)
        assertEquals("ok", api.leave(eventId, LeaveRequest(deviceId)).status)
    }

    @Test
    fun nearby_resultsDecode() = runBlocking<Unit> {
        val response = api.nearbyEvents(32.7555, -97.3308)
        val events = response.results.orEmpty().mapNotNull { ApiMapper.nearby(it) }
        // The window is 3 days back to 7 days ahead, so the fixture may legitimately be out of range.
        events.forEach { assertTrue(it.event.name.isNotEmpty()) }
        assertEquals(response.count ?: events.size, events.size)
    }
}
