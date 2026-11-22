package com.arcu.attnd.live

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.data.hasLocationPermission
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.LiveFollow
import com.arcu.attnd.domain.LiveMode
import com.arcu.attnd.domain.LiveState
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.RunningLate
import com.arcu.attnd.domain.Walking
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogJson
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.PerfMetric
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
 * Drives the Live Update and reminders from the device, with no server
 * (shared spec §8): [refresh] recomputes [LiveFollow.state] from the saved bundle, plan and hearts,
 * posts or cancels the notification, shows due reminders and schedules the next wake-up as an inexact
 * [AlarmManager.setWindow] alarm (no exact-alarm permission). It runs after every bundle / hearts / plan
 * change (AppGraph), on each alarm, on a `content_updated` push (the bundle changes), on boot, on the
 * notification actions.
 *
 * The Live Update only while "Follow on Lock Screen" is on for the joined event ([PlanStore.follows]),
 * reminders while they are on ([PlanStore.reminders], default: the same); never in screenshot mode
 * ([suspended]). With a plan and the user's position, the next item's departure time switches the
 * Live Update to "Leave now" (assistant spec §6, [LiveAssist.runningLate]).
 */
class LiveUpdateController(
    context: Context,
    private val repository: EventRepository,
    private val hearts: HeartStore,
    private val plans: PlanStore,
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
            stop(reason = if (bundle == null) "left_event" else "system")
            return
        }
        val eventId = bundle.event.id
        val heartsNow = hearts.hearts(eventId)
        val plan = plans.plan(eventId)
        val follows = plans.follows(eventId, heartsNow)
        val remindersOn = plans.reminders(eventId, heartsNow)
        if (!follows && !remindersOn) {
            stop(reason = "unfollowed")
            return
        }
        val zone = bundle.event.zone
        val today = now.atZone(zone).toLocalDate()
        val skipped = plans.skipped(eventId, today)
        val urgent = urgentBlock(bundle, eventId)
        val dismissedUrgent = plans.dismissedUrgent(eventId)
        val user = lastLocation()

        // Reminders for hearted / planned items (shown once each).
        val shown = plans.remindersShown(eventId)
        val reminders = if (!remindersOn) emptyList() else LiveFollow.reminders(bundle, plan, heartsNow, now, user, skipped).filter { it.first !in shown }
        val (due, later) = reminders.partition { !it.second.isAfter(now.plus(REMINDER_EARLY)) }
        due.forEach { (itemId, _) ->
            val item = bundle.scheduleItem(itemId) ?: return@forEach
            val (title, text) = LiveFollow.reminderText(bundle, item, now, user)
            LiveNotifications.showReminder(appContext, eventId, itemId, title, text)
            plans.markReminderShown(eventId, itemId)
        }

        // The Live Update itself; not reposted on a day the user swiped it away (unless urgent changed).
        val dismissedToday = plans.liveDismissedOn(eventId) == today.toString()
        val late = if (follows && plan != null) LiveAssist.runningLate(plan, bundle, now, user, skipped) else null
        val state = if (!follows) null else LiveFollow.state(bundle, plan, heartsNow, now, skipped, urgent, dismissedUrgent, user)
            ?.let { withRunningLate(it, late, bundle) }
        if (state != null && !dismissedToday) {
            var failure: Pair<String, Throwable?>? = null
            val posted = try {
                LiveNotifications.showLive(appContext, bundle, state).also { if (!it) failure = PerfMetric.LIVE_DENIED to null }
            } catch (e: RuntimeException) {
                failure = PerfMetric.LIVE_ERROR to e
                false
            }
            // Posted, but the user turned Live Updates (promoted notifications) off for the app.
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

        val wake = if (!follows) later.minOfOrNull { it.second.minus(REMINDER_EARLY) }
        else LiveFollow.nextWake(bundle, plan, heartsNow, now, skipped, urgent, dismissedUrgent, later)
        // Assistant spec §6: also wake at the next planned item's departure time (running late).
        val leaveBy = if (follows && plan != null && late == null) nextLeaveBy(plan, bundle, now, user, skipped) else null
        schedule(listOfNotNull(wake, leaveBy).minOrNull())
    }

    /**
     * "Leave now" in the Live Update (assistant spec §6): from the next planned item's departure time,
     * the title says to leave (LEAVE_NOW) or that it can't be made in time; the chip reads "Leave".
     * Urgent mode is never replaced.
     */
    private fun withRunningLate(state: LiveState, late: RunningLate?, bundle: EventBundle): LiveState {
        if (late == null || state.mode == LiveMode.URGENT) return state
        val item = bundle.scheduleItem(late.itemId) ?: return state
        if (lateShownFor != late.itemId) {
            lateShownFor = late.itemId
            val left = Duration.between(Instant.now(), item.startTime!!.toInstant()).toMinutes()
            AppLog.notice(
                LogCategory.LIVE, LogEvent.LIVE_RUNNING_LATE, "Running late: ${late.state}",
                mapOf("item_id" to late.itemId, "minutes_left" to left, "walk_min" to late.walkMinutes, "action" to "shown"),
            )
        }
        return state.copy(
            title = late.text,
            text = listOfNotNull(EventTime.formatTimeRange(item.startTime, item.endTime, bundle.event.zone), bundle.location(item.locationId)?.name).joinToString(" · "),
            chip = if (late.state == LateState.LEAVE_NOW) "Leave" else state.chip,
            focus = item,
        )
    }

    @Volatile
    private var lateShownFor: Int? = null

    /** When the next planned item's departure time comes (start − walk − 5 min), with the user's position. */
    private fun nextLeaveBy(plan: Plan, bundle: EventBundle, now: Instant, user: GeoPoint?, skipped: Set<Int>): Instant? {
        user ?: return null
        val next = LiveAssist.nextPlanned(plan, bundle, now, skipped) ?: return null
        val target = bundle.location(next.locationId)?.coordinate ?: return null
        val meters = Geo.distanceMeters(user, target)
        if (meters <= LiveAssist.THERE_METERS || !Walking.isNearby(meters)) return null
        val walk = Walking.minutes(Walking.estimateSeconds(meters))
        return next.startTime!!.toInstant().minus(Duration.ofMinutes(walk + Planner.BUFFER_MINUTES)).takeIf { it.isAfter(now) }
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

    private fun stop(reason: String) {
        cancelLive(reason)
        schedule(null)
    }

    // ── Actions (notification buttons, pushes) ─────────────────────────────

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
