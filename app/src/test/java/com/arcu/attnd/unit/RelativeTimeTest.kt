package com.arcu.attnd.unit

import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.NearbyEvent
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.unit.GlanceFixture.bundle
import com.arcu.attnd.unit.GlanceFixture.item
import com.arcu.attnd.unit.GlanceFixture.now
import com.arcu.attnd.unit.GlanceFixture.sat
import com.arcu.attnd.unit.GlanceFixture.sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The shared relative-time spec (same decisions as iOS). Event zone America/Chicago, en_US; "now" is
 * Sat Nov 14, 2026, 2:00 PM local.
 */
class RelativeTimeTest {

    private val zone = ZoneId.of("America/Chicago")
    private val locale = Locale.US

    private fun rel(start: OffsetDateTime, end: OffsetDateTime? = null, at: Instant = now, showEnd: Boolean = false) =
        RelativeTime.relativeStart(start.toInstant(), end?.toInstant(), at, zone, locale, showEnd)

    private fun local(date: String, time: String) = EventTime.parse("${date}T$time:00-06:00")!!

    @Test
    fun ended_includingExactlyNow_andTheDefaultHour() {
        assertEquals("Ended", rel(sat("12:00"), sat("13:59")))
        assertEquals("Ended", rel(sat("12:00"), sat("14:00")))
        // No end, or an end not after the start: start + 1 h.
        assertEquals("Ended", rel(sat("13:00")))
        assertEquals("Ended", rel(sat("12:30"), sat("12:30")))
        assertEquals("Started 30 min ago", rel(sat("13:30")))
        assertEquals("Started 30 min ago", rel(sat("13:30"), sat("13:00")))
    }

    @Test
    fun started() {
        assertEquals("Started just now", rel(sat("14:00"), sat("15:00")))
        assertEquals("Started just now", rel(OffsetDateTime.parse("2026-11-14T13:59:01-06:00"), sat("15:00")))
        assertEquals("Started 1 min ago", rel(OffsetDateTime.parse("2026-11-14T13:58:30-06:00"), sat("15:00")))
        assertEquals("Started 20 min ago", rel(sat("13:40"), sat("15:00")))
        assertEquals("Started 59 min ago", rel(sat("13:01"), sat("15:00")))
        assertEquals("Now", rel(sat("13:00"), sat("15:00")))
    }

    @Test
    fun started_longFormAddsTheEnd() {
        assertEquals("Now · ends 3:00 PM", rel(sat("12:00"), sat("15:00"), showEnd = true))
        assertEquals("Started 20 min ago · ends 3:00 PM", rel(sat("13:40"), sat("15:00"), showEnd = true))
        assertEquals("Started just now · ends 3:00 PM", rel(sat("14:00"), sat("15:00"), showEnd = true))
        // The default hour, and an end on another day.
        assertEquals("Started 30 min ago · ends 2:30 PM", rel(sat("13:30"), showEnd = true))
        assertEquals("Now · ends Sun 1:00 AM", rel(sat("12:00"), sun("01:00"), showEnd = true))
        // Future and ended forms never add it.
        assertEquals("in 30 min", rel(sat("14:30"), sat("15:00"), showEnd = true))
        assertEquals("Ended", rel(sat("12:00"), sat("13:00"), showEnd = true))
    }

    @Test
    fun withinTheHour_minutesRoundDown() {
        assertEquals("Starting now", OffsetDateTime.parse("2026-11-14T14:00:30-06:00").let { rel(it) })
        assertEquals("in 1 min", rel(sat("14:01")))
        assertEquals("in 1 min", OffsetDateTime.parse("2026-11-14T14:01:59-06:00").let { rel(it) })
        assertEquals("in 12 min", rel(sat("14:12")))
        assertEquals("in 59 min", rel(sat("14:59")))
    }

    @Test
    fun hoursUnderSix_dropZeroMinutes() {
        assertEquals("in 1 h", rel(sat("15:00")))
        assertEquals("in 3 h 20 min", rel(sat("17:20")))
        assertEquals("in 3 h", rel(sat("17:00")))
        assertEquals("in 5 h 59 min", rel(sat("19:59")))
    }

    @Test
    fun laterTodayAndTomorrow_neverHoursOfSixOrMore() {
        assertEquals("today 8:00 PM", rel(sat("20:00")))
        assertEquals("today 9:30 PM", rel(sat("21:30")))
        assertEquals("tomorrow 7:30 PM", rel(sun("19:30")))
        assertEquals("tomorrow 6:00 AM", rel(sun("06:00")))
    }

    @Test
    fun calendarDaysInTheEventZone() {
        // 11:30 PM Saturday local is already Sunday in UTC.
        val lateSat = Instant.parse("2026-11-15T05:30:00Z")
        assertEquals("in 1 h", rel(sun("00:30"), at = lateSat))
        // Same UTC day, but the next day in Chicago.
        assertEquals("tomorrow 7:00 AM", rel(sun("07:00"), at = lateSat))
    }

    @Test
    fun daysWeeksMonthsYears() {
        assertEquals("in 2 days", rel(local("2026-11-16", "09:00")))
        assertEquals("in 3 days", rel(local("2026-11-17", "09:00")))
        assertEquals("in 6 days", rel(local("2026-11-20", "23:00")))
        assertEquals("in 1 week", rel(local("2026-11-21", "09:00")))
        assertEquals("in 1 week", rel(local("2026-11-27", "09:00")))
        assertEquals("in 2 weeks", rel(local("2026-11-28", "09:00")))
        assertEquals("in 4 weeks", rel(local("2026-12-18", "09:00")))
        assertEquals("in 1 month", rel(local("2026-12-19", "09:00")))
        assertEquals("in 4 months", rel(local("2027-03-14", "09:00")))
        assertEquals("in 11 months", rel(local("2027-11-13", "09:00")))
        assertEquals("in 1 year", rel(local("2027-11-14", "09:00")))
        assertEquals("in 2 years", rel(local("2028-12-01", "09:00")))
    }

    @Test
    fun forItem() {
        assertEquals("Ended", RelativeTime.forItem(item(1, "Short", sat("12:30")), now, zone, locale))
        assertEquals("Started 30 min ago", RelativeTime.forItem(item(2, "Open", sat("13:30")), now, zone, locale))
        assertNull(RelativeTime.forItem(item(3, "TBA", null), now, zone, locale))
        assertEquals("Now · ends 3:00 PM", RelativeTime.forItem(bundle.scheduleItem(1)!!, now, zone, locale, showEnd = true))
    }

    @Test
    fun nearby_startsInHoursBecomesADate_andLastsUntilTheEndOfItsFirstDay() {
        // The "thousands of hours" bug: 1000 h is about six weeks away.
        val noTimes = bundle.event.copy(startTime = null, endTime = null)
        assertEquals("in 1 month", RelativeTime.forNearby(NearbyEvent(noTimes, 1.2, 1000.0), now, locale))
        assertEquals("in 2 h", RelativeTime.forNearby(NearbyEvent(noTimes, 1.2, 2.0), now, locale))
        assertNull(RelativeTime.forNearby(NearbyEvent(noTimes, 1.2, null), now, locale))
        // Started 3 h ago, no end: on until midnight.
        assertEquals("Now", RelativeTime.forNearby(NearbyEvent(noTimes, 1.2, -3.0), now, locale))
        val noEnd = bundle.event.copy(startTime = sat("10:00"), endTime = null)
        assertEquals("Now", RelativeTime.forNearby(NearbyEvent(noEnd, 0.4, null), sat("23:59").toInstant(), locale))
        assertEquals("Ended", RelativeTime.forNearby(NearbyEvent(noEnd, 0.4, null), sun("00:00").toInstant(), locale))
        // The event's own end wins.
        assertEquals("Now", RelativeTime.forNearby(NearbyEvent(bundle.event, 0.4, null), sun("12:00").toInstant(), locale))
        assertEquals("Ended", RelativeTime.forNearby(NearbyEvent(bundle.event, 0.4, null), Instant.parse("2026-11-17T00:00:00Z"), locale))
    }
}
