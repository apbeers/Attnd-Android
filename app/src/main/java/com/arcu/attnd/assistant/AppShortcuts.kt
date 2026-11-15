package com.arcu.attnd.assistant

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.arcu.attnd.R
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.OpenRequest
import com.arcu.attnd.domain.OpenTarget

/**
 * Launcher (long-press) dynamic shortcuts while an event is joined: Search, Happening now and
 * Updates. They open the app through the internal open links ([OpenIntents]); none while no event
 * is joined. Static `shortcuts.xml` App Actions capabilities are not used: they target Google
 * Assistant, and Gemini reaches the app through AppFunctions ([BaseAttndAppFunctionService]).
 */
class AppShortcuts(context: Context) {

    private val context = context.applicationContext

    /** Called for every change of the joined event (null = none). */
    fun update(bundle: EventBundle?) {
        try {
            if (bundle == null) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }
            val eventId = bundle.event.id
            val shortcuts = listOf(
                shortcut(ID_SEARCH, R.string.shortcut_search_short, R.string.shortcut_search_long, R.drawable.ic_shortcut_search, OpenRequest(eventId, OpenTarget.Search("")), rank = 0),
                shortcut(ID_NOW, R.string.shortcut_now_short, R.string.shortcut_now_long, R.drawable.ic_shortcut_now, OpenRequest(eventId, OpenTarget.Search("now")), rank = 1),
                shortcut(ID_UPDATES, R.string.shortcut_updates_short, R.string.shortcut_updates_long, R.drawable.ic_shortcut_updates, OpenRequest(eventId, OpenTarget.Notification(null)), rank = 2),
            )
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: RuntimeException) {
            // Rate limiting or a launcher without shortcut support: shortcuts are a convenience only.
            Log.w(TAG, "Couldn't update shortcuts", e)
        }
    }

    private fun shortcut(id: String, shortLabel: Int, longLabel: Int, icon: Int, request: OpenRequest, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(shortLabel))
            .setLongLabel(context.getString(longLabel))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(OpenIntents.intent(context, request).putExtra(EXTRA_SHORTCUT_ID, id))
            .setRank(rank)
            .build()

    companion object {
        private const val TAG = "AppShortcuts"
        private const val EXTRA_SHORTCUT_ID = "com.arcu.attnd.extra.SHORTCUT_ID"

        /** Tells the launcher a shortcut was used (it ranks shortcuts by use). Call for every launch intent. */
        fun reportUsed(context: Context, intent: android.content.Intent) {
            val id = intent.getStringExtra(EXTRA_SHORTCUT_ID) ?: return
            try {
                ShortcutManagerCompat.reportShortcutUsed(context, id)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Couldn't report shortcut use", e)
            }
        }
        private const val ID_SEARCH = "search"
        private const val ID_NOW = "now"
        private const val ID_UPDATES = "updates"
    }
}
