package com.arcu.attnd.unit

import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.LocationGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JoinLinksTest {

    @Test
    fun parseJoinCode_fromAppLinksAndQrCodes() {
        assertEquals("FWFM2026", JoinLinks.parseJoinCode("https://attnd.app/join/FWFM2026/"))
        assertEquals("FWFM2026", JoinLinks.parseJoinCode("https://attnd.app/join/FWFM2026"))
        assertEquals("FWFM2026", JoinLinks.parseJoinCode("https://www.attnd.app/join/FWFM2026/?utm=qr#top"))
        assertEquals("FWFM2026", JoinLinks.parseJoinCode("attnd.app/join/FWFM2026/"))
        assertEquals("A B", JoinLinks.parseJoinCode("https://attnd.app/join/A%20B/"))
        assertEquals("FWFM2026", JoinLinks.parseJoinCode("  FWFM2026 "))
    }

    @Test
    fun parseJoinCode_rejectsOtherUrls() {
        assertNull(JoinLinks.parseJoinCode("https://example.com/join/FWFM2026/"))
        assertNull(JoinLinks.parseJoinCode("https://attnd.app/events/FWFM2026/"))
        assertNull(JoinLinks.parseJoinCode("https://attnd.app/join/"))
        assertNull(JoinLinks.parseJoinCode(""))
        assertNull(JoinLinks.parseJoinCode(null))
    }

    @Test
    fun parseReferrerEventCode() {
        assertEquals("FWFM2026", JoinLinks.parseReferrerEventCode("event_code=FWFM2026"))
        assertEquals("FWFM2026", JoinLinks.parseReferrerEventCode("event_code%3DFWFM2026"))
        assertEquals("FWFM2026", JoinLinks.parseReferrerEventCode("utm_source=google-play&event_code=FWFM2026&x=1"))
        assertNull(JoinLinks.parseReferrerEventCode("utm_source=google-play&utm_medium=organic"))
        assertNull(JoinLinks.parseReferrerEventCode("event_code="))
        assertNull(JoinLinks.parseReferrerEventCode(null))
    }

    @Test
    fun geoPoint_rejectsNullZeroAndOutOfRange() {
        assertNotNull(GeoPoint.of(32.7, -97.3))
        assertNull(GeoPoint.of(null, -97.3))
        assertNull(GeoPoint.of(32.7, null))
        assertNull(GeoPoint.of(0.0, 0.0))
        assertNull(GeoPoint.of(91.0, 0.0))
        assertNull(GeoPoint.of(Double.NaN, 1.0))
        // Only the exact 0,0 placeholder is rejected; the equator or prime meridian alone is real.
        assertNotNull(GeoPoint.of(0.0, 32.5))
    }

    @Test
    fun bounds_enclosesAllPoints() {
        val (sw, ne) = LocationGrouping.bounds(listOf(GeoPoint(32.7, -97.4), GeoPoint(32.8, -97.3), GeoPoint(32.75, -97.35)))!!
        assertEquals(GeoPoint(32.7, -97.4), sw)
        assertEquals(GeoPoint(32.8, -97.3), ne)
        assertNull(LocationGrouping.bounds(emptyList()))
    }
}
