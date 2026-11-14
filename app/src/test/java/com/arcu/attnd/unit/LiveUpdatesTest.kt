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
            assertTrue(LiveUpdates.pushRefreshDelayMs(random) in 0L..10_000L)
        }
    }

    @Test
    fun offlineNoticeText_showsLastUpdatedInDeviceZone() {
        val zone = ZoneId.of("America/Chicago")
        val synced = Instant.parse("2026-11-14T20:14:00Z").toEpochMilli() // 2:14 PM in Chicago
        val now = Instant.parse("2026-11-14T20:30:00Z").toEpochMilli()
        val error = "Can't reach Attnd right now. Showing saved content."
        assertEquals(
            "Offline · last updated 2:14 PM",
            LiveUpdates.offlineNoticeText(error, offline = true, synced, now, zone, is24Hour = false, Locale.US),
        )
        assertEquals(
            "Offline · last updated 14:14",
            LiveUpdates.offlineNoticeText(error, offline = true, synced, now, zone, is24Hour = true, Locale.US),
        )
        assertEquals(
            "Server error (503). Showing saved content. Last updated 2:14 PM.",
            LiveUpdates.offlineNoticeText("Server error (503). Showing saved content.", offline = false, synced, now, zone, false, Locale.US),
        )
    }

    @Test
    fun offlineNoticeText_addsDateWhenNotToday_andNoTimeWhenNeverSynced() {
        val zone = ZoneId.of("America/Chicago")
        val synced = Instant.parse("2026-11-14T20:14:00Z").toEpochMilli()
        val nextDay = Instant.parse("2026-11-15T15:00:00Z").toEpochMilli()
        assertEquals(
            "Offline · last updated Nov 14, 2:14 PM",
            LiveUpdates.offlineNoticeText("x", offline = true, synced, nextDay, zone, is24Hour = false, Locale.US),
        )
        assertEquals("x", LiveUpdates.offlineNoticeText("x", offline = true, lastSyncedAt = null, now = nextDay, zone = zone, is24Hour = false))
    }
}
