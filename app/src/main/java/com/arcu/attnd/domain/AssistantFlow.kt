package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Which path a typed turn takes: the input bar's intent chip ("Searching…", "Asking…", "Editing plan…"). */
enum class TurnPath(val label: String) {
    SEARCH("Searching…"),
    ASK("Asking…"),
    PLAN("Editing plan…"),
}

/**
 * How the platform runs a turn (Android's assistant screen; pure so it is unit tested):
 * - [path]: a forced path wins (ASK only with the model). Otherwise the router's intent decides: plan
 *   intents → PLAN; with the model, a question, no match or a search of 4+ words → ASK (the model
 *   reads it, the router is its fallback); everything else → SEARCH (router only: commands, likes,
 *   toggles, navigation, short searches, time words answer instantly and deterministically).
 * - Forced SEARCH runs a plain `search` of the text; forced PLAN without a plan intent makes a plan
 *   (`plan_make`) when plans can be made.
 */
object AssistantFlow {

    val PLAN_INTENTS = setOf(
        AssistantIntent.PLAN_EDIT, AssistantIntent.PLAN_UNDO, AssistantIntent.PLAN_VERSION, AssistantIntent.PLAN_COMPARE,
        AssistantIntent.PLAN_MAKE, AssistantIntent.PLAN_SHOW, AssistantIntent.PLAN_NEXT, AssistantIntent.WALKING,
        AssistantIntent.RECAP, AssistantIntent.NO_PLAN, AssistantIntent.WHY_NOT,
    )

    private val ASKED = setOf(AssistantIntent.QUESTION, AssistantIntent.NO_MATCH)

    fun path(intent: AssistantIntent, input: String, modelAvailable: Boolean, forced: TurnPath? = null): TurnPath {
        if (forced == TurnPath.ASK) return if (modelAvailable) TurnPath.ASK else TurnPath.SEARCH
        if (forced != null) return forced
        if (intent in PLAN_INTENTS) return TurnPath.PLAN
        if (modelAvailable && (intent in ASKED || (intent == AssistantIntent.SEARCH && SearchText.words(input).size >= 4))) return TurnPath.ASK
        return TurnPath.SEARCH
    }

    /** A plain `search` turn for a forced SEARCH. */
    fun searchTurn(input: String, ctx: AssistantContext): AssistantTurn {
        val blocks = AssistantRouter.renderRead(AssistantAction.search(input.trim()), ctx)
        return AssistantTurn(blocks.ifEmpty {
            BlockValidator.validate(listOf(AssistantBlock.Text(AssistantRouter.NO_MATCH_TEXT), AssistantBlock.Chips(AssistantRouter.suggestionChips(ctx))), ctx.blockContext)
        })
    }

    /** A forced PLAN turn that isn't a plan command: make a plan from the text. */
    fun planTurn(input: String, routed: RoutedTurn, ctx: AssistantContext): AssistantTurn =
        if (routed.intent in PLAN_INTENTS || !ctx.canPlan) routed.turn
        else AssistantTurn(listOf(AssistantBlock.Text("Making your plan.")), listOf(AssistantAction.planMake(input.trim())))

    /**
     * What the model's conversation remembers of an assistant turn: its text blocks (bold markers
     * dropped), else "Showed <first names>" for its entities, else the block types.
     */
    fun replyText(turn: AssistantTurn, bundle: EventBundle): String {
        val texts = turn.blocks.filterIsInstance<AssistantBlock.Text>().joinToString(" ") { it.text.replace("**", "") }
        if (texts.isNotBlank()) return texts.take(200)
        val names = turn.blocks.flatMap { refs(it) }.distinct().mapNotNull { it.name(bundle) }.take(3)
        if (names.isNotEmpty()) return "Showed " + names.joinToString(", ")
        return "Showed " + turn.blocks.joinToString(", ") { it.type }.ifEmpty { "nothing" }
    }

    /** The entities a block shows (logging counts, conversation text). */
    fun refs(block: AssistantBlock): List<EntityRef> = when (block) {
        is AssistantBlock.ItemCard -> listOf(EntityRef(RefKind.SCHEDULE, block.id))
        is AssistantBlock.PlaceCard -> listOf(EntityRef(RefKind.LOCATION, block.id))
        is AssistantBlock.UpdateCard -> listOf(EntityRef(RefKind.UPDATE, block.id))
        is AssistantBlock.ListBlock -> block.ids
        is AssistantBlock.Carousel -> block.ids
        is AssistantBlock.Timeline -> block.ids.map { EntityRef(RefKind.SCHEDULE, it) }
        is AssistantBlock.MapBlock -> block.pins.map { EntityRef(RefKind.LOCATION, it) }
        is AssistantBlock.Gallery -> block.ids.map { EntityRef(RefKind.LOCATION, it) }
        is AssistantBlock.Countdown -> listOf(EntityRef(RefKind.SCHEDULE, block.id))
        is AssistantBlock.Calendar -> block.ids.map { EntityRef(RefKind.SCHEDULE, it) }
        is AssistantBlock.PlanDiffBlock -> (block.diff.removed + block.diff.added).map { EntityRef(RefKind.SCHEDULE, it) }
        else -> emptyList()
    }

    /** "Ask about this" from a detail screen: the text the assistant runs for that entity. */
    fun askAbout(ref: EntityRef, bundle: EventBundle): String? = ref.name(bundle)?.let { "Tell me about $it" }

    /** The turn for "Ask about this" without the model: the entity's card and chips (like `get_item`). */
    fun aboutTurn(ref: EntityRef, ctx: AssistantContext): AssistantTurn {
        val action = when (ref.kind) {
            RefKind.SCHEDULE -> AssistantAction(ActionName.GET_ITEM, buildJsonObject { put("id", ref.code) })
            RefKind.LOCATION -> AssistantAction(ActionName.GET_LOCATION, buildJsonObject { put("id", ref.code) })
            RefKind.UPDATE -> AssistantAction(ActionName.GET_UPDATE, buildJsonObject { put("id", ref.code) })
        }
        return AssistantTurn(AssistantRouter.renderRead(action, ctx))
    }
}

/** A plan version or a schedule item on one side of a `compare` block. */
sealed interface CompareSide {
    data class Item(val item: ScheduleItem) : CompareSide
    data class Version(val kind: PlanVersionKind, val edit: PlanEdit) : CompareSide
}

/**
 * The data a block shows, resolved from the bundle (the blocks carry ids and options only):
 * - `timeline` ids as given; source `plan` = today's plan day (else the first day with items) in plan
 *   order; source `whats_on` = items not ended that start within the next 3 h, by (start, end, id),
 *   at most 20.
 * - `compare` sides: "s12" → the item, "plan:chill" / "plan:everything" → that version's preview.
 * - `summary`: walking → [DayRoutes.totalText] of today's (else the first) plan day; recap →
 *   [LiveAssist.recap] of today; liked_counts → "3 liked items and 2 liked places".
 */
object BlockData {

    val WHATS_ON_AHEAD: Duration = Duration.ofHours(3)

    fun timeline(block: AssistantBlock.Timeline, bundle: EventBundle, plan: Plan?, now: Instant): List<Int> {
        if (block.ids.isNotEmpty()) return block.ids
        return when (block.source) {
            "plan" -> planDay(plan, bundle, now)?.items?.map { it.itemId }.orEmpty()
            "whats_on" -> bundle.schedule.filter { item ->
                val start = item.startTime?.toInstant() ?: return@filter false
                TimeMatcher.effectiveEnd(item)!!.isAfter(now) && start.isBefore(now.plus(WHATS_ON_AHEAD))
            }.sortedWith(Planner.ITEM_ORDER).map { it.id }.take(BlockValidator.MAX_LIST)
            else -> emptyList()
        }
    }

    fun planDay(plan: Plan?, bundle: EventBundle, now: Instant, day: LocalDate? = null): PlanDay? {
        plan ?: return null
        if (day != null) return plan.days.firstOrNull { it.date == day }
        val today = now.atZone(bundle.event.zone).toLocalDate()
        return plan.days.firstOrNull { it.date == today && it.entries.isNotEmpty() } ?: plan.days.firstOrNull { it.entries.isNotEmpty() }
    }

    fun compare(side: String, plan: Plan?, bundle: EventBundle, hearts: Hearts, now: Instant): CompareSide? {
        val s = side.trim().lowercase()
        if (s.startsWith("plan:")) {
            val kind = PlanVersionKind.of(s.removePrefix("plan:")) ?: return null
            return CompareSide.Version(kind, PlanVersions.make(kind, plan ?: return null, bundle, hearts, now))
        }
        val ref = EntityRef.parse(s)?.takeIf { it.kind == RefKind.SCHEDULE } ?: return null
        return bundle.scheduleItem(ref.id)?.let { CompareSide.Item(it) }
    }

    fun summary(kind: String, plan: Plan?, bundle: EventBundle, hearts: Hearts, now: Instant, units: DistanceUnits, locale: Locale = Locale.getDefault()): String? {
        val today = now.atZone(bundle.event.zone).toLocalDate()
        return when (kind) {
            "walking" -> {
                val day = planDay(plan, bundle, now) ?: return null
                DayRoutes.of(plan!!, bundle, day.date)?.let { DayRoutes.totalText(it, today, units, locale) }
            }
            "recap" -> plan?.let { p ->
                val date = p.days.lastOrNull { !it.date.isAfter(today) }?.date ?: p.days.firstOrNull()?.date ?: today
                LiveAssist.recap(p, bundle, hearts, date, now, units, locale).text
            }
            "liked_counts" -> likedCounts(hearts)
            else -> null
        }
    }

    fun likedCounts(hearts: Hearts): String {
        fun n(count: Int, one: String) = "$count liked ${if (count == 1) one else one + "s"}"
        return "${n(hearts.scheduleItems.size, "item")} and ${n(hearts.locations.size, "place")}"
    }
}

/** What the assistant's pinned plan shows live (spec §6), from location samples taken while the app is visible. */
data class LiveHints(
    val runningLate: RunningLate? = null,
    val gaps: List<GapSuggestion> = emptyList(),
    /** "You skipped X — fill the gap?" chips. */
    val skipped: List<Chip> = emptyList(),
    /** Planned items the organizer changed or removed. */
    val changed: List<Int> = emptyList(),
    /** Today's planned items have all ended: show the day's recap. */
    val recapReady: Boolean = false,
) {
    val isEmpty: Boolean get() = runningLate == null && gaps.isEmpty() && skipped.isEmpty() && changed.isEmpty() && !recapReady
}

/**
 * Combines the [LiveAssist] rules for the pinned plan: running late (user position known), gap
 * fillers (today), skipped items (minus [dismissed]), organizer changes, and recap-ready (today's
 * plan day has items, every one has ended, and now is still that day).
 */
object LiveHintRules {
    fun of(plan: Plan?, bundle: EventBundle, hearts: Hearts, now: Instant, user: GeoPoint?, samples: List<LocationSample>, dismissed: Set<Int> = emptySet()): LiveHints {
        plan ?: return LiveHints()
        val today = now.atZone(bundle.event.zone).toLocalDate()
        val day = plan.days.firstOrNull { it.date == today }
        val recap = day != null && day.items.isNotEmpty() && day.items.all { !it.end.isAfter(now) }
        return LiveHints(
            runningLate = LiveAssist.runningLate(plan, bundle, now, user),
            gaps = LiveAssist.gaps(plan, bundle, hearts, now),
            skipped = LiveAssist.skipped(plan, bundle, samples, now).filter { it !in dismissed }.mapNotNull { LiveAssist.skippedChip(it, bundle) },
            changed = LiveAssist.changedItems(plan, bundle),
            recapReady = recap,
        )
    }
}

/**
 * Shared plan links (API-015) opened in the app (spec §7): "Use this plan" = exactly the shared items
 * that are still ahead, as a plan (every other item excluded while planning, so the planner adds
 * walks and order but nothing else; stored with the shared items pinned only, so later edits re-solve
 * normally); "Merge with mine" = [GroupPlan] over every shared plan received for the event plus the
 * attendee's likes, with the current plan's preferences (or meals on).
 */
object SharedPlans {

    const val USE_REQUEST = "Shared plan"
    const val MERGE_REQUEST = "Group plan"

    fun use(ids: List<Int>, day: LocalDate?, bundle: EventBundle, hearts: Hearts, now: Instant): Plan? {
        val known = ids.filter { bundle.scheduleItem(it) != null }.distinct()
        if (known.isEmpty()) return null
        val zone = bundle.event.zone
        val days = (listOfNotNull(day) + known.mapNotNull { id -> bundle.scheduleItem(id)?.startTime?.let { EventTime.localDate(it, zone) } }).distinct().sorted()
        val excluded = bundle.schedule.map { it.id }.filter { it !in known }.toSet()
        val plan = Planner(bundle, PlanPreferences(days = days, pace = PlanPace.PACKED), hearts, PlanConstraints(pinned = known.toSet(), excluded = excluded)).plan(now, USE_REQUEST)
        if (plan.isEmpty) return null
        return plan.copy(constraints = PlanConstraints(pinned = known.toSet()))
    }

    fun merge(shared: List<List<Int>>, own: Hearts, bundle: EventBundle, current: Plan?, now: Instant): Plan? {
        if (shared.isEmpty()) return null
        val preferences = current?.preferences ?: PlanPreferences(includeMeals = true)
        return GroupPlan.plan(shared, own, bundle, preferences, now, MERGE_REQUEST).takeIf { !it.isEmpty }
    }
}

/** One stored assistant turn: what was typed (null for a chip or a system turn) and the validated turn. */
data class StoredTurn(val input: String?, val turn: AssistantTurn)

/**
 * The assistant screen's transcript, stored with the plan per event (on the device, kept after leaving
 * like the plan): the last [MAX] turns as `[{"input":…,"blocks":[…],"actions":[…]}]` in the block JSON.
 * Lenient: unknown blocks are dropped, a malformed turn is skipped, broken text is empty. Stored
 * turns are re-validated against the bundle when shown.
 */
object AssistantTranscript {
    const val MAX = 30
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(turns: List<StoredTurn>): String = JsonArray(turns.takeLast(MAX).map { stored ->
        val encoded = BlockJson.encode(stored.turn)
        JsonObject(buildMap {
            stored.input?.let { put("input", JsonPrimitive(it)) }
            putAll(encoded)
        })
    }).toString()

    fun decode(text: String?): List<StoredTurn> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val input = (obj["input"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val blocks = (obj["blocks"] as? JsonArray ?: return@mapNotNull null).mapNotNull { (BlockJson.decode(it) as? BlockJson.Decoded.Ok)?.block }
            val actions = (obj["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                ActionName.of((o["name"] as? JsonPrimitive)?.content)?.let { AssistantAction(it, o["params"] as? JsonObject ?: JsonObject(emptyMap())) }
            }
            if (blocks.isEmpty() && input == null) null else StoredTurn(input, AssistantTurn(blocks, actions))
        }.takeLast(MAX)
    }
}
