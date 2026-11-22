package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.MealKind
import com.arcu.attnd.domain.MealSlot
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.PlanCommands
import com.arcu.attnd.domain.PlanDiff
import com.arcu.attnd.domain.PlanOp
import com.arcu.attnd.domain.PlanProposals
import com.arcu.attnd.domain.PlanTabFlow
import com.arcu.attnd.domain.PlanVersionKind
import com.arcu.attnd.domain.PreviewRow
import com.arcu.attnd.domain.PreviewRows
import com.arcu.attnd.domain.ProposalKind
import com.arcu.attnd.domain.ProposalReply
import com.arcu.attnd.domain.ProposalText
import com.arcu.attnd.domain.RowMark
import com.arcu.attnd.domain.SequenceKind
import com.arcu.attnd.domain.SwapCommand
import com.arcu.attnd.domain.propose
import com.arcu.attnd.unit.AssistantFixture.ctx
import com.arcu.attnd.unit.AssistantFixture.plan
import com.arcu.attnd.unit.PlannerFixture.bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Proposals previewed in place (plan tab spec, the Android rows; re-synced with iOS `PlanProposalTests`: "more bbq"
 * instead of "remove …", which is a My schedule command now), typed replies, swap and sequences. Fixture plan
 * (Sat Nov 14): 10:00 111 · 11:00 109 · 12:00 lunch · 13:00 102 · 15:00 103 · 17:00 105 · 18:15 113 · 19:00 101 · 21:00 104.
 */
class PlanProposalTest {

    private val sat = LocalDate.of(2026, 11, 14)

    // ── Proposals ───────────────────────────────────────────────────────────

    private fun Iterable<PreviewRow>.text(): List<String> = map { row ->
        when (row) {
            is PreviewRow.Item -> "${row.itemId}" + if (row.mark == RowMark.NONE) "" else " ${row.mark.name.lowercase()}" + (row.movedTo?.let { "→$it" } ?: "")
            is PreviewRow.Meal -> row.slot.kind.name.lowercase() + if (row.mark == RowMark.NONE) "" else " ${row.mark.name.lowercase()}"
            is PreviewRow.Option -> "option ${row.index}: ${row.itemId}"
        }
    }

    @Test
    fun rows_withoutProposalAreThePlan() {
        assertEquals(listOf("111", "109", "lunch", "102", "103", "105", "113", "101", "104"), PreviewRows.of(plan, null, sat, bundle).text())
        assertEquals(listOf(sat), PreviewRows.days(plan, null))
    }

    @Test
    fun remove_isAChangeWithMarkedRowsAndPill() {
        val routed = AssistantRouter.route("more bbq", ctx())
        val proposal = PlanProposals.fromTurn(routed.turn, ctx())!!
        assertEquals(ProposalKind.CHANGE, proposal.kind)
        assertEquals(sat, proposal.day)
        assertEquals("−1 item, +1 item, −1 lunch break", proposal.pill)
        assertEquals(routed.actions.single(), proposal.first.action)
        assertEquals(
            listOf("111", "109 removed", "lunch removed", "106 added", "102", "103", "105", "113", "101", "104"),
            PreviewRows.of(plan, proposal, sat, bundle).text(),
        )
    }

    @Test
    fun add_isOneSuggestedRowWhereItWouldGo() {
        val proposal = PlanProposals.fromAction(AssistantAction.planEdit(listOf(PlanOp.Add(110)), bundle), ctx())!!
        assertEquals(ProposalKind.ADD, proposal.kind)
        assertEquals(1, proposal.options.size)
        assertEquals(110, proposal.first.itemId)
        assertEquals("You added this", proposal.first.reason)
        assertEquals("−1 item, +1 item", proposal.pill)
        assertEquals(
            listOf("111", "109", "lunch", "102", "103", "option 0: 110", "105", "113", "101", "104"),
            PreviewRows.of(plan, proposal, sat, bundle).text(),
        )
    }

    @Test
    fun add_pickChipsAreUpToFourSuggestedRowsInTimeOrder() {
        val chips = listOf(108, 112, 107, 114, 106).map { id ->
            com.arcu.attnd.domain.Chip("$id", AssistantAction.planEdit(listOf(PlanOp.Add(id)), bundle))
        }
        val turn = com.arcu.attnd.domain.AssistantTurn(listOf(AssistantBlock.Text("Which one do you mean?"), AssistantBlock.Chips(chips)))
        val proposal = PlanProposals.fromTurn(turn, ctx())!!
        assertEquals(ProposalKind.ADD, proposal.kind)
        assertEquals(listOf(108, 112, 107, 114), proposal.options.map { it.itemId })
        assertEquals("Pick one of 4 to add", proposal.pill)
        assertEquals(
            listOf("111", "109", "option 0: 108", "lunch", "102", "option 2: 107", "103", "105", "option 1: 112", "113", "101", "option 3: 114", "104"),
            PreviewRows.of(plan, proposal, sat, bundle).text(),
        )
    }

    @Test
    fun replace_crossesOutTheRowWithOptionsUnderIt() {
        val proposal = PlanProposals.fromAction(AssistantAction.planEdit(listOf(PlanOp.Replace(103, with = 107)), bundle), ctx())!!
        assertEquals(ProposalKind.REPLACE, proposal.kind)
        assertEquals(103, proposal.target)
        assertEquals(107, proposal.first.itemId)
        assertTrue(proposal.options.size in 1..PlanProposals.MAX_OPTIONS)
        assertTrue(proposal.options.none { it.itemId in plan.itemIds })
        val rows = PreviewRows.of(plan, proposal, sat, bundle).text()
        val at = rows.indexOf("103 replaced")
        assertEquals(listOf("111", "109", "lunch", "102", "103 replaced"), rows.subList(0, at + 1))
        assertEquals(proposal.options.indices.map { "option $it: ${proposal.options[it].itemId}" }, rows.subList(at + 1, at + 1 + proposal.options.size))
        // Every option is its own replace(103, with = option).
        proposal.options.forEach { option ->
            assertEquals(listOf(PlanOp.Replace(103, with = option.itemId)), option.action!!.ops(bundle, PlannerFixture.now))
            assertTrue(option.itemId!! in option.edit.after.itemIds)
        }
    }

    @Test
    fun constraintsAndVersionsAreChanges() {
        val ate = PlanProposals.fromTurn(AssistantRouter.route("I already ate", ctx()).turn, ctx())!!
        assertEquals(ProposalKind.CHANGE, ate.kind)
        assertEquals("−1 lunch break", ate.pill)
        assertEquals(listOf("111", "109", "lunch removed", "102", "103", "105", "113", "101", "104"), PreviewRows.of(plan, ate, sat, bundle).text())

        val done = PlanProposals.fromTurn(AssistantRouter.route("done by 9", ctx()).turn, ctx())!!
        assertEquals("−1 item", done.pill)

        val chill = PlanProposals.fromAction(AssistantAction.planVersion(PlanVersionKind.CHILL), ctx())!!
        assertEquals(ProposalKind.CHANGE, chill.kind)
        assertTrue(chill.summary.startsWith("Chill: "))
    }

    @Test
    fun noPlanNoEditNoProposal() {
        assertNull(PlanProposals.fromTurn(AssistantRouter.route("more bbq", ctx(plan = null)).turn, ctx(plan = null)))
        assertNull(PlanProposals.fromTurn(AssistantRouter.route("tacos", ctx()).turn, ctx()))
        assertNull(PlanProposals.fromAction(AssistantAction(ActionName.PLAN), ctx()))
    }

    @Test
    fun movedRowsGetABadge() {
        val diff = PlanDiff(moved = listOf(105 to 112))
        assertEquals("1 moved", ProposalText.diff(diff))
        assertEquals("No change", ProposalText.diff(PlanDiff()))
        val meals = PlanDiff(
            removed = listOf(1, 2),
            mealsAdded = listOf(MealSlot(sat, MealKind.LUNCH)),
            mealsMoved = listOf(MealSlot(sat, MealKind.DINNER)),
        )
        assertEquals("−2 items, +1 lunch break, dinner break moved", ProposalText.diff(meals))
    }

    @Test
    fun shared_useAndMerge() {
        val use = com.arcu.attnd.domain.SharedPlans.use(listOf(109, 103), sat, bundle, PlannerFixture.hearts, PlannerFixture.now)!!
        val proposal = PlanProposals.shared(null, use, null, bundle)
        assertEquals(ProposalKind.SHARED, proposal.kind)
        assertEquals(listOf("Use this plan"), proposal.options.map { it.label })
        assertEquals(listOf("109 added", "103 added"), PreviewRows.of(null, proposal, sat, bundle).text())
        assertEquals("Shared plan · +2 items", proposal.pill)
    }

    // ── Replies ─────────────────────────────────────────────────────────────

    @Test
    fun replies() {
        fun parse(text: String, n: Int = 3) = ProposalReply.parse(text, n)
        assertEquals(ProposalReply.Apply(0), parse("do it"))
        assertEquals(ProposalReply.Apply(0), parse("Yes!"))
        assertEquals(ProposalReply.Apply(0), parse("ok"))
        assertEquals(ProposalReply.Apply(1), parse("the second one"))
        assertEquals(ProposalReply.Apply(1), parse("2nd"))
        assertEquals(ProposalReply.Apply(2), parse("option 3"))
        assertEquals(ProposalReply.Apply(2), parse("#3"))
        assertEquals(ProposalReply.Apply(0), parse("1"))
        assertEquals(ProposalReply.Apply(2), parse("the last one"))
        assertEquals(ProposalReply.Cancel, parse("no"))
        assertEquals(ProposalReply.Cancel, parse("Cancel"))
        assertEquals(ProposalReply.Cancel, parse("never mind"))
        assertEquals(ProposalReply.Invalid("There are only 3 options."), parse("option 4"))
        assertEquals(ProposalReply.Invalid("There's only one suggestion."), parse("the second one", 1))
        assertEquals(ProposalReply.None, parse("tacos"))
        assertEquals(ProposalReply.None, parse("remove juniper lane"))
        assertEquals(ProposalReply.None, parse("do it", 0))
        assertEquals(ProposalReply.None, parse("   "))
    }

    // ── Without on-device AI ────────────────────────────────────────────────

    @Test
    fun withoutModel_needsAiNoteAndSearchFallback() {
        val c = ctx()
        val question = AssistantRouter.route("where can I get tacos?", c)
        val turn = PlanTabFlow.withoutModel(question, "where can I get tacos?", c)
        assertEquals("${PlanTabFlow.NEEDS_AI}. Here's what matches.", (turn.blocks.first() as AssistantBlock.Text).text)

        val free = "zzqx tacos please kindly"
        val noMatch = AssistantRouter.route(free, c)
        assertEquals(com.arcu.attnd.domain.AssistantIntent.NO_MATCH, noMatch.intent)
        val fallback = PlanTabFlow.withoutModel(noMatch, free, c)
        assertEquals("${PlanTabFlow.NEEDS_AI}. Here's what matches.", (fallback.blocks.first() as AssistantBlock.Text).text)
        assertTrue(fallback.blocks.size > 1)

        val nothing = PlanTabFlow.withoutModel(AssistantRouter.route("zzqx", c), "zzqx", c)
        assertEquals("${PlanTabFlow.NEEDS_AI}.", (nothing.blocks.first() as AssistantBlock.Text).text)
        assertTrue(nothing.blocks.last() is AssistantBlock.Chips)

        // With the model the router's turn is unchanged.
        assertEquals(question.turn, PlanTabFlow.withoutModel(question, "where can I get tacos?", c.copy(modelAvailable = true)))
    }

    // ── Swap and sequences (iOS `PlanProposalTests` / `ConversationContextTests`) ──

    @Test
    fun swapTheNextThing() {
        val swap = PlanCommands.swap("swap the next thing", ctx()) as SwapCommand.Proposal
        assertEquals(ProposalKind.REPLACE, swap.proposal.kind)
        assertEquals(109, swap.proposal.target)
        assertTrue(swap.proposal.options.isNotEmpty())
        assertTrue(swap.proposal.options.size <= 4)
        assertNull("with / for is the router's", PlanCommands.swap("swap Juniper Lane for Kolache masterclass", ctx()))
        assertEquals(SwapCommand.Message(AssistantRouter.NO_PLAN_TEXT), PlanCommands.swap("swap the next thing", ctx(plan = null)))
    }

    @Test
    fun repliesAsIos() {
        assertEquals(ProposalReply.Apply(0), ProposalReply.parse("Yes!", 3))
        assertEquals(ProposalReply.Apply(0), ProposalReply.parse("go with the first", 2))
        assertEquals(ProposalReply.Apply(3), ProposalReply.parse("the last one", 4))
        assertEquals(ProposalReply.Cancel, ProposalReply.parse("Never mind", 2))
        assertEquals(ProposalReply.None, ProposalReply.parse("add tacos", 2))
    }

    @Test
    fun sequenceBecomesOneProposal() {
        val sentence = "Let's do music, food, then more music"
        assertEquals(listOf(SequenceKind.MUSIC, SequenceKind.FOOD, SequenceKind.MUSIC), PlanCommands.sequenceSteps(sentence))
        assertEquals(listOf(SequenceKind.FOOD, SequenceKind.MUSIC), PlanCommands.sequenceSteps("tacos then a band"))
        assertEquals(listOf(SequenceKind.MUSIC, SequenceKind.FOOD, SequenceKind.DRINKS), PlanCommands.sequenceSteps("morning music, lunch, drinks"))
        // Not sequences: no "then" / comma, one step, a name inside a step.
        assertNull(PlanCommands.sequenceSteps("music and food"))
        assertNull(PlanCommands.sequenceSteps("music"))
        assertNull(PlanCommands.sequenceSteps("Juniper Lane then tacos"))
        assertNull(PlanCommands.sequenceSteps("what's on, and where is food"))

        // Sat 10:00, no plan: Juniper Lane (11:00, Main Stage), Taco Row (nearest food) at 12:15 for 45 min,
        // then Ana Morales Trio (13:00).
        val proposal = PlanCommands.sequence(sentence, ctx(hearts = Hearts.NONE, plan = null))!!
        assertEquals(ProposalKind.SEQUENCE, proposal.kind)
        assertEquals(1, proposal.options.size)
        val after = proposal.first.edit.after
        assertEquals(listOf("11:00 109", "12:15 lunch", "13:00 102"), AssistantFixture.clock(after))
        assertEquals(6, after.days.first().entries.filterIsInstance<MealBreak>().first().locationId)
        assertTrue(proposal.summary.contains("Taco Row"))
        // With a plan: its entries stay, the new ones are added.
        val merged = PlanCommands.sequence(sentence, ctx())!!.first.edit.after
        assertTrue(merged.itemIds.containsAll(plan.itemIds))
    }

    // ── One list (Nov 22, 2026): every proposal previews, then applies as one Undo step ──

    private class Memory : com.arcu.attnd.data.ScheduleStore.Storage {
        val texts = mutableMapOf<String, String>()
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
        override fun legacyPlan(eventId: Int): String? = null
        override fun legacyPlanHistory(eventId: Int): String? = null
        override fun deleteLegacyPlan(eventId: Int) = Unit
    }

    @Test
    fun everyProposalKindPreviewsThenAppliesAsOneUndoStep() {
        val eventId = bundle.event.id
        val controller = com.arcu.attnd.data.ScheduleController(com.arcu.attnd.data.ScheduleStore(Memory()), { bundle }, { _, _, _ -> })
        listOf(101, 114, 109).forEach { controller.setLiked(eventId, com.arcu.attnd.domain.SearchKind.SCHEDULE, it, true, countUsage = false) }
        val start = controller.history(eventId).snapshots.size
        fun context() = com.arcu.attnd.domain.AssistantContext(bundle, controller.hearts(eventId), controller.dayPlan(eventId), PlannerFixture.now,
            schedule = controller.schedule(eventId), history = controller.history(eventId))
        val day = context().plan!!
        assertEquals(setOf(101, 109, 114), day.itemIds.toSet())

        val newPlan = com.arcu.attnd.domain.Planner.Companion.run {
            val (planned, preview) = propose(controller.schedule(eventId), controller.preferences(eventId), bundle, PlannerFixture.now, AssistantFixture.musicAndBbq)
            PlanProposals.newPlan(day, planned, preview, bundle)
        }
        assertEquals(ProposalKind.PLAN, newPlan.kind)
        assertTrue(newPlan.summary.startsWith("Add "))
        val proposals = listOf(
            newPlan,
            PlanProposals.fromAction(AssistantAction.planEdit(listOf(PlanOp.Add(110)), bundle), context())!!,
            PlanProposals.fromAction(AssistantAction(ActionName.PLAN_VERSION, kotlinx.serialization.json.JsonObject(mapOf("kind" to kotlinx.serialization.json.JsonPrimitive("everything")))), context())!!,
            PlanCommands.sequence("Let's do music, food, then more music", context())!!,
        )
        // Previewing changes nothing.
        val before = controller.schedule(eventId)
        assertEquals(before, controller.schedule(eventId))
        assertEquals(start, controller.history(eventId).snapshots.size)

        // Apply: one step each; Undo pops exactly that step.
        for (proposal in proposals) {
            val previous = controller.schedule(eventId)
            val steps = controller.history(eventId).snapshots.size
            val applied = controller.applyPlan(eventId, proposal.first.edit.after, explicit = proposal.first.edit.after.constraints.excluded)
            if (applied.isEmpty) continue
            assertEquals(steps + 1, controller.history(eventId).snapshots.size)
            // The person's own entries stay.
            assertTrue(controller.schedule(eventId).itemIds.containsAll(setOf(101, 109)))
            assertTrue(controller.undo(eventId))
            assertEquals(previous, controller.schedule(eventId))
        }
        // An explicit removal ("drop Juniper Lane") takes the person's own entry.
        val drop = PlanProposals.fromAction(AssistantAction.planEdit(listOf(PlanOp.Remove(109)), bundle), context())!!
        controller.applyPlan(eventId, drop.first.edit.after, explicit = drop.first.edit.after.constraints.excluded)
        assertTrue(109 !in controller.schedule(eventId).itemIds)
    }
}
