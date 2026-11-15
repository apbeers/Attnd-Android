package com.arcu.attnd.data.log

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogContext
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.LogRecord
import com.arcu.attnd.domain.LogSink
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's [LogSink] (logging spec §2): `android.util.Log` with the tag `Attnd/<cat>`, plus the ring
 * buffer ([LogStore]) written by one background thread, so callers never touch the disk. Also tracks
 * what every record carries: foreground/background (started activities), the network (`wifi` / `cell` /
 * `none` / `other`), the joined event and the debug code; and maps `Trace` sections.
 */
class AndroidLogSink(
    application: Application,
    private val store: LogStore,
    private val app: String,
    private val build: String,
    private val eventId: () -> Int?,
) : LogSink {

    private val os = "Android ${Build.VERSION.RELEASE}"
    private val model = "${Build.MANUFACTURER} ${Build.MODEL}".take(48)

    @Volatile
    var diag: String? = null

    @Volatile
    private var net: String = "none"

    @Volatile
    private var startedActivities = 0

    /** How many records were just written, for the uploader's wake-up (it reads the store itself). */
    @Volatile
    var onWritten: ((Int) -> Unit)? = null

    /** Records dropped because the writer fell behind (reported as `logging.dropped`). */
    private val dropped = java.util.concurrent.atomic.AtomicInteger(0)

    private val queue = LinkedBlockingQueue<LogRecord>(QUEUE_CAPACITY)
    private val writer = Thread({ writeLoop() }, "attnd-log-writer").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    val isForeground: Boolean get() = startedActivities > 0

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        try {
            application.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val now = when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                            else -> "other"
                        }
                        if (now != net) {
                            net = now
                            AppLog.info(
                                LogCategory.NET, LogEvent.REACHABILITY, "Network: $now",
                                mapOf("net" to now, "expensive" to !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)),
                            )
                        }
                    }

                    override fun onLost(network: Network) {
                        net = "none"
                        AppLog.info(LogCategory.NET, LogEvent.REACHABILITY, "Network: none", mapOf("net" to "none"))
                    }
                }
            )
        } catch (_: RuntimeException) {
            // No network state (permission or service missing): records say "none".
        }
        writer.start()
    }

    /** Offline according to the system (for `perf_api_error` = offline). */
    val isOffline: Boolean get() = net == "none"

    override fun context(): LogContext = LogContext(
        app = app,
        build = build,
        os = os,
        model = model,
        state = if (isForeground) "fg" else "bg",
        net = net,
        eventId = eventId(),
        diag = diag,
    )

    override fun monoMs(): Long = SystemClock.elapsedRealtime()

    override fun write(record: LogRecord) {
        Log.println(priority(record.level), record.cat.tag, "${record.event} ${record.msg}")
        // Never block the caller: when the writer falls far behind, newer records are dropped.
        if (!queue.offer(record)) dropped.incrementAndGet()
    }

    /** Writes everything queued now, on the calling thread (the crash handler, before the process dies). */
    fun flushBlocking() {
        val pending = ArrayList<LogRecord>()
        queue.drainTo(pending)
        store.append(pending)
    }

    private fun writeLoop() {
        val batch = ArrayList<LogRecord>(64)
        while (true) {
            try {
                val first = queue.poll(30, TimeUnit.SECONDS) ?: continue
                batch += first
                queue.drainTo(batch, 499)
                store.append(batch)
                val written = batch.size
                batch.clear()
                onWritten?.invoke(written)
                val lost = dropped.getAndSet(0)
                if (lost > 0) {
                    AppLog.warning(LogCategory.LOGGING, LogEvent.DROPPED, "$lost log records dropped", mapOf("count" to lost, "reason" to "rate"))
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                batch.clear()
            }
        }
    }

    // A pass-through: AppLog.section pairs begin and end in a try/finally.
    @SuppressLint("UnclosedTrace")
    override fun beginSection(name: String) = Trace.beginSection(name.take(MAX_SECTION))

    override fun endSection() = Trace.endSection()

    override fun beginAsyncSection(name: String, cookie: Int) = Trace.beginAsyncSection(name.take(MAX_SECTION), cookie)

    override fun endAsyncSection(name: String, cookie: Int) = Trace.endAsyncSection(name.take(MAX_SECTION), cookie)

    private fun priority(level: LogLevel): Int = when (level) {
        LogLevel.TRACE -> Log.VERBOSE
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO, LogLevel.NOTICE -> Log.INFO
        LogLevel.WARNING -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
        LogLevel.FAULT -> Log.ASSERT
    }

    companion object {
        private const val QUEUE_CAPACITY = 5_000
        private const val MAX_SECTION = 127

        /** The installed sink, if [AppLog] has one of this type. */
        val installed: AndroidLogSink? get() = AppLog.sink as? AndroidLogSink
    }
}
