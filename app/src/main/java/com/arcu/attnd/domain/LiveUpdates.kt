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

    /** Pushes reach every device in the event at about the same time; spread the refreshes over this window. */
    const val MAX_PUSH_REFRESH_JITTER_MS = 10_000L

    fun pollIntervalMs(hasPushToken: Boolean): Long =
        if (hasPushToken) SAFETY_POLL_INTERVAL_MS else NO_PUSH_POLL_INTERVAL_MS

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

    /** Random delay of 0–10 s before a push-triggered refresh, so the event's devices don't hit the server at once. */
    fun pushRefreshDelayMs(random: Random = Random.Default): Long = random.nextLong(MAX_PUSH_REFRESH_JITTER_MS + 1)

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
