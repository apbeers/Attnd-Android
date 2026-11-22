package com.arcu.attnd.ui

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
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
import com.arcu.attnd.data.PreviewPoller
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
import com.arcu.attnd.domain.MySchedule
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
import com.arcu.attnd.domain.PreviewPoll
import com.arcu.attnd.domain.LiveUpdates
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.PermissionRules
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
import com.arcu.attnd.domain.EventTab
import com.arcu.attnd.domain.InAppBanner
import com.arcu.attnd.domain.StatusBadge
import com.arcu.attnd.domain.StatusBadges
import com.arcu.attnd.domain.TabLayout
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
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
    /** The navigation (iOS `TabLayout`): Home first and the default, then the event's tabs; today's tabs in screenshot mode. */
    val tabs: TabLayout = TabLayout.resolve(null, null, hasFlyer = false),
    /** The visible tab (Home or one of the event's). */
    val selectedEventTab: EventTab = EventTab.HOME,
    /** The organizer tab whose screen is visible; null on Home. */
    val selectedTab: Tab? = null,
    /** Incremented to focus My plan's input bar (the My plan button). */
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
    /** The search sheet (no AI) is open, and its one-shot request (a query, an item of a hidden tab). */
    val searchOpen: Boolean = false,
    val searchRequest: SearchRequest? = null,
    /** My plan (the planner and assistant; a full-screen destination until phase 6b) is open, and its bar's request. */
    val planOpen: Boolean = false,
    val planRequest: SearchRequest? = null,
    /**
     * The floating button opens My plan (Gemini Nano available, downloadable or downloading; plan §4.3)
     * rather than the search sheet. Never in screenshot mode.
     */
    val planButton: Boolean = false,
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
    /** Floating status pills not swiped away this session (iOS `EventStatusBadgeKind.all`); none in screenshot mode. */
    val statusBadges: List<StatusBadge> = emptyList(),
    /** Every pill, swiped away or not, for Options → Status. */
    val allStatusBadges: List<StatusBadge> = emptyList(),
    /** An organizer update that arrived while the app was open (top banner, ~6 s). */
    val inAppBanner: InAppBanner? = null,
    /** Store-screenshot mode: no location tracking, map glance features, badge or banner. */
    val isScreenshot: Boolean = false,
    /** API-026: this phone is the event's preview phone (the green "Instant updates on" capsule). */
    val instantUpdates: Boolean = false,
    val planner: PlanUiState = PlanUiState(),
    /** The onboarding tour (first join of an event, or Options → Tour). */
    val showOnboarding: Boolean = false,
    /** "Follow on Lock Screen" for the joined event (Live Update + reminders). */
    val followLive: Boolean = false,
    /** Debug logging and the log viewer (About & support; logging spec §2–3). */
    val diagnostics: DiagnosticsState = DiagnosticsState(dev = false),
    /** The joined event's My schedule (Home's "Up next in my schedule"); null outside an event and in screenshot mode. */
    val mySchedule: MySchedule? = null,
    /** Urgent announcements swiped off Home (per event; they stay in Updates). */
    val dismissedUrgentIds: Set<Int> = emptySet(),
    /** A change that can be undone from a snackbar ("Dismissed … · Undo"). */
    val undoToast: UndoToast? = null,
)

/** A snackbar offering Undo for the change it names ([id] tells two toasts with the same text apart). */
class UndoToast(val id: Long, val message: String, val undo: () -> Unit)

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
    /** Status pills swiped away this session (`ended`, `refresh`, `instant`). */
    val dismissedBadges: Set<String> = emptySet(),
    val inAppBanner: InAppBanner? = null,
    /** Per event: urgent announcements swiped off Home (loaded from prefs on first use). */
    val dismissedUrgent: Map<Int, Set<Int>> = emptyMap(),
    val undoToast: UndoToast? = null,
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
    /** Organizer updates pushed while the app is open (`AppGraph.inAppBanners`). */
    private val inAppBanners: Flow<InAppBanner> = emptyFlow(),
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

    private val previewPoller = PreviewPoller(repository)

    private val local = MutableStateFlow(
        LocalState(
            notificationsEnabled = prefs.notificationsEnabled,
            showUserLocation = prefs.showUserLocation,
            dismissedUpdateVersion = prefs.dismissedUpdateVersion,
        )
    )

    /** Gemini Nano's availability and download (checked once an event is shown). */
    private val model = OnDeviceModelController(viewModelScope, assistant) { screenshotMode }

    /** The selected tab, focus requests, the search sheet, My plan and Options. */
    private val nav = NavigationController(current = { uiState.value })

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
        mealsCovered = schedule::mealsCovered,
    )

    val uiState: StateFlow<AppUiState> =
        combine(
            repository.bundle,
            combine(repository.sync, repository.previewEventId, ::Pair),
            combine(local, nav.state, planner.state, model.state, ::UiInputs),
            repository.appUpdate,
            combine(heartStore.version, planStore.version, diagnostics.state) { hearts, plans, diag -> Triple(hearts, plans, diag) },
        ) { savedBundle, (savedSync, previewEventId), inputs, savedUpdate, versions ->
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
            val tabs = display?.tabLayout(screenshotMode = screenshot != null) ?: TabLayout.resolve(null, null, hasFlyer = false)
            val selectedEventTab = tabs.selected(navState.selectedTab)
            val instantUpdates = PreviewPoll.showsCapsule(previewEventId, eventId, screenshot != null)
            val badges = if (screenshot != null || bundle == null) emptyList() else StatusBadges.all(
                ended = bundle.event.isArchived,
                refreshTitle = sync.error?.let { refreshTitle(sync) },
                offline = sync.offline,
                instantUpdates = instantUpdates,
            )
            val model = if (screenshot != null) AssistantUiState(availability = AssistantAvailability.UNAVAILABLE) else inputs.model
            AppUiState(
                phase = when {
                    !state.ready || state.screenshotPending -> AppPhase.LOADING
                    bundle != null -> AppPhase.EVENT
                    else -> AppPhase.LAUNCH
                },
                bundle = bundle,
                sync = sync,
                tabs = tabs,
                selectedEventTab = selectedEventTab,
                selectedTab = selectedEventTab.tab,
                focusInput = navState.focusInput,
                join = state.join,
                nearby = state.nearby,
                showOptions = navState.showOptions && bundle != null,
                notificationsEnabled = state.notificationsEnabled,
                showUserLocation = state.showUserLocation,
                focusLocationId = navState.focusLocationId,
                focusScheduleItemId = navState.focusScheduleItemId,
                focusNotificationId = navState.focusNotificationId,
                searchOpen = (navState.searchOpen || navState.searchRequest != null) && bundle != null,
                searchRequest = navState.searchRequest.takeIf { bundle != null },
                planOpen = navState.planOpen && bundle != null && screenshot == null,
                planRequest = navState.planRequest.takeIf { bundle != null },
                planButton = screenshot == null && model.availability in PLAN_BUTTON_STATES,
                messageChip = state.messageChip.takeIf { screenshot == null && state.message != null },
                remindersOn = eventId != null && planStore.reminders(eventId, hearts),
                // Store screenshots never show the on-device assistant.
                assistant = model,
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
                statusBadges = StatusBadges.visible(badges, state.dismissedBadges),
                allStatusBadges = badges,
                inAppBanner = state.inAppBanner.takeIf { screenshot == null && bundle != null },
                isScreenshot = screenshot != null,
                instantUpdates = instantUpdates,
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
                mySchedule = if (screenshot == null && eventId != null) schedule.schedule(eventId) else null,
                dismissedUrgentIds = eventId?.let { state.dismissedUrgent[it] ?: prefs.homeUrgentDismissed(it) }.orEmpty(),
                undoToast = state.undoToast.takeIf { screenshot == null },
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
                    // My plan (a destination over the tabs) sends nothing new (null); Home sends `home`.
                    else bundle.event.id to when {
                        state.showOptions -> UsageMetric.TAB_OPTIONS
                        state.planOpen -> null
                        else -> TabLayout.usageName(state.selectedEventTab)
                    }
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
                    if (state.phase == AppPhase.EVENT && bundle != null && !state.showOptions && !state.planOpen && state.selectedTab == Tab.NOTIFICATIONS) {
                        bundle.event.id to bundle.notifications
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collect { seen -> if (seen != null) markUpdatesSeen(seen.first, seen.second) }
        }
        // A new refresh problem (or the end of one) shows its pill again after a swipe away.
        viewModelScope.launch {
            var before: String? = null
            repository.sync.map { sync -> sync.error?.let { if (sync.offline) "offline" else "server" } }.distinctUntilChanged().collect { key ->
                local.update { it.copy(dismissedBadges = StatusBadges.afterRefreshChange(it.dismissedBadges, before, key)) }
                before = key
            }
        }
        viewModelScope.launch { inAppBanners.collect(::showInAppBanner) }
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
        // API-026: a "Preview on my phone" link's token, sent once with the join (never logged or shown).
        val previewToken = JoinLinks.previewToken(trimmed)
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
            join(silent = false, via = via, previewToken = previewToken) { repository.lookup(code) }
        }
    }

    fun joinEvent(event: Event) {
        viewModelScope.launch {
            startJob.join()
            join(silent = false, via = "nearby") { event }
        }
    }

    private suspend fun join(silent: Boolean, via: String = "search", previewToken: String? = null, resolve: suspend () -> Event) {
        local.update { it.copy(join = JoinUiState(isJoining = true), message = null) }
        try {
            val event = resolve()
            val alreadyIn = repository.currentEventId == event.id && repository.bundle.value != null
            val result = repository.join(event, via, previewToken)
            // The tour, the first time this event is joined (never in screenshot mode).
            val tour = !alreadyIn && !screenshotMode && !prefs.onboardingSeen(event.id) && repository.bundle.value?.event?.id == event.id
            planner.clearError()
            nav.resetForJoin(alreadyIn)
            if (!alreadyIn) applySystemPermissionsOnJoin()
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
                    nav.update { it.copy(selectedTab = null) }
                } else {
                    join(silent = false, via = "search") { repository.lookup(request.eventCode) }
                    val location = repository.bundle.value?.let { request.focusLocation(it.locations) }
                    nav.update { it.copy(selectedTab = request.tab?.let(EventTab::of), focusLocationId = location?.id) }
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
        // In the foreground the Live Update posts at once, even for a day ahead (iOS "starts on join").
        liveUpdates.requestRefresh()
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

    /**
     * API-026: runs while the activity is resumed: the preview phone's `changes/` long-poll
     * ([PreviewPoller]; idle unless this is the shown event's preview phone). Never in screenshot mode.
     */
    suspend fun previewSync() {
        startJob.join()
        if (screenshotMode) return
        previewPoller.run()
    }

    // ── Navigation & preferences ────────────────────────────────────────────

    fun dismissUpdateBanner() {
        val version = uiState.value.update.latestVersion
        prefs.dismissedUpdateVersion = version
        local.update { it.copy(dismissedUpdateVersion = version) }
    }

    /** Shows [tab]'s screen; reselecting the map recenters it. A tab the event doesn't show changes nothing. */
    fun selectTab(tab: Tab) = nav.selectTab(tab)

    /** A navigation item tapped (Home or one of the event's tabs; today's tabs in screenshot mode). */
    fun selectEventTab(tab: EventTab) = nav.selectEventTab(tab)

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

    /** Home's urgent card swiped away (iOS `dismissUrgentWithUndo`): hidden on Home, kept in Updates, with Undo. */
    fun dismissUrgent(notificationId: Int) {
        val eventId = uiState.value.bundle?.event?.id ?: return
        setUrgentDismissed(eventId, notificationId, dismissed = true)
        showUndo("Urgent update dismissed") { setUrgentDismissed(eventId, notificationId, dismissed = false) }
    }

    private fun setUrgentDismissed(eventId: Int, notificationId: Int, dismissed: Boolean) {
        val current = local.value.dismissedUrgent[eventId] ?: prefs.homeUrgentDismissed(eventId)
        val next = if (dismissed) current + notificationId else current - notificationId
        prefs.setHomeUrgentDismissed(eventId, next)
        local.update { it.copy(dismissedUrgent = it.dismissedUrgent + (eventId to next)) }
    }

    /** Shows the Undo snackbar for a change ([undo] runs when tapped). */
    fun showUndo(message: String, undo: () -> Unit) {
        local.update { it.copy(undoToast = UndoToast(System.nanoTime(), message, undo)) }
    }

    /** The Undo snackbar went away (tapped: [undone]). */
    fun consumeUndo(toast: UndoToast, undone: Boolean) {
        if (undone) toast.undo()
        local.update { if (it.undoToast?.id == toast.id) it.copy(undoToast = null) else it }
    }

    // ── Hearts ──────────────────────────────────────────────────────────────

    /** Hearts or un-hearts a schedule item / location (stored per event; API-009 usage count). */
    fun toggleHeart(kind: SearchKind, id: Int) {
        val eventId = repository.bundle.value?.event?.id ?: return
        val hearted = !schedule.isLiked(eventId, kind, id)
        // Assistant spec §6, like clash: a liked item that overlaps the plan → "Add to plan instead of X?".
        // Checked before the add, which reconciles the plan with My schedule.
        val bundle = repository.bundle.value
        val clash = if (bundle != null && hearted && kind == SearchKind.SCHEDULE && !screenshotMode) schedule.likeClash(bundle, id) else null
        if (!setLiked(kind, id, hearted)) return
        clash?.let { chip -> local.update { it.copy(message = chip.label, messageChip = chip) } }
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
     * the tab layout shows it, otherwise in the search sheet. Ignored when it names another event than
     * the joined one; waits for the saved bundle on a cold start.
     */
    fun open(request: OpenRequest) {
        viewModelScope.launch {
            startJob.join()
            val bundle = repository.bundle.value ?: return@launch
            if (request.eventId != null && request.eventId != bundle.event.id) return@launch
            nav.route(request.target)
        }
    }

    /** The search sheet took its request. */
    fun consumeSearchRequest() = nav.consumeSearchRequest()

    /** My plan's bar took its request. */
    fun consumePlanRequest() = nav.consumePlanRequest()

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

    /** The search button: opens the search sheet (iOS `EventSearchSheet`; no AI). */
    fun openSearch() {
        AppLog.debug(LogCategory.UI, LogEvent.SCREEN, "Screen search", mapOf("name" to "search"))
        nav.openSearch()
    }

    fun closeSearch() = nav.closeSearch()

    /** The My plan button (plan §4.3): opens My plan with its bar focused. */
    fun openPlan() {
        if (screenshotMode) return
        AppLog.debug(LogCategory.UI, LogEvent.SCREEN, "Screen plan", mapOf("name" to "plan"))
        nav.showPlan(focus = true)
    }

    fun closePlan() = nav.closePlan()

    /** Closes whatever covers the tabs (the search sheet, My plan), for "Show on map" and tab links. */
    fun closeOverlays() {
        nav.closeSearch()
        nav.closePlan()
    }

    /** `reminders(on)`: reminders before liked and planned items (the caller holds POST_NOTIFICATIONS to turn on). */
    fun setReminders(on: Boolean) {
        val eventId = repository.bundle.value?.event?.id ?: return
        AppLog.debug(LogCategory.UI, LogEvent.UI_ACTION, "Reminders ${if (on) "on" else "off"}", mapOf("name" to if (on) "reminders_on" else "reminders_off"))
        planStore.setReminders(eventId, on)
    }

    /** `open(screen, id?)` from the assistant: closes it and shows the manual screen. */
    private fun openScreen(screen: String, ref: EntityRef?) {
        if (repository.bundle.value == null) return
        if (!nav.openScreen(screen, ref) && screen == "tour") showTour()
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
            nav.showProposal()
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

    /** Opens My plan. */
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


    // ── Status pills and banners (iOS batch 7) ──────────────────────────────

    /** The refresh pill's text in the device's zone and 12/24-hour setting. */
    private fun refreshTitle(sync: SyncState): String = LiveUpdates.offlineNoticeText(
        offline = sync.offline,
        lastSyncedAt = sync.lastSyncedAt,
        now = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        is24Hour = DateFormat.is24HourFormat(appContext),
    )

    /** A pill swiped away: gone for this session (Options → Status keeps it). */
    fun dismissStatusBadge(kind: StatusBadge.Kind) {
        AppLog.debug(LogCategory.UI, LogEvent.UI_BANNER, "Pill dismissed", mapOf("kind" to kind.bannerKind, "shown" to false))
        local.update { it.copy(dismissedBadges = it.dismissedBadges + kind.id) }
    }

    private val StatusBadge.Kind.bannerKind: String
        get() = when (this) {
            StatusBadge.Kind.ENDED -> "ended"
            StatusBadge.Kind.REFRESH -> "offline"
            StatusBadge.Kind.INSTANT -> "update"
        }

    private var inAppBannerJob: Job? = null

    private fun showInAppBanner(banner: InAppBanner) {
        if (screenshotMode || repository.bundle.value == null) return
        AppLog.debug(LogCategory.UI, LogEvent.UI_BANNER, "In-app banner", mapOf("kind" to if (banner.urgent) "urgent" else "update", "shown" to true))
        local.update { it.copy(inAppBanner = banner) }
        inAppBannerJob?.cancel()
        inAppBannerJob = viewModelScope.launch {
            delay(banner.durationMs)
            local.update { it.copy(inAppBanner = null) }
        }
    }

    fun dismissInAppBanner() {
        inAppBannerJob?.cancel()
        local.update { it.copy(inAppBanner = null) }
    }

    /** The in-app banner tapped: Updates at that update (it stays there and in the shade). */
    fun openInAppBanner() {
        val banner = local.value.inAppBanner ?: return
        dismissInAppBanner()
        nav.closeSearch()
        nav.closePlan()
        nav.route(OpenTarget.Notification(banner.notificationId))
    }

    /** iOS `applySystemPermissionsOnJoin`: what Android already allows is turned on for a new join; never a prompt. */
    private fun applySystemPermissionsOnJoin() {
        if (screenshotMode) return
        val state = local.value
        val apply = PermissionRules.onJoin(permissions.notifications, permissions.location, state.notificationsEnabled, state.showUserLocation)
        if (apply.notifications) setNotificationsEnabled(true)
        if (apply.location) setShowUserLocation(true)
    }

    /** Options → Permissions asked the system for [permission] (so a later refusal reads as "denied"). */
    fun markPermissionAsked(permission: String) = permissions.markAsked(permission)

    /** Whether Attnd asked for [permission] before (Options → Permissions). */
    fun permissionAsked(permission: String): Boolean = permissions.asked(permission)

    companion object {
        private const val RESUME_DEBOUNCE_MS = 30 * 1000L

        /** Model states that show the My plan button (plan §4.3: never when the phone can't run Gemini Nano). */
        private val PLAN_BUTTON_STATES = setOf(AssistantAvailability.AVAILABLE, AssistantAvailability.DOWNLOADABLE, AssistantAvailability.DOWNLOADING)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as AttndApplication
                EventViewModel(
                    app, app.graph.repository, app.graph.prefs, app.graph.usage, app.graph.assistant, app.graph.hearts,
                    app.graph.schedule, app.graph.permissions, app.graph.plans, app.graph.liveUpdates, app.graph.diagnostics, app.graph.perf,
                    app.graph.inAppBanners,
                )
            }
        }
    }
}
