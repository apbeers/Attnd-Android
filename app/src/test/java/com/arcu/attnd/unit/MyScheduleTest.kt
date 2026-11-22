package com.arcu.attnd.unit

import com.arcu.attnd.data.ScheduleController
import com.arcu.attnd.data.ScheduleStore
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.MealKind
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.PlanConstraints
import com.arcu.attnd.domain.PlanPace
import com.arcu.attnd.domain.PlanPreferences
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScheduleEntry
import com.arcu.attnd.domain.ScheduleEntrySource
import com.arcu.attnd.domain.ScheduleHistory
import com.arcu.attnd.domain.SchedulePreferences
import com.arcu.attnd.domain.ScheduleProposal
import com.arcu.attnd.domain.propose
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
 * and an older build's plan (one list §6: entries, meals, preferences, check-offs, idempotent, keys
 * deleted), add / remove / time / reminder, API-009 metrics only on real changes, the Schedule section's
 * order, Home's up next, clashes, source / reason, the planner's proposals (§2), the one Undo history
 * (§3), the derived day view (§4), organizer changes.
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
        override fun history(eventId: Int) = texts["u$eventId"]
        override fun setHistory(eventId: Int, value: String?) { if (value == null) texts.remove("u$eventId") else texts["u$eventId"] = value }
        override fun preferences(eventId: Int) = texts["r$eventId"]
        override fun setPreferences(eventId: Int, value: String) { texts["r$eventId"] = value }
        override fun checkedOff(eventId: Int) = texts["c$eventId"]
        override fun setCheckedOff(eventId: Int, value: String) { texts["c$eventId"] = value }
        override fun legacyPlan(eventId: Int) = texts["p$eventId"]
        override fun legacyPlanHistory(eventId: Int) = texts["ph$eventId"]
        override fun deleteLegacyPlan(eventId: Int) {
            texts.remove("p$eventId")
            texts.remove("ph$eventId")
        }
    }

    @Test
    fun migrationFromLikesAndPlan() {
        val plan = AssistantFixture.plan
        val migrated = MySchedule.migrate(setOf(101, 114), setOf(6), plan)
        // Hearted items, then the plan's other items (`source: planner`, the plan's reason); hearted places
        // saved; the plan's lunch at its food place becomes a place entry at the break's time.
        assertEquals(setOf(101, 114) + plan.itemIds, migrated.itemIds)
        assertTrue(6 in migrated.placeIds)
        assertEquals(ScheduleEntrySource.YOU, migrated.entry(ScheduleEntryKind.ITEM, 101)?.source)
        val planned = plan.items.first { it.itemId !in setOf(101, 114) }
        assertEquals(ScheduleEntrySource.PLANNER, migrated.entry(ScheduleEntryKind.ITEM, planned.itemId)?.source)
        assertEquals(planned.reason, migrated.entry(ScheduleEntryKind.ITEM, planned.itemId)?.reason)
        val meal = plan.days.flatMap { it.entries.filterIsInstance<MealBreak>() }.first { it.locationId != null }
        assertEquals(meal.start, migrated.entry(ScheduleEntryKind.PLACE, meal.locationId!!)?.at)
        // Once per event: the store migrates the hearts only while it has no schedule; they stay readable.
        val storage = MemoryStorage(mutableMapOf("h42" to "s101"))
        assertEquals(setOf(101), ScheduleStore(storage).hearts(42).scheduleItems)
        assertNotNull(storage.mySchedule(42))
        assertEquals("s101", storage.hearts(42))
    }

    @Test
    fun migrationAbsorbsAnOlderBuildsPlanOnceAndDeletesIt() {
        // One list §6: an older build's stored plan (and its plan history) folds into My schedule.
        val plan = AssistantFixture.plan.copy(
            constraints = PlanConstraints(maxWalkMinutes = 10, pace = PlanPace.RELAXED),
            checkedOff = setOf(101),
        )
        val storage = MemoryStorage(mutableMapOf("s42" to MySchedule(listOf(ScheduleEntry(ScheduleEntryKind.ITEM, 101))).encode(), "p42" to PlanCodec.encode(plan), "ph42" to "[]"))
        val store = ScheduleStore(storage)
        val schedule = store.schedule(42)
        assertEquals(setOf(101) + plan.itemIds, schedule.itemIds)
        // The person's own entry keeps its source; the planner's are marked.
        assertEquals(ScheduleEntrySource.YOU, schedule.entry(ScheduleEntryKind.ITEM, 101)?.source)
        assertTrue(schedule.entries.filter { it.id != 101 && it.kind == ScheduleEntryKind.ITEM }.all { it.source == ScheduleEntrySource.PLANNER })
        val meal = plan.days.flatMap { it.entries.filterIsInstance<MealBreak>() }.first { it.locationId != null }
        val mealEntry = schedule.entry(ScheduleEntryKind.PLACE, meal.locationId!!)!!
        assertEquals(meal.start, mealEntry.at)
        assertEquals(meal.kind.label, mealEntry.reason)
        // Preferences from the plan's preferences + constraints, check-offs kept, old keys deleted.
        val preferences = store.preferences(42)
        assertEquals(PlanPace.RELAXED, preferences.pace)
        assertEquals(10, preferences.maxWalkMinutes)
        assertEquals(setOf("live music", "bbq"), preferences.likedTags)
        assertEquals(MealKind.entries.toSet(), preferences.meals)
        assertEquals(setOf(101), store.checkedOff(42))
        assertNull(storage.legacyPlan(42))
        assertNull(storage.legacyPlanHistory(42))
        // Idempotent: a second run (a new process) finds nothing and changes nothing.
        val saved = storage.texts.toMap()
        assertEquals(schedule, ScheduleStore(storage).schedule(42))
        assertEquals(saved, storage.texts)
        // Running the absorb again on the result changes nothing either.
        assertFalse(schedule.copy().absorb(plan))
        // No stored plan, only an older plan history: its newest plan is absorbed.
        val history = kotlinx.serialization.json.JsonArray(listOf(PlanCodec.encode(plan.copy(days = emptyList())), PlanCodec.encode(plan)).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        val fromHistory = MemoryStorage(mutableMapOf("ph42" to history))
        assertEquals(plan.itemIds.toSet(), ScheduleStore(fromHistory).schedule(42).itemIds)
        assertNull(fromHistory.legacyPlanHistory(42))
        // Another event's plan is ignored (and deleted).
        val other = MemoryStorage(mutableMapOf("p7" to PlanCodec.encode(plan)))
        assertTrue(ScheduleStore(other).schedule(7).isEmpty)
        assertNull(other.legacyPlan(7))
    }

    @Test
    fun sourceAndReasonCodec() {
        val s = MySchedule()
        s.add(item(101))
        s.add(item(105))
        s.mark(item(105), ScheduleEntrySource.PLANNER, "Because you asked for bbq")
        s.add(place(6), at = sat("12:15").toInstant())
        s.mark(place(6), ScheduleEntrySource.ASSISTANT, "x".repeat(120))
        assertEquals(s, MySchedule.decode(s.encode()))
        // "you" is the default and isn't written.
        assertFalse(s.encode().contains("\"source\":\"you\""))
        assertEquals(ScheduleEntry.MAX_REASON, s.entry(ScheduleEntryKind.PLACE, 6)?.reason?.length)
        // Lenient: an unknown source is "you", a blank reason none.
        val lenient = MySchedule.decode("""{"entries":[{"kind":"item","id":5,"source":"robot","reason":"  "},{"kind":"item","id":6,"source":"Shared","reason":" In a shared plan "}]}""")
        assertEquals(ScheduleEntrySource.YOU, lenient.entries[0].source)
        assertNull(lenient.entries[0].reason)
        assertEquals(ScheduleEntrySource.SHARED, lenient.entries[1].source)
        assertEquals("In a shared plan", lenient.entries[1].reason)
    }

    @Test
    fun preferencesCodec() {
        val p = SchedulePreferences(
            pace = PlanPace.PACKED, kidFriendly = true, maxWalkMinutes = 10, likedTags = setOf("bbq", "live music"),
            dislikedTags = setOf("dj"), meals = setOf(MealKind.LUNCH), earliest = LocalTime.of(10, 0), latest = LocalTime.of(22, 30),
        )
        assertEquals(p, SchedulePreferences.decode(p.encode()))
        assertEquals("""{"pace":"balanced"}""", SchedulePreferences().encode())
        assertNull(SchedulePreferences.decode("nope"))
        val lenient = SchedulePreferences.decode("""{"pace":"zoom","kid_friendly":"true","max_walk_minutes":0,"liked_tags":["BBQ",3],"meals":["lunch","brunch"],"earliest":"9am"}""")!!
        assertEquals(PlanPace.BALANCED, lenient.pace)
        assertTrue(lenient.kidFriendly)
        assertNull(lenient.maxWalkMinutes)
        assertEquals(setOf("bbq"), lenient.likedTags)
        assertEquals(setOf(MealKind.LUNCH), lenient.meals)
        assertNull(lenient.earliest)
        // Round trip through the planner's types.
        assertEquals(p.copy(meals = MealKind.entries.toSet()), SchedulePreferences.of(p.planPreferences, p.constraints))
    }

    @Test
    fun addRemoveMetricsOnlyOnRealChanges() {
        val tracked = mutableListOf<Pair<String, String>>()
        val store = ScheduleStore(MemoryStorage())
        val controller = ScheduleController(store, { bundle }, { _, name, detail -> tracked += name to detail })
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
    fun proposeKeepsThePlannersNumbersAndProposesEdits() {
        // One list §2: My schedule's items are the must-includes; the planner's result is unchanged.
        val s = MySchedule()
        s.add(item(101))
        s.add(item(114))
        s.add(item(201)) // Sunday: not on the plan's day, stays
        val expected = Planner(bundle, AssistantFixture.musicAndBbq, Hearts(scheduleItems = s.itemIds)).plan(now, "Plan my Saturday")
        val (plan, proposal) = Planner.propose(s, SchedulePreferences(), bundle, now, request = AssistantFixture.musicAndBbq, text = "Plan my Saturday")
        assertEquals(expected, plan)
        assertTrue(listOf(101, 114).all { id -> id in plan.itemIds || plan.items.any { id in it.orInstead } })
        // Adds: the plan's other items with the planner's reasons, and its meal breaks' food places.
        val itemAdds = proposal.adds.filter { it.meal == null }
        assertEquals(plan.itemIds.filter { it !in s.itemIds }.toSet(), itemAdds.map { it.target.id }.toSet())
        itemAdds.forEach { add -> assertEquals(plan.items.first { it.itemId == add.target.id }.reason, add.reason) }
        val meals = plan.days.flatMap { it.entries.filterIsInstance<MealBreak>() }.filter { it.locationId != null }
        assertEquals(meals.map { it.locationId }, proposal.adds.filter { it.meal != null }.map { it.target.id })
        assertTrue(proposal.removes.isEmpty())
        assertEquals("Add ${proposal.adds.size} things to your schedule", proposal.title)
        assertTrue(proposal.pillText.startsWith("+${itemAdds.size} items"))
        // Previewing changes nothing; Apply adds everything with `source: planner`.
        assertEquals(setOf(101, 114, 201), s.itemIds)
        val changes = s.apply(proposal)
        assertEquals(plan.itemIds.toSet() + setOf(101, 114, 201), s.itemIds)
        assertEquals(itemAdds.size, changes.count { it.metric == UsageMetric.SCHEDULE_ITEM_HEART })
        assertEquals(ScheduleEntrySource.YOU, s.entry(ScheduleEntryKind.ITEM, 101)?.source)
        assertTrue(itemAdds.all { s.entry(ScheduleEntryKind.ITEM, it.target.id)?.source == ScheduleEntrySource.PLANNER })
        // The same plan again: nothing to do.
        assertTrue(s.proposal(plan, bundle).isEmpty)
        assertEquals("No change", s.proposal(plan, bundle).pillText)
    }

    @Test
    fun proposalsRemoveOnlyThePlannersEntriesUnlessAsked() {
        val s = MySchedule()
        s.add(item(101)) // the person's
        s.add(item(105))
        s.mark(item(105), ScheduleEntrySource.PLANNER, "Fits your day")
        s.add(item(201)) // Sunday, the planner's, another day
        s.mark(item(201), ScheduleEntrySource.PLANNER, null)
        val plan = Planner(bundle, PlanPreferences(likedTags = setOf("bbq")), Hearts.NONE, PlanConstraints(excluded = setOf(101, 105))).plan(now, "")
        assertFalse(101 in plan.itemIds || 105 in plan.itemIds)
        val proposal = s.proposal(plan, bundle)
        // 105 (planner, on the plan's day) goes; 101 (the person's) and 201 (another day) stay.
        assertEquals(listOf(item(105)), proposal.removes.map { it.target })
        // Asked for ("drop Juniper Lane"): the person's entry goes too.
        assertEquals(setOf(item(101), item(105)), s.proposal(plan, bundle, explicit = setOf(101)).removes.map { it.target }.toSet())
        assertTrue(s.proposal(null, bundle).isEmpty)
        // A planner's timed food place on the plan's day without its meal break goes; a place of the
        // person's never.
        s.add(place(6), at = sat("12:15").toInstant())
        s.mark(place(6), ScheduleEntrySource.PLANNER, "Lunch break")
        s.add(place(5), at = sat("18:30").toInstant())
        val noMeals = Planner(bundle, PlanPreferences(likedTags = setOf("bbq")), Hearts.NONE).plan(now, "")
        val removes = s.proposal(noMeals, bundle).removes
        assertTrue(ScheduleProposal.Remove(place(6), MealKind.LUNCH) in removes)
        assertFalse(removes.any { it.target == place(5) })
        assertTrue(s.proposal(noMeals, bundle).pillText.contains("\u22121 lunch break"))
    }

    @Test
    fun undoHistoryKeepsTwentySnapshotsHeartsIncluded() {
        val tracked = mutableListOf<String>()
        val storage = MemoryStorage()
        val controller = ScheduleController(ScheduleStore(storage), { bundle }, { _, name, _ -> tracked += name })
        // Hearts are steps of the one history.
        controller.setLiked(42, SearchKind.SCHEDULE, 101, true, countUsage = true)
        controller.setLiked(42, SearchKind.LOCATION, 6, true, countUsage = true)
        assertEquals(2, controller.history(42).snapshots.size)
        // A plan applied: one step.
        val plan = Planner(bundle, AssistantFixture.musicAndBbq, controller.hearts(42)).plan(now, "")
        val applied = controller.applyPlan(42, plan)
        assertFalse(applied.isEmpty)
        assertEquals(3, controller.history(42).snapshots.size)
        assertEquals(SchedulePreferences.of(plan.preferences), controller.preferences(42))
        // A reminder isn't a step.
        controller.setReminder(42, item(101), 30)
        assertEquals(3, controller.history(42).snapshots.size)
        // Undo: the plan's adds leave again (counted), then the place, then the item.
        tracked.clear()
        assertTrue(controller.undo(42))
        assertEquals(setOf(101), controller.hearts(42).scheduleItems)
        assertTrue(tracked.all { it == UsageMetric.SCHEDULE_ITEM_UNHEART || it == UsageMetric.LOCATION_UNHEART })
        assertTrue(controller.undo(42))
        assertTrue(controller.undo(42))
        assertTrue(controller.schedule(42).isEmpty)
        assertFalse(controller.undo(42))
        // Kept across processes; at most 20.
        repeat(25) { controller.setLiked(42, SearchKind.SCHEDULE, 300 + it, true, countUsage = false) }
        assertEquals(ScheduleHistory.MAX, ScheduleStore(storage).history(42).snapshots.size)
        assertEquals(ScheduleHistory.MAX, ScheduleHistory.decode(controller.history(42).encode()).snapshots.size)
        assertFalse(ScheduleHistory.decode("nonsense").canUndo)
        // Check-offs are a set beside the history.
        assertTrue(controller.setCheckedOff(42, 101, true))
        assertFalse(controller.setCheckedOff(42, 101, true))
        assertEquals(setOf(101), ScheduleStore(storage).checkedOff(42))
    }

    @Test
    fun dayPlanIsDerivedFromMySchedule() {
        val s = MySchedule()
        assertNull(s.dayPlan(bundle))
        s.add(item(101))
        s.add(item(105))
        s.mark(item(105), ScheduleEntrySource.PLANNER, "Because you asked for bbq")
        s.add(item(201))
        s.add(place(6), at = sat("12:15").toInstant()) // Taco Row at lunch: a meal break
        s.add(place(5)) // saved without a time: not in the day
        val plan = s.dayPlan(bundle, checkedOff = setOf(101, 999))!!
        assertEquals(setOf(101, 105, 201), plan.itemIds.toSet())
        assertEquals(2, plan.days.size)
        assertEquals("Because you asked for bbq", plan.items.first { it.itemId == 105 }.reason)
        assertEquals(MySchedule.IN_SCHEDULE_REASON, plan.items.first { it.itemId == 101 }.reason)
        val meals = plan.days.first().entries.filterIsInstance<MealBreak>()
        assertEquals(listOf(6), meals.map { it.locationId })
        assertEquals(setOf(101), plan.checkedOff)
        // Walks between the day's items, as the planner lays them out.
        assertNotNull(plan.days.first().items.last().walkMinutes)
        // The day's lunch is covered, so an edit doesn't plan another one; nothing invented.
        assertTrue(plan.constraints.mealsDone.isNotEmpty())
        assertEquals(s.proposal(plan, bundle), ScheduleProposal(summary = Planner.summary(plan, zone)))
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
