package com.arcu.attnd.data.log

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.HangRule
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug and dev builds only (iOS `MainThreadWatchdog`): a background thread posts a ping to the main looper every
 * [HangRule.PING_INTERVAL_MS]; when the main thread takes longer than [HangRule.THRESHOLD_MS] to run it, the stall
 * is logged as `perf.hang` (warning) with its `ms` and the Trace section running then ([AppLog.runningSignpost]).
 * Idle while the app is in the background ([isForeground]), so it costs nothing there. Never in release builds.
 */
class MainThreadWatchdog(private val isForeground: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        val thread = Thread(::loop, "attnd-watchdog")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    private fun loop() {
        while (true) {
            try {
                if (!isForeground()) {
                    Thread.sleep(BACKGROUND_SLEEP_MS)
                    continue
                }
                val posted = SystemClock.uptimeMillis()
                val answered = AtomicLong(0)
                main.post { answered.set(SystemClock.uptimeMillis()) }
                Thread.sleep(HangRule.PING_INTERVAL_MS)
                if (answered.get() == 0L) {
                    // Late: note what was running, then wait for the answer to measure the whole stall.
                    val signpost = AppLog.runningSignpost()
                    while (answered.get() == 0L) Thread.sleep(POLL_MS)
                    val ms = answered.get() - posted
                    if (HangRule.isHang(ms)) {
                        AppLog.warning(
                            LogCategory.PERF, LogEvent.PERF_HANG, "Main thread stalled for $ms ms",
                            mapOf("ms" to ms, "signpost" to signpost),
                        )
                    }
                }
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val POLL_MS = 10L
        const val BACKGROUND_SLEEP_MS = 1_000L
    }
}
