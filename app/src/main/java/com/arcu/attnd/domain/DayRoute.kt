package com.arcu.attnd.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A numbered stop of the day's walk: one location and the consecutive planned items there. */
data class RouteStop(val number: Int, val locationId: Int, val point: GeoPoint, val itemIds: List<Int>)

/** The walk between two consecutive stops: the estimate at first, the service route once fetched. */
data class RouteLeg(
    val from: RouteStop,
    val to: RouteStop,
    /** The last item at [from] and the first at [to]. */
    val fromItem: Int,
    val toItem: Int,
    val straightMeters: Double,
    val route: WalkRoute,
)

data class DayRoute(val date: LocalDate, val stops: List<RouteStop>, val legs: List<RouteLeg>) {
    val totalMeters: Double get() = legs.sumOf { it.route.distanceMeters }
    val totalSeconds: Long get() = legs.sumOf { it.route.durationSeconds }
}

/**
 * The plan's walking legs (assistant spec §5; pure, iOS matches):
 * - **Stops**: the day's planned items in plan order whose location has coordinates (items without
 *   one are skipped); consecutive items at the same location share one stop. Stops are numbered from 1.
 * - **Legs**: between consecutive stops; each starts as [Walking.estimate] (straight line × 1.2 at
 *   1.3 m/s) and is upgraded by [withServiceLegs]: a service route replaces the estimate only when
 *   [Walking.accept] (≤ 2.5 × the straight line); a missing / rejected one keeps the estimate.
 * - **Totals**: the sum of the legs' distances and durations.
 * - One Routes API `computeRoutes` call covers the day ([waypoints]: origin, intermediates,
 *   destination) when it has 2–25 stops; with more the estimates stay.
 */
object DayRoutes {

    const val MAX_STOPS = 25

    /**
     * The route of [date] (default: the plan's first day with items), optionally only through [only]
     * (the recap's checked-off items). Null when the day isn't planned.
     */
    fun of(plan: Plan, bundle: EventBundle, date: LocalDate? = null, only: Set<Int>? = null): DayRoute? {
        val day = (if (date != null) plan.days.firstOrNull { it.date == date } else plan.days.firstOrNull { it.items.isNotEmpty() }) ?: return null
        val stops = mutableListOf<RouteStop>()
        for (entry in day.items) {
            if (only != null && entry.itemId !in only) continue
            val item = bundle.scheduleItem(entry.itemId) ?: continue
            val location = bundle.location(item.locationId) ?: continue
            val point = location.coordinate ?: continue
            val last = stops.lastOrNull()
            if (last != null && last.locationId == location.id) {
                stops[stops.lastIndex] = last.copy(itemIds = last.itemIds + item.id)
            } else {
                stops += RouteStop(stops.size + 1, location.id, point, listOf(item.id))
            }
        }
        val legs = stops.zipWithNext { a, b ->
            RouteLeg(a, b, a.itemIds.last(), b.itemIds.first(), Geo.distanceMeters(a.point, b.point), Walking.estimate(a.point, b.point))
        }
        return DayRoute(day.date, stops, legs)
    }

    /** The points for one multi-waypoint request, or null with fewer than 2 or more than 25 stops. */
    fun waypoints(route: DayRoute): List<GeoPoint>? = route.stops.map { it.point }.takeIf { it.size in 2..MAX_STOPS }

    /** [route] with the service's per-leg routes ([service] index = leg index; null = keep the estimate). */
    fun withServiceLegs(route: DayRoute, service: List<WalkRoute?>): DayRoute = route.copy(
        legs = route.legs.mapIndexed { index, leg ->
            val candidate = service.getOrNull(index)?.takeIf { !it.isEstimate && Walking.accept(it.distanceMeters, leg.straightMeters) }
            if (candidate != null) leg.copy(route = candidate) else leg
        },
    )

    /** A leg row: "6 min walk · 0.3 mi" (the estimate says "about 6 min walk · 0.3 mi"). */
    fun legText(leg: RouteLeg, units: DistanceUnits, locale: Locale = Locale.getDefault()): String {
        val text = "${Walking.walkText(leg.route.durationSeconds)} · ${DistanceFormat.format(leg.route.distanceMeters, units, locale)}"
        return if (leg.route.isEstimate) "about $text" else text
    }

    /** "2.8 mi walking today", "1.2 km walking on Sunday"; "No walking" without legs. */
    fun totalText(route: DayRoute, today: LocalDate, units: DistanceUnits, locale: Locale = Locale.getDefault()): String {
        if (route.legs.isEmpty()) return "No walking"
        val distance = DistanceFormat.format(route.totalMeters, units, locale)
        val day = if (route.date == today) "today" else "on ${route.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}"
        return "$distance walking $day"
    }
}
