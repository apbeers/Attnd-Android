package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Who is uploading: the same on every batch and report (spec §5; no device id). */
data class DiagnosticsSource(val app: String, val build: String, val os: String, val model: String)

/**
 * API-013 log uploads (`POST /api/v2/diagnostics/logs/`): batches of at most [MAX_ENTRIES] records whose
 * JSON stays under [MAX_RAW_BYTES] (the server takes ≤ 256 KB as sent and 2 MB decompressed; gzip shrinks
 * log lines about 8×, so 1 MB raw stays well below both), and the Retry-After rules.
 */
object LogUpload {
    const val MAX_ENTRIES = 500
    const val MAX_RAW_BYTES = 1_000_000

    /** Between uploads in a production debug session (API_V2.md: every ~10 s). */
    const val INTERVAL_MS = 10_000L

    /** Dev builds: every ~30 s, or sooner once [DEV_BATCH_RECORDS] are waiting. */
    const val DEV_INTERVAL_MS = 30_000L
    const val DEV_BATCH_RECORDS = 200

    /** After a failure without Retry-After. */
    const val BACKOFF_MS = 60_000L

    /** Retry-After is honoured up to this long. */
    const val MAX_RETRY_AFTER_MS = 60 * 60 * 1000L

    /** Splits JSON [lines] (in order) into batches by count and size; a single oversized line is dropped. */
    fun batches(lines: List<String>, maxEntries: Int = MAX_ENTRIES, maxBytes: Int = MAX_RAW_BYTES): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var bytes = 0
        for (line in lines) {
            val size = line.length + 1
            if (size > maxBytes) continue
            if (current.size >= maxEntries || bytes + size > maxBytes) {
                if (current.isNotEmpty()) out += current
                current = mutableListOf()
                bytes = 0
            }
            current += line
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** The request body; [lines] are records already encoded by [LogJson]. */
    fun body(session: String, reason: String, source: DiagnosticsSource, lines: List<String>): String = buildString {
        append('{')
        append("\"session\":").append(JsonPrimitive(session))
        append(",\"platform\":\"android\"")
        append(",\"reason\":").append(JsonPrimitive(reason))
        append(",\"app\":").append(JsonPrimitive(source.app))
        append(",\"build\":").append(JsonPrimitive(source.build))
        append(",\"os\":").append(JsonPrimitive(source.os))
        append(",\"model\":").append(JsonPrimitive(source.model))
        append(",\"entries\":[")
        lines.forEachIndexed { i, line ->
            if (i > 0) append(',')
            append(line)
        }
        append("]}")
    }

    /** `Retry-After` as seconds or an HTTP date → milliseconds to wait (1 s … 1 h), or [fallbackMs]. */
    fun retryAfterMs(header: String?, now: Instant, fallbackMs: Long = BACKOFF_MS): Long {
        val value = header?.trim().orEmpty()
        val ms = value.toLongOrNull()?.times(1000)
            ?: try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now.toEpochMilli()
            } catch (_: Exception) {
                null
            }
            ?: return fallbackMs
        return ms.coerceIn(1_000, MAX_RETRY_AFTER_MS)
    }
}

/** What kind of exit `ApplicationExitInfo` reported, as the server's report `kind`. */
enum class ExitKind(val wire: String) { CRASH("crash"), ANR("anr"), LOW_MEMORY("low_memory"), EXIT("exit") }

/**
 * One anonymous crash / ANR / low-memory exit found at the next launch (`POST /api/v2/diagnostics/reports/`,
 * spec §6). [stack] is the Java stack saved by the crash handler or the ANR thread dump, still obfuscated
 * in release builds (the server retraces it with the build's mapping); redacted like log text.
 */
data class ExitReport(
    val kind: ExitKind,
    val occurredAtMs: Long,
    val reason: String,
    val description: String?,
    val foreground: Boolean,
    val pssKb: Long?,
    val rssKb: Long?,
    val stack: String?,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val diag: String? = null,
)

object ExitReports {
    const val MAX_STACK = 120 * 1024
    const val MAX_PER_REQUEST = 20

    /** Reports older than this are not sent any more (API_V2.md: drop them after 7 days). */
    const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_EXCEPTION_MESSAGE = 280
    private const val MAX_LINE = 1_000

    /** ANR traces start with every thread; keep the start (the main thread comes first). */
    fun trimStack(text: String?): String? = text?.takeIf { it.isNotBlank() }?.let {
        val cut = if (it.length > MAX_STACK) it.take(MAX_STACK) + "\n… (truncated)" else it
        cut.lineSequence().joinToString("\n") { line -> Redact.message(line.take(MAX_LINE)) }
    }

    /** Exits newer than [lastReportedMs] and at most 7 days old, oldest first, at most [MAX_PER_REQUEST] (the newest). */
    fun newSince(reports: List<ExitReport>, lastReportedMs: Long, nowMs: Long = Long.MAX_VALUE): List<ExitReport> =
        reports.filter { it.occurredAtMs > lastReportedMs && (nowMs == Long.MAX_VALUE || nowMs - it.occurredAtMs <= MAX_AGE_MS) }
            .sortedBy { it.occurredAtMs }.takeLast(MAX_PER_REQUEST)

    /** "java.lang.IllegalStateException: boom" from the first line of a Java stack. */
    fun exception(stack: String?): Pair<String, String?>? {
        val first = stack?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (first.startsWith("at ") || first.startsWith("\"") || first.startsWith("-----")) return null
        val type = first.substringBefore(':').trim()
        if (type.isEmpty() || type.contains(' ')) return null
        val message = first.substringAfter(':', "").trim().take(MAX_EXCEPTION_MESSAGE).takeIf { it.isNotEmpty() }
        return type to message
    }

    fun body(source: DiagnosticsSource, reports: List<ExitReport>): String = buildJsonObject {
        put("platform", "android")
        put("app", source.app)
        put("build", source.build)
        put("os", source.os)
        put("model", source.model)
        put("reports", JsonArray(reports.map { report(it) }))
    }.toString()

    private fun report(r: ExitReport): JsonObject = buildJsonObject {
        put("kind", r.kind.wire)
        put("occurred_at", LogJson.timestamp(Instant.ofEpochMilli(r.occurredAtMs)))
        put("reason", r.reason)
        val summary = listOfNotNull(r.exceptionType, r.exceptionMessage?.let(Redact::message)).joinToString(": ")
            .ifEmpty { r.description?.let(Redact::message) ?: r.reason }
        put("summary", summary.take(500))
        if (r.exceptionType != null) {
            put("exception", buildJsonObject {
                put("type", r.exceptionType)
                r.exceptionMessage?.let { put("message", Redact.message(it)) }
            })
        }
        r.stack?.let { put("stack", it) }
        put("payload", buildJsonObject {
            put("foreground", r.foreground)
            r.description?.let { put("description", Redact.message(it)) }
            r.pssKb?.let { put("pss_kb", it) }
            r.rssKb?.let { put("rss_kb", it) }
        })
        r.diag?.let { put("diag", it) }
    }
}
