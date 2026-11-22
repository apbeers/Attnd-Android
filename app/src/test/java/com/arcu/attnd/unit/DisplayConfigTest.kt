package com.arcu.attnd.unit

import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayConfigTest {

    private fun resolve(
        tabs: List<String?>? = null,
        defaultTab: String? = null,
        hasFlyer: Boolean = false,
        accent: String? = null,
    ) = DisplayConfig.resolve(
        rawTabs = tabs, rawDefaultTab = defaultTab, rawLocationsStyle = null, rawScheduleStyle = null,
        mapClustering = null, mapFitAll = null, showLocationImages = null, enableSearch = null,
        groupByCategory = null, rawSecondaryPane = null, accentColor = accent, logoUrl = null, hasFlyer = hasFlyer,
    )

    @Test
    fun defaultTabs_addFlyerOnlyWhenEventHasOne() {
        assertEquals(listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS), resolve().tabs)
        // Home + five: the flyer is cut first (iOS TabLayout.withHome).
        assertEquals(listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS), resolve(hasFlyer = true).tabs)
    }

    @Test
    fun serverTabs_keepOrder_dropUnknownAndDuplicates() {
        val config = resolve(tabs = listOf("notifications", "MAP", null, "bogus", "map"))
        assertEquals(listOf(Tab.NOTIFICATIONS, Tab.MAP), config.tabs)
    }

    @Test
    fun flyerOnlyWhenListed() {
        assertEquals(listOf(Tab.MAP, Tab.SCHEDULE), resolve(tabs = listOf("map", "schedule"), hasFlyer = true).tabs)
    }

    @Test
    fun tabs_atMostFiveInListedOrder() {
        val all = listOf("notifications", "flyer", "schedule", "locations", "map")
        val config = resolve(tabs = all, hasFlyer = true)
        // Home takes one of the five: Flyer is cut first, the rest keep the listed order.
        assertEquals(DisplayConfig.MAX_TABS, config.layout.tabs.size)
        assertEquals(listOf(Tab.NOTIFICATIONS, Tab.SCHEDULE, Tab.LOCATIONS, Tab.MAP), config.tabs)
        assertEquals(listOf(Tab.NOTIFICATIONS, Tab.FLYER, Tab.SCHEDULE, Tab.LOCATIONS, Tab.MAP), config.legacyLayout.organizerTabs)
    }

    @Test
    fun defaultTab_mustBeVisible() {
        assertEquals(Tab.SCHEDULE, resolve(tabs = listOf("map", "schedule"), defaultTab = "schedule").defaultTab)
        assertEquals(Tab.MAP, resolve(tabs = listOf("schedule", "map"), defaultTab = "flyer").defaultTab)
        assertEquals(Tab.SCHEDULE, resolve(tabs = listOf("schedule", "locations"), defaultTab = null).defaultTab)
    }

    @Test
    fun accentColor_parsedOrDropped() {
        assertEquals(0xFFFF5A1FL, DisplayConfig.parseHexColor("#FF5A1F"))
        assertEquals(0x80FF5A1FL, DisplayConfig.parseHexColor("#80FF5A1F"))
        assertNull(DisplayConfig.parseHexColor("orange"))
        assertNull(DisplayConfig.parseHexColor("#12345"))
        assertNull(resolve(accent = "not a colour").accentColor)
        assertEquals("#00AA00", resolve(accent = "#00AA00").accentColor)
    }
}
