package com.arcu.attnd.ui

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.InstallReferrer
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.PermissionsState
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.data.ScheduleController
import com.arcu.attnd.data.RefreshResult
import com.arcu.attnd.data.SyncState
import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.log.DiagnosticsController
import com.arcu.attnd.data.log.DiagnosticsState
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.AppUpdate
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.JoinLinks.ShareLink
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.LiveUpdates
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogFilter
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.LogRecord
import com.arcu.attnd.domain.NearbyEvent
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.domain.PlanRequest
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScreenshotRequest
import com.arcu.attnd.domain.ScreenshotScreen
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SupportInfo
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.EventTabModel
import com.arcu.attnd.domain.EventTabs
import com.arcu.attnd.domain.NavItem
import com.arcu.attnd.domain.UnreadUpdates
import com.arcu.attnd.domain.UpdateLevel
import com.arcu.attnd.domain.UsageMetric
import com.arcu.attnd.live.LiveUpdateController
import com.arcu.attnd.ui.assistant.AssistantController
import com.arcu.attnd.ui.assistant.AssistantHost
import com.arcu.attnd.ui.screens.SearchEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale

enum class AppPhase { LOADING, LAUNCH, EVENT }

data class JoinUiState(
    val isJoining: Boolean = false,
    val error: String? = null,
)

data class NearbyUiState(
    val isLoading: Boolean = false,
    val events: List<NearbyEvent> = emptyList(),
    val error: String? = null,
)

/** The planner (the My plan tab): every phone (likes + plain rules; Gemini Nano reads typed requests when available); never in screenshot mode. */
data class PlanUiState(
    val available: Boolean = false,
    val plan: Plan? = null,
    /** A planned item changed or disappeared in a content update since the plan was made. */
    val changed: Boolean = false,
    val isPlanning: Boolean = false,
    val error: String? = null,
    /** Example requests from this event's data (onboarding, the empty plan card). */
    val examples: List<PlanExample> = emptyList(),
    /** Incremented to show My plan (after planning, or from a link). */
    val showRequest: Int = 0,
)

/**
 * A one-shot request to open the search screen with [query] and, optionally, an item's [detail] on its
 * back stack (shortcuts and assistants; items whose tab is hidden open this way).
 */
data class SearchRequest(val query: String, val serial: Int, val detail: SearchEntry? = null, val ask: Boolean = false)

/** On-device Gemini Nano for the event assistant and the planner: availability and the model download. */
data class AssistantUiState(
    val availability: AssistantAvailability = AssistantAvailability.UNKNOWN,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val downloadError: String? = null,
)

/** Everything the UI renders. Screens receive slices of this plus callbacks; they hold no app state. */
data class AppUiState(
    val phase: AppPhase = AppPhase.LOADING,
    val bundle: EventBundle? = null,
    val sync: SyncState = SyncState(),
    /** The navigation (plan tab spec): My plan, Explore, Flyer, Updates; today's tabs in screenshot mode. */
    val tabs: EventTabs = EventTabModel.of(emptyList(), null),
    val selectedItem: NavItem = NavItem.PLAN,
    /** Explore's view (Map / Schedule / Places), remembered per event. */
    val exploreView: Tab? = null,
    /** The organizer tab whose screen is visible (Explore's view, Flyer, Updates); null on My plan. */
    val selectedTab: Tab? = null,
    /** Incremented to focus the My plan tab's input bar (the search FAB / shortcut while on My plan). */
    val focusInput: Int = 0,
    val join: JoinUiState = JoinUiState(),
    val nearby: NearbyUiState = NearbyUiState(),
    val showOptions: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val showUserLocation: Boolean = false,
    /** One-shot request to show a location's detail (from a map pin or a schedule item). */
    val focusLocationId: Int? = null,
    /** One-shot request to show a schedule item's detail (from a location's schedule). */
    val focusScheduleItemId: Int? = null,
    /** Scroll the Notifications tab to this notification and highlight it (from search / an assistant). */
    val focusNotificationId: Int? = null,
    /** Open the assistant with a query or an item (notification / Live Update link). */
    val searchRequest: SearchRequest? = null,
    /** The event assistant (the search FAB's target) is open. */
    val assistantOpen: Boolean = false,
    /** A follow-up for the snackbar's action button (e.g. "Add to plan instead of X?" after a like). */
    val messageChip: Chip? = null,
    /** Reminders before liked and planned items (assistant `reminders` setting). */
    val remindersOn: Boolean = false,
    val assistant: AssistantUiState = AssistantUiState(),
    /** Incremented when the Map tab is reselected, to recenter the camera. */
    val recenterRequest: Int = 0,
    /** One-shot user-facing message (snackbar / launch screen). */
    val message: String? = null,
    /** API-003: REQUIRED replaces the UI with the update screen. */
    val update: AppUpdate = AppUpdate(),
    /** A newer version exists and the user hasn't dismissed the banner for it. */
    val showUpdateBanner: Boolean = false,
    /** API-004: build, server, device and event details for Options → About & support. */
    val supportInfo: SupportInfo? = null,
    /** Hearted schedule items and locations of the joined event (on the device only). */
    val hearts: Hearts = Hearts.NONE,
    /** Organizer updates sent since the Updates tab was last seen (its navigation badge). */
    val unreadUpdates: Int = 0,
    /** The newest unread update (sent within 24 h, not dismissed), as a banner on the other tabs. */
    val updateBanner: EventNotification? = null,
    /** One-shot request to select a location on the map ("Show on map", peek). */
    val mapFocusLocationId: Int? = null,
    /** Store-screenshot mode: no location tracking, map glance features, badge or banner. */
    val isScreenshot: Boolean = false,
    val planner: PlanUiState = PlanUiState(),
    /** The onboarding tour (first join of an event, or Options → Tour). */
    val showOnboarding: Boolean = false,
    /** "Follow on Lock Screen" for the joined event (Live Update + reminders). */
    val followLive: Boolean = false,
    /** Debug logging and the log viewer (About & support; logging spec §2–3). */
    val diagnostics: DiagnosticsState = DiagnosticsState(dev = false),
)

private data class LocalState(
    val ready: Boolean = false,
    val join: JoinUiState = JoinUiState(),
    val nearby: NearbyUiState = NearbyUiState(),
    val notificationsEnabled: Boolean = false,
    val showUserLocation: Boolean = false,
    val message: String? = null,
    val messageChip: Chip? = null,
    val dismissedUpdateVersion: String? = null,
    /** Store-screenshot mode (debug builds): see [ScreenshotRequest]. */
    val screenshot: ScreenshotRequest? = null,
    /** The screenshot event is still being joined: keep showing the loading screen, not the launch screen. */
    val screenshotPending: Boolean = false,
    /** Per event: when its Updates tab was last seen (epoch ms) and which update banner was dismissed. */
    val updatesSeenAt: Map<Int, Long> = emptyMap(),
    val dismissedUpdateBanners: Map<Int, Int> = emptyMap(),
    val showOnboarding: Boolean = false,
)

/** Everything [EventViewModel.uiState] combines besides the repository's flows. */
private data class UiInputs(val local: LocalState, val nav: NavState, val planner: PlannerProgress, val model: AssistantUiState)

class EventViewModel(
    private val appContext: Context,
    private val repository: EventRepository,
    private val prefs: DevicePrefs,
    private val usage: UsageReporter,
    private val assistant: OnDeviceAssistant,
    private val heartStore: HeartStore,
    private val schedule: ScheduleController,
    private val permissions: PermissionsState,
    private val planStore: PlanStore,
    private val liveUpdates: LiveUpdateController,
    private val diagnostics: DiagnosticsController,
    private val perf: PerfRecorder,
) : ViewModel() {

    /** The parts of [SupportInfo] that never change while the process runs. */
    private val baseSupportInfo = SupportInfo(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        serverHost = BuildConfig.ATTND_SERVER,
        apiVersion = AttndApi.API_VERSION,
        osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        deviceId = repository.deviceId,
    )

    /** Set when the app leaves the foreground (and at launch): the next resume counts as `app_open`. */
    @Volatile
    private var pendingAppOpen = true

    /** Store-screenshot mode: no usage counts are sent (see [startScreenshot]). */
    @Volatile
    private var screenshotMode = false

    private val local = MutableStateFlow(
        LocalState(
            notificationsEnabled = prefs.notificationsEnabled,
            showUserLocation = prefs.showUserLocation,
            dismissedUpdateVersion = prefs.dismissedUpdateVersion,
        )
    )

    /** Gemini Nano's availability and download (checked once an event is shown). */
    private val model = OnDeviceModelController(viewModelScope, assistant) { screenshotMode }

    /** The selected item / tab, Explore's view, focus requests, the bar and Options. */
    private val nav = NavigationController(
        store = object : ExploreViewStore {
            override fun exploreView(eventId: Int): String? = prefs.exploreView(eventId)
            override fun setExploreView(eventId: Int, key: String) = prefs.setExploreView(eventId, key)
        },
        eventId = { repository.bundle.value?.event?.id },
        current = { uiState.value },
    )

    /** Making, re-planning, swapping and clearing plans. */
    private val planner = PlannerController(
        scope = viewModelScope,
        bundle = { repository.bundle.value },
        hearts = heartStore,
        plans = planStore,
        assistant = assistant,
        perf = perf,
        available = { uiState.value.planner.available },
        modelAvailable = { model.isAvailable },
        countPerf = { !screenshotMode },
    )

    val uiState: StateFlow<AppUiState> =
        combine(
            repository.bundle,
            repository.sync,
            combine(local, nav.state, planner.state, model.state, ::UiInputs),
            repository.appUpdate,
            combine(heartStore.version, planStore.version, diagnostics.state) { hearts, plans, diag -> Triple(hearts, plans, diag) },
        ) { savedBundle, savedSync, inputs, savedUpdate, versions ->
            val state = inputs.local
            val navState = inputs.nav
            val screenshot = state.screenshot
            // Screenshot mode: the screen's layout tweaks, and nothing on top (no update banner or
            // screen, status pill, offline notice or refresh spinner).
            val bundle = if (screenshot != null && savedBundle != null) screenshot.present(savedBundle) else savedBundle
            val update = if (screenshot != null) AppUpdate() else savedUpdate
            val sync = if (screenshot != null) savedSync.copy(isRefreshing = false, error = null) else savedSync
            val display = bundle?.display
            val eventId = bundle?.event?.id
            val updatesSeen = eventId?.let { id ->
                (state.updatesSeenAt[id] ?: prefs.updatesSeenAt(id))?.let(Instant::ofEpochMilli)
            }
            val notifications = bundle?.notifications.orEmpty()
            val hearts = eventId?.let(heartStore::hearts) ?: Hearts.NONE
            val plan = eventId?.let(planStore::plan)
            // The planner runs without on-device AI too (likes + the plain-rule reading); never in screenshot mode.
            val plannerAvailable = screenshot == null
            val tabs = display?.let { EventTabModel.of(it, legacy = screenshot != null) } ?: EventTabModel.of(emptyList(), null)
            val selectedItem = navState.selectedItem?.takeIf { it in tabs.items } ?: tabs.initialItem
            val exploreView = tabs.exploreView(eventId?.let(nav::exploreView))
            AppUiState(
                phase = when {
                    !state.ready || state.screenshotPending -> AppPhase.LOADING
                    bundle != null -> AppPhase.EVENT
                    else -> AppPhase.LAUNCH
                },
                bundle = bundle,
                sync = sync,
                tabs = tabs,
                selectedItem = selectedItem,
                exploreView = exploreView,
                selectedTab = tabs.contentTab(selectedItem, exploreView),
                focusInput = navState.focusInput,
                join = state.join,
                nearby = state.nearby,
                showOptions = navState.showOptions && bundle != null,
                notificationsEnabled = state.notificationsEnabled,
                showUserLocation = state.showUserLocation,
                focusLocationId = navState.focusLocationId,
                focusScheduleItemId = navState.focusScheduleItemId,
                focusNotificationId = navState.focusNotificationId,
                searchRequest = navState.searchRequest.takeIf { bundle != null },
                // The input bar over the other tabs (My plan has its own); never in screenshot mode.
                assistantOpen = (navState.assistantOpen || navState.searchRequest != null) && bundle != null && screenshot == null &&
                    selectedItem != NavItem.PLAN,
                messageChip = state.messageChip.takeIf { screenshot == null && state.message != null },
                remindersOn = eventId != null && planStore.reminders(eventId, hearts),
                // Store screenshots never show the on-device assistant.
                assistant = if (screenshot != null) AssistantUiState(availability = AssistantAvailability.UNAVAILABLE) else inputs.model,
                recenterRequest = navState.recenterRequest,
                message = state.message.takeIf { screenshot == null },
                update = update,
                showUpdateBanner = update.level == UpdateLevel.AVAILABLE &&
                    update.latestVersion != state.dismissedUpdateVersion,
                supportInfo = baseSupportInfo.copy(
                    eventName = bundle?.event?.name,
                    eventCode = bundle?.event?.eventCode,
                    eventId = bundle?.event?.id,
                    contentVersion = bundle?.contentVersion,
                    lastSyncedAt = sync.lastSyncedAt,
                ),
                hearts = hearts,
                unreadUpdates = if (screenshot != null) 0 else UnreadUpdates.unreadCount(notifications, updatesSeen),
                // Store screenshots never show the banner.
                updateBanner = if (screenshot != null || eventId == null) null else UnreadUpdates.banner(
                    notifications,
                    updatesSeen,
                    state.dismissedUpdateBanners[eventId] ?: prefs.dismissedUpdateBanner(eventId),
                    Instant.now(),
                ),
                mapFocusLocationId = navState.mapFocusLocationId,
                isScreenshot = screenshot != null,
                planner = PlanUiState(
                    available = plannerAvailable,
                    plan = plan,
                    changed = plan != null && Planner.changed(plan, bundle),
                    isPlanning = inputs.planner.isPlanning,
                    error = inputs.planner.error,
                    examples = if (bundle != null && plannerAvailable) PlanRequest.examples(bundle, Instant.now()) else emptyList(),
                    showRequest = navState.showPlanRequest,
                ),
                showOnboarding = state.showOnboarding && screenshot == null && bundle != null,
                followLive = eventId != null && planStore.follows(eventId, hearts),
                diagnostics = versions.third,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    private val startJob: Job = viewModelScope.launch { start() }

    init {
        // API-005 `tab_view`: whatever the user is looking at in the event (the initial/default
        // tab, every tab change, a jump from a pin or a push, and Options), counted on change.
        viewModelScope.launch {
            uiState
                .map { state ->
                    val bundle = state.bundle
                    if (state.phase != AppPhase.EVENT || bundle == null) null
                    // Explore sends its view's tab name; My plan sends nothing new (null).
                    else bundle.event.id to (if (state.showOptions) UsageMetric.TAB_OPTIONS else EventTabModel.usageName(state.selectedItem, state.selectedTab))
                }
                .distinctUntilChanged()
                .collect { view ->
                    if (view != null) {
                        val name = when (view.second) {
                            null -> "plan"
                            Tab.NOTIFICATIONS.key -> "updates"
                            else -> view.second
                        }
                        AppLog.debug(LogCategory.UI, LogEvent.SCREEN, "Screen $name", mapOf("name" to name))
                    }
                    val metric = view?.second
                    if (view != null && metric != null && !screenshotMode) usage.track(view.first, UsageMetric.TAB_VIEW, metric)
                }
        }
        // Unread updates: the Updates tab counts as seen while it is visible, including updates that
        // arrive while it is open.
        viewModelScope.launch {
            uiState
                .map { state ->
                    val bundle = state.bundle
                    if (state.phase == AppPhase.EVENT && bundle != null && !state.showOptions && state.selectedTab == Tab.NOTIFICATIONS) {
                        bundle.event.id to bundle.notifications
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collect { seen -> if (seen != null) markUpdatesSeen(seen.first, seen.second) }
        }
        checkAssistantInEvent()
    }

    /**
     * The planner and the planner onboarding card need to know whether Gemini Nano is available, so the
     * status is checked once an event is shown (not at app start, which never binds to AICore).
     */
    private fun checkAssistantInEvent() {
        viewModelScope.launch {
            uiState.map { it.phase == AppPhase.EVENT && !it.isScreenshot }.distinctUntilChanged().collect { inEvent ->
                if (inEvent) model.check()
            }
        }
    }

    private fun markUpdatesSeen(eventId: Int, notifications: List<EventNotification>) {
        if (screenshotMode) return
        val at = UnreadUpdates.seenAt(notifications, Instant.now()).toEpochMilli()
        prefs.setUpdatesSeenAt(eventId, at)
        local.update { it.copy(updatesSeenAt = it.updatesSeenAt + (eventId to at)) }
    }

    /**
     * Offline start: show the persisted bundle immediately, then refresh. Without a saved bundle,
     * migrate a v1 saved event code or check the Play install referrer (first install via QR code).
     */
    private suspend fun start() {
        viewModelScope.launch { repository.checkForUpdate() }
        val restored = repository.restore()
        if (restored == null) {
            val legacyCode = prefs.eventCode
            when {
                repository.currentEventId != null -> repository.refresh()
                legacyCode != null -> join(silent = true, via = "rejoin") { repository.lookup(legacyCode) }
                !prefs.installReferrerChecked -> {
                    prefs.installReferrerChecked = true
                    InstallReferrer.eventCode(appContext)?.let { code ->
                        join(silent = false, via = "referrer") { repository.lookup(code) }
                    }
                }
            }
        }
        prefs.installReferrerChecked = true
        local.update { it.copy(ready = true) }
        viewModelScope.launch { repository.registerDevice() }
    }

    // ── Joining ─────────────────────────────────────────────────────────────

    /** From the code field, a QR scan or an App Link. Accepts a bare code or a /join/<code>/ URL. */
    fun joinByCode(input: String, via: String = "search") {
        val trimmed = input.trim()
        // A shared plan's link or QR (API-015) joins its event and shows the plan.
        JoinLinks.parseShareLink(trimmed)?.let {
            openShareLink(it)
            return
        }
        val code = JoinLinks.parseJoinCode(trimmed)
            // Not a /join/ link: treat it as a code or exact event name, unless it is some other URL.
            ?: trimmed.takeIf { it.isNotEmpty() && !it.contains("://") }
        if (code == null) {
            if (trimmed.isNotEmpty()) local.update { it.copy(join = JoinUiState(error = "That isn't an Attnd event code or link.")) }
            return
        }
        viewModelScope.launch {
            startJob.join()
            join(silent = false, via = via) { repository.lookup(code) }
        }
    }

    fun joinEvent(event: Event) {
        viewModelScope.launch {
            startJob.join()
            join(silent = false, via = "nearby") { event }
        }
    }

    private suspend fun join(silent: Boolean, via: String = "search", resolve: suspend () -> Event) {
        local.update { it.copy(join = JoinUiState(isJoining = true), message = null) }
        try {
            val event = resolve()
            val alreadyIn = repository.currentEventId == event.id && repository.bundle.value != null
            val result = repository.join(event, via)
            // The tour, the first time this event is joined (never in screenshot mode).
            val tour = !alreadyIn && !screenshotMode && !prefs.onboardingSeen(event.id) && repository.bundle.value?.event?.id == event.id
            planner.clearError()
            nav.resetForJoin(alreadyIn)
            local.update {
                it.copy(
                    join = JoinUiState(),
                    showOnboarding = tour || (alreadyIn && it.showOnboarding),
                    message = when (result) {
                        is RefreshResult.Failed -> "Joined, but the event couldn't be downloaded. ${result.message}"
                        RefreshResult.EventGone -> "This event is no longer available."
                        else -> null
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.caught(LogCategory.NET, "join", e, if (e is IOException || (e is ApiException && e.code == 404)) LogLevel.WARNING else LogLevel.ERROR)
            if (!silent) {
                AppLog.warning(
                    LogCategory.UI, LogEvent.UI_ERROR_SHOWN, "Join error shown",
                    mapOf("kind" to if (e is ApiException && e.code == 404) "lookup" else "join", "status" to (e as? ApiException)?.code),
                )
            }
            if (silent && e is ApiException && e.code == 404) prefs.eventCode = null
            local.update { it.copy(join = JoinUiState(error = if (silent) null else describeJoinError(e))) }
        }
    }

    private fun describeJoinError(e: Exception): String = when {
        e is ApiException && e.code == 404 -> "Event not found. Check the code and try again."
        e is ApiException && e.code == 401 -> "Couldn't verify this app with the server. Please update Attnd."
        e is ApiException -> "Server error (${e.code}). Please try again."
        e is IOException -> "Network error. Check your connection and try again."
        else -> "Something went wrong (${e.javaClass.simpleName})."
    }

    fun leaveEvent() {
        viewModelScope.launch {
            repository.leave()
            planner.clearError()
            nav.resetForLeave()
            local.update { it.copy(showOnboarding = false) }
        }
    }

    /**
     * Store-screenshot mode (debug builds; `scripts/ci/screenshots.sh`): joins the requested event
     * through the normal lookup/join path, then shows [ScreenshotRequest.screen]. The loading screen
     * stays up until then, so the launch screen never flashes; a failed join shows the launch screen
     * with the error. [ScreenshotScreen.LAUNCH] leaves any joined event instead.
     */
    fun startScreenshot(request: ScreenshotRequest) {
        screenshotMode = true
        liveUpdates.suspended = true
        liveUpdates.requestRefresh()
        nav.update { it.copy(showOptions = false, focusLocationId = null) }
        local.update {
            it.copy(
                screenshot = request,
                screenshotPending = true,
                showOnboarding = false,
                message = null,
            )
        }
        viewModelScope.launch {
            try {
                startJob.join()
                if (request.screen == ScreenshotScreen.LAUNCH) {
                    if (repository.currentEventId != null) repository.leave()
                    nav.update { it.copy(selectedItem = null) }
                } else {
                    join(silent = false, via = "search") { repository.lookup(request.eventCode) }
                    val location = repository.bundle.value?.let { request.focusLocation(it.locations) }
                    nav.update { it.copy(selectedItem = request.tab?.let(NavItem::direct), focusLocationId = location?.id) }
                }
            } finally {
                local.update { it.copy(screenshotPending = false) }
            }
        }
    }

    // ── Nearby events ───────────────────────────────────────────────────────

    fun loadNearby(lat: Double, lng: Double) {
        local.update { it.copy(nearby = it.nearby.copy(isLoading = true, error = null)) }
        viewModelScope.launch {
            val nearby = try {
                NearbyUiState(events = repository.nearby(lat, lng))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.NET, "nearby", e, LogLevel.WARNING)
                NearbyUiState(error = "Could not load nearby events. ${describeJoinError(e)}")
            }
            local.update { it.copy(nearby = nearby) }
        }
    }

    fun setNearbyError(message: String?) {
        local.update { it.copy(nearby = it.nearby.copy(isLoading = false, error = message)) }
    }

    fun setNearbyLoading() {
        local.update { it.copy(nearby = it.nearby.copy(isLoading = true, error = null)) }
    }

    // ── Sync ────────────────────────────────────────────────────────────────

    /** Pull to refresh. */
    fun refresh() {
        track(UsageMetric.REFRESH)
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Pull to refresh", mapOf("name" to "refresh"))
        viewModelScope.launch { repository.refresh(source = "pull") }
    }

    /**
     * Runs while the activity is resumed: refresh now (the app came to the foreground), then poll
     * while an event is joined. API-007: live updates come from `content_updated` pushes, so the poll
     * is a safety net every 5 minutes, or every 60 s when the server has no push token for this
     * device, every 15 s while the last refresh failed ([LiveUpdates.pollIntervalMs]). Polls are conditional GETs (usually `304`) and silent
     * (no spinner); at these intervals one failed poll shows the offline notice
     * ([LiveUpdates.failuresBeforeOfflineNotice]). Cancelled automatically when the activity pauses.
     */
    suspend fun foregroundSync() {
        startJob.join()
        if (pendingAppOpen) {
            pendingAppOpen = false
            track(UsageMetric.APP_OPEN)
        }
        repository.checkForUpdate()
        val lastSync = repository.sync.value.lastSyncedAt
        if (lastSync == null || System.currentTimeMillis() - lastSync > RESUME_DEBOUNCE_MS) repository.refresh()
        var lastInterval = 0L
        while (true) {
            val interval = LiveUpdates.pollIntervalMs(repository.hasPushToken, repository.lastRefreshFailed)
            if (interval != lastInterval) {
                lastInterval = interval
                AppLog.trace(LogCategory.NET, LogEvent.POLL_SCHEDULED, "Safety poll every ${interval / 1000} s", mapOf("interval_s" to interval / 1000, "has_push_token" to repository.hasPushToken))
            }
            delay(interval)
            // A token the server hasn't confirmed (registration failed offline): retry, so pushes resume.
            if (repository.needsDeviceRegistration) repository.registerDevice()
            if (repository.bundle.value != null) {
                repository.refresh(silent = true, failuresBeforeError = LiveUpdates.failuresBeforeOfflineNotice(interval), source = "poll")
            }
        }
    }

    // ── Navigation & preferences ────────────────────────────────────────────

    fun dismissUpdateBanner() {
        val version = uiState.value.update.latestVersion
        prefs.dismissedUpdateVersion = version
        local.update { it.copy(dismissedUpdateVersion = version) }
    }

    /**
     * Shows [tab]'s screen: Explore on that view for the map / schedule / places, else the tab's own
     * item. Reselecting the map recenters it. A tab the event doesn't show changes nothing.
     */
    fun selectTab(tab: Tab) = nav.selectTab(tab)

    /** A navigation item tapped (My plan, Explore, Flyer, Updates; today's tabs in screenshot mode). */
    fun selectItem(item: NavItem) = nav.selectItem(item)

    /** Explore's switch: shows [view] and remembers it for the event. */
    fun selectExploreView(view: Tab) = nav.selectExploreView(view)

    /** Opening the app from a push notification lands on the Notifications tab. */
    fun openNotifications(notificationId: Int? = null) {
        nav.showNotifications(notificationId)
        viewModelScope.launch {
            // On a cold start the saved bundle (and so the event id) is only known after start().
            startJob.join()
            track(UsageMetric.NOTIFICATION_OPEN, notificationId?.toString())
            repository.refresh()
        }
    }

    /** Shows a location's detail: on the Locations tab, or as an overlay when that tab is hidden. */
    fun focusLocation(locationId: Int) = nav.focusLocation(locationId)

    fun consumeFocusLocation() = nav.consumeFocusLocation()

    fun focusScheduleItem(itemId: Int) = nav.focusScheduleItem(itemId)

    fun consumeFocusScheduleItem() = nav.consumeFocusScheduleItem()

    /** Shows a notification on the Notifications tab (or as an overlay when that tab is hidden). */
    fun focusNotification(notificationId: Int) = nav.focusNotification(notificationId)

    fun consumeFocusNotification() = nav.consumeFocusNotification()

    /** Selects a location on the Map tab (when the event shows one). */
    fun showOnMap(locationId: Int) = nav.showOnMap(locationId)

    fun consumeMapFocus() = nav.consumeMapFocus()

    /** The update banner's ✕: hides that update's banner (the badge stays until the tab is seen). */
    fun dismissLatestUpdate(notificationId: Int) {
        val eventId = uiState.value.bundle?.event?.id ?: return
        prefs.setDismissedUpdateBanner(eventId, notificationId)
        local.update { it.copy(dismissedUpdateBanners = it.dismissedUpdateBanners + (eventId to notificationId)) }
    }

    // ── Hearts ──────────────────────────────────────────────────────────────

    /** Hearts or un-hearts a schedule item / location (stored per event; API-009 usage count). */
    fun toggleHeart(kind: SearchKind, id: Int) {
        val eventId = repository.bundle.value?.event?.id ?: return
        val hearted = !schedule.isLiked(eventId, kind, id)
        setLiked(kind, id, hearted)
        // Assistant spec §6, like clash: a liked item that overlaps the plan → "Add to plan instead of X?".
        val bundle = repository.bundle.value ?: return
        if (hearted && kind == SearchKind.SCHEDULE && !screenshotMode) {
            schedule.likeClash(bundle, id)?.let { chip ->
                local.update { it.copy(message = chip.label, messageChip = chip) }
            }
        }
    }

    private fun setLiked(kind: SearchKind, id: Int, liked: Boolean): Boolean {
        val eventId = repository.bundle.value?.event?.id ?: return false
        if (!schedule.setLiked(eventId, kind, id, liked, countUsage = !screenshotMode)) return false
        assistantController.refreshLive()
        return true
    }

    /** The snackbar's action for [AppUiState.messageChip]. */
    fun runMessageChip() {
        val chip = local.value.messageChip ?: return
        local.update { it.copy(messageChip = null) }
        // A plan edit previews in place on My plan (AssistantHost.onProposal).
        val result = assistantController.run(chip.action, chip.label, ActionSource.RULES)
        if (result != null) local.update { it.copy(message = result) }
    }

    // ── Search & assistant ──────────────────────────────────────────────────

    /**
     * An internal open link (a notification or Live Update tap). An item opens on its own tab when
     * `display.tabs` shows it, otherwise on the search screen. Ignored when it names another event than
     * the joined one; waits for the saved bundle on a cold start.
     */
    fun open(request: OpenRequest) {
        viewModelScope.launch {
            startJob.join()
            val bundle = repository.bundle.value ?: return@launch
            if (request.eventId != null && request.eventId != bundle.event.id) return@launch
            nav.route(request.target, bundle.display.tabs)
        }
    }

    /** The bar took the request; [keepOpen] = the bar over a tab (My plan's bar is always there). */
    fun consumeSearchRequest(keepOpen: Boolean = true) = nav.consumeSearchRequest(keepOpen)

    // ── Event assistant (assistant spec) ────────────────────────────────────

    /** The assistant: conversation, actions, live hints (see [AssistantController]). */
    val assistantController: AssistantController = AssistantController(viewModelScope, repository, heartStore, planStore, assistant, object : AssistantHost {
        override fun modelAvailable(): Boolean = !screenshotMode && model.isAvailable
        override fun setLiked(kind: SearchKind, id: Int, liked: Boolean): Boolean = this@EventViewModel.setLiked(kind, id, liked)
        override suspend fun makePlan(request: String, source: String): Plan? = planner.buildPlan(request, source)
        override fun planError(): String? = planner.state.value.error
        override fun open(screen: String, ref: EntityRef?) = openScreen(screen, ref)
        override fun canPlan(): Boolean = !screenshotMode
        // A plan edit previews in place on My plan (typed on another tab, a hint, a like clash).
        override fun onProposal() = nav.showProposal()
        override fun setSetting(name: ActionName, on: Boolean): Boolean {
            if (on && !hasNotificationPermission()) return false
            when (name) {
                ActionName.FOLLOW_LOCK_SCREEN -> setFollowLive(on)
                ActionName.REMINDERS -> setReminders(on)
                ActionName.NOTIFICATIONS -> setNotificationsEnabled(on)
                else -> Unit
            }
            return true
        }
        override fun settingOn(name: ActionName): Boolean = when (name) {
            ActionName.FOLLOW_LOCK_SCREEN -> uiState.value.followLive
            ActionName.REMINDERS -> uiState.value.remindersOn
            ActionName.NOTIFICATIONS -> uiState.value.notificationsEnabled
            else -> false
        }
        override fun userLocation(): GeoPoint? = if (screenshotMode) null else userLocationSource()
        override fun units(): DistanceUnits = DistanceUnits.forRegion(Locale.getDefault().country)
    })

    private fun hasNotificationPermission(): Boolean = permissions.notifications

    private fun userLocationSource(): GeoPoint? =
        (appContext.applicationContext as? AttndApplication)?.graph?.userLocation?.location?.value

    /** The search FAB: opens the assistant. */
    fun openAssistant() {
        if (screenshotMode) return
        AppLog.debug(LogCategory.UI, LogEvent.SCREEN, "Screen assistant", mapOf("name" to "assistant"))
        nav.openAssistant()
    }

    fun closeAssistant() = nav.closeAssistant()

    /** `reminders(on)`: reminders before liked and planned items (the caller holds POST_NOTIFICATIONS to turn on). */
    fun setReminders(on: Boolean) {
        val eventId = repository.bundle.value?.event?.id ?: return
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Reminders ${if (on) "on" else "off"}", mapOf("name" to if (on) "reminders_on" else "reminders_off"))
        planStore.setReminders(eventId, on)
    }

    /** `open(screen, id?)` from the assistant: closes it and shows the manual screen. */
    private fun openScreen(screen: String, ref: EntityRef?) {
        val bundle = repository.bundle.value ?: return
        if (!nav.openScreen(screen, ref, bundle.display.tabs) && screen == "tour") showTour()
    }

    /**
     * A shared plan link (API-015, `https://attnd.app/p/<code>/<payload>`): joins its event (unless
     * already in it), then shows the shared plan in the assistant (a payload this build can't read only
     * joins).
     */
    fun openShareLink(link: ShareLink) {
        viewModelScope.launch {
            startJob.join()
            val current = repository.bundle.value?.event
            val already = current != null && current.eventCode.equals(link.eventCode, ignoreCase = true)
            if (!already) join(silent = false, via = "share") { repository.lookup(link.eventCode) }
            val bundle = repository.bundle.value ?: return@launch
            if (!bundle.event.eventCode.equals(link.eventCode, ignoreCase = true)) return@launch
            val payload = PlanShare.decode(link.payload)
            if (payload == null || !payload.isReadable || screenshotMode) {
                AppLog.notice(
                    LogCategory.UI, LogEvent.SHARE_OPENED, "Shared link opened (join only)",
                    mapOf("target" to "plan", "items" to 0, "known" to 0, "action" to "viewed", "joined" to !already),
                )
                return@launch
            }
            nav.update { it.copy(selectedItem = NavItem.PLAN, showOptions = false) }
            local.update { it.copy(showOnboarding = false) }
            assistantController.openShared(payload, joined = !already)
        }
    }

    /** Checks once (when the search screen opens) whether the on-device model can be used. */
    fun checkAssistant() = model.check()

    /** Downloads Gemini Nano (only when the person asks for it). */
    fun downloadAssistant() = model.download()

    // ── Planner ─────────────────────────────────────────────────────────────

    /** From the plan card (or an example chip). */
    fun makePlan(request: String, source: String = "card") = planner.makePlan(request, source)

    /** "Re-plan from now": the same preferences from the current time (also after a content change). */
    fun replanFromNow() = planner.replanFromNow()

    fun swapPlanItem(oldId: Int, newId: Int) = planner.swapPlanItem(oldId, newId)

    /** "Keep my plan" after a content change: the changed items are accepted as they are now. */
    fun keepChangedPlan() = planner.keepChangedPlan()

    fun clearPlan() = planner.clearPlan()

    /** Opens the My plan tab. */
    fun showPlan() = nav.showPlan()

    // ── Live Update and onboarding ─────────────────────────────────────────

    /** "Follow on Lock Screen" (the caller holds POST_NOTIFICATIONS when turning it on). */
    fun setFollowLive(follow: Boolean) {
        val eventId = repository.bundle.value?.event?.id ?: return
        AppLog.notice(LogCategory.LIVE, LogEvent.LIVE_FOLLOW_CHANGED, "Follow on Lock Screen ${if (follow) "on" else "off"}", mapOf("on" to follow, "default" to false))
        planStore.setFollows(eventId, follow)
    }

    fun showTour() {
        nav.showOptions(false)
        local.update { it.copy(showOnboarding = true) }
    }

    fun finishOnboarding() {
        repository.bundle.value?.event?.id?.let(prefs::setOnboardingSeen)
        local.update { it.copy(showOnboarding = false) }
    }

    /** An onboarding / plan-card example: a plan request plans, a question opens Ask with it. */
    fun useExample(example: PlanExample) {
        finishOnboarding()
        if (example.isPlan) {
            makePlan(example.text, source = "example")
            showPlan()
        } else {
            nav.ask(example.text)
        }
    }

    // ── Usage reporting (API-005) ───────────────────────────────────────────

    /** A location's detail became visible (from the list, a map pin or a schedule item). */
    fun onLocationShown(locationId: Int) = track(UsageMetric.LOCATION_VIEW, locationId.toString())

    fun onScheduleItemShown(itemId: Int) = track(UsageMetric.SCHEDULE_ITEM_VIEW, itemId.toString())

    /** A search was submitted or settled. The text itself is never reported. */
    fun onSearch() = track(UsageMetric.SEARCH)

    /** The app left the foreground (not a configuration change): send counts, and count the next resume. */
    fun onAppBackground() {
        pendingAppOpen = true
        usage.flushNow()
    }

    /** Counts [name] for the joined event; does nothing outside an event. */
    private fun track(name: String, detail: String? = null) {
        if (screenshotMode) return
        val eventId = repository.bundle.value?.event?.id ?: return
        usage.track(eventId, name, detail)
    }

    fun showOptions(show: Boolean) = nav.showOptions(show)

    fun showMessage(message: String) {
        local.update { it.copy(message = message, messageChip = null) }
    }

    fun consumeMessage() {
        local.update { it.copy(message = null, messageChip = null) }
    }

    fun clearJoinError() {
        local.update { it.copy(join = it.join.copy(error = null)) }
    }

    /** The caller must already hold POST_NOTIFICATIONS when enabling. */
    fun setNotificationsEnabled(enabled: Boolean) {
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Push notifications ${if (enabled) "on" else "off"}", mapOf("name" to if (enabled) "notifications_on" else "notifications_off"))
        local.update { it.copy(notificationsEnabled = enabled) }
        viewModelScope.launch {
            if (!repository.setNotificationsEnabled(enabled)) {
                local.update { it.copy(message = "Couldn't update notification settings. They'll sync next time you're online.") }
            }
        }
    }

    fun setShowUserLocation(show: Boolean) {
        prefs.showUserLocation = show
        local.update { it.copy(showUserLocation = show) }
    }

    // ── Diagnostics (About & support; logging spec §2–3) ────────────────────

    /** "Turn on debug logging for 10 minutes". */
    fun startDebugMode() = diagnostics.startDebugMode()

    fun stopDebugMode() = diagnostics.stopDebugMode()

    /** The ring buffer, oldest first (the log viewer filters and reverses it). */
    suspend fun logRecords(): List<LogRecord> = diagnostics.records()

    /** The last log upload attempt (dev builds and debug sessions). */
    val logUploadStatus: String? get() = diagnostics.lastUploadResult

    /** A `.jsonl` file of the records matching [filter], for the share sheet. */
    suspend fun exportLogs(filter: LogFilter?): File = diagnostics.export(filter)


    companion object {
        private const val RESUME_DEBOUNCE_MS = 30 * 1000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as AttndApplication
                EventViewModel(
                    app, app.graph.repository, app.graph.prefs, app.graph.usage, app.graph.assistant, app.graph.hearts,
                    app.graph.schedule, app.graph.permissions, app.graph.plans, app.graph.liveUpdates, app.graph.diagnostics, app.graph.perf,
                )
            }
        }
    }
}
