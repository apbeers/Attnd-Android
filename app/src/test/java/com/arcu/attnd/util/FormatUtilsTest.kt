package com.arcu.attnd.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun testFormatDistance_veryClose() {
        val result = FormatUtils.formatDistance(0.05)
        assertEquals("Very close", result)
    }

    @Test
    fun testFormatDistance_miles() {
        val result = FormatUtils.formatDistance(0.5)
        assertEquals("0.5 miles", result)
    }

    @Test
    fun testFormatDistance_rounded() {
        val result = FormatUtils.formatDistance(2.7)
        assertEquals("3 miles", result)
    }

    @Test
    fun testFormatHoursRemaining_startingSoon() {
        val result = FormatUtils.formatHoursRemaining(0.5)
        assertEquals("Starting soon", result)
    }

    @Test
    fun testFormatHoursRemaining_hours() {
        val result = FormatUtils.formatHoursRemaining(5.0)
        assertEquals("5h left", result)
    }
}
