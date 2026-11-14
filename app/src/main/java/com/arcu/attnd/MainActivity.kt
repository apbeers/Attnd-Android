package com.arcu.attnd

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.ScreenshotRequest
import com.arcu.attnd.push.PushNotifications
import com.arcu.attnd.ui.AttndApp
import com.arcu.attnd.ui.EventViewModel
import kotlinx.coroutines.launch

/**
 * Single activity. All state lives in [EventViewModel] / EventRepository; this class only wires
 * up intents (App Links, notification taps, debug store-screenshot extras) and the foreground refresh loop.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: EventViewModel by viewModels { EventViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
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
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded (not just rotated): send usage counts now; the next resume is an app_open.
        if (!isChangingConfigurations) viewModel.onAppBackground()
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
                // Never show the on-screen keyboard in a store screenshot.
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                viewModel.startScreenshot(screenshot)
                return
            }
        }
        if (PushNotifications.opensNotifications(intent)) {
            viewModel.openNotifications(PushNotifications.notificationId(intent))
            return
        }
        // App Link: https://attnd.app/join/<code>/
        if (intent.action == Intent.ACTION_VIEW) {
            JoinLinks.parseJoinCode(intent.dataString)?.let { viewModel.joinByCode(it) }
        }
    }
}
