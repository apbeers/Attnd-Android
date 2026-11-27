@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.arcu.attnd.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.arcu.attnd.ui.components.PaneSnackbarHost
import com.arcu.attnd.ui.components.TouchTracker
import com.arcu.attnd.ui.components.trackTouches
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTab
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.TabLayout
import com.arcu.attnd.domain.UpdateLevel
import com.arcu.attnd.ui.assistant.AssistantController
import com.arcu.attnd.ui.assistant.AssistantEffect
import com.arcu.attnd.ui.assistant.AssistantIntents
import com.arcu.attnd.ui.assistant.BlockActions
import com.arcu.attnd.ui.assistant.AskAttndChat
import com.arcu.attnd.ui.assistant.RunningLateCard
import com.arcu.attnd.ui.components.EventActions
import com.arcu.attnd.ui.components.InAppBannerCard
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.StatusPills
import com.arcu.attnd.ui.components.SwipeUpToDismiss
import com.arcu.attnd.ui.components.UpdateBanner
import com.arcu.attnd.ui.components.rememberUserLocation
import com.arcu.attnd.ui.screens.EventTopBar
import com.arcu.attnd.ui.screens.FlyerScreen
import com.arcu.attnd.ui.screens.HomeScreen
import com.arcu.attnd.ui.screens.LaunchScreen
import com.arcu.attnd.ui.screens.LocationDetail
import com.arcu.attnd.ui.screens.LocationsTab
import com.arcu.attnd.ui.screens.LogViewerSource
import com.arcu.attnd.ui.screens.NotificationsScreen
import com.arcu.attnd.ui.screens.OnboardingScreen
import com.arcu.attnd.ui.screens.OptionsScreen
import com.arcu.attnd.ui.screens.ScheduleItemDetail
import com.arcu.attnd.ui.screens.ScheduleTab
import com.arcu.attnd.ui.screens.SearchFab
import com.arcu.attnd.ui.screens.SearchSheet
import com.arcu.attnd.ui.screens.UpdateAvailableBanner
import com.arcu.attnd.ui.screens.UpdateRequiredScreen
import com.arcu.attnd.ui.theme.AttndTheme
import com.arcu.attnd.ui.components.LocalScreenEnv
import com.arcu.attnd.ui.components.rememberLayoutSections
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.ui.components.ScreenEnv
import com.arcu.attnd.ui.components.ComponentHost
import com.arcu.attnd.ui.components.withOpen
import com.arcu.attnd.data.AssistantAvailability
import kotlinx.coroutines.delay

/** Root composable: theme (the event's API-023 theme, else its accent colour) and launch vs. event UI. */
@Composable
fun AttndApp(viewModel: EventViewModel = viewModel(factory = EventViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val serverChoice by viewModel.serverChoice.collectAsStateWithLifecycle()
    AttndTheme(eventTheme = state.bundle?.event?.theme, accentColor = state.bundle?.display?.accentColor, screenshot = state.isScreenshot) {
        // API-024 screens: the bundle's layouts (never in screenshot mode) and what their conditions read.
        val screenEnv = ScreenEnv(
            layoutsEnabled = !state.isScreenshot,
            modelAvailable = state.assistant.availability == AssistantAvailability.AVAILABLE,
            hasSchedule = state.mySchedule?.isEmpty == false,
        )
        CompositionLocalProvider(LocalScreenshotMode provides state.isScreenshot, LocalScreenEnv provides screenEnv) {
            if (state.update.level == UpdateLevel.REQUIRED) {
                UpdateRequiredScreen(state.update)
                return@CompositionLocalProvider
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
                        val bundle = state.bundle ?: return@CompositionLocalProvider
                        val supportInfo = state.supportInfo
                        if (state.showOptions && supportInfo != null) {
                            val context = LocalContext.current
                            OptionsScreen(
                                event = bundle.event,
                                bundle = bundle,
                                onLocation = { id ->
                                    viewModel.showOptions(false)
                                    viewModel.open(OpenRequest(bundle.event.id, OpenTarget.Location(id)))
                                },
                                supportInfo = supportInfo,
                                notificationsEnabled = state.notificationsEnabled,
                                onNotificationsChange = viewModel::setNotificationsEnabled,
                                showUserLocation = state.showUserLocation,
                                onShowUserLocationChange = viewModel::setShowUserLocation,
                                followLive = state.followLive,
                                onFollowLiveChange = viewModel::setFollowLive,
                                onTour = viewModel::showTour,
                                onLeaveEvent = viewModel::leaveEvent,
                                onBack = { viewModel.showOptions(false) },
                                diagnostics = state.diagnostics,
                                onStartDebugMode = viewModel::startDebugMode,
                                onStopDebugMode = viewModel::stopDebugMode,
                                logs = remember(viewModel) {
                                    LogViewerSource(
                                        load = viewModel::logRecords,
                                        export = viewModel::exportLogs,
                                        uploadStatus = { viewModel.logUploadStatus },
                                    )
                                },
                                statusBadges = state.allStatusBadges,
                                lastUpdated = state.sync.lastSyncedAt?.let { at ->
                                    DateFormat.getTimeFormat(context).format(java.util.Date(at))
                                },
                                permissionAsked = viewModel::permissionAsked,
                                onPermissionAsked = viewModel::markPermissionAsked,
                                serverChoice = serverChoice,
                                onSwitchServer = viewModel::switchServer,
                            )
                        } else {
                            // Hearts and "Show on map" for every row, detail, card and the search sheet.
                            val hasMap = Tab.MAP in state.tabs
                            val actions = remember(state.hearts, state.mySchedule, bundle, hasMap, viewModel) {
                                EventActions(
                                    hearts = state.hearts,
                                    onToggleHeart = viewModel::toggleHeart,
                                    onShowOnMap = if (hasMap) { id -> viewModel.showOnMap(id) } else null,
                                    bundle = bundle,
                                    schedule = state.mySchedule,
                                    onAdd = viewModel::addToSchedule,
                                    onRemove = viewModel::removeFromSchedule,
                                    onSetPlaceTime = viewModel::setPlaceTime,
                                    onSetReminder = viewModel::setScheduleReminder,
                                    onKeepOrganizerChange = viewModel::keepOrganizerChange,
                                    onFindAnother = viewModel::findAnother,
                                    scheduleShareLink = { viewModel.assistantController.shareLink("schedule") },
                                    onShareSchedule = { via -> viewModel.assistantController.shareFromBlock("schedule", via) },
                                )
                            }
                            CompositionLocalProvider(LocalEventActions provides actions) {
                                EventShell(state, bundle, viewModel)
                            }
                            AssistantEffects(viewModel.assistantController)
                            // First join of an event (or Options → Tour); never in screenshot mode.
                            if (state.showOnboarding) {
                                OnboardingScreen(
                                    bundle = bundle,
                                    planner = state.planner,
                                    onExample = viewModel::useExample,
                                    onShowUserLocation = viewModel::setShowUserLocation,
                                    onNotificationsGranted = { viewModel.setNotificationsEnabled(true) },
                                    onDone = viewModel::finishOnboarding,
                                    followsLive = state.followLive,
                                    onFollowLive = viewModel::setFollowLive,
                                )
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.showUpdateBanner) {
                        SwipeUpToDismiss(onDismiss = viewModel::dismissUpdateBanner) {
                            UpdateAvailableBanner(update = state.update, onDismiss = viewModel::dismissUpdateBanner)
                        }
                    }
                    // An organizer update that arrived while the app is open (~6 s, urgent 10 s).
                    state.inAppBanner?.takeIf { state.phase == AppPhase.EVENT }?.let { banner ->
                        InAppBannerCard(banner, onOpen = viewModel::openInAppBanner, onDismiss = viewModel::dismissInAppBanner)
                    }
                }
            }
        }
    }
}

private val EventTab.navIcon: ImageVector
    get() = when (this) {
        EventTab.HOME -> Icons.Filled.Home
        EventTab.MAP -> Icons.Filled.Map
        EventTab.LOCATIONS -> Icons.AutoMirrored.Filled.List
        EventTab.SCHEDULE -> Icons.Filled.CalendarMonth
        EventTab.FLYER -> Icons.Filled.Description
        EventTab.NOTIFICATIONS -> Icons.Filled.Notifications
    }

/**
 * The joined-event UI (iOS `ContentView`): Home first and the default, then the event's own tabs
 * ([AppUiState.tabs]; today's tabs in screenshot mode). The navigation is a bottom bar on the
 * folded/cover screen and a rail on the unfolded inner display (NavigationSuiteScaffold picks this
 * from the window size class and posture). The organizer tabs have the event's top bar, with the
 * Options gear only on the Options tab's root (Home: on its header row). A floating button on every
 * tab opens the search sheet, or My plan when Gemini Nano is on the phone (plan §4.3).
 */
@Composable
private fun EventShell(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel) {
    val tabs = state.tabs
    val snackbar = remember { SnackbarHostState() }
    // The Undo toast stays in the pane that was tapped (unfolded foldables, iOS `UndoToastPane`).
    val touches = remember { TouchTracker() }
    val tabStates = rememberSaveableStateHolder()

    // Undo for a change made with one tap (Home's urgent card swiped away).
    LaunchedEffect(state.undoToast?.id) {
        val toast = state.undoToast ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(toast.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        viewModel.consumeUndo(toast, undone = result == SnackbarResult.ActionPerformed)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val chip = state.messageChip
        val result = snackbar.showSnackbar(message, actionLabel = if (chip != null) "Preview" else null, withDismissAction = chip != null)
        if (result == SnackbarResult.ActionPerformed && chip != null) viewModel.runMessageChip() else viewModel.consumeMessage()
    }

    val blockActions = rememberBlockActions(state, bundle, viewModel)
    val assistantState by viewModel.assistantController.state.collectAsStateWithLifecycle()
    // Live plan hints (assistant spec §6): position samples only while this is visible and a plan item
    // is on now or soon; recomputed once a minute.
    if (assistantState.wantsLocation && !state.isScreenshot) LiveLocationSampler(viewModel.assistantController::onLocation)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(bundle.event.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.assistantController.refreshLive()
                delay(60_000)
            }
        }
    }

    // Ask Attnd (phase 6b): the chat as a full-screen destination over the tabs.
    if (state.planOpen) {
        PlanDestination(state, bundle, viewModel, blockActions)
        return
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
    val navType = if (tabs.tabs.size < 2) NavigationSuiteType.None else NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    // The bottom bar pads itself for the system bars; with a rail (or nothing) the content must.
    val isBar = navType == NavigationSuiteType.ShortNavigationBarCompact || navType == NavigationSuiteType.ShortNavigationBarMedium
    val contentInsets = if (isBar) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
    }

    // The floating button (search sheet, or My plan with Gemini Nano). Where it sits follows the
    // Material patterns for each navigation: in the wide navigation rail's header on the unfolded
    // display; with the map showing (bar or no navigation) as the FAB of the map's vertical floating
    // toolbar, so the two never overlap; elsewhere in the Scaffold's FAB slot, bottom-end above the bar.
    val planButton = state.planButton
    val onFab: () -> Unit = if (planButton) viewModel::openPlan else viewModel::openSearch
    val isRail = navType == NavigationSuiteType.WideNavigationRailCollapsed ||
        navType == NavigationSuiteType.WideNavigationRailExpanded ||
        navType == NavigationSuiteType.NavigationRail
    val showFab = !state.searchOpen
    val fabInMapToolbar = showFab && !isRail && state.selectedTab == Tab.MAP
    val fabInScaffold = showFab && !isRail && !fabInMapToolbar
    val selected = state.selectedEventTab
    val onHome = selected == EventTab.HOME

    NavigationSuiteScaffold(
        navigationSuiteType = navType,
        navigationItems = {
            tabs.tabs.forEach { tab ->
                val unread = if (tab == EventTab.NOTIFICATIONS && tab != selected) state.unreadUpdates else 0
                NavigationSuiteItem(
                    selected = tab == selected,
                    onClick = { viewModel.selectEventTab(tab) },
                    icon = { Icon(tab.navIcon, contentDescription = null) },
                    label = { Text(TabLayout.title(tab, state.isScreenshot)) },
                    navigationSuiteType = navType,
                    badge = if (unread > 0) {
                        {
                            Badge(Modifier.semantics { contentDescription = "$unread unread" }) {
                                Text(if (unread > 99) "99+" else unread.toString())
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        },
        // Only shown by the rail (as its header); with a bar the FAB goes in the Scaffold below,
        // which also keeps snackbars above it.
        primaryActionContent = { if (isRail && showFab) SearchFab(onClick = onFab, plan = planButton) },
    ) {
        Scaffold(
            topBar = {
                // Home has no top app bar (its header row holds the gear), as on iOS.
                if (!onHome) {
                    EventTopBar(
                        bundle = bundle,
                        isRefreshing = state.sync.isRefreshing,
                        onOptions = if (tabs.optionsTab == selected) ({ viewModel.showOptions(true) }) else null,
                    )
                }
            },
            snackbarHost = {
                // The map trails its pane on the Map tab, so it is the primary pane there.
                PaneSnackbarHost(snackbar, touches)
            },
            floatingActionButton = { if (fabInScaffold) SearchFab(onClick = onFab, plan = planButton) },
            contentWindowInsets = contentInsets,
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .trackTouches(touches),
            ) {
                // Running late (assistant spec §6) on every tab.
                assistantState.live.runningLate?.takeIf { !state.isScreenshot }?.let { late ->
                    RunningLateCard(
                        late.text,
                        cantMake = late.state == LateState.CANT_MAKE_IT,
                        chips = late.chips,
                        onChip = { chip ->
                            viewModel.assistantController.run(chip.action, chip.label, ActionSource.RULES)?.let { message -> viewModel.showMessage(message) }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                // The newest unread organizer update, on the other tabs, for 24 h (swipe up or ✕). Home draws it
                // itself where its layout places an `update_banner` section (API-027).
                val homeLayout = rememberLayoutSections(LayoutScreen.HOME, bundle)
                val homePlacesBanner = selected == EventTab.HOME && homeLayout.any { it.type == "update_banner" }
                state.updateBanner?.takeIf { state.selectedTab != Tab.NOTIFICATIONS && !homePlacesBanner }?.let { update ->
                    SwipeUpToDismiss(onDismiss = { viewModel.dismissLatestUpdate(update.id) }) {
                        UpdateBanner(
                            notification = update,
                            bundle = bundle,
                            onOpen = { viewModel.open(OpenRequest(bundle.event.id, OpenTarget.Notification(update.id))) },
                            onDismiss = { viewModel.dismissLatestUpdate(update.id) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    tabStates.SaveableStateProvider(selected.key) {
                        TabContent(selected, state, bundle, viewModel, blockActions, onMapSearch = if (fabInMapToolbar) onFab else null)
                    }
                    // Float over the content (no layout space); each swipes away for the session.
                    StatusPills(state.statusBadges, onDismiss = viewModel::dismissStatusBadge, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
    // The search sheet (no AI): the floating button's target and links to items of hidden tabs.
    if (state.searchOpen) {
        SearchSheet(
            bundle = bundle,
            hearts = state.hearts,
            request = state.searchRequest,
            savedPlaces = state.mySchedule?.savedPlaceIds?.toSet().orEmpty(),
            onRequestConsumed = viewModel::consumeSearchRequest,
            onClose = viewModel::closeSearch,
            onSearch = viewModel::onSearch,
            onLocationShown = viewModel::onLocationShown,
            onScheduleItemShown = viewModel::onScheduleItemShown,
        )
    }
}

/** Ask Attnd (one list §7; was "My plan"): the chat with Close (iOS `PlanChatStack`), over the tabs. */
@Composable
private fun PlanDestination(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel, blockActions: BlockActions) {
    LaunchedEffect(Unit) { viewModel.checkAssistant() }
    AskAttndChat(
        controller = viewModel.assistantController,
        bundle = bundle,
        model = state.assistant,
        planner = state.planner,
        mySchedule = state.mySchedule,
        request = state.planRequest,
        focusInput = state.focusInput,
        actions = blockActions,
        onRequestConsumed = viewModel::consumePlanRequest,
        onSearch = viewModel::onSearch,
        onDownloadModel = viewModel::downloadAssistant,
        onClose = viewModel::closePlan,
        onLocationShown = viewModel::onLocationShown,
        onScheduleItemShown = viewModel::onScheduleItemShown,
    )
}

/** One tab's screen: Home, or an organizer tab (today's tabs in screenshot mode). */
@Composable
private fun TabContent(
    tab: EventTab,
    state: AppUiState,
    bundle: EventBundle,
    viewModel: EventViewModel,
    blockActions: BlockActions,
    onMapSearch: (() -> Unit)?,
) {
    when (tab) {
        EventTab.HOME -> {
            val eventId = bundle.event.id
            val homeActions = remember(blockActions, viewModel, eventId) {
                blockActions.withOpen { ref ->
                    val target = when (ref.kind) {
                        RefKind.SCHEDULE -> OpenTarget.ScheduleItem(ref.id)
                        RefKind.LOCATION -> OpenTarget.Location(ref.id)
                        RefKind.UPDATE -> OpenTarget.Notification(ref.id)
                    }
                    viewModel.open(OpenRequest(eventId, target))
                }
            }
            val showOptions = state.tabs.optionsTab == EventTab.HOME
            val host = remember(state.mySchedule, state.dismissedUrgentIds, state.updateBanner, showOptions, viewModel) {
                ComponentHost(
                    mySchedule = state.mySchedule,
                    updateBanner = state.updateBanner,
                    onDismissUpdate = viewModel::dismissLatestUpdate,
                    dismissedUrgent = state.dismissedUrgentIds,
                    onDismissUrgent = viewModel::dismissUrgent,
                    onEventInfo = { viewModel.open(OpenRequest(eventId, OpenTarget.EventInfo)) },
                    onSavedPlaces = viewModel::showSavedPlaces,
                    onOptions = if (showOptions) ({ viewModel.showOptions(true) }) else null,
                )
            }
            HomeScreen(
                bundle = bundle,
                actions = homeActions,
                host = host,
                isRefreshing = state.sync.isRefreshing,
                onRefresh = viewModel::refresh,
            )
        }
        EventTab.MAP -> MapTab(state, viewModel, onSearch = onMapSearch)
        EventTab.LOCATIONS -> LocationsTab(
            bundle = bundle,
            isRefreshing = state.sync.isRefreshing,
            onRefresh = viewModel::refresh,
            focusLocationId = state.focusLocationId,
            onFocusConsumed = viewModel::consumeFocusLocation,
            onScheduleItemClick = viewModel::focusScheduleItem,
            onLocationShown = viewModel::onLocationShown,
            onSearch = viewModel::onSearch,
        )
        EventTab.SCHEDULE -> ScheduleTab(
            bundle = bundle,
            isRefreshing = state.sync.isRefreshing,
            onRefresh = viewModel::refresh,
            focusItemId = state.focusScheduleItemId,
            onFocusConsumed = viewModel::consumeFocusScheduleItem,
            onLocationClick = viewModel::focusLocation,
            onItemShown = viewModel::onScheduleItemShown,
            onSearch = viewModel::onSearch,
        )
        EventTab.FLYER -> FlyerScreen(bundle.event.flyerUrl)
        EventTab.NOTIFICATIONS -> NotificationsScreen(
            bundle = bundle,
            isRefreshing = state.sync.isRefreshing,
            lastSyncedAt = state.sync.lastSyncedAt,
            onRefresh = viewModel::refresh,
            highlightId = state.focusNotificationId,
            onHighlightShown = viewModel::consumeFocusNotification,
        )
    }
}

/** What the assistant's cards and chips can do, wired to the view model (My plan and the bar). */
@Composable
private fun rememberBlockActions(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel): BlockActions {
    val controller = viewModel.assistantController
    val tabs = state.tabs
    return remember(bundle, state.planner.plan, state.hearts, state.followLive, state.remindersOn, state.notificationsEnabled, viewModel) {
        BlockActions(
            bundle = bundle,
            plan = state.planner.plan?.takeIf { !it.isEmpty },
            hearts = state.hearts,
            onChip = controller::chip,
            onOpenTab = { tab, ref ->
                viewModel.closeOverlays()
                when {
                    tab == Tab.SCHEDULE && ref?.kind == RefKind.SCHEDULE -> viewModel.focusScheduleItem(ref.id)
                    tab == Tab.LOCATIONS && ref?.kind == RefKind.LOCATION -> viewModel.focusLocation(ref.id)
                    else -> viewModel.selectTab(tab)
                }
            },
            onShowOnMap = { id ->
                if (Tab.MAP in tabs) {
                    viewModel.closeOverlays()
                    if (id != null) viewModel.showOnMap(id) else viewModel.selectTab(Tab.MAP)
                }
            },
            onDirections = { id -> controller.run(AssistantAction.directions(id), null, ActionSource.MANUAL) },
            onShare = { target, via -> controller.shareFromBlock(target, via) },
            shareLink = controller::shareLink,
            onSetting = { name, on -> controller.run(AssistantAction.toggle(name, on), null, ActionSource.MANUAL) },
            settingOn = { name ->
                when (name) {
                    ActionName.FOLLOW_LOCK_SCREEN -> state.followLive
                    ActionName.REMINDERS -> state.remindersOn
                    else -> state.notificationsEnabled
                }
            },
            onCheck = controller::toggleCheckOff,
            onUseVersion = { kind ->
                PlanVersionKind.of(kind)?.let { controller.run(AssistantAction.planVersion(it), "Use the ${it.label.lowercase()} version", ActionSource.MANUAL) }
            },
            onOpenFlyer = {
                if (Tab.FLYER in tabs) {
                    viewModel.closeOverlays()
                    viewModel.selectTab(Tab.FLYER)
                }
            },
            onOpenPlan = viewModel::showPlan,
        )
    }
}

/** Takes position samples for the live plan hints while composed (and resumed); none without permission. */
@Composable
private fun LiveLocationSampler(onSample: (GeoPoint) -> Unit) {
    val user = rememberUserLocation()
    LaunchedEffect(user) { user?.let(onSample) }
}

/** Carries out the assistant's OS hand-offs: share sheet, Maps, the notification permission. */
@Composable
private fun AssistantEffects(controller: AssistantController) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<AssistantAction?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { controller.onNotificationPermission(granted, it) }
        pending = null
    }
    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is AssistantEffect.Share -> AssistantIntents.share(context, effect.text, effect.title)
                is AssistantEffect.Directions -> AssistantIntents.directions(context, effect.point)
                is AssistantEffect.RequestNotifications -> {
                    pending = effect.action
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
