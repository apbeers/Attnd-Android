package com.arcu.attnd.domain

/**
 * The organizer's layout choices from the bundle's `display` object, with the client
 * defaults from API_V2.md applied for every missing or unknown value.
 */
enum class Tab(val key: String) {
    MAP("map"),
    LOCATIONS("locations"),
    SCHEDULE("schedule"),
    FLYER("flyer"),
    NOTIFICATIONS("notifications");

    companion object {
        fun fromKey(key: String?): Tab? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

enum class LocationsStyle(val key: String) {
    CARDS("cards"), LIST("list"), GROUPED("grouped");

    companion object {
        fun fromKey(key: String?): LocationsStyle? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

enum class ScheduleStyle(val key: String) {
    TIMELINE("timeline"), BY_DAY("by_day"), BY_LOCATION("by_location"), NOW_NEXT("now_next");

    companion object {
        fun fromKey(key: String?): ScheduleStyle? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

enum class SecondaryPane(val key: String) {
    LOCATIONS("locations"), SCHEDULE("schedule"), NOW_NEXT("now_next"), NONE("none");

    companion object {
        fun fromKey(key: String?): SecondaryPane? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

data class DisplayConfig(
    val tabs: List<Tab>,
    val defaultTab: Tab,
    val locationsStyle: LocationsStyle,
    val scheduleStyle: ScheduleStyle,
    val mapClustering: Boolean,
    val mapFitAll: Boolean,
    val showLocationImages: Boolean,
    val enableSearch: Boolean,
    val groupByCategory: Boolean,
    val secondaryPane: SecondaryPane,
    /** `#RRGGBB`, or null for the default brand colour. */
    val accentColor: String?,
    val logoUrl: String?,
) {
    companion object {
        val DEFAULT_LOCATIONS_STYLE = LocationsStyle.LIST
        val DEFAULT_SCHEDULE_STYLE = ScheduleStyle.BY_DAY
        const val DEFAULT_MAP_CLUSTERING = false
        const val DEFAULT_MAP_FIT_ALL = true
        const val DEFAULT_SHOW_LOCATION_IMAGES = true
        const val DEFAULT_ENABLE_SEARCH = true
        const val DEFAULT_GROUP_BY_CATEGORY = false
        val DEFAULT_SECONDARY_PANE = SecondaryPane.LOCATIONS

        /** Most items a Material navigation bar holds; extra listed tabs are dropped (no overflow item). */
        const val MAX_TABS = 5

        /** `[map, locations, schedule, notifications]`, plus `flyer` when the event has a flyer. */
        fun defaultTabs(hasFlyer: Boolean): List<Tab> = buildList {
            add(Tab.MAP)
            add(Tab.LOCATIONS)
            add(Tab.SCHEDULE)
            if (hasFlyer) add(Tab.FLYER)
            add(Tab.NOTIFICATIONS)
        }

        /**
         * Resolves the raw values of a `display` object. Every argument is nullable because
         * every key may be missing; [rawTabs] may also contain unknown values, which are dropped.
         */
        fun resolve(
            rawTabs: List<String?>?,
            rawDefaultTab: String?,
            rawLocationsStyle: String?,
            rawScheduleStyle: String?,
            mapClustering: Boolean?,
            mapFitAll: Boolean?,
            showLocationImages: Boolean?,
            enableSearch: Boolean?,
            groupByCategory: Boolean?,
            rawSecondaryPane: String?,
            accentColor: String?,
            logoUrl: String?,
            hasFlyer: Boolean,
        ): DisplayConfig {
            val parsedTabs = rawTabs?.mapNotNull { Tab.fromKey(it) }?.distinct().orEmpty()
            // The navigation bar holds only event tabs, never a "more" item: at most MAX_TABS, in the listed order.
            val tabs = parsedTabs.ifEmpty { defaultTabs(hasFlyer) }.take(MAX_TABS)
            val requestedDefault = Tab.fromKey(rawDefaultTab)
            val defaultTab = when {
                requestedDefault != null && requestedDefault in tabs -> requestedDefault
                Tab.MAP in tabs -> Tab.MAP
                else -> tabs.first()
            }
            return DisplayConfig(
                tabs = tabs,
                defaultTab = defaultTab,
                locationsStyle = LocationsStyle.fromKey(rawLocationsStyle) ?: DEFAULT_LOCATIONS_STYLE,
                scheduleStyle = ScheduleStyle.fromKey(rawScheduleStyle) ?: DEFAULT_SCHEDULE_STYLE,
                mapClustering = mapClustering ?: DEFAULT_MAP_CLUSTERING,
                mapFitAll = mapFitAll ?: DEFAULT_MAP_FIT_ALL,
                showLocationImages = showLocationImages ?: DEFAULT_SHOW_LOCATION_IMAGES,
                enableSearch = enableSearch ?: DEFAULT_ENABLE_SEARCH,
                groupByCategory = groupByCategory ?: DEFAULT_GROUP_BY_CATEGORY,
                secondaryPane = SecondaryPane.fromKey(rawSecondaryPane) ?: DEFAULT_SECONDARY_PANE,
                accentColor = accentColor?.takeIf { parseHexColor(it) != null },
                logoUrl = logoUrl?.takeIf { it.isNotBlank() },
            )
        }

        /** Defaults for a bundle that has no `display` object at all. */
        fun defaults(hasFlyer: Boolean, accentColor: String? = null, logoUrl: String? = null): DisplayConfig =
            resolve(
                rawTabs = null, rawDefaultTab = null, rawLocationsStyle = null, rawScheduleStyle = null,
                mapClustering = null, mapFitAll = null, showLocationImages = null, enableSearch = null,
                groupByCategory = null, rawSecondaryPane = null,
                accentColor = accentColor, logoUrl = logoUrl, hasFlyer = hasFlyer,
            )

        /**
         * Parses `#RRGGBB` (or `#AARRGGBB`) into an ARGB long, e.g. `0xFFFF5A1F`.
         * Returns null for anything else.
         */
        fun parseHexColor(value: String?): Long? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6 && hex.length != 8) return null
            val parsed = hex.toLongOrNull(16) ?: return null
            return if (hex.length == 6) 0xFF000000L or parsed else parsed
        }
    }
}
