package com.arcu.attnd.domain

/**
 * The event's tabs as iOS draws them (a 1:1 port of iOS `TabLayout.swift`): the navigation bar / rail
 * shows [TabLayout.tabs] (resolved with the bundle's display, [DisplayConfig.layout]).
 *
 * - The tabs `display.tabs` lists, in its order (Map, Places, Schedule, Flyer, Updates; Flyer only when
 *   listed or, by default, when the event has a flyer), at most [DisplayConfig.MAX_TABS], so never a
 *   "more" item. No My plan and no Explore tab.
 * - The tab selected after joining: `default_tab` when listed, else Map, else the first.
 * - Home (API-024): always on, first and the default tab ([withHome]); never in screenshot mode.
 * - The Locations tab is titled "Places"; screenshot mode keeps "Locations" so the store screenshots stay.
 * - Options (the gear) is on one tab's root only: Home's header row, else Updates, else the first tab.
 */
enum class EventTab(val key: String, val title: String, val icon: String) {
    /** `home` (API-024): the event's layout sections. */
    HOME("home", "Home", "Home"),
    MAP("map", "Map", "Map"),
    LOCATIONS("locations", "Locations", "List"),
    SCHEDULE("schedule", "Schedule", "CalendarMonth"),
    FLYER("flyer", "Flyer", "Description"),
    NOTIFICATIONS("notifications", "Updates", "Notifications");

    /** The organizer tab this shows (null for Home). */
    val tab: Tab?
        get() = when (this) {
            HOME -> null
            MAP -> Tab.MAP
            LOCATIONS -> Tab.LOCATIONS
            SCHEDULE -> Tab.SCHEDULE
            FLYER -> Tab.FLYER
            NOTIFICATIONS -> Tab.NOTIFICATIONS
        }

    companion object {
        fun fromKey(key: String?): EventTab? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }

        fun of(tab: Tab): EventTab = when (tab) {
            Tab.MAP -> MAP
            Tab.LOCATIONS -> LOCATIONS
            Tab.SCHEDULE -> SCHEDULE
            Tab.FLYER -> FLYER
            Tab.NOTIFICATIONS -> NOTIFICATIONS
        }
    }
}

data class TabLayout(
    val tabs: List<EventTab>,
    /** The tab selected after joining. */
    val defaultTab: EventTab,
) {
    operator fun contains(tab: EventTab): Boolean = tab in tabs

    operator fun contains(tab: Tab): Boolean = EventTab.of(tab) in tabs

    /** The organizer tabs shown (everything but Home), in order. */
    val organizerTabs: List<Tab> get() = tabs.mapNotNull { it.tab }

    /** [requested] when this layout shows it, else the default tab. */
    fun selected(requested: EventTab?): EventTab = requested?.takeIf { it in tabs } ?: defaultTab

    /**
     * Home is always on, first and the default tab, whatever the bundle lists, so a cached or older
     * bundle without it can't make it come and go. Over five tabs the server's rule drops Flyer first,
     * then Places when the Map is there, then the last tab that isn't Updates.
     */
    fun withHome(): TabLayout {
        val result = (listOf(EventTab.HOME) + tabs.filter { it != EventTab.HOME }).toMutableList()
        while (result.size > DisplayConfig.MAX_TABS) {
            val flyer = result.indexOf(EventTab.FLYER)
            val places = result.indexOf(EventTab.LOCATIONS)
            val last = result.indexOfLast { it != EventTab.NOTIFICATIONS && it != EventTab.HOME }
            when {
                flyer >= 0 -> result.removeAt(flyer)
                EventTab.MAP in result && places >= 0 -> result.removeAt(places)
                last >= 0 -> result.removeAt(last)
                else -> break
            }
        }
        return TabLayout(result, EventTab.HOME)
    }

    /** The tab whose root screen has the Options button: Home, else Updates, else the first tab. */
    val optionsTab: EventTab?
        get() = when {
            EventTab.HOME in tabs -> EventTab.HOME
            EventTab.NOTIFICATIONS in tabs -> EventTab.NOTIFICATIONS
            else -> tabs.firstOrNull()
        }

    companion object {
        /**
         * [listed] = `display.tabs` as sent (unknown keys already dropped; null or empty = the defaults),
         * [requestedDefault] = `default_tab`. Screenshot mode drops Home: the store screenshots keep the
         * built-in tabs.
         */
        fun resolve(listed: List<EventTab>?, requestedDefault: EventTab?, hasFlyer: Boolean, screenshotMode: Boolean = false): TabLayout {
            var tabsIn = listed
            var defaultIn = requestedDefault
            if (screenshotMode && tabsIn != null) {
                tabsIn = tabsIn.filter { it != EventTab.HOME }
                if (defaultIn == EventTab.HOME) defaultIn = null
            }
            val resolved = if (!tabsIn.isNullOrEmpty()) tabsIn.take(DisplayConfig.MAX_TABS)
            else DisplayConfig.defaultTabs(hasFlyer).map(EventTab::of)
            val defaultTab = when {
                defaultIn != null && defaultIn in resolved -> defaultIn
                EventTab.MAP in resolved -> EventTab.MAP
                else -> resolved.firstOrNull() ?: EventTab.MAP
            }
            val layout = TabLayout(resolved, defaultTab)
            if (screenshotMode) return layout
            // Home's rule sees the whole list, so the five-tab cut drops Flyer / Places, never a tab that
            // happens to be listed sixth (Schedule added back last).
            if (!tabsIn.isNullOrEmpty()) return TabLayout(tabsIn.distinct(), defaultTab).withHome()
            return layout.withHome()
        }

        /** API-005 `tab_view` detail for a visible tab (the server accepts `home` too). */
        fun usageName(tab: EventTab): String = tab.key

        /** The navigation item's (and the tab's) title. */
        fun title(tab: EventTab, screenshotMode: Boolean): String =
            if (tab == EventTab.LOCATIONS && !screenshotMode) "Places" else tab.title
    }
}

/**
 * The Map tab's navigation: place details pushed over the map (by location id) and the pin to focus
 * next. "Show on map" (from any detail, including one pushed over the map itself) pops back to the map
 * and focuses the pin, so the selected-location card and walking route show.
 */
data class MapNavigation(
    val path: List<Int> = emptyList(),
    /** The pin to select and frame once the map is on screen (consumed by the map). */
    val focusLocationId: Int? = null,
) {
    /** A pin's card tapped: its detail over the map. */
    fun open(locationId: Int): MapNavigation = copy(path = path + locationId)

    /** Back to the map (every pushed detail pops) with [locationId] focused. */
    fun showOnMap(locationId: Int): MapNavigation = MapNavigation(emptyList(), locationId)

    /** The pending focus and the navigation without it. */
    fun takeFocus(): Pair<Int?, MapNavigation> = focusLocationId to copy(focusLocationId = null)
}
