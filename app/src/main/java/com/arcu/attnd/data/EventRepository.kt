package com.arcu.attnd.data

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.DeviceRequest
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.api.LeaveRequest
import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.AppUpdate
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.ImageCachePlan
import com.arcu.attnd.domain.LiveUpdates
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.NearbyEvent
import com.arcu.attnd.domain.PreviewPoll
import com.arcu.attnd.domain.UpdateLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Progress of the bundle sync, shown as refresh indicators and an offline banner. */
data class SyncState(
    val isRefreshing: Boolean = false,
    val lastSyncedAt: Long? = null,
    /** Message for the last failed refresh; cleared by the next successful one. */
    val error: String? = null,
    /** The last failure was a connectivity problem (the notice reads "Offline · last updated …"). */
    val offline: Boolean = false,
)

sealed interface RefreshResult {
    data object Updated : RefreshResult
    data object NotModified : RefreshResult
    data object NoEvent : RefreshResult
    /** The server no longer has the event (404); local state was cleared. */
    data object EventGone : RefreshResult
    data class Failed(val message: String) : RefreshResult

    /** For diagnostics: `updated`, `not_modified`, `no_event`, `gone`, `failed`. */
    val label: String get() = when (this) {
        Updated -> "updated"
        NotModified -> "not_modified"
        NoEvent -> "no_event"
        EventGone -> "gone"
        is Failed -> "failed"
    }
}

/**
 * Single source of truth for the joined event.
 *
 * - [bundle] is the current event bundle, or null when the device is not in an event.
 * - The raw bundle JSON is persisted to [cacheFile] so the app starts offline with the last content.
 * - Refreshes send `If-None-Match` with the stored ETag and treat `304` as "unchanged".
 * - API-007: the bundle's images are kept on disk by [images] (prefetched after every applied
 *   bundle and after a restore, deleted with the bundle).
 */
class EventRepository(
    private val api: AttndApi,
    private val prefs: DevicePrefs,
    private val cacheFile: File,
    private val appVersion: String,
    private val pushTokenProvider: suspend () -> String?,
    /** API-005: flushed before leaving or switching events so counts go to the old event. */
    private val usage: UsageReporter? = null,
    /** API-007: offline copies of the bundle's images. */
    private val images: ImageDiskCache? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _bundle = MutableStateFlow<EventBundle?>(null)
    val bundle: StateFlow<EventBundle?> = _bundle.asStateFlow()

    private val _sync = MutableStateFlow(SyncState(lastSyncedAt = prefs.lastSyncedAt))
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    private val refreshMutex = Mutex()

    private val _previewEventId = MutableStateFlow(prefs.previewEventId)

    /** API-026: the event this device is the organizer's preview phone for ([PreviewPoller] long-polls it). */
    val previewEventId: StateFlow<Int?> = _previewEventId.asStateFlow()

    /** Failed refreshes since the last successful one; lets silent polls ride out a blip. */
    @Volatile
    private var consecutiveFailures = 0

    /** The last refresh failed (offline, server error): the foreground poll retries every 15 s. */
    val lastRefreshFailed: Boolean get() = consecutiveFailures > 0

    /** Event id and time of the last successful bundle fetch (200 or 304), for [LiveUpdates.skipsRecentFetch]. */
    @Volatile
    private var lastFetch: Pair<Int, Long>? = null

    /** When `app-status/` was last asked this launch ([LiveUpdates.shouldCheckAppStatus]). */
    @Volatile
    private var lastAppStatusAt: Long? = null

    /** [LiveUpdates.deviceRegistrationKey] of the last `POST devices/` the server accepted this launch; null after a failure. */
    @Volatile
    private var lastRegistrationKey: String? = null

    /** The last `POST devices/` failed: sent again after the next successful refresh. */
    @Volatile
    private var deviceRegistrationPending = false

    private val leaveMutex = Mutex()

    /**
     * The push token the server last accepted (`POST devices/` or join), or null. API-007: while it
     * is set, `content_updated` pushes keep the bundle current and the foreground poll slows to a
     * safety net. In memory only, so each launch re-confirms it with the launch registration.
     */
    @Volatile
    private var registeredToken: String? = null

    /** The server has this device's current push token, so it will get `content_updated` pushes. */
    val hasPushToken: Boolean get() = registeredToken != null

    /** There is a push token the server hasn't confirmed yet (an earlier `POST devices/` failed). */
    val needsDeviceRegistration: Boolean get() = prefs.pushToken.let { it != null && it != registeredToken }

    private val _appUpdate = MutableStateFlow(AppUpdate())

    /**
     * Whether this build should update (API-003): the last `app-status` answer, forced to
     * REQUIRED once any API call has answered 410/426 (this build's API version is retired).
     */
    val appUpdate: Flow<AppUpdate> = combine(_appUpdate, api.updateRequired) { update, retired ->
        if (retired) update.copy(level = UpdateLevel.REQUIRED) else update
    }

    /**
     * Asks the server whether this build should update: once per launch, then at most hourly
     * ([LiveUpdates.shouldCheckAppStatus]; a retired API version is caught by every call's 410
     * anyway). Failures keep the last answer.
     */
    suspend fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (!LiveUpdates.shouldCheckAppStatus(lastAppStatusAt, now)) return
        lastAppStatusAt = now
        val started = System.nanoTime()
        val status = withContext(io) {
            try {
                api.appStatus(appVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.caught(LogCategory.NET, "app_status", e, LogLevel.WARNING)
                null
            }
        } ?: return
        AppLog.debug(
            LogCategory.NET, LogEvent.APP_STATUS, "app-status: ${status.update ?: "none"}",
            mapOf("status" to (status.update ?: "none"), "ms" to (System.nanoTime() - started) / 1_000_000),
        )
        val update = ApiMapper.appUpdate(status)
        if (update != _appUpdate.value) {
            AppLog.notice(
                LogCategory.LIFECYCLE, LogEvent.UPDATE_PROMPT, "Update: ${update.level.name.lowercase()}",
                mapOf("update" to update.level.name.lowercase(), "latest_version" to update.latestVersion),
            )
        }
        _appUpdate.value = update
    }

    val deviceId: String get() = prefs.deviceId
    val currentEventId: Int? get() = prefs.eventId

    /** Loads the persisted bundle for the joined event, if any. Safe to call more than once. */
    suspend fun restore(): EventBundle? = withContext(io) {
        _bundle.value?.let { return@withContext it }
        val eventId = prefs.eventId
        if (eventId == null) {
            images?.clear() // nothing joined: no images should be left from an earlier event
            return@withContext null
        }
        val started = System.nanoTime()
        val cached = readCache()?.takeIf { it.event.id == eventId }
        if (cached != null) {
            AppLog.info(
                LogCategory.STORAGE, LogEvent.BUNDLE_RESTORED, "Saved bundle restored",
                mapOf(
                    "bytes" to cacheFile.length(), "ms" to (System.nanoTime() - started) / 1_000_000, "content_version" to cached.contentVersion,
                    "age_s" to prefs.lastSyncedAt?.let { (System.currentTimeMillis() - it) / 1000 },
                ),
            )
        }
        if (cached == null) prefs.bundleEtag = null
        _bundle.value = cached
        cached?.let { prefetchImages(it) }
        cached
    }

    suspend fun nearby(lat: Double, lng: Double): List<NearbyEvent> = withContext(io) {
        api.nearbyEvents(lat, lng).results.orEmpty().mapNotNull { ApiMapper.nearby(it) }
    }

    /** Resolves an event code (or exact name). Throws `ApiException` 404 when nothing matches. */
    suspend fun lookup(codeOrName: String): Event = withContext(io) {
        ApiMapper.event(api.lookup(codeOrName.trim()).event) ?: throw IOException("Malformed lookup response")
    }

    /**
     * Joins [event], leaving any other event first, then downloads its bundle.
     * Throws when the join call fails; returns the result of the bundle download.
     */
    suspend fun join(event: Event, via: String = "search", previewToken: String? = null): RefreshResult = withContext(io) {
        val previous = prefs.eventId
        // Rejoining an event cancels a leave of it that is still queued.
        prefs.pendingLeaves = prefs.pendingLeaves - event.id
        if (previous != null && previous != event.id) {
            usage?.flushNow()
            AppLog.notice(LogCategory.LIFECYCLE, LogEvent.EVENT_LEFT, "Left event $previous for another", mapOf("event_id" to previous, "reason" to "switch"))
            // Leave the old event gracefully: queued and sent once the new one is joined (later
            // when offline); a failure never blocks joining the new one.
            prefs.pendingLeaves = prefs.pendingLeaves + previous
        }
        val token = pushToken()
        // API-026: a "Preview on my phone" link's token goes with this join only.
        val answer = api.join(event.id, JoinRequest(deviceId = deviceId, pushToken = token, appVersion = appVersion, previewToken = previewToken))
        if (token != null) registeredToken = token
        if (previous != event.id) {
            clearLocal()
            prefs.eventId = event.id
            sendPendingLeaves()
        }
        val preview = PreviewPoll.afterJoin(_previewEventId.value, event.id, answer.preview)
        if (preview != _previewEventId.value) {
            setPreviewEvent(preview)
            if (preview == event.id) {
                AppLog.notice(LogCategory.LIFECYCLE, LogEvent.EVENT_JOINED, "Joined as the organizer's preview phone", mapOf("event_id" to event.id, "via" to "preview"))
            }
        } else if (previewToken != null && answer.preview != true) {
            AppLog.info(LogCategory.LIFECYCLE, LogEvent.EVENT_JOINED, "Preview link joined as an attendee", mapOf("event_id" to event.id, "via" to "preview_refused"))
        }
        prefs.eventCode = event.eventCode
        AppLog.notice(
            LogCategory.LIFECYCLE, LogEvent.EVENT_JOINED, "Joined event ${event.id}",
            mapOf("event_id" to event.id, "via" to via),
        )
        refresh(source = "join")
    }

    /**
     * Leaves the current event. Local state is cleared even if the network call fails: the
     * `POST leave/` is queued ([DevicePrefs.pendingLeaves]) and sent now, then again at launch and
     * after a successful refresh until the server has it, so a leave made offline still stops the
     * event's pushes.
     */
    suspend fun leave() {
        withContext(io) {
            val eventId = prefs.eventId
            if (eventId != null) usage?.flushNow()
            if (eventId != null) AppLog.notice(LogCategory.LIFECYCLE, LogEvent.EVENT_LEFT, "Left event $eventId", mapOf("event_id" to eventId, "reason" to "user"))
            if (eventId != null) prefs.pendingLeaves = prefs.pendingLeaves + eventId
            clearLocal()
            sendPendingLeaves()
        }
    }

    /**
     * Sends the queued `POST leave/`s (never for the joined event). Each one the server
     * accepted, or answered 404 / 400 (the event is gone or refuses it), is dropped; the first other
     * failure (offline) stops the round and keeps the rest for the next one.
     */
    suspend fun sendPendingLeaves() {
        withContext(io) {
            if (!leaveMutex.tryLock()) return@withContext
            try {
                for (eventId in prefs.pendingLeaves) {
                    if (eventId == prefs.eventId) continue
                    val outcome = runSuspendCatching { api.leave(eventId, LeaveRequest(deviceId)) }
                    val code = (outcome.exceptionOrNull() as? ApiException)?.code
                    if (outcome.isSuccess || code == 404 || code == 400) {
                        prefs.pendingLeaves = prefs.pendingLeaves - eventId
                    } else {
                        outcome.exceptionOrNull()?.let { AppLog.caught(LogCategory.NET, "leave", it, LogLevel.NOTICE, mapOf("pending" to prefs.pendingLeaves.size)) }
                        break
                    }
                }
            } finally {
                leaveMutex.unlock()
            }
        }
    }

    /**
     * Server work that failed offline, sent once the server answers again (after a successful
     * bundle refresh): queued leaves and a device registration that didn't go through.
     */
    private suspend fun retryPendingServerWork() {
        if (prefs.pendingLeaves.isNotEmpty()) sendPendingLeaves()
        if (deviceRegistrationPending) registerDevice()
    }

    /**
     * Downloads the bundle unless the server says it is unchanged. Never throws (except cancellation).
     *
     * [silent] is for polls and push-triggered refreshes: it never shows [SyncState.isRefreshing].
     * A failure only sets [SyncState.error] (the offline notice) once [failuresBeforeError] refreshes
     * in a row have failed (see [LiveUpdates.failuresBeforeOfflineNotice]). Any success clears it.
     */
    suspend fun refresh(silent: Boolean = false, failuresBeforeError: Int = 1, source: String = "foreground"): RefreshResult = withContext(io) {
        val eventId = prefs.eventId ?: return@withContext RefreshResult.NoEvent
        // Right after a fetch (a join's, say) a foreground or poll refresh adds nothing; pushes,
        // pull to refresh and joins always go.
        if (LiveUpdates.skipsRecentFetch(source, lastFetch?.takeIf { it.first == eventId }?.second, System.currentTimeMillis())) {
            return@withContext RefreshResult.NotModified
        }
        val fetchedBefore = lastFetch
        val outcome = refreshMutex.withLock {
            if (prefs.eventId != eventId) return@withLock RefreshResult.NotModified
            if (!silent) _sync.update { it.copy(isRefreshing = true) }
            val result = try {
                AppLog.asyncSection("bundle_fetch") { fetchBundle(eventId, failuresBeforeError, source) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLog.caught(LogCategory.NET, "bundle.refresh", e, LogLevel.WARNING, mapOf("silent" to silent))
                fail("Can't reach Attnd right now. Showing saved content.", failuresBeforeError, offline = true)
            } catch (e: Exception) {
                AppLog.caught(LogCategory.NET, "bundle.refresh", e, fields = mapOf("silent" to silent))
                fail("Couldn't refresh (${e.javaClass.simpleName}).", failuresBeforeError)
            } finally {
                if (!silent) _sync.update { it.copy(isRefreshing = false) }
            }
            result
        }
        // The server answered (200 or 304): send what failed while it didn't.
        if (lastFetch !== fetchedBefore && lastFetch?.first == eventId) retryPendingServerWork()
        outcome
    }

    /**
     * API-007: a `content_updated` push for the joined event. Refreshes silently, after a random
     * delay (every device in the event gets the push at once: 0…[jitterMs] when the push sent
     * API-026 `jitter_ms`, else 0–10 s), unless the bundle is already
     * at [pushVersion] or newer. Works with the app in the background too: restores the saved
     * bundle first when the push started the process.
     */
    suspend fun onContentUpdated(pushVersion: Int?, jitterMs: Long? = null): RefreshResult? {
        val started = System.nanoTime()
        restore()
        val shown = _bundle.value?.contentVersion
        if (!LiveUpdates.shouldRefreshForPush(pushVersion, shown)) {
            AppLog.info(
                LogCategory.PUSH, LogEvent.PUSH_HANDLED, "content_updated $pushVersion: already at $shown",
                mapOf("type" to "content_updated", "action" to "skipped_not_newer", "ms" to (System.nanoTime() - started) / 1_000_000),
            )
            return null
        }
        val wait = LiveUpdates.pushRefreshDelayMs(jitterMs)
        AppLog.info(
            LogCategory.BACKGROUND, LogEvent.REFRESH_STARTED, "Refreshing for content_updated $pushVersion in $wait ms",
            mapOf("task" to "bundle", "reason" to "silent_push"),
        )
        delay(wait)
        // Another refresh (resume, poll, an earlier push) may have fetched it meanwhile.
        val result = if (!LiveUpdates.shouldRefreshForPush(pushVersion, _bundle.value?.contentVersion)) {
            RefreshResult.NotModified
        } else {
            refresh(silent = true, source = "push")
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (result is RefreshResult.Failed) {
            AppLog.warning(LogCategory.BACKGROUND, LogEvent.REFRESH_FAILED, "content_updated refresh failed", mapOf("task" to "bundle", "ms" to ms, "error" to result.label))
        } else {
            AppLog.info(
                LogCategory.BACKGROUND, LogEvent.REFRESH_FINISHED, "content_updated refresh: ${result.label}",
                mapOf("task" to "bundle", "ms" to ms, "result" to if (result == RefreshResult.Updated) "new_data" else "no_data"),
            )
        }
        AppLog.info(
            LogCategory.PUSH, LogEvent.PUSH_HANDLED, "content_updated $pushVersion handled",
            mapOf("type" to "content_updated", "action" to "refreshed", "ms" to ms, "delay_ms" to wait),
        )
        return result
    }

    /**
     * `POST devices/`: registers the device with its push token, app version and push opt-in.
     * Called on launch and whenever the token or the preference changes. API-007: the token is sent
     * whenever there is one, whatever the opt-in ([DevicePrefs.notificationsEnabled] goes as
     * `subscribed`), so silent `content_updated` pushes reach every device; without a token the
     * `push_token` key is left out (never sent empty, which would clear it on the server).
     */
    suspend fun registerDevice(): Boolean = withContext(io) {
        val token = pushToken()
        // Only when something changed since the last registration the server accepted this launch
        // (or it failed): iOS's network tidy-ups.
        val key = LiveUpdates.deviceRegistrationKey(token, prefs.notificationsEnabled, prefs.eventId)
        if (!LiveUpdates.shouldRegisterDevice(key, lastRegistrationKey)) return@withContext true
        val request = DeviceRequest(
            deviceId = deviceId,
            pushToken = token,
            appVersion = appVersion,
            subscribed = prefs.notificationsEnabled,
        )
        val outcome = runSuspendCatching { api.registerDevice(request) }
        val ok = outcome.isSuccess
        val error = outcome.exceptionOrNull()
        AppLog.log(
            if (ok) LogLevel.INFO else LogLevel.WARNING,
            LogCategory.NET, LogEvent.DEVICE_REGISTERED, if (ok) "Device registered" else "Device registration failed",
            mapOf(
                "status" to if (ok) 200 else (error as? ApiException)?.code ?: 0,
                "has_push_token" to (token != null), "subscribed" to prefs.notificationsEnabled,
            ),
        )
        when {
            ok -> registeredToken = token
            // The server still has the old token, which FCM has replaced: don't count on pushes.
            token != registeredToken -> registeredToken = null
        }
        lastRegistrationKey = key.takeIf { ok }
        deviceRegistrationPending = !ok
        ok
    }

    suspend fun onPushTokenChanged(token: String) {
        if (token.isBlank()) return
        AppLog.info(LogCategory.PUSH, LogEvent.PUSH_TOKEN, "New push token", mapOf("kind" to "fcm", "changed" to (token != prefs.pushToken), "length" to token.length))
        prefs.pushToken = token
        registerDevice()
    }

    suspend fun setNotificationsEnabled(enabled: Boolean): Boolean {
        prefs.notificationsEnabled = enabled
        return registerDevice()
    }

    /**
     * API-026 `GET events/<id>/changes/?since=` for the preview phone (a long-poll of about 25 s).
     * Throws on network and server errors; [PreviewPoller] waits 15 s then.
     */
    suspend fun changes(eventId: Int, since: Int?): PreviewPoll.Answer = withContext(io) {
        val dto = api.changes(eventId, since ?: 0, deviceId)
        PreviewPoll.Answer.of(dto.contentVersion, dto.changed, dto.preview, dto.retryAfter)
    }

    /** The server says this is no longer [eventId]'s preview phone (`preview: false`): back to the normal polling. */
    fun endPreview(eventId: Int) {
        if (_previewEventId.value != eventId) return
        AppLog.notice(LogCategory.LIFECYCLE, LogEvent.EVENT_JOINED, "No longer the preview phone", mapOf("event_id" to eventId, "via" to "preview_ended"))
        setPreviewEvent(null)
    }

    private fun setPreviewEvent(eventId: Int?) {
        prefs.previewEventId = eventId
        _previewEventId.value = eventId
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** Only send the ETag when the matching bundle is actually in memory; otherwise a 304 would leave us empty. */
    private fun etagFor(eventId: Int): String? =
        prefs.bundleEtag?.takeIf { _bundle.value?.event?.id == eventId }

    private suspend fun fetchBundle(eventId: Int, failuresBeforeError: Int, source: String): RefreshResult {
        val response = api.bundle(eventId, etagFor(eventId), deviceId)
        return when {
            response.code == 304 -> {
                AppLog.debug(
                    LogCategory.NET, LogEvent.BUNDLE_NOT_MODIFIED, "Bundle unchanged",
                    mapOf("content_version" to _bundle.value?.contentVersion, "source" to source),
                )
                markSynced(eventId)
                // Retries images an earlier pass couldn't get (a no-op once they're all on disk).
                _bundle.value?.let { prefetchImages(it) }
                RefreshResult.NotModified
            }
            response.code in 200..299 -> {
                val json = response.json
                val parsed = json?.let { ApiMapper.parseBundle(it) }
                when {
                    json == null || parsed == null -> fail("The server sent content this version can't read.", failuresBeforeError)
                    prefs.eventId != eventId -> RefreshResult.NotModified // left or switched meanwhile
                    else -> {
                        writeCache(json, parsed.contentVersion)
                        prefs.bundleEtag = response.etag
                        val previousVersion = _bundle.value?.contentVersion
                        _bundle.value = parsed
                        AppLog.info(
                            LogCategory.NET, LogEvent.BUNDLE_APPLIED, "Bundle ${parsed.contentVersion} applied",
                            mapOf(
                                "content_version" to parsed.contentVersion, "previous_version" to previousVersion, "source" to source,
                                "locations" to parsed.locations.size, "items" to parsed.schedule.size, "notifications" to parsed.notifications.size,
                            ),
                        )
                        markSynced(eventId)
                        prefetchImages(parsed)
                        RefreshResult.Updated
                    }
                }
            }
            response.code == 404 -> {
                AppLog.notice(LogCategory.LIFECYCLE, LogEvent.EVENT_LEFT, "Event $eventId is gone (404)", mapOf("event_id" to eventId, "reason" to "404"))
                if (prefs.eventId == eventId) clearLocal()
                _sync.update { it.copy(error = "This event is no longer available.") }
                RefreshResult.EventGone
            }
            else -> fail("Server error (${response.code}). Showing saved content.", failuresBeforeError)
        }
    }

    private fun markSynced(eventId: Int) {
        if (_sync.value.error != null) AppLog.notice(LogCategory.NET, LogEvent.OFFLINE_NOTICE, "Back online", mapOf("shown" to false, "failures" to consecutiveFailures))
        val now = System.currentTimeMillis()
        lastFetch = eventId to now
        prefs.lastSyncedAt = now
        consecutiveFailures = 0
        _sync.update { it.copy(lastSyncedAt = now, error = null, offline = false) }
    }

    private fun fail(message: String, failuresBeforeError: Int, offline: Boolean = false): RefreshResult {
        consecutiveFailures++
        if (consecutiveFailures >= failuresBeforeError) {
            if (_sync.value.error == null) AppLog.notice(LogCategory.NET, LogEvent.OFFLINE_NOTICE, "Offline notice shown", mapOf("shown" to true, "failures" to consecutiveFailures))
            _sync.update { it.copy(error = message, offline = offline) }
        }
        return RefreshResult.Failed(message)
    }

    private fun prefetchImages(bundle: EventBundle) {
        images?.prefetch(ImageCachePlan.referencedUrls(bundle))
    }

    private fun clearLocal() {
        prefs.clearEvent()
        setPreviewEvent(null)
        cacheFile.delete()
        images?.clear()
        _bundle.value = null
        consecutiveFailures = 0
        lastFetch = null
        _sync.value = SyncState()
    }

    private suspend fun pushToken(): String? {
        val fresh = runSuspendCatching { withTimeoutOrNull(PUSH_TOKEN_TIMEOUT_MS) { pushTokenProvider() } }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (fresh != null) prefs.pushToken = fresh
        return fresh ?: prefs.pushToken?.takeIf { it.isNotBlank() }
    }

    private fun readCache(): EventBundle? = try {
        if (cacheFile.exists()) ApiMapper.parseBundle(cacheFile.readText()) else null
    } catch (e: IOException) {
        AppLog.error(LogCategory.STORAGE, LogEvent.BUNDLE_FAILED, "Saved bundle unreadable", mapOf("op" to "restore", "error" to e.javaClass.simpleName))
        null
    }

    /** Writes via a temp file + rename so a crash never leaves a half-written bundle. */
    private fun writeCache(json: String, contentVersion: Int?) {
        val started = System.nanoTime()
        try {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(json)
                tmp.delete()
            }
            AppLog.debug(
                LogCategory.STORAGE, LogEvent.BUNDLE_SAVED, "Bundle saved",
                mapOf("bytes" to json.length, "ms" to (System.nanoTime() - started) / 1_000_000, "content_version" to contentVersion),
            )
        } catch (e: IOException) {
            // Caching is best effort; the in-memory bundle is still current.
            AppLog.error(LogCategory.STORAGE, LogEvent.BUNDLE_FAILED, "Bundle not saved", mapOf("op" to "save", "error" to e.javaClass.simpleName))
        }
    }

    private companion object {
        const val PUSH_TOKEN_TIMEOUT_MS = 10_000L
    }
}

/** runCatching that does not swallow coroutine cancellation. */
internal suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
