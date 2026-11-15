package com.arcu.attnd.unit

import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveFollow
import com.arcu.attnd.domain.LiveMode
import com.arcu.attnd.domain.LiveSegment
import com.arcu.attnd.domain.LiveSource
import com.arcu.attnd.domain.PlanPreferences
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.UrgentBlock
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Shared spec §8: Live Update content, windows, wake-ups, reminders and the urgent block. */
class LiveFollowTest {

    private val saturday = LocalDate.of(2026, 11, 14)
    private val centre = GeoPoint(32.7495, -97.3316)
    private fun at(localTime: String) = PlannerFixture.sat(localTime).toInstant()

    @Test
    fun windowIsTwoHoursBeforeTheFirstItemToHalfAnHourAfterTheLast() {
        val (start, end) = LiveFollow.window(bundle, null, hearts, saturday)!!
        assertEquals(at("17:00"), start) // The Prairie Lights at 7 PM
        assertEquals(at("21:30"), end) // Neon Armadillo ends at 9 PM
        // Without hearts or a plan: the event's own day, from 2 h before the gates.
        val (eventStart, eventEnd) = LiveFollow.window(bundle, null, Hearts.NONE, saturday)!!
        assertEquals(at("08:00"), eventStart)
        assertEquals(at("23:30"), eventEnd)
        assertNull(LiveFollow.window(bundle, null, hearts, LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun theSourceIsThePlanThenHeartsThenTheEvent() {
        val plan = Planner(bundle, PlanPreferences(likedTags = setOf("bbq")), Hearts.NONE).plan(now, "")
        assertEquals(LiveSource.PLAN, LiveFollow.items(bundle, plan, hearts, saturday, emptySet()).first)
        assertEquals(LiveSource.HEARTS, LiveFollow.items(bundle, null, hearts, saturday, emptySet()).first)
        assertEquals(LiveSource.EVENT, LiveFollow.items(bundle, null, Hearts.NONE, saturday, emptySet()).first)
        // Sunday has no hearted items: the event's own Sunday.
        assertEquals(LiveSource.EVENT, LiveFollow.items(bundle, null, hearts, LocalDate.of(2026, 11, 15), emptySet()).first)
    }

    @Test
    fun statesThroughTheEvening() {
        assertNull(LiveFollow.state(bundle, null, hearts, at("16:59"), locale = Locale.US))

        val before = LiveFollow.state(bundle, null, hearts, at("17:30"), user = centre, locale = Locale.US)!!
        assertEquals(LiveMode.BEFORE, before.mode)
        assertEquals("The Prairie Lights at 7:00 PM", before.title)
        assertEquals("Main Stage · 2 min walk · then Neon Armadillo", before.text)
        assertEquals("7:00", before.chip)
        assertEquals(at("19:00"), before.countdownTo)
        assertEquals(101, before.focus?.id)

        val during = LiveFollow.state(bundle, null, hearts, at("19:10"), locale = Locale.US)!!
        assertEquals(LiveMode.ACTIVE, during.mode)
        assertEquals("Now: The Prairie Lights · Main Stage", during.title)
        assertEquals("Up next 8:00 PM: Neon Armadillo · Trinity Stage · 4 min walk", during.text)
        assertEquals("8:00", during.chip)
        assertEquals(4, during.walkMinutesToNext)
        assertEquals(listOf(LiveSegment(120, isItem = true)), during.segments)
        assertEquals(10, during.progress)
        assertEquals(listOf(60), during.points)

        val skipped = LiveFollow.state(bundle, null, hearts, at("19:10"), skipped = setOf(114), locale = Locale.US)!!
        assertNull(skipped.next)
        assertEquals("Ends 8:30 PM", skipped.text)

        val wrap = LiveFollow.state(bundle, null, hearts, at("21:10"), locale = Locale.US)!!
        assertEquals(LiveMode.END_OF_DAY, wrap.mode)
        assertEquals("That's a wrap for today", wrap.title)
        assertEquals("2 hearted items today", wrap.text)
        assertNull(LiveFollow.state(bundle, null, hearts, at("21:31"), locale = Locale.US))
    }

    @Test
    fun arrivingAtTheVenueStartsTheDayEarly() {
        assertNull(LiveFollow.state(bundle, null, hearts, at("14:00"), locale = Locale.US))
        val arrived = LiveFollow.state(bundle, null, hearts, at("14:00"), arrived = true, locale = Locale.US)!!
        assertEquals(LiveMode.BEFORE, arrived.mode)
        assertEquals(101, arrived.next?.id)
        assertEquals(at("14:15"), LiveFollow.nextWake(bundle, null, hearts, at("14:00"), arrived = true))
    }

    @Test
    fun gatesOpenBeforeTheEvent() {
        val state = LiveFollow.state(bundle, null, Hearts.NONE, at("09:00"), locale = Locale.US)!!
        assertEquals(LiveMode.BEFORE, state.mode)
        assertEquals("Gates open at 10:00 AM", state.title)
        assertEquals("First up: Gates open", state.text)
        assertEquals("10:00", state.chip)
        assertEquals(at("10:00"), state.gatesOpen)
    }

    @Test
    fun urgentReplacesEverythingForTwelveHoursOrUntilDismissed() {
        val urgent = UrgentBlock(31, "Main Stage closed", "Storm on the way, head indoors.", at("18:00"))
        val state = LiveFollow.state(bundle, null, hearts, at("18:30"), urgent = urgent, locale = Locale.US)!!
        assertEquals(LiveMode.URGENT, state.mode)
        assertEquals("Main Stage closed", state.title)
        assertEquals("Urgent", state.chip)
        // Shown even outside the window, until 12 h after it was sent.
        assertNotNull(LiveFollow.state(bundle, null, hearts, Instant.parse("2026-11-15T11:59:00Z"), urgent = urgent))
        assertNull(LiveFollow.state(bundle, null, hearts, Instant.parse("2026-11-15T12:00:00Z"), urgent = urgent))
        assertEquals(LiveMode.BEFORE, LiveFollow.state(bundle, null, hearts, at("18:30"), urgent = urgent, dismissedUrgentId = 31)!!.mode)
    }

    @Test
    fun nextWakeFollowsTheDay() {
        assertEquals(at("17:00"), LiveFollow.nextWake(bundle, null, hearts, now))
        assertEquals(at("19:00"), LiveFollow.nextWake(bundle, null, hearts, at("18:50")))
        assertEquals(at("17:45"), LiveFollow.nextWake(bundle, null, hearts, at("17:30"))) // refresh every 15 min
        val reminders = LiveFollow.reminders(bundle, null, hearts, now, centre)
        assertEquals(at("16:45"), LiveFollow.nextWake(bundle, null, hearts, at("16:40"), reminders = listOf(101 to at("16:45"))))
        assertTrue(reminders.isNotEmpty())
        // Sunday's own items (no hearts on Sunday) after Saturday ends.
        assertEquals(PlannerFixture.sun("07:00").toInstant(), LiveFollow.nextWake(bundle, null, hearts, at("22:00")))
    }

    @Test
    fun venueFenceCoversEveryLocation() {
        val (centre, radius) = LiveFollow.venueFence(bundle)!!
        assertEquals(GeoPoint(32.7495, -97.3316), centre)
        assertEquals(394.0, radius, 1.0) // Panther Island Pavilion is ~244 m from the centre, + 150 m
        val tiny = bundle.copy(locations = bundle.locations.filter { it.coordinate == null })
        assertEquals(LiveFollow.MIN_FENCE_METERS, LiveFollow.venueFence(tiny)!!.second, 0.0)
        val nowhere = bundle.copy(event = bundle.event.copy(coordinate = null), locations = bundle.locations.map { it.copy(coordinate = null) })
        assertNull(LiveFollow.venueFence(nowhere))
    }

    @Test
    fun remindersUseTheWalk() {
        val reminders = LiveFollow.reminders(bundle, null, hearts, now, centre)
        assertEquals(listOf(101 to at("18:43"), 114 to at("19:42")), reminders)
        // Without a position: 15 minutes before.
        assertEquals(listOf(101 to at("18:45"), 114 to at("19:45")), LiveFollow.reminders(bundle, null, hearts, now, null))
        assertEquals(listOf(114 to at("19:45")), LiveFollow.reminders(bundle, null, hearts, now, null, skipped = setOf(101)))
        val (title, text) = LiveFollow.reminderText(bundle, bundle.scheduleItem(101)!!, at("18:45"), centre, Locale.US)
        assertEquals("The Prairie Lights in 15 min · 2 min walk", title)
        assertEquals("Main Stage · 7:00 PM – 8:30 PM", text)
    }
}
