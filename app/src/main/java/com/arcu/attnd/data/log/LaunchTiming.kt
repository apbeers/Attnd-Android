package com.arcu.attnd.data.log

import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.PerfMetric

/**
 * Launch timing (logging spec §1 `lifecycle.launch`, §6 `perf_launch_cold` / `perf_launch_warm`), all in
 * `SystemClock.uptimeMillis` time:
 * - **cold**: the first activity of a process that was started for it (created within
 *   [COLD_WINDOW_MS] of the process start), from the process start to the first committed frame;
 * - **warm**: any other activity creation without saved state (the process was alive), from
 *   `onCreate` to the first committed frame. Re-creations after a configuration change aren't launches.
 * A `launch` Trace section spans the same interval.
 */
object LaunchTiming {
    const val COLD_WINDOW_MS = 10_000L
    private const val SECTION = "launch"

    private var processStartMs = 0L
    private var activitySeen = false
    private var pending: Launch? = null

    private class Launch(val cold: Boolean, val startMs: Long, val cookie: Int)

    /** From `Application.onCreate`. */
    fun onProcessStart(processStartUptimeMs: Long) {
        processStartMs = processStartUptimeMs
    }

    /** From `Activity.onCreate` (main thread). Returns false when this creation isn't a launch. */
    fun onActivityCreate(nowUptimeMs: Long, restored: Boolean): Boolean {
        val first = !activitySeen
        activitySeen = true
        if (restored) return false
        val cold = first && processStartMs > 0 && nowUptimeMs - processStartMs < COLD_WINDOW_MS
        pending = Launch(cold, if (cold) processStartMs else nowUptimeMs, AppLog.beginAsync(SECTION))
        return true
    }

    /** The launch's first frame was committed: logs it and counts it (API-014). */
    fun onFirstFrame(nowUptimeMs: Long, perf: PerfRecorder?, restored: Boolean) {
        val launch = pending ?: return
        pending = null
        AppLog.endAsync(SECTION, launch.cookie)
        val ms = (nowUptimeMs - launch.startMs).coerceAtLeast(0)
        val kind = if (launch.cold) "cold" else "warm"
        AppLog.info(LogCategory.LIFECYCLE, LogEvent.LAUNCH, "Launch ($kind) in $ms ms", mapOf("kind" to kind, "ms" to ms, "restored" to restored))
        // API-014: only launches into an event count.
        perf?.recordInEvent(if (launch.cold) PerfMetric.LAUNCH_COLD else PerfMetric.LAUNCH_WARM, PerfMetric.launchBucket(ms))
    }
}
