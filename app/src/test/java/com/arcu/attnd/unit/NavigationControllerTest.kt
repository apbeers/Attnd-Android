package com.arcu.attnd.unit

import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTab
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.TabLayout
import com.arcu.attnd.ui.AppUiState
import com.arcu.attnd.ui.NavigationController
import com.arcu.attnd.ui.screens.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NavigationController] wired to iOS's TabLayout (Home first; no My plan or Explore tab). */
class NavigationControllerTest {

    /** A controller whose "current UI" follows its own state, like the ViewModel's uiState. */
    private fun controller(tabs: List<Tab>, screenshot: Boolean = false): Pair<NavigationController, () -> AppUiState> {
        val layout = TabLayout.resolve(tabs.map(EventTab::of), null, hasFlyer = false, screenshotMode = screenshot)
        val bundle: EventBundle = PlannerFixture.bundle.copy(display = PlannerFixture.bundle.display.copy(tabs = layout.organizerTabs))
        lateinit var nav: NavigationController
        val ui = {
            val state = nav.state.value
            val selected = layout.selected(state.selectedTab)
            AppUiState(bundle = bundle, tabs = layout, selectedEventTab = selected, selectedTab = selected.tab, showOptions = state.showOptions)
        }
        nav = NavigationController(current = ui)
        return nav to ui
    }

    private val allTabs = listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS)

    @Test
    fun homeIsTheDefault_tabsSelectDirectly() {
        val (nav, ui) = controller(allTabs)
        assertEquals(EventTab.HOME, ui().selectedEventTab)
        assertNull(ui().selectedTab)
        nav.selectTab(Tab.SCHEDULE)
        assertEquals(Tab.SCHEDULE, ui().selectedTab)
        nav.selectTab(Tab.NOTIFICATIONS)
        assertEquals(EventTab.NOTIFICATIONS, ui().selectedEventTab)
        // A tab the event doesn't show changes nothing.
        nav.selectTab(Tab.FLYER)
        assertEquals(EventTab.NOTIFICATIONS, ui().selectedEventTab)
        nav.selectEventTab(EventTab.HOME)
        assertEquals(EventTab.HOME, ui().selectedEventTab)
    }

    @Test
    fun screenshotModeKeepsTodaysDefault() {
        val (_, ui) = controller(allTabs, screenshot = true)
        assertEquals(EventTab.MAP, ui().selectedEventTab)
        assertFalse(EventTab.HOME in ui().tabs)
    }

    @Test
    fun reselectingTheMapRecenters() {
        val (nav, ui) = controller(allTabs)
        nav.selectTab(Tab.MAP)
        assertEquals(Tab.MAP, ui().selectedTab)
        assertEquals(0, nav.state.value.recenterRequest)
        nav.selectTab(Tab.MAP)
        assertEquals(1, nav.state.value.recenterRequest)
        nav.selectEventTab(EventTab.MAP)
        assertEquals(2, nav.state.value.recenterRequest)
    }

    @Test
    fun route_itemsOpenOnTheirTabElseInTheSearchSheet() {
        val (nav, ui) = controller(allTabs)
        nav.showOptions(true)
        nav.route(OpenTarget.ScheduleItem(101))
        assertEquals(101, nav.state.value.focusScheduleItemId)
        assertEquals(Tab.SCHEDULE, ui().selectedTab)
        assertFalse(nav.state.value.showOptions)

        val (hidden, hiddenUi) = controller(listOf(Tab.MAP))
        hidden.route(OpenTarget.Location(6))
        val request = hidden.state.value.searchRequest!!
        assertEquals(SearchEntry(SearchKind.LOCATION, 6), request.detail)
        assertEquals(1, request.serial)
        assertTrue(hidden.state.value.searchOpen)
        // A detail keeps the current tab; a search opens the sheet with the query.
        hidden.selectTab(Tab.MAP)
        hidden.route(OpenTarget.Search("tacos"))
        assertEquals("tacos", hidden.state.value.searchRequest!!.query)
        assertEquals(2, hidden.state.value.searchRequest!!.serial)
        assertEquals(Tab.MAP, hiddenUi().selectedTab)
        hidden.consumeSearchRequest()
        assertNull(hidden.state.value.searchRequest)
        assertTrue(hidden.state.value.searchOpen)
        hidden.closeSearch()
        assertFalse(hidden.state.value.searchOpen)
    }

    @Test
    fun route_mapPinsUpdatesAndMyPlan() {
        val (nav, ui) = controller(allTabs)
        nav.openSearch()
        nav.route(OpenTarget.MapLocation(6))
        assertEquals(6, nav.state.value.mapFocusLocationId)
        assertEquals(Tab.MAP, ui().selectedTab)
        assertFalse("Show on map closes the search sheet", nav.state.value.searchOpen)
        nav.route(OpenTarget.Notification(31))
        assertEquals(31, nav.state.value.focusNotificationId)
        assertEquals(EventTab.NOTIFICATIONS, ui().selectedEventTab)
        nav.route(OpenTarget.EventInfo)
        assertTrue(nav.state.value.showOptions)
        nav.route(OpenTarget.MyPlan)
        assertTrue(nav.state.value.planOpen)
        assertEquals(1, nav.state.value.showPlanRequest)
        assertEquals("the tab underneath stays", EventTab.NOTIFICATIONS, ui().selectedEventTab)
    }

    @Test
    fun showOnMap_needsAMapTab() {
        val (nav, _) = controller(listOf(Tab.SCHEDULE))
        nav.showOnMap(6)
        assertNull(nav.state.value.mapFocusLocationId)
    }

    @Test
    fun myPlanAndItsRequests() {
        val (nav, _) = controller(allTabs)
        nav.showPlan(focus = true)
        assertTrue(nav.state.value.planOpen)
        assertEquals(1, nav.state.value.focusInput)
        nav.closePlan()
        assertFalse(nav.state.value.planOpen)
        nav.ask("What's on near me right now?")
        assertTrue(nav.state.value.planOpen)
        assertTrue(nav.state.value.planRequest!!.ask)
        nav.consumePlanRequest()
        assertNull(nav.state.value.planRequest)
        nav.openSearch()
        nav.showProposal()
        assertFalse(nav.state.value.searchOpen)
        assertTrue(nav.state.value.planOpen)
    }

    @Test
    fun openScreen_andResets() {
        val (nav, ui) = controller(allTabs)
        nav.showPlan()
        assertTrue(nav.openScreen("schedule", EntityRef(RefKind.SCHEDULE, 101)))
        assertEquals(101, nav.state.value.focusScheduleItemId)
        assertFalse("a manual screen closes My plan", nav.state.value.planOpen)
        assertFalse(nav.openScreen("tour", null))
        nav.showOnMap(6)
        nav.resetForLeave()
        assertNull(nav.state.value.focusScheduleItemId)
        assertEquals(6, nav.state.value.mapFocusLocationId)
        assertEquals(EventTab.HOME, ui().selectedEventTab)
        nav.selectTab(Tab.NOTIFICATIONS)
        nav.resetForJoin(alreadyIn = true)
        assertEquals(EventTab.NOTIFICATIONS, ui().selectedEventTab)
        assertNull(nav.state.value.mapFocusLocationId)
        nav.resetForJoin(alreadyIn = false)
        assertEquals(EventTab.HOME, ui().selectedEventTab)
    }
}
