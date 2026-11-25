package com.arcu.attnd.data.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import androidx.core.content.edit
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DiagnosticsSource
import com.arcu.attnd.domain.ExitKind
import com.arcu.attnd.domain.ExitReport
import com.arcu.attnd.domain.ExitReports
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash, ANR, low-memory and other exits, reported anonymously at the next launch (API-013,
 * `POST /api/v2/diagnostics/reports/`; no device id: app version, `build` = versionCode, OS, model):
 * `ActivityManager.getHistoricalProcessExitReasons` newer than the last one handled. `REASON_CRASH` /
 * `REASON_CRASH_NATIVE` → `crash`, `REASON_ANR` → `anr` (the system's thread dump from
 * `getTraceInputStream` as `stack`), `REASON_LOW_MEMORY` → `low_memory`, other system exits → `exit`
 * (exits the user or the app asked for, and app updates, are skipped). Java crashes carry the stack our
 * [installCrashHandler] saved to `filesDir/logs/crash.txt` (ApplicationExitInfo has none for them); it is
 * sent alone when no exit info matches. Release stacks are obfuscated: the server retraces them with the
 * build's R8 mapping. Unsent reports are retried at the next launch and dropped after 7 days.
 */
class ExitReporter(
    context: Context,
    private val scope: CoroutineScope,
    private val source: DiagnosticsSource,
    private val send: suspend (gzipJson: ByteArray) -> HttpResponse,
    private val diag: () -> String?,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(DiagnosticsController.PREFS_NAME, Context.MODE_PRIVATE)
    private val crashFile = File(appContext.filesDir, "logs/crash.txt")

    /**
     * Saves the obfuscated stack of an uncaught exception (with this process's pid and the time) and writes
     * the ring buffer, then lets the previous handler (the system's crash dialog / kill) run.
     */
    fun installCrashHandler(sink: AndroidLogSink) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                crashFile.parentFile?.mkdirs()
                crashFile.writeText("${Process.myPid()}|${System.currentTimeMillis()}|${diag().orEmpty()}\n$stack")
                AppLog.fault(
                    LogCategory.LIFECYCLE, LogEvent.ERROR_CAUGHT, "Uncaught ${error.javaClass.name} on ${thread.name}",
                    mapOf("domain" to error.javaClass.simpleName, "code" to null, "type" to "uncaught", "where" to "thread:${thread.name}"),
                )
                sink.flushBlocking()
            } catch (_: Throwable) {
                // Never get in the way of the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A few seconds after launch (off the start-up path): finds new exits and reports them. */
    fun reportPendingLater() {
        scope.launch(Dispatchers.IO) {
            delay(START_DELAY_MS)
            try {
                report()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.LOGGING, "exit_reports", e, LogLevel.WARNING)
            }
        }
    }

    private suspend fun report() {
        val activity = appContext.getSystemService(ActivityManager::class.java) ?: return
        val last = prefs.getLong(KEY_REPORTED_AT, 0L)
        val crash = readCrashFile()
        val infos = activity.getHistoricalProcessExitReasons(appContext.packageName, 0, MAX_EXITS)
            .filter { it.timestamp > last && it.pid != Process.myPid() }
        infos.forEach { info ->
            AppLog.notice(
                LogCategory.LIFECYCLE, LogEvent.EXIT_REASON, "Previous run ended: ${reasonName(info.reason)}",
                mapOf("reason" to reasonName(info.reason), "status" to info.status, "importance" to info.importance),
            )
        }
        val exits = infos.mapNotNull { toReport(it, crash) }.toMutableList()
        // A saved crash no exit info matched (e.g. the system had no record of it): sent on its own.
        if (crash != null && crash.atMs > last && exits.none { it.kind == ExitKind.CRASH && it.stack == ExitReports.trimStack(crash.stack) }) {
            exits += crashReport(crash, crash.atMs, "CRASH", foreground = true, pss = null, rss = null, description = null)
        }
        val fresh = ExitReports.newSince(exits, last, System.currentTimeMillis())
        if (fresh.isEmpty()) {
            if (crash != null && crash.atMs <= last) crashFile.delete()
            return
        }
        val response = send(LogUploader.gzip(ExitReports.body(source, fresh)))
        fresh.groupBy { it.kind }.forEach { (kind, list) ->
            AppLog.log(
                if (response.isSuccessful) LogLevel.INFO else LogLevel.WARNING,
                LogCategory.LOGGING, LogEvent.REPORT_SENT, "${list.size} ${kind.wire} report(s): ${response.code}",
                mapOf("kind" to kind.wire, "count" to list.size, "status" to response.code),
            )
        }
        if (response.isSuccessful || response.code == 400 || response.code == 413) {
            prefs.edit { putLong(KEY_REPORTED_AT, fresh.maxOf { it.occurredAtMs }) }
            crashFile.delete()
        }
    }

    private class SavedCrash(val pid: Int, val atMs: Long, val diag: String?, val stack: String)

    private fun readCrashFile(): SavedCrash? = try {
        if (!crashFile.exists()) {
            null
        } else {
            val text = crashFile.readText()
            val header = text.substringBefore('\n').split('|')
            val pid = header.getOrNull(0)?.toIntOrNull()
            val at = header.getOrNull(1)?.toLongOrNull()
            if (pid == null || at == null) null else SavedCrash(pid, at, header.getOrNull(2)?.takeIf { it.isNotEmpty() }, text.substringAfter('\n'))
        }
    } catch (_: IOException) {
        null
    }

    private fun crashReport(
        crash: SavedCrash?,
        atMs: Long,
        reason: String,
        foreground: Boolean,
        pss: Long?,
        rss: Long?,
        description: String?,
    ): ExitReport {
        val exception = ExitReports.exception(crash?.stack)
        return ExitReport(
            kind = ExitKind.CRASH, occurredAtMs = atMs, reason = reason, description = description, foreground = foreground,
            pssKb = pss, rssKb = rss, stack = ExitReports.trimStack(crash?.stack),
            exceptionType = exception?.first, exceptionMessage = exception?.second, diag = crash?.diag,
        )
    }

    private fun toReport(info: ApplicationExitInfo, crash: SavedCrash?): ExitReport? {
        val foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val pss = info.pss.takeIf { it > 0 }
        val rss = info.rss.takeIf { it > 0 }
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.CRASH
            ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
            ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
            in SKIPPED_REASONS -> return null
            else -> ExitKind.EXIT
        }
        if (info.reason == ApplicationExitInfo.REASON_CRASH) {
            return crashReport(crash?.takeIf { it.pid == info.pid }, info.timestamp, reasonName(info.reason), foreground, pss, rss, info.description)
        }
        return ExitReport(
            kind = kind,
            occurredAtMs = info.timestamp,
            reason = reasonName(info.reason),
            description = info.description,
            foreground = foreground,
            pssKb = pss,
            rssKb = rss,
            // ANR: the thread dump; native crashes: their tombstone is a binary protobuf, not sent.
            stack = if (info.reason == ApplicationExitInfo.REASON_ANR) ExitReports.trimStack(readTrace(info)) else null,
        )
    }

    private fun readTrace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream -> stream.readNBytes(ExitReports.MAX_STACK + 1).toString(Charsets.UTF_8) }
    } catch (_: IOException) {
        null
    }

    /** `ApplicationExitInfo.REASON_*` without the prefix (`CRASH`, `ANR`, `LOW_MEMORY`, …). */
    private fun reasonName(reason: Int): String = REASON_NAMES[reason] ?: "REASON_$reason"

    private companion object {
        const val KEY_REPORTED_AT = "exit_reported_at"
        const val MAX_EXITS = 10
        const val START_DELAY_MS = 8_000L

        /** Exits nobody needs to look at: asked for by the user or the app, or an app update. */
        val SKIPPED_REASONS = setOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        )

        val REASON_NAMES = mapOf(
            ApplicationExitInfo.REASON_UNKNOWN to "UNKNOWN",
            ApplicationExitInfo.REASON_EXIT_SELF to "EXIT_SELF",
            ApplicationExitInfo.REASON_SIGNALED to "SIGNALED",
            ApplicationExitInfo.REASON_LOW_MEMORY to "LOW_MEMORY",
            ApplicationExitInfo.REASON_CRASH to "CRASH",
            ApplicationExitInfo.REASON_CRASH_NATIVE to "CRASH_NATIVE",
            ApplicationExitInfo.REASON_ANR to "ANR",
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "INITIALIZATION_FAILURE",
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to "PERMISSION_CHANGE",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE_USAGE",
            ApplicationExitInfo.REASON_USER_REQUESTED to "USER_REQUESTED",
            ApplicationExitInfo.REASON_USER_STOPPED to "USER_STOPPED",
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to "DEPENDENCY_DIED",
            ApplicationExitInfo.REASON_OTHER to "OTHER",
            ApplicationExitInfo.REASON_FREEZER to "FREEZER",
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE",
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to "PACKAGE_UPDATED",
        )
    }
}
