package com.arcu.attnd.unit

import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.EventTab
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tabs (display → TabLayout, phase 4). Proposals are tested in [PlanProposalTest]. */
class PlanTabTest {

    private fun display(vararg keys: String, default: String? = null, flyer: Boolean = true) = DisplayConfig.resolve(
        rawTabs = keys.toList().takeIf { it.isNotEmpty() }, rawDefaultTab = default, rawLocationsStyle = null, rawScheduleStyle = null,
        mapClustering = null, mapFitAll = null, showLocationImages = null, enableSearch = null, groupByCategory = null,
        rawSecondaryPane = null, accentColor = null, logoUrl = null, hasFlyer = flyer,
    )

    // ── Tabs: the display wired to iOS's TabLayout (phase 4; no My plan or Explore tab) ──

    @Test
    fun tabs_homeFirstThenTheEventsOwnTabs() {
        val d = display("map", "schedule", "flyer", "notifications", default = "schedule")
        assertEquals(listOf(EventTab.HOME, EventTab.MAP, EventTab.SCHEDULE, EventTab.FLYER, EventTab.NOTIFICATIONS), d.layout.tabs)
        assertEquals(EventTab.HOME, d.layout.defaultTab)
        assertEquals(listOf(Tab.MAP, Tab.SCHEDULE, Tab.FLYER, Tab.NOTIFICATIONS), d.tabs)
        assertEquals("Home's header holds the gear", EventTab.HOME, d.layout.optionsTab)
    }

    @Test
    fun tabs_rawHomeAndTheFiveTabCut() {
        // `home` as the server sends it with X-Attnd-Sections; six listed → Flyer cut, never Schedule listed last.
        val d = display("home", "map", "locations", "flyer", "notifications", "schedule", default = "home")
        assertEquals(listOf(EventTab.HOME, EventTab.MAP, EventTab.LOCATIONS, EventTab.NOTIFICATIONS, EventTab.SCHEDULE), d.layout.tabs)
        assertFalse("a cut tab isn't visible", Tab.FLYER in d.tabs)
        // Defaults with a flyer: Home + five → Flyer goes.
        assertEquals(listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS), display().tabs)
    }

    @Test
    fun tabs_screenshotModeKeepsTodaysTabs() {
        val d = display("home", "map", "locations", "schedule", "notifications", default = "schedule")
        val legacy = d.tabLayout(screenshotMode = true)
        assertEquals(listOf(EventTab.MAP, EventTab.LOCATIONS, EventTab.SCHEDULE, EventTab.NOTIFICATIONS), legacy.tabs)
        assertEquals(EventTab.SCHEDULE, legacy.defaultTab)
        assertEquals("no Home: the gear is on Updates", EventTab.NOTIFICATIONS, legacy.optionsTab)
        assertEquals(EventTab.HOME, d.tabLayout(screenshotMode = false).defaultTab)
    }

    @Test
    fun tabs_selectionAndUsageNames() {
        val layout = display("map", "schedule").layout
        assertEquals(EventTab.SCHEDULE, layout.selected(EventTab.SCHEDULE))
        assertEquals("a hidden tab falls back to the default", EventTab.HOME, layout.selected(EventTab.LOCATIONS))
        assertEquals(EventTab.HOME, layout.selected(null))
        assertTrue(Tab.MAP in layout)
        assertNull(EventTab.HOME.tab)
        assertEquals(Tab.NOTIFICATIONS, EventTab.NOTIFICATIONS.tab)
        assertEquals("home", TabLayout.usageName(EventTab.HOME))
        assertEquals("notifications", TabLayout.usageName(EventTab.NOTIFICATIONS))
    }
}
