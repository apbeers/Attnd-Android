package com.arcu.attnd.data

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.data.api.AttndApi
import com.arcu.attnd.data.api.DeviceRequest
import com.arcu.attnd.data.api.JoinRequest
import com.arcu.attnd.data.api.LeaveRequest
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.NearbyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class EventRepository(
    private val api: AttndApi,
    private val prefs: DevicePrefs,
    private val cacheFile: File,
    private val appVersion: String,
    private val pushTokenProvider: suspend () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _bundle = MutableStateFlow<EventBundle?>(null)
    val bundle: StateFlow<EventBundle?> = _bundle.asStateFlow()

    private val _sync = MutableStateFlow(SyncState(lastSyncedAt = prefs.lastSyncedAt))
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    private val refreshMutex = Mutex()

    val deviceId: String get() = prefs.deviceId
    val currentEventId: Int? get() = prefs.eventId

    /** Loads the persisted bundle for the joined event, if any. Safe to call more than once. */
    suspend fun restore(): EventBundle? = withContext(io) {
        _bundle.value?.let { return@withContext it }
        val eventId = prefs.eventId ?: return@withContext null
        val cached = readCache()?.takeIf { it.event.id == eventId }
        if (cached == null) prefs.bundleEtag = null
        _bundle.value = cached
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
            // Leave the old event gracefully; failure must not block joining the new one.
            runSuspendCatching { api.leave(previous, LeaveRequest(deviceId)) }
        }
        api.join(event.id, JoinRequest(deviceId = deviceId, pushToken = pushToken(), appVersion = appVersion))
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
            clearLocal()
            if (eventId != null) {
                runSuspendCatching { api.leave(eventId, LeaveRequest(deviceId)) }
            }
            Unit
        }
    }

    /** Downloads the bundle unless the server says it is unchanged. Never throws (except cancellation). */
    suspend fun refresh(): RefreshResult = withContext(io) {
        val eventId = prefs.eventId ?: return@withContext RefreshResult.NoEvent
        refreshMutex.withLock {
            if (prefs.eventId != eventId) return@withLock RefreshResult.NotModified
            _sync.update { it.copy(isRefreshing = true) }
            try {
                fetchBundle(eventId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                fail("Can't reach Attnd right now. Showing saved content.")
            } catch (e: Exception) {
                fail("Couldn't refresh (${e.javaClass.simpleName}).")
            } finally {
                _sync.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * `POST devices/`: registers the device with its push token, app version and push opt-in.
     * Called on launch and whenever the token or the preference changes.
     */
    suspend fun registerDevice(): Boolean = withContext(io) {
        val request = DeviceRequest(
            deviceId = deviceId,
            pushToken = pushToken(),
            appVersion = appVersion,
            subscribed = prefs.notificationsEnabled,
        )
        runSuspendCatching { api.registerDevice(request) }.isSuccess
    }

    suspend fun onPushTokenChanged(token: String) {
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

    private suspend fun fetchBundle(eventId: Int): RefreshResult {
        val response = api.bundle(eventId, etagFor(eventId), deviceId)
        return when {
            response.code == 304 -> {
                markSynced()
                RefreshResult.NotModified
            }
            response.code in 200..299 -> {
                val json = response.json
                val parsed = json?.let { ApiMapper.parseBundle(it) }
                when {
                    json == null || parsed == null -> fail("The server sent content this version can't read.")
                    prefs.eventId != eventId -> RefreshResult.NotModified // left or switched meanwhile
                    else -> {
                        writeCache(json)
                        prefs.bundleEtag = response.etag
                        _bundle.value = parsed
                        markSynced()
                        RefreshResult.Updated
                    }
                }
            }
            response.code == 404 -> {
                if (prefs.eventId == eventId) clearLocal()
                _sync.update { it.copy(error = "This event is no longer available.") }
                RefreshResult.EventGone
            }
            else -> fail("Server error (${response.code}). Showing saved content.")
        }
    }

    private fun markSynced() {
        val now = System.currentTimeMillis()
        prefs.lastSyncedAt = now
        _sync.update { it.copy(lastSyncedAt = now, error = null) }
    }

    private fun fail(message: String): RefreshResult {
        _sync.update { it.copy(error = message) }
        return RefreshResult.Failed(message)
    }

    private fun clearLocal() {
        prefs.clearEvent()
        cacheFile.delete()
        _bundle.value = null
        _sync.value = SyncState()
    }

    private suspend fun pushToken(): String? {
        val fresh = runSuspendCatching { withTimeoutOrNull(PUSH_TOKEN_TIMEOUT_MS) { pushTokenProvider() } }.getOrNull()
        if (fresh != null) prefs.pushToken = fresh
        return fresh ?: prefs.pushToken
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
