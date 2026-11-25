package com.arcu.attnd.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.arcu.attnd.data.prefs.DevicePrefs

/**
 * What the system currently allows the app (iOS: Options → Permissions' system status). Read on demand,
 * so a change in Settings counts at once. [asked] tells "never asked" from "denied" (Android has no
 * such state): every request Attnd makes is recorded with [markAsked].
 */
class PermissionsState(context: Context, private val prefs: DevicePrefs) {
    private val appContext = context.applicationContext

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** `POST_NOTIFICATIONS`: organizer pushes, the Live Update and reminders. */
    val notifications: Boolean get() = granted(Manifest.permission.POST_NOTIFICATIONS)

    /** Fine or approximate location. */
    val location: Boolean
        get() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun asked(permission: String): Boolean = prefs.permissionAsked(permission)

    fun markAsked(permission: String) = prefs.setPermissionAsked(permission)
}
