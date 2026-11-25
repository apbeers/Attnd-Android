package com.arcu.attnd.unit

import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.EventTab
import com.arcu.attnd.domain.EventTab.FLYER
import com.arcu.attnd.domain.EventTab.HOME
import com.arcu.attnd.domain.EventTab.LOCATIONS
import com.arcu.attnd.domain.EventTab.MAP
import com.arcu.attnd.domain.EventTab.NOTIFICATIONS
import com.arcu.attnd.domain.EventTab.SCHEDULE
import com.arcu.attnd.domain.MapNavigation
import com.arcu.attnd.domain.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab bar as iOS draws it (iOS `TabLayoutTests`, the same cases): the event's own tabs, Home first. */
class TabLayoutTest {

    /** The event's own tabs (screenshot mode: no Home) unless [home]. */
    private fun layout(tabs: List<EventTab>?, default: EventTab? = null, flyer: Boolean = false, home: Boolean = false) =
        TabLayout.resolve(tabs, default, hasFlyer = flyer, screenshotMode = !home)

    @Test
    fun serverTabRules_API_V2() {
        assertEquals(listOf(HOME), layout(listOf(HOME), home = true).tabs)
        assertEquals(HOME, layout(listOf(HOME, MAP, SCHEDULE), default = MAP, home = true).defaultTab)
        // Home plus five: the flyer is left out first.
        assertEquals(listOf(HOME, MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS),
            layout(listOf(HOME, MAP, LOCATIONS, SCHEDULE, FLYER, NOTIFICATIONS), home = true).tabs)
    }

    @Test
    fun removeThenReAddATab() {
        val full = layout(listOf(HOME, MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), home = true)
        assertEquals(listOf(HOME, MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), full.tabs)
        assertFalse(SCHEDULE in layout(listOf(HOME, MAP, LOCATIONS, NOTIFICATIONS), home = true))
        assertTrue("a tab added back shows again, wherever it is listed",
            SCHEDULE in layout(listOf(HOME, MAP, LOCATIONS, NOTIFICATIONS, SCHEDULE), home = true))
        // Five tabs without Home listed: Home is added and Flyer, not Schedule, makes room.
        assertEquals(listOf(HOME, MAP, SCHEDULE, LOCATIONS, NOTIFICATIONS),
            layout(listOf(MAP, SCHEDULE, LOCATIONS, FLYER, NOTIFICATIONS), home = true).tabs)
        // Six listed: Home's rule cuts Flyer, so Schedule listed last stays.
        assertEquals(listOf(HOME, MAP, LOCATIONS, NOTIFICATIONS, SCHEDULE),
            layout(listOf(HOME, MAP, LOCATIONS, FLYER, NOTIFICATIONS, SCHEDULE), home = true).tabs)
    }

    @Test
    fun homeAlwaysFirstAndDefault() {
        val l = layout(listOf(MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), default = SCHEDULE, home = true)
        assertEquals(listOf(HOME, MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), l.tabs)
        assertEquals(HOME, l.defaultTab)
        assertEquals("the gear is on Home, so Updates is optional", HOME, l.optionsTab)
        assertEquals(listOf(HOME, SCHEDULE, MAP), layout(listOf(SCHEDULE, HOME, MAP), home = true).tabs)
        // Five at most: Flyer goes first, then Places when the Map is there.
        assertEquals(listOf(HOME, MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS),
            layout(listOf(MAP, LOCATIONS, SCHEDULE, FLYER, NOTIFICATIONS), home = true).tabs)
        assertEquals(listOf(HOME, MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), layout(null, home = true).tabs)
        // Screenshot mode keeps today's tabs.
        assertFalse(HOME in layout(listOf(HOME, MAP)))
    }

    @Test
    fun listedTabsInOrder() {
        val l = layout(listOf(SCHEDULE, MAP, FLYER, NOTIFICATIONS))
        assertEquals(listOf(SCHEDULE, MAP, FLYER, NOTIFICATIONS), l.tabs)
        assertEquals("no default_tab → Map when listed", MAP, l.defaultTab)
    }

    @Test
    fun defaults() {
        assertEquals(listOf(MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), layout(null).tabs)
        assertEquals(listOf(MAP, LOCATIONS, SCHEDULE, NOTIFICATIONS), layout(emptyList()).tabs)
        assertEquals(listOf(MAP, LOCATIONS, SCHEDULE, FLYER, NOTIFICATIONS), layout(null, flyer = true).tabs)
        assertEquals(MAP, layout(null).defaultTab)
    }

    @Test
    fun defaultTab() {
        assertEquals(SCHEDULE, layout(listOf(MAP, SCHEDULE, NOTIFICATIONS), default = SCHEDULE).defaultTab)
        assertEquals("an unlisted default_tab is ignored", MAP, layout(listOf(MAP, NOTIFICATIONS), default = SCHEDULE).defaultTab)
        assertEquals("no Map → the first tab", FLYER, layout(listOf(FLYER, NOTIFICATIONS)).defaultTab)
    }

    @Test
    fun atMostFiveTabs() {
        assertEquals(DisplayConfig.MAX_TABS, layout(listOf(MAP, LOCATIONS, SCHEDULE, FLYER, NOTIFICATIONS, MAP)).tabs.size)
    }

    @Test
    fun titles() {
        assertEquals("Places", TabLayout.title(LOCATIONS, screenshotMode = false))
        assertEquals("Locations", TabLayout.title(LOCATIONS, screenshotMode = true))
        assertEquals("Updates", TabLayout.title(NOTIFICATIONS, screenshotMode = false))
        assertEquals("Map", TabLayout.title(MAP, screenshotMode = false))
    }

    @Test
    fun iconsAreDistinct() {
        val l = layout(null, flyer = true)
        assertEquals(l.tabs.size, l.tabs.map { it.icon }.toSet().size)
        assertEquals(EventTab.entries.size, EventTab.entries.map { it.icon }.toSet().size)
        assertEquals("List", LOCATIONS.icon)
    }

    @Test
    fun optionsTab() {
        assertEquals("Options lives on Updates", NOTIFICATIONS, layout(null).optionsTab)
        assertEquals(NOTIFICATIONS, layout(listOf(SCHEDULE, MAP, NOTIFICATIONS)).optionsTab)
        assertEquals("no Updates → the first tab", SCHEDULE, layout(listOf(SCHEDULE, MAP)).optionsTab)
        assertEquals(FLYER, layout(listOf(FLYER)).optionsTab)
    }

    @Test
    fun showOnMapPopsBackToTheMap() {
        var nav = MapNavigation().open(12)
        assertEquals(listOf(12), nav.path)
        // Show on map from the detail pushed over the map: back to the map, the pin focused.
        nav = nav.showOnMap(12)
        assertEquals(emptyList<Int>(), nav.path)
        assertEquals(12, nav.focusLocationId)
        val (focus, cleared) = nav.takeFocus()
        assertEquals(12, focus)
        assertNull(cleared.focusLocationId)
        assertNull(cleared.takeFocus().first)
        // From another tab (nothing pushed) it only focuses.
        assertEquals(MapNavigation(emptyList(), 5), cleared.showOnMap(5))
        assertEquals(MapNavigation(), MapNavigation())
        assertEquals(HOME, EventTab.fromKey(" Home "))
    }
}
