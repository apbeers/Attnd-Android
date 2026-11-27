package com.arcu.attnd.unit

import com.arcu.attnd.domain.FoldPosture
import com.arcu.attnd.domain.FoldRect
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.TabletopGlance
import com.arcu.attnd.domain.UndoToastPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Foldable rules (phase 10a): iOS `DisplayLogicTests.testFoldPosture`, `DuoFeaturesTests` (Undo toast, tabletop
 * glance) case for case, plus Android's zero-width flat hinge, the half-opened gate and the Undo toast
 * pane. Fixed now Sat Nov 14 2026 2:00 PM America/Chicago (20:00 UTC).
 */
class FoldRulesTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = FoldRect(x, y, x + w, y + h)

    @Test
    fun foldPosture_iosCases() {
        assertEquals(FoldPosture.Flat, FoldPosture.of(951.0, 669.0, emptyList()))
        // A horizontal fold across the middle: tabletop.
        assertEquals(FoldPosture.Tabletop(320.0, 344.0), FoldPosture.of(951.0, 669.0, listOf(rect(0.0, 320.0, 951.0, 24.0))))
        // A vertical fold: book.
        assertEquals(FoldPosture.Book(322.0, 346.0), FoldPosture.of(669.0, 951.0, listOf(rect(322.0, 0.0, 24.0, 951.0))))
        // A small strip at the edge (not a fold across the screen): flat.
        assertEquals(FoldPosture.Flat, FoldPosture.of(951.0, 669.0, listOf(rect(0.0, 0.0, 951.0, 30.0))))
    }

    @Test
    fun foldPosture_android() {
        // Android's flat (non-occluding) fold has zero width: still a book fold.
        assertEquals(FoldPosture.Book(420.0, 420.0), FoldPosture.of(840.0, 900.0, listOf(rect(420.0, 0.0, 0.0, 900.0))))
        // A horizontal hinge while the phone is flat open (landscape inner display) is not tabletop.
        assertEquals(FoldPosture.Flat, FoldPosture.of(951.0, 669.0, listOf(rect(0.0, 334.0, 951.0, 0.0)), halfOpened = false))
        assertEquals(FoldPosture.Tabletop(334.0, 334.0), FoldPosture.of(951.0, 669.0, listOf(rect(0.0, 334.0, 951.0, 0.0))))
        // A hinge outside this container (the list pane of a list-detail): flat.
        assertEquals(FoldPosture.Flat, FoldPosture.of(400.0, 900.0, listOf(rect(420.0, 0.0, 0.0, 900.0))))
        // A hinge reported in window coordinates, moved into a detail pane that starts at 420: at its edge, flat.
        assertEquals(FoldPosture.Flat, FoldPosture.of(420.0, 900.0, listOf(rect(0.0, 0.0, 0.0, 900.0))))
    }

    // iOS DuoFeaturesTests.testUndoToastStaysInOnePane

    @Test
    fun undoToastStaysInOnePane() {
        assertEquals(0.0..400.0, UndoToastPane.span(400.0, null, null, 300.0))
        assertEquals(460.0..900.0, UndoToastPane.span(900.0, 440.0..460.0, null, 700.0))
        assertEquals(0.0..440.0, UndoToastPane.span(900.0, 440.0..460.0, null, 100.0))
        // No tap position: the main pane, which PanePair places leading.
        assertEquals(0.0..440.0, UndoToastPane.span(900.0, 440.0..460.0, null, null))
        assertEquals(0.0..330.0, UndoToastPane.span(640.0, null, 330.0, null))
        assertEquals(0.0..640.0, UndoToastPane.span(640.0, null, 600.0, null))
    }

    // iOS DuoFeaturesTests tabletop cases (same schedule)

    private val now: Instant = Instant.ofEpochSecond(1_794_686_400)
    private fun at(minutes: Long): OffsetDateTime = now.plusSeconds(minutes * 60).atOffset(ZoneOffset.UTC)
    private fun item(id: Int, start: Long, end: Long?, location: Int?) =
        ScheduleItem(id, "Set $id", "", at(start), end?.let { at(it) }, location, null, emptyList())

    private val schedule = listOf(
        item(10, -30, 30, 1),
        item(11, 60, 120, 1),
        item(20, 45, 105, 2),
        item(21, 150, null, 2),
        item(30, -120, -60, 3),
        item(40, 30, 90, null),
    )

    @Test
    fun tabletopGlancePrefersMySchedule() {
        val event = TabletopGlance.of(schedule, mine = emptySet(), now = now)
        assertEquals(10, event.now?.id)
        assertEquals(40, event.next?.id)
        assertFalse(event.nowIsMine)
        val mine = TabletopGlance.of(schedule, mine = setOf(11), now = now)
        assertEquals(10, mine.now?.id)
        assertEquals(11, mine.next?.id)
        assertTrue(mine.nextIsMine)
        val later = TabletopGlance.of(schedule, mine = emptySet(), now = now.plusSeconds(300 * 60))
        assertNull(later.now)
        assertNull(later.next)
    }

    @Test
    fun tabletopOfferOnlyOnceWhileLive() {
        fun t(m: Long) = at(m).toInstant()
        assertTrue(TabletopGlance.isLive(t(-60), t(600), emptyList(), now))
        assertFalse(TabletopGlance.isLive(t(60), t(600), schedule, now))
        assertTrue(TabletopGlance.isLive(null, null, schedule, now))
        assertFalse(TabletopGlance.isLive(null, null, listOf(item(1, 200, 260, 1)), now))
        assertTrue(TabletopGlance.shouldOffer(live = true, alreadyOffered = false, modeOn = false))
        assertFalse(TabletopGlance.shouldOffer(live = true, alreadyOffered = true, modeOn = false))
        assertFalse(TabletopGlance.shouldOffer(live = false, alreadyOffered = false, modeOn = false))
    }

    @Test
    fun tabletopCountdown() {
        assertEquals("1:05:09", TabletopGlance.countdown(now, now.plusSeconds(3909)))
        assertEquals("12:05", TabletopGlance.countdown(now, now.plusSeconds(725)))
        assertEquals("0:09", TabletopGlance.countdown(now, now.plusSeconds(9)))
        assertEquals("0:00", TabletopGlance.countdown(now, now.minusSeconds(30)))
    }
}
