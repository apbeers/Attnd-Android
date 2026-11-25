package com.arcu.attnd.unit

import com.arcu.attnd.domain.CompassPoint
import com.arcu.attnd.domain.DistanceFormat
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Distance text, bearings and compass arrows (shared spec with iOS). */
class GeoTest {

    private val locale = Locale.US
    private val mile = DistanceFormat.METERS_PER_MILE
    private fun imperial(meters: Double) = DistanceFormat.format(meters, DistanceUnits.IMPERIAL, locale)
    private fun metric(meters: Double) = DistanceFormat.format(meters, DistanceUnits.METRIC, locale)

    @Test
    fun units_fromRegion() {
        assertEquals(DistanceUnits.IMPERIAL, DistanceUnits.forRegion("US"))
        assertEquals(DistanceUnits.IMPERIAL, DistanceUnits.forRegion("gb"))
        assertEquals(DistanceUnits.IMPERIAL, DistanceUnits.forRegion("LR"))
        assertEquals(DistanceUnits.IMPERIAL, DistanceUnits.forRegion("MM"))
        assertEquals(DistanceUnits.METRIC, DistanceUnits.forRegion("DE"))
        assertEquals(DistanceUnits.METRIC, DistanceUnits.forRegion("CA"))
        assertEquals(DistanceUnits.METRIC, DistanceUnits.forRegion(""))
        assertEquals(DistanceUnits.METRIC, DistanceUnits.forRegion(null))
    }

    @Test
    fun imperial_feetUnderATenthOfAMile_thenMiles() {
        assertEquals("100 ft", imperial(30.0))
        assertEquals("500 ft", imperial(500 * DistanceFormat.METERS_PER_FOOT))
        assertEquals("0.1 mi", imperial(0.1 * mile))
        assertEquals("0.6 mi", imperial(0.6 * mile))
        assertEquals("2.5 mi", imperial(2.5 * mile))
        assertEquals("9.9 mi", imperial(9.94 * mile))
        // 9.96 rounds up to 10.0: whole miles from there.
        assertEquals("10 mi", imperial(9.96 * mile))
        assertEquals("12 mi", imperial(12.4 * mile))
    }

    @Test
    fun metric_metresUnderAKilometre_thenKilometres() {
        assertEquals("10 m", metric(3.0))
        assertEquals("350 m", metric(347.0))
        assertEquals("1.0 km", metric(996.0))
        assertEquals("1.2 km", metric(1234.0))
        assertEquals("9.9 km", metric(9_940.0))
        assertEquals("12 km", metric(12_400.0))
    }

    @Test
    fun spoken() {
        assertEquals("0.6 miles", DistanceFormat.spoken(0.6 * mile, DistanceUnits.IMPERIAL, locale))
        assertEquals("500 feet", DistanceFormat.spoken(500 * DistanceFormat.METERS_PER_FOOT, DistanceUnits.IMPERIAL, locale))
        assertEquals("12 miles", DistanceFormat.spoken(12.0 * mile, DistanceUnits.IMPERIAL, locale))
        assertEquals("350 meters", DistanceFormat.spoken(350.0, DistanceUnits.METRIC, locale))
        assertEquals("1.2 kilometers", DistanceFormat.spoken(1234.0, DistanceUnits.METRIC, locale))
        assertEquals("1.0 kilometers", DistanceFormat.spoken(1_000.0, DistanceUnits.METRIC, locale))
    }

    @Test
    fun nearbyMiles_fromTheServer() {
        assertEquals("0.6 mi", DistanceFormat.formatMiles(0.6, DistanceUnits.IMPERIAL, locale))
        assertEquals("970 m", DistanceFormat.formatMiles(0.6, DistanceUnits.METRIC, locale))
    }

    @Test
    fun distanceAndBearing() {
        val a = GeoPoint(32.75, -97.33)
        // 0.01° of latitude ≈ 1.11 km.
        assertEquals(1112.0, Geo.distanceMeters(a, GeoPoint(32.76, -97.33)), 2.0)
        assertEquals(0.0, Geo.bearingDegrees(a, GeoPoint(32.76, -97.33)), 0.01)
        assertEquals(180.0, Geo.bearingDegrees(a, GeoPoint(32.74, -97.33)), 0.01)
        assertEquals(90.0, Geo.bearingDegrees(a, GeoPoint(32.75, -97.32)), 0.01)
        assertEquals(270.0, Geo.bearingDegrees(a, GeoPoint(32.75, -97.34)), 0.01)
        assertEquals(45.0, Geo.bearingDegrees(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.001)), 0.01)
    }

    @Test
    fun compassPoints_45DegreesEach() {
        assertEquals(CompassPoint.NORTH, Geo.compassPoint(0.0))
        assertEquals(CompassPoint.NORTH, Geo.compassPoint(22.4))
        assertEquals(CompassPoint.NORTHEAST, Geo.compassPoint(22.5))
        assertEquals(CompassPoint.EAST, Geo.compassPoint(90.0))
        assertEquals(CompassPoint.SOUTHEAST, Geo.compassPoint(135.0))
        assertEquals(CompassPoint.SOUTH, Geo.compassPoint(180.0))
        assertEquals(CompassPoint.SOUTHWEST, Geo.compassPoint(225.0))
        assertEquals(CompassPoint.WEST, Geo.compassPoint(270.0))
        assertEquals(CompassPoint.NORTHWEST, Geo.compassPoint(337.4))
        assertEquals(CompassPoint.NORTH, Geo.compassPoint(337.5))
        assertEquals(CompassPoint.NORTH, Geo.compassPoint(-10.0))
        assertEquals(CompassPoint.EAST, Geo.compassPoint(450.0))
    }

    @Test
    fun arrow_isBearingMinusHeading() {
        assertEquals(45.0, Geo.relativeBearing(45.0, 0.0), 1e-9)
        assertEquals(315.0, Geo.relativeBearing(45.0, 90.0), 1e-9)
        assertEquals(10.0, Geo.relativeBearing(5.0, 355.0), 1e-9)
        assertEquals(0.0, Geo.relativeBearing(200.0, 200.0), 1e-9)
    }

    @Test
    fun unwrap_turnsTheShortWay() {
        assertEquals(361.0, Geo.unwrap(359.0, 1.0), 1e-9)
        assertEquals(-1.0, Geo.unwrap(1.0, 359.0), 1e-9)
        assertEquals(90.0, Geo.unwrap(0.0, 90.0), 1e-9)
        assertEquals(720.0 + 10.0, Geo.unwrap(725.0, 10.0), 1e-9)
    }

    @Test
    fun arrow_hiddenWithoutAReliableHeadingOrPreciseFix() {
        assertTrue(Geo.arrowAllowed(90.0))
        assertTrue(Geo.arrowAllowed(90.0, headingAccuracy = 0.0, locationAccuracyMeters = 100.0))
        assertFalse(Geo.arrowAllowed(null))
        assertFalse(Geo.arrowAllowed(90.0, headingAccuracy = -1.0))
        assertFalse(Geo.arrowAllowed(90.0, approximateLocation = true))
        assertFalse(Geo.arrowAllowed(90.0, locationAccuracyMeters = 100.1))
        val user = GeoPoint(32.75, -97.33)
        val target = GeoPoint(32.76, -97.33)
        val coarse = Geo.direction(user, target, 0.0, DistanceUnits.IMPERIAL, locale, approximateLocation = true)
        assertNull(coarse.arrowDegrees)
        // The distance is still shown.
        assertEquals("0.7 mi", coarse.text)
        assertNull(Geo.direction(user, target, 0.0, DistanceUnits.IMPERIAL, locale, headingAccuracy = -1.0).arrowDegrees)
        assertNull(Geo.direction(user, target, 0.0, DistanceUnits.IMPERIAL, locale, locationAccuracyMeters = 250.0).arrowDegrees)
    }

    @Test
    fun localeDecimalSeparator() {
        assertEquals("0,6 mi", DistanceFormat.format(0.6 * mile, DistanceUnits.IMPERIAL, Locale.GERMANY))
        assertEquals("1,2 km", DistanceFormat.format(1234.0, DistanceUnits.METRIC, Locale.GERMANY))
    }

    @Test
    fun direction_textArrowAndAccessibility() {
        val user = GeoPoint(32.75, -97.33)
        // ~0.6 mi to the northeast.
        val target = GeoPoint(32.75 + 0.0061, -97.33 + 0.0073)
        val d = Geo.direction(user, target, headingDegrees = 90.0, units = DistanceUnits.IMPERIAL, locale = locale)
        assertEquals("0.6 mi", d.text)
        assertEquals("Northeast, 0.6 miles", d.description)
        assertFalse(d.isHere)
        val bearing = Geo.bearingDegrees(user, target)
        assertEquals(Geo.normalize(bearing - 90.0), d.arrowDegrees!!, 1e-9)

        // No heading: distance only.
        assertNull(Geo.direction(user, target, null, DistanceUnits.IMPERIAL, locale).arrowDegrees)

        // Within ~20 m: "Here", no arrow.
        val here = Geo.direction(user, GeoPoint(32.75009, -97.33), 0.0, DistanceUnits.METRIC, locale)
        assertTrue(here.isHere)
        assertEquals("Here", here.text)
        assertEquals("Here", here.description)
        assertNull(here.arrowDegrees)
    }
}
