package com.arcu.attnd.unit

import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.EventTabModel
import com.arcu.attnd.domain.MealKind
import com.arcu.attnd.domain.MealSlot
import com.arcu.attnd.domain.NavItem
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
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.unit.AssistantFixture.ctx
import com.arcu.attnd.unit.AssistantFixture.plan
import com.arcu.attnd.unit.PlannerFixture.bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Plan tab spec (shared with iOS, same cases): the tab model, proposals previewed in place (rows,
 * pill), and the typed replies to a proposal. Fixture plan (Sat Nov 14):
 * 10:00 111 · 11:00 109 · 12:00 lunch · 13:00 102 · 15:00 103 · 17:00 105 · 18:15 113 · 19:00 101 · 21:00 104.
 */
class PlanTabTest {

    private val sat = LocalDate.of(2026, 11, 14)

    private fun tabs(vararg keys: String, default: String? = null, legacy: Boolean = false) = EventTabModel.of(
        DisplayConfig.resolve(
            rawTabs = keys.toList(), rawDefaultTab = default, rawLocationsStyle = null, rawScheduleStyle = null,
            mapClustering = null, mapFitAll = null, showLocationImages = null, enableSearch = null, groupByCategory = null,
            rawSecondaryPane = null, accentColor = null, logoUrl = null, hasFlyer = true,
        ),
        legacy,
    )

    // ── Tab model ───────────────────────────────────────────────────────────

    @Test
    fun tabs_planFirstThenExploreThenFlyerAndUpdates() {
        val model = tabs("map", "schedule", "flyer", "notifications")
        assertEquals(listOf(NavItem.PLAN, NavItem.EXPLORE, NavItem.FLYER, NavItem.UPDATES), model.items)
        assertEquals(listOf(Tab.MAP, Tab.SCHEDULE), model.exploreViews)
        assertEquals(NavItem.PLAN, model.initialItem)
        assertEquals(Tab.MAP, model.initialExploreView)
        assertTrue(model.hasSwitch)
    }

    @Test
    fun tabs_oneViewHasNoSwitch_noneHasNoExplore() {
        val one = tabs("schedule")
        assertEquals(listOf(NavItem.PLAN, NavItem.EXPLORE), one.items)
        assertEquals(listOf(Tab.SCHEDULE), one.exploreViews)
        assertEquals(Tab.SCHEDULE, one.initialExploreView)
        assertFalse(one.hasSwitch)

        // Nothing listed: the plan only (resolve() itself falls back to the default tabs).
        val none = EventTabModel.of(emptyList(), null)
        assertEquals(listOf(NavItem.PLAN), none.items)
        assertNull(none.initialExploreView)
        assertEquals(listOf(NavItem.PLAN, NavItem.EXPLORE, NavItem.FLYER, NavItem.UPDATES), tabs().items)
        assertEquals(listOf(Tab.MAP, Tab.SCHEDULE, Tab.LOCATIONS), tabs().exploreViews)
        assertEquals(listOf(NavItem.PLAN, NavItem.UPDATES, NavItem.FLYER), tabs("notifications", "flyer").items)
    }

    @Test
    fun tabs_switchOrderIsFixed_defaultView() {
        val model = tabs("locations", "notifications", "schedule", "map", default = "schedule")
        assertEquals(listOf(Tab.MAP, Tab.SCHEDULE, Tab.LOCATIONS), model.exploreViews)
        assertEquals(Tab.SCHEDULE, model.initialExploreView)
        // My plan stays the default whatever default_tab says.
        assertEquals(NavItem.PLAN, model.initialItem)
        // default_tab not an Explore view → the first Explore view display.tabs lists.
        assertEquals(Tab.LOCATIONS, tabs("locations", "schedule", "notifications", default = "notifications").initialExploreView)
    }

    @Test
    fun tabs_legacyKeepsTodaysTabs() {
        val model = tabs("map", "locations", "schedule", "notifications", default = "schedule", legacy = true)
        assertEquals(listOf(NavItem.MAP, NavItem.LOCATIONS, NavItem.SCHEDULE, NavItem.UPDATES), model.items)
        assertEquals(NavItem.SCHEDULE, model.initialItem)
        assertEquals(NavItem.LOCATIONS, model.itemFor(Tab.LOCATIONS))
        assertEquals(Tab.LOCATIONS, model.contentTab(NavItem.LOCATIONS, null))
    }

    @Test
    fun tabs_targetsAndUsageNames() {
        val model = tabs("map", "schedule", "notifications")
        assertEquals(NavItem.EXPLORE, model.itemFor(Tab.MAP))
        assertEquals(NavItem.EXPLORE, model.itemFor(Tab.SCHEDULE))
        assertNull(model.itemFor(Tab.LOCATIONS))
        assertNull(model.itemFor(Tab.FLYER))
        assertEquals(NavItem.UPDATES, model.itemFor(Tab.NOTIFICATIONS))
        assertEquals(Tab.SCHEDULE, model.contentTab(NavItem.EXPLORE, Tab.SCHEDULE))
        assertEquals(Tab.MAP, model.contentTab(NavItem.EXPLORE, Tab.LOCATIONS))
        assertNull(model.contentTab(NavItem.PLAN, Tab.MAP))
        assertEquals("schedule", EventTabModel.usageName(NavItem.EXPLORE, Tab.SCHEDULE))
        assertEquals("locations", EventTabModel.usageName(NavItem.EXPLORE, Tab.LOCATIONS))
        assertEquals("notifications", EventTabModel.usageName(NavItem.UPDATES, null))
        assertNull(EventTabModel.usageName(NavItem.PLAN, null))
        assertEquals("Places", EventTabModel.viewLabel(Tab.LOCATIONS))
    }

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
        val routed = AssistantRouter.route("remove juniper lane", ctx())
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
        assertNull(PlanProposals.fromTurn(AssistantRouter.route("remove juniper lane", ctx(plan = null)).turn, ctx(plan = null)))
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
}
