package com.arcu.attnd

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.Trace
import com.arcu.attnd.data.AttestedTokenProvider
import com.arcu.attnd.data.BlockStoreDeviceId
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeadingSource
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.ImageDiskCache
import com.arcu.attnd.data.OnDeviceAssistant
import com.arcu.attnd.data.PermissionsState
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.data.ScheduleController
import com.arcu.attnd.data.ScheduleStore
import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.UserLocationSource
import com.arcu.attnd.data.WalkRouter
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.log.AndroidLogSink
import com.arcu.attnd.data.log.DiagnosticsController
import com.arcu.attnd.data.log.ExitReporter
import com.arcu.attnd.data.log.LaunchTiming
import com.arcu.attnd.data.log.LogStore
import com.arcu.attnd.data.log.MainThreadWatchdog
import com.arcu.attnd.data.net.ServerHost
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DebugMode
import com.arcu.attnd.domain.DeviceIdentity
import com.arcu.attnd.domain.DiagnosticsSource
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.ServerSwitch
import com.arcu.attnd.live.LiveNotifications
import com.arcu.attnd.live.LiveUpdateController
import com.arcu.attnd.push.PushNotifications
import com.arcu.attnd.ui.components.ImageLoader
import com.arcu.attnd.util.integrity.AttestationManager
import com.arcu.attnd.domain.InAppBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

class AttndApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        LaunchTiming.onProcessStart(Process.getStartUptimeMillis())
        val prefs = DevicePrefs(this)
        // Before anything builds a URL: the test server picked in Options (dev and debug builds), else the build's.
        ServerHost.install(ServerSwitch.resolve(BuildConfig.ATTND_SERVER, prefs.serverChoice, BuildConfig.DEV))
        // Logging first (spec §2), so everything after it can log: logcat + the ring buffer.
        val logStore = LogStore(File(filesDir, "logs"))
        val logSink = AndroidLogSink(this, logStore, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()) { prefs.eventId }
        AppLog.minLevel = if (BuildConfig.DEV) LogLevel.DEBUG else DebugMode.RELEASE_LEVEL
        AppLog.sink = logSink
        Trace.beginSection("app.onCreate")
        val blockStore = BlockStoreDeviceId(this)
        try {
            // Before anything reads the device id (attestation, the first API call).
            resolveDeviceId(prefs, blockStore)
            // Attestation must be ready before the first API call; warmup() fetches the JWT in the
            // background so it is usually cached by the time the UI needs data.
            AttestationManager.init(this) { prefs.deviceId }
            AttestationManager.warmup()
            PushNotifications.createChannel(this)
            LiveNotifications.createChannels(this)
            graph = AppGraph(this, prefs, logStore, logSink)
        } finally {
            Trace.endSection()
        }
        graph.exits.installCrashHandler(logSink)
        // perf.hang (iOS MainThreadWatchdog): debug and dev builds only.
        if (BuildConfig.DEV) MainThreadWatchdog { logSink.isForeground }.start()
        graph.exits.reportPendingLater()
        // Usual launch: give Block Store the id when it lacks it (an install from before Block Store).
        graph.appScope.launch { syncDeviceIdToBlockStore(prefs, blockStore) }
    }

    /**
     * The first launch of an install (no id in the app's preferences): a reinstall on the same phone
     * takes its old id back from Block Store, waiting at most [BLOCK_STORE_TIMEOUT_MS] (only this
     * once; Play services' callbacks don't need the main thread), else a new UUID
     * ([DeviceIdentity.resolve]). Later launches read the app's own copy straight away.
     */
    private fun resolveDeviceId(prefs: DevicePrefs, blockStore: BlockStoreDeviceId) {
        if (prefs.storedDeviceId != null) return
        val kept = runBlocking(Dispatchers.IO) { withTimeoutOrNull(BLOCK_STORE_TIMEOUT_MS) { blockStore.read() } }
        val resolution = DeviceIdentity.resolve(prefs = null, blockStore = kept) { UUID.randomUUID().toString() }
        prefs.setDeviceId(resolution.id)
    }

    private suspend fun syncDeviceIdToBlockStore(prefs: DevicePrefs, blockStore: BlockStoreDeviceId) {
        val id = prefs.storedDeviceId ?: return
        val resolution = DeviceIdentity.resolve(prefs = id, blockStore = blockStore.read()) { id }
        if (resolution.saveBlockStore) blockStore.write(resolution.id)
    }

    private companion object {
        const val BLOCK_STORE_TIMEOUT_MS = 2_000L
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the levels that mean memory is short (not just "UI hidden").
        if (level >= TRIM_MEMORY_BACKGROUND) {
            AppLog.warning(LogCategory.LIFECYCLE, LogEvent.MEMORY_WARNING, "Memory is low (trim level $level)", mapOf("level" to level))
        }
    }
}

/** Process-wide singletons (manual DI). */
class AppGraph(context: Context, val prefs: DevicePrefs, logStore: LogStore, val logSink: AndroidLogSink) {
    private val appContext = context.applicationContext

    /** For work that must outlive a screen, e.g. a refresh triggered by a push. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val api = AttndApi(AttestedTokenProvider)

    /** API-005: anonymous usage counts, flushed in the background on [appScope]. */
    val usage = UsageReporter(appScope, sender = { eventId, request -> api.sendUsage(eventId, request) })

    /** API-014: anonymous performance counts, sent with the usage batch of the joined event. */
    val perf = PerfRecorder(usage, eventId = { prefs.eventId })

    private val diagnosticsSource = DiagnosticsSource(
        app = BuildConfig.VERSION_NAME,
        build = BuildConfig.VERSION_CODE.toString(),
        os = "Android ${Build.VERSION.RELEASE}",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".take(48),
    )

    /** API-013: debug mode, levels, log uploads (dev builds / debug sessions only) and the log viewer's data. */
    val diagnostics = DiagnosticsController(
        appContext, BuildConfig.DEV, BuildConfig.UPLOAD_LOGS, logStore, logSink, appScope, diagnosticsSource,
        send = { api.uploadDiagnostics("diagnostics/logs/", it) },
    )

    /** Crash / ANR / low-memory exits, reported anonymously at the next launch. */
    val exits = ExitReporter(
        appContext, appScope, diagnosticsSource,
        send = { api.uploadDiagnostics("diagnostics/reports/", it) },
        diag = { diagnostics.diagCode },
    )

    /**
     * API-007: the event's images in `filesDir/images/` (not the cache dir, which the OS may purge
     * mid-event). Read by [ImageLoader]; prefetched and cleared by [repository].
     */
    private val images = ImageDiskCache(File(appContext.filesDir, "images"), appScope).also {
        ImageLoader.diskCache = it
    }

    val repository = EventRepository(
        api = api,
        prefs = prefs,
        cacheFile = File(appContext.filesDir, "event_bundle.json"),
        appVersion = BuildConfig.VERSION_NAME,
        pushTokenProvider = { PushNotifications.currentToken(appContext) },
        usage = usage,
        images = images,
    )

    /** My schedule per event, on the device: the only stored list (migrated once from hearts and an older build's plan). */
    val scheduleStore = ScheduleStore.of(prefs)

    /** Hearted schedule items and locations, per event: My schedule's mirror (UI and the Live Update). */
    val hearts = HeartStore(scheduleStore)

    /** The user's position and the compass heading, only while a screen showing distances / arrows is visible. */
    val userLocation = UserLocationSource(appContext)
    val heading = HeadingSource(appContext, userLocation)

    /** Walking routes for the map card (Routes API with the Maps key, estimate fallback). */
    val walkRouter = WalkRouter(appContext, BuildConfig.MAPS_API_KEY)

    /** "Ask about this event" and the planner: Gemini Nano on the device (ML Kit GenAI Prompt API), created lazily. */
    val assistant = OnDeviceAssistant()

    /** The assistant's conversation, "Follow on Lock Screen", skipped items, urgent block and reminders, per event (on the device). */
    val plans = PlanStore(prefs)

    /** My schedule: adds, removes, times, reminders (API-009 counts), shared by the UI, the assistant and the planner. */
    val schedule = ScheduleController(
        store = scheduleStore,
        bundle = { repository.bundle.value },
        track = { eventId, name, detail -> usage.track(eventId, name, detail) },
    )

    /** What the system allows the app (notifications, location), read on demand. */
    val permissions = PermissionsState(appContext, prefs)

    /** Organizer updates pushed while the app is open: the shell's in-app banner (iOS `InAppBannerOverlay`). */
    val inAppBanners = MutableSharedFlow<InAppBanner>(extraBufferCapacity = 4)

    /** The device-driven Live Update and reminders (shared spec §8). */
    val liveUpdates = LiveUpdateController(appContext, repository, hearts, plans, schedule, appScope, perf, foreground = { logSink.isForeground })

    init {
        api.diagSession = { diagnostics.diagCode }
        api.callListener = { call -> perf.onApiCall(call, logSink.isOffline) }
        // Leaves made offline in an earlier run (also retried after every successful refresh).
        appScope.launch { repository.sendPendingLeaves() }
        // Performance counts recorded outside an event go to the next one (API-014).
        appScope.launch {
            repository.bundle.map { it?.event?.id }.distinctUntilChanged().collect { perf.onEvent(it) }
        }
        // A new bundle gives My schedule's entries their fingerprints (organizer changes).
        appScope.launch { repository.bundle.collect { bundle -> bundle?.let(schedule::onBundle) } }
        // The Live Update follows the bundle (joins, refreshes, content_updated pushes), My schedule and its settings.
        appScope.launch {
            combine(repository.bundle, hearts.version, plans.version) { bundle, _, _ -> bundle?.contentVersion to bundle?.event?.id }
                .collect { liveUpdates.refresh() }
        }
    }
}
