package com.arcu.attnd

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arcu.attnd.assistant.AppShortcuts
import com.arcu.attnd.assistant.OpenIntents
import com.arcu.attnd.data.log.LaunchTiming
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.ScreenshotRequest
import com.arcu.attnd.push.PushNotifications
import com.arcu.attnd.ui.AttndApp
import com.arcu.attnd.ui.EventViewModel
import kotlinx.coroutines.launch

/**
 * Single activity. All state lives in [EventViewModel] / EventRepository; this class only wires
 * up intents (App Links incl. shared plans, notification taps, shortcuts / assistant results, debug store-screenshot extras)
 * and the foreground refresh loop.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: EventViewModel by viewModels { EventViewModel.Factory }

    private val graph get() = (application as AttndApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        val isLaunch = LaunchTiming.onActivityCreate(SystemClock.uptimeMillis(), restored = savedInstanceState != null)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only on a fresh start: after a configuration change the intent was already handled.
        if (savedInstanceState == null) handleIntent(intent)

        // Refresh when the app comes to the foreground, then a safety poll while it stays there
        // (live updates arrive as content_updated pushes; see EventViewModel.foregroundSync).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.foregroundSync() }
        }

        setContent { AttndApp(viewModel) }

        // Launch timing (cold / warm) ends with the first frame committed after this onCreate.
        if (isLaunch) {
            window.decorView.viewTreeObserver.registerFrameCommitCallback {
                LaunchTiming.onFirstFrame(SystemClock.uptimeMillis(), graph.perf, restored = graph.repository.bundle.value != null)
            }
        }
    }

    /** When the app last went to the background / foreground (`since_bg_s`, `fg_s`). */
    private var backgroundedAt = 0L
    private var foregroundedAt = 0L

    override fun onStart() {
        super.onStart()
        if (!isChangingConfigurations) {
            val now = SystemClock.elapsedRealtime()
            foregroundedAt = now
            AppLog.info(
                LogCategory.LIFECYCLE, LogEvent.FOREGROUND, "App in the foreground",
                mapOf("since_bg_s" to (if (backgroundedAt > 0) (now - backgroundedAt) / 1000 else null)),
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded (not just rotated): send usage counts now; the next resume is an app_open.
        if (!isChangingConfigurations) {
            val now = SystemClock.elapsedRealtime()
            backgroundedAt = now
            AppLog.info(
                LogCategory.LIFECYCLE, LogEvent.BACKGROUND, "App in the background",
                mapOf("fg_s" to (if (foregroundedAt > 0) (now - foregroundedAt) / 1000 else null)),
            )
            viewModel.onAppBackground()
            graph.diagnostics.onBackground()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Store screenshots (scripts/ci/screenshots.sh), debug builds only: --es attnd_screenshot <screen>.
        if (BuildConfig.DEBUG) {
            val screenshot = ScreenshotRequest.parse(
                screen = intent.getStringExtra(ScreenshotRequest.EXTRA_SCREEN),
                eventCode = intent.getStringExtra(ScreenshotRequest.EXTRA_EVENT_CODE),
                locationName = intent.getStringExtra(ScreenshotRequest.EXTRA_LOCATION),
            )
            if (screenshot != null) {
                // Screenshot mode is quiet: no log uploads, no performance counts.
                graph.diagnostics.suspendUploads()
                graph.perf.suspended = true
                // Never show the on-screen keyboard in a store screenshot.
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                viewModel.startScreenshot(screenshot)
                return
            }
        }
        if (PushNotifications.opensNotifications(intent)) {
            AppLog.info(
                LogCategory.PUSH, LogEvent.PUSH_OPENED, "Notification opened",
                mapOf("type" to "notification", "notification_id" to PushNotifications.notificationId(intent)),
            )
            viewModel.openNotifications(PushNotifications.notificationId(intent))
            return
        }
        // Internal open links (launcher shortcuts, AppFunctions results): attnd-open://<event>/<target>
        OpenIntents.parse(intent)?.let {
            AppLog.info(
                LogCategory.LIFECYCLE, LogEvent.DEEP_LINK, "Opened by an internal link",
                mapOf("kind" to if (AppShortcuts.isShortcut(intent)) "shortcut" else "open", "target" to it.target.javaClass.simpleName.lowercase()),
            )
            AppShortcuts.reportUsed(this, intent)
            viewModel.open(it)
            return
        }
        // App Links: https://attnd.app/join/<code>/ and shared plans https://attnd.app/p/<code>/<payload> (API-015)
        if (intent.action == Intent.ACTION_VIEW) {
            JoinLinks.parseShareLink(intent.dataString)?.let {
                AppLog.info(LogCategory.LIFECYCLE, LogEvent.DEEP_LINK, "Opened by a shared plan link", mapOf("kind" to "share", "target" to "/p/{id}/{payload}"))
                viewModel.openShareLink(it)
                return
            }
            JoinLinks.parseJoinCode(intent.dataString)?.let {
                AppLog.info(LogCategory.LIFECYCLE, LogEvent.DEEP_LINK, "Opened by a join link", mapOf("kind" to "join", "target" to "/join/{id}/"))
                viewModel.joinByCode(it)
            }
        }
    }
}
