package com.arcu.attnd

import android.app.Application
import android.content.Context
import com.arcu.attnd.data.AttestedTokenProvider
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.push.PushNotifications
import com.arcu.attnd.util.integrity.AttestationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AttndApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = DevicePrefs(this)
        // Attestation must be ready before the first API call; warmup() fetches the JWT in the
        // background so it is usually cached by the time the UI needs data.
        AttestationManager.init(this) { prefs.deviceId }
        AttestationManager.warmup()
        PushNotifications.createChannel(this)
        graph = AppGraph(this, prefs)
    }
}

/** Process-wide singletons (manual DI). */
class AppGraph(context: Context, val prefs: DevicePrefs) {
    private val appContext = context.applicationContext

    /** For work that must outlive a screen, e.g. a refresh triggered by a push. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val api = AttndApi(AttestedTokenProvider)

    /** API-005: anonymous usage counts, flushed in the background on [appScope]. */
    val usage = UsageReporter(appScope, sender = { eventId, request -> api.sendUsage(eventId, request) })

    val repository = EventRepository(
        api = api,
        prefs = prefs,
        cacheFile = File(appContext.filesDir, "event_bundle.json"),
        appVersion = BuildConfig.VERSION_NAME,
        pushTokenProvider = { PushNotifications.currentToken(appContext) },
        usage = usage,
    )
}
