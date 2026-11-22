package com.arcu.attnd.domain

/**
 * The device id (a random UUID the app made, never a system identifier) that `join/`, `leave/`,
 * `devices/` and `X-Device-Id` carry, kept across a reinstall on the same phone (iOS
 * `DeviceIdentity`, the Keychain there; Block Store here, with cloud backup off so a new phone
 * gets a new id). Order: the app's own copy (an existing install), else Block Store's (a
 * reinstall), else a new one; whichever store lacks the id gets it. Pure Kotlin, unit tested.
 */
object DeviceIdentity {
    /** The Block Store key (letters, digits and `_` only). */
    const val BLOCK_STORE_KEY = "attnd_device_id"

    data class Resolution(val id: String, val savePrefs: Boolean, val saveBlockStore: Boolean)

    fun resolve(prefs: String?, blockStore: String?, make: () -> String): Resolution {
        val stored = valid(prefs)
        val kept = valid(blockStore)
        val id = stored ?: kept ?: make()
        return Resolution(id = id, savePrefs = stored != id, saveBlockStore = kept != id)
    }

    /** A trimmed id, or null when missing or blank. */
    fun valid(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
