package com.arcu.attnd.data.log

import android.Manifest
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent

/** Permission answers (`location.permission`, `push.permission`), logged where they were asked. */
object PermissionLog {
    fun location(where: String, result: Map<String, Boolean>) {
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        AppLog.notice(
            LogCategory.LOCATION, LogEvent.LOCATION_PERMISSION,
            "Location permission ${if (fine) "precise" else if (coarse) "approximate" else "denied"} ($where)",
            mapOf(
                "status" to if (fine || coarse) "granted" else "denied",
                "precision" to if (fine) "precise" else "approximate",
                "background" to false, "asked" to true,
            ),
        )
    }

    fun notifications(where: String, granted: Boolean) {
        AppLog.notice(
            LogCategory.PUSH, LogEvent.PUSH_PERMISSION, "Notification permission ${if (granted) "granted" else "denied"} ($where)",
            mapOf("status" to if (granted) "granted" else "denied", "asked" to true),
        )
    }

    fun camera(granted: Boolean) {
        AppLog.debug(
            LogCategory.UI, LogEvent.UI_ACTION, "Camera permission ${if (granted) "granted" else "denied"}",
            mapOf("name" to if (granted) "camera_allowed" else "camera_denied"),
        )
    }
}
