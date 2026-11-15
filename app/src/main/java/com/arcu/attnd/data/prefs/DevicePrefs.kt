package com.arcu.attnd.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Small key/value settings that must survive restarts. The bundle itself lives in a file
 * (see EventRepository); this only holds identifiers and preferences.
 */
class DevicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stable per-install device id, generated on first use. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also { prefs.edit { putString(KEY_DEVICE_ID, it) } }
        }

    /** The joined event, or null when the device is not in an event. */
    var eventId: Int?
        get() = prefs.getInt(KEY_EVENT_ID, NO_EVENT).takeIf { it != NO_EVENT }
        set(value) = prefs.edit { if (value == null) remove(KEY_EVENT_ID) else putInt(KEY_EVENT_ID, value) }

    var eventCode: String?
        get() = prefs.getString(KEY_EVENT_CODE, null)
        set(value) = prefs.edit { putString(KEY_EVENT_CODE, value) }

    /** ETag of the bundle stored on disk. */
    var bundleEtag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit { putString(KEY_ETAG, value) }

    var lastSyncedAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }
        set(value) = prefs.edit { if (value == null) remove(KEY_LAST_SYNC) else putLong(KEY_LAST_SYNC, value) }

    /** The `latest_version` whose "update available" banner the user dismissed. */
    var dismissedUpdateVersion: String?
        get() = prefs.getString(KEY_DISMISSED_UPDATE, null)
        set(value) = prefs.edit { putString(KEY_DISMISSED_UPDATE, value) }

    var pushToken: String?
        get() = prefs.getString(KEY_PUSH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_PUSH_TOKEN, value) }

    /** The user's push opt-in (also requires the POST_NOTIFICATIONS permission to be granted). */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    /** Map blue-dot toggle. */
    var showUserLocation: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOCATION, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LOCATION, value) }

    /** Whether the Play install referrer has been checked (only done once per install). */
    var installReferrerChecked: Boolean
        get() = prefs.getBoolean(KEY_REFERRER_CHECKED, false)
        set(value) = prefs.edit { putBoolean(KEY_REFERRER_CHECKED, value) }

    /** Hearted locations / schedule items of an event ([com.arcu.attnd.domain.Hearts] text). Kept after leaving. */
    fun hearts(eventId: Int): String? = prefs.getString("$KEY_HEARTS$eventId", null)

    fun setHearts(eventId: Int, value: String) = prefs.edit {
        if (value.isEmpty()) remove("$KEY_HEARTS$eventId") else putString("$KEY_HEARTS$eventId", value)
    }

    /** When the Updates tab of an event was last seen (epoch ms); null = never. */
    fun updatesSeenAt(eventId: Int): Long? = prefs.getLong("$KEY_UPDATES_SEEN$eventId", 0L).takeIf { it > 0L }

    fun setUpdatesSeenAt(eventId: Int, epochMillis: Long) = prefs.edit { putLong("$KEY_UPDATES_SEEN$eventId", epochMillis) }

    /** The organizer update whose banner the user dismissed, per event. */
    fun dismissedUpdateBanner(eventId: Int): Int? =
        prefs.getInt("$KEY_UPDATE_BANNER$eventId", NO_EVENT).takeIf { it != NO_EVENT }

    fun setDismissedUpdateBanner(eventId: Int, notificationId: Int) =
        prefs.edit { putInt("$KEY_UPDATE_BANNER$eventId", notificationId) }

    fun clearEvent() = prefs.edit {
        remove(KEY_EVENT_ID)
        remove(KEY_EVENT_CODE)
        remove(KEY_ETAG)
        remove(KEY_LAST_SYNC)
    }

    companion object {
        const val PREFS_NAME = "attnd_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_EVENT_CODE = "event_code"
        private const val KEY_ETAG = "bundle_etag"
        private const val KEY_LAST_SYNC = "bundle_last_sync"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_DISMISSED_UPDATE = "dismissed_update_version"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_SHOW_LOCATION = "show_user_location"
        private const val KEY_REFERRER_CHECKED = "install_referrer_checked"
        private const val KEY_HEARTS = "hearts_"
        private const val KEY_UPDATES_SEEN = "updates_seen_"
        private const val KEY_UPDATE_BANNER = "update_banner_dismissed_"
        private const val NO_EVENT = -1
    }
}
