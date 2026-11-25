package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.ScreenshotRequest
import com.arcu.attnd.domain.ScreenshotScreen
import com.arcu.attnd.domain.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotRequestTest {

    @Test
    fun parse_knownScreens_withDefaults() {
        val request = ScreenshotRequest.parse(" Map ")
        assertEquals(ScreenshotScreen.MAP, request?.screen)
        assertEquals("DEMO", request?.eventCode)
        assertEquals("Main Stage", request?.locationName)

        val custom = ScreenshotRequest.parse("location", eventCode = " FWFM2026 ", locationName = "Info Booth")
        assertEquals(ScreenshotScreen.LOCATION, custom?.screen)
        assertEquals("FWFM2026", custom?.eventCode)
        assertEquals("Info Booth", custom?.locationName)

        val blanks = ScreenshotRequest.parse("schedule", eventCode = "  ", locationName = "")
        assertEquals("DEMO", blanks?.eventCode)
        assertEquals("Main Stage", blanks?.locationName)
    }

    @Test
    fun parse_unknownOrMissingScreen_isNull() {
        assertNull(ScreenshotRequest.parse(null))
        assertNull(ScreenshotRequest.parse(""))
        assertNull(ScreenshotRequest.parse("options"))
    }

    @Test
    fun tab_perScreen() {
        assertNull(ScreenshotRequest(ScreenshotScreen.LAUNCH).tab)
        assertEquals(Tab.MAP, ScreenshotRequest(ScreenshotScreen.MAP).tab)
        assertEquals(Tab.LOCATIONS, ScreenshotRequest(ScreenshotScreen.LOCATIONS).tab)
        assertEquals(Tab.LOCATIONS, ScreenshotRequest(ScreenshotScreen.LOCATION).tab)
        assertEquals(Tab.SCHEDULE, ScreenshotRequest(ScreenshotScreen.SCHEDULE).tab)
        assertEquals(Tab.NOTIFICATIONS, ScreenshotRequest(ScreenshotScreen.NOTIFICATIONS).tab)
    }

    @Test
    fun focusLocation_byNameIgnoringCase_onlyForLocationScreen() {
        val bundle = ApiMapper.parseBundle(SampleBundles.FULL)!!
        assertEquals(6, ScreenshotRequest(ScreenshotScreen.LOCATION, locationName = "main stage").focusLocation(bundle.locations)?.id)
        assertNull(ScreenshotRequest(ScreenshotScreen.LOCATION, locationName = "Nowhere").focusLocation(bundle.locations))
        assertNull(ScreenshotRequest(ScreenshotScreen.LOCATIONS).focusLocation(bundle.locations))
    }

    @Test
    fun adjust_mapShowsEveryPin_otherScreensKeepTheLayout() {
        val display = ApiMapper.parseBundle(SampleBundles.FULL)!!.display
        assertTrue(display.mapClustering)
        assertFalse(display.mapFitAll)

        val map = ScreenshotRequest(ScreenshotScreen.MAP).adjust(display)
        assertTrue(map.mapFitAll)
        assertFalse(map.mapClustering)
        assertEquals(display.tabs, map.tabs)

        assertSame(display, ScreenshotRequest(ScreenshotScreen.SCHEDULE).adjust(display))
    }

    @Test
    fun present_hidesDemoPill_andAdjustsTheLayout() {
        val bundle = ApiMapper.parseBundle(SampleBundles.FULL)!!
        assertTrue(bundle.event.isDemo)

        val shown = ScreenshotRequest(ScreenshotScreen.MAP).present(bundle)
        assertFalse(shown.event.isDemo)
        assertFalse(shown.display.mapClustering)
        assertEquals(bundle.event.name, shown.event.name)
        assertEquals(bundle.locations, shown.locations)
    }
}
