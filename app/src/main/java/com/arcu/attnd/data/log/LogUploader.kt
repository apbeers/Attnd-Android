package com.arcu.attnd.data.log

import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DebugSession
import com.arcu.attnd.domain.DiagnosticsSource
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.LogUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * API-013 uploads of the ring buffer (`POST /api/v2/diagnostics/logs/`), only in dev builds or while a
 * debug session runs ([DiagnosticsController] starts and stops it). Every ~10 s in a debug session, every
 * ~30 s or 200 records ([nudge]) in dev builds, and on [flush] it sends the records after the session's cursor that belong to it (from
 * [DebugSession.uploadFromMs]: the session's start, no pre-roll), in batches of ≤ 500, gzip-compressed.
 * `429` waits for `Retry-After`; a `413` or `400` batch is skipped; other failures back off and retry.
 * The cursor (last uploaded `seq` per debug code) is stored, so a restart neither repeats nor loses lines.
 */
class LogUploader(
    private val store: LogStore,
    private val scope: CoroutineScope,
    private val source: DiagnosticsSource,
    private val cursors: Cursors,
    private val send: suspend (gzipJson: ByteArray) -> HttpResponse,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where the last uploaded seq per debug code is kept (SharedPreferences in the app). */
    interface Cursors {
        fun get(code: String): Long
        fun set(code: String, seq: Long)
    }

    private var job: Job? = null
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var session: DebugSession? = null

    @Volatile
    private var reason: String = "debug_mode"

    @Volatile
    private var notBeforeMs = 0L

    /** The result of the last attempt, for the viewer ("Uploaded 12:04", "Server busy"). */
    @Volatile
    var lastResult: String? = null
        private set

    @Synchronized
    fun start(session: DebugSession, reason: String) {
        this.session = session
        this.reason = reason
        if (job?.isActive == true) return
        job = scope.launch {
            // The first pass waits too: launch lines arrive together, and screenshot mode can still opt out.
            var wait = interval()
            while (isActive) {
                withTimeoutOrNull(wait) { wake.receive() }
                wait = uploadOnce()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        session = null
    }

    /** Many new records (dev builds: 200): upload before the interval is up (Retry-After still applies). */
    fun nudge() {
        wake.trySend(Unit)
    }

    /** API_V2.md: debug sessions batch every ~10 s, dev builds every ~30 s (or 200 records, [nudge]). */
    private fun interval(): Long = if (reason == "dev") LogUpload.DEV_INTERVAL_MS else LogUpload.INTERVAL_MS

    /** Sends now (app backgrounded, session ending), unless a Retry-After is pending. */
    fun flush(): Job = scope.launch { uploadOnce() }

    /** One pass; returns how long to wait before the next one. Never throws except for cancellation. */
    internal suspend fun uploadOnce(): Long = mutex.withLock {
        val current = session ?: return interval()
        val now = clock()
        if (now < notBeforeMs) return notBeforeMs - now
        val lines = withContext(Dispatchers.IO) { store.lines() }
        var cursor = cursors.get(current.code)
        val maxSeq = lines.lastOrNull()?.let { LogJson.decode(it)?.seq } ?: 0
        if (maxSeq < cursor) cursor = 0 // the buffer was cleared: start over
        val pending = lines.mapNotNull { line ->
            val record = LogJson.decode(line) ?: return@mapNotNull null
            val belongs = record.seq > cursor && record.epochMillis >= current.uploadFromMs &&
                (record.context.diag == null || record.context.diag == current.code)
            if (belongs) record.seq to line else null
        }
        if (pending.isEmpty()) return interval()
        val seqOf = pending.associate { (seq, line) -> line to seq }
        for (batch in LogUpload.batches(pending.map { it.second })) {
            val compressed = gzip(LogUpload.body(current.code, reason, source, batch))
            val response = try {
                send(compressed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastResult = "Upload failed (${e.javaClass.simpleName})"
                AppLog.warning(LogCategory.LOGGING, LogEvent.UPLOAD_FAILED, "Log upload failed (${AppLog.errorKind(e)})", mapOf("error" to AppLog.errorKind(e), "entries" to batch.size))
                notBeforeMs = clock() + LogUpload.BACKOFF_MS
                return LogUpload.BACKOFF_MS
            }
            when {
                response.isSuccessful || response.code == 400 || response.code == 413 -> {
                    // Accepted, or a batch the server will never take: don't send it again.
                    seqOf[batch.last()]?.let { cursors.set(current.code, it) }
                    lastResult = if (response.isSuccessful) "Uploaded ${batch.size} lines" else "Server refused a batch (${response.code})"
                    AppLog.log(
                        if (response.isSuccessful) LogLevel.DEBUG else LogLevel.WARNING,
                        LogCategory.LOGGING, if (response.isSuccessful) LogEvent.UPLOAD else LogEvent.UPLOAD_FAILED,
                        "Log upload: ${batch.size} lines, ${response.code}",
                        if (response.isSuccessful) {
                            mapOf("entries" to batch.size, "bytes" to compressed.size, "status" to response.code)
                        } else {
                            mapOf("error" to "refused", "status" to response.code, "entries" to batch.size)
                        },
                    )
                }
                response.code == 429 || response.code == 503 -> {
                    val wait = LogUpload.retryAfterMs(response.header("Retry-After"), Instant.ofEpochMilli(clock()))
                    notBeforeMs = clock() + wait
                    lastResult = "Server busy; retrying in ${wait / 1000} s"
                    AppLog.debug(
                        LogCategory.LOGGING, LogEvent.UPLOAD, "Log upload deferred ${wait / 1000} s",
                        mapOf("entries" to batch.size, "bytes" to compressed.size, "status" to response.code, "retry_after_s" to wait / 1000),
                    )
                    return wait
                }
                else -> {
                    lastResult = "Upload failed (${response.code})"
                    AppLog.warning(LogCategory.LOGGING, LogEvent.UPLOAD_FAILED, "Log upload failed (${response.code})", mapOf("error" to "http", "status" to response.code, "entries" to batch.size))
                    notBeforeMs = clock() + LogUpload.BACKOFF_MS
                    return LogUpload.BACKOFF_MS
                }
            }
        }
        interval()
    }

    companion object {
        fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream(text.length / 6 + 64)
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }
    }
}
