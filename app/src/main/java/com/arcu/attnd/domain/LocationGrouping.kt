package com.arcu.attnd.domain

/** A titled run of locations, used by the `grouped` locations style. */
data class LocationSection(
    val key: String,
    val title: String,
    val locations: List<EventLocation>,
)

/** Pure grouping and filtering for the Locations tab and the map. */
object LocationGrouping {

    const val UNCATEGORIZED_TITLE = "Other"

    /**
     * Groups by `category`. Sections follow the bundle's [categories] order, then any category that
     * is not in that list (in order of first appearance), then uncategorized locations under "Other".
     * Within a section the organizer's order is kept.
     */
    fun byCategory(locations: List<EventLocation>, categories: List<String>): List<LocationSection> {
        val grouped = LinkedHashMap<String, MutableList<EventLocation>>()
        val uncategorized = mutableListOf<EventLocation>()
        for (location in locations) {
            val category = location.category?.trim()?.takeIf { it.isNotEmpty() }
            if (category == null) uncategorized += location
            else grouped.getOrPut(category) { mutableListOf() } += location
        }
        val ordered = categories.filter { it in grouped } + grouped.keys.filter { it !in categories }
        val sections = ordered.map { LocationSection("cat-$it", it, grouped.getValue(it)) }
        return if (uncategorized.isEmpty()) sections
        else sections + LocationSection("cat-none", UNCATEGORIZED_TITLE, uncategorized)
    }

    /** Category chip values: the bundle's list first, then any extra categories found on locations. */
    fun availableCategories(locations: List<EventLocation>, categories: List<String>): List<String> {
        val used = locations.mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return categories.filter { it in used } + used.filter { it !in categories }
    }

    /** Filters by a case-insensitive [query] and an optional exact [category]. */
    fun filter(locations: List<EventLocation>, query: String, category: String?): List<EventLocation> {
        val needle = query.trim()
        return locations.filter { location ->
            (category == null || location.category?.trim() == category) &&
                (needle.isEmpty() ||
                    location.name.contains(needle, ignoreCase = true) ||
                    location.snippet.contains(needle, ignoreCase = true) ||
                    location.description.contains(needle, ignoreCase = true) ||
                    location.address.contains(needle, ignoreCase = true) ||
                    (location.category?.contains(needle, ignoreCase = true) == true) ||
                    location.tags.any { it.contains(needle, ignoreCase = true) })
        }
    }

    /** Locations that can be pinned. Locations with null (or 0,0) coordinates are skipped, never dropped at 0,0. */
    fun mappable(locations: List<EventLocation>): List<Pair<EventLocation, GeoPoint>> =
        locations.mapNotNull { location -> location.coordinate?.let { location to it } }

    /**
     * South-west / north-east corners enclosing every point, or null when there are none.
     * (Does not handle the antimeridian; events never span it.)
     */
    fun bounds(points: List<GeoPoint>): Pair<GeoPoint, GeoPoint>? {
        if (points.isEmpty()) return null
        return GeoPoint(points.minOf { it.latitude }, points.minOf { it.longitude }) to
            GeoPoint(points.maxOf { it.latitude }, points.maxOf { it.longitude })
    }
}
