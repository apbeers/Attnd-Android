package com.arcu.attnd.integration

// Live protocol tests against the v2 API (attnd-web API_V2.md) through the app's real client stack.
// Server and key come from ATTND_IT_SERVER / ATTND_IT_KEY; without them every test is skipped.
// They only touch the DEMO event (the production CI key is accepted for nothing else).

import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.api.ApiJson
import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.DeviceRequest
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.api.LeaveRequest
import com.arcu.attnd.data.api.UsageEntryDto
import com.arcu.attnd.data.api.UsageRequest
import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.domain.UpdateLevel
import com.arcu.attnd.domain.UsageAggregator
import com.arcu.attnd.domain.UsageMetric
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AttndApiTest {

    private val api by lazy { LiveServer.api() }

    /** (event id, device id) pairs joined by a test; left again in [leaveJoinedEvents]. */
    private val joined = mutableListOf<Pair<Int, String>>()

    @Before
    fun requireServer() = LiveServer.assumeConfigured()

    @After
    fun leaveJoinedEvents() = runBlocking {
        for ((eventId, deviceId) in joined) {
            runCatching { LiveServer.api().leave(eventId, LeaveRequest(deviceId)) }
        }
        joined.clear()
    }

    private suspend fun expectApiError(code: Int, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected HTTP $code")
        } catch (e: ApiException) {
            assertEquals("HTTP status", code, e.code)
        }
    }

    // ── 1. app-status (unversioned, unauthenticated) ────────────────────────

    @Test
    fun appStatus_answersForThisPlatformAndVersion() = runBlocking<Unit> {
        val dto = api.appStatus(BuildConfig.VERSION_NAME)
        assertTrue("update = ${dto.update}", dto.update in setOf("none", "available", "required"))
        assertTrue("update_url = ${dto.updateUrl}", dto.updateUrl.orEmpty().startsWith("https://"))
        assertTrue("Android update_url should point at Play: ${dto.updateUrl}", "com.arcu.attnd" in dto.updateUrl.orEmpty())
        val update = ApiMapper.appUpdate(dto)
        assertEquals(dto.updateUrl, update.updateUrl)
        // The build speaks a supported API version, so the server only asks it to update for min_version.
        if (dto.minVersion == null) assertTrue(update.level != UpdateLevel.REQUIRED)
    }

    // ── 2. lookup + authentication ──────────────────────────────────────────

    @Test
    fun lookup_codeInAnyCaseAndWhitespace_findsDemo() = runBlocking<Unit> {
        val ids = listOf("DEMO", "demo", " Demo ").map { query ->
            val event = ApiMapper.event(api.lookup(query).event)
            assertNotNull("lookup('$query') returned no event", event)
            assertEquals("DEMO", event!!.eventCode)
            event.id
        }
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun lookup_unknownCode_is404() = runBlocking<Unit> {
        expectApiError(404) { api.lookup("CI-NOPE-${UUID.randomUUID().toString().take(8)}") }
    }

    @Test
    fun lookup_withoutAuthorization_is401() = runBlocking<Unit> {
        // The real HTTP client with no Authorization header at all.
        val response = LiveServer.http.execute(
            "GET", LiveServer.baseUrl() + "events/lookup/?q=DEMO", mapOf("Accept" to "application/json"), null,
        )
        assertEquals(401, response.code)
        // An empty token (what the app sends with no JWT and no static key) is rejected the same way.
        expectApiError(401) { LiveServer.api(token = "").lookup(LiveServer.DEMO_CODE) }
    }

    @Test
    fun lookup_wrongKey_is401_andNotRetriedWithoutReattestation() = runBlocking<Unit> {
        val tokens = LiveServer.Tokens("ci-wrong-key-${UUID.randomUUID()}")
        val wrong = AttndApi(tokens, LiveServer.baseUrl(), LiveServer.http)
        expectApiError(401) { wrong.lookup(LiveServer.DEMO_CODE) }
        assertEquals("a 401 asks the provider to re-attest once", 1, tokens.reauthCount)
        assertFalse(wrong.updateRequired.value)
    }

    @Test
    fun lookup_wrongKey_thenReattestation_retriesOnceAndSucceeds() = runBlocking<Unit> {
        val tokens = LiveServer.Tokens("ci-wrong-key-${UUID.randomUUID()}", fresh = LiveServer.key)
        val api = AttndApi(tokens, LiveServer.baseUrl(), LiveServer.http)
        assertEquals("DEMO", api.lookup(LiveServer.DEMO_CODE).event?.eventCode)
        assertEquals(1, tokens.reauthCount)
    }

    // ── 3. bundle: ETag / If-None-Match ─────────────────────────────────────

    @Test
    fun bundle_etag_conditionalGet() = runBlocking<Unit> {
        val id = LiveServer.demo.id
        val first = api.bundle(id, etag = null, deviceId = null)
        assertEquals(200, first.code)
        assertNotNull("bundle has a body", first.json)
        val etag = first.etag
        assertNotNull("bundle response carries an ETag", etag)

        val unchanged = api.bundle(id, etag = etag, deviceId = null)
        assertEquals("same ETag → 304", 304, unchanged.code)
        assertTrue("304 has no body", unchanged.json.isNullOrEmpty())

        val stale = api.bundle(id, etag = "\"e$id-v0\"", deviceId = null)
        assertEquals("stale ETag → full bundle", 200, stale.code)
        assertNotNull(ApiMapper.parseBundle(stale.json!!))

        val garbage = api.bundle(id, etag = "not-an-etag-${UUID.randomUUID()}", deviceId = null)
        assertEquals("garbage If-None-Match → full bundle", 200, garbage.code)
        assertNotNull(ApiMapper.parseBundle(garbage.json!!))
    }

    @Test
    fun bundle_withDeviceIdHeader_isAccepted() = runBlocking<Unit> {
        assertEquals(200, api.bundle(LiveServer.demo.id, etag = null, deviceId = LiveServer.newDeviceId()).code)
    }

    @Test
    fun bundle_unknownEvent_is404() = runBlocking<Unit> {
        assertEquals(404, api.bundle(999_999_999, etag = null, deviceId = null).code)
    }

    // ── 4. join / leave ─────────────────────────────────────────────────────

    @Test
    fun joinTwice_thenLeaveTwice_areOk() = runBlocking<Unit> {
        val id = LiveServer.demo.id
        val deviceId = LiveServer.newDeviceId()
        joined += id to deviceId
        val request = JoinRequest(deviceId = deviceId, appVersion = BuildConfig.VERSION_NAME)

        val first = api.join(id, request)
        assertEquals("ok", first.status)
        assertTrue("join returns the content_version", (first.contentVersion ?: 0) >= 1)
        assertEquals("join is idempotent", "ok", api.join(id, request).status)

        assertEquals("ok", api.leave(id, LeaveRequest(deviceId)).status)
        assertEquals("leaving again is still ok", "ok", api.leave(id, LeaveRequest(deviceId)).status)
    }

    // ── 5. devices ──────────────────────────────────────────────────────────

    @Test
    fun devices_registerWithToken_thenWithoutToken() = runBlocking<Unit> {
        val deviceId = LiveServer.newDeviceId()
        val token = "ci-${UUID.randomUUID()}"

        val registered = api.registerDevice(
            DeviceRequest(deviceId = deviceId, pushToken = token, appVersion = BuildConfig.VERSION_NAME, subscribed = false),
        )
        assertEquals("ok", registered.status)
        assertEquals(false, registered.subscribed)
        assertNull("a device that never joined has no event", registered.eventId)

        // Joined devices keep their event when devices/ is called without one.
        val id = LiveServer.demo.id
        joined += id to deviceId
        assertEquals("ok", api.join(id, JoinRequest(deviceId = deviceId)).status)

        // No token: the key is left out (never ""), and the rest of the device is unchanged.
        val again = api.registerDevice(DeviceRequest(deviceId = deviceId, appVersion = BuildConfig.VERSION_NAME))
        assertEquals("ok", again.status)
        assertEquals("subscribed is unchanged when omitted", false, again.subscribed)
        assertEquals("devices/ doesn't change the joined event", id, again.eventId)

        val body = ApiJson.encodeToString(DeviceRequest.serializer(), DeviceRequest(deviceId = deviceId))
        assertFalse("no push_token key without a token: $body", "push_token" in body)
        LiveServer.sentBodies.toList().forEach { sent ->
            assertFalse("never send an empty push_token: $sent", Regex("\"push_token\"\\s*:\\s*\"\"").containsMatchIn(sent))
        }
    }

    // ── 6. usage (API-005) ──────────────────────────────────────────────────

    @Test
    fun usage_validBatch_isAccepted() = runBlocking<Unit> {
        val aggregator = UsageAggregator()
        aggregator.add(UsageMetric.APP_OPEN, at = Instant.now())
        aggregator.add(UsageMetric.TAB_VIEW, detail = "map", at = Instant.now(), count = 2)
        val entries = aggregator.drain().map { UsageEntryDto(it.name, it.detail, it.count, it.at) }

        val response = api.sendUsage(LiveServer.demo.id, UsageRequest(events = entries))
        assertEquals("ok", response.status)
        assertEquals(entries.size, response.accepted)
    }

    @Test
    fun usage_invalidEntries_areDroppedNot400() = runBlocking<Unit> {
        // API_V2.md: unknown names and invalid details are dropped silently; the request still succeeds.
        val entries = listOf(
            UsageEntryDto(name = "ci_not_a_metric"),
            UsageEntryDto(name = UsageMetric.TAB_VIEW, detail = "ci_not_a_tab"),
            UsageEntryDto(name = UsageMetric.LOCATION_VIEW, detail = "999999999"),
        )
        val response = api.sendUsage(LiveServer.demo.id, UsageRequest(events = entries))
        assertEquals("ok", response.status)
        assertEquals(0, response.accepted)

        // A malformed `at` is not a reason to fail either (the server counts it now).
        val badTime = api.sendUsage(
            LiveServer.demo.id, UsageRequest(events = listOf(UsageEntryDto(name = UsageMetric.APP_OPEN, at = "not-a-time"))),
        )
        assertEquals("ok", badTime.status)
    }

    @Test
    fun usage_malformedBody_stillReturns200() = runBlocking<Unit> {
        val response = LiveServer.http.execute(
            "POST",
            LiveServer.baseUrl() + "events/${LiveServer.demo.id}/usage/",
            mapOf("Authorization" to "Bearer ${LiveServer.key}", "Accept" to "application/json"),
            "{not json",
        )
        assertEquals(200, response.code)
        assertEquals("0", LiveServer.parse(response.body!!).jsonObject["accepted"]?.jsonPrimitive?.content)
    }

    @Test
    fun usage_emptyBatch_isANoOp() = runBlocking<Unit> {
        val response = api.sendUsage(LiveServer.demo.id, UsageRequest(events = emptyList()))
        assertEquals("ok", response.status)
        assertEquals(0, response.accepted)
    }

    // ── 7. retired API version ──────────────────────────────────────────────

    @Test
    fun retiredApiVersion_is410UpdateRequired() = runBlocking<Unit> {
        val retired = LiveServer.api(apiVersion = 9)
        expectApiError(410) { retired.lookup(LiveServer.DEMO_CODE) }
        assertTrue("410 flags updateRequired", retired.updateRequired.value)

        val raw = LiveServer.http.execute(
            "GET",
            LiveServer.baseUrl(apiVersion = 9) + "events/lookup/?q=DEMO",
            mapOf("Authorization" to "Bearer ${LiveServer.key}", "Accept" to "application/json"),
            null,
        )
        assertEquals(410, raw.code)
        val json = LiveServer.parse(raw.body!!).jsonObject
        assertEquals("update_required", json["error"]?.jsonPrimitive?.content)
        assertTrue(json["update_url"]?.jsonPrimitive?.content.orEmpty().startsWith("https://"))
    }
}
