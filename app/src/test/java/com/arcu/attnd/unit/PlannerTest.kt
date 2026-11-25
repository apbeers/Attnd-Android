package com.arcu.attnd.unit

import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.MealKind
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.PlanPace
import com.arcu.attnd.domain.PlanPreferences
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.TfIdfSimilarity
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Shared spec §6 (the same cases as iOS): scores, choice, pace, meals, reasons, swap, changes, storage. */
class PlannerTest {

    private val zone = ZoneId.of("America/Chicago")
    private val musicAndBbq = PlanPreferences(likedTags = setOf("live music", "bbq"), includeMeals = true)

    private fun clock(plan: Plan): List<String> = plan.days.flatMap { day ->
        day.entries.map { entry ->
            val time = entry.start.atZone(zone).toLocalTime().toString()
            when (entry) {
                is PlannedItem -> "$time ${entry.itemId}"
                is MealBreak -> "$time ${entry.kind.name.lowercase()}"
            }
        }
    }

    private fun item(id: Int) = bundle.scheduleItem(id)!!

    @Test
    fun tfIdfSimilarity() {
        val similarity = TfIdfSimilarity(bundle)
        fun s(a: Int, b: Int) = similarity.similarity(item(a), item(b))
        assertEquals(1.0, s(101, 101), 0.0)
        assertEquals(0.4854, s(101, 103), 0.0001) // both country, "Texas", live music
        assertEquals(0.2108, s(101, 114), 0.0001)
        assertEquals(0.0551, s(101, 106), 0.0001)
        assertEquals(0.4586, s(106, 107), 0.0001) // chef demos at the same kitchen
        assertEquals(0.0, s(101, 111), 0.0) // nothing shared
        assertEquals(s(103, 101), s(101, 103), 0.0)
    }

    @Test
    fun scoresAreIntegerMilliPoints() {
        val planner = Planner(bundle, musicAndBbq, hearts)
        assertEquals(4922, planner.score(item(101))) // 0.5 + 3 + 2·0.2108 + 1
        assertEquals(4922, planner.score(item(114)))
        assertEquals(2471, planner.score(item(103))) // 0.5 + 2·0.4854 + 1
        assertEquals(1610, planner.score(item(106))) // bbq: 0.5 + 2·0.0551 + 1
        assertEquals(500, planner.score(item(111))) // base only
        val disliked = Planner(bundle, PlanPreferences(dislikedTags = setOf("dj")), Hearts.NONE)
        assertEquals(-1500, disliked.score(item(104)))
    }

    @Test
    fun balancedPlanKeepsTheBetterHeartedItemAndOffersTheOther() {
        val plan = Planner(bundle, musicAndBbq, hearts).plan(now, "Plan my Saturday around live music and BBQ")
        assertEquals(
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101", "21:00 104"),
            clock(plan),
        )
        val day = plan.days.single()
        assertEquals(LocalDate.of(2026, 11, 14), day.date)
        // Neon Armadillo clashes with The Prairie Lights (same score, later start): "or instead".
        val prairie = plan.items.single { it.itemId == 101 }
        assertEquals(listOf(114), prairie.orInstead)
        assertEquals(listOf(114), prairie.alternatives)
        assertEquals("In your schedule", prairie.reason)
        assertEquals(4, prairie.walkMinutes)
        // The lunch break suggests the food location nearest to where the attendee was.
        val lunch = day.entries.filterIsInstance<MealBreak>().single()
        assertEquals(MealKind.LUNCH, lunch.kind)
        assertEquals(6, lunch.locationId)
        assertEquals(Duration.ofMinutes(45), Duration.between(lunch.start, lunch.end))
        // Reasons and walks.
        assertEquals("Because The Prairie Lights is in your schedule", plan.items.single { it.itemId == 103 }.reason)
        assertEquals("Because you asked for live music", plan.items.single { it.itemId == 104 }.reason)
        assertNull(plan.items.first().walkMinutes) // first item of the day
        assertNull(plan.items[1].walkMinutes) // Gates open has no location
        assertEquals(0, plan.items.single { it.itemId == 103 }.walkMinutes) // same stage as Ana Morales Trio
        assertEquals(listOf(106), plan.items.single { it.itemId == 109 }.alternatives)
    }

    @Test
    fun summaryLine() {
        val plan = Planner(bundle, musicAndBbq, hearts).plan(now, "")
        assertEquals("8 items, 10:00 AM – 11:00 PM, with a lunch break", Planner.summary(plan, zone, java.util.Locale.US))
        val empty = Planner(bundle, PlanPreferences(days = listOf(LocalDate.of(2026, 11, 15)), earliest = LocalTime.of(22, 0)), Hearts.NONE).plan(now, "")
        assertEquals("Nothing fits", Planner.summary(empty, zone, java.util.Locale.US))
    }

    @Test
    fun paceCapsTheBookedShare() {
        val relaxed = Planner(bundle, musicAndBbq.copy(pace = PlanPace.RELAXED), hearts).plan(now, "")
        assertEquals(
            listOf("10:00 111", "11:00 109", "12:00 lunch", "13:00 102", "15:00 103", "17:00 105", "18:15 113", "19:00 101"),
            clock(relaxed),
        )
        // Span 10:00–23:00 = 780 min; relaxed books at most 390.
        val booked = relaxed.items.sumOf { Duration.between(it.start, it.end).toMinutes() }
        assertEquals(390, booked)
        assertEquals(listOf(114, 104), relaxed.items.single { it.itemId == 101 }.alternatives)

        val packed = Planner(bundle, PlanPreferences(pace = PlanPace.PACKED), Hearts.NONE).plan(now, "")
        assertEquals(
            listOf("10:00 111", "12:00 106", "13:00 102", "14:15 107", "16:15 110", "18:15 113", "19:00 101", "21:00 104"),
            clock(packed),
        )
        assertTrue(packed.items.all { it.reason == "Fits your day" })
    }

    @Test
    fun kidFriendlyWithThreeHours() {
        val plan = Planner(bundle, PlanPreferences(kidFriendly = true, durationMinutes = 180), Hearts.NONE).plan(now, "")
        val day = plan.days.single()
        assertEquals(LocalTime.of(13, 0), day.windowEnd.atZone(zone).toLocalTime())
        assertEquals(listOf("11:00 108"), clock(plan))
        assertEquals("Kid-friendly", plan.items.single().reason)
        assertEquals(listOf(111, 109, 106), plan.items.single().alternatives)
        // The 21+ tasting never appears with kids.
        val evening = Planner(bundle, PlanPreferences(kidFriendly = true, earliest = LocalTime.of(17, 0)), Hearts.NONE).plan(now, "")
        assertFalse(112 in evening.itemIds)
    }

    @Test
    fun namedDayAndLikedTag() {
        val plan = Planner(bundle, PlanPreferences(days = listOf(LocalDate.of(2026, 11, 15)), likedTags = setOf("rock")), hearts).plan(now, "")
        assertEquals(listOf("09:00 203", "12:00 201", "14:00 202"), clock(plan))
        assertEquals(LocalDate.of(2026, 11, 15), plan.days.single().date)
        // Days that aren't event days are ignored; with none left the first day with candidates is planned.
        val fallback = Planner(bundle, PlanPreferences(days = listOf(LocalDate.of(2026, 12, 1))), Hearts.NONE).plan(now, "")
        assertEquals(LocalDate.of(2026, 11, 14), fallback.days.single().date)
    }

    @Test
    fun eveningWithDislikeAndExample() {
        val plan = Planner(
            bundle,
            PlanPreferences(earliest = LocalTime.of(17, 0), dislikedTags = setOf("dj"), similarTo = setOf(103)),
            Hearts.NONE,
        ).plan(now, "")
        assertEquals(listOf("17:00 105", "18:15 113", "20:00 114"), clock(plan))
        assertEquals("Similar to Big Sky Radio", plan.items.single { it.itemId == 114 }.reason)
        assertFalse(104 in plan.itemIds)
    }

    @Test
    fun latestBeforeSixIsTheNextMorning() {
        val plan = Planner(bundle, PlanPreferences(earliest = LocalTime.of(20, 0), latest = LocalTime.of(1, 0)), Hearts.NONE).plan(now, "")
        val day = plan.days.single()
        assertEquals("2026-11-15T07:00:00Z", day.windowEnd.toString())
        // Span 20:00–23:00 = 180 min, cap 126: one of Neon Armadillo (60) and DJ Lone Star (120); equal
        // scores, so the one that books fewer minutes.
        assertEquals(listOf("20:00 114"), clock(plan))
        assertEquals(listOf(104), plan.items.single().alternatives)
    }

    @Test
    fun replanFromLaterDropsStartedItems() {
        val later = now.plus(Duration.ofHours(9)) // 7:00 PM
        val plan = Planner(bundle, musicAndBbq, hearts).plan(later, "")
        // Span 19:00–23:00 = 240 min, cap 168: The Prairie Lights (90) leaves no room for DJ Lone Star (120).
        assertEquals(listOf("19:00 101"), clock(plan))
        assertEquals(listOf(114, 104), plan.items.single().alternatives)
        assertEquals("2026-11-15T01:00:00Z", plan.days.single().windowStart.toString())
    }

    @Test
    fun swapReplacesAnItemWithAnAlternative() {
        val planner = Planner(bundle, musicAndBbq, hearts)
        val plan = planner.plan(now, "")
        val swapped = planner.swap(plan, oldId = 101, newId = 114)
        assertNotNull(swapped)
        swapped!!
        assertTrue(114 in swapped.itemIds)
        assertFalse(101 in swapped.itemIds)
        val neon = swapped.items.single { it.itemId == 114 }
        assertEquals(listOf(101), neon.orInstead) // the hearted one can be swapped back
        assertEquals(101, neon.alternatives.first())
        assertNull(planner.swap(plan, oldId = 101, newId = 9999))
        assertNull(planner.swap(plan, oldId = 9999, newId = 114))
    }

    @Test
    fun changesAreNoticed() {
        val plan = Planner(bundle, musicAndBbq, hearts).plan(now, "")
        assertFalse(Planner.changed(plan, bundle))
        val moved = bundle.copy(schedule = bundle.schedule.map { if (it.id == 105) it.copy(startTime = PlannerFixture.sat("20:30"), endTime = PlannerFixture.sat("21:30")) else it })
        assertTrue(Planner.changed(plan, moved))
        val removed = bundle.copy(schedule = bundle.schedule.filter { it.id != 113 })
        assertTrue(Planner.changed(plan, removed))
        val unrelated = bundle.copy(schedule = bundle.schedule.map { if (it.id == 112) it.copy(name = "Beer flight") else it })
        assertFalse(Planner.changed(plan, unrelated))
    }

    @Test
    fun codecRoundTripsAndIsLenient() {
        val plan = Planner(bundle, musicAndBbq.copy(earliest = LocalTime.of(10, 0), days = listOf(LocalDate.of(2026, 11, 14))), hearts).plan(now, "Plan my Saturday")
        assertEquals(plan, PlanCodec.decode(PlanCodec.encode(plan)))
        assertNull(PlanCodec.decode("not json"))
        assertNull(PlanCodec.decode(""))
        val extra = PlanCodec.decode("""{"eventId":42,"createdAt":"2026-11-14T16:00:00Z","future":1,"days":[]}""")
        assertEquals(42, extra?.eventId)
        assertTrue(extra!!.isEmpty)
    }

    @Test
    fun dayViewLaysOutChosenItemsWithoutInventingMeals() {
        // One list: My schedule's items of a day, laid out by the planner's rules (walks, alternatives),
        // the given meals kept verbatim, no new meal breaks.
        val day = LocalDate.of(2026, 11, 14)
        val planner = Planner(bundle, PlanPreferences(), Hearts(scheduleItems = setOf(101, 105, 109)))
        val lunch = MealBreak(MealKind.LUNCH, PlannerFixture.sat("12:15").toInstant(), PlannerFixture.sat("13:00").toInstant(), 6)
        val view = planner.dayView(day, listOf(101, 105, 109, 109), listOf(lunch), PlannerFixture.sat("10:00").toInstant(), PlannerFixture.sat("23:00").toInstant())
        assertEquals(listOf("11:00 109", "12:15 lunch", "17:00 105", "19:00 101"), clock(Plan(42, now, "", PlanPreferences(), listOf(view), emptyMap())))
        assertNull(view.items.first().walkMinutes)
        assertNotNull(view.items.last().walkMinutes)
        assertTrue(view.items.all { it.reason == "In your schedule" })
    }
}
