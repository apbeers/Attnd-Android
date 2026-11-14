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

    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun show(context: Context, title: String, body: String, notificationId: Int) {
        if (!hasPermission(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_NOTIFICATIONS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_attnd)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    /**
     * The current FCM registration token, or null when Firebase is not configured
     * (no google-services.json in the build) or the token cannot be fetched.
     */
    suspend fun currentToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
