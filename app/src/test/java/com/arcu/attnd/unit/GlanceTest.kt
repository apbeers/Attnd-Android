package com.arcu.attnd.unit

import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.UnreadUpdates
import com.arcu.attnd.unit.GlanceFixture.bundle
import com.arcu.attnd.unit.GlanceFixture.now
import com.arcu.attnd.unit.GlanceFixture.sat
import com.arcu.attnd.unit.GlanceFixture.sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** Glanceables (shared spec with iOS): live pins, "Next" subtitles, location lines, Now playing, progress, unread updates. */
class GlanceTest {

    private val locale = Locale.US

    @Test
    fun locationLine_nowAndNext() {
        // Main Stage: Roaming Mariachi (from 12:30) and Sound Bath (from 1:30) are on: the earliest-starting
        // one is "Now"; Jazz Night is next at 7:00.
        assertEquals("Now: Roaming Mariachi · Next 7:00 Jazz Night", Glance.locationLine(bundle, 10, now, locale))
        assertEquals(listOf(1, 9), Glance.nowAt(bundle, 10, now).map { it.id })
        // Taco Truck: only next.
        assertEquals("Next 2:30 Taco Tasting", Glance.locationLine(bundle, 11, now, locale))
        // Café Olé: tomorrow, so the day is named.
        assertEquals("Next Sun 10:00 Sunday Brunch", Glance.locationLine(bundle, 12, now, locale))
        // After everything: nothing to say.
        assertNull(Glance.locationLine(bundle, 11, Instant.parse("2026-11-16T00:00:00Z"), locale))
        // No schedule at all.
        assertNull(Glance.locationLine(bundle, 99, now, locale))
    }

    @Test
    fun locationLine_nowOnly() {
        // Sat 9:45 PM: Late set (9:30, no end → an hour) is on; nothing later at Main Stage.
        val late = Instant.parse("2026-11-15T03:45:00Z")
        assertEquals("Now: Late set", Glance.locationLine(bundle, 10, late, locale))
    }

    @Test
    fun pins_liveDotAndNextWithinThreeHours() {
        assertTrue(Glance.isLive(bundle, 10, now))
        assertFalse(Glance.isLive(bundle, 11, now))
        // Jazz Night is 5 h away: no subtitle.
        assertNull(Glance.pinSubtitle(bundle, 10, now, locale))
        assertEquals("Next 2:30", Glance.pinSubtitle(bundle, 11, now, locale))
        assertNull(Glance.pinSubtitle(bundle, 12, now, locale))
        // Exactly 3 h before still counts; a minute earlier doesn't.
        assertEquals("Next 7:00", Glance.pinSubtitle(bundle, 10, sat("16:00").toInstant(), locale))
        assertNull(Glance.pinSubtitle(bundle, 10, sat("15:59").toInstant(), locale))
    }

    @Test
    fun nowPlaying_byStart_onlyLocationsWithCoordinates() {
        // Drum Circle (no location) is on too, but there is nothing to select.
        assertEquals(
            listOf("Main Stage · Roaming Mariachi", "Main Stage · Sound Bath"),
            Glance.nowPlaying(bundle, now).map { it.label },
        )
        // 2:45 PM: Mariachi (12:30) before Taco Tasting (2:30).
        assertEquals(
            listOf("Main Stage · Roaming Mariachi", "Taco Truck · Taco Tasting"),
            Glance.nowPlaying(bundle, sat("14:45").toInstant()).map { it.label },
        )
        // Sunday Brunch is at Café Olé, which has no coordinates.
        assertTrue(Glance.nowPlaying(bundle, sun("10:30").toInstant()).isEmpty())
    }

    @Test
    fun progress_ofAnItemInProgress() {
        val mariachi = bundle.scheduleItem(1)!!
        // 12:30–3:00, now 2:00: 90 of 150 minutes.
        assertEquals(0.6f, Glance.progress(mariachi, now)!!, 1e-4f)
        assertNull(Glance.progress(bundle.scheduleItem(2)!!, now))
        assertNull(Glance.progress(bundle.scheduleItem(7)!!, now))
    }

    @Test
    fun unread_countsUpdatesAfterTheTabWasSeen() {
        val notifications = bundle.notifications
        assertEquals(2, UnreadUpdates.unreadCount(notifications, null))
        assertEquals(1, UnreadUpdates.unreadCount(notifications, sat("10:00").toInstant()))
        assertEquals(0, UnreadUpdates.unreadCount(notifications, sat("11:00").toInstant()))
        // Updates without a send time never count.
        val undated = notifications + EventNotification(22, "Undated", "", null)
        assertEquals(2, UnreadUpdates.unreadCount(undated, null))
        assertEquals(0, UnreadUpdates.unreadCount(undated, now))
    }

    @Test
    fun banner_newestUnreadFor24Hours() {
        val notifications = bundle.notifications
        assertEquals(21, UnreadUpdates.banner(notifications, null, null, now)?.id)
        assertEquals(21, UnreadUpdates.banner(notifications, sat("10:00").toInstant(), null, now)?.id)
        assertNull(UnreadUpdates.banner(notifications, sat("11:00").toInstant(), null, now))
        // Dismissed: no banner (the older update doesn't take its place).
        assertNull(UnreadUpdates.banner(notifications, null, 21, now))
        // 24 h after it was sent it is gone.
        assertEquals(21, UnreadUpdates.banner(notifications, null, null, sun("11:00").toInstant())?.id)
        assertNull(UnreadUpdates.banner(notifications, null, null, sun("11:01").toInstant()))
    }

    @Test
    fun seenAt_isNowOrTheNewestUpdate() {
        assertEquals(now, UnreadUpdates.seenAt(bundle.notifications, now))
        val ahead = bundle.notifications + EventNotification(23, "From the future", "", sat("14:05"))
        assertEquals(sat("14:05").toInstant(), UnreadUpdates.seenAt(ahead, now))
    }
}
