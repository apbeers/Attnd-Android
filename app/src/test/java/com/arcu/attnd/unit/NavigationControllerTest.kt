package com.arcu.attnd.unit

import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTabModel
import com.arcu.attnd.domain.NavItem
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.ui.AppUiState
import com.arcu.attnd.ui.ExploreViewStore
import com.arcu.attnd.ui.NavigationController
import com.arcu.attnd.ui.screens.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NavigationController], the navigation part split out of the ViewModel (same rules as before the split). */
class NavigationControllerTest {

    private class Store : ExploreViewStore {
        val saved = HashMap<Int, String>()
        override fun exploreView(eventId: Int): String? = saved[eventId]
        override fun setExploreView(eventId: Int, key: String) { saved[eventId] = key }
    }

    private val eventId = PlannerFixture.bundle.event.id

    /** A controller whose "current UI" follows its own state, like the ViewModel's uiState. */
    private fun controller(tabs: List<Tab>, store: Store = Store()): Pair<NavigationController, () -> AppUiState> {
        val bundle: EventBundle = PlannerFixture.bundle.copy(display = PlannerFixture.bundle.display.copy(tabs = tabs))
        val model = EventTabModel.of(tabs, null)
        lateinit var nav: NavigationController
        val ui = {
            val state = nav.state.value
            val item = state.selectedItem?.takeIf { it in model.items } ?: model.initialItem
            val view = model.exploreView(nav.exploreView(eventId))
            AppUiState(bundle = bundle, tabs = model, selectedItem = item, exploreView = view, selectedTab = model.contentTab(item, view), showOptions = state.showOptions)
        }
        nav = NavigationController(store, eventId = { eventId }, current = ui)
        return nav to ui
    }

    private val allTabs = listOf(Tab.MAP, Tab.LOCATIONS, Tab.SCHEDULE, Tab.NOTIFICATIONS)

    @Test
    fun selectTab_exploreViewsAreRememberedPerEvent() {
        val store = Store()
        val (nav, ui) = controller(allTabs, store)
        assertEquals(NavItem.PLAN, ui().selectedItem)
        nav.selectTab(Tab.SCHEDULE)
        assertEquals(NavItem.EXPLORE, ui().selectedItem)
        assertEquals(Tab.SCHEDULE, ui().selectedTab)
        assertEquals("schedule", store.saved[eventId])
        nav.selectTab(Tab.NOTIFICATIONS)
        assertEquals(NavItem.UPDATES, ui().selectedItem)
        assertEquals("schedule", store.saved[eventId])
        // A tab the event doesn't show changes nothing.
        nav.selectTab(Tab.FLYER)
        assertEquals(NavItem.UPDATES, ui().selectedItem)
    }

    @Test
    fun reselectingTheMapRecenters() {
        val (nav, ui) = controller(allTabs)
        nav.selectTab(Tab.MAP)
        assertEquals(Tab.MAP, ui().selectedTab)
        assertEquals(0, nav.state.value.recenterRequest)
        nav.selectTab(Tab.MAP)
        assertEquals(1, nav.state.value.recenterRequest)
        nav.selectItem(NavItem.EXPLORE)
        assertEquals(2, nav.state.value.recenterRequest)
    }

    @Test
    fun route_itemsOpenOnTheirTabElseInTheBar() {
        val (nav, ui) = controller(allTabs)
        nav.showOptions(true)
        nav.route(OpenTarget.ScheduleItem(101), allTabs)
        assertEquals(101, nav.state.value.focusScheduleItemId)
        assertEquals(Tab.SCHEDULE, ui().selectedTab)
        assertFalse(nav.state.value.showOptions)

        val (hidden, hiddenUi) = controller(listOf(Tab.MAP))
        hidden.route(OpenTarget.Location(6), listOf(Tab.MAP))
        val request = hidden.state.value.searchRequest!!
        assertEquals(SearchEntry(SearchKind.LOCATION, 6), request.detail)
        assertEquals(1, request.serial)
        // A detail keeps the current item; a search goes to My plan's bar.
        hidden.selectTab(Tab.MAP)
        hidden.route(OpenTarget.Search("tacos"), listOf(Tab.MAP))
        assertEquals("tacos", hidden.state.value.searchRequest!!.query)
        assertEquals(2, hidden.state.value.searchRequest!!.serial)
        assertEquals(NavItem.PLAN, hiddenUi().selectedItem)
    }

    @Test
    fun route_mapPinsAndUpdates() {
        val (nav, ui) = controller(allTabs)
        nav.route(OpenTarget.MapLocation(6), allTabs)
        assertEquals(6, nav.state.value.mapFocusLocationId)
        assertEquals(Tab.MAP, ui().selectedTab)
        nav.route(OpenTarget.Notification(31), allTabs)
        assertEquals(31, nav.state.value.focusNotificationId)
        assertEquals(NavItem.UPDATES, ui().selectedItem)
        nav.route(OpenTarget.EventInfo, allTabs)
        assertTrue(nav.state.value.showOptions)
        nav.route(OpenTarget.MyPlan, allTabs)
        assertEquals(NavItem.PLAN, ui().selectedItem)
        assertEquals(1, nav.state.value.showPlanRequest)
    }

    @Test
    fun showOnMap_needsAMapTab() {
        val (nav, _) = controller(listOf(Tab.SCHEDULE))
        nav.showOnMap(6)
        assertNull(nav.state.value.mapFocusLocationId)
    }

    @Test
    fun theBarAndItsRequests() {
        val (nav, _) = controller(allTabs)
        // On My plan the FAB focuses the bar; elsewhere it opens over the tab.
        nav.openAssistant()
        assertEquals(1, nav.state.value.focusInput)
        assertFalse(nav.state.value.assistantOpen)
        nav.selectTab(Tab.MAP)
        nav.openAssistant()
        assertTrue(nav.state.value.assistantOpen)
        nav.closeAssistant()
        assertFalse(nav.state.value.assistantOpen)
        nav.ask("What's on near me right now?")
        assertTrue(nav.state.value.searchRequest!!.ask)
        nav.consumeSearchRequest(keepOpen = true)
        assertNull(nav.state.value.searchRequest)
        assertTrue(nav.state.value.assistantOpen)
    }

    @Test
    fun openScreen_andResets() {
        val (nav, ui) = controller(allTabs)
        assertTrue(nav.openScreen("schedule", EntityRef(RefKind.SCHEDULE, 101), allTabs))
        assertEquals(101, nav.state.value.focusScheduleItemId)
        assertFalse(nav.openScreen("tour", null, allTabs))
        nav.showOnMap(6)
        nav.resetForLeave()
        assertNull(nav.state.value.focusScheduleItemId)
        assertEquals(6, nav.state.value.mapFocusLocationId)
        assertEquals(NavItem.PLAN, ui().selectedItem)
        nav.selectTab(Tab.NOTIFICATIONS)
        nav.resetForJoin(alreadyIn = true)
        assertEquals(NavItem.UPDATES, ui().selectedItem)
        assertNull(nav.state.value.mapFocusLocationId)
        nav.resetForJoin(alreadyIn = false)
        assertEquals(NavItem.PLAN, ui().selectedItem)
    }
}
