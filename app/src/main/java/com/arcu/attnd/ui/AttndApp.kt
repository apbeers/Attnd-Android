@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.arcu.attnd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.UpdateLevel
import com.arcu.attnd.ui.components.EventStatusPills
import com.arcu.attnd.ui.components.OfflineNotice
import com.arcu.attnd.ui.screens.EventSearch
import com.arcu.attnd.ui.screens.EventTopBar
import com.arcu.attnd.ui.screens.FlyerScreen
import com.arcu.attnd.ui.screens.LaunchScreen
import com.arcu.attnd.ui.screens.LocationDetail
import com.arcu.attnd.ui.screens.LocationsTab
import com.arcu.attnd.ui.screens.NotificationsScreen
import com.arcu.attnd.ui.screens.OptionsScreen
import com.arcu.attnd.ui.screens.ScheduleItemDetail
import com.arcu.attnd.ui.screens.ScheduleTab
import com.arcu.attnd.ui.screens.SearchFab
import com.arcu.attnd.ui.screens.UpdateAvailableBanner
import com.arcu.attnd.ui.screens.UpdateRequiredScreen
import com.arcu.attnd.ui.theme.AttndTheme
import kotlinx.coroutines.launch

/** Root composable: theme (with the event's accent colour) and launch vs. event UI. */
@Composable
fun AttndApp(viewModel: EventViewModel = viewModel(factory = EventViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accent = remember(state.bundle?.display?.accentColor) {
        DisplayConfig.parseHexColor(state.bundle?.display?.accentColor)?.let { Color(it) }
    }

    AttndTheme(accentColor = accent) {
        if (state.update.level == UpdateLevel.REQUIRED) {
            UpdateRequiredScreen(state.update)
            return@AttndTheme
        }
        Box(Modifier.fillMaxSize()) {
            when (state.phase) {
                AppPhase.LOADING -> Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                }
                AppPhase.LAUNCH -> LaunchScreen(
                    join = state.join,
                    nearby = state.nearby,
                    message = state.message ?: state.sync.error,
                    onJoinCode = viewModel::joinByCode,
                    onJoinEvent = viewModel::joinEvent,
                    onNearbyLoading = viewModel::setNearbyLoading,
                    onNearbyLocation = viewModel::loadNearby,
                    onNearbyError = viewModel::setNearbyError,
                    onClearJoinError = viewModel::clearJoinError,
                )
                AppPhase.EVENT -> {
                    val bundle = state.bundle ?: return@AttndTheme
                    val supportInfo = state.supportInfo
                    if (state.showOptions && supportInfo != null) {
                        OptionsScreen(
                            event = bundle.event,
                            supportInfo = supportInfo,
                            notificationsEnabled = state.notificationsEnabled,
                            onNotificationsChange = viewModel::setNotificationsEnabled,
                            showUserLocation = state.showUserLocation,
                            onShowUserLocationChange = viewModel::setShowUserLocation,
                            onLeaveEvent = viewModel::leaveEvent,
                            onBack = { viewModel.showOptions(false) },
                        )
                    } else {
                        EventShell(state, bundle, viewModel)
                    }
                }
            }
            if (state.showUpdateBanner) {
                UpdateAvailableBanner(
                    update = state.update,
                    onDismiss = viewModel::dismissUpdateBanner,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

private val Tab.label: String
    get() = when (this) {
        Tab.MAP -> "Map"
        Tab.LOCATIONS -> "Locations"
        Tab.SCHEDULE -> "Schedule"
        Tab.FLYER -> "Flyer"
        Tab.NOTIFICATIONS -> "Updates"
    }

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.MAP -> Icons.Filled.Map
        Tab.LOCATIONS -> Icons.AutoMirrored.Filled.List
        Tab.SCHEDULE -> Icons.Filled.CalendarMonth
        Tab.FLYER -> Icons.Filled.Description
        Tab.NOTIFICATIONS -> Icons.Filled.Notifications
    }

/**
 * The joined-event UI. Tabs come from `display.tabs` (order, visibility, default; at most
 * [DisplayConfig.MAX_TABS], never a "more" item). The navigation is a bottom bar on the
 * folded/cover screen and a rail on the unfolded inner display (NavigationSuiteScaffold picks this
 * from the window size class and posture). Options sits in the top bar, search behind a FAB.
 */
@Composable
private fun EventShell(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel) {
    val tabs = bundle.display.tabs
    val snackbar = remember { SnackbarHostState() }
    val tabStates = rememberSaveableStateHolder()

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // A location/schedule item requested while its tab is hidden is shown full screen instead.
    val overlayLocation = state.focusLocationId?.takeIf { Tab.LOCATIONS !in tabs }?.let { bundle.location(it) }
    val overlayItem = state.focusScheduleItemId?.takeIf { Tab.SCHEDULE !in tabs }?.let { bundle.scheduleItem(it) }
    if (overlayLocation != null) {
        LaunchedEffect(overlayLocation.id) { viewModel.onLocationShown(overlayLocation.id) }
        BackHandler { viewModel.consumeFocusLocation() }
        Surface(Modifier.fillMaxSize()) {
            LocationDetail(bundle, overlayLocation, onBack = viewModel::consumeFocusLocation, onScheduleItemClick = viewModel::focusScheduleItem)
        }
        return
    }
    if (overlayItem != null) {
        LaunchedEffect(overlayItem.id) { viewModel.onScheduleItemShown(overlayItem.id) }
        BackHandler { viewModel.consumeFocusScheduleItem() }
        Surface(Modifier.fillMaxSize()) {
            ScheduleItemDetail(bundle, overlayItem, onBack = viewModel::consumeFocusScheduleItem, onLocationClick = viewModel::focusLocation)
        }
        return
    }

    // Material 3 Expressive navigation: a short navigation bar (folded / cover screen, compact width;
    // items side by side with their labels when the height is compact or in tabletop posture) and a
    // collapsed wide navigation rail on the unfolded inner display.
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val navType = if (tabs.size < 2) NavigationSuiteType.None else NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    // The bottom bar pads itself for the system bars; with a rail (or nothing) the content must.
    val isBar = navType == NavigationSuiteType.ShortNavigationBarCompact || navType == NavigationSuiteType.ShortNavigationBarMedium
    val contentInsets = if (isBar) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
    }

    // App-wide search: a FAB opens the full-screen search (EventSearch, always composed so shortcut /
    // AppFunctions / attnd-open requests can open it too). Where the FAB sits follows the Material
    // patterns for each navigation: in the wide navigation rail's header on the unfolded display;
    // on the Map tab (bar or no navigation) as the FAB of the map's vertical floating toolbar, so the
    // two never overlap; elsewhere in the Scaffold's FAB slot, bottom-end above the navigation bar.
    val searchBarState = rememberSearchBarState()
    val scope = rememberCoroutineScope()
    val openSearch: () -> Unit = { scope.launch { searchBarState.animateToExpanded() } }
    val isRail = navType == NavigationSuiteType.WideNavigationRailCollapsed ||
        navType == NavigationSuiteType.WideNavigationRailExpanded ||
        navType == NavigationSuiteType.NavigationRail
    val fabInMapToolbar = !isRail && state.selectedTab == Tab.MAP
    val fabInScaffold = !isRail && !fabInMapToolbar

    NavigationSuiteScaffold(
        navigationSuiteType = navType,
        navigationItems = {
            tabs.forEach { tab ->
                NavigationSuiteItem(
                    selected = tab == state.selectedTab,
                    onClick = { viewModel.selectTab(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                    navigationSuiteType = navType,
                )
            }
        },
        // Only shown by the rail (as its header); with a bar the FAB goes in the Scaffold below,
        // which also keeps snackbars above it.
        primaryActionContent = { if (isRail) SearchFab(onClick = openSearch) },
    ) {
        Scaffold(
            topBar = {
                EventTopBar(
                    bundle = bundle,
                    isRefreshing = state.sync.isRefreshing,
                    onOptions = { viewModel.showOptions(true) },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = { if (fabInScaffold) SearchFab(onClick = openSearch) },
            contentWindowInsets = contentInsets,
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                OfflineNotice(state.sync)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    tabStates.SaveableStateProvider(state.selectedTab.key) {
                        TabContent(state, bundle, viewModel, onMapSearch = if (fabInMapToolbar) openSearch else null)
                    }
                    // Floats over the content (no layout space) so demo / ended events stay obvious.
                    EventStatusPills(bundle.event, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }

    EventSearch(
        state = searchBarState,
        bundle = bundle,
        request = state.searchRequest,
        assistant = state.assistant,
        onRequestConsumed = viewModel::consumeSearchRequest,
        onSearch = viewModel::onSearch,
        onLocationShown = viewModel::onLocationShown,
        onScheduleItemShown = viewModel::onScheduleItemShown,
        onCheckAssistant = viewModel::checkAssistant,
        onDownloadAssistant = viewModel::downloadAssistant,
        onAsk = viewModel::ask,
        onClearAnswer = viewModel::clearAnswer,
    )
}

@Composable
private fun TabContent(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel, onMapSearch: (() -> Unit)?) {
    when (state.selectedTab) {
        Tab.MAP -> MapTab(state, viewModel, onSearch = onMapSearch)
        Tab.LOCATIONS -> LocationsTab(
            bundle = bundle,
            isRefreshing = state.sync.isRefreshing,
            onRefresh = viewModel::refresh,
            focusLocationId = state.focusLocationId,
            onFocusConsumed = viewModel::consumeFocusLocation,
            onScheduleItemClick = viewModel::focusScheduleItem,
            onLocationShown = viewModel::onLocationShown,
            onSearch = viewModel::onSearch,
        )
        Tab.SCHEDULE -> ScheduleTab(
            bundle = bundle,
            isRefreshing = state.sync.isRefreshing,
            onRefresh = viewModel::refresh,
            focusItemId = state.focusScheduleItemId,
            onFocusConsumed = viewModel::consumeFocusScheduleItem,
            onLocationClick = viewModel::focusLocation,
            onItemShown = viewModel::onScheduleItemShown,
            onSearch = viewModel::onSearch,
        )
        Tab.FLYER -> FlyerScreen(bundle.event.flyerUrl)
        Tab.NOTIFICATIONS -> NotificationsScreen(
            bundle = bundle,
            isRefreshing = state.sync.isRefreshing,
            lastSyncedAt = state.sync.lastSyncedAt,
            onRefresh = viewModel::refresh,
            highlightId = state.focusNotificationId,
            onHighlightShown = viewModel::consumeFocusNotification,
        )
    }
}
