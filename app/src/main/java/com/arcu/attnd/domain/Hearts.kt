package com.arcu.attnd.domain

/**
 * The schedule items and locations the user hearted in one event. Stored only on the device (per
 * event, in the backed-up preferences) as text: `l5,l7,s3` (l = location id, s = schedule item id).
 */
data class Hearts(
    val locations: Set<Int> = emptySet(),
    val scheduleItems: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean get() = locations.isEmpty() && scheduleItems.isEmpty()

    fun has(kind: SearchKind, id: Int): Boolean = when (kind) {
        SearchKind.LOCATION -> id in locations
        SearchKind.SCHEDULE -> id in scheduleItems
        else -> false
    }

    /** Hearted ([hearted] true) or not; other kinds are ignored. */
    fun with(kind: SearchKind, id: Int, hearted: Boolean): Hearts = when (kind) {
        SearchKind.LOCATION -> copy(locations = if (hearted) locations + id else locations - id)
        SearchKind.SCHEDULE -> copy(scheduleItems = if (hearted) scheduleItems + id else scheduleItems - id)
        else -> this
    }

    fun toggled(kind: SearchKind, id: Int): Hearts = with(kind, id, !has(kind, id))

    fun encode(): String =
        (locations.sorted().map { "l$it" } + scheduleItems.sorted().map { "s$it" }).joinToString(",")

    companion object {
        val NONE = Hearts()

        /** Lenient: unknown or malformed parts are skipped. */
        fun decode(value: String?): Hearts {
            if (value.isNullOrBlank()) return NONE
            val locations = mutableSetOf<Int>()
            val items = mutableSetOf<Int>()
            for (part in value.split(',')) {
                val trimmed = part.trim()
                val id = trimmed.drop(1).toIntOrNull() ?: continue
                when (trimmed.firstOrNull()) {
                    'l' -> locations += id
                    's' -> items += id
                }
            }
            return Hearts(locations, items)
        }

        /** API-009 usage metric for a heart change (detail = the item's id). */
        fun metric(kind: SearchKind, hearted: Boolean): String? = when (kind) {
            SearchKind.LOCATION -> if (hearted) UsageMetric.LOCATION_HEART else UsageMetric.LOCATION_UNHEART
            SearchKind.SCHEDULE -> if (hearted) UsageMetric.SCHEDULE_ITEM_HEART else UsageMetric.SCHEDULE_ITEM_UNHEART
            else -> null
        }
    }
}
