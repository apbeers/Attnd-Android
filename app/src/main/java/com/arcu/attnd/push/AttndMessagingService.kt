package com.arcu.attnd.push

import com.arcu.attnd.AttndApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Receives FCM messages (API_V2.md, "Push notifications"):
 * - `type=notification`: an organizer notification. Shown with our channel; tapping opens the Notifications tab.
 * - `type=content_updated`: a silent data message. The bundle is refetched.
 *
 * Only runs when Firebase is configured (google-services.json present at build time).
 */
class AttndMessagingService : FirebaseMessagingService() {

    private val graph get() = (application as AttndApplication).graph

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
                if (isForCurrentEvent) graph.appScope.launch { repository.refresh() }
            }
            else -> {
                // Organizer notification (or an untyped message with a notification block).
                val title = message.notification?.title ?: data["title"]
                val body = message.notification?.body ?: data["body"]
                if (title != null || body != null) {
                    val id = data[PushNotifications.KEY_NOTIFICATION_ID]?.toIntOrNull()
                        ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                    PushNotifications.show(this, title ?: "Event update", body.orEmpty(), id)
                }
                if (isForCurrentEvent) graph.appScope.launch { repository.refresh() }
            }
        }
    }
}
