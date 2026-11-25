package com.arcu.attnd.domain

/** The screens `scripts/ci/screenshots.sh` captures for the Play listing. */
enum class ScreenshotScreen(val key: String) {
    LAUNCH("launch"),
    MAP("map"),
    LOCATIONS("locations"),
    LOCATION("location"),
    SCHEDULE("schedule"),
    NOTIFICATIONS("notifications");

    companion object {
        fun fromKey(key: String?): ScreenshotScreen? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * Store-screenshot mode, debug builds only (MainActivity checks `BuildConfig.DEBUG`):
 *
 *     adb shell am start -n com.arcu.attnd.debug/com.arcu.attnd.MainActivity --es attnd_screenshot map
 *
 * joins [eventCode] (default `DEMO`, the always-current demo event) through the normal lookup/join
 * path, then shows [screen] with no launch screen, update banner, status pill, offline notice,
 * snackbar or usage reporting.
 * Optional extras: `attnd_screenshot_event` (event code) and `attnd_screenshot_location` (the
 * location whose detail [ScreenshotScreen.LOCATION] opens, default "Main Stage").
 */
data class ScreenshotRequest(
    val screen: ScreenshotScreen,
    val eventCode: String = DEFAULT_EVENT_CODE,
    val locationName: String = DEFAULT_LOCATION,
) {
    /** The tab to show, or null for the launch screen. */
    val tab: Tab?
        get() = when (screen) {
            ScreenshotScreen.LAUNCH -> null
            ScreenshotScreen.MAP -> Tab.MAP
            ScreenshotScreen.LOCATIONS, ScreenshotScreen.LOCATION -> Tab.LOCATIONS
            ScreenshotScreen.SCHEDULE -> Tab.SCHEDULE
            ScreenshotScreen.NOTIFICATIONS -> Tab.NOTIFICATIONS
        }

    /** The location whose detail to open (only for [ScreenshotScreen.LOCATION]): exact name, any case. */
    fun focusLocation(locations: List<EventLocation>): EventLocation? {
        if (screen != ScreenshotScreen.LOCATION) return null
        val wanted = locationName.trim()
        return locations.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
    }

    /** The map shot shows every pin: fit all pins, no clustering. Other screens keep the organizer's layout. */
    fun adjust(display: DisplayConfig): DisplayConfig =
        if (screen == ScreenshotScreen.MAP) display.copy(mapFitAll = true, mapClustering = false) else display

    /**
     * The bundle as the screenshot shows it: [adjust]ed layout, and no "Demo event" pill (the DEMO
     * event is a demo; the listing shows the app as attendees of a real event see it).
     */
    fun present(bundle: EventBundle): EventBundle =
        bundle.copy(event = bundle.event.copy(isDemo = false), display = adjust(bundle.display))

    companion object {
        const val EXTRA_SCREEN = "attnd_screenshot"
        const val EXTRA_EVENT_CODE = "attnd_screenshot_event"
        const val EXTRA_LOCATION = "attnd_screenshot_location"
        const val DEFAULT_EVENT_CODE = "DEMO"
        const val DEFAULT_LOCATION = "Main Stage"

        /** Null unless [screen] names a known screen. Blank optional values fall back to the defaults. */
        fun parse(screen: String?, eventCode: String? = null, locationName: String? = null): ScreenshotRequest? {
            val parsed = ScreenshotScreen.fromKey(screen) ?: return null
            return ScreenshotRequest(
                screen = parsed,
                eventCode = eventCode?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_EVENT_CODE,
                locationName = locationName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LOCATION,
            )
        }
    }
}
