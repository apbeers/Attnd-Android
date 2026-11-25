package com.arcu.attnd.unit

import com.arcu.attnd.domain.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logging spec §1 redaction: never text, tokens, emails or ids; URLs as path templates; rounded coordinates. */
class LogRedactionTest {

    @Test
    fun path_templatesIdsAndDropsHostAndQuery() {
        assertEquals("/api/v2/events/{id}/bundle/", Redact.path("https://attnd.app/api/v2/events/5/bundle/"))
        assertEquals("/api/v2/events/lookup/", Redact.path("https://dev.attnd.app/api/v2/events/lookup/?q=DEMO"))
        assertEquals("/api/v2/events/nearby/", Redact.path("https://attnd.app/api/v2/events/nearby/?lat=41.88&lng=-87.63"))
        assertEquals("/api/app-status/", Redact.path("https://attnd.app/api/app-status/?platform=android&version=2.0.1&api=2"))
        assertEquals("/api/v2/diagnostics/logs/", Redact.path("https://attnd.app/api/v2/diagnostics/logs/"))
        assertEquals("/join/{id}/", Redact.path("https://attnd.app/join/ABC123/"))
        assertEquals("/devices/{id}", Redact.path("/devices/3f2a9c1b-1d2e-4f5a-8b6c-7d8e9f0a1b2c#x"))
        assertEquals("/images/{id}", Redact.path("https://cdn.example/images/a8f3e9b2c4d1e0f7a6b5"))
        assertEquals("/", Redact.path("https://attnd.app"))
    }

    @Test
    fun text_onlyLengthsInProduction_textInDevBuilds() {
        assertEquals(mapOf("query_len" to 12), Redact.textFields("query", "tacos tonite", allowText = false))
        assertEquals(mapOf("query_len" to 12, "query" to "tacos tonite"), Redact.textFields("query", "tacos tonite", allowText = true))
        assertEquals(mapOf("query_len" to 0), Redact.textFields("query", null, allowText = true))
        // Even in dev builds, typed text is masked and cut.
        assertEquals("mail [email]", Redact.textFields("query", "mail me@example.com", allowText = true)["query"])
        assertEquals(Redact.MAX_TEXT, (Redact.textFields("query", "word ".repeat(100), allowText = true)["query"] as String).length)
    }

    @Test
    fun message_masksEmailsTokensJwtsUuidsAndUrls() {
        assertEquals("sent to [email]", Redact.message("sent to Ann.Lee+x@example.co.uk"))
        assertEquals("Authorization: Bearer [redacted]", Redact.message("Authorization: Bearer abcdef0123456789"))
        assertEquals("token=[redacted] ok", Redact.message("token=f1e2d3c4b5a6 ok"))
        assertEquals("jwt [jwt]", Redact.message("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"))
        assertEquals("device [uuid]", Redact.message("device 3f2a9c1b-1d2e-4f5a-8b6c-7d8e9f0a1b2c"))
        assertEquals("fcm [token]", Redact.message("fcm dQw4w9WgXcQ:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx"))
        assertEquals("GET /api/v2/events/{id}/bundle/ failed", Redact.message("GET https://attnd.app/api/v2/events/7/bundle/?x=1 failed"))
        // Ordinary text and stack frames survive.
        val frame = "at com.arcu.attnd.data.EventRepository.refresh(EventRepository.kt:212)"
        assertEquals(frame, Redact.message(frame))
        assertEquals("GET /api/v2/events/{id}/bundle/ 304 in 182 ms", Redact.message("GET /api/v2/events/{id}/bundle/ 304 in 182 ms"))
        assertEquals("Prompt tokens 320", Redact.message("Prompt tokens 320"))
    }

    @Test
    fun message_isCut() {
        assertTrue(Redact.message("a ".repeat(2_000)).length <= 1_001)
    }

    @Test
    fun fields_dropSensitiveKeys_keepLengths() {
        val out = Redact.fields(
            mapOf(
                "push_token" to "x", "pushToken" to "x", "Authorization" to "Bearer x", "api_key" to "k",
                "device_id" to "d", "support_code" to "3F2A9C1B", "email" to "a@b.cc", "jwt" to "j",
                "query_len" to 5, "status" to 304, "req_id" to "0123456789abcdef",
                "url" to "https://attnd.app/api/v2/events/5/join/?a=b", "note" to "for a@b.cc",
            )
        )
        assertEquals(setOf("query_len", "status", "req_id", "url", "note"), out.keys)
        assertEquals("/api/v2/events/{id}/join/", out["url"])
        assertEquals("for [email]", out["note"])
        assertEquals("0123456789abcdef", out["req_id"])
        assertFalse(Redact.isSensitiveKey("text_len"))
        assertTrue(Redact.isSensitiveKey("live_activity_token"))
    }

    @Test
    fun coordinate_roundedToOneHundredth() {
        assertEquals(41.88, Redact.coordinate(41.878114), 0.0)
        assertEquals(-87.63, Redact.coordinate(-87.629798), 0.0)
        assertEquals(0.01, Redact.coordinate(0.005), 0.0)
    }
}
