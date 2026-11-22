package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Diagnostics log levels (logging spec §1; numeric 0–6, identical on iOS, Android and attnd-web).
 * [wire] is the JSON value of `level`.
 */
enum class LogLevel(val wire: String) {
    TRACE("trace"), DEBUG("debug"), INFO("info"), NOTICE("notice"), WARNING("warning"), ERROR("error"), FAULT("fault");

    val number: Int get() = ordinal

    companion object {
        fun parse(value: String?): LogLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** Log categories (spec §1): `assistant` covers the on-device model and the router. [wire] is the JSON `cat` and the logcat tag suffix. */
enum class LogCategory(val wire: String) {
    NET("net"), PUSH("push"), BACKGROUND("background"), LIVE("live"), LOCATION("location"), MAP("map"),
    PLANNER("planner"), ASSISTANT("assistant"), SEARCH("search"), STORAGE("storage"), UI("ui"), PERF("perf"),
    AUTH("auth"), LIFECYCLE("lifecycle"), LOGGING("logging");

    /** `android.util.Log` tag, e.g. `Attnd/net` (≤ 23 characters). */
    val tag: String get() = "Attnd/$wire"

    companion object {
        fun parse(value: String?): LogCategory? = entries.firstOrNull { it.wire == value }
    }
}

/** Per-process facts every record carries (spec §1: app, build, os, model, state, net, event_id, diag). */
data class LogContext(
    val app: String = "",
    val build: String = "",
    val os: String = "",
    val model: String = "",
    /** `fg` or `bg`. */
    val state: String = "bg",
    /** `wifi`, `cell` or `none` (anything else that is connected counts as `wifi`). */
    val net: String = "none",
    val eventId: Int? = null,
    /** The debug code (`D-XXXXX`) while a debug session runs (always in dev builds). */
    val diag: String? = null,
)

/** One diagnostics record (`v` = 1), stored as one JSON line in the ring buffer and uploaded as is. */
data class LogRecord(
    val ts: String,
    val monoMs: Long,
    val seq: Long,
    val level: LogLevel,
    val cat: LogCategory,
    val event: String,
    val msg: String,
    val fields: JsonObject = JsonObject(emptyMap()),
    val context: LogContext = LogContext(),
) {
    val isError: Boolean get() = level >= LogLevel.ERROR

    /** Epoch millis of [ts], or 0 when it can't be read. */
    val epochMillis: Long get() = try {
        Instant.parse(ts).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

/** The JSON line format (spec §1). Lenient: unknown keys are ignored, a line that can't be read is skipped. */
object LogJson {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /** `2026-11-15T19:04:12.381Z` (always milliseconds). */
    fun timestamp(at: Instant): String {
        val millis = at.truncatedTo(ChronoUnit.MILLIS)
        val text = TS_FORMAT.format(millis)
        // ISO_INSTANT drops a zero fraction; keep the width fixed.
        return if (millis.nano == 0) text.removeSuffix("Z") + ".000Z" else text
    }

    fun encode(record: LogRecord): String = buildJsonObject {
        put("v", VERSION)
        put("ts", record.ts)
        put("mono_ms", record.monoMs)
        put("seq", record.seq)
        put("level", record.level.wire)
        put("cat", record.cat.wire)
        put("event", record.event)
        put("msg", record.msg)
        put("f", record.fields)
        val c = record.context
        put("app", c.app)
        put("build", c.build)
        put("os", c.os)
        put("model", c.model)
        put("state", c.state)
        put("net", c.net)
        put("event_id", c.eventId?.let { JsonPrimitive(it) } ?: JsonNull)
        // LOG_EVENTS.md: `diag` is omitted outside debug sessions.
        c.diag?.let { put("diag", it) }
    }.toString()

    fun decode(line: String): LogRecord? = try {
        val o = json.parseToJsonElement(line).jsonObject
        LogRecord(
            ts = o.string("ts") ?: return null,
            monoMs = (o["mono_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            seq = (o["seq"] as? JsonPrimitive)?.longOrNull ?: 0L,
            level = LogLevel.parse(o.string("level")) ?: return null,
            cat = LogCategory.parse(o.string("cat")) ?: return null,
            event = o.string("event") ?: return null,
            msg = o.string("msg").orEmpty(),
            fields = o["f"] as? JsonObject ?: JsonObject(emptyMap()),
            context = LogContext(
                app = o.string("app").orEmpty(),
                build = o.string("build").orEmpty(),
                os = o.string("os").orEmpty(),
                model = o.string("model").orEmpty(),
                state = o.string("state") ?: "bg",
                net = o.string("net") ?: "none",
                eventId = (o["event_id"] as? JsonPrimitive)?.intOrNull,
                diag = o.string("diag"),
            ),
        )
    } catch (_: Exception) {
        null
    }

    /** A field map from call sites: strings, numbers, booleans and null; anything else as its text. */
    fun fields(values: Map<String, Any?>): JsonObject = JsonObject(values.mapValues { (_, v) -> element(v) })

    fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name.lowercase())
        else -> JsonPrimitive(value.toString())
    }

    /** One field as text for the log viewer (`status=304`). */
    fun fieldText(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.booleanOrNull?.toString() ?: "null"
        else -> value.toString()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Log viewer filters (spec §2: level, category, text, errors only). Pure, so the viewer and its tests agree. */
data class LogFilter(
    val minLevel: LogLevel = LogLevel.TRACE,
    val categories: Set<LogCategory> = emptySet(),
    val text: String = "",
    val errorsOnly: Boolean = false,
) {
    fun matches(record: LogRecord): Boolean {
        if (errorsOnly && !record.isError) return false
        if (record.level < minLevel) return false
        if (categories.isNotEmpty() && record.cat !in categories) return false
        val needle = text.trim()
        if (needle.isEmpty()) return true
        return record.event.contains(needle, ignoreCase = true) ||
            record.msg.contains(needle, ignoreCase = true) ||
            record.fields.entries.any { (k, v) -> k.contains(needle, true) || LogJson.fieldText(v).contains(needle, true) }
    }

    /** Newest first, at most [limit]. */
    fun apply(records: List<LogRecord>, limit: Int = 2_000): List<LogRecord> =
        records.asReversed().asSequence().filter(::matches).take(limit).toList()
}
