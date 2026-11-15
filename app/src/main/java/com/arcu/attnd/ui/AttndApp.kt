@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.arcu.attnd.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.NavItem
import com.arcu.attnd.domain.EventTabModel
import com.arcu.attnd.domain.UpdateLevel
import com.arcu.attnd.ui.components.EventActions
import com.arcu.attnd.ui.components.EventStatusPills
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.OfflineNotice
import com.arcu.attnd.ui.components.UpdateBanner
import com.arcu.attnd.ui.screens.EventTopBar
import com.arcu.attnd.ui.screens.FlyerScreen
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
import com.arcu.attnd.ui.screens.UpdateAvailableBanner
import com.arcu.attnd.ui.screens.UpdateRequiredScreen
import com.arcu.attnd.ui.theme.AttndTheme
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.ui.assistant.AssistantController
import com.arcu.attnd.ui.assistant.AssistantEffect
import com.arcu.attnd.ui.assistant.AssistantIntents
import com.arcu.attnd.ui.assistant.AssistantBarOverlay
import com.arcu.attnd.ui.assistant.MyPlanTab
import com.arcu.attnd.ui.assistant.BlockActions
import com.arcu.attnd.ui.assistant.RunningLateCard
import com.arcu.attnd.ui.components.rememberUserLocation
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Root composable: theme (with the event's accent colour) and launch vs. event UI. */
@Composable
fun AttndApp(viewModel: EventViewModel = viewModel(factory = EventViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accent = remember(state.bundle?.display?.accentColor) {
        DisplayConfig.parseHexColor(state.bundle?.display?.accentColor)?.let { Color(it) }
    }

    AttndTheme(accentColor = accent) {
        CompositionLocalProvider(LocalScreenshotMode provides state.isScreenshot) {
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
                            OptionsScreen(
                                event = bundle.event,
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
                            )
                        } else {
                            // Hearts and "Show on map" for every row, detail, card and the search screen.
                            val hasMap = Tab.MAP in bundle.display.tabs
                            val actions = remember(state.hearts, hasMap, viewModel, state.isScreenshot) {
                                EventActions(
                                    hearts = state.hearts,
                                    onToggleHeart = viewModel::toggleHeart,
                                    onShowOnMap = if (hasMap) { id -> viewModel.closeAssistant(); viewModel.showOnMap(id) } else null,
                                    onAskAbout = if (state.isScreenshot) null else viewModel::askAbout,
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
                                )
                            }
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
}

private val NavItem.label: String
    get() = when (this) {
        NavItem.PLAN -> "My plan"
        NavItem.EXPLORE -> "Explore"
        NavItem.MAP -> "Map"
        NavItem.LOCATIONS -> "Locations"
        NavItem.SCHEDULE -> "Schedule"
        NavItem.FLYER -> "Flyer"
        NavItem.UPDATES -> "Updates"
    }

/** Distinct icons: My plan `AutoAwesome`, Explore `Map` (the legacy Map tab never sits beside Explore). */
private val NavItem.icon: ImageVector
    get() = when (this) {
        NavItem.PLAN -> Icons.Filled.AutoAwesome
        NavItem.EXPLORE, NavItem.MAP -> Icons.Filled.Map
        NavItem.LOCATIONS -> Icons.AutoMirrored.Filled.List
        NavItem.SCHEDULE -> Icons.Filled.CalendarMonth
        NavItem.FLYER -> Icons.Filled.Description
        NavItem.UPDATES -> Icons.Filled.Notifications
    }

/**
 * The joined-event UI (plan tab spec): My plan, Explore (Map | Schedule | Places), Flyer and
 * Updates ([AppUiState.tabs]; today's tabs in screenshot mode). The navigation is a bottom bar on the
 * folded/cover screen and a rail on the unfolded inner display (NavigationSuiteScaffold picks this
 * from the window size class and posture). Options sits in the top bar; My plan has its own input
 * bar, the other tabs a search FAB that opens the same bar over them.
 */
@Composable
private fun EventShell(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel) {
    val tabs = bundle.display.tabs
    val items = state.tabs.items
    val snackbar = remember { SnackbarHostState() }
    val tabStates = rememberSaveableStateHolder()

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val chip = state.messageChip
        val result = snackbar.showSnackbar(message, actionLabel = if (chip != null) "Preview" else null, withDismissAction = chip != null)
        if (result == SnackbarResult.ActionPerformed && chip != null) viewModel.runMessageChip() else viewModel.consumeMessage()
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
    val navType = if (items.size < 2) NavigationSuiteType.None else NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    // The bottom bar pads itself for the system bars; with a rail (or nothing) the content must.
    val isBar = navType == NavigationSuiteType.ShortNavigationBarCompact || navType == NavigationSuiteType.ShortNavigationBarMedium
    val contentInsets = if (isBar) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
    }

    // The search FAB (every tab but My plan, whose input bar is always there) opens the same bar over
    // the tab. Where it sits follows the Material patterns for each navigation: in the wide navigation
    // rail's header on the unfolded display; with the map showing (bar or no navigation) as the FAB of
    // the map's vertical floating toolbar, so the two never overlap; elsewhere in the Scaffold's FAB
    // slot, bottom-end above the navigation bar.
    val openSearch: () -> Unit = viewModel::openAssistant
    val onPlan = state.selectedItem == NavItem.PLAN
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
    val isRail = navType == NavigationSuiteType.WideNavigationRailCollapsed ||
        navType == NavigationSuiteType.WideNavigationRailExpanded ||
        navType == NavigationSuiteType.NavigationRail
    val showFab = !onPlan && !state.assistantOpen
    val fabInMapToolbar = showFab && !isRail && state.selectedTab == Tab.MAP
    val fabInScaffold = showFab && !isRail && !fabInMapToolbar
    val blockActions = rememberBlockActions(state, bundle, viewModel)

    NavigationSuiteScaffold(
        navigationSuiteType = navType,
        navigationItems = {
            items.forEach { item ->
                val unread = if (item == NavItem.UPDATES && item != state.selectedItem) state.unreadUpdates else 0
                NavigationSuiteItem(
                    selected = item == state.selectedItem,
                    onClick = { viewModel.selectItem(item) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label) },
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
        primaryActionContent = { if (isRail && showFab) SearchFab(onClick = openSearch) },
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
                // Running late (assistant spec §6) on every other tab (My plan shows it in its timeline).
                assistantState.live.runningLate?.takeIf { !state.isScreenshot && !onPlan }?.let { late ->
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
                // The newest unread organizer update, on the other tabs, for 24 h (dismissible).
                state.updateBanner?.takeIf { state.selectedTab != Tab.NOTIFICATIONS }?.let { update ->
                    UpdateBanner(
                        notification = update,
                        bundle = bundle,
                        onOpen = {
                            if (Tab.NOTIFICATIONS in tabs) viewModel.focusNotification(update.id)
                            else viewModel.open(OpenRequest(bundle.event.id, OpenTarget.Notification(update.id)))
                        },
                        onDismiss = { viewModel.dismissLatestUpdate(update.id) },
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    tabStates.SaveableStateProvider(state.selectedItem.key + ":" + (state.selectedTab?.key ?: "")) {
                        TabContent(state, bundle, viewModel, blockActions, onMapSearch = if (fabInMapToolbar) openSearch else null)
                    }
                    // Floats over the content (no layout space) so demo / ended events stay obvious.
                    EventStatusPills(bundle.event, Modifier.align(Alignment.TopCenter))
                    // The input bar over this tab (the search FAB's target; never in screenshot mode).
                    if (state.assistantOpen) {
                        AssistantBarOverlay(
                            controller = viewModel.assistantController,
                            bundle = bundle,
                            model = state.assistant,
                            request = state.searchRequest,
                            actions = blockActions,
                            onRequestConsumed = { viewModel.consumeSearchRequest() },
                            onClose = viewModel::closeAssistant,
                            onSearch = viewModel::onSearch,
                            onLocationShown = viewModel::onLocationShown,
                            onScheduleItemShown = viewModel::onScheduleItemShown,
                        )
                        LaunchedEffect(Unit) { viewModel.checkAssistant() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel, blockActions: BlockActions, onMapSearch: (() -> Unit)?) {
    when (state.selectedItem) {
        NavItem.PLAN -> {
            MyPlanTab(
                controller = viewModel.assistantController,
                bundle = bundle,
                model = state.assistant,
                planner = state.planner,
                request = state.searchRequest,
                focusInput = state.focusInput,
                actions = blockActions,
                onRequestConsumed = { viewModel.consumeSearchRequest(keepOpen = false) },
                onSearch = viewModel::onSearch,
                onDownloadModel = viewModel::downloadAssistant,
                onLocationShown = viewModel::onLocationShown,
                onScheduleItemShown = viewModel::onScheduleItemShown,
                onReplan = viewModel::replanFromNow,
                onKeepChanged = viewModel::keepChangedPlan,
            )
            LaunchedEffect(Unit) { viewModel.checkAssistant() }
        }
        NavItem.EXPLORE -> ExploreTab(state, bundle, viewModel, onMapSearch)
        else -> state.selectedTab?.let { ViewContent(it, state, bundle, viewModel, onMapSearch) }
    }
}

/**
 * Explore (plan tab spec): a segmented switch Map | Schedule | Places (only the views `display.tabs`
 * lists, none with one view) above that view's screen, which keeps its own adaptive layout (list-detail
 * panes, the map beside its secondary pane on an unfolded foldable). The choice is remembered per event.
 */
@Composable
private fun ExploreTab(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel, onMapSearch: (() -> Unit)?) {
    val views = state.tabs.exploreViews
    val view = state.selectedTab ?: return
    val views2 = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        if (state.tabs.hasSwitch) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                views.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = tab == view,
                        onClick = { viewModel.selectExploreView(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index, views.size),
                        label = { Text(EventTabModel.viewLabel(tab), maxLines = 1) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            views2.SaveableStateProvider(view.key) { ViewContent(view, state, bundle, viewModel, onMapSearch) }
        }
    }
}

/** One organizer tab's screen (Explore's views, Flyer, Updates; today's tabs in screenshot mode). */
@Composable
private fun ViewContent(tab: Tab, state: AppUiState, bundle: EventBundle, viewModel: EventViewModel, onMapSearch: (() -> Unit)?) {
    when (tab) {
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

/** What the assistant's cards and chips can do, wired to the view model (My plan and the bar). */
@Composable
private fun rememberBlockActions(state: AppUiState, bundle: EventBundle, viewModel: EventViewModel): BlockActions {
    val controller = viewModel.assistantController
    val tabs = bundle.display.tabs
    return remember(bundle, state.planner.plan, state.hearts, state.followLive, state.remindersOn, state.notificationsEnabled, viewModel) {
        BlockActions(
            bundle = bundle,
            plan = state.planner.plan?.takeIf { !it.isEmpty },
            hearts = state.hearts,
            onChip = controller::chip,
            onOpenTab = { tab, ref ->
                viewModel.closeAssistant()
                when {
                    tab == Tab.SCHEDULE && ref?.kind == RefKind.SCHEDULE -> viewModel.focusScheduleItem(ref.id)
                    tab == Tab.LOCATIONS && ref?.kind == RefKind.LOCATION -> viewModel.focusLocation(ref.id)
                    else -> viewModel.selectTab(tab)
                }
            },
            onShowOnMap = { id ->
                if (Tab.MAP in tabs) {
                    viewModel.closeAssistant()
                    if (id != null) viewModel.showOnMap(id) else viewModel.selectTab(Tab.MAP)
                }
            },
            onDirections = { id -> controller.run(AssistantAction.directions(id), null, ActionSource.MANUAL) },
            onShare = { target, via -> controller.shareFromBlock(target, via) },
            shareLink = controller::shareLink,
            onCalendar = { ids, plan ->
                val action = if (plan) AssistantAction.calendarPlan()
                else AssistantAction(ActionName.ADD_TO_CALENDAR, buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive("s$it") })) })
                controller.run(action, null, ActionSource.MANUAL)
            },
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
                    viewModel.closeAssistant()
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

/** Carries out the assistant's OS hand-offs: share sheet, calendar insert, Maps, the notification permission. */
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
                is AssistantEffect.Calendar -> AssistantIntents.addToCalendar(context, effect.entry)
                is AssistantEffect.Directions -> AssistantIntents.directions(context, effect.point)
                is AssistantEffect.RequestNotifications -> {
                    pending = effect.action
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
