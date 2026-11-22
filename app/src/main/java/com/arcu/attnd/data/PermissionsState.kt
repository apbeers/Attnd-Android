package com.arcu.attnd.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * What the system currently allows the app (iOS: Options → Permissions' system status). Read on demand,
 * so a change in Settings counts at once. Phase 4 adds the never-asked / denied distinction for Options.
 */
class PermissionsState(context: Context) {
    private val appContext = context.applicationContext

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** `POST_NOTIFICATIONS`: organizer pushes, the Live Update and reminders. */
    val notifications: Boolean get() = granted(Manifest.permission.POST_NOTIFICATIONS)

    /** Fine or approximate location. */
    val location: Boolean
        get() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
}
