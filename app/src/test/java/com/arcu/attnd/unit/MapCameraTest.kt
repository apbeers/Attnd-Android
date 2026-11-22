package com.arcu.attnd.unit

import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.HangRule
import com.arcu.attnd.domain.MapCamera
import com.arcu.attnd.domain.MapFit
import com.arcu.attnd.domain.MapFollowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Map polish (iOS batch 7): follow modes, the filter refit, the cluster distance; the hang rule. */
class MapCameraTest {

    @Test
    fun locationButtonCyclesOffFollowHeadingOff() {
        assertEquals(MapFollowMode.FOLLOW, MapFollowMode.OFF.next())
        assertEquals(MapFollowMode.FOLLOW_HEADING, MapFollowMode.FOLLOW.next())
        assertEquals(MapFollowMode.OFF, MapFollowMode.FOLLOW_HEADING.next())
        MapFollowMode.entries.forEach { assertEquals(MapFollowMode.OFF, it.afterGesture()) }
    }

    @Test
    fun fitFramesThePinsLeft() {
        assertEquals(MapFit.Nothing, MapCamera.fit(emptyList()))
        val a = GeoPoint(32.75, -97.33)
        val b = GeoPoint(32.76, -97.35)
        val c = GeoPoint(32.74, -97.34)
        assertEquals(MapFit.Single(a), MapCamera.fit(listOf(a)))
        assertEquals(MapFit.Single(a), MapCamera.fit(listOf(a, a)))
        assertEquals(MapFit.Bounds(GeoPoint(32.74, -97.35), GeoPoint(32.76, -97.33), 3), MapCamera.fit(listOf(a, b, c)))
    }

    @Test
    fun refitOnlyWhenTheFilterChanges() {
        assertFalse(MapCamera.refitsOnFilter(null, null to false))
        assertFalse(MapCamera.refitsOnFilter("Food" to false, "Food" to false))
        assertTrue(MapCamera.refitsOnFilter(null to false, "Food" to false))
        assertTrue(MapCamera.refitsOnFilter("Food" to false, null to false))
        assertTrue(MapCamera.refitsOnFilter(null to false, null to true))
        assertEquals(24, MapCamera.CLUSTER_DISTANCE_DP)
    }

    @Test
    fun hangsAreStallsOver100ms() {
        assertFalse(HangRule.isHang(100))
        assertTrue(HangRule.isHang(101))
        assertEquals(50, HangRule.PING_INTERVAL_MS)
    }
}
