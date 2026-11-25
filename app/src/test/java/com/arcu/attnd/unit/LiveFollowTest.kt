package com.arcu.attnd.unit

import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.FollowDay
import com.arcu.attnd.domain.FollowPlace
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.LiveActivitySettings
import com.arcu.attnd.domain.LiveContent
import com.arcu.attnd.domain.LiveDecision
import com.arcu.attnd.domain.LiveFollow
import com.arcu.attnd.domain.LiveMode
import com.arcu.attnd.domain.LiveSegment
import com.arcu.attnd.domain.LiveSource
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.NotificationPriority
import com.arcu.attnd.domain.RunningLate
import com.arcu.attnd.domain.ScheduleEntry
import com.arcu.attnd.domain.ScheduleEntryKind
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.UrgentBlock
import com.arcu.attnd.unit.GlanceFixture.item
import com.arcu.attnd.unit.GlanceFixture.location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Shared spec §8: the followed day, decision, content, urgent block and reminders — the cases of iOS
 * `LiveFollowTests` (fixed now Sat Nov 14 2026 2:00 PM America/Chicago, en_US) — plus Android's
 * notification texts on [PlannerFixture].
 */
class LiveFollowTest {

    private fun d(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()

    private val now = d("2026-11-14T14:00:00-06:00")
    private val chicago = ZoneId.of("America/Chicago")

    private val mainStage = location(10, "Main Stage", coordinate = GeoPoint(41.88, -87.63))
    private val foodCourt = location(20, "Food Court", coordinate = GeoPoint(41.881, -87.631))
    private val locations = listOf(mainStage, foodCourt)
    private val atFoodCourt = foodCourt.coordinate!!

    private fun sat(t: String) = OffsetDateTime.parse("2026-11-14T$t:00-06:00")
    private val schedule: List<ScheduleItem> = listOf(
        item(1, "Morning Yoga", sat("08:00"), sat("09:00"), locationId = 20),
        item(2, "Prairie Lights", sat("13:30"), sat("15:00"), locationId = 10),
        item(3, "Taco Tasting", sat("13:45"), sat("14:30"), locationId = 20),
        item(4, "Midnight Choir", sat("16:30"), sat("17:30"), locationId = 10),
        item(5, "Late Set", sat("20:30"), locationId = 10),
        item(6, "Sunday Brunch", OffsetDateTime.parse("2026-11-15T10:00:00-06:00"), OffsetDateTime.parse("2026-11-15T11:00:00-06:00"), locationId = 20),
        item(7, "To be announced", null),
    )
    private val eventStart = d("2026-11-14T09:00:00-06:00")

    private fun day(
        hearted: Set<Int> = emptySet(),
        places: List<FollowPlace> = emptyList(),
        at: Instant = now,
        schedule: List<ScheduleItem> = this.schedule,
    ) = LiveFollow.followDay(schedule, hearted, places, eventStart, at, chicago)

    private fun content(
        day: FollowDay,
        hearted: Set<Int> = emptySet(),
        skipped: Set<Int> = emptySet(),
        user: GeoPoint? = null,
        userAt: Instant? = null,
        at: Instant = now,
    ) = LiveFollow.content(day, locations, hearted, skipped, user, userAt, urgent = null, now = at)

    // ── The followed day ────────────────────────────────────────────────────

    @Test
    fun followDay_todayWithoutHeartsFollowsTheSchedule() {
        val today = day()!!
        assertFalse(today.followsHearts)
        assertEquals(listOf(1, 2, 3, 4, 5), today.items.map { it.id })
        assertEquals(d("2026-11-14T00:00:00-06:00"), today.dayStart)
        assertEquals(eventStart, today.gatesOpen)
        assertEquals(d("2026-11-14T06:00:00-06:00"), today.startAt)
        // The last item has no end: start + 1 h.
        assertEquals(d("2026-11-14T21:30:00-06:00"), today.endAt)
    }

    @Test
    fun followDay_heartsWhileOneIsLeftToday() {
        val hearted = day(hearted = setOf(2, 6))!!
        assertTrue(hearted.followsHearts)
        assertEquals(listOf(2), hearted.items.map { it.id })
        // The gates (9:00) open before the hearted item (13:30).
        assertEquals(d("2026-11-14T07:00:00-06:00"), hearted.startAt)
        // Once the hearted item is over, the rest of today's schedule.
        assertFalse(day(hearted = setOf(2, 6), at = d("2026-11-14T15:30:00-06:00"))!!.followsHearts)
        // Hearts only on another day: today follows the schedule.
        assertFalse(day(hearted = setOf(6))!!.followsHearts)
    }

    @Test
    fun followDay_nextDayOnceTodayIsOver() {
        val tomorrow = day(at = d("2026-11-14T22:00:00-06:00"))!!
        assertEquals(d("2026-11-15T00:00:00-06:00"), tomorrow.dayStart)
        assertEquals(listOf(6), tomorrow.items.map { it.id })
        assertNull(tomorrow.gatesOpen)
        assertEquals(d("2026-11-15T08:00:00-06:00"), tomorrow.startAt)
        assertNull(day(at = d("2026-11-15T12:00:00-06:00")))
    }

    @Test
    fun followDay_itemRunningPastMidnightCountsForToday() {
        val late = item(8, "After Hours", OffsetDateTime.parse("2026-11-13T23:00:00-06:00"), sat("01:00"), locationId = 10)
        val halfPast = d("2026-11-14T00:30:00-06:00")
        val today = day(at = halfPast, schedule = schedule + late)!!
        assertEquals(d("2026-11-14T00:00:00-06:00"), today.dayStart)
        assertEquals(8, today.items.first().id)
        assertEquals(8, content(today, at = halfPast).current?.itemId)
    }

    @Test
    fun decision() {
        assertEquals(LiveDecision.StartNow, LiveFollow.decision(day(), now, chicago))
        val dayBefore = d("2026-11-13T12:00:00-06:00")
        assertEquals(LiveDecision.Schedule(d("2026-11-14T06:00:00-06:00")), LiveFollow.decision(day(at = dayBefore), dayBefore, chicago))
        // More than 36 h ahead: nothing yet (Android still wakes at the day's start).
        val twoDaysBefore = d("2026-11-12T12:00:00-06:00")
        assertEquals(LiveDecision.Nothing, LiveFollow.decision(day(at = twoDaysBefore), twoDaysBefore, chicago))
        // Forced skips the 2 h lead today only.
        val early = d("2026-11-14T04:00:00-06:00")
        assertEquals(LiveDecision.Schedule(d("2026-11-14T06:00:00-06:00")), LiveFollow.decision(day(at = early), early, chicago))
        assertEquals(LiveDecision.StartNow, LiveFollow.decision(day(at = early), early, chicago, forced = true))
        val night = d("2026-11-14T22:00:00-06:00")
        assertEquals(LiveDecision.Schedule(d("2026-11-15T08:00:00-06:00")), LiveFollow.decision(day(at = night), night, chicago, forced = true))
        assertEquals(LiveDecision.Nothing, LiveFollow.decision(null, now, chicago))
    }

    // ── Content ─────────────────────────────────────────────────────────────

    @Test
    fun content_currentNextAfterAndWalk() {
        val state = content(day()!!, hearted = setOf(4), user = atFoodCourt)
        // Two items are on; the one nearest the user (Taco Tasting at the Food Court).
        assertEquals(3, state.current?.itemId)
        assertEquals("Food Court", state.current?.location)
        assertEquals(4, state.next?.itemId)
        assertEquals("Midnight Choir", state.next?.name)
        assertEquals("Main Stage", state.next?.location)
        assertEquals(10, state.next?.locationId)
        assertEquals(true, state.next?.hearted)
        assertEquals(5, state.after?.itemId)
        assertEquals(d("2026-11-14T21:30:00-06:00"), state.after?.end)
        // ~139 m to Main Stage: 139 × 1.2 / 1.3 m/s, rounded up.
        assertEquals(3, state.walkMinutesToNext)
        assertFalse(state.followsHearts)
        assertNull(state.opensAt)
        assertFalse(state.dayComplete)
        assertNull(state.urgent)
    }

    @Test
    fun content_skipAndNoLocation() {
        val state = content(day()!!, skipped = setOf(4))
        assertEquals(5, state.next?.itemId)
        assertNull(state.after)
        assertNull(state.walkMinutesToNext)
    }

    @Test
    fun content_gatesOpenAndDayComplete() {
        val early = d("2026-11-14T07:30:00-06:00")
        val morning = content(day(at = early, schedule = schedule.drop(1))!!, at = early)
        assertEquals(eventStart, morning.opensAt)
        assertNull(morning.current)
        assertEquals(2, morning.next?.itemId)

        val done = content(day()!!, at = d("2026-11-14T22:00:00-06:00"))
        assertTrue(done.dayComplete)
        assertEquals(5, done.completedCount)
        assertNull(done.current)
        assertNull(done.next)
    }

    @Test
    fun staleAt() {
        val state = content(day()!!)
        // Without a position Now is the earliest started (Prairie Lights, ends 15:00), plus a minute.
        assertEquals(2, state.current?.itemId)
        assertEquals(d("2026-11-14T15:01:00-06:00"), LiveFollow.staleAt(state, now))
        assertEquals(now.plus(Duration.ofHours(1)), LiveFollow.staleAt(LiveContent(), now))
    }

    @Test
    fun followDay_followsMySchedule() {
        // One list (Nov 22, 2026): no plan source; the planner's items are in My schedule.
        val mine = day(hearted = setOf(2, 5))!!
        assertTrue(mine.followsHearts)
        assertEquals(listOf(2, 5), mine.items.map { it.id })
        assertEquals(LiveSource.HEARTS, mine.source)
        val state = content(mine, hearted = setOf(2, 5))
        assertTrue(state.followsHearts)
        assertEquals(2, state.current?.itemId)
        assertEquals(5, state.next?.itemId)
    }

    @Test
    fun followDay_endedEntriesFallBackToTheDay() {
        // Only Morning Yoga (ended) is in My schedule today: the event's own day.
        val everything = day(hearted = setOf(1))!!
        assertFalse(everything.followsHearts)
        assertEquals(LiveSource.EVENT, everything.source)
        assertEquals(listOf(1, 2, 3, 4, 5), everything.items.map { it.id })
    }

    @Test
    fun content_gapInMyScheduleShowsWhatsOnAtTheEvent() {
        val mine = day(hearted = setOf(4))!!
        assertEquals(listOf(1, 2, 3, 4, 5), mine.eventItems.map { it.id })
        val state = content(mine, hearted = setOf(4), user = atFoodCourt)
        assertEquals(3, state.current?.itemId)
        assertEquals("event", state.nowSource)
        assertTrue(state.nowIsFromEvent)
        assertEquals(4, state.next?.itemId)
        assertEquals("schedule", state.nextSource)
        assertEquals(d("2026-11-14T16:30:00-06:00"), state.freeUntil)
        // Without a position: the earliest started (Prairie Lights, 1:30 PM).
        assertEquals(2, content(mine, hearted = setOf(4)).current?.itemId)

        // Mine in progress: no gap, Now from My schedule.
        val planned = content(day(hearted = setOf(2, 5))!!, hearted = setOf(2, 5))
        assertEquals("schedule", planned.nowSource)
        assertNull(planned.freeUntil)

        // Nothing of mine left after Next: Next comes from the event.
        val lastMine = content(day(hearted = setOf(2))!!, hearted = setOf(2))
        assertEquals(2, lastMine.current?.itemId)
        assertEquals(4, lastMine.next?.itemId)
        assertEquals("event", lastMine.nextSource)
        assertNull(lastMine.freeUntil)
    }

    @Test
    fun content_timedPlacesOfMySchedule() {
        val dinner = FollowPlace(20, "Food Court", d("2026-11-14T15:00:00-06:00"), d("2026-11-14T15:45:00-06:00"))
        // Only a place today: it is followed (My schedule), the event fills Now.
        val onlyPlace = day(places = listOf(dinner))!!
        assertTrue(onlyPlace.followsHearts)
        assertEquals(listOf(dinner), onlyPlace.places)
        val state = content(onlyPlace)
        assertEquals("event", state.nowSource)
        assertTrue(state.next!!.isPlace)
        assertNull(state.next?.itemId)
        assertEquals(20, state.next?.locationId)
        assertEquals(dinner.start, state.freeUntil)

        // With an item: by start.
        val both = content(day(hearted = setOf(4), places = listOf(dinner))!!, hearted = setOf(4))
        assertTrue(both.next!!.isPlace)
        assertEquals(4, both.after?.itemId)
        // During dinner Now is the place.
        val during = content(day(hearted = setOf(4), places = listOf(dinner))!!, hearted = setOf(4), at = d("2026-11-14T15:10:00-06:00"))
        assertEquals(20, during.current?.locationId)
        assertEquals("schedule", during.nowSource)
        assertEquals(4, during.next?.itemId)
        assertNull(day(places = listOf(dinner), at = d("2026-11-14T23:30:00-06:00"))?.places?.firstOrNull())
    }

    @Test
    fun content_walkOnlyFromARecentPosition() {
        // iOS's direction / distance case: Android keeps the walk part (the controller passes no position).
        assertEquals(3, content(day()!!, user = atFoodCourt, userAt = now.minus(Duration.ofMinutes(10))).walkMinutesToNext)
        assertNull(content(day()!!, user = atFoodCourt, userAt = now.minus(Duration.ofMinutes(31))).walkMinutesToNext)
    }

    @Test
    fun placesComeFromMySchedulesTimedPlaceEntries() {
        val bundle = GlanceFixture.bundle.copy(locations = locations, schedule = schedule)
        val mine = MySchedule(
            listOf(
                ScheduleEntry(ScheduleEntryKind.PLACE, 20, at = d("2026-11-14T15:00:00-06:00")),
                ScheduleEntry(ScheduleEntryKind.PLACE, 10),
                ScheduleEntry(ScheduleEntryKind.PLACE, 99, at = now),
                ScheduleEntry(ScheduleEntryKind.ITEM, 4),
            ),
        )
        assertEquals(
            listOf(FollowPlace(20, "Food Court", d("2026-11-14T15:00:00-06:00"), d("2026-11-14T15:45:00-06:00"))),
            LiveFollow.places(mine, bundle),
        )
    }

    // ── Urgent (API-011) ────────────────────────────────────────────────────

    @Test
    fun urgent_newestFromTheBundleElseThePushedOne() {
        fun note(id: Int, title: String, body: String, sent: String, urgent: Boolean) =
            EventNotification(id, title, body, OffsetDateTime.parse(sent), if (urgent) NotificationPriority.URGENT else NotificationPriority.NORMAL)
        val notifications = listOf(
            note(7, "Water refill", "", "2026-11-14T13:50:00-06:00", false),
            note(8, "Old warning", "", "2026-11-14T01:00:00-06:00", true),
            note(9, "Main Stage closed", "Storm on the way", "2026-11-14T13:40:00-06:00", true),
        )
        val urgent = LiveFollow.urgent(notifications, emptySet(), null, now)!!
        assertEquals(9, urgent.id)
        assertEquals("Main Stage closed", urgent.title)
        assertEquals("Storm on the way", urgent.body)
        assertEquals(d("2026-11-14T13:40:00-06:00"), urgent.sentAt)
        // Older than 12 h or dismissed: gone.
        assertNull(LiveFollow.urgent(notifications, setOf(9), null, now))

        // Only pushed (the push arrived before the bundle): kept while ≤ 12 h old.
        val pushed = UrgentBlock(11, "Gate B closed", "Use gate A", Instant.parse("2026-11-14T19:00:00Z"))
        assertEquals(pushed, LiveFollow.urgent(emptyList(), emptySet(), pushed, now))
        assertNull(LiveFollow.urgent(emptyList(), setOf(11), pushed, now))
        assertNull(LiveFollow.urgent(emptyList(), emptySet(), pushed, d("2026-11-15T08:00:00-06:00")))
    }

    // ── live_activity (API-027) ─────────────────────────────────────────────

    @Test
    fun inputs_liveActivitySettings() {
        val bundle = GlanceFixture.bundle.copy(locations = locations, schedule = schedule)
        val place = FollowPlace(20, "Food Court", now, now.plus(Duration.ofMinutes(45)))
        val mine = LiveFollow.inputs(null, bundle, setOf(2), listOf(place))
        assertEquals(setOf(2), mine.hearted)
        assertEquals(1, mine.places.size)
        val all = LiveFollow.inputs(LiveActivitySettings(follows = LiveActivitySettings.Follows.EVERYTHING), bundle, setOf(2), listOf(place))
        assertTrue(all.hearted.isEmpty() && all.places.isEmpty())
        assertEquals(schedule.size, all.schedule.size)
        val stage = LiveFollow.inputs(LiveActivitySettings(follows = LiveActivitySettings.Follows.LOCATION, locationId = 10), bundle, setOf(3), emptyList())
        assertEquals(listOf(2, 4, 5), stage.schedule.map { it.id })
        assertTrue(stage.hearted.isEmpty())
        // An unknown place falls back to My schedule.
        assertEquals(
            setOf(3),
            LiveFollow.inputs(LiveActivitySettings(follows = LiveActivitySettings.Follows.LOCATION, locationId = 99), bundle, setOf(3), emptyList()).hearted,
        )
    }

    @Test
    fun buttonsStaleTextAndTheEmptyHint() {
        val today = day()!!
        val plain = LiveFollow.decorate(content(today), today, null, hasMine = false, late = null)
        assertEquals(listOf("skip", "map"), plain.buttons)
        assertEquals(LiveFollow.EMPTY_HINT, plain.hint)
        val drawn = LiveFollow.render(plain, today, "Fest", chicago, now, Locale.US)
        assertEquals(LiveFollow.EMPTY_HINT, drawn.subText)
        assertEquals(4, drawn.skipItemId)
        assertEquals(10, drawn.mapLocationId)

        val onlyMap = LiveFollow.decorate(content(today), today, LiveActivitySettings(buttons = listOf("map"), staleText = "Open the app"), hasMine = true, late = null)
        assertNull(onlyMap.hint)
        assertEquals("Open the app", onlyMap.staleText)
        assertFalse(onlyMap.showsButton("skip"))
        val mapOnly = LiveFollow.render(onlyMap, today, "Fest", chicago, now, Locale.US)
        assertNull(mapOnly.skipItemId)
        assertEquals("Fest", mapOnly.subText)
        // `add` is accepted and not drawn.
        assertEquals(emptyList<String>(), LiveActivitySettings(buttons = listOf("add")).liveButtons)
        // No hint on the day's summary.
        assertNull(LiveFollow.decorate(content(today, at = d("2026-11-14T22:00:00-06:00")), today, null, false, null).hint)
    }

    // ── Reminders ───────────────────────────────────────────────────────────

    @Test
    fun reminders_beforeHeartedItemsWithWalk() {
        val reminders = LiveFollow.reminders(1, schedule, setOf(2, 4, 5, 6, 7), locations, atFoodCourt, now, chicago, Locale.US)
        // 2 has started (its reminder time passed), 7 has no time.
        assertEquals(listOf(4, 5, 6), reminders.map { it.itemId })
        assertEquals("attnd.reminder.1.4", reminders[0].id)
        assertEquals(d("2026-11-14T16:15:00-06:00"), reminders[0].fireAt)
        assertEquals("Midnight Choir in 15 min · 3 min walk", reminders[0].title)
        assertEquals("Main Stage · 4:30 PM", reminders[0].body)
        assertEquals("Main Stage · 8:30 PM", reminders[1].body)
        // Already at the Food Court: no walk.
        assertEquals("Sunday Brunch in 15 min", reminders[2].title)
        assertEquals("Food Court · 10:00 AM", reminders[2].body)

        assertEquals("Midnight Choir in 15 min", LiveFollow.reminders(1, schedule, setOf(4), locations, null, now, chicago, Locale.US).first().title)
        assertTrue(LiveFollow.reminders(1, schedule, emptySet(), locations, null, now, chicago).isEmpty())
    }

    @Test
    fun reminders_perEntryLeadAndTimedPlaces() {
        val dinner = ScheduleEntry(ScheduleEntryKind.PLACE, 20, at = d("2026-11-14T15:00:00-06:00"))
        val reminders = LiveFollow.reminders(
            1, schedule, setOf(4, 5), locations, mainStage.coordinate, now, chicago, Locale.US,
            leadMinutes = mapOf(4 to 30, 5 to 0), places = listOf(dinner),
        )
        assertEquals(listOf("attnd.reminder.1.p20", "attnd.reminder.1.4"), reminders.map { it.id })
        val place = reminders[0]
        assertEquals(d("2026-11-14T14:45:00-06:00"), place.fireAt)
        assertEquals("Food Court in 15 min · 3 min walk", place.title)
        assertEquals("In your schedule · 3:00 PM", place.body)
        assertEquals(20, place.locationId)
        assertEquals(-20, place.shownKey)
        assertEquals(d("2026-11-14T16:00:00-06:00"), reminders[1].fireAt)
        assertEquals("Midnight Choir in 30 min", reminders[1].title)
        // A place with its reminder off.
        assertTrue(LiveFollow.reminders(1, schedule, emptySet(), locations, null, now, chicago, places = listOf(dinner.copy(reminder = 0))).isEmpty())
    }

    // ── Android's notification (PlannerFixture: hearted 101 The Prairie Lights + 114 Neon Armadillo) ──

    private val bundle = PlannerFixture.bundle
    private fun at(localTime: String) = PlannerFixture.sat(localTime).toInstant()
    private fun live(hearts: Hearts, at: Instant, skipped: Set<Int> = emptySet()) =
        LiveFollow.followDay(bundle.schedule, hearts.scheduleItems, emptyList(), bundle.event.startTime?.toInstant(), at, chicago)!!.let { day ->
            val c = LiveFollow.content(day, bundle.locations, hearts.scheduleItems, skipped, null, urgent = null, now = at)
            LiveFollow.render(LiveFollow.decorate(c, day, null, !hearts.isEmpty, null), day, bundle.event.name, chicago, at, Locale.US)
        }

    @Test
    fun notificationThroughTheEvening() {
        val hearts = PlannerFixture.hearts
        val before = live(hearts, at("17:30"))
        assertEquals(LiveMode.ACTIVE, before.mode)
        assertEquals(LiveSource.HEARTS, before.source)
        assertEquals("At the event: Velvet Mesquite · Main Stage", before.title)
        assertEquals("Free until 7:00 · Next: The Prairie Lights at Main Stage", before.text)
        assertEquals("7:00", before.chip)
        assertEquals(at("19:00"), before.countdownTo)
        assertEquals(bundle.event.name, before.subText)

        val during = live(hearts, at("19:10"))
        assertEquals("Now: The Prairie Lights · Main Stage", during.title)
        assertEquals("Next 8:00 PM: Neon Armadillo · Trinity Stage", during.text)
        assertEquals("8:00", during.chip)
        assertEquals(listOf(LiveSegment(120, isItem = true)), during.segments)
        assertEquals(10, during.progress)
        assertEquals(listOf(60), during.points)
        assertEquals(101, during.focus?.itemId)
        assertEquals(114, during.skipItemId)

        // Neon Armadillo skipped: Next comes from the event.
        val skipped = live(hearts, at("19:10"), skipped = setOf(114))
        assertEquals("Next 9:00 PM: DJ Lone Star · Panther Island Pavilion", skipped.text)
        assertEquals(104, skipped.skipItemId)
    }

    @Test
    fun notificationGatesAndTheDaysSummary() {
        val gates = live(Hearts.NONE, at("09:00"))
        assertEquals(LiveMode.BEFORE, gates.mode)
        assertEquals("Gates open 10:00 · First: Gates open", gates.title)
        assertEquals("General · 10:00 AM", gates.text)
        assertEquals("10:00", gates.chip)
        assertEquals(LiveFollow.EMPTY_HINT, gates.subText)

        // Saturday ended at 11 PM; at 11:10 PM the followed day is Sunday and the summary shows.
        val inputs = LiveFollow.inputs(null, bundle, emptySet(), emptyList())
        val start = bundle.event.startTime?.toInstant()
        val late = at("23:10")
        val sunday = LiveFollow.followDay(inputs, start, late, chicago)
        assertEquals(PlannerFixture.sun("00:00").toInstant(), sunday?.dayStart)
        val ended = LiveFollow.endedToday(inputs, start, late, chicago, sunday)!!
        val wrap = LiveFollow.render(
            LiveFollow.content(ended, bundle.locations, emptySet(), emptySet(), null, urgent = null, now = late), ended, bundle.event.name, chicago, late, Locale.US,
        )
        assertEquals(LiveMode.END_OF_DAY, wrap.mode)
        assertEquals("That's a wrap for today", wrap.title)
        assertNull(LiveFollow.endedToday(inputs, start, at("23:31"), chicago, sunday))
    }

    @Test
    fun notificationUrgentAndRunningLate() {
        val urgent = UrgentBlock(31, "Main Stage closed", "Storm on the way, head indoors.", at("18:00"))
        val state = LiveFollow.render(LiveContent(urgent = urgent), null, "Fest", chicago, at("18:30"), Locale.US)
        assertEquals(LiveMode.URGENT, state.mode)
        assertEquals("Main Stage closed", state.title)
        assertEquals("Urgent", state.chip)
        assertEquals(urgent, state.urgent)
        assertNull(state.skipItemId)
        assertTrue(urgent.isActive(Instant.parse("2026-11-15T11:59:00Z"), null))
        assertFalse(urgent.isActive(Instant.parse("2026-11-15T12:00:00Z"), null))

        // Following My schedule, late for Next: "Leave now".
        val planDay = LiveFollow.followDay(bundle.schedule, setOf(101, 114), emptyList(), null, at("18:40"), chicago)!!
        val c = LiveFollow.content(planDay, bundle.locations, setOf(101, 114), emptySet(), null, urgent = null, now = at("18:40"))
        val leave = RunningLate(101, LateState.LEAVE_NOW, 8, at("18:47"), "Leave now for The Prairie Lights · 8 min walk", emptyList())
        val decorated = LiveFollow.decorate(c, planDay, null, hasMine = true, late = leave)
        assertEquals(LateState.LEAVE_NOW, decorated.lateState)
        val drawn = LiveFollow.render(decorated, planDay, "Fest", chicago, at("18:40"), Locale.US)
        assertEquals("Leave now for The Prairie Lights · 8 min walk", drawn.title)
        assertEquals("Leave", drawn.chip)
        // Not for another item than Next.
        assertNull(LiveFollow.decorate(c, planDay, null, true, leave.copy(itemId = 114)).lateState)
    }

    @Test
    fun nextWakeFollowsTheDay() {
        val hearts = PlannerFixture.hearts.scheduleItems
        fun dayAt(t: Instant) = LiveFollow.followDay(bundle.schedule, hearts, emptyList(), bundle.event.startTime?.toInstant(), t, chicago)
        val now = PlannerFixture.now // Sat 10:00 AM
        // The event's own items change Now (Gates open ends 10:30).
        assertEquals(at("10:30"), LiveFollow.nextWake(dayAt(now), now, showing = false))
        assertEquals(at("17:45"), LiveFollow.nextWake(dayAt(at("17:30")), at("17:30"), showing = true)) // every 15 min
        assertEquals(at("10:05"), LiveFollow.nextWake(dayAt(now), now, showing = false, extra = listOf(at("10:05"))))
        assertNotNull(LiveFollow.nextWake(dayAt(at("22:00")), at("22:00"), showing = false))
        assertNull(LiveFollow.nextWake(null, now, showing = false))
    }
}
