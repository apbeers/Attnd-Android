package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LocationsStyle
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.domain.SecondaryPane
import com.arcu.attnd.domain.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ApiMapperTest {

    @Test
    fun fullBundle_decodesEveryObject_andIgnoresUnknownKeys() {
        val bundle = ApiMapper.parseBundle(SampleBundles.FULL)
        assertNotNull(bundle)
        bundle!!
        assertEquals(2, bundle.schemaVersion)
        assertEquals(15, bundle.contentVersion)

        val event = bundle.event
        assertEquals(1, event.id)
        assertEquals("FWFM2026", event.eventCode)
        assertEquals(GeoPoint(32.75, -97.33), event.coordinate)
        assertEquals(ZoneId.of("America/Chicago"), event.zone)
        assertEquals(EventStatus.PUBLISHED, event.status)
        assertTrue(event.isDemo)
        assertFalse(event.isArchived)
        assertEquals("https://attnd.app/media/flyer.png", event.flyerUrl)
        assertNotNull(event.endTime)

        assertEquals(listOf("Food", "Stage"), bundle.categories)
        assertEquals(listOf(5, 6, 7), bundle.locations.map { it.id })
        assertEquals(1, bundle.notifications.size)
        assertEquals(listOf("Music"), bundle.schedule.first().tags)
    }

    @Test
    fun fullBundle_displayValuesAreUsed_unknownTabDropped() {
        val display = ApiMapper.parseBundle(SampleBundles.FULL)!!.display
        assertEquals(listOf(Tab.SCHEDULE, Tab.MAP, Tab.LOCATIONS, Tab.NOTIFICATIONS, Tab.FLYER), display.tabs)
        assertEquals(Tab.SCHEDULE, display.defaultTab)
        assertEquals(LocationsStyle.GROUPED, display.locationsStyle)
        assertEquals(ScheduleStyle.NOW_NEXT, display.scheduleStyle)
        assertTrue(display.mapClustering)
        assertFalse(display.mapFitAll)
        assertFalse(display.showLocationImages)
        assertFalse(display.enableSearch)
        assertTrue(display.groupByCategory)
        assertEquals(SecondaryPane.NOW_NEXT, display.secondaryPane)
        assertEquals("#123456", display.accentColor)
        // display.logo_url is null, so the event's logo is used.
        assertEquals("https://attnd.app/media/logo.png", display.logoUrl)
    }

    @Test
    fun nullAndZeroCoordinates_areNotPins() {
        val locations = ApiMapper.parseBundle(SampleBundles.FULL)!!.locations
        assertEquals(GeoPoint(32.74, -97.36), locations.first { it.id == 5 }.coordinate)
        assertNull("null coordinates", locations.first { it.id == 6 }.coordinate)
        assertNull("0,0 is not a real location", locations.first { it.id == 7 }.coordinate)
    }

    @Test
    fun nullableFields_scheduleItemWithoutLocation() {
        val item = ApiMapper.parseBundle(SampleBundles.FULL)!!.schedule.first { it.id == 2 }
        assertNull(item.locationId)
        assertNull(item.locationName)
        assertNull(item.endTime)
        assertTrue(item.tags.isEmpty())
    }

    @Test
    fun minimalBundle_fallsBackToDefaults() {
        val bundle = ApiMapper.parseBundle(SampleBundles.MINIMAL)
        assertNotNull(bundle)
        bundle!!
        assertEquals(3, bundle.event.id)
        assertNull(bundle.event.coordinate)
        assertNull(bundle.event.timezone)
        assertFalse("paid and no is_demo means not a demo", bundle.event.isDemo)
        assertEquals(EventStatus.PUBLISHED, bundle.event.status)
        assertTrue(bundle.locations.isEmpty())
        assertTrue(bundle.notifications.isEmpty())
        assertTrue(bundle.categories.isEmpty())
        // The schedule item without an id is skipped; the other gets empty defaults.
        assertEquals(1, bundle.schedule.size)
        assertEquals("", bundle.schedule.single().description)
        assertNull(bundle.schedule.single().startTime)

        val display = bundle.display
        assertEquals(listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS), display.tabs)
        assertEquals(Tab.MAP, display.defaultTab)
        assertEquals(LocationsStyle.LIST, display.locationsStyle)
        assertEquals(ScheduleStyle.BY_DAY, display.scheduleStyle)
        assertFalse(display.mapClustering)
        assertTrue(display.mapFitAll)
        assertTrue(display.showLocationImages)
        assertTrue(display.enableSearch)
        assertFalse(display.groupByCategory)
        assertEquals(SecondaryPane.LOCATIONS, display.secondaryPane)
        assertNull(display.accentColor)
    }

    @Test
    fun partialDisplay_missingAndUnknownValuesUseDefaults() {
        val bundle = ApiMapper.parseBundle(SampleBundles.PARTIAL_DISPLAY)!!
        assertTrue(bundle.event.isArchived)
        assertFalse(bundle.event.isDemo)
        val display = bundle.display
        // "nonsense" is the only tab, so the defaults apply, including flyer since flyer_url is set.
        assertEquals(listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.FLYER, Tab.NOTIFICATIONS), display.tabs)
        assertEquals(Tab.FLYER, display.defaultTab)
        assertEquals(ScheduleStyle.BY_LOCATION, display.scheduleStyle)
        assertEquals(LocationsStyle.LIST, display.locationsStyle)
        assertEquals(SecondaryPane.LOCATIONS, display.secondaryPane)
        assertTrue(display.mapFitAll)
    }

    @Test
    fun invalidJson_orMissingEvent_returnsNull() {
        assertNull(ApiMapper.parseBundle("not json"))
        assertNull(ApiMapper.parseBundle("{}"))
        assertNull(ApiMapper.parseBundle("""{"event": {"name": "no id"}}"""))
    }
}
