package com.arcu.attnd.unit

import com.arcu.attnd.domain.DistanceFormat
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EncodedPolyline
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.WalkRoute
import com.arcu.attnd.domain.Walking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Walk times, the estimate fallback, route plausibility, caching keys and polyline decoding. */
class WalkingTest {

    private val locale = Locale.US

    @Test
    fun estimate_1point3MetresPerSecondTimes1point2() {
        // 1000 m straight → 1200 m walked at 1.3 m/s = 923 s → 16 min (rounded up).
        assertEquals(923L, Walking.estimateSeconds(1000.0))
        assertEquals(0L, Walking.estimateSeconds(-5.0))
        val a = GeoPoint(32.75, -97.33)
        val b = GeoPoint(32.76, -97.33)
        val estimate = Walking.estimate(a, b)
        assertTrue(estimate.isEstimate)
        assertEquals(listOf(a, b), estimate.points)
        assertEquals(Walking.estimateSeconds(Geo.distanceMeters(a, b)), estimate.durationSeconds)
    }

    @Test
    fun walkTexts() {
        assertEquals("1 min", Walking.durationText(0))
        assertEquals("1 min", Walking.durationText(10))
        assertEquals("8 min", Walking.durationText(480))
        assertEquals("9 min", Walking.durationText(481))
        assertEquals("16 min", Walking.durationText(923))
        assertEquals("1 h", Walking.durationText(3600))
        assertEquals("1 h 6 min", Walking.durationText(3901))
        assertEquals("1 h 6 min", Walking.durationText(3960))
        assertEquals("8 min walk", Walking.walkText(480))
        val route = WalkRoute(0.6 * DistanceFormat.METERS_PER_MILE, 720, emptyList(), isEstimate = false)
        assertEquals("12 min walk · 0.6 mi", Walking.cardText(route, DistanceUnits.IMPERIAL, locale))
        assertEquals("12 min walk · 970 m", Walking.cardText(route, DistanceUnits.METRIC, locale))
        assertEquals("about 8 min walk", Walking.cardText(route.copy(durationSeconds = 480, isEstimate = true), DistanceUnits.IMPERIAL, locale))
    }

    @Test
    fun routeLongerThan2point5TimesTheStraightLine_isRejected() {
        assertTrue(Walking.accept(2500.0, 1000.0))
        assertFalse(Walking.accept(2501.0, 1000.0))
        assertTrue(Walking.accept(40.0, 0.5))
        assertFalse(Walking.accept(Double.NaN, 1000.0))
    }

    @Test
    fun nearby_within8000Metres() {
        assertTrue(Walking.isNearby(8_000.0))
        assertFalse(Walking.isNearby(8_000.1))
        assertTrue(Walking.isNearby(GeoPoint(32.75, -97.33), GeoPoint(32.80, -97.33)))
        assertFalse(Walking.isNearby(GeoPoint(32.75, -97.33), GeoPoint(32.83, -97.33)))
    }

    @Test
    fun rowText_walkTimeOrYoureHere() {
        assertEquals("You're here", Walking.rowText(19.9))
        // 500 m → 461.5 s → 8 min.
        assertEquals("8 min walk", Walking.rowText(500.0))
        assertEquals("1 min walk", Walking.rowText(20.0))
        assertNull(Walking.rowText(8_001.0))
    }

    @Test
    fun rerouting_after50Metres() {
        val origin = GeoPoint(32.75, -97.33)
        assertTrue(Walking.shouldReroute(null, origin))
        assertFalse(Walking.shouldReroute(origin, GeoPoint(32.75027, -97.33))) // ~30 m
        assertTrue(Walking.shouldReroute(origin, GeoPoint(32.75054, -97.33))) // ~60 m
    }

    @Test
    fun protobufDurations() {
        assertEquals(165L, Walking.parseDurationSeconds("165s"))
        assertEquals(166L, Walking.parseDurationSeconds("165.6s"))
        assertEquals(0L, Walking.parseDurationSeconds("0s"))
        assertNull(Walking.parseDurationSeconds("abc"))
        assertNull(Walking.parseDurationSeconds("-3s"))
        assertNull(Walking.parseDurationSeconds(null))
    }

    @Test
    fun polyline_decodesGooglesExample() {
        val points = EncodedPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 1e-9)
        assertEquals(-120.2, points[0].longitude, 1e-9)
        assertEquals(40.7, points[1].latitude, 1e-9)
        assertEquals(-120.95, points[1].longitude, 1e-9)
        assertEquals(43.252, points[2].latitude, 1e-9)
        assertEquals(-126.453, points[2].longitude, 1e-9)
    }

    @Test
    fun polyline_roundTripsAndToleratesTruncation() {
        val route = listOf(GeoPoint(32.75, -97.33), GeoPoint(32.75123, -97.32987), GeoPoint(32.7501, -97.3311))
        val encoded = EncodedPolyline.encode(route)
        assertEquals("_p~iF~ps|U", EncodedPolyline.encode(listOf(GeoPoint(38.5, -120.2))))
        val decoded = EncodedPolyline.decode(encoded)
        assertEquals(route.size, decoded.size)
        route.zip(decoded).forEach { (a, b) ->
            assertEquals(a.latitude, b.latitude, 1e-5)
            assertEquals(a.longitude, b.longitude, 1e-5)
        }
        assertEquals(2, EncodedPolyline.decode(encoded.dropLast(1)).size)
        assertTrue(EncodedPolyline.decode("").isEmpty())
    }
}
