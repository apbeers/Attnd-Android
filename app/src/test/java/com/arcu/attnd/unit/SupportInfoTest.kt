package com.arcu.attnd.unit

import com.arcu.attnd.domain.SupportInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** API-004: support code and the "Copy support info" text. */
class SupportInfoTest {

    private val info = SupportInfo(
        appVersion = "2.0.57",
        versionCode = 57000,
        buildType = "release",
        serverHost = "attnd.app",
        apiVersion = 2,
        osVersion = "16 (API 36)",
        device = "Google Pixel 10 Pro Fold",
        deviceId = "3f2a9c1b-7d4e-4a2b-9c1d-0e5f6a7b8c9d",
        eventName = "Fort Worth Food & Music",
        eventCode = "FWFM2026",
        eventId = 12,
        contentVersion = 15,
        lastSyncedAt = Instant.parse("2026-11-14T16:05:00Z").toEpochMilli(),
    )

    @Test
    fun supportCode_isFirstEightCharactersWithoutDashes_upperCase() {
        assertEquals("3F2A9C1B", info.supportCode)
        assertEquals("AB12CD34", SupportInfo.supportCode("ab-12-cd-34-ef56"))
        assertEquals("ABC", SupportInfo.supportCode("a-b-c"))
    }

    @Test
    fun testBuild_whenServerIsNotProduction() {
        assertFalse(info.isTestBuild)
        assertTrue(info.copy(serverHost = "dev.attnd.app").isTestBuild)
    }

    @Test
    fun plainText_containsEverythingIncludingFullDeviceId() {
        val text = info.plainText(ZoneId.of("America/Chicago"))
        listOf(
            "Support code: 3F2A9C1B",
            "Device ID: 3f2a9c1b-7d4e-4a2b-9c1d-0e5f6a7b8c9d",
            "App version: 2.0.57 (57000)",
            "Build type: release",
            "Server: attnd.app",
            "API version: v2",
            "Platform: Android 16 (API 36)",
            "Device: Google Pixel 10 Pro Fold",
            "Event: Fort Worth Food & Music",
            "Event code: FWFM2026",
            "Event ID: 12",
            "Content version: 15",
            "Last refresh: 2026-11-14 10:05:00 CST",
        ).forEach { assertTrue("missing '$it' in:\n$text", text.contains(it)) }
    }

    @Test
    fun lastRefresh_neverWhenUnknown() {
        assertEquals("Never", info.copy(lastSyncedAt = null).lastRefreshText(ZoneId.of("UTC")))
    }
}
