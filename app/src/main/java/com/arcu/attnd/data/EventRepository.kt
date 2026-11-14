package com.arcu.attnd.data

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.DeviceRequest
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.api.LeaveRequest
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.AppUpdate
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.ImageCachePlan
import com.arcu.attnd.domain.LiveUpdates
import com.arcu.attnd.domain.NearbyEvent
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

    /** Failed refreshes since the last successful one; lets silent polls ride out a blip. */
    private var consecutiveFailures = 0

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

    /** Asks the server whether this build should update. Failures keep the last answer. */
    suspend fun checkForUpdate() {
        val status = withContext(io) {
            try {
                api.appStatus(appVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return
        _appUpdate.value = ApiMapper.appUpdate(status)
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
        val cached = readCache()?.takeIf { it.event.id == eventId }
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
    suspend fun join(event: Event): RefreshResult = withContext(io) {
        val previous = prefs.eventId
        if (previous != null && previous != event.id) {
            usage?.flushNow()
            // Leave the old event gracefully; failure must not block joining the new one.
            runSuspendCatching { api.leave(previous, LeaveRequest(deviceId)) }
        }
        val token = pushToken()
        api.join(event.id, JoinRequest(deviceId = deviceId, pushToken = token, appVersion = appVersion))
        if (token != null) registeredToken = token
        if (previous != event.id) {
            clearLocal()
            prefs.eventId = event.id
        }
        prefs.eventCode = event.eventCode
        refresh()
    }

    /** Leaves the current event. Local state is cleared even if the network call fails. */
    suspend fun leave() {
        withContext(io) {
            val eventId = prefs.eventId
            if (eventId != null) usage?.flushNow()
            clearLocal()
            if (eventId != null) {
                runSuspendCatching { api.leave(eventId, LeaveRequest(deviceId)) }
            }
            Unit
        }
    }

    /**
     * Downloads the bundle unless the server says it is unchanged. Never throws (except cancellation).
     *
     * [silent] is for polls and push-triggered refreshes: it never shows [SyncState.isRefreshing].
     * A failure only sets [SyncState.error] (the offline notice) once [failuresBeforeError] refreshes
     * in a row have failed (see [LiveUpdates.failuresBeforeOfflineNotice]). Any success clears it.
     */
    suspend fun refresh(silent: Boolean = false, failuresBeforeError: Int = 1): RefreshResult = withContext(io) {
        val eventId = prefs.eventId ?: return@withContext RefreshResult.NoEvent
        refreshMutex.withLock {
            if (prefs.eventId != eventId) return@withLock RefreshResult.NotModified
            if (!silent) _sync.update { it.copy(isRefreshing = true) }
            try {
                fetchBundle(eventId, failuresBeforeError)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                fail("Can't reach Attnd right now. Showing saved content.", failuresBeforeError, offline = true)
            } catch (e: Exception) {
                fail("Couldn't refresh (${e.javaClass.simpleName}).", failuresBeforeError)
            } finally {
                if (!silent) _sync.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * API-007: a `content_updated` push for the joined event. Refreshes silently, after a random
     * 0–10 s delay (every device in the event gets the push at once), unless the bundle is already
     * at [pushVersion] or newer. Works with the app in the background too: restores the saved
     * bundle first when the push started the process.
     */
    suspend fun onContentUpdated(pushVersion: Int?) {
        restore()
        if (!LiveUpdates.shouldRefreshForPush(pushVersion, _bundle.value?.contentVersion)) return
        delay(LiveUpdates.pushRefreshDelayMs())
        // Another refresh (resume, poll, an earlier push) may have fetched it meanwhile.
        if (!LiveUpdates.shouldRefreshForPush(pushVersion, _bundle.value?.contentVersion)) return
        refresh(silent = true)
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
        val request = DeviceRequest(
            deviceId = deviceId,
            pushToken = token,
            appVersion = appVersion,
            subscribed = prefs.notificationsEnabled,
        )
        val ok = runSuspendCatching { api.registerDevice(request) }.isSuccess
        when {
            ok -> registeredToken = token
            // The server still has the old token, which FCM has replaced: don't count on pushes.
            token != registeredToken -> registeredToken = null
        }
        ok
    }

    suspend fun onPushTokenChanged(token: String) {
        if (token.isBlank()) return
        prefs.pushToken = token
        registerDevice()
    }

    suspend fun setNotificationsEnabled(enabled: Boolean): Boolean {
        prefs.notificationsEnabled = enabled
        return registerDevice()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** Only send the ETag when the matching bundle is actually in memory; otherwise a 304 would leave us empty. */
    private fun etagFor(eventId: Int): String? =
        prefs.bundleEtag?.takeIf { _bundle.value?.event?.id == eventId }

    private suspend fun fetchBundle(eventId: Int, failuresBeforeError: Int): RefreshResult {
        val response = api.bundle(eventId, etagFor(eventId), deviceId)
        return when {
            response.code == 304 -> {
                markSynced()
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
                        writeCache(json)
                        prefs.bundleEtag = response.etag
                        _bundle.value = parsed
                        markSynced()
                        prefetchImages(parsed)
                        RefreshResult.Updated
                    }
                }
            }
            response.code == 404 -> {
                if (prefs.eventId == eventId) clearLocal()
                _sync.update { it.copy(error = "This event is no longer available.") }
                RefreshResult.EventGone
            }
            else -> fail("Server error (${response.code}). Showing saved content.", failuresBeforeError)
        }
    }

    private fun markSynced() {
        val now = System.currentTimeMillis()
        prefs.lastSyncedAt = now
        consecutiveFailures = 0
        _sync.update { it.copy(lastSyncedAt = now, error = null, offline = false) }
    }

    private fun fail(message: String, failuresBeforeError: Int, offline: Boolean = false): RefreshResult {
        consecutiveFailures++
        if (consecutiveFailures >= failuresBeforeError) _sync.update { it.copy(error = message, offline = offline) }
        return RefreshResult.Failed(message)
    }

    private fun prefetchImages(bundle: EventBundle) {
        images?.prefetch(ImageCachePlan.referencedUrls(bundle))
    }

    private fun clearLocal() {
        prefs.clearEvent()
        cacheFile.delete()
        images?.clear()
        _bundle.value = null
        consecutiveFailures = 0
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
    } catch (_: IOException) {
        null
    }

    /** Writes via a temp file + rename so a crash never leaves a half-written bundle. */
    private fun writeCache(json: String) {
        try {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(json)
                tmp.delete()
            }
        } catch (_: IOException) {
            // Caching is best effort; the in-memory bundle is still current.
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
