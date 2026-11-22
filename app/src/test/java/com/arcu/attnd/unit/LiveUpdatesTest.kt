package com.arcu.attnd.unit

import com.arcu.attnd.domain.LiveUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

/** API-007: push-driven refresh, the safety poll and the offline notice text. */
class LiveUpdatesTest {

    @Test
    fun pollInterval_fiveMinutesWithPush_oneMinuteWithout() {
        assertEquals(5 * 60_000L, LiveUpdates.pollIntervalMs(hasPushToken = true))
        assertEquals(60_000L, LiveUpdates.pollIntervalMs(hasPushToken = false))
    }

    @Test
    fun pollInterval_fifteenSecondsAfterAFailedRefresh() {
        // iOS LiveRefreshPolicy: 15 s while the last refresh failed, with or without push.
        assertEquals(15_000L, LiveUpdates.pollIntervalMs(hasPushToken = true, lastRefreshFailed = true))
        assertEquals(15_000L, LiveUpdates.pollIntervalMs(hasPushToken = false, lastRefreshFailed = true))
        assertEquals(5 * 60_000L, LiveUpdates.pollIntervalMs(hasPushToken = true, lastRefreshFailed = false))
    }

    @Test
    fun appStatus_oncePerLaunchThenAtMostHourly() {
        val now = 10_000_000L
        assertTrue(LiveUpdates.shouldCheckAppStatus(lastCheckedAt = null, now = now))
        assertFalse(LiveUpdates.shouldCheckAppStatus(lastCheckedAt = now - 73_000L, now = now))
        assertFalse(LiveUpdates.shouldCheckAppStatus(lastCheckedAt = now - 3_599_999L, now = now))
        assertTrue(LiveUpdates.shouldCheckAppStatus(lastCheckedAt = now - 3_600_000L, now = now))
        assertTrue("a clock set back asks again", LiveUpdates.shouldCheckAppStatus(lastCheckedAt = now + 60_000L, now = now))
    }

    @Test
    fun recentFetch_skipsForegroundAndPollsOnly() {
        val now = 1_000_000L
        assertTrue(LiveUpdates.skipsRecentFetch("foreground", lastFetchAt = now - 14_999L, now = now))
        assertTrue(LiveUpdates.skipsRecentFetch("poll", lastFetchAt = now - 1_000L, now = now))
        assertFalse(LiveUpdates.skipsRecentFetch("poll", lastFetchAt = now - 15_000L, now = now))
        assertFalse(LiveUpdates.skipsRecentFetch("foreground", lastFetchAt = null, now = now))
        for (source in listOf("push", "pull", "join")) {
            assertFalse(source, LiveUpdates.skipsRecentFetch(source, lastFetchAt = now - 1_000L, now = now))
        }
    }

    @Test
    fun deviceRegistration_onlyWhenSomethingChanged() {
        val key = LiveUpdates.deviceRegistrationKey("ff00", subscribed = true, eventId = 4)
        assertEquals(key, LiveUpdates.deviceRegistrationKey("ff00", subscribed = true, eventId = 4))
        assertFalse(LiveUpdates.shouldRegisterDevice(key, lastAccepted = key))
        assertTrue("never accepted, or the last one failed", LiveUpdates.shouldRegisterDevice(key, lastAccepted = null))
        assertTrue(LiveUpdates.shouldRegisterDevice(LiveUpdates.deviceRegistrationKey("ee11", true, 4), key))
        assertTrue(LiveUpdates.shouldRegisterDevice(LiveUpdates.deviceRegistrationKey("ff00", false, 4), key))
        assertTrue(LiveUpdates.shouldRegisterDevice(LiveUpdates.deviceRegistrationKey("ff00", true, null), key))
        // No token and a blank token are the same request (the key is left out, never sent empty).
        assertEquals(LiveUpdates.deviceRegistrationKey(null, false, null), LiveUpdates.deviceRegistrationKey(" ", false, null))
    }

    @Test
    fun contentUpdatedJitter() {
        // API-026, iOS testContentUpdatedJitter: FCM data values are strings.
        assertEquals(1_000L, LiveUpdates.pushJitterMs("1000"))
        assertEquals(0L, LiveUpdates.pushJitterMs("0"))
        assertEquals(60_000L, LiveUpdates.pushJitterMs("999999"))
        assertEquals(0L, LiveUpdates.pushJitterMs("-5"))
        assertEquals(null, LiveUpdates.pushJitterMs(null))
        assertEquals(null, LiveUpdates.pushJitterMs("soon"))
        assertEquals(0L, LiveUpdates.pushRefreshDelayMs(maxMs = 0L))
        val random = Random(3)
        repeat(500) {
            assertTrue(LiveUpdates.pushRefreshDelayMs(maxMs = 1_000L, random = random) in 0L..1_000L)
            assertTrue(LiveUpdates.pushRefreshDelayMs(maxMs = null, random = random) in 0L..10_000L)
            assertTrue(LiveUpdates.pushRefreshDelayMs(maxMs = 999_999L, random = random) in 0L..60_000L)
        }
    }

    @Test
    fun offlineNotice_afterFirstFailedPoll_atTheseIntervals() {
        assertEquals(1, LiveUpdates.failuresBeforeOfflineNotice(LiveUpdates.pollIntervalMs(true)))
        assertEquals(1, LiveUpdates.failuresBeforeOfflineNotice(LiveUpdates.pollIntervalMs(false)))
        assertEquals(3, LiveUpdates.failuresBeforeOfflineNotice(5_000L))
    }

    @Test
    fun pushRefresh_onlyForNewerOrUnknownVersions() {
        assertTrue(LiveUpdates.shouldRefreshForPush(pushVersion = 16, currentVersion = 15))
        assertFalse(LiveUpdates.shouldRefreshForPush(pushVersion = 15, currentVersion = 15))
        assertFalse(LiveUpdates.shouldRefreshForPush(pushVersion = 14, currentVersion = 15))
        assertTrue(LiveUpdates.shouldRefreshForPush(pushVersion = null, currentVersion = 15))
        assertTrue(LiveUpdates.shouldRefreshForPush(pushVersion = 3, currentVersion = null))
    }

    @Test
    fun pushRefreshDelay_isZeroToTenSeconds() {
        val random = Random(7)
        repeat(1_000) {
            assertTrue(LiveUpdates.pushRefreshDelayMs(random = random) in 0L..10_000L)
        }
    }

    @Test
    fun offlineNoticeText_showsLastUpdatedInDeviceZone() {
        val zone = ZoneId.of("America/Chicago")
        val synced = Instant.parse("2026-11-14T20:14:00Z").toEpochMilli() // 2:14 PM in Chicago
        val now = Instant.parse("2026-11-14T20:30:00Z").toEpochMilli()
        assertEquals(
            "Offline · Updated 2:14 PM · everything still works",
            LiveUpdates.offlineNoticeText(offline = true, synced, now, zone, is24Hour = false, Locale.US),
        )
        assertEquals(
            "Offline · Updated 14:14 · everything still works",
            LiveUpdates.offlineNoticeText(offline = true, synced, now, zone, is24Hour = true, Locale.US),
        )
        assertEquals(
            "Can't update · Updated 2:14 PM",
            LiveUpdates.offlineNoticeText(offline = false, synced, now, zone, false, Locale.US),
        )
    }

    @Test
    fun offlineNoticeText_addsDateWhenNotToday_andNoTimeWhenNeverSynced() {
        val zone = ZoneId.of("America/Chicago")
        val synced = Instant.parse("2026-11-14T20:14:00Z").toEpochMilli()
        val nextDay = Instant.parse("2026-11-15T15:00:00Z").toEpochMilli()
        assertEquals(
            "Offline · Updated Nov 14, 2:14 PM · everything still works",
            LiveUpdates.offlineNoticeText(offline = true, synced, nextDay, zone, is24Hour = false, Locale.US),
        )
        assertEquals("Offline", LiveUpdates.offlineNoticeText(offline = true, lastSyncedAt = null, now = nextDay, zone = zone, is24Hour = false))
    }
}
