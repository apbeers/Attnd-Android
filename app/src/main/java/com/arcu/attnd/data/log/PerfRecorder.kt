package com.arcu.attnd.data.log

import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.api.ApiCall
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.PerfMetric
import java.time.Instant

/**
 * API-014: anonymous performance counts (launch, bundle fetch, API errors, push refresh delay, Live Update
 * failures, planner, map first render, app exits) go into the usage batch of the joined event. A count
 * recorded outside an event (a cold launch before the saved event is known, a crash report) waits for
 * the next event ([onEvent]); at most [MAX_PENDING] wait, older ones are dropped. Never in screenshot mode.
 */
class PerfRecorder(
    private val usage: UsageReporter,
    private val eventId: () -> Int?,
    private val clock: () -> Instant = Instant::now,
) {
    private class Pending(val name: String, val detail: String?, val at: Instant)

    private val pending = ArrayDeque<Pending>()

    /** Store-screenshot mode: nothing is counted. */
    @Volatile
    var suspended: Boolean = false

    fun record(name: String, detail: String?) {
        if (suspended) return
        AppLog.debug(LogCategory.PERF, LogEvent.PERF_METRIC, "$name $detail", mapOf("name" to name, "bucket" to detail))
        val id = eventId()
        if (id != null) {
            usage.track(id, name, detail)
            return
        }
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(Pending(name, detail, clock()))
        }
    }

    /** Counts [name] only while in an event, never later (launches to the launch screen aren't counted). */
    fun recordInEvent(name: String, detail: String?) {
        if (eventId() != null) record(name, detail)
    }

    /** The device is in [id] now: counts waiting for an event go to it. */
    fun onEvent(id: Int?) {
        if (id == null || suspended) return
        val waiting = synchronized(pending) { pending.toList().also { pending.clear() } }
        waiting.forEach { usage.track(id, it.name, it.detail, it.at) }
    }

    /** From [com.arcu.attnd.data.api.AttndApi.callListener]: bundle timings and API errors. */
    fun onApiCall(call: ApiCall, offline: Boolean) {
        if (call.isDiagnostics) return
        when {
            call.isBundle && call.status == 304 -> record(PerfMetric.BUNDLE_304, PerfMetric.fetchBucket(call.ms))
            call.isBundle && call.status in 200..299 -> record(PerfMetric.BUNDLE_FETCH, PerfMetric.fetchBucket(call.ms))
        }
        PerfMetric.apiErrorDetail(call.status, call.timedOut, offline && call.status == null)
            ?.let { record(PerfMetric.API_ERROR, it) }
    }

    companion object {
        const val MAX_PENDING = 50
    }
}
