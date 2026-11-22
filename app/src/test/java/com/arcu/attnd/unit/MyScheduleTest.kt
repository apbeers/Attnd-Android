package com.arcu.attnd.unit

import com.arcu.attnd.data.ScheduleController
import com.arcu.attnd.data.ScheduleStore
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScheduleEntryKind
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.UsageMetric
import com.arcu.attnd.unit.PlannerFixture.sat
import com.arcu.attnd.unit.PlannerFixture.sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * My schedule (iOS `MyScheduleTests`, the same cases and expectations): entries, migration from likes
 * and plans, add / remove / time / reminder, API-009 metrics only on real changes, the Schedule
 * section's order, Home's up next, clashes, the planner filling My schedule, organizer changes.
 */
class MyScheduleTest {
    private val bundle = PlannerFixture.bundle
    private val now = PlannerFixture.now // Sat 10:00 AM
    private val zone = AssistantFixture.zone

    private fun item(id: Int) = ScheduleTarget.item(id)
    private fun place(id: Int) = ScheduleTarget.place(id)

    /** Texts in memory instead of the device's preferences. */
    private class MemoryStorage(val texts: MutableMap<String, String> = mutableMapOf()) : ScheduleStore.Storage {
        override fun mySchedule(eventId: Int) = texts["s$eventId"]
        override fun setMySchedule(eventId: Int, value: String) { texts["s$eventId"] = value }
        override fun hearts(eventId: Int) = texts["h$eventId"]
        override fun setHearts(eventId: Int, value: String) { texts["h$eventId"] = value }
        override fun plan(eventId: Int) = texts["p$eventId"]
    }

    @Test
    fun migrationFromLikesAndPlan() {
        val plan = AssistantFixture.plan
        val migrated = MySchedule.migrate(setOf(101, 114), setOf(6), plan)
        // Hearted items, then the plan's other items; hearted places saved; the plan's lunch at its food
        // place becomes a place entry at the break's time.
        assertEquals(setOf(101, 114) + plan.itemIds, migrated.itemIds)
        assertTrue(6 in migrated.placeIds)
        val meal = plan.days.flatMap { it.entries.filterIsInstance<MealBreak>() }.first { it.locationId != null }
        assertEquals(meal.start, migrated.entry(ScheduleEntryKind.PLACE, meal.locationId!!)?.at)
        // Once per event: the store migrates only while it has no schedule; the hearts stay readable.
        val storage = MemoryStorage(mutableMapOf("h42" to "s101"))
        assertEquals(setOf(101), ScheduleStore(storage).hearts(42).scheduleItems)
        assertNotNull(storage.mySchedule(42))
        storage.texts["p42"] = PlanCodec.encode(plan)
        assertEquals("not migrated twice", setOf(101), ScheduleStore(storage).hearts(42).scheduleItems)
        assertEquals("s101", storage.hearts(42))
        // An older build's plan is migrated with the hearts.
        val withPlan = MemoryStorage(mutableMapOf("h42" to "s101", "p42" to PlanCodec.encode(plan)))
        assertEquals(setOf(101) + plan.itemIds, ScheduleStore(withPlan).schedule(42).itemIds)
    }

    @Test
    fun addRemoveMetricsOnlyOnRealChanges() {
        val tracked = mutableListOf<Pair<String, String>>()
        val store = ScheduleStore(MemoryStorage())
        val controller = ScheduleController(store, { bundle }, { null }, { _, _ -> }, { _, name, detail -> tracked += name to detail })
        assertTrue(controller.setLiked(42, SearchKind.SCHEDULE, 101, true, countUsage = true))
        assertFalse(controller.setLiked(42, SearchKind.SCHEDULE, 101, true, countUsage = true))
        assertTrue(controller.setLiked(42, SearchKind.LOCATION, 6, true, countUsage = true))
        assertEquals(setOf(6), controller.hearts(42).locations)
        // A time for a place already there: a change, no metric.
        val change = controller.add(42, place(6), at = sat("19:00").toInstant()).single()
        assertNull(change.metric)
        assertNull(controller.setReminder(42, item(101), 30).single().metric)
        assertTrue(controller.setLiked(42, SearchKind.SCHEDULE, 101, false, countUsage = true))
        assertFalse(controller.setLiked(42, SearchKind.SCHEDULE, 101, false, countUsage = true))
        assertEquals(emptySet<Int>(), controller.hearts(42).scheduleItems)
        assertFalse(controller.setLiked(42, SearchKind.NOTIFICATION, 31, true, countUsage = true))
        assertEquals(
            listOf(
                UsageMetric.SCHEDULE_ITEM_HEART to "101",
                UsageMetric.LOCATION_HEART to "6",
                UsageMetric.SCHEDULE_ITEM_UNHEART to "101",
            ),
            tracked,
        )
        // Screenshot mode: stored, never counted.
        assertTrue(controller.setLiked(42, SearchKind.SCHEDULE, 102, true, countUsage = false))
        assertEquals(3, tracked.size)
        // The add filled the fingerprint from the bundle (organizer changes).
        assertNotNull(controller.schedule(42).entry(item(102))?.fingerprint)
    }

    @Test
    fun timesRemindersAndRows() {
        val s = MySchedule()
        s.add(item(101))
        s.add(item(109))
        s.add(place(6), at = sat("18:00").toInstant())
        s.add(place(5))
        assertEquals(listOf(5), s.savedPlaceIds)
        assertEquals(listOf("Juniper Lane", "Taco Row", "The Prairie Lights"), s.rows(bundle, now).map { it.name })
        // A place entry lasts 45 min by default.
        assertEquals(sat("18:45").toInstant(), s.rows(bundle, now)[1].end)
        // Move, reminder (nearest choice), off.
        s.move(place(6), sat("20:00").toInstant())
        assertEquals(sat("20:00").toInstant(), s.entry(ScheduleEntryKind.PLACE, 6)?.at)
        assertNull(s.move(item(101), sat("20:00").toInstant()))
        s.setReminder(item(101), 25)
        assertEquals(30, s.entry(ScheduleEntryKind.ITEM, 101)?.reminderMinutes)
        s.setReminder(item(101), 0)
        assertEquals(0, s.entry(ScheduleEntryKind.ITEM, 101)?.reminderMinutes)
        assertEquals(15, s.entry(ScheduleEntryKind.ITEM, 109)?.reminderMinutes)
        // The Schedule tab's section: upcoming first, ended at the end.
        val later = sat("12:30").toInstant()
        assertEquals(listOf("The Prairie Lights", "Taco Row", "Juniper Lane"), s.sectionRows(bundle, later).map { it.name })
        assertEquals(true, s.sectionRows(bundle, later).last().ended)
        // Home: up next.
        assertEquals(listOf("The Prairie Lights", "Taco Row"), s.upNext(bundle, later, limit = 5).map { it.name })
        // Codec round trip, lenient.
        assertEquals(s, MySchedule.decode(s.encode()))
        val lenient = MySchedule.decode("""{"entries":[{"kind":"item","id":5,"reminder":7},{"kind":"x"},{"kind":"place","id":6,"extra":1}],"more":2}""")
        assertEquals(listOf(5, 6), lenient.entries.map { it.id })
        assertNull(lenient.entries[0].reminder)
        assertTrue(MySchedule.decode("not json").isEmpty)
        assertTrue(MySchedule.decode(null).isEmpty)
    }

    @Test
    fun clashesAndSuggestedTimes() {
        val s = MySchedule()
        s.add(item(113)) // Sundown Choir 18:15–18:45
        val beer = s.span(item(112), bundle)!! // 18:00–19:00
        assertEquals(listOf(113), s.clashes(beer.first, beer.second, bundle, now).map { it.entry.id })
        assertTrue(s.clashes(sat("19:00").toInstant(), sat("20:00").toInstant(), bundle, now).isEmpty())
        // "When?" from now + walk + 5 min, on the quarter hour, every 30 min; the first free one suggested.
        val options = s.suggestedTimes(6, bundle, sat("17:52").toInstant(), walkMinutes = 3)
        assertEquals(sat("18:00").toInstant(), options.times.first())
        assertEquals(sat("19:00").toInstant(), options.suggested)
        // Clock times: today, or tomorrow when more than 30 min past; near an entry's day when moving.
        assertEquals(sat("20:00").toInstant(), MySchedule.date(LocalTime.of(20, 0), null, now, zone))
        assertEquals(sun("08:00").toInstant(), MySchedule.date(LocalTime.of(8, 0), null, now, zone))
        assertEquals(sun("13:00").toInstant(), MySchedule.date(LocalTime.of(13, 0), sun("12:00").toInstant(), now, zone))
    }

    @Test
    fun plannerFillsMyScheduleWithPinnedEntries() {
        val s = MySchedule()
        s.add(item(101))
        s.add(item(201)) // Sunday: not on the plan's day, stays
        val planner = Planner(bundle, AssistantFixture.musicAndBbq, Hearts(scheduleItems = s.itemIds))
        val plan = planner.plan(now, "Plan my Saturday around live music and BBQ")
        // My schedule's items are the planner's must-includes.
        assertTrue(101 in plan.itemIds)
        val changes = s.apply(plan, null)
        assertEquals(plan.itemIds.toSet() + 201, s.itemIds)
        // Counted once per item that was new.
        assertEquals(plan.itemIds.count { it != 101 }, changes.count { it.metric == UsageMetric.SCHEDULE_ITEM_HEART })
        // A changed plan drops what it removed (on its days); clearing the plan keeps My schedule.
        val edited = plan.copy(days = plan.days.mapIndexed { i, day ->
            if (i == 0) day.copy(entries = day.entries.filterNot { it is PlannedItem && it.itemId == 109 }) else day
        })
        s.apply(edited, plan)
        assertFalse(109 in s.itemIds)
        assertTrue(s.apply(null, edited).isEmpty())
        assertTrue(101 in s.itemIds)
        // Reconcile: an item removed elsewhere leaves the plan; one added on its day joins it.
        s.remove(item(105))
        s.add(item(110))
        val reconciled: Plan = s.reconcile(edited, bundle)!!
        assertFalse(105 in reconciled.itemIds)
        assertEquals(MySchedule.IN_SCHEDULE_REASON, reconciled.items.first { it.itemId == 110 }.reason)
    }

    @Test
    fun remindersPerEntry() {
        // iOS `ReminderPlanner.reminders(leadMinutes:places:)`: fire times per entry. The reminder texts
        // and posting them stay with the Live Update (phase 3b / 8).
        val s = MySchedule()
        s.add(item(105)) // 17:00
        s.setReminder(item(105), 30)
        s.add(place(6), at = sat("19:00").toInstant())
        val reminders = s.reminders(bundle, now)
        assertEquals(listOf(sat("16:30").toInstant(), sat("18:45").toInstant()), reminders.map { it.fireAt })
        assertEquals(item(105), reminders[0].target)
        assertEquals(place(6), reminders[1].target)
        // Off: none.
        s.setReminder(item(105), 0)
        s.setReminder(place(6), 0)
        assertTrue(s.reminders(bundle, now).isEmpty())
    }

    @Test
    fun organizerChanges() {
        val s = MySchedule()
        s.add(item(105))
        assertTrue(s.fillFingerprints(bundle))
        assertTrue(s.organizerChanges(bundle).isEmpty())
        val moved = bundle.copy(schedule = bundle.schedule.map {
            if (it.id == 105) it.copy(startTime = sat("20:30"), endTime = sat("21:30")) else it
        })
        assertEquals(OrganizerChange.Kind.Moved(sat("20:30").toInstant()), s.organizerChanges(moved).first().kind)
        val gone = bundle.copy(schedule = bundle.schedule.filter { it.id != 105 })
        assertEquals(OrganizerChange.Kind.Cancelled, s.organizerChanges(gone).first().kind)
        assertEquals("Velvet Mesquite", s.organizerChanges(gone).first().name)
        s.keepChange(105, moved)
        assertTrue(s.organizerChanges(moved).isEmpty())
    }

    @Test
    fun mapPlaces_includeTheItemsPlaces() {
        val schedule = listOf(
            ScheduleItem(1, "Set", "", null, null, 10, null, emptyList()),
            ScheduleItem(2, "Talk", "", null, null, 20, null, emptyList()),
            ScheduleItem(3, "General", "", null, null, null, null, emptyList()),
        )
        assertEquals(setOf(10, 30), MySchedule.placeIds(setOf(30), setOf(1, 3), schedule))
        assertEquals(emptySet<Int>(), MySchedule.placeIds(emptySet(), emptySet(), schedule))
    }

    @Test
    fun mealsCoveredByATimedFoodPlace() {
        // Android addition: the planner's mealsDone from My schedule (iOS `makePlanner`).
        val s = MySchedule()
        s.add(place(6), at = sat("12:15").toInstant()) // Taco Row (food) at lunch
        s.add(place(1), at = sat("18:30").toInstant()) // Main Stage: not food
        assertEquals(1, s.mealsCovered(bundle).size)
        val plan = Planner(bundle, AssistantFixture.musicAndBbq, PlannerFixture.hearts,
            com.arcu.attnd.domain.PlanConstraints(mealsDone = s.mealsCovered(bundle))).plan(now, "")
        assertTrue(plan.days.flatMap { it.entries.filterIsInstance<MealBreak>() }.isEmpty())
    }
}
