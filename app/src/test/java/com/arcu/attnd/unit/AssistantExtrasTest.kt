package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.DayRoutes
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.GroupPlan
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.LocationSample
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanDay
import com.arcu.attnd.domain.PlanEditor
import com.arcu.attnd.domain.PlanOp
import com.arcu.attnd.domain.PlanPreferences
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.SharedPayload
import com.arcu.attnd.domain.WalkRoute
import com.arcu.attnd.domain.WhyNot
import com.arcu.attnd.unit.AssistantFixture.clock
import com.arcu.attnd.unit.AssistantFixture.musicAndBbq
import com.arcu.attnd.unit.AssistantFixture.plan
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import com.arcu.attnd.unit.PlannerFixture.sat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.Locale

/** Assistant spec §5–7: day routes, live rules, share links (API-015), group plans, why-not. */
class AssistantExtrasTest {

    private val saturday = LocalDate.of(2026, 11, 14)
    private val panther = bundle.location(3)!!.coordinate!!
    private val mainStage = bundle.location(1)!!.coordinate!!
    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))
    private fun b64(json: String) = Base64.getUrlEncoder().encodeToString(json.toByteArray())

    // ── Share (API-015) ─────────────────────────────────────────────────────

    @Test
    fun shareCodecMatchesTheServer() {
        assertEquals("""{"v":1,"ids":[101,114],"d":"2026-11-14"}""", PlanShare.json(listOf(101, 114), saturday))
        assertEquals("eyJ2IjoxLCJpZHMiOlsxMDEsMTE0XSwiZCI6IjIwMjYtMTEtMTQifQ", PlanShare.payload(listOf(101, 114), saturday))
        assertEquals(SharedPayload(1, listOf(101, 114), saturday), PlanShare.decode("eyJ2IjoxLCJpZHMiOlsxMDEsMTE0XSwiZCI6IjIwMjYtMTEtMTQifQ"))
        assertEquals("""{"v":1,"ids":[101]}""", PlanShare.json(listOf(101), null))
        assertEquals(
            "https://attnd.app/p/DEMO/eyJ2IjoxLCJpZHMiOlsxMTEsMTA5LDEwMiwxMDMsMTA1LDExMywxMDEsMTA0XSwiZCI6IjIwMjYtMTEtMTQifQ",
            PlanShare.planLink(plan, bundle),
        )
        assertEquals(SharedPayload(1, listOf(111, 109, 102, 103, 105, 113, 101, 104), saturday), PlanShare.decode(PlanShare.planLink(plan, bundle).substringAfterLast('/')))
        assertEquals(SharedPayload(1, listOf(101), null), PlanShare.decode(PlanShare.itemLink(101, bundle).substringAfterLast('/')))
    }

    @Test
    fun myScheduleShareLinkListsItsItemsInTimeOrder() {
        val items = bundle.schedule.sortedWith(compareBy({ it.startTime?.toInstant() ?: java.time.Instant.MAX }, { it.id })).map { it.id }
        val later = items[3]
        val first = items[0]
        val schedule = com.arcu.attnd.domain.MySchedule(listOf(
            com.arcu.attnd.domain.ScheduleEntry(com.arcu.attnd.domain.ScheduleEntryKind.ITEM, later),
            com.arcu.attnd.domain.ScheduleEntry(com.arcu.attnd.domain.ScheduleEntryKind.PLACE, 1),
            com.arcu.attnd.domain.ScheduleEntry(com.arcu.attnd.domain.ScheduleEntryKind.ITEM, first),
        ))
        val link = PlanShare.scheduleLink(schedule, bundle)!!
        assertEquals(SharedPayload(1, listOf(first, later), null), PlanShare.decode(link.substringAfterLast('/')))
        assertEquals(null, PlanShare.scheduleLink(com.arcu.attnd.domain.MySchedule(), bundle))
    }

    @Test
    fun shareDecodingIsLenient() {
        // Padding, "s12" ids, a malformed day, unknown keys.
        assertEquals(SharedPayload(1, listOf(101, 114), saturday), PlanShare.decode("eyJ2IjoxLCJpZHMiOlsxMDEsMTE0XSwiZCI6IjIwMjYtMTEtMTQifQ=="))
        assertEquals(SharedPayload(1, listOf(101, 114), null), PlanShare.decode(b64("""{"v":1,"ids":["s101",114,"l3"],"d":"Saturday","x":true}""")))
        assertEquals(SharedPayload(2, emptyList(), null), PlanShare.decode(b64("""{"v":2,"plan":{}}""")))
        assertFalse(PlanShare.decode(b64("""{"v":2}"""))!!.isReadable)
        assertNull(PlanShare.decode(b64("""{"v":1,"ids":"101"}""")))
        assertNull(PlanShare.decode(b64("""{"ids":[101]}""")))
        assertNull(PlanShare.decode("not base64 json!"))
        assertNull(PlanShare.decode("A".repeat(1600)))
        // Ids no longer in the bundle are gone; long plans are cut to 1,500 characters.
        assertEquals(listOf(101), PlanShare.resolve(SharedPayload(1, listOf(101, 999), null), bundle))
        val long = PlanShare.payload((1..1000).toList())
        assertTrue(long.length <= PlanShare.MAX_PAYLOAD)
        assertEquals((1..PlanShare.decode(long)!!.ids.size).toList(), PlanShare.decode(long)!!.ids)
    }

    @Test
    fun shareLinks() {
        val payload = "eyJ2IjoxLCJpZHMiOlsxMDEsMTE0XSwiZCI6IjIwMjYtMTEtMTQifQ"
        assertEquals(JoinLinks.ShareLink("DEMO", payload), JoinLinks.parseShareLink("https://attnd.app/p/DEMO/$payload"))
        assertEquals(JoinLinks.ShareLink("DEMO", payload), JoinLinks.parseShareLink("https://www.attnd.app/p/DEMO/$payload/?utm=x#top"))
        assertEquals(JoinLinks.ShareLink("FW 26", payload), JoinLinks.parseShareLink("attnd.app/p/FW%2026/$payload"))
        assertNull(JoinLinks.parseShareLink("https://attnd.app/p/DEMO/$payload/qr.svg"))
        assertNull(JoinLinks.parseShareLink("https://attnd.app/p/DEMO/"))
        assertNull(JoinLinks.parseShareLink("https://example.com/p/DEMO/$payload"))
        assertNull(JoinLinks.parseShareLink("https://attnd.app/join/DEMO/"))
        assertNull(JoinLinks.parseShareLink(null))
    }

    @Test
    fun groupPlan() {
        val (merged, counts) = GroupPlan.merge(listOf(listOf(103, 114), listOf(114, 110, 999)), hearts, bundle)
        assertEquals(setOf(101, 103, 110, 114), merged.scheduleItems)
        assertEquals(mapOf(101 to 1, 103 to 1, 110 to 1, 114 to 3), counts)
        val group = GroupPlan.plan(listOf(listOf(103, 114), listOf(114, 110)), hearts, bundle, musicAndBbq, now)
        assertEquals(listOf("10:00 111", "12:00 106", "13:00 102", "15:00 103", "16:15 110", "18:15 113", "18:45 dinner", "20:00 114"), clock(group))
        assertEquals("Picked by 3 of you", group.items.single { it.itemId == 114 }.reason)
        assertEquals("In your schedule", group.items.single { it.itemId == 103 }.reason)
    }

    // ── Why not ─────────────────────────────────────────────────────────────

    @Test
    fun whyNot() {
        fun why(id: Int, p: Plan? = plan, at: Instant = now) = WhyNot.explain(id, p, bundle, hearts, at, Locale.US)?.text
        assertEquals("Neon Armadillo clashes with The Prairie Lights (in your schedule).", why(114))
        assertEquals("There isn't time to walk between Brisket 101 and Juniper Lane.", why(106))
        assertEquals("Los Hermanos Flores is on Sunday, and your plan covers Saturday.", why(201))
        assertEquals("Gates open is already in your plan.", why(111))
        assertEquals("You don't have a plan yet.", why(114, p = null))
        val withoutJuniper = plan.copy(days = plan.days.map { d -> d.copy(entries = d.entries.filter { (it as? PlannedItem)?.itemId != 109 }) })
        assertEquals("Juniper Lane has ended.", why(109, withoutJuniper, at = at(180)))
        assertEquals("Juniper Lane has already started.", why(109, withoutJuniper, at = at(70)))
        val removed = PlanEditor.preview(plan, listOf(PlanOp.Remove(109)), bundle, hearts, now).after
        assertEquals("You took Juniper Lane out of your plan.", why(109, removed))
        val doneBy = PlanEditor.preview(plan, listOf(PlanOp.DoneBy(LocalTime.of(21, 0))), bundle, hearts, now).after
        assertEquals("DJ Lone Star is outside your plan's hours (10:00 AM – 9:00 PM).", why(104, doneBy))
        val kids = Planner(bundle, PlanPreferences(kidFriendly = true), Hearts.NONE).plan(now, "")
        assertEquals("Beer tasting is 21+, and you asked for kid-friendly.", WhyNot.explain(112, kids, bundle, Hearts.NONE, now, Locale.US)?.text)
        val ate = PlanEditor.preview(plan, listOf(PlanOp.NoFoodUntil(Instant.parse("2026-11-14T19:00:00Z"))), bundle, hearts, now).after
        assertEquals("You said you already ate, so food waits until 1:00 PM.", why(106, ate))
        val chip = WhyNot.chip(WhyNot.explain(114, plan, bundle, hearts, now)!!, bundle)!!
        assertEquals("Swap it in", chip.label)
        assertEquals(AssistantAction.planEdit(listOf(PlanOp.Add(114)), bundle), chip.action)
        // Swapping it in pins it ahead of the liked item it clashes with.
        val swapped = PlanEditor.preview(plan, listOf(PlanOp.Add(114)), bundle, hearts, now).after
        assertTrue(114 in swapped.itemIds)
        assertFalse(101 in swapped.itemIds)
        assertEquals(listOf(101), swapped.items.single { it.itemId == 114 }.orInstead)
    }

    // ── Day route ───────────────────────────────────────────────────────────

    @Test
    fun dayRoute() {
        val route = DayRoutes.of(plan, bundle)!!
        assertEquals(listOf("1:1[109]", "2:2[102, 103]", "3:1[105]", "4:2[113]", "5:1[101]", "6:3[104]"), route.stops.map { "${it.number}:${it.locationId}${it.itemIds}" })
        assertEquals(listOf(109 to 102, 103 to 105, 105 to 113, 113 to 101, 101 to 104), route.legs.map { it.fromItem to it.toItem })
        assertEquals(List(5) { "about 4 min walk · 0.2 mi" }, route.legs.map { DayRoutes.legText(it, DistanceUnits.IMPERIAL, Locale.US) })
        assertEquals("0.8 mi walking today", DayRoutes.totalText(route, saturday, DistanceUnits.IMPERIAL, Locale.US))
        assertEquals("1.3 km walking on Saturday", DayRoutes.totalText(route, saturday.plusDays(1), DistanceUnits.METRIC, Locale.US))
        assertEquals(6, DayRoutes.waypoints(route)!!.size)
        // Service legs: plausible ones replace the estimate, a route > 2.5× the straight line doesn't.
        val upgraded = DayRoutes.withServiceLegs(route, listOf(WalkRoute(300.0, 240, listOf(mainStage, panther), false), WalkRoute(5000.0, 3600, emptyList(), false), null))
        assertEquals("4 min walk · 0.2 mi", DayRoutes.legText(upgraded.legs[0], DistanceUnits.IMPERIAL, Locale.US))
        assertTrue(upgraded.legs[1].route.isEstimate)
        assertEquals(route.legs.drop(1), upgraded.legs.drop(1))
        // Only checked-off items (the recap).
        assertEquals(listOf(1, 2), DayRoutes.of(plan, bundle, only = setOf(109, 102, 103))!!.stops.map { it.locationId })
        assertNull(DayRoutes.of(plan, bundle, date = saturday.plusDays(1)))
    }

    // ── Live rules ──────────────────────────────────────────────────────────

    @Test
    fun runningLate() {
        // Juniper Lane starts at 11:00 on the Main Stage, 4 min from the pavilion: leave by 10:51.
        assertNull(LiveAssist.runningLate(plan, bundle, at(40), panther))
        val leave = LiveAssist.runningLate(plan, bundle, at(53), panther)!!
        assertEquals(LateState.LEAVE_NOW, leave.state)
        assertEquals(Instant.parse("2026-11-14T16:51:00Z"), leave.leaveBy)
        assertEquals("Leave now for Juniper Lane · 4 min walk", leave.text)
        assertEquals(listOf("Directions"), leave.chips.map { it.label })
        val late = LiveAssist.runningLate(plan, bundle, at(58), panther)!!
        assertEquals(LateState.CANT_MAKE_IT, late.state)
        assertEquals("You can't make Juniper Lane in time · 4 min walk", late.text)
        assertEquals(listOf("Swap for Brisket 101", "Remove Juniper Lane"), late.chips.map { it.label })
        assertEquals(AssistantAction.planEdit(listOf(PlanOp.Replace(109, with = 106)), bundle), late.chips.first().action)
        assertNull(LiveAssist.runningLate(plan, bundle, at(58), mainStage)) // already there
        assertNull(LiveAssist.runningLate(plan, bundle, at(58), null))
        assertNull(LiveAssist.runningLate(plan, bundle, at(58), GeoPoint(30.2672, -97.7431))) // Austin: too far to say
    }

    @Test
    fun checkOffAndSkipped() {
        val juniper = bundle.scheduleItem(109)!!
        fun sample(point: GeoPoint, minute: Long) = LocationSample(point, sat("11:00").toInstant().plus(Duration.ofMinutes(minute)))
        val near = GeoPoint(mainStage.latitude + 0.0005, mainStage.longitude) // ~55 m
        assertTrue(LiveAssist.autoCheck(juniper, bundle, listOf(sample(near, 5), sample(mainStage, 10), sample(near, 15))))
        assertFalse(LiveAssist.autoCheck(juniper, bundle, listOf(sample(near, 5), sample(near, 12))))
        assertFalse(LiveAssist.autoCheck(juniper, bundle, listOf(sample(near, 5), sample(panther, 9), sample(near, 15))))
        assertFalse(LiveAssist.autoCheck(juniper, bundle, listOf(sample(near, -20), sample(near, -5)))) // before it started
        val samples = listOf(sample(near, 5), sample(near, 20))
        assertEquals(setOf(109), LiveAssist.checkOffs(plan, bundle, samples, at(150)))
        val elsewhere = listOf(sample(panther, 10), sample(panther, 40))
        assertEquals(listOf(109), LiveAssist.skipped(plan, bundle, elsewhere, at(150)))
        assertEquals(emptyList<Int>(), LiveAssist.skipped(plan, bundle, emptyList(), at(150)))
        assertEquals(emptyList<Int>(), LiveAssist.skipped(plan.copy(checkedOff = setOf(109)), bundle, elsewhere, at(150)))
        val chip = LiveAssist.skippedChip(109, bundle)!!
        assertEquals("You skipped Juniper Lane — fill the gap?", chip.label)
        assertEquals(AssistantAction.planEdit(listOf(PlanOp.Remove(109)), bundle), chip.action)
    }

    @Test
    fun gapFillerAndLikeClash() {
        assertEquals(emptyList<String>(), LiveAssist.gaps(plan, bundle, hearts, now).map { it.text })
        val twoItems = Plan(
            42, now, "", PlanPreferences(),
            listOf(
                PlanDay(
                    saturday, now, Instant.parse("2026-11-15T06:00:00Z"),
                    listOf(
                        PlannedItem(103, sat("15:00").toInstant(), sat("16:00").toInstant(), "", null, emptyList(), emptyList()),
                        PlannedItem(113, sat("18:15").toInstant(), sat("18:45").toInstant(), "", 0, emptyList(), emptyList()),
                    ),
                ),
            ),
            emptyMap(),
        )
        val gap = LiveAssist.gaps(twoItems, bundle, Hearts.NONE, now).single()
        assertEquals("135 free minutes near Trinity Stage: Kolache masterclass is 2 min away", gap.text)
        assertEquals(110, gap.itemId)
        assertEquals("Add Kolache masterclass", gap.chip.label)

        val clash = LiveAssist.likeClash(112, plan, bundle)!!
        assertEquals("Replace Sundown Choir with this?", clash.label)
        assertEquals(AssistantAction.planEdit(listOf(PlanOp.Replace(113, with = 112)), bundle), clash.action)
        assertNull(LiveAssist.likeClash(101, plan, bundle))
        assertNull(LiveAssist.likeClash(201, plan, bundle))
    }

    @Test
    fun recapAndChanges() {
        val recap = LiveAssist.recap(plan.copy(checkedOff = setOf(109, 102, 103)), bundle, hearts, saturday, at(420), DistanceUnits.IMPERIAL, Locale.US)
        assertEquals("You saw 3 things, added 2 and walked 0.2 mi.", recap.text)
        assertEquals(listOf(109, 102, 103), recap.checkedOff)
        assertEquals(listOf("Plan tomorrow"), recap.chips.map { it.label })
        assertEquals(AssistantAction.planMake("Plan my Sunday"), recap.chips.single().action)
        assertEquals(ActionName.PLAN_MAKE, recap.chips.single().action.name)
    }
}
