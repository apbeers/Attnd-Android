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

    /**
     * Events left while `POST leave/` couldn't be sent (offline, server down). Kept until the server
     * accepted it (it only detaches the device from that event, so a late leave never affects a newer
     * join); rejoining the event drops it.
     */
    var pendingLeaves: Set<Int>
        get() = prefs.getString(KEY_PENDING_LEAVES, null).orEmpty()
            .split(',').mapNotNullTo(LinkedHashSet()) { it.trim().toIntOrNull() }
        set(value) = prefs.edit {
            if (value.isEmpty()) remove(KEY_PENDING_LEAVES) else putString(KEY_PENDING_LEAVES, value.joinToString(","))
        }

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

    // ── Planner, Live Update, onboarding (per event) ──────────────────────────

    /** The stored plan ([com.arcu.attnd.domain.PlanCodec] JSON). Kept after leaving, like hearts. */
    fun plan(eventId: Int): String? = prefs.getString("$KEY_PLAN$eventId", null)

    fun setPlan(eventId: Int, value: String?) = prefs.edit {
        if (value == null) remove("$KEY_PLAN$eventId") else putString("$KEY_PLAN$eventId", value)
    }

    /** "Follow on Lock Screen": null = never set (the default applies). */
    fun followLive(eventId: Int): Boolean? =
        if (prefs.contains("$KEY_FOLLOW$eventId")) prefs.getBoolean("$KEY_FOLLOW$eventId", true) else null

    fun setFollowLive(eventId: Int, follow: Boolean) = prefs.edit { putBoolean("$KEY_FOLLOW$eventId", follow) }

    /** Schedule items skipped from the Live Update, as `<date>|<id>,<id>` (only that event day counts). */
    fun liveSkipped(eventId: Int): String? = prefs.getString("$KEY_SKIPPED$eventId", null)

    fun setLiveSkipped(eventId: Int, value: String) = prefs.edit { putString("$KEY_SKIPPED$eventId", value) }

    /** The day (ISO date) on which the user swiped the Live Update away: not reposted that day. */
    fun liveDismissedDay(eventId: Int): String? = prefs.getString("$KEY_LIVE_DISMISSED$eventId", null)

    fun setLiveDismissedDay(eventId: Int, day: String?) = prefs.edit {
        if (day == null) remove("$KEY_LIVE_DISMISSED$eventId") else putString("$KEY_LIVE_DISMISSED$eventId", day)
    }

    /** API-011: the latest urgent announcement, as `id|sentAtEpochMs|title|body` (body may contain `|`). */
    fun urgent(eventId: Int): String? = prefs.getString("$KEY_URGENT$eventId", null)

    fun setUrgent(eventId: Int, value: String) = prefs.edit { putString("$KEY_URGENT$eventId", value) }

    fun dismissedUrgent(eventId: Int): Int? = prefs.getInt("$KEY_URGENT_DISMISSED$eventId", NO_EVENT).takeIf { it != NO_EVENT }

    fun setDismissedUrgent(eventId: Int, notificationId: Int) = prefs.edit { putInt("$KEY_URGENT_DISMISSED$eventId", notificationId) }

    /** Schedule items whose reminder was already shown (comma-separated ids). */
    fun remindersShown(eventId: Int): String? = prefs.getString("$KEY_REMINDERS$eventId", null)

    fun setRemindersShown(eventId: Int, value: String) = prefs.edit { putString("$KEY_REMINDERS$eventId", value) }

    /** Whether the onboarding tour was shown (or skipped) for an event. */
    fun onboardingSeen(eventId: Int): Boolean = prefs.getBoolean("$KEY_ONBOARDING$eventId", false)

    fun setOnboardingSeen(eventId: Int) = prefs.edit { putBoolean("$KEY_ONBOARDING$eventId", true) }

    /** Explore's switch (Map | Schedule | Places) per event: the view last shown (a `Tab` key). */
    fun exploreView(eventId: Int): String? = prefs.getString("$KEY_EXPLORE_VIEW$eventId", null)

    fun setExploreView(eventId: Int, key: String) = prefs.edit { putString("$KEY_EXPLORE_VIEW$eventId", key) }

    // Event assistant (assistant spec §4, §7): stored with the plan per event, kept after leaving.

    /** The plan's undo stack (`PlanHistory.encode`). */
    fun planHistory(eventId: Int): String? = prefs.getString("$KEY_PLAN_HISTORY$eventId", null)

    fun setPlanHistory(eventId: Int, value: String?) = prefs.edit {
        if (value == null) remove("$KEY_PLAN_HISTORY$eventId") else putString("$KEY_PLAN_HISTORY$eventId", value)
    }

    /** The model's conversation (`AssistantConversation.encode`). */
    fun assistantConversation(eventId: Int): String? = prefs.getString("$KEY_CONVERSATION$eventId", null)

    fun setAssistantConversation(eventId: Int, value: String?) = prefs.edit {
        if (value == null) remove("$KEY_CONVERSATION$eventId") else putString("$KEY_CONVERSATION$eventId", value)
    }

    /** The assistant screen's transcript (`AssistantTranscript.encode`). */
    fun assistantTranscript(eventId: Int): String? = prefs.getString("$KEY_TRANSCRIPT$eventId", null)

    fun setAssistantTranscript(eventId: Int, value: String?) = prefs.edit {
        if (value == null) remove("$KEY_TRANSCRIPT$eventId") else putString("$KEY_TRANSCRIPT$eventId", value)
    }

    /** Reminders before liked / planned items; null = not chosen (follows "Follow on Lock Screen"). */
    fun reminders(eventId: Int): Boolean? =
        if (prefs.contains("$KEY_REMINDERS_ON$eventId")) prefs.getBoolean("$KEY_REMINDERS_ON$eventId", true) else null

    fun setReminders(eventId: Int, on: Boolean) = prefs.edit { putBoolean("$KEY_REMINDERS_ON$eventId", on) }

    /** Shared plans opened for the event (API-015): one line of comma-separated ids per plan. */
    fun sharedPlans(eventId: Int): String? = prefs.getString("$KEY_SHARED$eventId", null)

    fun setSharedPlans(eventId: Int, value: String) = prefs.edit { putString("$KEY_SHARED$eventId", value) }

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
        private const val KEY_PENDING_LEAVES = "pending_leaves"
        private const val KEY_HEARTS = "hearts_"
        private const val KEY_UPDATES_SEEN = "updates_seen_"
        private const val KEY_UPDATE_BANNER = "update_banner_dismissed_"
        private const val KEY_PLAN = "plan_"
        private const val KEY_FOLLOW = "follow_live_"
        private const val KEY_SKIPPED = "live_skipped_"
        private const val KEY_LIVE_DISMISSED = "live_dismissed_"
        private const val KEY_URGENT = "urgent_"
        private const val KEY_URGENT_DISMISSED = "urgent_dismissed_"
        private const val KEY_REMINDERS = "reminders_shown_"
        private const val KEY_ONBOARDING = "onboarding_seen_"
        private const val KEY_EXPLORE_VIEW = "explore_view_"
        private const val KEY_PLAN_HISTORY = "plan_history_"
        private const val KEY_CONVERSATION = "assistant_conversation_"
        private const val KEY_TRANSCRIPT = "assistant_transcript_"
        private const val KEY_REMINDERS_ON = "reminders_on_"
        private const val KEY_SHARED = "shared_plans_"
        private const val NO_EVENT = -1
    }
}
