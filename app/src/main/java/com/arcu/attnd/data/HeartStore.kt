package com.arcu.attnd.data

import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.SearchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Hearted schedule items and locations, per event, on the device only (SharedPreferences, which Auto
 * Backup includes). Shared by the UI and the AppFunctions service; [version] changes on every write so
 * the UI picks up hearts set by an assistant too.
 */
class HeartStore(private val prefs: DevicePrefs) {

    private val cache = HashMap<Int, Hearts>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Synchronized
    fun hearts(eventId: Int): Hearts = cache.getOrPut(eventId) { Hearts.decode(prefs.hearts(eventId)) }

    /** Sets one heart; returns true when that changed anything. */
    @Synchronized
    fun set(eventId: Int, kind: SearchKind, id: Int, hearted: Boolean): Boolean {
        val current = hearts(eventId)
        val updated = current.with(kind, id, hearted)
        if (updated == current) return false
        cache[eventId] = updated
        prefs.setHearts(eventId, updated.encode())
        _version.update { it + 1 }
        return true
    }
}
