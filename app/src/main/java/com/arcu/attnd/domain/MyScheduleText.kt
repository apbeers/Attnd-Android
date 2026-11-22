package com.arcu.attnd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words of My schedule in the UI (iOS `MyScheduleText`; no "like" anywhere in the UI since the
 * one-list change). Identifiers, storage keys and the API-009 metric names keep "heart".
 */
object MyScheduleText {
    const val TITLE = MySchedule.TITLE
    const val ADD = "Add"
    const val ADD_LONG = "Add to my schedule"
    const val IN_SCHEDULE = "In my schedule"
    const val REMOVE = "Remove from My Schedule"
    const val SAVED_PLACES = "Saved Places"
    const val REMIND_ME = "Remind Me"
    const val EMPTY = "Add things to your schedule to see them here"
    const val NOTHING_YET = "Nothing in your schedule yet."
    const val TIME_DIALOG_TITLE = "When?"
    const val TIME_DIALOG_MESSAGE = "Pick a time for it in your schedule, or keep it saved without one."

    /** The heart's accessibility label: "Add Main Stage to my schedule" / "Main Stage, in my schedule". */
    fun heartDescription(name: String?, added: Boolean): String = when {
        name == null -> if (added) IN_SCHEDULE else ADD_LONG
        added -> "$name, $IN_SCHEDULE"
        else -> "Add $name to my schedule"
    }

    /** A reminder choice: "Off", "1 hour before", "15 min before". */
    fun reminder(minutes: Int): String = if (minutes <= 0) "Off" else MySchedule.reminderText(minutes)

    /** The filled heart's time item for a place: "Set a Time…" / "Change Time…". */
    fun timeAction(hasTime: Boolean): String = if (hasTime) "Change Time…" else "Set a Time…"

    /** The "When?" dialog's keep-without-a-time button. */
    fun saveWithoutTime(hasTime: Boolean): String = if (hasTime) "No Time, Just Save It" else "Just Save It"

    /** A "When?" option: "7:15 PM", "7:15 PM (Suggested)". */
    fun timeOption(time: Instant, suggested: Instant?, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val text = time.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", locale))
        return if (time == suggested) "$text (Suggested)" else text
    }

    /** The Undo snackbar after a heart add: "Taco Row added to My Schedule". */
    fun added(name: String?): String = "${name ?: "It"} added to My Schedule"

    /** The Undo snackbar after a remove: "Taco Row removed from My Schedule". */
    fun removed(name: String?): String = "${name ?: "It"} removed from My Schedule"

    /** The Schedule tab's organizer change: "X moved to 8:00 PM." / "X changed." / "X was cancelled." */
    fun organizerChange(change: OrganizerChange, zone: ZoneId, locale: Locale = Locale.getDefault()): String = when (val kind = change.kind) {
        is OrganizerChange.Kind.Moved -> "${change.name} moved to ${kind.start.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", locale))}."
        OrganizerChange.Kind.Changed -> "${change.name} changed."
        OrganizerChange.Kind.Cancelled -> "${change.name} was cancelled."
    }

    /** Home's saved places row: "1 saved place" / "3 saved places". */
    fun savedPlaces(count: Int): String = if (count == 1) "1 saved place" else "$count saved places"

    /**
     * The walk (whole minutes, rounded up) that delays "When?"'s first time, from [user] to [target]:
     * only within walking range (8 km); null otherwise or without either point.
     */
    fun walkMinutes(user: GeoPoint?, target: GeoPoint?): Int? {
        if (user == null || target == null) return null
        val meters = Geo.distanceMeters(user, target)
        if (!Walking.isNearby(meters)) return null
        return Walking.minutes(Walking.estimateSeconds(meters)).toInt()
    }
}
