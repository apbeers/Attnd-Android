package com.arcu.attnd.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arcu.attnd.AttndApplication
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.launch

/**
 * Wake-ups for the Live Update (alarm ticks, notification actions, the venue geofence, boot / app
 * update). Each hands the work to [LiveUpdateController] on the app scope and keeps the receiver
 * alive with [goAsync] until it is done.
 */
class LiveUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AttndApplication ?: return
        val live = app.graph.liveUpdates
        val eventId = intent.getIntExtra(EXTRA_EVENT_ID, -1)
        val itemId = intent.getIntExtra(EXTRA_ITEM_ID, -1)
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_HEART -> if (itemId >= 0) live.toggleHeart(eventId, itemId)
                    ACTION_SKIP -> if (itemId >= 0) live.skip(eventId, itemId)
                    ACTION_DISMISS_URGENT -> if (itemId >= 0) live.dismissUrgent(eventId, itemId)
                    ACTION_DISMISSED -> live.onDismissed(eventId)
                    ACTION_GEOFENCE -> {
                        val event = GeofencingEvent.fromIntent(intent)
                        if (event != null && !event.hasError() && event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                            live.onArrived()
                        }
                    }
                    // ACTION_TICK, BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED: recompute.
                    else -> live.refresh()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.arcu.attnd.live.TICK"
        const val ACTION_HEART = "com.arcu.attnd.live.HEART"
        const val ACTION_SKIP = "com.arcu.attnd.live.SKIP"
        const val ACTION_DISMISS_URGENT = "com.arcu.attnd.live.DISMISS_URGENT"
        const val ACTION_DISMISSED = "com.arcu.attnd.live.DISMISSED"
        const val ACTION_GEOFENCE = "com.arcu.attnd.live.GEOFENCE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_ITEM_ID = "item_id"
    }
}
