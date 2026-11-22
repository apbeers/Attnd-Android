package com.arcu.attnd.domain

/**
 * Options → Permissions (iOS `PermissionsSection`): the system's answer for Location and
 * Notifications, and what the row offers. Pure; `data/PermissionsState.kt` reads the system.
 */
enum class PermissionStatus {
    /** Granted: the row is Attnd's own switch (location = the dot and walk times; notifications = the opt-in). */
    ALLOWED,

    /** Never asked, or Android may still ask again: the switch asks the system. */
    NOT_ASKED,

    /** Refused for good ("don't ask again", or twice): the row opens the system settings. */
    DENIED;

    companion object {
        /**
         * Android has no "not determined": a refused permission reads the same as one never asked,
         * except that after a request [askedBefore] is remembered. Asked before and no rationale to
         * show means Android won't ask again ([DENIED]); with a rationale it still asks.
         */
        fun of(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): PermissionStatus = when {
            granted -> ALLOWED
            !askedBefore || shouldShowRationale -> NOT_ASKED
            else -> DENIED
        }
    }
}

object PermissionRules {
    enum class Row { SWITCH, ASK_SWITCH, OPEN_SETTINGS }

    fun row(status: PermissionStatus): Row = when (status) {
        PermissionStatus.ALLOWED -> Row.SWITCH
        PermissionStatus.NOT_ASKED -> Row.ASK_SWITCH
        PermissionStatus.DENIED -> Row.OPEN_SETTINGS
    }

    fun locationStatusText(status: PermissionStatus): String = when (status) {
        PermissionStatus.ALLOWED -> "Allowed · your dot on the map and walk times"
        PermissionStatus.NOT_ASKED -> "Not asked yet · turning this on asks once"
        PermissionStatus.DENIED -> "Off in Settings"
    }

    fun notificationsStatusText(status: PermissionStatus): String = when (status) {
        PermissionStatus.ALLOWED -> "Allowed · organizer updates and reminders"
        PermissionStatus.NOT_ASKED -> "Not asked yet · turning this on asks once"
        PermissionStatus.DENIED -> "Off in Settings"
    }

    const val FOOTER = "When Android already allows them, Attnd turns both on for every event you join."

    /** What a new join turns on (iOS `applySystemPermissionsOnJoin`): whatever the system already allows; never a prompt. */
    data class OnJoin(val notifications: Boolean, val location: Boolean)

    fun onJoin(notificationsAllowed: Boolean, locationAllowed: Boolean, notificationsOn: Boolean, locationOn: Boolean): OnJoin =
        OnJoin(notifications = notificationsAllowed && !notificationsOn, location = locationAllowed && !locationOn)
}
