package com.arcu.attnd.unit

import com.arcu.attnd.domain.JoinErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** API-025: the free limit's 409 shows the server's message (attnd-web API_V2.md → Join). */
class JoinErrorsTest {
    @Test
    fun eventFullShowsTheServersMessage() {
        val message = "This event is full for now: its free trial takes 20 people. Ask the organizer to remove the limit, then try again."
        assertEquals(message, JoinErrors.message(409, "event_full_free", message))
        assertEquals(JoinErrors.FULL_FALLBACK, JoinErrors.message(409, "event_full_free", "  "))
        assertEquals(JoinErrors.FULL_FALLBACK, JoinErrors.message(409, "event_full_free", null))
        assertEquals(300, JoinErrors.message(409, "event_full_free", "x".repeat(400))!!.length)
    }

    @Test
    fun otherErrorsKeepTheAppsText() {
        assertNull(JoinErrors.message(409, "something_else", "Nope"))
        assertNull(JoinErrors.message(404, null, null))
        assertNull(JoinErrors.message(500, "event_full_free", "x"))
    }
}
