package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiJson
import com.arcu.attnd.data.api.ChangesResponseDto
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.api.StatusResponseDto
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.PreviewPoll
import com.arcu.attnd.domain.PreviewPoll.Next
import com.arcu.attnd.domain.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** API-026 preview phones: iOS's `testPreviewPoll` and `testPreviewToken_LinksAndJoinBody` cases, plus Android's rules. */
class PreviewPhoneTest {

    private fun answer(json: String): PreviewPoll.Answer {
        val dto = ApiJson.decodeFromString(ChangesResponseDto.serializer(), json)
        return PreviewPoll.Answer.of(dto.contentVersion, dto.changed, dto.preview, dto.retryAfter)
    }

    @Test
    fun poll_nextStep() {
        assertEquals(Next.Wait(0), PreviewPoll.next(answer("""{"content_version": 8, "changed": false}"""), since = 8))
        assertEquals(Next.Refresh, PreviewPoll.next(answer("""{"content_version": 9}"""), since = 8))
        assertEquals(Next.Refresh, PreviewPoll.next(answer("""{"changed": true}"""), since = 8))
        assertEquals(Next.Wait(15), PreviewPoll.next(answer("""{"retry_after": 15}"""), since = 8))
        assertEquals(Next.Wait(3600), PreviewPoll.next(answer("""{"retry_after": 99999}"""), since = 8))
        assertEquals(Next.Stop, PreviewPoll.next(answer("""{"preview": false, "changed": true}"""), since = 8))
        // Android: a negative retry_after is "at once"; no bundle yet means any version is newer; unknown keys are ignored.
        assertEquals(Next.Wait(0), PreviewPoll.next(answer("""{"retry_after": -4, "extra": 1}"""), since = 8))
        assertEquals(Next.Refresh, PreviewPoll.next(answer("""{"content_version": 3}"""), since = null))
        assertEquals(Next.Wait(0), PreviewPoll.next(answer("""{"content_version": 7}"""), since = 8))
        assertEquals(Next.Stop, PreviewPoll.next(answer("""{"preview": false, "retry_after": 300}"""), since = 8))
        assertEquals(15_000L, PreviewPoll.ERROR_WAIT_MS)
    }

    @Test
    fun poll_runsOnlyResumedForTheShownEventAndNeverInScreenshots() {
        assertTrue(PreviewPoll.runs(resumed = true, previewEventId = 5, shownEventId = 5, screenshot = false))
        assertFalse(PreviewPoll.runs(resumed = false, previewEventId = 5, shownEventId = 5, screenshot = false))
        assertFalse(PreviewPoll.runs(resumed = true, previewEventId = 5, shownEventId = 6, screenshot = false))
        assertFalse(PreviewPoll.runs(resumed = true, previewEventId = null, shownEventId = 5, screenshot = false))
        assertFalse(PreviewPoll.runs(resumed = true, previewEventId = 5, shownEventId = 5, screenshot = true))
        assertTrue(PreviewPoll.showsCapsule(5, 5, screenshot = false))
        assertFalse(PreviewPoll.showsCapsule(5, null, screenshot = false))
        assertFalse(PreviewPoll.showsCapsule(5, 5, screenshot = true))
    }

    @Test
    fun joinAnswer_setsKeepsOrEndsThePreview() {
        assertEquals(5, PreviewPoll.afterJoin(stored = null, eventId = 5, preview = true))
        // The 4th preview phone joins as an ordinary attendee: `preview: false`.
        assertNull(PreviewPoll.afterJoin(stored = 5, eventId = 5, preview = false))
        assertNull(PreviewPoll.afterJoin(stored = null, eventId = 5, preview = false))
        // An ordinary rejoin (no key) keeps it.
        assertEquals(5, PreviewPoll.afterJoin(stored = 5, eventId = 5, preview = null))
        assertEquals(5, PreviewPoll.afterJoin(stored = 5, eventId = 6, preview = false))
        assertEquals(true, ApiJson.decodeFromString(StatusResponseDto.serializer(), """{"status":"ok","content_version":15,"preview":true}""").preview)
        assertNull(ApiJson.decodeFromString(StatusResponseDto.serializer(), """{"status":"ok"}""").preview)
    }

    @Test
    fun previewToken_fromJoinLinksAndScans() {
        assertEquals("tok", JoinLinks.previewToken("https://attnd.app/join/DEMO/?preview=tok"))
        assertEquals("abc.DEF-1_2:z", JoinLinks.previewToken("https://attnd.app/join/DEMO/?utm=qr&preview=abc.DEF-1_2:z"))
        assertNull(JoinLinks.previewToken("https://attnd.app/join/DEMO/?preview=%3Cb%3E"))
        assertNull(JoinLinks.previewToken("https://attnd.app/join/DEMO/"))
        assertNull(JoinLinks.previewToken("https://attnd.app/join/DEMO/?preview="))
        assertEquals("tok", JoinLinks.previewToken(" https://attnd.app/join/DEMO/?preview=tok \n"))
        assertNull(JoinLinks.previewToken("DEMO"))
        assertNull(JoinLinks.previewToken("https://example.com/join/DEMO/?preview=tok"))
        assertNull(JoinLinks.previewToken("https://attnd.app/join/DEMO/?preview=" + "a".repeat(513)))
        assertEquals("a".repeat(512), JoinLinks.previewToken("https://attnd.app/join/DEMO/?preview=" + "a".repeat(512)))
        // The code is still read from a preview link.
        assertEquals("DEMO", JoinLinks.parseJoinCode("https://attnd.app/join/DEMO/?preview=tok"))
    }

    @Test
    fun joinBody_sendsTheTokenOnlyWhenThereIsOne() {
        val withToken = ApiJson.encodeToString(JoinRequest.serializer(), JoinRequest(deviceId = "d", appVersion = "1", previewToken = "tok"))
        assertTrue(withToken, withToken.contains("\"preview_token\":\"tok\""))
        val plain = ApiJson.encodeToString(JoinRequest.serializer(), JoinRequest(deviceId = "d", appVersion = "1"))
        assertFalse(plain, plain.contains("preview_token"))
        assertFalse("never sent as empty", plain.contains("push_token"))
    }

    @Test
    fun token_neverLogged() {
        assertEquals("/join/{id}/", Redact.path("https://attnd.app/join/ABC123/?preview=abc.DEF-1_2:z"))
        assertEquals("opened attnd.app/join/DEMO/?preview=[redacted]", Redact.message("opened attnd.app/join/DEMO/?preview=abc.DEF-1_2:z"))
        assertEquals("preview_token=[redacted]&x=1", Redact.message("preview_token=abc123&x=1"))
        assertTrue(Redact.isSensitiveKey("preview_token"))
        assertEquals(emptyMap<String, Any?>(), Redact.fields(mapOf("preview_token" to "abc")))
        // "preview" as a word stays readable.
        assertEquals("preview: true", Redact.message("preview: true"))
    }
}
