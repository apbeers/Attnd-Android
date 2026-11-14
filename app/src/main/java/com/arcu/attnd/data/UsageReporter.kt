package com.arcu.attnd.data

import com.arcu.attnd.data.api.UsageEntryDto
import com.arcu.attnd.data.api.UsageRequest
import com.arcu.attnd.domain.UsageAggregator
import com.arcu.attnd.domain.UsageEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Anonymous usage reporting (API-005, `POST events/<id>/usage/`).
 *
 * Counts are aggregated in memory per event by (name, detail, UTC hour) and sent at most every
 * [flushIntervalMs] while there is something to send, when the app goes to the background and
 * before leaving or switching events ([flushNow]). A batch that fails is retried once on the next
 * flush, then dropped. Everything runs on [scope]; nothing ever blocks the UI or surfaces an error.
 * No device id or other identifier is ever sent.
 */
class UsageReporter(
    private val scope: CoroutineScope,
    private val sender: suspend (eventId: Int, request: UsageRequest) -> Unit,
    private val clock: () -> Instant = Instant::now,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
) {
    private class Batch(val eventId: Int, val entries: List<UsageEntryDto>)

    private val lock = Any()
    private val pending = LinkedHashMap<Int, UsageAggregator>()
    /** Batches that already failed once; dropped if they fail again. */
    private val retries = mutableListOf<Batch>()
    private var timer: Job? = null
    private val flushMutex = Mutex()

    /** Counts one [name] (with optional [detail]) for [eventId]. Cheap; safe from any thread. */
    fun track(eventId: Int, name: String, detail: String? = null) {
        synchronized(lock) {
            pending.getOrPut(eventId) { UsageAggregator() }.add(name, detail, clock())
        }
        scheduleFlush()
    }

    /** Sends everything now, in the background (app backgrounded, leaving or switching events). */
    fun flushNow(): Job = scope.launch { flush() }

    /** Sends pending counts and retries. Never throws except for cancellation. */
    suspend fun flush() {
        flushMutex.withLock {
            val (retrying, fresh) = synchronized(lock) {
                val failedOnce = retries.toList()
                retries.clear()
                val drained = pending.flatMap { (eventId, aggregator) -> batches(eventId, aggregator.drain()) }
                pending.clear()
                failedOnce to drained
            }
            retrying.forEach { send(it) } // second failure: dropped
            fresh.forEach { batch ->
                if (!send(batch)) synchronized(lock) { retries += batch }
            }
        }
        if (hasWork()) scheduleFlush()
    }

    internal fun hasWork(): Boolean = synchronized(lock) {
        retries.isNotEmpty() || pending.values.any { !it.isEmpty }
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (timer?.isActive == true) return
            timer = scope.launch {
                delay(flushIntervalMs)
                synchronized(lock) { timer = null }
                flush()
            }
        }
    }

    private suspend fun send(batch: Batch): Boolean = try {
        sender(batch.eventId, UsageRequest(events = batch.entries))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun batches(eventId: Int, entries: List<UsageEntry>): List<Batch> =
        entries
            .map { UsageEntryDto(name = it.name, detail = it.detail, count = it.count, at = it.at) }
            .chunked(MAX_ENTRIES_PER_REQUEST)
            .map { Batch(eventId, it) }

    companion object {
        const val FLUSH_INTERVAL_MS = 60_000L

        /** The server accepts up to 200 entries per request. */
        const val MAX_ENTRIES_PER_REQUEST = 200
    }
}
