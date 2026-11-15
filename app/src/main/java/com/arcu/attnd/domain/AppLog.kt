package com.arcu.attnd.domain

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where [AppLog] records go: logcat + the ring buffer in the app (`data/log/AndroidLogSink`), nothing in
 * JVM unit tests (no sink installed). Also the `android.os.Trace` sections.
 */
interface LogSink {
    fun context(): LogContext
    fun monoMs(): Long
    fun write(record: LogRecord)
    fun beginSection(name: String) {}
    fun endSection() {}
    fun beginAsyncSection(name: String, cookie: Int) {}
    fun endAsyncSection(name: String, cookie: Int) {}
}

/**
 * The logging facade (logging spec §2): `AppLog.debug(LogCategory.NET, LogEvent.REQUEST_FINISHED, "GET bundle 304 in 182 ms", fields)`.
 * The level is checked first (nothing is built below [minLevel]); every message and field passes [Redact];
 * the sink writes off the main thread. Pure Kotlin, so data/api and the domain can log too.
 */
object AppLog {
    @Volatile
    var sink: LogSink? = null

    /** Records below this level are dropped (dev builds and debug mode: debug; production: warning). */
    @Volatile
    var minLevel: LogLevel = LogLevel.WARNING

    /** Dev builds may log what people typed (search, Ask, plan text); production logs lengths only. */
    @Volatile
    var allowText: Boolean = false

    private val cookies = AtomicInteger(1)

    fun isLoggable(level: LogLevel): Boolean = sink != null && level >= minLevel

    fun trace(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.TRACE, cat, event, msg, fields)
    fun debug(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.DEBUG, cat, event, msg, fields)
    fun info(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.INFO, cat, event, msg, fields)
    fun notice(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.NOTICE, cat, event, msg, fields)
    fun warning(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.WARNING, cat, event, msg, fields)
    fun error(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.ERROR, cat, event, msg, fields)
    fun fault(cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.FAULT, cat, event, msg, fields)

    /**
     * A caught error (`error.caught`, LOG_EVENTS.md): `domain` = the exception class, `code` = the HTTP status
     * when it has one, `type` = what kind of failure ([errorKind]), `where` = the code that caught it. Logged in
     * the category of that code. [level] is `warning` for expected failures (offline, a missing optional feature).
     */
    fun caught(
        cat: LogCategory,
        where: String,
        error: Throwable,
        level: LogLevel = LogLevel.ERROR,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (!isLoggable(level)) return
        val name = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
        log(
            level, cat, LogEvent.ERROR_CAUGHT, "$where: $name${error.message?.let { ": ${it.lineSequence().first().take(MAX_ERROR_MESSAGE)}" } ?: ""}",
            mapOf("domain" to name, "code" to errorCode(error), "type" to errorKind(error), "where" to where) + fields,
        )
    }

    private const val MAX_ERROR_MESSAGE = 280

    /** HTTP status of an `ApiException` (matched by name so the domain needs no data import). */
    private fun errorCode(error: Throwable): Any? =
        if (error.javaClass.simpleName == "ApiException") error.message?.removePrefix("HTTP ")?.toIntOrNull() else null

    /**
     * `timeout`, `offline`, `dns`, `tls`, `cancelled`, `decode`, `http` or `other` (the `error` values of
     * `net.request.failed`; also `type` of `error.caught`). Matched by class name: the domain has no java.net imports.
     */
    fun errorKind(error: Throwable): String {
        val names = generateSequence(error) { it.cause }.take(4).map { it.javaClass.name }.toList()
        return when {
            names.any { it.endsWith("SocketTimeoutException") || it.endsWith("TimeoutCancellationException") } -> "timeout"
            names.any { it.endsWith("UnknownHostException") } -> "dns"
            names.any { it.startsWith("javax.net.ssl.") } -> "tls"
            names.any { it.endsWith("ConnectException") || it.endsWith("NoRouteToHostException") || it.endsWith("SocketException") } -> "offline"
            names.any { it.endsWith("CancellationException") } -> "cancelled"
            names.any { it.endsWith("SerializationException") } || error.message?.startsWith("Malformed") == true -> "decode"
            error.javaClass.simpleName == "ApiException" -> "http"
            else -> "other"
        }
    }

    fun log(level: LogLevel, cat: LogCategory, event: String, msg: String, fields: Map<String, Any?> = emptyMap()) {
        val target = sink ?: return
        if (level < minLevel) return
        try {
            val record = LogRecord(
                ts = LogJson.timestamp(Instant.now()),
                monoMs = target.monoMs(),
                seq = 0, // assigned by the sink's writer, in write order
                level = level,
                cat = cat,
                event = event,
                msg = Redact.message(msg),
                fields = LogJson.fields(Redact.fields(fields)),
                context = target.context(),
            )
            target.write(record)
        } catch (_: Exception) {
            // Logging must never break the app.
        }
    }

    /**
     * A synchronous `Trace` section (the planner), also logged as `perf.signpost` with its `ms`. [name] is one
     * of LOG_EVENTS.md's signpost names: `launch`, `bundle_fetch`, `bundle_decode`, `planner`, `map_first_render`.
     */
    inline fun <T> section(name: String, block: () -> T): T {
        val target = sink
        val started = System.nanoTime()
        target?.beginSection(name)
        try {
            return block()
        } finally {
            target?.endSection()
            signpost(name, (System.nanoTime() - started) / 1_000_000)
        }
    }

    /** An async `Trace` section that may cross threads (bundle fetch), also logged as `perf.signpost`. */
    suspend fun <T> asyncSection(name: String, block: suspend () -> T): T {
        val cookie = beginAsync(name)
        try {
            return block()
        } finally {
            endAsync(name, cookie)
        }
    }

    private val asyncStarts = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun beginAsync(name: String): Int = cookies.incrementAndGet().also {
        asyncStarts[it] = System.nanoTime()
        sink?.beginAsyncSection(name, it)
    }

    /** Ends the section; returns its length in ms. */
    fun endAsync(name: String, cookie: Int): Long {
        sink?.endAsyncSection(name, cookie)
        val ms = asyncStarts.remove(cookie)?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0L
        signpost(name, ms)
        return ms
    }

    fun signpost(name: String, ms: Long) {
        debug(LogCategory.PERF, LogEvent.SIGNPOST, "$name: $ms ms", mapOf("name" to name, "ms" to ms))
    }
}
