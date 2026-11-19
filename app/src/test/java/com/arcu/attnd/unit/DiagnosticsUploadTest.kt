package com.arcu.attnd.unit

import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.api.ApiCall
import com.arcu.attnd.data.log.LogStore
import com.arcu.attnd.data.log.LogUploader
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.domain.DebugSession
import com.arcu.attnd.domain.DiagnosticsSource
import com.arcu.attnd.domain.ExitKind
import com.arcu.attnd.domain.ExitReport
import com.arcu.attnd.domain.ExitReports
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogContext
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.LogRecord
import com.arcu.attnd.domain.LogUpload
import com.arcu.attnd.domain.PerfMetric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.GZIPInputStream

/** API-013/014: the ring buffer, log upload batches and cursors, exit reports and the performance buckets. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsUploadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = DiagnosticsSource("2.0.131", "131000", "Android 16", "Google Pixel 9")
    private val base = Instant.parse("2026-11-15T19:00:00Z").toEpochMilli()

    private fun record(atMs: Long, diag: String? = "D-7K3QX", msg: String = "line") = LogRecord(
        ts = LogJson.timestamp(Instant.ofEpochMilli(atMs)), monoMs = 0, seq = 0, level = LogLevel.DEBUG,
        cat = LogCategory.NET, event = "net.request.finished", msg = msg, context = LogContext(diag = diag),
    )

    // ── Ring buffer ──────────────────────────────────────────────────────────

    @Test
    fun store_assignsSeqInOrder_andContinuesAfterRestart() {
        val dir = folder.newFolder("logs")
        val store = LogStore(dir)
        store.append(listOf(record(base), record(base + 1)))
        assertEquals(listOf(1L, 2L), store.records().map { it.seq })
        val reopened = LogStore(dir)
        reopened.append(listOf(record(base + 2)))
        assertEquals(listOf(1L, 2L, 3L), reopened.records().map { it.seq })
    }

    @Test
    fun store_keepsTheNewestWithinItsBudget() {
        val dir = folder.newFolder("logs")
        val store = LogStore(dir, maxBytes = 20_000)
        repeat(400) { store.append(listOf(record(base + it, msg = "x".repeat(60)))) }
        val records = store.records()
        assertEquals("newest kept", 400L, records.last().seq)
        assertTrue("older ones dropped", records.first().seq > 1)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 20_000 + 1_000)
        assertEquals(records.map { it.seq }, records.map { it.seq }.sorted())
        store.clear()
        assertTrue(store.lines().isEmpty())
    }

    // ── Batches and Retry-After ──────────────────────────────────────────────

    @Test
    fun batches_byCountAndSize() {
        val lines = (1..1_200).map { "{\"n\":$it}" }
        val batches = LogUpload.batches(lines)
        assertEquals(listOf(500, 500, 200), batches.map { it.size })
        assertEquals(lines, batches.flatten())
        val sized = LogUpload.batches(listOf("a".repeat(40), "b".repeat(40), "c".repeat(40)), maxBytes = 100)
        assertEquals(listOf(2, 1), sized.map { it.size })
        assertEquals("an oversized line is dropped", listOf(listOf("ok")), LogUpload.batches(listOf("x".repeat(200), "ok"), maxBytes = 100))
    }

    @Test
    fun body_isTheServersShape() {
        val body = Json.parseToJsonElement(LogUpload.body("D-7K3QX", "debug_mode", source, listOf(LogJson.encode(record(base))))).jsonObject
        assertEquals("D-7K3QX", body["session"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("debug_mode", body["reason"]!!.jsonPrimitive.content)
        assertEquals("2.0.131", body["app"]!!.jsonPrimitive.content)
        assertEquals(1, body["entries"]!!.jsonArray.size)
        assertEquals("net.request.finished", body["entries"]!!.jsonArray[0].jsonObject["event"]!!.jsonPrimitive.content)
    }

    @Test
    fun retryAfter_secondsOrDate_clamped() {
        val now = Instant.parse("2026-11-15T19:00:00Z")
        assertEquals(120_000L, LogUpload.retryAfterMs("120", now))
        assertEquals(30_000L, LogUpload.retryAfterMs("Sun, 15 Nov 2026 19:00:30 GMT", now))
        assertEquals(LogUpload.MAX_RETRY_AFTER_MS, LogUpload.retryAfterMs("86400", now))
        assertEquals(1_000L, LogUpload.retryAfterMs("0", now))
        assertEquals(LogUpload.BACKOFF_MS, LogUpload.retryAfterMs(null, now))
        assertEquals(LogUpload.BACKOFF_MS, LogUpload.retryAfterMs("soon", now))
    }

    // ── Uploader ─────────────────────────────────────────────────────────────

    private class MemoryCursors : LogUploader.Cursors {
        val map = mutableMapOf<String, Long>()
        override fun get(code: String) = map[code] ?: 0L
        override fun set(code: String, seq: Long) {
            map[code] = seq
        }
    }

    private fun gunzip(bytes: ByteArray) = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8)

    @Test
    fun uploader_sendsOnlyTheSession_noPreRoll_onceEach() = runTest {
        val store = LogStore(folder.newFolder("logs"))
        val start = base + 10 * 60_000
        store.append(
            listOf(
                record(start - 6 * 60_000, diag = null, msg = "too old"),
                record(start - 4 * 60_000, diag = null, msg = "before the session"),
                record(start - 60_000, diag = "D-OTHER", msg = "another session"),
                record(start + 1_000, msg = "in session"),
            )
        )
        val sent = mutableListOf<String>()
        val cursors = MemoryCursors()
        val uploader = LogUploader(store, TestScope(testScheduler), source, cursors, send = { gz ->
            sent += gunzip(gz)
            HttpResponse(200, """{"accepted":1}""", emptyMap())
        }, clock = { start + 2_000 })
        uploader.start(DebugSession("D-7K3QX", start, start + 600_000), "debug_mode")

        assertEquals(LogUpload.INTERVAL_MS, uploader.uploadOnce())
        val entries = Json.parseToJsonElement(sent.single()).jsonObject["entries"]!!.jsonArray
        assertEquals(listOf("in session"), entries.map { it.jsonObject["msg"]!!.jsonPrimitive.content })
        assertEquals(4L, cursors.get("D-7K3QX"))

        uploader.uploadOnce()
        assertEquals("nothing new: nothing sent", 1, sent.size)
        uploader.stop()
    }

    @Test
    fun uploader_honoursRetryAfter_andKeepsTheCursor() = runTest {
        val store = LogStore(folder.newFolder("logs"))
        store.append(listOf(record(base + 1_000)))
        var now = base + 2_000
        var calls = 0
        val cursors = MemoryCursors()
        val uploader = LogUploader(store, TestScope(testScheduler), source, cursors, send = {
            calls++
            HttpResponse(429, null, mapOf("Retry-After" to listOf("90")))
        }, clock = { now })
        uploader.start(DebugSession("D-7K3QX", base, base + 600_000), "debug_mode")

        assertEquals(90_000L, uploader.uploadOnce())
        assertEquals(0L, cursors.get("D-7K3QX"))
        now += 30_000
        assertEquals("still waiting: no request", 60_000L, uploader.uploadOnce())
        assertEquals(1, calls)
        uploader.stop()
    }

    @Test
    fun uploader_withoutSession_sendsNothing() = runTest {
        val store = LogStore(folder.newFolder("logs"))
        store.append(listOf(record(base)))
        var calls = 0
        val uploader = LogUploader(store, TestScope(testScheduler), source, MemoryCursors(), send = {
            calls++
            HttpResponse(200, null, emptyMap())
        })
        uploader.uploadOnce()
        assertEquals(0, calls)
    }

    // ── Exit reports ─────────────────────────────────────────────────────────

    private val anrTrace = "----- pid 123 at 2026-11-15 -----\n\"main\" prio=5 tid=1 Blocked\n  at com.arcu.attnd.a.b(SourceFile:12)"
    private val crashStack = "java.lang.IllegalStateException: boom for a@b.cc\n\tat com.arcu.attnd.a.b(SourceFile:12)"

    @Test
    fun exitReports_newSinceAndShape() {
        val crash = ExitReport(ExitKind.CRASH, base + 2, "crash", null, true, 120_000, 180_000, ExitReports.trimStack(crashStack), "java.lang.IllegalStateException", "boom for a@b.cc")
        val anr = ExitReport(ExitKind.ANR, base + 1, "anr", "Input dispatching timed out", true, null, null, ExitReports.trimStack(anrTrace))
        val old = ExitReport(ExitKind.LOW_MEMORY, base - 1, "low_memory", null, false, null, null, null)
        assertEquals(listOf(anr, crash), ExitReports.newSince(listOf(crash, old, anr), base))

        val body = Json.parseToJsonElement(ExitReports.body(source, listOf(anr, crash))).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        val reports = body["reports"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("anr", "crash"), reports.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("java.lang.IllegalStateException: boom for [email]", reports[1]["summary"]!!.jsonPrimitive.content)
        assertEquals("java.lang.IllegalStateException", reports[1]["exception"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(reports[1]["stack"]!!.jsonPrimitive.content.contains("[email]"))
        assertEquals("Input dispatching timed out", reports[0]["summary"]!!.jsonPrimitive.content)
        assertTrue(reports[0]["occurred_at"]!!.jsonPrimitive.content.endsWith("Z"))
        assertFalse("no device id anywhere", body.toString().contains("device"))
    }

    @Test
    fun exitReports_exceptionFromTheFirstLine_andTrim() {
        assertEquals("java.lang.IllegalStateException" to "boom for a@b.cc", ExitReports.exception(crashStack))
        assertEquals("kotlin.KotlinNullPointerException" to null, ExitReports.exception("kotlin.KotlinNullPointerException\n\tat x"))
        assertNull(ExitReports.exception(anrTrace))
        assertNull(ExitReports.exception(null))
        assertNull(ExitReports.trimStack("  "))
        assertTrue(ExitReports.trimStack("x\n".repeat(100_000))!!.endsWith("(truncated)"))
    }

    // ── Performance counts ───────────────────────────────────────────────────

    @Test
    fun perfBuckets_matchApiV2Exactly() {
        // API-014 edges: 249/250/499/500 ms, 0.5 s, 4 s, 60 s; `<X` = less than X.
        assertEquals("<0.5s", PerfMetric.launchBucket(499))
        assertEquals("<1s", PerfMetric.launchBucket(500))
        assertEquals("<4s", PerfMetric.launchBucket(3_999))
        assertEquals(">=4s", PerfMetric.launchBucket(4_000))
        assertEquals("<250ms", PerfMetric.fetchBucket(249))
        assertEquals("<500ms", PerfMetric.fetchBucket(250))
        assertEquals("<500ms", PerfMetric.fetchBucket(499))
        assertEquals("<1s", PerfMetric.fetchBucket(500))
        assertEquals("<5s", PerfMetric.fetchBucket(4_999))
        assertEquals(">=5s", PerfMetric.fetchBucket(5_000))
        assertEquals("<5s", PerfMetric.pushDelayBucket(4_999))
        assertEquals("<10s", PerfMetric.pushDelayBucket(9_000))
        assertEquals("<15s", PerfMetric.pushDelayBucket(10_000))
        assertEquals("<60s", PerfMetric.pushDelayBucket(59_999))
        assertEquals(">=60s", PerfMetric.pushDelayBucket(60_000))
        assertEquals("<100ms", PerfMetric.plannerBucket(99))
        assertEquals("<2s", PerfMetric.plannerBucket(1_999))
        assertEquals(">=2s", PerfMetric.plannerBucket(2_000))
        assertEquals("<250ms", PerfMetric.mapBucket(249))
        assertEquals("<500ms", PerfMetric.mapBucket(250))
        assertEquals("<2s", PerfMetric.mapBucket(1_500))
        assertEquals(">=4s", PerfMetric.mapBucket(4_000))
        assertNull(PerfMetric.apiErrorDetail(304))
        assertEquals("4xx", PerfMetric.apiErrorDetail(429))
        assertEquals("5xx", PerfMetric.apiErrorDetail(503))
        assertEquals("timeout", PerfMetric.apiErrorDetail(null, timeout = true))
        assertEquals("offline", PerfMetric.apiErrorDetail(null))
        assertNull(PerfMetric.apiErrorDetail(304))
    }

    @Test
    fun perfRecorder_bundleTimingsAndErrors_pendingUntilAnEvent() = runTest {
        val sent = mutableListOf<Pair<Int, List<String>>>()
        val usage = UsageReporter(TestScope(testScheduler), sender = { id, request ->
            sent += id to request.events.map { "${it.name}:${it.detail}:${it.count}" }
        })
        var eventId: Int? = null
        val perf = PerfRecorder(usage, eventId = { eventId })

        perf.record(PerfMetric.LAUNCH_COLD, "<1s") // no event yet: waits
        eventId = 5
        perf.onApiCall(ApiCall("GET", "/api/v2/events/{id}/bundle/", 304, 120, 0), offline = false)
        perf.onApiCall(ApiCall("GET", "/api/v2/events/{id}/bundle/", 200, 700, 9_000), offline = false)
        perf.onApiCall(ApiCall("POST", "/api/v2/events/{id}/join/", 503, 30, 0), offline = false)
        perf.onApiCall(ApiCall("GET", "/api/v2/events/lookup/", 404, 30, 0), offline = false)
        perf.onApiCall(ApiCall("POST", "/api/v2/events/{id}/usage/", 500, 30, 0), offline = false)
        perf.onApiCall(ApiCall("POST", "/api/v2/diagnostics/logs/", 429, 30, 0), offline = false)
        perf.onApiCall(ApiCall("GET", "/api/v2/events/{id}/bundle/", null, 15_000, 0, timedOut = true), offline = false)
        perf.onEvent(5)
        usage.flush()

        val names = sent.flatMap { it.second }.toSet()
        assertEquals(
            setOf(
                "perf_bundle_304:<250ms:1", "perf_bundle_fetch:<1s:1", "perf_api_error:5xx:2", "perf_api_error:4xx:1",
                "perf_api_error:timeout:1", "perf_launch_cold:<1s:1",
            ),
            names,
        )
        assertTrue(sent.all { it.first == 5 })

        perf.suspended = true
        perf.record(PerfMetric.MAP_FIRST_RENDER, "<1s")
        usage.flush()
        assertFalse(sent.flatMap { it.second }.any { it.startsWith("perf_map") })
    }
}
