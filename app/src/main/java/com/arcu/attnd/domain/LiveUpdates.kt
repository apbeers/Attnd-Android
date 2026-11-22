package com.arcu.attnd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * API-007: how the app keeps the joined event current. `content_updated` pushes drive refreshes;
 * the poll while the app is open is only a safety net (or the fallback when this device can't get
 * pushes). Pure Kotlin, unit tested.
 */
object LiveUpdates {

    /** Safety poll while resumed when the server has our push token. */
    const val SAFETY_POLL_INTERVAL_MS = 5 * 60 * 1000L

    /** Poll while resumed when there is no registered push token (no Firebase, no Play services, token fetch failed). */
    const val NO_PUSH_POLL_INTERVAL_MS = 60 * 1000L

    /** Poll while resumed after a failed refresh (offline, server down), whatever the push state. */
    const val AFTER_FAILURE_POLL_INTERVAL_MS = 15 * 1000L

    /** Pushes reach every device in the event at about the same time; spread the refreshes over this window. */
    const val MAX_PUSH_REFRESH_JITTER_MS = 10_000L

    /** API-026: the largest `jitter_ms` a push may ask for. */
    const val MAX_PUSH_JITTER_CAP_MS = 60_000L

    /** `GET app-status/` once per launch, then at most this often on foreground (iOS network tidy-ups). */
    const val APP_STATUS_INTERVAL_MS = 60 * 60 * 1000L

    /** A foreground or poll refresh this soon after a successful fetch adds nothing and is skipped. */
    const val RECENT_FETCH_SKIP_MS = 15 * 1000L

    /** The refresh sources that skip within [RECENT_FETCH_SKIP_MS]; pushes, pull to refresh and joins always go. */
    val SKIPPABLE_SOURCES: Set<String> = setOf("foreground", "poll")

    /**
     * 5 min with a registered push token, 60 s without; [AFTER_FAILURE_POLL_INTERVAL_MS] while the
     * last refresh failed, so the app notices quickly when the server answers again.
     */
    fun pollIntervalMs(hasPushToken: Boolean, lastRefreshFailed: Boolean = false): Long = when {
        lastRefreshFailed -> AFTER_FAILURE_POLL_INTERVAL_MS
        hasPushToken -> SAFETY_POLL_INTERVAL_MS
        else -> NO_PUSH_POLL_INTERVAL_MS
    }

    /** Whether to ask `app-status/` now: never asked this launch, or [APP_STATUS_INTERVAL_MS] since the last time. */
    fun shouldCheckAppStatus(lastCheckedAt: Long?, now: Long): Boolean =
        lastCheckedAt == null || now - lastCheckedAt !in 0 until APP_STATUS_INTERVAL_MS

    /**
     * Whether a bundle refresh from [source] can be skipped: a foreground or poll refresh within
     * [RECENT_FETCH_SKIP_MS] of a successful fetch (200 or 304) of the same event.
     */
    fun skipsRecentFetch(source: String, lastFetchAt: Long?, now: Long): Boolean =
        source in SKIPPABLE_SOURCES && lastFetchAt != null && now - lastFetchAt in 0 until RECENT_FETCH_SKIP_MS

    /** What `POST devices/` tells the server; it is only sent again when this changes (or the last one failed). */
    fun deviceRegistrationKey(pushToken: String?, subscribed: Boolean, eventId: Int?): String =
        "${pushToken?.takeIf { it.isNotBlank() } ?: "-"}|$subscribed|${eventId ?: 0}"

    /** [lastAccepted] = the key of the last registration the server accepted this launch (null: none, or it failed). */
    fun shouldRegisterDevice(key: String, lastAccepted: String?): Boolean = key != lastAccepted

    /** API-026 `jitter_ms` from a `content_updated` push (FCM data values are strings), clamped to 0–60 s; null when missing or not a number. */
    fun pushJitterMs(raw: String?): Long? =
        raw?.trim()?.toLongOrNull()?.coerceIn(0L, MAX_PUSH_JITTER_CAP_MS)

    /**
     * Failed polls in a row before the offline notice shows. Fast polls (under a minute) may ride
     * out a blip or two; at a minute or more, one failure already means the content may be a
     * minute or more stale, so the notice shows straight away. Any success clears it.
     */
    fun failuresBeforeOfflineNotice(pollIntervalMs: Long): Int = if (pollIntervalMs >= 60_000L) 1 else 3

    /**
     * Whether a `content_updated` push with [pushVersion] should trigger a refresh. Unknown versions
     * (an older server, nothing loaded yet) refresh; a push for a version we already have doesn't.
     */
    fun shouldRefreshForPush(pushVersion: Int?, currentVersion: Int?): Boolean =
        pushVersion == null || currentVersion == null || pushVersion > currentVersion

    /**
     * Random delay before a push-triggered refresh, so the event's devices don't hit the server at
     * once: 0…[maxMs] when the push sent `jitter_ms` (API-026: the server scales it to the event,
     * 0 = at once, e.g. a preview phone), else 0–10 s.
     */
    fun pushRefreshDelayMs(maxMs: Long? = null, random: Random = Random.Default): Long {
        val limit = maxMs?.coerceIn(0L, MAX_PUSH_JITTER_CAP_MS) ?: MAX_PUSH_REFRESH_JITTER_MS
        return if (limit == 0L) 0L else random.nextLong(limit + 1)
    }

    /**
     * The offline notice text: "Offline · last updated 2:14 PM" (the time of the last successful
     * refresh in the device's [zone], with the date when it wasn't today) for a connectivity failure,
     * otherwise [error] followed by "Last updated …". Without a successful refresh it is just [error].
     */
    fun offlineNoticeText(
        error: String,
        offline: Boolean,
        lastSyncedAt: Long?,
        now: Long,
        zone: ZoneId,
        is24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        lastSyncedAt ?: return error
        val synced = Instant.ofEpochMilli(lastSyncedAt).atZone(zone)
        val sameDay = synced.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = if (is24Hour) "H:mm" else "h:mm a"
        val pattern = if (sameDay) time else "MMM d, $time"
        val text = DateTimeFormatter.ofPattern(pattern, locale).format(synced)
        return if (offline) "Offline · last updated $text" else "$error Last updated $text."
    }
}
