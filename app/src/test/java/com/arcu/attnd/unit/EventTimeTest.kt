package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.EventTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class EventTimeTest {

    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun formatsInEventZone() {
        val start = EventTime.parse("2026-11-15T03:30:00+00:00")
        assertEquals("9:30 PM", EventTime.formatTime(start, chicago, Locale.US))
        assertEquals("Sat 9:30 PM", EventTime.formatDayTime(start, chicago, Locale.US))
        assertEquals("12:30 PM", EventTime.formatTime(start, ZoneId.of("Asia/Tokyo"), Locale.US))
    }

    @Test
    fun timeRange() {
        val start = EventTime.parse("2026-11-14T22:00:00Z")
        val sameDayEnd = EventTime.parse("2026-11-14T23:30:00Z")
        val nextDayEnd = EventTime.parse("2026-11-15T07:00:00Z")
        assertEquals("4:00 PM – 5:30 PM", EventTime.formatTimeRange(start, sameDayEnd, chicago, Locale.US))
        assertEquals("4:00 PM – Sun 1:00 AM", EventTime.formatTimeRange(start, nextDayEnd, chicago, Locale.US))
        assertEquals("4:00 PM", EventTime.formatTimeRange(start, null, chicago, Locale.US))
        assertEquals("Time TBA", EventTime.formatTimeRange(null, null, chicago, Locale.US))
    }

    @Test
    fun parse_acceptsOffsetsAndRejectsGarbage() {
        assertEquals(Instant.parse("2026-11-14T16:00:00Z"), EventTime.parse("2026-11-14T10:00:00-06:00")!!.toInstant())
        assertEquals(Instant.parse("2026-11-14T16:00:00Z"), EventTime.parse("2026-11-14T16:00:00Z")!!.toInstant())
        assertNull(EventTime.parse("tomorrow"))
        assertNull(EventTime.parse(""))
        assertNull(EventTime.parse(null))
    }

    @Test
    fun zoneOf_fallsBack() {
        val fallback = ZoneId.of("UTC")
        assertEquals(chicago, EventTime.zoneOf("America/Chicago", fallback))
        assertEquals(fallback, EventTime.zoneOf("Not/AZone", fallback))
        assertEquals(fallback, EventTime.zoneOf(null, fallback))
    }

    @Test
    fun isEventDay_usesEventZoneAndEndDate() {
        // Event: Nov 14 10:00 CST to Nov 15 22:00 CST.
        val event = ApiMapper.parseBundle(SampleBundles.FULL)!!.event
        // Nov 16 03:00 UTC is still Nov 15 in Chicago.
        assertTrue(EventTime.isEventDay(event, Instant.parse("2026-11-16T03:00:00Z")))
        assertTrue(EventTime.isEventDay(event, Instant.parse("2026-11-14T12:00:00Z")))
        assertFalse(EventTime.isEventDay(event, Instant.parse("2026-11-13T12:00:00Z")))
        assertFalse(EventTime.isEventDay(event, Instant.parse("2026-11-16T12:00:00Z")))
    }

    @Test
    fun startsInText() {
        val now = Instant.parse("2026-11-14T16:00:00Z")
        assertEquals("Starts in 1h 30m", EventTime.startsInText(EventTime.parse("2026-11-14T17:30:00Z"), now))
        assertEquals("Starts in 2h", EventTime.startsInText(EventTime.parse("2026-11-14T18:00:00Z"), now))
        assertEquals("Starts in 5m", EventTime.startsInText(EventTime.parse("2026-11-14T16:05:00Z"), now))
        assertNull(EventTime.startsInText(EventTime.parse("2026-11-14T15:00:00Z"), now))
        assertNull(EventTime.startsInText(EventTime.parse("2026-11-20T15:00:00Z"), now))
    }
}
