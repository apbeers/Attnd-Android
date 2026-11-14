package com.arcu.attnd.unit

import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.AuthTokenProvider
import com.arcu.attnd.data.api.DeviceRequest
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.data.net.HttpClient
import com.arcu.attnd.data.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The HTTP layer against a scripted fake server: auth retry, conditional GET, JSON bodies. */
class AttndApiClientTest {

    private class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private class FakeHttp(vararg responses: HttpResponse) : HttpClient {
        val requests = mutableListOf<Request>()
        private val queue = ArrayDeque(responses.toList())
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            requests += Request(method, url, headers, body)
            return queue.removeFirst()
        }
    }

    private class FakeTokens(private val fresh: String?) : AuthTokenProvider {
        var reauthCount = 0
        override suspend fun token() = "old-token"
        override suspend fun reauthenticate(): String? {
            reauthCount++
            return fresh
        }
    }

    private fun response(code: Int, body: String? = null, headers: Map<String, List<String>> = emptyMap()) =
        HttpResponse(code, body, headers)

    private val okStatus = """{"status": "ok", "content_version": 15, "extra": true}"""

    @Test
    fun unauthorized_reattestsAndRetriesOnce() = runTest {
        val http = FakeHttp(response(401), response(200, okStatus))
        val tokens = FakeTokens(fresh = "new-token")
        val api = AttndApi(tokens, "https://example.test/api/v2/", http)

        val status = api.join(1, JoinRequest(deviceId = "dev-1"))

        assertEquals("ok", status.status)
        assertEquals(1, tokens.reauthCount)
        assertEquals(listOf("Bearer old-token", "Bearer new-token"), http.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun unauthorized_withoutFreshToken_givesUp() = runTest {
        val http = FakeHttp(response(401))
        val api = AttndApi(FakeTokens(fresh = null), "https://example.test/api/v2/", http)
        try {
            api.lookup("FWFM2026")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun secondUnauthorized_isNotRetriedAgain() = runTest {
        val http = FakeHttp(response(401), response(401))
        val api = AttndApi(FakeTokens(fresh = "new-token"), "https://example.test/api/v2/", http)
        try {
            api.lookup("x")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
        assertEquals(2, http.requests.size)
    }

    @Test
    fun bundle_sendsConditionalHeaders_andHandles304() = runTest {
        val http = FakeHttp(
            response(200, """{"event": {"id": 1}}""", mapOf("etag" to listOf("\"v15\""))),
            response(304),
        )
        val api = AttndApi(FakeTokens(null), "https://example.test/api/v2/", http)

        val first = api.bundle(1, etag = null, deviceId = "dev-1")
        assertEquals(200, first.code)
        assertEquals("\"v15\"", first.etag)
        assertFalse(http.requests[0].headers.containsKey("If-None-Match"))
        assertEquals("dev-1", http.requests[0].headers["X-Device-Id"])
        assertEquals("https://example.test/api/v2/events/1/bundle/", http.requests[0].url)

        val second = api.bundle(1, etag = first.etag, deviceId = "dev-1")
        assertEquals(304, second.code)
        assertNull(second.json)
        assertEquals("\"v15\"", http.requests[1].headers["If-None-Match"])
    }

    @Test
    fun requests_encodeQueryAndJsonBodies() = runTest {
        val http = FakeHttp(
            response(200, """{"event": {"id": 2, "name": "Expo"}}"""),
            response(200, okStatus),
            response(200, """{"count": 0, "results": []}"""),
        )
        val api = AttndApi(FakeTokens(null), "https://example.test/api/v2/", http)

        assertEquals(2, api.lookup("Food & Music").event?.id)
        assertEquals("https://example.test/api/v2/events/lookup/?q=Food+%26+Music", http.requests[0].url)

        api.registerDevice(DeviceRequest(deviceId = "dev-1", subscribed = true))
        val body = http.requests[1].body!!
        assertEquals("POST", http.requests[1].method)
        assertTrue(body, body.contains("\"device_id\":\"dev-1\""))
        assertTrue(body, body.contains("\"platform\":\"android\""))
        assertTrue(body, body.contains("\"subscribed\":true"))
        assertFalse("null optionals are omitted: $body", body.contains("push_token"))

        assertTrue(api.nearbyEvents(32.75, -97.33).results!!.isEmpty())
        assertEquals("https://example.test/api/v2/events/nearby/?lat=32.75&lng=-97.33", http.requests[2].url)
    }

    @Test
    fun errorStatus_throwsApiException() = runTest {
        val api = AttndApi(FakeTokens(null), "https://example.test/api/v2/", FakeHttp(response(404, """{"detail": "nope"}""")))
        try {
            api.lookup("missing")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
        }
    }

    // ── API-003: app status / retired API versions ─────────────────────────

    @Test
    fun appStatus_isUnauthenticatedAndOutsideTheVersionedPath() = runTest {
        val http = FakeHttp(response(200, """{"update": "required", "update_url": "https://play.google.com/x", "new": 1}"""))
        val api = AttndApi(FakeTokens(fresh = null), "https://example.test/api/v2/", http)

        val status = api.appStatus("2.0.5")

        assertEquals("required", status.update)
        val request = http.requests.single()
        assertEquals("https://example.test/api/app-status/?platform=android&version=2.0.5&api=2", request.url)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun goneOrUpgradeRequired_flagsUpdateRequired() = runTest {
        val http = FakeHttp(response(410, """{"error": "update_required"}"""))
        val api = AttndApi(FakeTokens(fresh = null), "https://example.test/api/v2/", http)
        assertFalse(api.updateRequired.value)
        try {
            api.lookup("CODE")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(410, e.code)
        }
        assertTrue(api.updateRequired.value)
    }
}
