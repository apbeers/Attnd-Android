package com.arcu.attnd.ui

import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventTab
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.TabLayout
import com.arcu.attnd.ui.screens.SearchEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the app is (iOS `AppState`'s tab, sheet and focus state): the selected tab, one-shot focus
 * requests, the search sheet, My plan and Options.
 */
data class NavState(
    /** The tab chosen by the user; null (or a tab the event no longer shows) = the layout's default. */
    val selectedTab: EventTab? = null,
    /** Incremented to focus My plan's input bar when it opens from the My plan button. */
    val focusInput: Int = 0,
    val showOptions: Boolean = false,
    val focusLocationId: Int? = null,
    val focusScheduleItemId: Int? = null,
    val focusNotificationId: Int? = null,
    /** The search sheet (no AI; iOS `EventSearchSheet`) is open. */
    val searchOpen: Boolean = false,
    /** A one-shot request for the search sheet: a query and/or an item pushed on its stack. */
    val searchRequest: SearchRequest? = null,
    /** My plan is open (a full-screen destination until the phase 6b chat). */
    val planOpen: Boolean = false,
    /** A one-shot request for My plan's input bar (an onboarding question, a typed plan request). */
    val planRequest: SearchRequest? = null,
    val recenterRequest: Int = 0,
    val mapFocusLocationId: Int? = null,
    val showPlanRequest: Int = 0,
)

/**
 * The navigation part of the event UI, split out of [EventViewModel]. Pure Kotlin: [current] is the
 * resolved [AppUiState] (its [AppUiState.tabs] layout, selected tab and bundle), read the same way
 * the ViewModel reads it. "Is this tab shown" always asks the layout on screen, so screenshot mode's
 * legacy tabs and the five-tab cut count.
 */
class NavigationController(private val current: () -> AppUiState) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val layout: TabLayout get() = current().tabs

    /** For the few multi-field changes the ViewModel makes on join, leave and in screenshot mode. */
    fun update(transform: (NavState) -> NavState) = _state.update(transform)

    /** Shows [tab]'s screen; reselecting the map recenters it. A tab the event doesn't show changes nothing. */
    fun selectTab(tab: Tab) = selectEventTab(EventTab.of(tab))

    /** A navigation item tapped (Home or one of the event's tabs). */
    fun selectEventTab(tab: EventTab) {
        val ui = current()
        if (tab !in ui.tabs) return
        if (tab == EventTab.MAP && ui.selectedEventTab == EventTab.MAP && !ui.showOptions) {
            _state.update { it.copy(recenterRequest = it.recenterRequest + 1) }
            return
        }
        _state.update { it.copy(selectedTab = tab) }
    }

    private fun NavState.on(tab: Tab): NavState = if (tab in layout) copy(selectedTab = EventTab.of(tab)) else this

    /** The Notifications tab, scrolled to [notificationId] when given (a push tap). */
    fun showNotifications(notificationId: Int?) = _state.update {
        it.on(Tab.NOTIFICATIONS).copy(showOptions = false, focusNotificationId = notificationId ?: it.focusNotificationId)
    }

    /** Shows a location's detail: on the Places tab, or as an overlay when that tab is hidden. */
    fun focusLocation(locationId: Int) = _state.update { it.on(Tab.LOCATIONS).copy(focusLocationId = locationId) }

    fun consumeFocusLocation() = _state.update { it.copy(focusLocationId = null) }

    fun focusScheduleItem(itemId: Int) = _state.update { it.on(Tab.SCHEDULE).copy(focusScheduleItemId = itemId) }

    fun consumeFocusScheduleItem() = _state.update { it.copy(focusScheduleItemId = null) }

    /** Shows a notification on the Updates tab (or as an overlay when that tab is hidden). */
    fun focusNotification(notificationId: Int) =
        _state.update { it.on(Tab.NOTIFICATIONS).copy(focusNotificationId = notificationId, showOptions = false) }

    fun consumeFocusNotification() = _state.update { it.copy(focusNotificationId = null) }

    /** Selects a location on the Map tab (when the event shows one); closes the search sheet and My plan. */
    fun showOnMap(locationId: Int) {
        if (Tab.MAP !in layout) return
        _state.update {
            it.on(Tab.MAP).copy(showOptions = false, searchOpen = false, searchRequest = null, planOpen = false, mapFocusLocationId = locationId)
        }
    }

    fun consumeMapFocus() = _state.update { it.copy(mapFocusLocationId = null) }

    fun showOptions(show: Boolean) = _state.update { it.copy(showOptions = show) }

    /** The search button: opens the search sheet with the field focused. */
    fun openSearch() = _state.update { it.copy(searchOpen = true, showOptions = false) }

    fun closeSearch() = _state.update { it.copy(searchOpen = false, searchRequest = null) }

    /** The sheet took its request (it stays open). */
    fun consumeSearchRequest() = _state.update { it.copy(searchRequest = null, searchOpen = it.searchOpen || it.searchRequest != null) }

    /** Opens My plan ([focus]: the My plan button focuses its input bar). */
    fun showPlan(focus: Boolean = false) = _state.update {
        it.copy(
            planOpen = true,
            showOptions = false,
            searchOpen = false,
            searchRequest = null,
            showPlanRequest = it.showPlanRequest + 1,
            focusInput = if (focus) it.focusInput + 1 else it.focusInput,
        )
    }

    fun closePlan() = _state.update { it.copy(planOpen = false, planRequest = null) }

    /** A plan edit previews in place on My plan (a hint, a like clash, a shared plan). */
    fun showProposal() = _state.update { it.copy(planOpen = true, searchOpen = false, searchRequest = null, showOptions = false) }

    /** My plan's bar runs [text] (an example question: Ask when the model is there). */
    fun ask(text: String) = _state.update {
        it.copy(showOptions = false, planOpen = true, planRequest = SearchRequest(text, (it.planRequest?.serial ?: 0) + 1, ask = true))
    }

    fun consumePlanRequest() = _state.update { it.copy(planRequest = null) }

    private fun search(query: String, detail: SearchEntry? = null) = _state.update {
        it.copy(showOptions = false, searchOpen = true, searchRequest = SearchRequest(query, (it.searchRequest?.serial ?: 0) + 1, detail))
    }

    /**
     * An internal open link's target (notification and Live Update taps). An item opens on its own tab
     * when the layout shows it, otherwise in the search sheet (its detail on the sheet's stack, iOS
     * `navigate(to:)`). [OpenTarget.EventInfo] opens Options and [OpenTarget.MyPlan] My plan.
     */
    fun route(target: OpenTarget) {
        val tabs = layout
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
                else -> _state.update { it.on(Tab.NOTIFICATIONS).copy(showOptions = false) }
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
     * `open(screen, id?)` from the assistant: shows the manual screen (closing My plan). Returns false
     * for a screen this controller doesn't own (`tour`), which the caller handles.
     */
    fun openScreen(screen: String, ref: EntityRef?): Boolean {
        val tabs = layout
        if (screen != "plan" && screen != "tour") closePlan()
        when (screen) {
            "map" -> when {
                ref?.kind == RefKind.LOCATION && Tab.MAP in tabs -> showOnMap(ref.id)
                Tab.MAP in tabs -> selectTab(Tab.MAP)
            }
            "locations" -> if (ref?.kind == RefKind.LOCATION) focusLocation(ref.id) else if (Tab.LOCATIONS in tabs) selectTab(Tab.LOCATIONS)
            "schedule" -> if (ref?.kind == RefKind.SCHEDULE) focusScheduleItem(ref.id) else if (Tab.SCHEDULE in tabs) selectTab(Tab.SCHEDULE)
            "updates" -> if (ref?.kind == RefKind.UPDATE && Tab.NOTIFICATIONS in tabs) focusNotification(ref.id) else if (Tab.NOTIFICATIONS in tabs) selectTab(Tab.NOTIFICATIONS)
            "flyer" -> if (Tab.FLYER in tabs) selectTab(Tab.FLYER)
            "home" -> selectEventTab(EventTab.HOME)
            "options" -> showOptions(true)
            "plan" -> showPlan()
            else -> return false
        }
        return true
    }

    /** A join: the selection is kept when [alreadyIn] the event, every one-shot request is dropped. */
    fun resetForJoin(alreadyIn: Boolean) = _state.update {
        it.copy(
            selectedTab = if (alreadyIn) it.selectedTab else null,
            showOptions = false,
            focusLocationId = null,
            focusScheduleItemId = null,
            focusNotificationId = null,
            mapFocusLocationId = null,
            searchRequest = null,
            searchOpen = false,
            planRequest = null,
            planOpen = if (alreadyIn) it.planOpen else false,
        )
    }

    /** Leaving the event (the map focus is left as it was, as before the split). */
    fun resetForLeave() = _state.update {
        it.copy(
            showOptions = false,
            selectedTab = null,
            focusLocationId = null,
            focusScheduleItemId = null,
            focusNotificationId = null,
            searchRequest = null,
            searchOpen = false,
            planRequest = null,
            planOpen = false,
        )
    }
}
