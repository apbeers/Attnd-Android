package com.arcu.attnd.unit

import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.UsageMetric
import com.arcu.attnd.unit.GlanceFixture.bundle
import com.arcu.attnd.domain.ItemTiming
import com.arcu.attnd.unit.GlanceFixture.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Hearts (stored per event on the device), their API-009 usage names, and the "Hearted" search filter. */
class HeartsTest {

    private val hearts = Hearts(locations = setOf(11), scheduleItems = setOf(3))

    @Test
    fun encodeDecode_roundTripAndLenient() {
        assertEquals("l11,s3", hearts.encode())
        assertEquals(hearts, Hearts.decode("l11,s3"))
        assertEquals(Hearts(setOf(5, 12), setOf(7)), Hearts.decode("x, l, s7 ,l12,l5,lfoo,"))
        assertEquals(Hearts.NONE, Hearts.decode(null))
        assertEquals(Hearts.NONE, Hearts.decode(""))
        assertEquals("", Hearts.NONE.encode())
    }

    @Test
    fun toggleAndKinds() {
        assertTrue(hearts.has(SearchKind.LOCATION, 11))
        assertFalse(hearts.has(SearchKind.SCHEDULE, 11))
        assertFalse(hearts.has(SearchKind.NOTIFICATION, 11))
        val toggled = hearts.toggled(SearchKind.SCHEDULE, 3).toggled(SearchKind.SCHEDULE, 5)
        assertEquals(setOf(5), toggled.scheduleItems)
        assertEquals(hearts, hearts.with(SearchKind.EVENT, 77, true))
        assertTrue(Hearts.NONE.isEmpty)
        assertFalse(hearts.isEmpty)
    }

    @Test
    fun usageMetricNames_api009() {
        assertEquals("location_heart", Hearts.metric(SearchKind.LOCATION, true))
        assertEquals("location_unheart", Hearts.metric(SearchKind.LOCATION, false))
        assertEquals("schedule_item_heart", Hearts.metric(SearchKind.SCHEDULE, true))
        assertEquals("schedule_item_unheart", Hearts.metric(SearchKind.SCHEDULE, false))
        assertEquals(UsageMetric.SCHEDULE_ITEM_HEART, Hearts.metric(SearchKind.SCHEDULE, true))
        assertNull(Hearts.metric(SearchKind.NOTIFICATION, true))
    }

    @Test
    fun heartedList_scheduleInTimeOrderThenLocations_notAQuery() {
        val list = EventSearch.hearted(bundle, Hearts(setOf(11, 10, 404), setOf(5, 3, 1, 404)), now)
        assertEquals(
            listOf(SearchKind.SCHEDULE to 1, SearchKind.SCHEDULE to 3, SearchKind.SCHEDULE to 5, SearchKind.LOCATION to 10, SearchKind.LOCATION to 11),
            list.map { it.kind to it.id },
        )
        assertEquals(ItemTiming.NOW, list.first().timing)
        assertTrue(EventSearch.hearted(bundle, Hearts.NONE, now).isEmpty())
        // "hearted" is not a query word.
        assertTrue(EventSearch.search(bundle, "hearted", now).isEmpty())
        // The suggestions themselves don't change; the screen adds the Hearted entry once something is hearted.
        assertFalse(EventSearch.suggestions(bundle, now).any { it.label == EventSearch.HEARTED_TITLE })
    }
}
