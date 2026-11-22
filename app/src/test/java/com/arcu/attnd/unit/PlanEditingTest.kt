package com.arcu.attnd.unit

import com.arcu.attnd.domain.MealSlot
import com.arcu.attnd.domain.MealKind
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.PlanConstraints
import com.arcu.attnd.domain.PlanDiff
import com.arcu.attnd.domain.PlanEditor
import com.arcu.attnd.domain.PlanOp
import com.arcu.attnd.domain.PlanOpJson
import com.arcu.attnd.domain.PlanOrigin
import com.arcu.attnd.domain.PlanPace
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.PlanVersions
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.SchedulePreferences
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.keepingScheduleMeals
import com.arcu.attnd.unit.AssistantFixture.clock
import com.arcu.attnd.unit.AssistantFixture.musicAndBbq
import com.arcu.attnd.unit.AssistantFixture.plan
import com.arcu.attnd.unit.AssistantFixture.zone
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Assistant spec §4: plan edits on the fixture plan (planner rules 13–20), with the exact plans iOS
 * must produce. The fixture plan is
 * 10:00 111 · 11:00 109 · 12:00 lunch · 13:00 102 · 15:00 103 · 17:00 105 · 18:15 113 · 19:00 101 · 21:00 104.
 */
class PlanEditingTest {

    private fun edit(vararg ops: PlanOp, at: Instant = now, base: Plan = plan, b: com.arcu.attnd.domain.EventBundle = bundle) =
        PlanEditor.preview(base, ops.toList(), b, hearts, at)

    @Test
    fun reSolveWithoutConstraintsIsTheSamePlan() {
        assertEquals(plan, Planner(bundle, musicAndBbq, hearts).replan(plan, now))
        assertEquals(plan, Planner(bundle, musicAndBbq, hearts, PlanConstraints.NONE).plan(now, plan.request))
    }

    @Test
    fun removeAddReplaceKeep() {
        val remove = edit(PlanOp.Remove(109))
        assertEquals(listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104"), clock(remove.after))
        assertEquals(PlanDiff(removed = listOf(109), added = listOf(106), mealsRemoved = listOf(MealSlot(LocalDate.of(2026, 11, 14), MealKind.LUNCH))), remove.diff)
        assertTrue(109 in remove.after.constraints.excluded)

        // A pinned item is a must-include ahead of the rest (rule 16): Kolache in, Velvet Mesquite out.
        val add = edit(PlanOp.Add(110))
        assertEquals(listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "16:15 110", "18:15 113", "19:00 101", "21:00 104"), clock(add.after))
        assertEquals(PlanDiff(removed = listOf(105), added = listOf(110)), add.diff)
        assertEquals("You added this", add.after.items.single { it.itemId == 110 }.reason)

        val with = edit(PlanOp.Replace(103, with = 107))
        assertEquals(listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "14:15 107", "17:00 105", "18:15 113", "19:00 101", "21:00 104"), clock(with.after))

        // "something like Neon Armadillo" in the 3 PM slot: the most similar item that fits between 102 and 105.
        val like = edit(PlanOp.Replace(103, like = 114))
        assertEquals(clock(with.after), clock(like.after))
        assertEquals(emptyList<String>(), like.notes)

        val tag = edit(PlanOp.Replace(105, tag = "food"))
        assertEquals(listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "16:15 110", "18:15 113", "19:00 101", "21:00 104"), clock(tag.after))

        val keep = edit(PlanOp.Keep(106))
        assertEquals(listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104"), clock(keep.after))

        // Not planned → no change and a note.
        val missing = edit(PlanOp.Replace(106, with = 107))
        assertEquals(clock(plan), clock(missing.after))
        assertEquals(listOf("Brisket 101 isn't in your plan"), missing.notes)
    }

    @Test
    fun constraints() {
        val ate = edit(PlanOp.NoFoodUntil(Instant.parse("2026-11-14T19:00:00Z")))
        assertEquals(listOf("10:00 111", "11:00 109", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104"), clock(ate.after))
        assertEquals(setOf(MealSlot(LocalDate.of(2026, 11, 14), MealKind.LUNCH)), ate.after.constraints.mealsDone)

        assertEquals(
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101"),
            clock(edit(PlanOp.DoneBy(LocalTime.of(21, 0))).after),
        )
        assertEquals(listOf("15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104"), clock(edit(PlanOp.StartAt(LocalTime.of(14, 0))).after))
        assertEquals(
            listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "16:15 110", "18:00 dinner", "19:00 101"),
            clock(edit(PlanOp.MaxWalk(3)).after),
        )
        assertEquals(
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101"),
            clock(edit(PlanOp.Pace(PlanPace.RELAXED)).after),
        )
        val more = edit(PlanOp.MoreTag("bbq"))
        assertEquals(listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104"), clock(more.after))
        // bbq: 0.5 + 2·0.0551 + 1 (liked tag) + 1 (more) = 2.610.
        assertEquals(2610, Planner(bundle, musicAndBbq, hearts, more.after.constraints).score(bundle.scheduleItem(106)!!))
        val less = edit(PlanOp.LessTag("dj"))
        assertFalse(104 in less.after.itemIds)
        // More then less of the same tag: only "less" remains.
        assertEquals(setOf("bbq"), edit(PlanOp.MoreTag("bbq"), PlanOp.LessTag("bbq")).after.constraints.lessTags)
    }

    @Test
    fun originRestartsTheDayFromThere() {
        // 10:58 at Panther Island Pavilion: Juniper Lane (11:00, 4 min away) can't be reached any more.
        val at = now.plus(Duration.ofMinutes(58))
        val origin = edit(PlanOp.FromLocation(3), at = at)
        assertEquals(PlanOrigin(3, at), origin.after.constraints.origin)
        assertEquals(listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "16:15 110", "18:15 113", "19:00 101", "21:00 104"), clock(origin.after))
        // Gates open has started (frozen, kept verbatim); Brisket 101 shows the walk from the pavilion.
        assertEquals(listOf(111 to null, 106 to 3, 102 to 2, 103 to 0, 110 to 2, 113 to 2, 101 to 4, 104 to 4), origin.after.items.map { it.itemId to it.walkMinutes })
        // At the plan's own time and place nothing changes.
        assertEquals(clock(plan), clock(edit(PlanOp.FromLocation(6)).after))
    }

    @Test
    fun startedItemsAreFrozen() {
        // 3:30 PM: everything before stays; Big Sky Radio (in progress) is removed on request.
        val e = edit(PlanOp.Remove(103), at = now.plus(Duration.ofMinutes(330)))
        assertEquals(listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "17:00 105", "18:15 113", "19:00 101"), clock(e.after))
        assertEquals("2026-11-14T21:30:00Z", e.after.days.single().windowStart.toString())
        assertEquals(plan.items.take(3), e.after.items.take(3))
        // Checked-off items stay too.
        val checked = plan.copy(checkedOff = setOf(105))
        val c = edit(PlanOp.DoneBy(LocalTime.of(17, 30)), base = checked)
        assertTrue(105 in c.after.itemIds)
    }

    @Test
    fun moveToAnotherShowing() {
        val encore = ScheduleItem(115, "Juniper Lane", "", PlannerFixture.sun("15:30"), PlannerFixture.sun("16:30"), 1, "Main Stage", listOf("live music", "folk"))
        val repeat = bundle.copy(schedule = bundle.schedule + encore)
        val base = Planner(repeat, musicAndBbq, hearts).plan(now, "")
        assertEquals(clock(plan), clock(base))
        val moved = edit(PlanOp.Move(109, later = true), base = base, b = repeat)
        assertEquals(listOf(109 to 115), moved.diff.moved)
        assertTrue(115 in moved.after.itemIds)
        assertEquals(listOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)), moved.after.days.map { it.date })
        assertEquals(listOf("The Prairie Lights has no later time"), edit(PlanOp.Move(101, later = true)).notes)
    }

    @Test
    fun versions() {
        val chill = PlanVersions.make(PlanVersionKind.CHILL, plan, bundle, hearts, now)
        assertEquals(listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101"), clock(chill.after))
        assertEquals(PlanPace.RELAXED, chill.after.constraints.pace)
        assertEquals(10, chill.after.constraints.maxWalkMinutes)
        val everything = PlanVersions.make(PlanVersionKind.EVERYTHING, plan, bundle, hearts, now)
        assertEquals(clock(plan), clock(everything.after))
    }

    @Test
    fun editsOfMyScheduleKeepItsFoodPlaces() {
        // One list: the day view of My schedule (Taco Row at lunch) edited; the re-solve doesn't plan
        // another lunch (covered) and the person's lunch place stays in the result.
        val schedule = MySchedule()
        listOf(101, 105, 114).forEach { schedule.add(ScheduleTarget.item(it)) }
        schedule.add(ScheduleTarget.place(6), at = PlannerFixture.sat("12:15").toInstant())
        val day = schedule.dayPlan(bundle, SchedulePreferences(meals = MealKind.entries.toSet()))!!
        val edit = PlanEditor.preview(day, listOf(PlanOp.Pace(PlanPace.RELAXED)), bundle, schedule.hearts, now)
        val lunches = edit.after.days.flatMap { it.entries.filterIsInstance<MealBreak>() }.filter { it.kind == MealKind.LUNCH }
        assertEquals(listOf(6), lunches.map { it.locationId })
        assertTrue(edit.diff.mealsRemoved.isEmpty())
        // Applied: nothing removed or moved for the place.
        val proposal = schedule.proposal(edit.after, bundle, edit.after.constraints.excluded)
        assertFalse(proposal.removes.any { it.target == ScheduleTarget.place(6) })
        assertFalse(proposal.adds.any { it.target == ScheduleTarget.place(6) })
        // A planner plan (no covered slots) is unchanged by the rule.
        val plain = edit(PlanOp.Pace(PlanPace.RELAXED))
        assertEquals(plain, plain.keepingScheduleMeals(bundle))
    }

    @Test
    fun olderBuildsPlanHistoryIsReadOnce() {
        // Only for the migration (one list §6): the newest readable plan of the old history.
        val older = plan.copy(request = "v1")
        val newer = plan.copy(request = "v2")
        val text = JsonArray(listOf(PlanCodec.encode(older), PlanCodec.encode(newer), "broken").map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        assertEquals("v2", PlanCodec.newestInHistory(text)?.request)
        assertNull(PlanCodec.newestInHistory("nonsense"))
        assertNull(PlanCodec.newestInHistory(null))
    }

    @Test
    fun constraintsAndCheckOffsAreStored() {
        val edited = edit(PlanOp.NoFoodUntil(Instant.parse("2026-11-14T19:00:00Z")), PlanOp.Add(110), PlanOp.FromLocation(6), PlanOp.MoreTag("bbq"), PlanOp.DoneBy(LocalTime.of(23, 0)))
            .after.copy(checkedOff = setOf(111))
        assertEquals(edited, PlanCodec.decode(PlanCodec.encode(edited)))
    }

    @Test
    fun opsJson() {
        val ops = listOf(
            PlanOp.Replace(103, like = 114), PlanOp.Remove(109), PlanOp.Move(109, later = false), PlanOp.NoFoodUntil(Instant.parse("2026-11-14T19:00:00Z")),
            PlanOp.DoneBy(LocalTime.of(21, 0)), PlanOp.MaxWalk(10), PlanOp.Pace(PlanPace.RELAXED), PlanOp.FromLocation(6), PlanOp.LessTag("dj"),
        )
        val json = PlanOpJson.encode(ops, zone)
        assertEquals(
            """[{"op":"replace","item":"s103","like":"s114"},{"op":"remove","item":"s109"},{"op":"move","item":"s109","direction":"earlier"},{"op":"constraint","no_food_until":"2026-11-14T19:00:00Z"},{"op":"constraint","done_by":"21:00"},{"op":"constraint","max_walk_min":10},{"op":"constraint","pace":"relaxed"},{"op":"constraint","from_location":"l6"},{"op":"constraint","less_tag":"dj"}]""",
            json.toString(),
        )
        assertEquals(ops, PlanOpJson.parse(json, zone, now))
        // Clock times are today in the event's zone; numbers and "S12" ids are accepted.
        val lenient = Json.parseToJsonElement("""[{"op":"constraint","no_food_until":"13:00"},{"op":"add","item":110},{"op":"keep","item":"S106"}]""") as JsonArray
        assertEquals(listOf(PlanOp.NoFoodUntil(Instant.parse("2026-11-14T19:00:00Z")), PlanOp.Add(110), PlanOp.Keep(106)), PlanOpJson.parse(lenient, zone, now))
        assertNull(PlanOpJson.parse(Json.parseToJsonElement("""[{"op":"teleport","item":1}]"""), zone, now))
        assertNull(PlanOpJson.parse(Json.parseToJsonElement("""[{"op":"remove","item":"l3"}]"""), zone, now))
    }
}
