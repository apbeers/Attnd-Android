package com.arcu.attnd.domain

/**
 * API-014: anonymous performance counts, sent with the usage batch (`POST events/<id>/usage/`) as metric
 * names whose detail is a bucket (logging spec §6). No per-person data: only these counts.
 * Bucket labels are shared with iOS and attnd-web; keep them identical.
 */
object PerfMetric {
    const val LAUNCH_COLD = "perf_launch_cold"
    const val LAUNCH_WARM = "perf_launch_warm"
    const val BUNDLE_FETCH = "perf_bundle_fetch"
    const val BUNDLE_304 = "perf_bundle_304"
    const val API_ERROR = "perf_api_error"
    const val PUSH_REFRESH_DELAY = "perf_push_refresh_delay"
    const val LIVE_START_FAIL = "perf_live_start_fail"
    const val PLANNER_MS = "perf_planner_ms"
    const val MAP_FIRST_RENDER = "perf_map_first_render"

    /** Upper bounds (exclusive, ms) and labels; the last label is for everything above. */
    private class Buckets(val bounds: LongArray, val labels: List<String>) {
        fun label(ms: Long): String {
            val i = bounds.indexOfFirst { ms < it }
            return if (i < 0) labels.last() else labels[i]
        }
    }

    // The labels of API_V2.md → usage → Performance, exactly (`<X` = less than X; ASCII `>=`).
    private val LAUNCH = Buckets(longArrayOf(500, 1_000, 2_000, 4_000), listOf("<0.5s", "<1s", "<2s", "<4s", ">=4s"))
    private val FETCH = Buckets(longArrayOf(250, 500, 1_000, 2_000, 5_000), listOf("<250ms", "<500ms", "<1s", "<2s", "<5s", ">=5s"))
    private val PUSH_DELAY = Buckets(longArrayOf(5_000, 10_000, 15_000, 30_000, 60_000), listOf("<5s", "<10s", "<15s", "<30s", "<60s", ">=60s"))
    private val PLANNER = Buckets(longArrayOf(100, 250, 500, 1_000, 2_000), listOf("<100ms", "<250ms", "<500ms", "<1s", "<2s", ">=2s"))
    private val MAP = Buckets(longArrayOf(250, 500, 1_000, 2_000, 4_000), listOf("<250ms", "<500ms", "<1s", "<2s", "<4s", ">=4s"))

    /** `perf_live_start_fail` details. */
    const val LIVE_DISABLED = "disabled"
    const val LIVE_DENIED = "denied"
    const val LIVE_LIMIT = "limit"
    const val LIVE_ERROR = "error"

    fun launchBucket(ms: Long): String = LAUNCH.label(ms)
    fun fetchBucket(ms: Long): String = FETCH.label(ms)
    fun pushDelayBucket(ms: Long): String = PUSH_DELAY.label(ms)
    fun plannerBucket(ms: Long): String = PLANNER.label(ms)
    fun mapBucket(ms: Long): String = MAP.label(ms)

    /** `perf_api_error` detail: `4xx`, `5xx`, `timeout` or `offline`; null for anything that isn't an error. */
    fun apiErrorDetail(status: Int?, timeout: Boolean = false, offline: Boolean = false): String? = when {
        timeout -> "timeout"
        offline -> "offline"
        status == null -> "offline"
        status == 304 -> null
        status in 400..499 -> "4xx"
        status >= 500 -> "5xx"
        else -> null
    }
}
