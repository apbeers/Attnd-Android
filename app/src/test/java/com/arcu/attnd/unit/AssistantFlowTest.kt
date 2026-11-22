package com.arcu.attnd.unit

import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantFlow
import com.arcu.attnd.domain.AssistantIntent
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.AssistantTranscript
import com.arcu.attnd.domain.AssistantTurn
import com.arcu.attnd.domain.BlockData
import com.arcu.attnd.domain.BlockJson
import com.arcu.attnd.domain.CompareSide
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveHintRules
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SharedPlans
import com.arcu.attnd.domain.StoredTurn
import com.arcu.attnd.domain.TurnPath
import com.arcu.attnd.unit.AssistantFixture.ctx
import com.arcu.attnd.unit.AssistantFixture.plan
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** The Android assistant screen's pure helpers ([AssistantFlow], [BlockData], [SharedPlans], transcript). */
class AssistantFlowTest {

    @Test
    fun path_commandsStayOnTheRouter_questionsGoToTheModel() {
        fun path(input: String, model: Boolean, forced: TurnPath? = null) =
            AssistantFlow.path(AssistantRouter.route(input, ctx()).intent, input, model, forced)
        assertEquals(TurnPath.SEARCH, path("tacos", model = true))
        assertEquals(TurnPath.SEARCH, path("like prairie lights", model = true))
        assertEquals(TurnPath.SEARCH, path("what's on tonight", model = true))
        assertEquals(TurnPath.PLAN, path("done by 9", model = true))
        assertEquals(TurnPath.PLAN, path("why isn't neon armadillo in my plan", model = false))
        assertEquals(TurnPath.ASK, path("where can I get something vegetarian?", model = true))
        assertEquals(TurnPath.SEARCH, path("where can I get something vegetarian?", model = false))
        assertEquals(TurnPath.ASK, path("xqzv blorp", model = true))
        // Forced: Ask needs the model; Search and Plan are always possible.
        assertEquals(TurnPath.SEARCH, path("done by 9", model = false, forced = TurnPath.ASK))
        assertEquals(TurnPath.SEARCH, path("done by 9", model = true, forced = TurnPath.SEARCH))
        assertEquals(TurnPath.PLAN, path("tacos", model = true, forced = TurnPath.PLAN))
    }

    @Test
    fun forcedSearchAndPlanTurns() {
        val search = AssistantFlow.searchTurn("tacos", ctx())
        assertTrue(search.blocks.any { it is AssistantBlock.PlaceCard && it.id == 6 })
        assertTrue(search.actions.isEmpty())
        val nothing = AssistantFlow.searchTurn("xqzv blorp", ctx())
        assertEquals(AssistantBlock.Text(AssistantRouter.NO_MATCH_TEXT), nothing.blocks.first())

        val routed = AssistantRouter.route("live music", ctx())
        val plan = AssistantFlow.planTurn("live music", routed, ctx())
        assertEquals("plan_make", plan.actions.single().name.key)
        // Without the planner a forced plan is just the router's turn.
        val noPlanner = ctx().copy(canPlan = false)
        assertEquals(routed.turn, AssistantFlow.planTurn("live music", AssistantRouter.route("live music", noPlanner), noPlanner))
        // A plan command keeps its own turn.
        val edit = AssistantRouter.route("done by 9", ctx())
        assertEquals(AssistantIntent.PLAN_EDIT, edit.intent)
        assertEquals(edit.turn, AssistantFlow.planTurn("done by 9", edit, ctx()))
    }

    @Test
    fun replyText_textElseNamesElseTypes() {
        assertEquals("Liked The Prairie Lights.", AssistantFlow.replyText(AssistantTurn(listOf(AssistantBlock.Text("Liked **The Prairie Lights**."))), bundle))
        assertEquals("Showed Taco Row, Street tacos at home", AssistantFlow.replyText(AssistantTurn(listOf(AssistantBlock.PlaceCard(6), AssistantBlock.ItemCard(107))), bundle))
        assertEquals("Showed share", AssistantFlow.replyText(AssistantTurn(listOf(AssistantBlock.Share("plan"))), bundle))
    }

    @Test
    fun askAbout() {
        assertEquals("Tell me about The Prairie Lights", AssistantFlow.askAbout(EntityRef(RefKind.SCHEDULE, 101), bundle))
        assertNull(AssistantFlow.askAbout(EntityRef(RefKind.SCHEDULE, 999), bundle))
        val turn = AssistantFlow.aboutTurn(EntityRef(RefKind.LOCATION, 6), ctx())
        assertEquals(AssistantBlock.PlaceCard(6), turn.blocks.first())
        assertTrue(turn.blocks.last() is AssistantBlock.Chips)
    }

    @Test
    fun timeline_sources() {
        assertEquals(listOf(1, 2), BlockData.timeline(AssistantBlock.Timeline(listOf(1, 2)), bundle, plan, now))
        assertEquals(plan.days.first().items.map { it.itemId }, BlockData.timeline(AssistantBlock.Timeline(source = "plan"), bundle, plan, now))
        assertEquals(emptyList<Int>(), BlockData.timeline(AssistantBlock.Timeline(source = "plan"), bundle, null, now))
        // Sat 10:00: not ended and starting before 1:00 PM, by (start, end, id).
        assertEquals(listOf(111, 109, 108, 106), BlockData.timeline(AssistantBlock.Timeline(source = "whats_on"), bundle, plan, now))
    }

    @Test
    fun compareSides() {
        val item = BlockData.compare("s101", plan, bundle, hearts, now)
        assertEquals(101, (item as CompareSide.Item).item.id)
        val chill = BlockData.compare("plan:chill", plan, bundle, hearts, now) as CompareSide.Version
        assertEquals(PlanVersionKind.CHILL, chill.kind)
        assertNull(BlockData.compare("plan:chill", null, bundle, hearts, now))
        assertNull(BlockData.compare("l1", plan, bundle, hearts, now))
    }

    @Test
    fun summaries() {
        assertEquals("2 items and 0 places", BlockData.summary("liked_counts", plan, bundle, hearts, now, DistanceUnits.IMPERIAL, Locale.US))
        assertEquals("1 item and 1 place", BlockData.likedCounts(Hearts(locations = setOf(1), scheduleItems = setOf(101))))
        val walking = BlockData.summary("walking", plan, bundle, hearts, now, DistanceUnits.IMPERIAL, Locale.US)
        assertTrue(walking!!, walking.endsWith(" walking today"))
        assertEquals("You saw 0 things, added 2 and walked 10 ft.", BlockData.summary("recap", plan, bundle, hearts, now, DistanceUnits.IMPERIAL, Locale.US))
        assertNull(BlockData.summary("walking", null, bundle, hearts, now, DistanceUnits.IMPERIAL, Locale.US))
    }

    @Test
    fun transcript_roundTripsAndIsLenient() {
        val routed = AssistantRouter.route("tacos", ctx())
        val turns = listOf(StoredTurn("tacos", AssistantTurn(routed.blocks)), StoredTurn(null, AssistantTurn(listOf(AssistantBlock.Text("Hi")))))
        val decoded = AssistantTranscript.decode(AssistantTranscript.encode(turns))
        assertEquals(turns, decoded)
        assertEquals(BlockJson.encode(routed.turn.copy(actions = emptyList())), BlockJson.encode(decoded.first().turn))
        // Unknown blocks dropped, malformed turns skipped, broken text empty.
        val text = """[{"input":"x","blocks":[{"type":"hologram"},{"type":"text","text":"ok"}],"actions":[{"name":"nope"}]},{"blocks":"no"},7]"""
        assertEquals(listOf(StoredTurn("x", AssistantTurn(listOf(AssistantBlock.Text("ok"))))), AssistantTranscript.decode(text))
        assertEquals(emptyList<StoredTurn>(), AssistantTranscript.decode("{"))
        assertEquals(emptyList<StoredTurn>(), AssistantTranscript.decode(null))
        // At most 30 turns are kept.
        val many = (1..40).map { StoredTurn("$it", AssistantTurn(listOf(AssistantBlock.Text("$it")))) }
        assertEquals("11", AssistantTranscript.decode(AssistantTranscript.encode(many)).first().input)
    }

    @Test
    fun sharedPlan_useIsExactlyTheSharedItemsAhead() {
        val used = SharedPlans.use(listOf(109, 103, 999, 101), LocalDate.of(2026, 11, 14), bundle, Hearts.NONE, now)
        assertNotNull(used)
        assertEquals(listOf(109, 103, 101), used!!.itemIds)
        assertEquals(setOf(109, 103, 101), used.constraints.pinned)
        assertTrue(used.constraints.excluded.isEmpty())
        assertNull(SharedPlans.use(listOf(999), null, bundle, Hearts.NONE, now))
        // Items that already ended are left out.
        val evening = Instant.parse("2026-11-15T02:00:00Z") // Sat 8:00 PM
        assertEquals(listOf(104), SharedPlans.use(listOf(109, 104), null, bundle, Hearts.NONE, evening)!!.itemIds)
    }

    @Test
    fun sharedPlan_mergeWeighsEveryone() {
        val merged = SharedPlans.merge(listOf(listOf(103, 105), listOf(103)), hearts, bundle, plan, now)
        assertNotNull(merged)
        assertTrue(merged!!.itemIds.containsAll(listOf(103, 101)))
        assertEquals(2, merged.constraints.groupLikes[103])
        assertNull(SharedPlans.merge(emptyList(), hearts, bundle, plan, now))
    }

    @Test
    fun liveHints() {
        val none = LiveHintRules.of(null, bundle, hearts, now, null, emptyList())
        assertTrue(none.isEmpty)
        val morning = LiveHintRules.of(plan, bundle, hearts, now, null, emptyList())
        assertNull(morning.runningLate)
        assertFalse(morning.recapReady)
        val late = Instant.parse("2026-11-15T05:30:00Z") // Sat 11:30 PM: every planned item ended
        assertTrue(LiveHintRules.of(plan, bundle, hearts, late, null, emptyList()).recapReady)
    }

    @Test
    fun liveHintsAndWhyNotReadMyScheduleOnly() {
        // One list §4: hints come from My schedule's day view, never a stored plan.
        val mine = com.arcu.attnd.domain.MySchedule()
        plan.itemIds.forEach { mine.add(com.arcu.attnd.domain.ScheduleTarget.item(it)) }
        val day = mine.dayPlan(bundle)!!
        assertEquals(plan.itemIds.toSet(), day.itemIds.toSet())
        val late = Instant.parse("2026-11-15T05:30:00Z") // Sat 11:30 PM: every item ended
        assertTrue(LiveHintRules.of(day, bundle, mine.hearts, late, null, emptyList()).recapReady)
        assertFalse(LiveHintRules.of(day, bundle, mine.hearts, now, null, emptyList()).recapReady)
        val why = com.arcu.attnd.domain.WhyNot.explain(plan.itemIds.first(), day, bundle, mine.hearts, now, java.util.Locale.US)
        assertEquals(com.arcu.attnd.domain.WhyNotReason.InPlan, why?.reason)
        assertTrue(com.arcu.attnd.domain.PlanShare.planLink(day, bundle).contains("/p/"))
    }
}
