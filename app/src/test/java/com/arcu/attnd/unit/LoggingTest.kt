package com.arcu.attnd.unit

import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DebugMode
import com.arcu.attnd.domain.DebugSession
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogContext
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogFilter
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.LogRecord
import com.arcu.attnd.domain.LogSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/** Logging spec §1–3: the v1 record format, levels, the facade, the viewer filter and debug mode. */
class LoggingTest {

    private val context = LogContext(
        app = "2.0.131", build = "131000", os = "Android 16", model = "Google Pixel 9", state = "fg", net = "wifi",
        eventId = 5, diag = "D-7K3QX",
    )

    private fun record(
        level: LogLevel = LogLevel.DEBUG,
        cat: LogCategory = LogCategory.NET,
        event: String = LogEvent.REQUEST_FINISHED,
        msg: String = "GET /api/v2/events/{id}/bundle/ 304 in 182 ms",
        fields: JsonObject = LogJson.fields(mapOf("status" to 304, "ms" to 182, "cache" to true, "path" to "/api/v2/events/{id}/bundle/")),
        seq: Long = 4182,
    ) = LogRecord("2026-11-15T19:04:12.381Z", 523101, seq, level, cat, event, msg, fields, context)

    @After
    fun tearDown() {
        AppLog.sink = null
        AppLog.minLevel = DebugMode.RELEASE_LEVEL
        AppLog.allowText = false
    }

    // ── Record format ────────────────────────────────────────────────────────

    @Test
    fun encode_writesEveryV1Key() {
        val json = Json.parseToJsonElement(LogJson.encode(record())).jsonObject
        assertEquals(
            listOf("v", "ts", "mono_ms", "seq", "level", "cat", "event", "msg", "f", "app", "build", "os", "model", "state", "net", "event_id", "diag"),
            json.keys.toList(),
        )
        assertEquals(1, json["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("debug", json["level"]!!.jsonPrimitive.content)
        assertEquals("net", json["cat"]!!.jsonPrimitive.content)
        assertEquals("304", json["f"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("5", json["event_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodeDecode_roundTrips() {
        val original = record()
        assertEquals(original, LogJson.decode(LogJson.encode(original)))
    }

    @Test
    fun encode_nullEventAndDiag() {
        val json = Json.parseToJsonElement(LogJson.encode(record().copy(context = context.copy(eventId = null, diag = null)))).jsonObject
        assertEquals(JsonNull, json["event_id"])
        assertFalse("diag is omitted outside debug sessions", "diag" in json)
    }

    @Test
    fun decode_isLenient() {
        assertNull(LogJson.decode("not json"))
        assertNull(LogJson.decode("""{"v":1,"ts":"2026-11-15T19:04:12.381Z","level":"loud","cat":"net","event":"a.b","msg":""}"""))
        val minimal = LogJson.decode("""{"v":1,"ts":"2026-11-15T19:04:12.381Z","level":"error","cat":"ui","event":"ui.screen","extra":[1]}""")
        assertNotNull(minimal)
        assertEquals(LogLevel.ERROR, minimal!!.level)
        assertEquals("", minimal.msg)
        assertEquals(0L, minimal.seq)
    }

    @Test
    fun timestamp_alwaysHasMilliseconds() {
        assertEquals("2026-11-15T19:04:12.000Z", LogJson.timestamp(Instant.parse("2026-11-15T19:04:12Z")))
        assertEquals("2026-11-15T19:04:12.381Z", LogJson.timestamp(Instant.parse("2026-11-15T19:04:12.381456Z")))
    }

    @Test
    fun levels_areNumberedZeroToSix_andCategoriesMatchTheSpec() {
        assertEquals(listOf("trace", "debug", "info", "notice", "warning", "error", "fault"), LogLevel.entries.map { it.wire })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), LogLevel.entries.map { it.number })
        assertEquals(
            listOf("net", "push", "background", "live", "location", "map", "planner", "assistant", "search", "storage", "ui", "perf", "auth", "lifecycle", "logging"),
            LogCategory.entries.map { it.wire },
        )
        LogCategory.entries.forEach { assertTrue(it.tag, it.tag.length <= 23) }
    }

    @Test
    fun eventNames_areDottedStableAndUnique() {
        // attnd-web validates `^[a-z][a-z0-9_]*(\.[a-z0-9_]+){1,4}$`, ≤ 64 characters.
        val shape = Regex("""^[a-z][a-z0-9_]*(\.[a-z0-9_]+){1,4}$""")
        LogEvent.ALL.forEach { assertTrue(it, shape.matches(it) && it.length <= 64) }
        assertEquals(LogEvent.ALL.size, LogEvent.ALL.toSet().size)
        // Each name starts with its category (except error.caught, logged under the failing area, and
        // the plan.* / share.* names LOG_EVENTS.md files under planner / ui).
        val categories = LogCategory.entries.map { it.wire }.toSet() + setOf("plan", "share")
        LogEvent.ALL.filter { it != LogEvent.ERROR_CAUGHT }.forEach { assertTrue(it, it.substringBefore('.') in categories) }
    }

    @Test
    fun eventNames_includeTheIosParityNames() {
        // Phase 11: names iOS emits, all rows of attnd-web LOG_EVENTS.md (category, level, fields there).
        val expected = listOf(
            "perf.hang", "perf.frame_drops", "lifecycle.server_switch", "live.reminder.scheduled", "net.app_status",
            "search.open", "ui.banner", "map.fit", "map.pin.selected", "map.route.shown", "map.tiles.error", "location.fix",
            "storage.prefs.migrated", "storage.images.pruned", "planner.changed", "logging.ring.trimmed",
        )
        expected.forEach { assertTrue(it, it in LogEvent.ALL) }
        // Every constant declared in LogEvent is listed in ALL (reflection over the object's String constants).
        val declared = LogEvent::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        assertEquals(declared.toSet(), LogEvent.ALL.toSet())
    }

    @Test
    fun signposts_areLogEventsNames_andTheRunningOneIsReported() {
        assertEquals(
            listOf("launch", "bundle_fetch", "bundle_decode", "bundle_apply", "bundle_save", "planner", "map_first_render", "search_index"),
            LogEvent.SIGNPOSTS,
        )
        assertNull(AppLog.runningSignpost())
        val inner = AppLog.section("bundle_apply") { AppLog.runningSignpost() }
        assertEquals("bundle_apply", inner)
        val cookie = AppLog.beginAsync("bundle_fetch")
        assertEquals("bundle_fetch", AppLog.runningSignpost())
        AppLog.section("bundle_decode") { assertEquals("bundle_decode", AppLog.runningSignpost()) }
        assertEquals("bundle_fetch", AppLog.runningSignpost())
        AppLog.endAsync("bundle_fetch", cookie)
        assertNull(AppLog.runningSignpost())
    }

    // ── Facade ───────────────────────────────────────────────────────────────

    private class MemorySink : LogSink {
        val records = mutableListOf<LogRecord>()
        override fun context() = LogContext(app = "t")
        override fun monoMs() = 42L
        override fun write(record: LogRecord) {
            records += record
        }
    }

    @Test
    fun facade_dropsBelowTheLevel_andRedacts() {
        val sink = MemorySink()
        AppLog.sink = sink
        AppLog.minLevel = LogLevel.WARNING
        AppLog.debug(LogCategory.NET, LogEvent.REQUEST_STARTED, "dropped")
        AppLog.warning(
            LogCategory.PUSH, LogEvent.PUSH_TOKEN, "token for someone@example.com",
            mapOf("push_token" to "abc", "path" to "https://attnd.app/api/v2/events/12/bundle/?q=x"),
        )
        assertEquals(1, sink.records.size)
        val r = sink.records.single()
        assertEquals("token for [email]", r.msg)
        assertFalse("push_token" in r.fields)
        assertEquals(JsonPrimitive("/api/v2/events/{id}/bundle/"), r.fields["path"])
        assertEquals(42L, r.monoMs)
    }

    @Test
    fun facade_caught_recordsDomainTypeAndCode() {
        val sink = MemorySink()
        AppLog.sink = sink
        AppLog.caught(LogCategory.NET, "join", com.arcu.attnd.data.net.ApiException(503))
        val r = sink.records.single()
        assertEquals(LogEvent.ERROR_CAUGHT, r.event)
        assertEquals(LogLevel.ERROR, r.level)
        assertEquals(JsonPrimitive("join"), r.fields["where"])
        assertEquals(JsonPrimitive("ApiException"), r.fields["domain"])
        assertEquals(JsonPrimitive("http"), r.fields["type"])
        assertEquals(JsonPrimitive(503), r.fields["code"])
    }

    @Test
    fun errorKind_classifiesNetworkFailures() {
        assertEquals("timeout", AppLog.errorKind(java.net.SocketTimeoutException()))
        assertEquals("dns", AppLog.errorKind(java.net.UnknownHostException("attnd.app")))
        assertEquals("offline", AppLog.errorKind(java.net.ConnectException()))
        assertEquals("tls", AppLog.errorKind(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals("decode", AppLog.errorKind(java.io.IOException("Malformed response: x")))
        assertEquals("timeout", AppLog.errorKind(java.io.IOException("wrapped", java.net.SocketTimeoutException())))
        assertEquals("other", AppLog.errorKind(IllegalStateException()))
    }

    @Test
    fun facade_withoutSink_doesNothing() {
        AppLog.sink = null
        AppLog.minLevel = LogLevel.TRACE
        assertFalse(AppLog.isLoggable(LogLevel.FAULT))
        AppLog.fault(LogCategory.LIFECYCLE, LogEvent.ERROR_CAUGHT, "no sink: no crash")
    }

    // ── Viewer filter ────────────────────────────────────────────────────────

    @Test
    fun filter_levelCategoryTextAndErrorsOnly_newestFirst() {
        val records = listOf(
            record(seq = 1),
            record(seq = 2, level = LogLevel.ERROR, cat = LogCategory.LIVE, event = LogEvent.LIVE_START, msg = "Live Update not posted", fields = LogJson.fields(mapOf("posted" to false))),
            record(seq = 3, level = LogLevel.WARNING, cat = LogCategory.PUSH, event = LogEvent.PUSH_RECEIVED, msg = "Push content_updated", fields = LogJson.fields(mapOf("type" to "content_updated"))),
        )
        assertEquals(listOf(3L, 2L, 1L), LogFilter().apply(records).map { it.seq })
        assertEquals(listOf(2L), LogFilter(errorsOnly = true).apply(records).map { it.seq })
        assertEquals(listOf(3L, 2L), LogFilter(minLevel = LogLevel.WARNING).apply(records).map { it.seq })
        assertEquals(listOf(3L), LogFilter(categories = setOf(LogCategory.PUSH)).apply(records).map { it.seq })
        assertEquals(listOf(2L), LogFilter(text = "not POSTED").apply(records).map { it.seq })
        assertEquals("matches field values", listOf(1L), LogFilter(text = "304").apply(records).map { it.seq })
        assertEquals("and event names", listOf(3L), LogFilter(text = "push.rec").apply(records).map { it.seq })
    }

    // ── Debug mode ───────────────────────────────────────────────────────────

    @Test
    fun debugCode_isDPlusFiveCrockfordCharacters() {
        val random = Random(7)
        repeat(200) {
            val code = DebugMode.newCode(random)
            assertTrue(code, DebugMode.isValidCode(code))
            assertTrue(code, code.drop(2).none { it in "ILOU" })
        }
        assertFalse(DebugMode.isValidCode("D-7K3QI"))
        assertFalse(DebugMode.isValidCode("D-7K3Q"))
        assertFalse(DebugMode.isValidCode("X-7K3QX"))
        assertNotEquals(DebugMode.newCode(Random(1)), DebugMode.newCode(Random(2)))
    }

    @Test
    fun debugSession_tenMinutes_noPreRollAndCountdown() {
        val start = 1_000_000_000L
        val session = DebugSession.start("D-7K3QX", start)
        assertTrue(session.isActive(start))
        assertTrue(session.isActive(start + 9 * 60_000))
        assertFalse(session.isActive(start + 10 * 60_000))
        assertEquals(start, session.uploadFromMs) // nothing from before it was turned on
        assertEquals("10:00", session.remainingText(start))
        assertEquals("9:41", session.remainingText(start + 19_000))
        assertEquals("0:01", session.remainingText(start + 599_500))
        assertEquals("0:00", session.remainingText(start + 700_000))
    }

    @Test
    fun debugSession_storedAndRestored() {
        val session = DebugSession.start("D-7K3QX", 1_000L)
        assertEquals(session, DebugSession.decode(session.encode()))
        assertNull(DebugSession.decode(null))
        assertNull(DebugSession.decode("D-7K3QX|5|1"))
        assertNull(DebugSession.decode("nope|1|2"))
        assertEquals(0L, DebugSession.permanent("D-7K3QX").uploadFromMs)
    }

    @Test
    fun levels_devAndDebugSessionsAreDebug_productionErrorsOnly() {
        val session = DebugSession.start("D-7K3QX", 1_000L)
        assertEquals(LogLevel.DEBUG, DebugMode.level(dev = true, session = null, nowMs = 0))
        assertEquals(LogLevel.ERROR, DebugMode.level(dev = false, session = null, nowMs = 0))
        assertEquals(LogLevel.DEBUG, DebugMode.level(dev = false, session = session, nowMs = 2_000))
        assertEquals(LogLevel.ERROR, DebugMode.level(dev = false, session = session, nowMs = 1_000 + DebugMode.DURATION_MS))
        assertTrue(DebugMode.uploads(dev = true, session = null, nowMs = 0))
        assertFalse(DebugMode.uploads(dev = false, session = null, nowMs = 0))
        assertEquals("dev", DebugMode.reason(true))
        assertEquals("debug_mode", DebugMode.reason(false))
    }
}
