package com.arcu.attnd.domain

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Miles/feet (US, UK, Liberia, Myanmar) or kilometres/metres everywhere else. */
enum class DistanceUnits {
    IMPERIAL,
    METRIC;

    companion object {
        private val IMPERIAL_REGIONS = setOf("US", "GB", "LR", "MM")

        /** From the phone's region (ISO 3166 code, e.g. `Locale.getDefault().country`). */
        fun forRegion(region: String?): DistanceUnits =
            if (region?.uppercase(Locale.ROOT) in IMPERIAL_REGIONS) IMPERIAL else METRIC
    }
}

/**
 * Distances as short text (shared spec with iOS):
 * imperial: feet under 0.1 mi ("500 ft", nearest 10 ft), miles with one decimal under 10 ("0.6 mi"),
 * whole miles above ("12 mi"); metric: metres under 1 km ("350 m", nearest 10 m), km with one
 * decimal under 10 ("1.2 km"), whole km above.
 */
object DistanceFormat {

    const val METERS_PER_MILE = 1609.344
    const val METERS_PER_FOOT = 0.3048

    fun format(meters: Double, units: DistanceUnits, locale: Locale = Locale.getDefault()): String =
        parts(meters, units, locale).let { (value, unit) -> "$value ${unit.short}" }

    /** For accessibility: "0.6 miles", "1 mile", "500 feet", "350 meters", "1.2 kilometers". */
    fun spoken(meters: Double, units: DistanceUnits, locale: Locale = Locale.getDefault()): String =
        parts(meters, units, locale).let { (value, unit) ->
            "$value ${if (value == "1") unit.singular else unit.plural}"
        }

    /** The server's `distance_miles` (nearby events) in the phone's units. */
    fun formatMiles(miles: Double, units: DistanceUnits, locale: Locale = Locale.getDefault()): String =
        format(miles * METERS_PER_MILE, units, locale)

    private enum class Unit(val short: String, val singular: String, val plural: String) {
        FEET("ft", "foot", "feet"),
        MILES("mi", "mile", "miles"),
        METERS("m", "meter", "meters"),
        KILOMETERS("km", "kilometer", "kilometers"),
    }

    private fun parts(meters: Double, units: DistanceUnits, locale: Locale): Pair<String, Unit> {
        val safe = if (meters.isFinite() && meters > 0) meters else 0.0
        return when (units) {
            DistanceUnits.IMPERIAL -> {
                val miles = safe / METERS_PER_MILE
                val feet = roundToTen(safe / METERS_PER_FOOT)
                if (miles < 0.1) feet.toString() to Unit.FEET
                else large(miles, locale) to Unit.MILES
            }
            DistanceUnits.METRIC -> {
                val rounded = roundToTen(safe)
                if (rounded < 1000) rounded.toString() to Unit.METERS
                else large(safe / 1000.0, locale) to Unit.KILOMETERS
            }
        }
    }

    /** One decimal under 10 (9.96 becomes "10"), whole numbers above. */
    private fun large(value: Double, locale: Locale): String {
        val tenths = (value * 10).roundToLong()
        return if (tenths < 100) String.format(locale, "%.1f", tenths / 10.0) else value.roundToLong().toString()
    }

    private fun roundToTen(value: Double): Long = ((value / 10).roundToLong() * 10).coerceAtLeast(10)
}

enum class CompassPoint(val label: String) {
    NORTH("North"), NORTHEAST("Northeast"), EAST("East"), SOUTHEAST("Southeast"),
    SOUTH("South"), SOUTHWEST("Southwest"), WEST("West"), NORTHWEST("Northwest"),
}

/**
 * Distance and direction from the user to a place, as shown next to it ("0.6 mi" + an arrow).
 * [arrowDegrees] rotates an up-pointing arrow clockwise (bearing − heading); null when
 * [Geo.arrowAllowed] says no, or when [isHere]. [description] is for accessibility: "Northeast, 0.6 miles".
 */
data class Direction(
    val meters: Double,
    val text: String,
    val arrowDegrees: Double?,
    val description: String,
    val isHere: Boolean,
)

/** Great-circle maths on [GeoPoint]s (spherical Earth; plenty for walking distances). */
object Geo {

    const val EARTH_RADIUS_M = 6_371_000.0

    /** Closer than this, the place is "Here" and no arrow is shown. */
    const val HERE_METERS = 20.0

    const val HERE = "Here"

    /** A position fix less precise than this hides the arrow (it would point the wrong way). */
    const val MAX_ARROW_FIX_METERS = 100.0

    /**
     * Whether a direction arrow can be shown: a heading is known, its accuracy isn't negative
     * (invalid), the location permission is precise, and the fix is within 100 m (unknown accuracies
     * don't hide it).
     */
    fun arrowAllowed(
        headingDegrees: Double?,
        headingAccuracy: Double? = null,
        locationAccuracyMeters: Double? = null,
        approximateLocation: Boolean = false,
    ): Boolean =
        headingDegrees != null && headingDegrees.isFinite() &&
            (headingAccuracy == null || headingAccuracy >= 0) &&
            !approximateLocation &&
            (locationAccuracyMeters == null || locationAccuracyMeters <= MAX_ARROW_FIX_METERS)

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from true north in [0, 360). */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** Where the arrow points on screen: bearing − device heading (true north), in [0, 360). */
    fun relativeBearing(bearing: Double, heading: Double): Double = normalize(bearing - heading)

    fun normalize(degrees: Double): Double {
        val d = degrees % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /** The 8-point compass direction of a bearing (each point covers 45°, centred on it). */
    fun compassPoint(bearing: Double): CompassPoint =
        CompassPoint.entries[(floor((normalize(bearing) + 22.5) / 45.0).toInt()) % 8]

    /**
     * [next] shifted by whole turns so it is within 180° of [previous], so an animated arrow turns the
     * short way (359° → 1° is +2°, not −358°).
     */
    fun unwrap(previous: Double, next: Double): Double {
        var delta = (next - previous) % 360.0
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return previous + delta
    }

    fun direction(
        user: GeoPoint,
        target: GeoPoint,
        headingDegrees: Double?,
        units: DistanceUnits,
        locale: Locale = Locale.getDefault(),
        headingAccuracy: Double? = null,
        locationAccuracyMeters: Double? = null,
        approximateLocation: Boolean = false,
    ): Direction {
        val meters = distanceMeters(user, target)
        if (meters < HERE_METERS) return Direction(meters, HERE, null, HERE, isHere = true)
        val bearing = bearingDegrees(user, target)
        return Direction(
            meters = meters,
            text = DistanceFormat.format(meters, units, locale),
            arrowDegrees = headingDegrees
                ?.takeIf { arrowAllowed(it, headingAccuracy, locationAccuracyMeters, approximateLocation) }
                ?.let { relativeBearing(bearing, it) },
            description = "${compassPoint(bearing).label}, ${DistanceFormat.spoken(meters, units, locale)}",
            isHere = false,
        )
    }
}
