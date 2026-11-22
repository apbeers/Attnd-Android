package com.arcu.attnd.live

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.arcu.attnd.data.EventRepository
import com.arcu.attnd.data.HeartStore
import com.arcu.attnd.data.PlanStore
import com.arcu.attnd.data.ScheduleController
import com.arcu.attnd.data.hasLocationPermission
import com.arcu.attnd.data.log.PerfRecorder
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.LiveFollow
import com.arcu.attnd.domain.LiveContent
import com.arcu.attnd.domain.LiveDecision
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
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.ScheduleEntryKind
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
 * (shared spec §8, iOS's Follow on Lock Screen): [refresh] follows the day ([LiveFollow.followDay]: the
 * My schedule's items and timed places, else the event's; `live_activity.follows`),
 * builds its content ([LiveFollow.content]: Now / Next always filled) from the saved bundle,
 * posts or cancels the notification, shows due reminders and schedules the next wake-up as an inexact
 * [AlarmManager.setWindow] alarm (no exact-alarm permission). It runs after every bundle / My schedule
 * change (AppGraph), on each alarm, on a `content_updated` push (the bundle changes), on boot, on the
 * notification actions.
 *
 * In the foreground the Live Update posts at once, even when the day is ahead (iOS batch 7, "starts on
 * join"); in the background it appears 2 h before the day's first entry and keeps updating once up.
 * The Live Update only while "Follow on Lock Screen" is on for the joined event ([PlanStore.follows], on by default),
 * reminders while they are on ([PlanStore.reminders], default: the same); never in screenshot mode
 * ([suspended]). It follows My schedule (one list, Nov 22, 2026: no separate plan); with the user's
 * position, My schedule's next item's departure time switches the Live Update to "Leave now" (assistant spec §6, [LiveAssist.runningLate]).
 */
class LiveUpdateController(
    context: Context,
    private val repository: EventRepository,
    private val hearts: HeartStore,
    private val plans: PlanStore,
    private val mySchedules: ScheduleController,
    private val scope: CoroutineScope,
    private val perf: PerfRecorder? = null,
    /** Whether an activity of the app is started (the Live Update then posts at once, iOS "starts on join"). */
    private val foreground: () -> Boolean = { false },
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

    /** The reminders waiting for their time at the last update (`live.reminder.scheduled` on change). */
    @Volatile
    private var lastPendingReminders: Set<Int>? = null

    private suspend fun update(now: Instant) {
        val bundle = repository.restore()
        if (bundle == null || suspended) {
            stop(reason = if (bundle == null) "left_event" else "system")
            return
        }
        val eventId = bundle.event.id
        val heartsNow = hearts.hearts(eventId)
        // My schedule's day view (one list: derived, never stored): running late and "Leave now".
        val plan = mySchedules.dayPlan(eventId, bundle)
        val follows = plans.follows(eventId, heartsNow)
        val remindersOn = plans.reminders(eventId, heartsNow)
        if (!follows && !remindersOn) {
            stop(reason = "unfollowed")
            return
        }
        val zone = bundle.event.zone
        val today = now.atZone(zone).toLocalDate()
        val skipped = plans.skipped(eventId, today)
        val user = lastLocation()
        val mine = mySchedules.schedule(eventId)

        // Reminders per entry of My schedule (its own lead, default 15 min; timed places too), shown once each.
        val shown = plans.remindersShown(eventId)
        val reminders = if (!remindersOn) emptyList() else LiveFollow.reminders(
            eventId = eventId,
            schedule = bundle.schedule,
            hearted = heartsNow.scheduleItems - skipped,
            locations = bundle.locations,
            user = user,
            // Alarms are inexact: a reminder whose time passed a little ago is still shown before its start.
            now = now.minus(REMINDER_LATE),
            zone = zone,
            leadMinutes = mine.entries.filter { it.kind == ScheduleEntryKind.ITEM }.associate { it.id to it.reminderMinutes },
            places = mine.entries.filter { it.kind == ScheduleEntryKind.PLACE && it.at != null },
        ).filter { it.start.isAfter(now) && it.shownKey !in shown }
        val (due, later) = reminders.partition { !it.fireAt.isAfter(now.plus(REMINDER_EARLY)) }
        due.forEach { reminder ->
            val target = reminder.locationId?.let { OpenTarget.Location(it) } ?: OpenTarget.ScheduleItem(reminder.itemId)
            LiveNotifications.showReminder(appContext, eventId, reminder.shownKey, target, reminder.title, reminder.body)
            plans.markReminderShown(eventId, reminder.shownKey)
        }

        // The Live Update (iOS's Follow on Lock Screen): what the organizer chose it follows (API-027).
        val inputs = LiveFollow.inputs(bundle.liveActivity, bundle, heartsNow.scheduleItems, LiveFollow.places(mine, bundle))
        val eventStart = bundle.event.startTime?.toInstant()
        val day = if (follows) LiveFollow.followDay(inputs, eventStart, now, zone) else null
        val summaryDay = if (follows) LiveFollow.endedToday(inputs, eventStart, now, zone, day) else null
        val urgent = LiveFollow.urgent(bundle.notifications, setOfNotNull(plans.dismissedUrgent(eventId)), plans.urgent(eventId), now)
            ?.takeIf { follows }
        val showing = LiveNotifications.isShowing(appContext)
        val decision = LiveFollow.decision(day, now, zone)
        // Starts on join (iOS batch 7): in the foreground it posts at once, even for a day ahead; in the
        // background it starts 2 h before the day's first entry and keeps updating once up.
        val dayShown = day != null && now.isBefore(day.endAt) && (decision == LiveDecision.StartNow || foreground() || showing)
        val shownDay = if (dayShown) day else summaryDay?.takeIf { showing }
        val late = if (shownDay?.followsHearts == true && plan != null && !plan.isEmpty) LiveAssist.runningLate(plan, bundle, now, user, skipped) else null
        val state = when {
            shownDay != null -> {
                // No direction, distance or walk from the user's position (iOS batch 7): user = null.
                val content = LiveFollow.content(shownDay, bundle.locations, heartsNow.scheduleItems, skipped, user = null, urgent = urgent, now = now)
                val hasMine = !heartsNow.isEmpty
                LiveFollow.render(LiveFollow.decorate(content, shownDay, bundle.liveActivity, hasMine, late), shownDay, bundle.event.name, zone, now)
            }
            // An urgent block shows even outside the day.
            urgent != null -> LiveFollow.render(LiveContent(urgent = urgent), null, bundle.event.name, zone, now)
            else -> null
        }
        state?.content?.lateState?.let { logRunningLate(late!!, bundle) }

        // Not reposted on a day the user swiped it away.
        val dismissedToday = plans.liveDismissedOn(eventId) == today.toString()
        if (state != null && !dismissedToday) post(bundle, state) else cancelLive(if (dismissedToday) "user" else "day_over")

        val reminderWakes = later.map { it.fireAt.minus(REMINDER_EARLY) }
        // live.reminder.scheduled: only when the set of pending reminders changes (not every recompute).
        val pendingKeys = later.map { it.shownKey }.toSet()
        if (pendingKeys != lastPendingReminders) {
            lastPendingReminders = pendingKeys
            AppLog.debug(LogCategory.LIVE, LogEvent.LIVE_REMINDER_SCHEDULED, "${later.size} reminders scheduled", mapOf("count" to later.size))
        }
        val wake = if (!follows) reminderWakes.minOrNull()
        else LiveFollow.nextWake(day ?: summaryDay, now, showing = state != null && !dismissedToday, urgent = urgent, extra = reminderWakes)
        // Assistant spec §6: also wake at My schedule's next item's departure time (running late).
        val leaveBy = if (day?.followsHearts == true && plan != null && late == null) nextLeaveBy(plan, bundle, now, user, skipped) else null
        schedule(listOfNotNull(wake, leaveBy).minOrNull())
    }

    private fun post(bundle: EventBundle, state: LiveState) {
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
    }

    /** "Leave now" (assistant spec §6, [LiveFollow.decorate]): logged once per item. */
    private fun logRunningLate(late: RunningLate, bundle: EventBundle) {
        if (lateShownFor == late.itemId) return
        lateShownFor = late.itemId
        val item = bundle.scheduleItem(late.itemId) ?: return
        val left = item.startTime?.let { Duration.between(Instant.now(), it.toInstant()).toMinutes() }
        AppLog.notice(
            LogCategory.LIVE, LogEvent.LIVE_RUNNING_LATE, "Running late: ${late.state}",
            mapOf("item_id" to late.itemId, "minutes_left" to left, "walk_min" to late.walkMinutes, "action" to "shown"),
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

        /** …or passed at most this long ago (a late alarm), while its entry hasn't started. */
        val REMINDER_LATE: Duration = Duration.ofMinutes(15)
    }
}
