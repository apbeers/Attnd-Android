package com.arcu.attnd.push

import com.arcu.attnd.AttndApplication
import com.arcu.attnd.live.LiveNotifications
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Receives FCM messages (API_V2.md, "Push notifications"):
 * - `type=notification`: an organizer notification. Shown with our channel; tapping opens the Notifications tab.
 *   With `priority=urgent` (API-011; data-only, `title`/`body` in `data`): the urgent channel, whatever
 *   the in-app opt-in, and the Live Update's urgent mode.
 * - `type=content_updated`: a silent data message (API-007: sent to every device in the event with
 *   a token, opted in to notifications or not; debounced and collapsible). The bundle is refetched
 *   silently after a random 0–10 s delay, unless it is already at the pushed `content_version`.
 *   Data messages need no POST_NOTIFICATIONS permission.
 *
 * Refreshes run on the app scope, so they also work while the app is in the background.
 * Only runs when Firebase is configured (google-services.json present at build time).
 */
class AttndMessagingService : FirebaseMessagingService() {

    private val graph get() = (application as AttndApplication).graph

    // Deprecated with the token API in firebase-messaging 25.1 (see PushNotifications.currentToken):
    // still called for tokens until the app moves to FIDs, which needs an attnd-web API change first.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        graph.appScope.launch { graph.repository.onPushTokenChanged(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val repository = graph.repository
        val eventId = data[PushNotifications.KEY_EVENT_ID]?.toIntOrNull()
        val isForCurrentEvent = eventId == null || eventId == repository.currentEventId

        when (data[PushNotifications.KEY_TYPE]) {
            PushNotifications.TYPE_CONTENT_UPDATED -> {
                // FCM data values are strings.
                val version = data[PushNotifications.KEY_CONTENT_VERSION]?.trim()?.toIntOrNull()
                if (isForCurrentEvent) graph.appScope.launch { repository.onContentUpdated(version) }
            }
            else -> {
                // Organizer notification (or an untyped message with a notification block).
                val title = message.notification?.title ?: data[PushNotifications.KEY_TITLE]
                val body = message.notification?.body ?: data[PushNotifications.KEY_BODY]
                val serverId = data[PushNotifications.KEY_NOTIFICATION_ID]?.toIntOrNull()
                if (data[PushNotifications.KEY_PRIORITY]?.trim()?.lowercase() == PushNotifications.PRIORITY_URGENT) {
                    // API-011: data-only, high priority, sent to every device in the event. Shown on the
                    // urgent channel whenever POST_NOTIFICATIONS is granted, even with the in-app opt-in
                    // off; the Live Update switches to urgent mode.
                    LiveNotifications.showUrgent(this, serverId, title ?: "Urgent update", body.orEmpty())
                    if (isForCurrentEvent && eventId != null && serverId != null) {
                        graph.appScope.launch { graph.liveUpdates.onUrgent(eventId, serverId, title.orEmpty(), body.orEmpty()) }
                    }
                } else if (title != null || body != null) {
                    val id = serverId ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                    PushNotifications.show(this, title ?: "Event update", body.orEmpty(), id, serverId)
                }
                if (isForCurrentEvent) graph.appScope.launch { repository.refresh(silent = true) }
            }
        }
    }
}
