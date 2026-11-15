package com.arcu.attnd.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arcu.attnd.MainActivity
import com.arcu.attnd.R
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Notification channel, local display of organizer pushes, and guarded FCM token access. */
object PushNotifications {

    /** Also referenced from the manifest as FCM's default channel. */
    const val CHANNEL_ID = "event_updates"

    /** Intent extra asking MainActivity to open a tab; FCM's own tray notifications carry `type` instead. */
    const val EXTRA_OPEN_TAB = "com.arcu.attnd.extra.OPEN_TAB"
    const val TAB_NOTIFICATIONS = "notifications"

    /** FCM data keys (see API_V2.md, "Push notifications"). */
    const val KEY_TYPE = "type"
    const val KEY_EVENT_ID = "event_id"
    const val KEY_NOTIFICATION_ID = "notification_id"
    const val KEY_CONTENT_VERSION = "content_version"
    const val KEY_PRIORITY = "priority"
    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"
    const val PRIORITY_URGENT = "urgent"
    const val TYPE_NOTIFICATION = "notification"
    const val TYPE_CONTENT_UPDATED = "content_updated"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** True when an Intent (launch or new) came from tapping one of our notifications. */
    fun opensNotifications(intent: Intent?): Boolean {
        intent ?: return false
        return intent.getStringExtra(EXTRA_OPEN_TAB) == TAB_NOTIFICATIONS ||
            intent.getStringExtra(KEY_TYPE) == TYPE_NOTIFICATION
    }

    /**
     * The server's notification id carried by a notification-tap Intent: our own notifications put
     * it there, and FCM's tray notifications copy the data payload into the extras (as strings).
     */
    fun notificationId(intent: Intent?): Int? = intent?.getStringExtra(KEY_NOTIFICATION_ID)?.toIntOrNull()

    /**
     * Shows an organizer notification. [notificationId] is the local id (unique per notification);
     * [serverNotificationId] is the API's id when the push carried one, passed on for usage counts.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun show(context: Context, title: String, body: String, notificationId: Int, serverNotificationId: Int? = null) {
        if (!hasPermission(context)) {
            AppLog.info(LogCategory.PUSH, LogEvent.PUSH_DROPPED, "Not shown: no notification permission", mapOf("type" to "notification", "reason" to "no_permission"))
            return
        }
        val pendingIntent = openNotificationsIntent(context, notificationId, serverNotificationId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_attnd)
            .setColor(context.getColor(R.color.attnd_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    /** Opens the Notifications tab (at [serverNotificationId] when known); [requestCode] keeps intents apart. */
    fun openNotificationsIntent(context: Context, requestCode: Int, serverNotificationId: Int?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_NOTIFICATIONS)
            if (serverNotificationId != null) putExtra(KEY_NOTIFICATION_ID, serverNotificationId.toString())
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The current FCM registration token, or null when Firebase is not configured
     * (no google-services.json in the build) or the token cannot be fetched.
     *
     * firebase-messaging 25.1 deprecates the token API in favour of `register()` / `onRegistered`,
     * which hand out a Firebase Installation ID (FID) instead of a token. Moving to it changes what
     * `push_token` carries and how attnd-web sends pushes, so it is an API change that starts in
     * attnd-web (API_CHANGES.md); until then the token API is used on purpose.
     */
    @Suppress("DEPRECATION")
    suspend fun currentToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) {
            AppLog.info(LogCategory.PUSH, LogEvent.PUSH_TOKEN_FAILED, "Firebase not configured: no push token", mapOf("error" to "not_configured"))
            return null
        }
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warning(LogCategory.PUSH, LogEvent.PUSH_TOKEN_FAILED, "FCM token unavailable", mapOf("error" to AppLog.errorKind(e), "domain" to e.javaClass.simpleName))
            null
        }
    }
}
