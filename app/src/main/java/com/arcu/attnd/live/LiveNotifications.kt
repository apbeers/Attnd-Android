package com.arcu.attnd.live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arcu.attnd.R
import com.arcu.attnd.assistant.OpenIntents
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveMode
import com.arcu.attnd.domain.LiveState
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.push.PushNotifications

/**
 * The Live Update (shared spec §8), reminders and urgent announcements (API-011) as notifications.
 *
 * The Live Update is an Android 16 **promoted ongoing notification** ("Live Update"): ongoing, a content
 * title, `ProgressStyle` (or `BigTextStyle` in urgent mode), `setRequestPromotedOngoing(true)` and the
 * status-bar chip text via `setShortCriticalText`, on a channel that isn't IMPORTANCE_MIN, never
 * colorized or custom, with `POST_PROMOTED_NOTIFICATIONS` in the manifest. Below Android 16,
 * NotificationCompat posts the same notification as a normal ongoing one (progress bar, no chip).
 * The user can turn promotion off in system settings (`canPostPromotedNotifications()`); it then shows
 * as a normal ongoing notification.
 */
object LiveNotifications {

    const val CHANNEL_LIVE = "live_update"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_URGENT = "urgent_updates"

    const val LIVE_NOTIFICATION_ID = 7_001
    private const val REMINDER_ID_BASE = 8_000_000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                // Quiet: the Live Update changes often and must never buzz. LOW is allowed (only MIN isn't).
                NotificationChannel(CHANNEL_LIVE, context.getString(R.string.live_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.live_channel_description)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.reminders_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.reminders_channel_description)
                },
                NotificationChannel(CHANNEL_URGENT, context.getString(R.string.urgent_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.urgent_channel_description)
                },
            )
        )
    }

    private fun open(context: Context, eventId: Int, target: OpenTarget): PendingIntent =
        OpenIntents.pendingIntent(context, OpenRequest(eventId, target))

    private fun action(context: Context, action: String, eventId: Int, itemId: Int? = null): PendingIntent {
        val intent = Intent(context, LiveUpdateReceiver::class.java).setAction(action)
            .putExtra(LiveUpdateReceiver.EXTRA_EVENT_ID, eventId)
        if (itemId != null) intent.putExtra(LiveUpdateReceiver.EXTRA_ITEM_ID, itemId)
        // The request code keeps PendingIntents for different items apart.
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() * 31 + (itemId ?: 0)),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Posts the Live Update; false when it can't be shown (no POST_NOTIFICATIONS). */
    @SuppressLint("MissingPermission") // checked by PushNotifications.hasPermission()
    fun showLive(context: Context, bundle: EventBundle, state: LiveState, hearts: Hearts): Boolean {
        if (!PushNotifications.hasPermission(context)) return false
        val eventId = bundle.event.id
        val focus = state.focus
        val urgent = state.urgent
        val tap = when {
            urgent != null -> open(context, eventId, OpenTarget.Notification(urgent.id))
            focus != null -> open(context, eventId, OpenTarget.ScheduleItem(focus.id))
            else -> open(context, eventId, OpenTarget.MyPlan)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_attnd)
            .setColor(context.getColor(R.color.attnd_primary))
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setSubText(bundle.event.name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(state.chip)
            .setContentIntent(tap)
            .setDeleteIntent(action(context, LiveUpdateReceiver.ACTION_DISMISSED, eventId))

        val countdown = state.countdownTo
        if (countdown != null) {
            builder.setWhen(countdown.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }

        if (state.mode == LiveMode.URGENT && urgent != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(urgent.body).setBigContentTitle(state.title))
            builder.addAction(0, context.getString(R.string.live_action_dismiss_urgent), action(context, LiveUpdateReceiver.ACTION_DISMISS_URGENT, eventId, urgent.id))
        } else {
            builder.setStyle(progressStyle(context, state))
        }

        if (focus != null && state.mode != LiveMode.URGENT) {
            val hearted = hearts.has(SearchKind.SCHEDULE, focus.id)
            builder.addAction(
                0,
                context.getString(if (hearted) R.string.live_action_unheart else R.string.live_action_heart),
                action(context, LiveUpdateReceiver.ACTION_HEART, eventId, focus.id),
            )
        }
        state.next?.takeIf { state.mode != LiveMode.URGENT }?.let { next ->
            builder.addAction(0, context.getString(R.string.live_action_skip), action(context, LiveUpdateReceiver.ACTION_SKIP, eventId, next.id))
        }
        val place = bundle.location(focus?.locationId)?.takeIf { it.coordinate != null }
        if (place != null && Tab.MAP in bundle.display.tabs) {
            builder.addAction(0, context.getString(R.string.live_action_map), open(context, eventId, OpenTarget.MapLocation(place.id)))
        }
        NotificationManagerCompat.from(context).notify(LIVE_NOTIFICATION_ID, builder.build())
        return true
    }

    /** The day as a journey: item segments in the accent colour, gaps neutral, a point at each start. */
    private fun progressStyle(context: Context, state: LiveState): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle().setStyledByProgress(true)
        if (state.segments.isEmpty()) return style.setProgressIndeterminate(true)
        val accent = context.getColor(R.color.attnd_primary)
        val gap = context.getColor(R.color.live_gap)
        style.setProgressSegments(state.segments.map { NotificationCompat.ProgressStyle.Segment(it.minutes).setColor(if (it.isItem) accent else gap) })
        style.setProgressPoints(state.points.map { NotificationCompat.ProgressStyle.Point(it).setColor(accent) })
        style.setProgress(state.progress)
        return style
    }

    /** Live Updates may be promoted (Android 16+ lets the user turn that off per app). */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA || NotificationManagerCompat.from(context).canPostPromotedNotifications()

    fun cancelLive(context: Context) {
        NotificationManagerCompat.from(context).cancel(LIVE_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission") // checked by PushNotifications.hasPermission()
    fun showReminder(context: Context, eventId: Int, itemId: Int, title: String, text: String) {
        if (!PushNotifications.hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_attnd)
            .setColor(context.getColor(R.color.attnd_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(REMINDER_TIMEOUT_MS)
            .setContentIntent(open(context, eventId, OpenTarget.ScheduleItem(itemId)))
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_ID_BASE + itemId, notification)
        AppLog.info(LogCategory.LIVE, LogEvent.LIVE_REMINDER_SHOWN, "Reminder shown", mapOf("item_id" to itemId))
    }

    /**
     * API-011: an urgent announcement on the high-importance urgent channel, whenever POST_NOTIFICATIONS
     * is granted, whatever the in-app opt-in. Tapping opens Updates at that notification.
     */
    @SuppressLint("MissingPermission") // checked by PushNotifications.hasPermission()
    fun showUrgent(context: Context, notificationId: Int?, title: String, body: String) {
        if (!PushNotifications.hasPermission(context)) {
            AppLog.warning(LogCategory.PUSH, LogEvent.PUSH_DROPPED, "Urgent notification not shown: no permission", mapOf("type" to "notification", "reason" to "no_permission"))
            return
        }
        val localId = notificationId ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        // Like other organizer notifications: counts `notification_open` and scrolls to it.
        val tap = PushNotifications.openNotificationsIntent(context, localId, notificationId)
        val notification = NotificationCompat.Builder(context, CHANNEL_URGENT)
            .setSmallIcon(R.drawable.ic_stat_attnd)
            .setColor(context.getColor(R.color.attnd_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(URGENT_TIMEOUT_MS)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(localId, notification)
    }

    private const val REMINDER_TIMEOUT_MS = 60 * 60 * 1000L
    private const val URGENT_TIMEOUT_MS = 12 * 60 * 60 * 1000L
}
