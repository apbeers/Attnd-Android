package com.arcu.attnd.unit

import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.ScheduleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ScheduleGroupingTest {

    private val chicago = ZoneId.of("America/Chicago")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun item(id: Int, start: String?, end: String? = null, locationId: Int? = null, locationName: String? = null) =
        ScheduleItem(
            id = id, name = "Item $id", description = "", startTime = EventTime.parse(start),
            endTime = EventTime.parse(end), locationId = locationId, locationName = locationName, tags = emptyList(),
        )

    private fun location(id: Int, name: String) = EventLocation(
        id = id, name = name, snippet = "", description = "", category = null, website = null,
        imageUrl = null, address = "", coordinate = null, sortOrder = 0,
    )

    @Test
    fun byDay_usesEventTimeZone_notUtc() {
        // 03:30 UTC on Nov 15 is 9:30 PM on Nov 14 in Chicago (CST, UTC-6).
        val items = listOf(
            item(1, "2026-11-14T16:00:00+00:00"),
            item(2, "2026-11-15T03:30:00+00:00"),
            item(3, "2026-11-15T18:00:00+00:00"),
        )
        val sections = ScheduleGrouping.byDay(items, chicago, Locale.US)
        assertEquals(listOf("Saturday, November 14", "Sunday, November 15"), sections.map { it.title })
        assertEquals(listOf(1, 2), sections[0].items.map { it.id })
        assertEquals(listOf(3), sections[1].items.map { it.id })

        // The same instants in Tokyo (UTC+9) fall on Nov 15 and Nov 16.
        val tokyoSections = ScheduleGrouping.byDay(items, tokyo, Locale.US)
        assertEquals(listOf(listOf(1, 2), listOf(3)), tokyoSections.map { s -> s.items.map { it.id } })
        assertEquals("Sunday, November 15", tokyoSections[0].title)
    }

    @Test
    fun byDay_untimedItemsGoLast() {
        val sections = ScheduleGrouping.byDay(listOf(item(1, null), item(2, "2026-11-14T16:00:00+00:00")), chicago, Locale.US)
        assertEquals(2, sections.size)
        assertEquals(ScheduleGrouping.TIME_TBA_TITLE, sections.last().title)
        assertEquals(listOf(1), sections.last().items.map { it.id })
    }

    @Test
    fun timeline_isChronological() {
        val sections = ScheduleGrouping.timeline(
            listOf(item(3, "2026-11-15T10:00:00Z"), item(1, null), item(2, "2026-11-14T10:00:00-05:00"))
        )
        assertEquals(1, sections.size)
        assertEquals("", sections.single().title)
        assertEquals(listOf(2, 3, 1), sections.single().items.map { it.id })
    }

    @Test
    fun byLocation_followsLocationOrder_andPutsUnassignedUnderGeneral() {
        val locations = listOf(location(10, "Stage"), location(11, "Food Court"))
        val items = listOf(
            item(1, "2026-11-14T16:00:00Z", locationId = 11),
            item(2, "2026-11-14T15:00:00Z", locationId = 10),
            item(3, "2026-11-14T17:00:00Z", locationId = null),
            item(4, "2026-11-14T18:00:00Z", locationId = 99, locationName = "Pop-up Tent"),
        )
        val sections = ScheduleGrouping.byLocation(items, locations)
        assertEquals(listOf("Stage", "Food Court", "Pop-up Tent", "General"), sections.map { it.title })
        assertEquals(listOf(3), sections.last().items.map { it.id })
    }

    @Test
    fun nowNext_splitsIntoNowUpNextAndLater_andDropsEnded() {
        val now = Instant.parse("2026-11-14T18:00:00Z")
        val items = listOf(
            item(1, "2026-11-14T15:00:00Z", "2026-11-14T16:00:00Z"), // ended
            item(2, "2026-11-14T17:30:00Z", "2026-11-14T19:00:00Z"), // now
            item(3, "2026-11-14T17:30:00Z"),                          // now (no end: assumed 1 hour)
            item(4, "2026-11-14T19:30:00Z"),                          // up next (within 2 hours)
            item(5, "2026-11-14T20:30:00Z"),                          // later
            item(6, null),                                            // untimed: later
            item(7, "2026-11-14T16:30:00Z"),                          // no end, started 90 min ago: ended
        )
        val sections = ScheduleGrouping.nowNext(items, now)
        assertEquals(listOf(ScheduleGrouping.NOW_TITLE, ScheduleGrouping.NEXT_TITLE, ScheduleGrouping.LATER_TITLE), sections.map { it.title })
        assertEquals(listOf(2, 3), sections[0].items.map { it.id })
        assertEquals(listOf(4), sections[1].items.map { it.id })
        assertEquals(listOf(5, 6), sections[2].items.map { it.id })
    }

    @Test
    fun group_dispatchesOnStyle() {
        val items = listOf(item(1, "2026-11-14T16:00:00Z"))
        val now = Instant.parse("2026-11-14T10:00:00Z")
        assertEquals("", ScheduleGrouping.group(ScheduleStyle.TIMELINE, items, emptyList(), chicago, now, Locale.US).single().title)
        assertEquals("Saturday, November 14", ScheduleGrouping.group(ScheduleStyle.BY_DAY, items, emptyList(), chicago, now, Locale.US).single().title)
        assertEquals("General", ScheduleGrouping.group(ScheduleStyle.BY_LOCATION, items, emptyList(), chicago, now, Locale.US).single().title)
        assertEquals(ScheduleGrouping.LATER_TITLE, ScheduleGrouping.group(ScheduleStyle.NOW_NEXT, items, emptyList(), chicago, now, Locale.US).single().title)
    }

    @Test
    fun hasEnded_andHappeningNow() {
        val now = Instant.parse("2026-11-14T18:00:00Z")
        assertTrue(ScheduleGrouping.hasEnded(item(1, "2026-11-14T16:00:00Z"), now))
        assertFalse(ScheduleGrouping.hasEnded(item(2, "2026-11-14T17:30:00Z"), now))
        assertTrue(ScheduleGrouping.isHappeningNow(item(2, "2026-11-14T17:30:00Z"), now))
        assertFalse(ScheduleGrouping.hasEnded(item(3, null), now))
    }

    @Test
    fun filter_matchesNameLocationAndTags() {
        val items = listOf(
            item(1, null).copy(name = "Jazz Brunch"),
            item(2, null, locationName = "Main Stage"),
            item(3, null).copy(tags = listOf("Family")),
        )
        assertEquals(listOf(1), ScheduleGrouping.filter(items, "jazz").map { it.id })
        assertEquals(listOf(2), ScheduleGrouping.filter(items, "stage").map { it.id })
        assertEquals(listOf(3), ScheduleGrouping.filter(items, "family").map { it.id })
        assertEquals(3, ScheduleGrouping.filter(items, "  ").size)
    }
}
