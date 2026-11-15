package com.arcu.attnd.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.arcu.attnd.MainActivity
import com.arcu.attnd.domain.OpenLinks
import com.arcu.attnd.domain.OpenRequest

/**
 * Explicit intents to MainActivity carrying an internal open link ([OpenLinks]) as their data.
 * Used by dynamic shortcuts and returned to assistants by the AppFunctions.
 */
object OpenIntents {

    fun intent(context: Context, request: OpenRequest): Intent =
        Intent(Intent.ACTION_VIEW, OpenLinks.uri(request).toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * Immutable; the data URI differs per target, so PendingIntents for different items never
     * replace each other (request codes need not be unique).
     */
    fun pendingIntent(context: Context, request: OpenRequest): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The open request carried by [intent], if it is one of ours. */
    fun parse(intent: Intent?): OpenRequest? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.let { OpenLinks.parse(it.dataString) }
}
