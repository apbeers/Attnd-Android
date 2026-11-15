package com.arcu.attnd.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.data.AssistantDownload
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.InstallReferrer
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.PlanStore
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
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.RefKind
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
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.NearbyEvent
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.PerfMetric
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.domain.PlanRequest
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.Redact
import com.arcu.attnd.domain.ScreenshotRequest
import com.arcu.attnd.domain.ScreenshotScreen
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SupportInfo
import com.arcu.attnd.domain.Tab
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

/** The planner ("My plan" in Schedule), offered only when Gemini Nano is available on the device. */
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
data class SearchRequest(val query: String, val serial: Int, val detail: SearchEntry? = null, val ask: Boolean = false, val about: EntityRef? = null)

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
    val selectedTab: Tab = Tab.MAP,
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
    /** Open the assistant with a query, an item or "Ask about this" (shortcut / assistant / detail). */
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
    val selectedTab: Tab? = null,
    val join: JoinUiState = JoinUiState(),
    val nearby: NearbyUiState = NearbyUiState(),
    val showOptions: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val showUserLocation: Boolean = false,
    val focusLocationId: Int? = null,
    val focusScheduleItemId: Int? = null,
    val focusNotificationId: Int? = null,
    val searchRequest: SearchRequest? = null,
    val assistantOpen: Boolean = false,
    val assistant: AssistantUiState = AssistantUiState(),
    val recenterRequest: Int = 0,
    val message: String? = null,
    val messageChip: Chip? = null,
    val dismissedUpdateVersion: String? = null,
    /** Store-screenshot mode (debug builds): see [ScreenshotRequest]. */
    val screenshot: ScreenshotRequest? = null,
    /** The screenshot event is still being joined: keep showing the loading screen, not the launch screen. */
    val screenshotPending: Boolean = false,
    val mapFocusLocationId: Int? = null,
    /** Per event: when its Updates tab was last seen (epoch ms) and which update banner was dismissed. */
    val updatesSeenAt: Map<Int, Long> = emptyMap(),
    val dismissedUpdateBanners: Map<Int, Int> = emptyMap(),
    val isPlanning: Boolean = false,
    val planError: String? = null,
    val showPlanRequest: Int = 0,
    val showOnboarding: Boolean = false,
)

class EventViewModel(
    private val appContext: Context,
    private val repository: EventRepository,
    private val prefs: DevicePrefs,
    private val usage: UsageReporter,
    private val assistant: OnDeviceAssistant,
    private val heartStore: HeartStore,
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

    /** A plan being made and the model download (see [makePlan], [downloadAssistant]). */
    private var planJob: Job? = null
    private var downloadJob: Job? = null

    private val local = MutableStateFlow(
        LocalState(
            notificationsEnabled = prefs.notificationsEnabled,
            showUserLocation = prefs.showUserLocation,
            dismissedUpdateVersion = prefs.dismissedUpdateVersion,
        )
    )

    val uiState: StateFlow<AppUiState> =
        combine(
            repository.bundle,
            repository.sync,
            local,
            repository.appUpdate,
            combine(heartStore.version, planStore.version, diagnostics.state) { hearts, plans, diag -> Triple(hearts, plans, diag) },
        ) { savedBundle, savedSync, state, savedUpdate, versions ->
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
            val plannerAvailable = screenshot == null && state.assistant.availability == AssistantAvailability.AVAILABLE
            AppUiState(
                phase = when {
                    !state.ready || state.screenshotPending -> AppPhase.LOADING
                    bundle != null -> AppPhase.EVENT
                    else -> AppPhase.LAUNCH
                },
                bundle = bundle,
                sync = sync,
                selectedTab = display?.let { d -> state.selectedTab?.takeIf { it in d.tabs } ?: d.defaultTab } ?: Tab.MAP,
                join = state.join,
                nearby = state.nearby,
                showOptions = state.showOptions && bundle != null,
                notificationsEnabled = state.notificationsEnabled,
                showUserLocation = state.showUserLocation,
                focusLocationId = state.focusLocationId,
                focusScheduleItemId = state.focusScheduleItemId,
                focusNotificationId = state.focusNotificationId,
                searchRequest = state.searchRequest.takeIf { bundle != null },
                // Never in screenshot mode (assistant spec §8).
                assistantOpen = (state.assistantOpen || state.searchRequest != null) && bundle != null && screenshot == null,
                messageChip = state.messageChip.takeIf { screenshot == null && state.message != null },
                remindersOn = eventId != null && planStore.reminders(eventId, hearts),
                // Store screenshots never show the on-device assistant.
                assistant = if (screenshot != null) AssistantUiState(availability = AssistantAvailability.UNAVAILABLE) else state.assistant,
                recenterRequest = state.recenterRequest,
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
                mapFocusLocationId = state.mapFocusLocationId,
                isScreenshot = screenshot != null,
                planner = PlanUiState(
                    available = plannerAvailable,
                    plan = plan,
                    changed = plan != null && Planner.changed(plan, bundle),
                    isPlanning = state.isPlanning,
                    error = state.planError,
                    examples = if (bundle != null && plannerAvailable) PlanRequest.examples(bundle, Instant.now()) else emptyList(),
                    showRequest = state.showPlanRequest,
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
                    else bundle.event.id to (if (state.showOptions) UsageMetric.TAB_OPTIONS else state.selectedTab.key)
                }
                .distinctUntilChanged()
                .collect { view ->
                    if (view != null) {
                        val name = if (view.second == Tab.NOTIFICATIONS.key) "updates" else view.second
                        AppLog.debug(LogCategory.UI, LogEvent.SCREEN, "Screen $name", mapOf("name" to name))
                    }
                    if (view != null && !screenshotMode) usage.track(view.first, UsageMetric.TAB_VIEW, view.second)
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
                if (inEvent) checkAssistant()
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
            local.update {
                it.copy(
                    join = JoinUiState(),
                    showOnboarding = tour || (alreadyIn && it.showOnboarding),
                    planError = null,
                    selectedTab = if (alreadyIn) it.selectedTab else null,
                    showOptions = false,
                    focusLocationId = null,
                    focusScheduleItemId = null,
                    focusNotificationId = null,
                    mapFocusLocationId = null,
                    searchRequest = null,
                    assistantOpen = false,
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
            local.update {
                it.copy(
                    showOptions = false,
                    showOnboarding = false,
                    planError = null,
                    selectedTab = null,
                    focusLocationId = null,
                    focusScheduleItemId = null,
                    focusNotificationId = null,
                    searchRequest = null,
                    assistantOpen = false,
                )
            }
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
        local.update {
            it.copy(
                screenshot = request,
                screenshotPending = true,
                showOptions = false,
                showOnboarding = false,
                message = null,
                focusLocationId = null,
            )
        }
        viewModelScope.launch {
            try {
                startJob.join()
                if (request.screen == ScreenshotScreen.LAUNCH) {
                    if (repository.currentEventId != null) repository.leave()
                    local.update { it.copy(selectedTab = null) }
                } else {
                    join(silent = false, via = "search") { repository.lookup(request.eventCode) }
                    val location = repository.bundle.value?.let { request.focusLocation(it.locations) }
                    local.update { it.copy(selectedTab = request.tab, focusLocationId = location?.id) }
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
     * device ([LiveUpdates.pollIntervalMs]). Polls are conditional GETs (usually `304`) and silent
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
            val interval = LiveUpdates.pollIntervalMs(repository.hasPushToken)
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

    fun selectTab(tab: Tab) {
        val current = uiState.value.selectedTab
        local.update {
            if (tab == current && tab == Tab.MAP) it.copy(recenterRequest = it.recenterRequest + 1)
            else it.copy(selectedTab = tab)
        }
    }

    /** Opening the app from a push notification lands on the Notifications tab. */
    fun openNotifications(notificationId: Int? = null) {
        local.update { it.copy(selectedTab = Tab.NOTIFICATIONS, showOptions = false, focusNotificationId = notificationId ?: it.focusNotificationId) }
        viewModelScope.launch {
            // On a cold start the saved bundle (and so the event id) is only known after start().
            startJob.join()
            track(UsageMetric.NOTIFICATION_OPEN, notificationId?.toString())
            repository.refresh()
        }
    }

    /** Shows a location's detail: on the Locations tab, or as an overlay when that tab is hidden. */
    fun focusLocation(locationId: Int) {
        local.update { it.copy(focusLocationId = locationId, selectedTab = Tab.LOCATIONS) }
    }

    fun consumeFocusLocation() {
        local.update { it.copy(focusLocationId = null) }
    }

    fun focusScheduleItem(itemId: Int) {
        local.update { it.copy(focusScheduleItemId = itemId, selectedTab = Tab.SCHEDULE) }
    }

    fun consumeFocusScheduleItem() {
        local.update { it.copy(focusScheduleItemId = null) }
    }

    /** Shows a notification on the Notifications tab (or as an overlay when that tab is hidden). */
    fun focusNotification(notificationId: Int) {
        local.update { it.copy(focusNotificationId = notificationId, selectedTab = Tab.NOTIFICATIONS, showOptions = false) }
    }

    fun consumeFocusNotification() {
        local.update { it.copy(focusNotificationId = null) }
    }

    /** Selects a location on the Map tab (when the event shows one). */
    fun showOnMap(locationId: Int) {
        val bundle = uiState.value.bundle ?: return
        if (Tab.MAP !in bundle.display.tabs) return
        local.update { it.copy(selectedTab = Tab.MAP, showOptions = false, mapFocusLocationId = locationId) }
    }

    fun consumeMapFocus() {
        local.update { it.copy(mapFocusLocationId = null) }
    }

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
        val hearted = !heartStore.hearts(eventId).has(kind, id)
        setLiked(kind, id, hearted)
        // Assistant spec §6, like clash: a liked item that overlaps the plan → "Add to plan instead of X?".
        val bundle = repository.bundle.value ?: return
        val plan = planStore.plan(eventId)?.takeIf { !it.isEmpty }
        if (hearted && kind == SearchKind.SCHEDULE && plan != null && !screenshotMode) {
            LiveAssist.likeClash(id, plan, bundle)?.let { chip ->
                local.update { it.copy(message = chip.label, messageChip = chip) }
            }
        }
    }

    private fun setLiked(kind: SearchKind, id: Int, liked: Boolean): Boolean {
        val eventId = repository.bundle.value?.event?.id ?: return false
        if (!heartStore.set(eventId, kind, id, liked)) return false
        Hearts.metric(kind, liked)?.let { track(it, id.toString()) }
        assistantController.refreshLive()
        return true
    }

    /** The snackbar's action for [AppUiState.messageChip]. */
    fun runMessageChip() {
        val chip = local.value.messageChip ?: return
        local.update { it.copy(messageChip = null) }
        val result = assistantController.run(chip.action, chip.label, ActionSource.MANUAL)
        if (result != null) local.update { it.copy(message = result) }
    }

    // ── Search & assistant ──────────────────────────────────────────────────

    /**
     * An internal open link (launcher shortcut, AppFunctions result). An item opens on its own tab
     * when `display.tabs` shows it, otherwise on the search screen. Ignored when it names another
     * event than the joined one; waits for the saved bundle on a cold start.
     */
    fun open(request: OpenRequest) {
        viewModelScope.launch {
            startJob.join()
            val bundle = repository.bundle.value ?: return@launch
            if (request.eventId != null && request.eventId != bundle.event.id) return@launch
            val tabs = bundle.display.tabs
            fun search(query: String, detail: SearchEntry? = null) = local.update {
                it.copy(showOptions = false, searchRequest = SearchRequest(query, (it.searchRequest?.serial ?: 0) + 1, detail))
            }
            when (val target = request.target) {
                is OpenTarget.Location ->
                    if (Tab.LOCATIONS in tabs) {
                        local.update { it.copy(showOptions = false) }
                        focusLocation(target.id)
                    } else {
                        search("", SearchEntry(SearchKind.LOCATION, target.id))
                    }
                is OpenTarget.ScheduleItem ->
                    if (Tab.SCHEDULE in tabs) {
                        local.update { it.copy(showOptions = false) }
                        focusScheduleItem(target.id)
                    } else {
                        search("", SearchEntry(SearchKind.SCHEDULE, target.id))
                    }
                is OpenTarget.Notification -> when {
                    Tab.NOTIFICATIONS !in tabs -> search("", target.id?.let { SearchEntry(SearchKind.NOTIFICATION, it) })
                    target.id != null -> focusNotification(target.id)
                    else -> local.update { it.copy(selectedTab = Tab.NOTIFICATIONS, showOptions = false) }
                }
                OpenTarget.EventInfo -> showOptions(true)
                is OpenTarget.Search -> search(target.query)
                is OpenTarget.MapLocation ->
                    if (Tab.MAP in tabs) showOnMap(target.id)
                    else if (Tab.LOCATIONS in tabs) {
                        local.update { it.copy(showOptions = false) }
                        focusLocation(target.id)
                    } else {
                        search("", SearchEntry(SearchKind.LOCATION, target.id))
                    }
                OpenTarget.MyPlan -> showPlan()
            }
        }
    }

    fun consumeSearchRequest() {
        local.update { it.copy(searchRequest = null, assistantOpen = it.assistantOpen || it.searchRequest != null) }
    }

    // ── Event assistant (assistant spec) ────────────────────────────────────

    /** The assistant: conversation, actions, live hints (see [AssistantController]). */
    val assistantController: AssistantController = AssistantController(viewModelScope, repository, heartStore, planStore, assistant, object : AssistantHost {
        override fun modelAvailable(): Boolean = !screenshotMode && local.value.assistant.availability == AssistantAvailability.AVAILABLE
        override fun setLiked(kind: SearchKind, id: Int, liked: Boolean): Boolean = this@EventViewModel.setLiked(kind, id, liked)
        override suspend fun makePlan(request: String, source: String): Plan? = buildPlan(request, source)
        override fun planError(): String? = local.value.planError
        override fun open(screen: String, ref: EntityRef?) = openScreen(screen, ref)
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

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun userLocationSource(): GeoPoint? =
        (appContext.applicationContext as? AttndApplication)?.graph?.userLocation?.location?.value

    /** The search FAB: opens the assistant. */
    fun openAssistant() {
        if (screenshotMode) return
        AppLog.debug(LogCategory.UI, LogEvent.SCREEN, "Screen assistant", mapOf("name" to "assistant"))
        local.update { it.copy(assistantOpen = true, showOptions = false) }
    }

    fun closeAssistant() {
        local.update { it.copy(assistantOpen = false, searchRequest = null) }
    }

    /** "Ask about this" on a detail screen: the assistant with that item as context. */
    fun askAbout(kind: SearchKind, id: Int) {
        val ref = when (kind) {
            SearchKind.SCHEDULE -> EntityRef(RefKind.SCHEDULE, id)
            SearchKind.LOCATION -> EntityRef(RefKind.LOCATION, id)
            SearchKind.NOTIFICATION -> EntityRef(RefKind.UPDATE, id)
            SearchKind.EVENT -> return
        }
        local.update { it.copy(assistantOpen = true, showOptions = false, searchRequest = SearchRequest("", (it.searchRequest?.serial ?: 0) + 1, about = ref)) }
    }

    /** `reminders(on)`: reminders before liked and planned items (the caller holds POST_NOTIFICATIONS to turn on). */
    fun setReminders(on: Boolean) {
        val eventId = repository.bundle.value?.event?.id ?: return
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Reminders ${if (on) "on" else "off"}", mapOf("name" to if (on) "reminders_on" else "reminders_off"))
        planStore.setReminders(eventId, on)
    }

    /** `open(screen, id?)` from the assistant: closes it and shows the manual screen. */
    private fun openScreen(screen: String, ref: EntityRef?) {
        val bundle = repository.bundle.value ?: return
        val tabs = bundle.display.tabs
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
            "tour" -> showTour()
            "plan" -> showPlan()
        }
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
            local.update { it.copy(assistantOpen = true, showOptions = false, showOnboarding = false) }
            assistantController.openShared(payload, joined = !already)
        }
    }

    /** Checks once (when the search screen opens) whether the on-device model can be used. */
    fun checkAssistant() {
        if (screenshotMode) return
        val current = local.value.assistant.availability
        if (current != AssistantAvailability.UNKNOWN && current != AssistantAvailability.DOWNLOADING) return
        viewModelScope.launch {
            val availability = assistant.availability()
            AppLog.info(LogCategory.ASSISTANT, LogEvent.MODEL_STATUS, "Gemini Nano: $availability", mapOf("status" to availability))
            AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_AVAILABILITY, "Planner: $availability", mapOf("status" to availability))
            local.update { it.copy(assistant = it.assistant.copy(availability = availability)) }
        }
    }

    /** Downloads Gemini Nano (only when the person asks for it). */
    fun downloadAssistant() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            local.update { it.copy(assistant = it.assistant.copy(availability = AssistantAvailability.DOWNLOADING, downloadError = null)) }
            assistant.download().collect { status ->
                when (status) {
                    is AssistantDownload.Started -> AppLog.info(LogCategory.ASSISTANT, LogEvent.MODEL_DOWNLOAD, "Model download started", mapOf("state" to "started", "pct" to 0))
                    AssistantDownload.Completed -> AppLog.info(LogCategory.ASSISTANT, LogEvent.MODEL_DOWNLOAD, "Model download finished", mapOf("state" to "finished", "pct" to 100))
                    is AssistantDownload.Failed -> AppLog.warning(LogCategory.ASSISTANT, LogEvent.MODEL_DOWNLOAD, "Model download failed: ${status.message}", mapOf("state" to "failed"))
                    is AssistantDownload.Progress -> Unit
                }
                local.update { state ->
                    val a = state.assistant
                    state.copy(
                        assistant = when (status) {
                            is AssistantDownload.Started -> a.copy(totalBytes = status.totalBytes, downloadedBytes = 0)
                            is AssistantDownload.Progress -> a.copy(downloadedBytes = status.downloadedBytes)
                            AssistantDownload.Completed -> a.copy(availability = AssistantAvailability.AVAILABLE)
                            is AssistantDownload.Failed -> a.copy(availability = AssistantAvailability.DOWNLOADABLE, downloadError = status.message)
                        }
                    )
                }
            }
            // The flow can end without a final status: ask AICore again.
            if (local.value.assistant.availability == AssistantAvailability.DOWNLOADING) {
                val availability = assistant.availability()
                local.update { it.copy(assistant = it.assistant.copy(availability = availability)) }
            }
        }
    }

    // ── Planner ─────────────────────────────────────────────────────────────

    /**
     * Makes and stores a plan for [request]: the on-device model extracts preferences (JSON, validated
     * against the bundle; the plain-rule reading fills gaps or replaces it on failure), then the
     * deterministic [Planner] schedules. Returns null when there is no event or the planner is off.
     */
    private suspend fun buildPlan(request: String, source: String): Plan? {
        val bundle = repository.bundle.value ?: return null
        if (!uiState.value.planner.available) return null
        AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_REQUEST, "Plan requested ($source)", mapOf("source" to source) + Redact.textFields("query", request, AppLog.allowText))
        local.update { it.copy(isPlanning = true, planError = null) }
        try {
            val now = Instant.now()
            val hearts = heartStore.hearts(bundle.event.id)
            val rules = PlanRequest.heuristic(request, bundle, now)
            val modelStarted = System.nanoTime()
            val model = try {
                val prompt = PlanRequest.prompt(bundle, request, hearts, now)
                val raw = assistant.extractPreferences(prompt)
                PlanRequest.parse(raw, bundle, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.PLANNER, "plan.preferences", e, LogLevel.WARNING)
                null
            }
            val preferences = PlanRequest.merge(model, rules)
            AppLog.log(
                if (model != null) LogLevel.INFO else LogLevel.WARNING, LogCategory.PLANNER, LogEvent.PLANNER_EXTRACT,
                if (model != null) "Preferences from Gemini Nano" else "Preferences from the plain rules only",
                mapOf(
                    "ms" to (System.nanoTime() - modelStarted) / 1_000_000, "ok" to (model != null),
                    "model" to if (model != null) "gemini_nano" else "rules",
                    "liked_tags" to preferences.likedTags.size, "disliked_tags" to preferences.dislikedTags.size, "days" to preferences.days.size,
                ),
            )
            val plan = timedPlan { Planner(bundle, preferences, hearts).plan(now, request.trim()) }
            if (plan.isEmpty) {
                local.update { it.copy(planError = "Nothing on the schedule fits that. Try another day or fewer limits.") }
                return plan
            }
            planStore.applyPlan(bundle.event.id, plan)
            return plan
        } finally {
            local.update { it.copy(isPlanning = false) }
        }
    }

    /** From the plan card in Schedule (or an example chip). */
    fun makePlan(request: String, source: String = "card") {
        val trimmed = request.trim()
        if (trimmed.isEmpty() || planJob?.isActive == true) return
        planJob = viewModelScope.launch { buildPlan(trimmed, source) }
    }

    /** "Re-plan from now": the same preferences from the current time (also after a content change). */
    fun replanFromNow() {
        val bundle = repository.bundle.value ?: return
        val plan = planStore.plan(bundle.event.id) ?: return
        val replanned = timedPlan {
            Planner(bundle, plan.preferences, heartStore.hearts(bundle.event.id), plan.constraints).replan(plan, Instant.now())
        }
        if (replanned.isEmpty) {
            local.update { it.copy(planError = "Nothing left on the schedule fits this plan.") }
        } else {
            local.update { it.copy(planError = null) }
            planStore.applyPlan(bundle.event.id, replanned.copy(constraints = plan.constraints, checkedOff = plan.checkedOff))
        }
    }

    fun swapPlanItem(oldId: Int, newId: Int) {
        val bundle = repository.bundle.value ?: return
        val plan = planStore.plan(bundle.event.id) ?: return
        val swapped = Planner(bundle, plan.preferences, heartStore.hearts(bundle.event.id)).swap(plan, oldId, newId) ?: return
        AppLog.info(LogCategory.PLANNER, LogEvent.PLANNER_SWAP, "Plan item swapped", mapOf("item_id" to oldId))
        planStore.applyPlan(bundle.event.id, swapped)
    }

    /** "Keep my plan" after a content change: the changed items are accepted as they are now. */
    fun keepChangedPlan() {
        val bundle = repository.bundle.value ?: return
        val plan = planStore.plan(bundle.event.id) ?: return
        val kept = plan.days.map { day -> day.copy(entries = day.entries.filter { it !is PlannedItem || bundle.scheduleItem(it.itemId) != null }) }
        val updated = plan.copy(
            days = kept,
            fingerprints = kept.flatMap { it.items }.mapNotNull { bundle.scheduleItem(it.itemId) }.associate { it.id to Planner.fingerprint(it) },
        )
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Changed plan kept", mapOf("name" to "plan_keep"))
        planStore.applyPlan(bundle.event.id, updated)
    }

    fun clearPlan() {
        val eventId = repository.bundle.value?.event?.id ?: return
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Plan cleared", mapOf("name" to "plan_clear"))
        planStore.applyPlan(eventId, null)
        local.update { it.copy(planError = null) }
    }

    /** Opens Schedule → My plan. */
    fun showPlan() {
        val bundle = repository.bundle.value ?: return
        if (Tab.SCHEDULE !in bundle.display.tabs) return
        local.update { it.copy(selectedTab = Tab.SCHEDULE, showOptions = false, showPlanRequest = it.showPlanRequest + 1) }
    }

    // ── Live Update and onboarding ─────────────────────────────────────────

    /** "Follow on Lock Screen" (the caller holds POST_NOTIFICATIONS when turning it on). */
    fun setFollowLive(follow: Boolean) {
        val eventId = repository.bundle.value?.event?.id ?: return
        AppLog.notice(LogCategory.LIVE, LogEvent.LIVE_FOLLOW_CHANGED, "Follow on Lock Screen ${if (follow) "on" else "off"}", mapOf("on" to follow, "default" to false))
        planStore.setFollows(eventId, follow)
    }

    fun showTour() {
        local.update { it.copy(showOptions = false, showOnboarding = true) }
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
            local.update {
                it.copy(showOptions = false, assistantOpen = true, searchRequest = SearchRequest(example.text, (it.searchRequest?.serial ?: 0) + 1, ask = true))
            }
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

    fun showOptions(show: Boolean) {
        local.update { it.copy(showOptions = show) }
    }

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

    /** One planner run: Trace section `planner` (+ `perf.signpost`), `planner.run`, API-014 `perf_planner_ms`. */
    private inline fun timedPlan(block: () -> Plan): Plan {
        val started = System.nanoTime()
        val plan = AppLog.section("planner") { block() }
        val ms = (System.nanoTime() - started) / 1_000_000
        AppLog.info(
            LogCategory.PLANNER, LogEvent.PLANNER_RUN, "Plan: ${plan.itemIds.size} items in $ms ms",
            mapOf(
                "ms" to ms, "items" to plan.itemIds.size, "days" to plan.days.size,
                "meals" to plan.days.sumOf { day -> day.entries.count { it is MealBreak } },
                "pace" to plan.preferences.pace,
            ),
        )
        if (!screenshotMode) perf.record(PerfMetric.PLANNER_MS, PerfMetric.plannerBucket(ms))
        return plan
    }

    companion object {
        private const val RESUME_DEBOUNCE_MS = 30 * 1000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as AttndApplication
                EventViewModel(
                    app, app.graph.repository, app.graph.prefs, app.graph.usage, app.graph.assistant, app.graph.hearts,
                    app.graph.plans, app.graph.liveUpdates, app.graph.diagnostics, app.graph.perf,
                )
            }
        }
    }
}
