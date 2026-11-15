package com.arcu.attnd.domain

import java.time.Instant
import java.time.LocalDate

// ── Tabs (plan tab spec: My plan + Explore + Flyer + Updates) ──────────────────

/**
 * One navigation item. [PLAN] and [EXPLORE] are the plan-first layout; [MAP], [LOCATIONS] and
 * [SCHEDULE] are only used in the legacy layout (store screenshots keep today's tabs). [tab] is the
 * organizer's tab a direct item shows (null for My plan and Explore).
 */
enum class NavItem(val key: String, val tab: Tab?) {
    PLAN("plan", null),
    EXPLORE("explore", null),
    MAP("map", Tab.MAP),
    LOCATIONS("locations", Tab.LOCATIONS),
    SCHEDULE("schedule", Tab.SCHEDULE),
    FLYER("flyer", Tab.FLYER),
    UPDATES("notifications", Tab.NOTIFICATIONS);

    companion object {
        fun direct(tab: Tab): NavItem = when (tab) {
            Tab.MAP -> MAP
            Tab.LOCATIONS -> LOCATIONS
            Tab.SCHEDULE -> SCHEDULE
            Tab.FLYER -> FLYER
            Tab.NOTIFICATIONS -> UPDATES
        }
    }
}

/**
 * The event's navigation: [items] in order, the views of Explore's switch ([exploreViews], always in
 * the order Map | Schedule | Places, only those `display.tabs` lists), the item selected at first
 * and Explore's first view.
 */
data class EventTabs(
    val items: List<NavItem>,
    val exploreViews: List<Tab>,
    val initialItem: NavItem,
    val initialExploreView: Tab?,
) {
    /** Explore has more than one view: show the switch. */
    val hasSwitch: Boolean get() = exploreViews.size > 1

    /**
     * Where [tab]'s screen lives: Explore for a map / schedule / places view it holds, else the tab's
     * own item; null when the event doesn't show it.
     */
    fun itemFor(tab: Tab): NavItem? = when {
        tab in exploreViews && NavItem.EXPLORE in items -> NavItem.EXPLORE
        NavItem.direct(tab) in items -> NavItem.direct(tab)
        else -> null
    }

    /** The organizer tab whose screen [item] shows (Explore: [exploreView]); null for My plan. */
    fun contentTab(item: NavItem, exploreView: Tab?): Tab? = when (item) {
        NavItem.PLAN -> null
        NavItem.EXPLORE -> exploreView?.takeIf { it in exploreViews } ?: initialExploreView
        else -> item.tab
    }

    /** [view] if Explore holds it, else Explore's first view. */
    fun exploreView(view: Tab?): Tab? = view?.takeIf { it in exploreViews } ?: initialExploreView
}

/**
 * The plan-first tab model (client side only, no API change):
 * - **My plan** first and the default, whatever `display.default_tab` says.
 * - **Explore** next: the map / schedule / places views `display.tabs` lists, in the switch order
 *   Map | Schedule | Places (one view → no switch; none → no Explore). Its first view is
 *   `display.default_tab` when that is one of them, else the first one `display.tabs` lists.
 * - Then Flyer and Updates in the listed order (at most 4 items, under [DisplayConfig.MAX_TABS]).
 * - [legacy] (store screenshot mode) keeps the organizer's tabs and default as they are.
 */
object EventTabModel {
    val EXPLORE_ORDER = listOf(Tab.MAP, Tab.SCHEDULE, Tab.LOCATIONS)

    fun of(display: DisplayConfig, legacy: Boolean = false): EventTabs = of(display.tabs, display.defaultTab, legacy)

    fun of(tabs: List<Tab>, defaultTab: Tab?, legacy: Boolean = false): EventTabs {
        if (legacy) {
            val items = tabs.map(NavItem::direct)
            val initial = defaultTab?.takeIf { it in tabs }?.let(NavItem::direct) ?: items.firstOrNull() ?: NavItem.MAP
            return EventTabs(items, emptyList(), initial, null)
        }
        val views = EXPLORE_ORDER.filter { it in tabs }
        val items = buildList {
            add(NavItem.PLAN)
            if (views.isNotEmpty()) add(NavItem.EXPLORE)
            tabs.filter { it == Tab.FLYER || it == Tab.NOTIFICATIONS }.forEach { add(NavItem.direct(it)) }
        }
        val first = defaultTab?.takeIf { it in views } ?: tabs.firstOrNull { it in views }
        return EventTabs(items, views, NavItem.PLAN, first)
    }

    /** The `tab_view` name for what is shown (Explore sends its view's tab name; My plan sends nothing). */
    fun usageName(item: NavItem, exploreView: Tab?): String? = when (item) {
        NavItem.PLAN -> null
        NavItem.EXPLORE -> exploreView?.key
        else -> item.tab?.key
    }

    /** The switch's label for an Explore view. */
    fun viewLabel(tab: Tab): String = when (tab) {
        Tab.MAP -> "Map"
        Tab.SCHEDULE -> "Schedule"
        Tab.LOCATIONS -> "Places"
        Tab.FLYER -> "Flyer"
        Tab.NOTIFICATIONS -> "Updates"
    }
}

// ── Proposals (edits preview in place) ──────────────────────────────────────

enum class ProposalKind {
    /** Dashed "suggested" rows where they would go (up to [PlanProposals.MAX_OPTIONS]). */
    ADD,

    /** The current row crossed out, with option rows under it. */
    REPLACE,

    /** Remove / move / constraints / versions: the affected rows marked, plus the diff pill. */
    CHANGE,

    /** A shared plan link (API-015): Use this plan / Merge with mine. */
    SHARED,
}

/**
 * One way to apply a proposal: [action] (a `plan_edit` / `plan_version`, applied through the
 * dispatcher with Undo) and its previewed [edit]. [itemId] is the suggested item of an add / replace
 * option, [reason] the planner's reason for it.
 */
data class ProposalOption(
    val label: String,
    val action: AssistantAction?,
    val edit: PlanEdit,
    val itemId: Int? = null,
    val reason: String? = null,
)

/** A plan edit shown in the timeline before it is applied. */
data class PlanProposal(
    val kind: ProposalKind,
    val options: List<ProposalOption>,
    /** REPLACE: the planned item that would go. */
    val target: Int? = null,
    /** The plan day the preview is about (the timeline switches to it). */
    val day: LocalDate? = null,
    /** The pill above the input bar ("−2 items, +1 lunch break"). */
    val pill: String,
    /** The describe text ("Removed A. Added B."), for accessibility and the log. */
    val summary: String,
) {
    val first: ProposalOption get() = options.first()
}

/**
 * Builds proposals from what a turn wants to change (router, model, chips, live hints, shared links).
 * Rules (iOS implements the same):
 * - `plan_version` → CHANGE with that version's preview.
 * - `plan_edit` of a single `add` of an item not in the plan → ADD with that one option.
 * - `plan_edit` of a single `replace` of a planned item → REPLACE: the edit's own replacement first,
 *   then the item's Swap alternatives ([PlannedItem.alternatives]) not in the plan, at most
 *   [MAX_OPTIONS], each previewed as `replace(item, with = option)`. No replacement and no
 *   alternatives → CHANGE (the removal).
 * - every other `plan_edit` → CHANGE.
 * - a turn without such an action whose chips (the "Which one do you mean?" pick) are all single
 *   `add`s → ADD with one option per chip (≤ [MAX_OPTIONS]); all single `replace`s of the same item →
 *   REPLACE with one option per chip.
 * - No plan → no proposal (the turn answers "You don't have a plan yet").
 */
object PlanProposals {
    const val MAX_OPTIONS = 4

    fun isEdit(action: AssistantAction): Boolean = action.name == ActionName.PLAN_EDIT || action.name == ActionName.PLAN_VERSION

    fun fromTurn(turn: AssistantTurn, ctx: AssistantContext): PlanProposal? {
        turn.actions.firstOrNull(::isEdit)?.let { return fromAction(it, ctx) }
        val chips = turn.blocks.filterIsInstance<AssistantBlock.Chips>().flatMap { it.chips }
        if (chips.isEmpty() || !chips.all { it.action.name == ActionName.PLAN_EDIT }) return null
        val plan = ctx.plan ?: return null
        val ops = chips.map { it.action.ops(ctx.bundle, ctx.now)?.singleOrNull() ?: return null }
        if (ops.all { it is PlanOp.Add && it.item !in plan.itemIds }) {
            val options = ops.map { it as PlanOp.Add }.distinctBy { it.item }.take(MAX_OPTIONS)
                .map { op -> option(plan, AssistantAction.planEdit(listOf(op), ctx.bundle), listOf(op), op.item, ctx) }
            return add(plan, options, ctx)
        }
        val replaces = ops.filterIsInstance<PlanOp.Replace>()
        if (replaces.size == ops.size && replaces.map { it.item }.distinct().size == 1 && replaces.all { it.with != null }) {
            val target = replaces.first().item
            val options = replaces.distinctBy { it.with }.take(MAX_OPTIONS)
                .map { op -> option(plan, AssistantAction.planEdit(listOf(op), ctx.bundle), listOf(op), op.with, ctx) }
            return replace(plan, target, options, ctx)
        }
        return null
    }

    fun fromAction(action: AssistantAction, ctx: AssistantContext): PlanProposal? {
        val plan = ctx.plan ?: return null
        val bundle = ctx.bundle
        return when (action.name) {
            ActionName.PLAN_VERSION -> {
                val kind = PlanVersionKind.of(action.string("kind")) ?: return null
                val edit = PlanVersions.make(kind, plan, bundle, ctx.hearts, ctx.now)
                change(listOf(ProposalOption("${kind.label} version", action, edit)), ctx, prefix = "${kind.label}: ")
            }
            ActionName.PLAN_EDIT -> {
                val ops = action.ops(bundle, ctx.now) ?: return null
                val single = ops.singleOrNull()
                when {
                    single is PlanOp.Add && single.item !in plan.itemIds ->
                        add(plan, listOf(option(plan, action, ops, single.item, ctx)), ctx)
                    single is PlanOp.Replace && single.item in plan.itemIds -> {
                        val own = PlanEditor.preview(plan, ops, bundle, ctx.hearts, ctx.now)
                        val chosen = own.diff.added.firstOrNull()
                        val alternatives = plan.items.firstOrNull { it.itemId == single.item }?.alternatives.orEmpty()
                        val ids = (listOfNotNull(chosen) + alternatives).distinct()
                            .filter { it != single.item && it !in plan.itemIds && bundle.scheduleItem(it) != null }
                            .take(MAX_OPTIONS)
                        if (ids.isEmpty()) return change(listOf(ProposalOption(describe(own, bundle), action, own)), ctx)
                        val options = ids.map { id ->
                            if (id == chosen) option(plan, action, ops, id, ctx, own)
                            else {
                                val op = PlanOp.Replace(single.item, with = id)
                                option(plan, AssistantAction.planEdit(listOf(op), bundle), listOf(op), id, ctx)
                            }
                        }
                        replace(plan, single.item, options, ctx)
                    }
                    else -> {
                        val edit = PlanEditor.preview(plan, ops, bundle, ctx.hearts, ctx.now)
                        change(listOf(ProposalOption(describe(edit, bundle), action, edit)), ctx)
                    }
                }
            }
            else -> null
        }
    }

    /** A shared plan (API-015): option 0 = "Use this plan", option 1 (when given) = "Merge with mine". */
    fun shared(current: Plan?, use: Plan, merge: Plan?, bundle: EventBundle): PlanProposal {
        val before = current ?: use.copy(days = emptyList(), fingerprints = emptyMap())
        fun edit(after: Plan) = PlanEdit(before, after, PlanDiff.between(before, after, bundle), emptyList())
        val options = listOfNotNull(
            ProposalOption("Use this plan", null, edit(use)),
            merge?.let { ProposalOption("Merge with mine", null, edit(it)) },
        )
        val edit = options.first().edit
        return PlanProposal(
            ProposalKind.SHARED, options, day = affectedDay(edit, bundle),
            pill = "Shared plan · " + ProposalText.diff(edit.diff),
            summary = "Someone shared a plan with ${use.itemIds.size} items.",
        )
    }

    private fun option(plan: Plan, action: AssistantAction, ops: List<PlanOp>, itemId: Int?, ctx: AssistantContext, edit: PlanEdit? = null): ProposalOption {
        val preview = edit ?: PlanEditor.preview(plan, ops, ctx.bundle, ctx.hearts, ctx.now)
        val reason = itemId?.let { id -> preview.after.items.firstOrNull { it.itemId == id }?.reason }
        return ProposalOption(itemId?.let { ctx.bundle.scheduleItem(it)?.name } ?: describe(preview, ctx.bundle), action, preview, itemId, reason)
    }

    private fun add(plan: Plan, options: List<ProposalOption>, ctx: AssistantContext): PlanProposal {
        val bundle = ctx.bundle
        val day = options.firstNotNullOfOrNull { o -> o.itemId?.let { bundle.scheduleItem(it)?.startTime }?.let { EventTime.localDate(it, bundle.event.zone) } }
        return PlanProposal(
            ProposalKind.ADD, options, day = day ?: plan.days.firstOrNull()?.date,
            pill = if (options.size == 1) ProposalText.diff(options.single().edit.diff) else "Pick one of ${options.size} to add",
            summary = if (options.size == 1) describe(options.single().edit, bundle) else "Suggestions: " + options.joinToString(", ") { it.label },
        )
    }

    private fun replace(plan: Plan, target: Int, options: List<ProposalOption>, ctx: AssistantContext): PlanProposal {
        val bundle = ctx.bundle
        val name = bundle.scheduleItem(target)?.name ?: "this item"
        return PlanProposal(
            ProposalKind.REPLACE, options, target = target,
            day = plan.days.firstOrNull { d -> d.items.any { it.itemId == target } }?.date,
            pill = if (options.size == 1) "Swap $name for ${options.single().label}" else "Pick a replacement for $name",
            summary = "Instead of $name: " + options.joinToString(", ") { it.label },
        )
    }

    private fun change(options: List<ProposalOption>, ctx: AssistantContext, prefix: String = ""): PlanProposal {
        val edit = options.first().edit
        return PlanProposal(
            ProposalKind.CHANGE, options, day = affectedDay(edit, ctx.bundle),
            pill = ProposalText.diff(edit.diff),
            summary = prefix + describe(edit, ctx.bundle),
        )
    }

    private fun describe(edit: PlanEdit, bundle: EventBundle) = AssistantRouter.describe(edit, bundle)

    /** The first plan day the edit touches (before or after), else the first day of the plan. */
    fun affectedDay(edit: PlanEdit, bundle: EventBundle): LocalDate? {
        val diff = edit.diff
        val ids = diff.removed + diff.added + diff.moved.flatMap { listOf(it.first, it.second) }
        val days = (edit.before.days + edit.after.days)
        val byItem = days.filter { d -> d.items.any { it.itemId in ids } }.map { it.date }
        val byMeal = (diff.mealsRemoved + diff.mealsAdded + diff.mealsMoved).map { it.date }
        return (byItem + byMeal).minOrNull() ?: edit.after.days.firstOrNull()?.date ?: edit.before.days.firstOrNull()?.date
            ?: ids.firstNotNullOfOrNull { bundle.scheduleItem(it)?.startTime }?.let { EventTime.localDate(it, bundle.event.zone) }
    }
}

/** How a timeline row is shown while a proposal is previewed. */
enum class RowMark { NONE, REMOVED, MOVED, REPLACED, ADDED }

/** One row of the My plan timeline (with a proposal's preview merged in). */
sealed interface PreviewRow {
    val start: Instant

    /** A planned item; [movedTo] = the other showing a MOVED row moves to. */
    data class Item(val itemId: Int, override val start: Instant, val mark: RowMark = RowMark.NONE, val movedTo: Int? = null) : PreviewRow

    data class Meal(val slot: MealSlot, override val start: Instant, val locationId: Int?, val mark: RowMark = RowMark.NONE) : PreviewRow

    /** A dashed suggested row: option [index] of the proposal (tap to apply). */
    data class Option(val index: Int, val itemId: Int, override val start: Instant, val reason: String?) : PreviewRow
}

/**
 * The rows of one plan day with a proposal merged in (pure, unit tested):
 * - No proposal: the day's entries as they are.
 * - ADD: each option's item as an [PreviewRow.Option], placed by start time (before the first row
 *   that starts later; after rows starting at the same time; options among themselves by start, id).
 * - REPLACE: the target row marked REPLACED, its options right under it in option order.
 * - CHANGE / SHARED (option 0's edit): rows of the day before the edit, removed items REMOVED, moved
 *   items MOVED (with [PreviewRow.Item.movedTo]), removed / moved meals REMOVED / MOVED; items the
 *   edit adds and meal breaks it adds, from the plan after the edit, inserted by start time as ADDED.
 * Options whose item doesn't start on [date] aren't shown on that day.
 */
object PreviewRows {

    fun of(plan: Plan?, proposal: PlanProposal?, date: LocalDate, bundle: EventBundle): List<PreviewRow> {
        val zone = bundle.event.zone
        val edit = proposal?.first?.edit
        val basePlan = if (proposal?.kind == ProposalKind.CHANGE || proposal?.kind == ProposalKind.SHARED) edit!!.before else plan
        val day = basePlan?.days?.firstOrNull { it.date == date }
        val diff = edit?.diff
        val changing = proposal?.kind == ProposalKind.CHANGE || proposal?.kind == ProposalKind.SHARED
        val base = day?.entries.orEmpty().map { entry ->
            when (entry) {
                is PlannedItem -> {
                    val moved = if (changing) diff!!.moved.firstOrNull { it.first == entry.itemId }?.second else null
                    val mark = when {
                        proposal?.kind == ProposalKind.REPLACE && entry.itemId == proposal.target -> RowMark.REPLACED
                        !changing -> RowMark.NONE
                        moved != null -> RowMark.MOVED
                        entry.itemId in diff!!.removed -> RowMark.REMOVED
                        else -> RowMark.NONE
                    }
                    PreviewRow.Item(entry.itemId, entry.start, mark, moved)
                }
                is MealBreak -> {
                    val slot = MealSlot(date, entry.kind)
                    val mark = when {
                        !changing -> RowMark.NONE
                        slot in diff!!.mealsMoved -> RowMark.MOVED
                        slot in diff.mealsRemoved -> RowMark.REMOVED
                        else -> RowMark.NONE
                    }
                    PreviewRow.Meal(slot, entry.start, entry.locationId, mark)
                }
            }
        }
        if (proposal == null) return base
        return when (proposal.kind) {
            ProposalKind.ADD -> {
                val options = proposal.options.mapIndexedNotNull { index, option ->
                    val id = option.itemId ?: return@mapIndexedNotNull null
                    val start = bundle.scheduleItem(id)?.startTime?.toInstant() ?: return@mapIndexedNotNull null
                    if (start.atZone(zone).toLocalDate() != date) return@mapIndexedNotNull null
                    PreviewRow.Option(index, id, start, option.reason)
                }.sortedWith(compareBy<PreviewRow.Option> { it.start }.thenBy { it.itemId })
                insert(base, options)
            }
            ProposalKind.REPLACE -> {
                val at = base.indexOfFirst { it is PreviewRow.Item && it.itemId == proposal.target }
                if (at < 0) return base
                val options = proposal.options.mapIndexedNotNull { index, option ->
                    val id = option.itemId ?: return@mapIndexedNotNull null
                    val start = bundle.scheduleItem(id)?.startTime?.toInstant() ?: return@mapIndexedNotNull null
                    PreviewRow.Option(index, id, start, option.reason)
                }
                base.subList(0, at + 1) + options + base.subList(at + 1, base.size)
            }
            ProposalKind.CHANGE, ProposalKind.SHARED -> {
                val after = edit!!.after.days.firstOrNull { it.date == date }
                val added = after?.entries.orEmpty().mapNotNull { entry ->
                    when (entry) {
                        is PlannedItem -> if (entry.itemId in diff!!.added) PreviewRow.Item(entry.itemId, entry.start, RowMark.ADDED) else null
                        is MealBreak -> {
                            val slot = MealSlot(date, entry.kind)
                            if (slot in diff!!.mealsAdded) PreviewRow.Meal(slot, entry.start, entry.locationId, RowMark.ADDED) else null
                        }
                    }
                }
                insert(base, added)
            }
        }
    }

    /** The days the timeline's day switcher offers: the plan's days plus the proposal's added days. */
    fun days(plan: Plan?, proposal: PlanProposal?): List<LocalDate> {
        val own = plan?.days?.filter { it.entries.isNotEmpty() }?.map { it.date }.orEmpty()
        val proposed = proposal?.first?.edit?.after?.days?.filter { it.entries.isNotEmpty() }?.map { it.date }.orEmpty()
        return (own + proposed + listOfNotNull(proposal?.day)).distinct().sorted()
    }

    private fun insert(base: List<PreviewRow>, extra: List<PreviewRow>): List<PreviewRow> {
        val rows = base.toMutableList()
        for (row in extra) {
            val at = rows.indexOfFirst { it.start.isAfter(row.start) }.let { if (it < 0) rows.size else it }
            rows.add(at, row)
        }
        return rows
    }
}

/** The diff pill's text and the Undo pill's label. */
object ProposalText {

    /** "−2 items, +1 lunch break" (items removed, added, moved; meal breaks removed, added, moved); "No change". */
    fun diff(diff: PlanDiff): String {
        fun items(n: Int) = if (n == 1) "1 item" else "$n items"
        val parts = buildList {
            if (diff.removed.isNotEmpty()) add("−" + items(diff.removed.size))
            if (diff.added.isNotEmpty()) add("+" + items(diff.added.size))
            if (diff.moved.isNotEmpty()) add("${diff.moved.size} moved")
            MealKind.entries.forEach { kind ->
                val label = kind.label.lowercase()
                diff.mealsRemoved.count { it.kind == kind }.takeIf { it > 0 }?.let { add("−$it $label") }
                diff.mealsAdded.count { it.kind == kind }.takeIf { it > 0 }?.let { add("+$it $label") }
                diff.mealsMoved.count { it.kind == kind }.takeIf { it > 0 }?.let { add("$label moved") }
            }
        }
        return parts.joinToString(", ").ifEmpty { "No change" }
    }
}

/** A typed reply while a proposal is shown. */
sealed interface ProposalReply {
    /** Apply option [index]. */
    data class Apply(val index: Int) : ProposalReply

    /** Clear the preview. */
    data object Cancel : ProposalReply

    /** An option number that isn't there ("option 5" of 3): [text] says so; the preview stays. */
    data class Invalid(val text: String) : ProposalReply

    /** Not a reply: a new turn (the preview is cleared). */
    data object None : ProposalReply

    companion object {
        private val APPLY = setOf(
            "do it", "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "apply", "apply it", "go ahead", "sounds good",
            "do that", "lets do it", "let s do it", "yes please", "please", "that one", "this one", "perfect", "great",
        )
        private val CANCEL = setOf(
            "no", "nope", "nah", "cancel", "never mind", "nevermind", "no thanks", "no thank you", "don t", "dont",
            "forget it", "stop", "not that", "leave it",
        )
        private val ORDINALS = mapOf("first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2, "fourth" to 3, "4th" to 3)

        /**
         * "do it" / "yes" / … → option 1; "the second one", "second", "2nd", "option 3", "number 2",
         * "#2", "2" → that option; "the last one" → the last; "no" / "cancel" / … → [Cancel]; an
         * option number above [optionCount] → [Invalid]; anything else → [None].
         */
        fun parse(text: String, optionCount: Int): ProposalReply {
            val cmd = AssistantRouter.command(text.replace("#", " number "))
            if (cmd.isEmpty() || optionCount <= 0) return None
            if (cmd in CANCEL) return Cancel
            if (cmd in APPLY) return Apply(0)
            val index = Regex("""^(?:(?:yes|ok|okay)\s+)?(?:the\s+)?(first|1st|second|2nd|third|3rd|fourth|4th|last)(?:\s+(?:one|option))?(?:\s+please)?$""").find(cmd)?.let { m ->
                val word = m.groupValues[1]
                if (word == "last") optionCount - 1 else ORDINALS.getValue(word)
            } ?: Regex("""^(?:(?:option|number|choice|no|pick)\s+)?(\d{1,2})$""").find(cmd)?.let { it.groupValues[1].toInt() - 1 }
            ?: return None
            if (index !in 0 until optionCount) {
                return Invalid(if (optionCount == 1) "There's only one suggestion." else "There are only $optionCount options.")
            }
            return Apply(index)
        }
    }
}

/**
 * The My plan tab's turn rules (pure):
 * - [NEEDS_AI]: without on-device AI, a question gets the note "Needs on-device AI. Here's what
 *   matches." instead of the router's Ask note; a request the router can't read (NO_MATCH) is searched
 *   by its keywords ([SearchQuery.keywords]) under the same note, and when that finds nothing too
 *   it keeps the router's suggestion chips under "Needs on-device AI.".
 * - [inPlace]: intents whose result is the plan itself (show, next, walking, recap) are answered by
 *   the timeline on My plan; their answer card still shows on other tabs.
 */
object PlanTabFlow {
    const val NEEDS_AI = "Needs on-device AI"
    const val PLACEHOLDER = "Change your plan or search…"

    fun withoutModel(routed: RoutedTurn, input: String, ctx: AssistantContext): AssistantTurn {
        if (ctx.modelAvailable) return routed.turn
        return when (routed.intent) {
            AssistantIntent.QUESTION -> AssistantTurn(
                routed.blocks.map { if (it is AssistantBlock.Text && it.text == AssistantRouter.ASK_UNAVAILABLE) AssistantBlock.Text("$NEEDS_AI. Here's what matches.") else it },
                routed.actions,
            )
            AssistantIntent.NO_MATCH -> {
                val keywords = SearchQuery.keywords(AssistantRouter.command(input))
                val tokens = SearchText.words(keywords)
                val results = tokens.flatMap { EventSearch.search(ctx.bundle, it, ctx.now) }.distinctBy { it.kind to it.id }
                    .sortedByDescending { it.score }
                if (results.isEmpty()) {
                    AssistantTurn(listOf(AssistantBlock.Text("$NEEDS_AI.")) + routed.blocks.filter { it !is AssistantBlock.Text }, routed.actions)
                } else {
                    AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text("$NEEDS_AI. Here's what matches.")) + AssistantRouter.searchBlocks(results, ctx), ctx.blockContext))
                }
            }
            else -> routed.turn
        }
    }

    private val IN_PLACE = setOf(AssistantIntent.PLAN_SHOW)

    fun inPlace(intent: AssistantIntent): Boolean = intent in IN_PLACE
}
