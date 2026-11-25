package com.arcu.attnd.domain

import java.util.Locale
import kotlin.math.roundToLong

/**
 * A walking route to a location: from the routing service ([isEstimate] false; [points] is its
 * polyline) or the straight-line estimate ([isEstimate] true; [points] is origin → destination,
 * drawn dashed).
 */
data class WalkRoute(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val points: List<GeoPoint>,
    val isEstimate: Boolean,
)

/**
 * Walking times and route rules (shared spec with iOS). The estimate assumes 1.3 m/s over the
 * straight line × 1.2. Routes and walk times are only shown within 8 km (≈ 5 miles). A service route longer than
 * 2.5 × the straight line (common inside festival grounds, where paths aren't mapped) is replaced by
 * the estimate.
 */
object Walking {

    const val SPEED_M_PER_S = 1.3
    const val DETOUR_FACTOR = 1.2
    const val MAX_ROUTE_RATIO = 2.5

    /** 8 km (≈ 5 miles): the "you are at (or near) the event" radius for routes and walk times. */
    const val NEARBY_METERS = 8_000.0

    /** A cached route is reused while the user is within this distance of where it was computed. */
    const val REROUTE_METERS = 50.0

    const val YOURE_HERE = "You're here"

    fun estimateSeconds(straightMeters: Double): Long =
        (straightMeters.coerceAtLeast(0.0) * DETOUR_FACTOR / SPEED_M_PER_S).roundToLong()

    fun estimate(origin: GeoPoint, destination: GeoPoint): WalkRoute {
        val straight = Geo.distanceMeters(origin, destination)
        return WalkRoute(straight * DETOUR_FACTOR, estimateSeconds(straight), listOf(origin, destination), isEstimate = true)
    }

    /** Whether a service route is plausible; otherwise the estimate is shown instead. */
    fun accept(routeMeters: Double, straightMeters: Double): Boolean =
        routeMeters.isFinite() && routeMeters >= 0 && (straightMeters < 1 || routeMeters <= straightMeters * MAX_ROUTE_RATIO)

    fun isNearby(meters: Double): Boolean = meters <= NEARBY_METERS

    fun isNearby(user: GeoPoint, target: GeoPoint): Boolean = isNearby(Geo.distanceMeters(user, target))

    /** Whole minutes, rounded up, at least 1. */
    fun minutes(seconds: Long): Long = ((seconds + 59) / 60).coerceAtLeast(1)

    /** "8 min", "1 h", "1 h 6 min". */
    fun durationText(seconds: Long): String {
        val total = minutes(seconds)
        val hours = total / 60
        val rest = total % 60
        return when {
            hours == 0L -> "$total min"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** "8 min walk". */
    fun walkText(seconds: Long): String = "${durationText(seconds)} walk"

    /**
     * Schedule and location rows (always the straight-line estimate): "8 min walk", "You're here"
     * within 20 m, or null beyond [NEARBY_METERS].
     */
    fun rowText(straightMeters: Double): String? = when {
        straightMeters < Geo.HERE_METERS -> YOURE_HERE
        isNearby(straightMeters) -> walkText(estimateSeconds(straightMeters))
        else -> null
    }

    /** The map card: "12 min walk · 0.6 mi" for a route, "about 8 min walk" for the estimate. */
    fun cardText(route: WalkRoute, units: DistanceUnits, locale: Locale = Locale.getDefault()): String =
        if (route.isEstimate) "about ${walkText(route.durationSeconds)}"
        else "${walkText(route.durationSeconds)} · ${DistanceFormat.format(route.distanceMeters, units, locale)}"

    /** True for the first position, or once the user is more than 50 m from where the route was computed. */
    fun shouldReroute(lastOrigin: GeoPoint?, origin: GeoPoint): Boolean =
        lastOrigin == null || Geo.distanceMeters(lastOrigin, origin) > REROUTE_METERS

    /** A protobuf JSON duration such as "165s" or "165.5s" → whole seconds; null when malformed. */
    fun parseDurationSeconds(value: String?): Long? {
        val text = value?.trim()?.removeSuffix("s") ?: return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.roundToLong()
    }
}

/** Google's encoded polyline format (precision 5), as returned by the Routes API. */
object EncodedPolyline {

    /** Decodes [encoded]; a truncated or malformed string yields the points decoded so far. */
    fun decode(encoded: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            val dLat = next(encoded, index) ?: break
            index = dLat.second
            val dLng = next(encoded, index) ?: break
            index = dLng.second
            lat += dLat.first
            lng += dLng.first
            points += GeoPoint(lat / 1e5, lng / 1e5)
        }
        return points
    }

    /** One signed varint value and the index after it, or null when the string ends mid-value. */
    private fun next(encoded: String, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var index = start
        while (true) {
            if (index >= encoded.length || shift > 60) return null
            val b = encoded[index++].code - 63
            if (b < 0) return null
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to index
    }

    /** Encodes [points] (used by tests to round-trip). */
    fun encode(points: List<GeoPoint>): String = buildString {
        var lastLat = 0L
        var lastLng = 0L
        for (point in points) {
            val lat = (point.latitude * 1e5).roundToLong()
            val lng = (point.longitude * 1e5).roundToLong()
            appendValue(lat - lastLat)
            appendValue(lng - lastLng)
            lastLat = lat
            lastLng = lng
        }
    }

    private fun StringBuilder.appendValue(delta: Long) {
        var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (value >= 0x20) {
            append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        append((value.toInt() + 63).toChar())
    }
}
