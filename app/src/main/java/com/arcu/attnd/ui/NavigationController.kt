package com.arcu.attnd.ui

import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.NavItem
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.ui.screens.SearchEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the app is: the selected item, Explore's view per event, one-shot focus requests, the bar and Options. */
data class NavState(
    val selectedItem: NavItem? = null,
    /** Explore's view per event (also in [ExploreViewStore]). */
    val exploreViews: Map<Int, Tab> = emptyMap(),
    val focusInput: Int = 0,
    val showOptions: Boolean = false,
    val focusLocationId: Int? = null,
    val focusScheduleItemId: Int? = null,
    val focusNotificationId: Int? = null,
    val searchRequest: SearchRequest? = null,
    val assistantOpen: Boolean = false,
    val recenterRequest: Int = 0,
    val mapFocusLocationId: Int? = null,
    val showPlanRequest: Int = 0,
)

/** Explore's remembered view per event (`DevicePrefs.exploreView`). */
interface ExploreViewStore {
    fun exploreView(eventId: Int): String?
    fun setExploreView(eventId: Int, key: String)
}

/**
 * The navigation part of the event UI, split out of [EventViewModel] (iOS: `AppState`'s tab, sheet and
 * focus state): which item and organizer tab are shown, Explore's view, the one-shot focus requests
 * (location, schedule item, notification, map pin), the input bar over a tab and Options. Pure Kotlin:
 * [current] is the resolved [AppUiState] (tabs, selected item and tab, the bundle) and [eventId] the
 * joined event's id from the repository, read the same way the ViewModel read them before the split.
 */
class NavigationController(
    private val store: ExploreViewStore,
    private val eventId: () -> Int?,
    private val current: () -> AppUiState,
) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** For the few multi-field changes the ViewModel makes on join, leave and in screenshot mode. */
    fun update(transform: (NavState) -> NavState) = _state.update(transform)

    /**
     * Shows [tab]'s screen: Explore on that view for the map / schedule / places, else the tab's own
     * item. Reselecting the map recenters it. A tab the event doesn't show changes nothing.
     */
    fun selectTab(tab: Tab) {
        val ui = current()
        if (tab == Tab.MAP && ui.selectedTab == Tab.MAP && !ui.showOptions) {
            _state.update { it.copy(recenterRequest = it.recenterRequest + 1) }
            return
        }
        _state.update { withTab(it, tab) }
    }

    /** A navigation item tapped (My plan, Explore, Flyer, Updates; today's tabs in screenshot mode). */
    fun selectItem(item: NavItem) {
        val ui = current()
        if (item == ui.selectedItem && ui.selectedTab == Tab.MAP) {
            _state.update { it.copy(recenterRequest = it.recenterRequest + 1) }
            return
        }
        _state.update { it.copy(selectedItem = item, assistantOpen = if (item == NavItem.PLAN) false else it.assistantOpen) }
    }

    /** Explore's switch: shows [view] and remembers it for the event. */
    fun selectExploreView(view: Tab) = _state.update { withTab(it, view) }

    /** [state] showing [tab] (the item that holds it; Explore's view remembered per event). */
    private fun withTab(state: NavState, tab: Tab): NavState {
        val item = current().tabs.itemFor(tab) ?: return state
        val eventId = eventId()
        if (item != NavItem.EXPLORE || eventId == null) return state.copy(selectedItem = item)
        store.setExploreView(eventId, tab.key)
        return state.copy(selectedItem = item, exploreViews = state.exploreViews + (eventId to tab))
    }

    /** The Notifications tab, scrolled to [notificationId] when given (a push tap). */
    fun showNotifications(notificationId: Int?) =
        _state.update { withTab(it, Tab.NOTIFICATIONS).copy(showOptions = false, focusNotificationId = notificationId ?: it.focusNotificationId) }

    /** Shows a location's detail: on the Locations tab, or as an overlay when that tab is hidden. */
    fun focusLocation(locationId: Int) = _state.update { withTab(it, Tab.LOCATIONS).copy(focusLocationId = locationId) }

    fun consumeFocusLocation() = _state.update { it.copy(focusLocationId = null) }

    fun focusScheduleItem(itemId: Int) = _state.update { withTab(it, Tab.SCHEDULE).copy(focusScheduleItemId = itemId) }

    fun consumeFocusScheduleItem() = _state.update { it.copy(focusScheduleItemId = null) }

    /** Shows a notification on the Notifications tab (or as an overlay when that tab is hidden). */
    fun focusNotification(notificationId: Int) =
        _state.update { withTab(it, Tab.NOTIFICATIONS).copy(focusNotificationId = notificationId, showOptions = false) }

    fun consumeFocusNotification() = _state.update { it.copy(focusNotificationId = null) }

    /** Selects a location on the Map tab (when the event shows one). */
    fun showOnMap(locationId: Int) {
        val bundle = current().bundle ?: return
        if (Tab.MAP !in bundle.display.tabs) return
        _state.update { withTab(it, Tab.MAP).copy(showOptions = false, mapFocusLocationId = locationId) }
    }

    fun consumeMapFocus() = _state.update { it.copy(mapFocusLocationId = null) }

    fun showOptions(show: Boolean) = _state.update { it.copy(showOptions = show) }

    /** The search FAB: on My plan the bar is always there, so focus it; elsewhere it opens over the tab. */
    fun openAssistant() {
        if (current().selectedItem == NavItem.PLAN) _state.update { it.copy(focusInput = it.focusInput + 1, showOptions = false) }
        else _state.update { it.copy(assistantOpen = true, showOptions = false) }
    }

    fun closeAssistant() = _state.update { it.copy(assistantOpen = false, searchRequest = null) }

    /** The bar took the request; [keepOpen] = the bar over a tab (My plan's bar is always there). */
    fun consumeSearchRequest(keepOpen: Boolean = true) = _state.update {
        it.copy(searchRequest = null, assistantOpen = if (keepOpen) it.assistantOpen || it.searchRequest != null else it.assistantOpen)
    }

    /** Opens the My plan tab. */
    fun showPlan() {
        if (NavItem.PLAN !in current().tabs.items) return
        _state.update { it.copy(selectedItem = NavItem.PLAN, showOptions = false, assistantOpen = false, showPlanRequest = it.showPlanRequest + 1) }
    }

    /** A plan edit previews in place on My plan (typed on another tab, a hint, a like clash). */
    fun showProposal() {
        if (NavItem.PLAN in current().tabs.items) _state.update { it.copy(selectedItem = NavItem.PLAN, assistantOpen = false, searchRequest = null, showOptions = false) }
    }

    /** My plan's bar runs [text] (an example question: Ask when the model is there). */
    fun ask(text: String) = _state.update {
        it.copy(showOptions = false, selectedItem = NavItem.PLAN, searchRequest = SearchRequest(text, (it.searchRequest?.serial ?: 0) + 1, ask = true))
    }

    /**
     * An internal open link's target (notification and Live Update taps). An item opens on its own tab
     * when the event's [tabs] show it, otherwise in the bar (its detail on the bar's back stack).
     * [OpenTarget.EventInfo] opens Options and [OpenTarget.MyPlan] My plan.
     */
    fun route(target: OpenTarget, tabs: List<Tab>) {
        // Searches go to My plan's input bar; items of hidden tabs open in the bar over the current tab.
        fun search(query: String, detail: SearchEntry? = null) = _state.update {
            val base = if (detail == null && NavItem.PLAN in current().tabs.items) it.copy(selectedItem = NavItem.PLAN) else it
            base.copy(showOptions = false, searchRequest = SearchRequest(query, (it.searchRequest?.serial ?: 0) + 1, detail))
        }
        when (target) {
            is OpenTarget.Location ->
                if (Tab.LOCATIONS in tabs) {
                    showOptions(false)
                    focusLocation(target.id)
                } else {
                    search("", SearchEntry(SearchKind.LOCATION, target.id))
                }
            is OpenTarget.ScheduleItem ->
                if (Tab.SCHEDULE in tabs) {
                    showOptions(false)
                    focusScheduleItem(target.id)
                } else {
                    search("", SearchEntry(SearchKind.SCHEDULE, target.id))
                }
            is OpenTarget.Notification -> when {
                Tab.NOTIFICATIONS !in tabs -> search("", target.id?.let { SearchEntry(SearchKind.NOTIFICATION, it) })
                target.id != null -> focusNotification(target.id)
                else -> _state.update { withTab(it, Tab.NOTIFICATIONS).copy(showOptions = false) }
            }
            OpenTarget.EventInfo -> showOptions(true)
            is OpenTarget.Search -> search(target.query)
            is OpenTarget.MapLocation ->
                if (Tab.MAP in tabs) showOnMap(target.id)
                else if (Tab.LOCATIONS in tabs) {
                    showOptions(false)
                    focusLocation(target.id)
                } else {
                    search("", SearchEntry(SearchKind.LOCATION, target.id))
                }
            OpenTarget.MyPlan -> showPlan()
        }
    }

    /**
     * `open(screen, id?)` from the assistant: closes the bar and shows the manual screen. Returns false
     * for a screen this controller doesn't own (`tour`), which the caller handles.
     */
    fun openScreen(screen: String, ref: EntityRef?, tabs: List<Tab>): Boolean {
        closeAssistant()
        when (screen) {
            "map" -> when {
                ref?.kind == RefKind.LOCATION && Tab.MAP in tabs -> showOnMap(ref.id)
                Tab.MAP in tabs -> selectTab(Tab.MAP)
            }
            "locations" -> if (ref?.kind == RefKind.LOCATION) focusLocation(ref.id) else if (Tab.LOCATIONS in tabs) selectTab(Tab.LOCATIONS)
            "schedule" -> if (ref?.kind == RefKind.SCHEDULE) focusScheduleItem(ref.id) else if (Tab.SCHEDULE in tabs) selectTab(Tab.SCHEDULE)
            "updates" -> if (ref?.kind == RefKind.UPDATE && Tab.NOTIFICATIONS in tabs) focusNotification(ref.id) else if (Tab.NOTIFICATIONS in tabs) selectTab(Tab.NOTIFICATIONS)
            "flyer" -> if (Tab.FLYER in tabs) selectTab(Tab.FLYER)
            "options" -> showOptions(true)
            "plan" -> showPlan()
            else -> return false
        }
        return true
    }

    /** A join: the selection is kept when [alreadyIn] the event, every one-shot request is dropped. */
    fun resetForJoin(alreadyIn: Boolean) = _state.update {
        it.copy(
            selectedItem = if (alreadyIn) it.selectedItem else null,
            showOptions = false,
            focusLocationId = null,
            focusScheduleItemId = null,
            focusNotificationId = null,
            mapFocusLocationId = null,
            searchRequest = null,
            assistantOpen = false,
        )
    }

    /** Leaving the event (the map focus is left as it was, as before the split). */
    fun resetForLeave() = _state.update {
        it.copy(
            showOptions = false,
            selectedItem = null,
            focusLocationId = null,
            focusScheduleItemId = null,
            focusNotificationId = null,
            searchRequest = null,
            assistantOpen = false,
        )
    }

    /** Explore's view for [eventId]: this session's choice, else the remembered one. */
    fun exploreView(eventId: Int): Tab? = _state.value.exploreViews[eventId] ?: store.exploreView(eventId)?.let(Tab::fromKey)
}
