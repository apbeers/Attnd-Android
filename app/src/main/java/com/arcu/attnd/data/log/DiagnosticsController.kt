package com.arcu.attnd.data.log

import android.content.Context
import androidx.core.content.edit
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DebugMode
import com.arcu.attnd.domain.DebugSession
import com.arcu.attnd.domain.DiagnosticsSource
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogFilter
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogRecord
import com.arcu.attnd.domain.LogUpload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Options → About & support shows about diagnostics. */
data class DiagnosticsState(
    /** A dev build (or a local debug build): debug level and uploads, always. */
    val dev: Boolean,
    /** The running debug session (dev builds: the install's permanent one). */
    val session: DebugSession? = null,
    /** Uploads are paused (store-screenshot mode). */
    val uploadsSuspended: Boolean = false,
) {
    val debugMode: Boolean get() = dev || session != null
}

/**
 * Debug mode, levels and uploads (logging spec §2–3). Production keeps only errors (`error` and above) on
 * the device, until the user turns on debug logging for 10 minutes (a new random `D-XXXXX` code; uploads
 * start when it is turned on, nothing from before); the session's expiry is stored, so it ends on time
 * across restarts. Dev builds keep one code per install and always log `debug` and upload.
 * Diagnostics preferences are local only (excluded from backup; never linked to the device id).
 */
class DiagnosticsController(
    context: Context,
    private val dev: Boolean,
    private val store: LogStore,
    private val sink: AndroidLogSink,
    private val scope: CoroutineScope,
    source: DiagnosticsSource,
    send: suspend (gzipJson: ByteArray) -> HttpResponse,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(DiagnosticsState(dev = dev))
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()
    private var expiryJob: Job? = null

    /** The upload cursors (last uploaded seq per debug code). */
    private val cursors = object : LogUploader.Cursors {
        override fun get(code: String): Long = prefs.getLong(KEY_CURSOR + code, 0L)
        override fun set(code: String, seq: Long) = prefs.edit { putLong(KEY_CURSOR + code, seq) }
    }

    private val uploader = LogUploader(store, scope, source, cursors, send)

    /** The last upload attempt, for the log viewer. */
    val lastUploadResult: String? get() = uploader.lastResult

    /** The current debug code (`X-Diag-Session`), or null outside a session (and in screenshot mode). */
    val diagCode: String? get() = _state.value.takeIf { !it.uploadsSuspended }?.session?.code

    init {
        AppLog.allowText = dev
        val session = if (dev) {
            DebugSession.permanent(devCode())
        } else {
            DebugSession.decode(prefs.getString(KEY_SESSION, null))?.takeIf { it.isActive(now()) }
                .also { if (it == null) prefs.edit { remove(KEY_SESSION) } }
        }
        apply(session)
        if (session != null && !dev) {
            AppLog.notice(LogCategory.LOGGING, LogEvent.DEBUG_MODE, "Debug logging continues after a restart", mapOf("on" to true, "reason" to "restart"))
        }
        if (dev) {
            // Dev builds: upload once 200 records are waiting, not only every 30 s.
            var waiting = 0
            sink.onWritten = { count ->
                waiting += count
                if (waiting >= LogUpload.DEV_BATCH_RECORDS) {
                    waiting = 0
                    uploader.nudge()
                }
            }
        }
    }

    /** About & support → "Turn on debug logging for 10 minutes". */
    fun startDebugMode() {
        if (dev) return
        val session = DebugSession.start(DebugMode.newCode(), now())
        prefs.edit { putString(KEY_SESSION, session.encode()) }
        apply(session)
        AppLog.notice(LogCategory.LOGGING, LogEvent.DEBUG_MODE, "Debug logging on for 10 minutes", mapOf("on" to true, "minutes" to 10, "reason" to "user"))
    }

    /** "Turn off" in About & support, or the 10 minutes are over ([expired]). */
    fun stopDebugMode(expired: Boolean = false) {
        if (dev) return
        if (_state.value.session == null) return
        AppLog.notice(LogCategory.LOGGING, LogEvent.DEBUG_MODE, "Debug logging off", mapOf("on" to false, "reason" to if (expired) "expired" else "user"))
        prefs.edit { remove(KEY_SESSION) }
        // Let the final flush pick up the "off" line before uploads stop.
        scope.launch {
            delay(FINAL_FLUSH_MS)
            uploader.flush().join()
            if (_state.value.session == null) uploader.stop()
        }
        apply(null, stopUploads = false)
    }

    /** Store-screenshot mode: nothing is uploaded from this process. */
    fun suspendUploads() {
        uploader.stop()
        sink.diag = null
        _state.value = _state.value.copy(uploadsSuspended = true)
    }

    /** The app went to the background: send what's pending. */
    fun onBackground() {
        if (_state.value.session != null && !_state.value.uploadsSuspended) uploader.flush()
    }

    suspend fun records(): List<LogRecord> = withContext(Dispatchers.IO) { store.records() }

    /**
     * Writes the records matching [filter] (oldest first) to a `.jsonl` file in the cache directory, for
     * the share sheet. Old exports are deleted first.
     */
    suspend fun export(filter: LogFilter?): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = EXPORT_STAMP.format(Instant.now())
        val file = File(dir, "attnd-logs-$stamp.jsonl")
        val lines = store.lines()
        val chosen = if (filter == null) lines else lines.filter { line -> LogJson.decode(line)?.let(filter::matches) == true }
        store.export(file, chosen)
        AppLog.info(LogCategory.LOGGING, LogEvent.EXPORT, "Exported ${chosen.size} log lines", mapOf("entries" to chosen.size))
        file
    }

    private fun apply(session: DebugSession?, stopUploads: Boolean = true) {
        val active = session?.takeIf { it.isActive(now()) }
        AppLog.minLevel = DebugMode.level(dev, active, now())
        sink.diag = active?.code?.takeIf { !_state.value.uploadsSuspended }
        _state.value = _state.value.copy(session = active)
        expiryJob?.cancel()
        if (active != null) {
            if (!_state.value.uploadsSuspended) uploader.start(active, DebugMode.reason(dev))
            if (!dev) {
                expiryJob = scope.launch {
                    delay((active.expiresAtMs - now()).coerceAtLeast(0))
                    stopDebugMode(expired = true)
                }
            }
        } else if (stopUploads) {
            uploader.stop()
        }
    }

    private fun devCode(): String = prefs.getString(KEY_DEV_CODE, null)?.takeIf(DebugMode::isValidCode)
        ?: DebugMode.newCode().also { prefs.edit { putString(KEY_DEV_CODE, it) } }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val PREFS_NAME = "attnd_diagnostics"
        const val EXPORT_DIR = "log-exports"
        private const val KEY_SESSION = "debug_session"
        private const val KEY_DEV_CODE = "dev_debug_code"
        private const val KEY_CURSOR = "upload_cursor_"
        private const val FINAL_FLUSH_MS = 1_500L
        private val EXPORT_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)
    }
}
