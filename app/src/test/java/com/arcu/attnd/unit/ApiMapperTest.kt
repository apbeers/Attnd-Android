package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AppStatusDto
import com.arcu.attnd.domain.AppUpdate
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LocationsStyle
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.domain.SecondaryPane
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.UpdateLevel
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun appUpdate_unknownOrMissingValuesNeverPrompt() {
        val required = ApiMapper.appUpdate(AppStatusDto(update = "required", latestVersion = "2.0.9", updateUrl = "https://play.google.com/store/apps/details?id=com.arcu.attnd"))
        assertEquals(UpdateLevel.REQUIRED, required.level)
        assertEquals("2.0.9", required.latestVersion)
        assertEquals(UpdateLevel.NONE, ApiMapper.appUpdate(AppStatusDto(update = "someday")).level)
        assertEquals(UpdateLevel.NONE, ApiMapper.appUpdate(AppStatusDto()).level)
        // Only https store links are opened.
        assertEquals(AppUpdate.PLAY_STORE_URL, ApiMapper.appUpdate(AppStatusDto(update = "available", updateUrl = "javascript:x")).updateUrl)
    }

    @Test
    fun locationTagsAndNotificationPriority_api010_api011() {
        val bundle = ApiMapper.parseBundle(
            """
            {"event": {"id": 1, "name": "E"},
             "locations": [
               {"id": 5, "name": "Main Stage", "tags": ["live music", null, "", "free"]},
               {"id": 6, "name": "Old server"}
             ],
             "notifications": [
               {"id": 1, "title": "Cancelled", "priority": "urgent"},
               {"id": 2, "title": "Normal", "priority": "normal"},
               {"id": 3, "title": "Future value", "priority": "emergency"},
               {"id": 4, "title": "Old server"}
             ]}
            """
        )!!
        assertEquals(listOf("live music", "free"), bundle.location(5)!!.tags)
        assertEquals(emptyList<String>(), bundle.location(6)!!.tags)
        assertEquals(listOf(true, false, false, false), bundle.notifications.map { it.isUrgent })
        assertEquals("Live music", com.arcu.attnd.domain.Tags.display("live music"))
        assertEquals("Kid-friendly", com.arcu.attnd.domain.Tags.display("kid-friendly"))
        assertEquals("21+", com.arcu.attnd.domain.Tags.display("21+"))
    }
}
