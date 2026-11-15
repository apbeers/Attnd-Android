package com.arcu.attnd.live

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.hasLocationPermission
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveFollow
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.PerfMetric
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.UrgentBlock
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * Drives the Live Update, reminders and the venue geofence from the device, with no server
 * (shared spec §8): [refresh] recomputes [LiveFollow.state] from the saved bundle, plan and hearts,
 * posts or cancels the notification, shows due reminders and schedules the next wake-up as an inexact
 * [AlarmManager.setWindow] alarm (no exact-alarm permission). It runs after every bundle / hearts / plan
 * change (AppGraph), on each alarm, on a `content_updated` push (the bundle changes), on boot, on the
 * notification actions and when the geofence reports an arrival.
 *
 * Only while "Follow on Lock Screen" is on for the joined event ([PlanStore.follows]); never in
 * screenshot mode ([suspended]).
 */
class LiveUpdateController(
    context: Context,
    private val repository: EventRepository,
    private val hearts: HeartStore,
    private val plans: PlanStore,
    private val usage: UsageReporter,
    private val scope: CoroutineScope,
    private val perf: PerfRecorder? = null,
) {
    private val appContext = context.applicationContext

    /** Whether this process has the Live Update posted (for live.start / live.end). */
    @Volatile
    private var liveShown = false

    /** A failed start was counted (API-014 `perf_live_start_fail`) and nothing was posted since. */
    @Volatile
    private var failCounted = false

    private val mutex = Mutex()
    private val geofence = VenueGeofence(appContext)

    /** Store-screenshot mode: nothing is posted or scheduled. */
    @Volatile
    var suspended: Boolean = false

    fun requestRefresh() {
        scope.launch { refresh() }
    }

    suspend fun refresh(now: Instant = Instant.now()) = mutex.withLock {
        try {
            update(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.caught(LogCategory.LIVE, "live.refresh", e)
        }
    }

    private suspend fun update(now: Instant) {
        val bundle = repository.restore()
        if (bundle == null || suspended) {
            stop(clearGeofence = bundle == null, reason = if (bundle == null) "left_event" else "system")
            return
        }
        val eventId = bundle.event.id
        val heartsNow = hearts.hearts(eventId)
        val plan = plans.plan(eventId)
        if (!plans.follows(eventId, heartsNow)) {
            stop(clearGeofence = true, reason = "unfollowed")
            return
        }
        val zone = bundle.event.zone
        val today = now.atZone(zone).toLocalDate()
        val skipped = plans.skipped(eventId, today)
        val urgent = urgentBlock(bundle, eventId)
        val dismissedUrgent = plans.dismissedUrgent(eventId)
        val arrived = plans.arrived(eventId, today)
        val user = lastLocation()

        // Reminders for hearted / planned items (shown once each).
        val shown = plans.remindersShown(eventId)
        val reminders = LiveFollow.reminders(bundle, plan, heartsNow, now, user, skipped).filter { it.first !in shown }
        val (due, later) = reminders.partition { !it.second.isAfter(now.plus(REMINDER_EARLY)) }
        due.forEach { (itemId, _) ->
            val item = bundle.scheduleItem(itemId) ?: return@forEach
            val (title, text) = LiveFollow.reminderText(bundle, item, now, user)
            LiveNotifications.showReminder(appContext, eventId, itemId, title, text)
            plans.markReminderShown(eventId, itemId)
        }

        // The Live Update itself; not reposted on a day the user swiped it away (unless urgent changed).
        val dismissedToday = plans.liveDismissedOn(eventId) == today.toString()
        val state = LiveFollow.state(bundle, plan, heartsNow, now, skipped, urgent, dismissedUrgent, user, arrived)
        if (state != null && !dismissedToday) {
            var failure: Pair<String, Throwable?>? = null
            val posted = try {
                LiveNotifications.showLive(appContext, bundle, state, heartsNow).also { if (!it) failure = PerfMetric.LIVE_DENIED to null }
            } catch (e: RuntimeException) {
                failure = PerfMetric.LIVE_ERROR to e
                false
            }
            // Android 16+: posted, but the user turned Live Updates (promoted notifications) off for the app.
            if (posted && !LiveNotifications.canPromote(appContext)) failure = PerfMetric.LIVE_DISABLED to null
            failure?.let { (reason, error) ->
                if (!failCounted) {
                    failCounted = true
                    perf?.record(PerfMetric.LIVE_START_FAIL, reason)
                    AppLog.warning(
                        LogCategory.LIVE, LogEvent.LIVE_START_FAILED, "Live Update can't start ($reason)",
                        mapOf("reason" to reason, "domain" to error?.javaClass?.simpleName),
                    )
                }
            }
            val fields = mapOf("mode" to state.mode, "source" to state.source, "items" to state.segments.count { it.isItem })
            when {
                !posted -> liveShown = false
                !liveShown -> AppLog.notice(LogCategory.LIVE, LogEvent.LIVE_START, "Live Update started (${state.mode})", fields)
                else -> AppLog.debug(LogCategory.LIVE, LogEvent.LIVE_UPDATE, "Live Update (${state.mode})", mapOf("mode" to state.mode))
            }
            if (posted) {
                liveShown = true
                if (failure == null) failCounted = false
            }
        } else {
            cancelLive(if (dismissedToday) "user" else "day_over")
        }

        val wake = LiveFollow.nextWake(bundle, plan, heartsNow, now, skipped, urgent, dismissedUrgent, later, arrived)
        schedule(wake)
        geofence.ensure(bundle)
    }

    /** The urgent block: the newest of the last urgent push and the bundle's urgent updates. */
    private fun urgentBlock(bundle: EventBundle, eventId: Int): UrgentBlock? {
        val fromBundle = bundle.notifications.filter { it.isUrgent && it.sendTime != null }
            .maxByOrNull { it.sendTime!! }
            ?.let { UrgentBlock(it.id, it.title, it.body, it.sendTime!!.toInstant()) }
        val fromPush = plans.urgent(eventId)
        return listOfNotNull(fromBundle, fromPush).maxByOrNull { it.sentAt }
    }

    private fun cancelLive(reason: String) {
        if (liveShown) AppLog.info(LogCategory.LIVE, LogEvent.LIVE_END, "Live Update ended ($reason)", mapOf("reason" to reason))
        liveShown = false
        LiveNotifications.cancelLive(appContext)
    }

    private fun stop(clearGeofence: Boolean, reason: String) {
        cancelLive(reason)
        schedule(null)
        if (clearGeofence) geofence.clear()
    }

    // ── Actions (notification buttons, geofence, pushes) ────────────────────

    suspend fun toggleHeart(eventId: Int, itemId: Int) {
        if (repository.restore()?.event?.id != eventId) return
        val hearted = !hearts.hearts(eventId).has(SearchKind.SCHEDULE, itemId)
        AppLog.info(LogCategory.LIVE, LogEvent.LIVE_ACTION, "Live Update action: ${if (hearted) "heart" else "unheart"}", mapOf("action" to if (hearted) "heart" else "unheart"))
        if (hearts.set(eventId, SearchKind.SCHEDULE, itemId, hearted)) {
            Hearts.metric(SearchKind.SCHEDULE, hearted)?.let { usage.track(eventId, it, itemId.toString()) }
        }
        refresh()
    }

    suspend fun skip(eventId: Int, itemId: Int) {
        AppLog.info(LogCategory.LIVE, LogEvent.LIVE_ACTION, "Live Update action: skip", mapOf("action" to "skip"))
        val bundle = repository.restore()?.takeIf { it.event.id == eventId } ?: return
        plans.skip(eventId, Instant.now().atZone(bundle.event.zone).toLocalDate(), itemId)
        refresh()
    }

    suspend fun dismissUrgent(eventId: Int, notificationId: Int) {
        AppLog.info(LogCategory.LIVE, LogEvent.LIVE_ACTION, "Live Update action: dismiss urgent", mapOf("action" to "dismiss_urgent"))
        plans.dismissUrgent(eventId, notificationId)
        refresh()
    }

    /** The user swiped the Live Update away: don't repost it today (Android's Live Update guidance). */
    suspend fun onDismissed(eventId: Int) {
        val bundle = repository.restore()?.takeIf { it.event.id == eventId } ?: return
        plans.setLiveDismissed(eventId, Instant.now().atZone(bundle.event.zone).toLocalDate())
        refresh()
    }

    suspend fun onArrived() {
        AppLog.notice(LogCategory.LIVE, LogEvent.LIVE_ARRIVAL, "Arrived at the venue", mapOf("action" to "entered"))
        val bundle = repository.restore() ?: return
        plans.setArrived(bundle.event.id, Instant.now().atZone(bundle.event.zone).toLocalDate())
        refresh()
    }

    /** API-011: an urgent push for the joined event. */
    suspend fun onUrgent(eventId: Int, notificationId: Int, title: String, body: String) {
        if (repository.restore()?.event?.id != eventId) return
        plans.setUrgent(eventId, UrgentBlock(notificationId, title, body, Instant.now()))
        refresh()
    }

    // ── Alarms and location ─────────────────────────────────────────────────

    private fun schedule(at: Instant?) {
        val alarms = appContext.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, LiveUpdateReceiver::class.java).setAction(LiveUpdateReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarms.cancel(intent)
            return
        }
        // Inexact: delivered within ±5 min of the target (Android may stretch windows under 10 min).
        val start = maxOf(System.currentTimeMillis(), at.toEpochMilli() - WINDOW_MS / 2)
        alarms.setWindow(AlarmManager.RTC_WAKEUP, start, WINDOW_MS, intent)
        AppLog.debug(
            LogCategory.LIVE, LogEvent.LIVE_WAKE_SCHEDULED, "Next Live Update wake-up in ${(start - System.currentTimeMillis()) / 60_000} min",
            mapOf("at" to LogJson.timestamp(Instant.ofEpochMilli(start)), "reason" to "next_change", "window_s" to WINDOW_MS / 1000),
        )
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission()
    private suspend fun lastLocation(): GeoPoint? {
        if (!hasLocationPermission(appContext)) return null
        return try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                LocationServices.getFusedLocationProviderClient(appContext).lastLocation.await()
            }?.takeIf { Duration.ofMillis(System.currentTimeMillis() - it.time) < MAX_LOCATION_AGE }
                ?.let { GeoPoint.of(it.latitude, it.longitude) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.caught(LogCategory.LOCATION, "live.last_location", e, LogLevel.WARNING)
            null
        }
    }

    private companion object {
        const val WINDOW_MS = 10 * 60 * 1000L
        const val LOCATION_TIMEOUT_MS = 3_000L
        val MAX_LOCATION_AGE: Duration = Duration.ofMinutes(30)

        /** A reminder is shown when its time is at most this far ahead (alarms are inexact). */
        val REMINDER_EARLY: Duration = Duration.ofMinutes(5)
    }
}
