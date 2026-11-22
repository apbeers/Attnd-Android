package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Everything the router (and the model prompt) may use. All local. */
data class AssistantContext(
    val bundle: EventBundle,
    val hearts: Hearts,
    val plan: Plan?,
    val now: Instant,
    /** The user's position, only when location is permitted and known. */
    val user: GeoPoint? = null,
    /** The planner may make plans (model available, not the App Clip). */
    val canPlan: Boolean = true,
    /** Ask (the on-device model) is available; the router is then only the fallback. */
    val modelAvailable: Boolean = false,
    val history: PlanHistory = PlanHistory(),
    val units: DistanceUnits = DistanceUnits.IMPERIAL,
    val locale: Locale = Locale.US,
) {
    val blockContext: BlockContext get() = BlockContext(bundle, plan, now)
}

enum class AssistantIntent {
    EMPTY, SEARCH, QUESTION, NO_MATCH, PICK, WHATS_ON, NEAR_ME, LIKE, UNLIKE, LIKED,
    PLAN_EDIT, PLAN_UNDO, PLAN_VERSION, PLAN_COMPARE, PLAN_MAKE, PLAN_SHOW, PLAN_NEXT, WALKING, RECAP, NO_PLAN,
    WHY_NOT, TOGGLE, OPEN, SHARE, CALENDAR,
}

/** A routed turn: what the router understood, the turn (validated blocks + actions) and any plan preview. */
data class RoutedTurn(val intent: AssistantIntent, val turn: AssistantTurn, val edit: PlanEdit? = null) {
    val blocks: List<AssistantBlock> get() = turn.blocks
    val actions: List<AssistantAction> get() = turn.actions
}

/** An entity name in a command, resolved through the search index. */
sealed interface Resolution {
    data class Found(val ref: EntityRef) : Resolution
    data class Ambiguous(val refs: List<EntityRef>) : Resolution
    data object NotFound : Resolution
}

/**
 * The rule-based assistant (assistant spec §3; every phone; pure, iOS ports it 1:1). [route] reads the
 * input as a small command grammar, first match wins (on the folded text: lower case, accents
 * dropped, everything but letters, digits and ':' as spaces):
 *  1. empty → EMPTY (suggestion chips).
 *  2. "undo" → `plan_undo`.
 *  3. "add <x | my plan> to (my) calendar" → a "not available" text (+ Share my plan), as on iOS
 *     (no Add to Calendar since Nov 19, 2026).
 *  4. "replace | swap <a> with | for <b>": b = "something | anything | one like | similar to <item>"
 *     (like), "something | some | more <tag>" or a bare tag (tag), else an item (with); a is resolved
 *     preferring planned items → `plan_edit replace`.
 *  5. "remove | drop | skip | cancel <a> (from my plan)" → `plan_edit remove`.
 *  6. "add <a> (to my plan)" → `plan_edit add`.
 *  7. "move <a> later | earlier" → `plan_edit move`.
 *  8. "keep <a>" → keep; "unkeep | unpin <a>" → unkeep.
 *  9. "already ate", "just ate", "i ate", "had lunch | dinner | food", "ate already" →
 *     `no_food_until` = now (truncated to the minute) + 3 h.
 * 10. "i m tired", "im tired", "i am tired", "tired", "exhausted", "slow down", "take it easy" → pace relaxed.
 * 11. "done | finish(ed) | home | back | out | leave | leaving  by | at | before  H(:MM)( am | pm)" → `done_by`.
 * 12. "start | begin | starting  at | after | from | around  H(:MM)( am | pm)" → `start_at`.
 *     Clock rule for 11–12: am/pm as given (12 am = 00); 13–23 as is; a bare 1–11 is the morning time
 *     when that is after now (event zone), else the evening one; a bare 12 is noon.
 * 13. "short walks", "short walk", "less walking", "walk less", "shorter walks", "no long walks",
 *     "don t want to walk" → `max_walk_min` 10; "walk(s) under | less than | up to N min" → N.
 * 14. "i m | im | i am  at | by | near | in <place>" → `from_location`.
 * 15. "more | extra | lots of <tag>" → `more_tag`; "less | fewer | no more | not so much <tag>" →
 *     `less_tag` (tag = an event tag equal to the phrase, or to it without / with a final "s").
 * 16. "chill | relaxed | easy  version | plan | day" → `plan_version chill`; "see everything",
 *     "see it all", "packed version | plan", "everything version" → `plan_version everything`;
 *     "compare … version(s) | plan(s)" → `compare` plan:chill vs plan:everything.
 * 17. "unlike | unheart <x>" → `unlike`; "like | heart | love | save | i like | i love <x>" → `like`
 *     (schedule items and places).
 * 18. toggles (a confirm block): "remind"/"reminder(s)" → `reminders`; "lock screen", "live update",
 *     "follow" → `follow_lock_screen`; "notification(s)", "notify" → `notifications`; off when the
 *     text has "off", "stop", "disable", "don t" or "no ", else on.
 * 19. "share (my) plan" → `share` plan; "share <item>" → `share` item.
 * 20. "why isn t | isnt | is not | wasn t | not | no <item> (in my plan)" → `why_not`.
 * 21. "what s next" or "next … plan" with a plan → the next planned item (card + countdown).
 * 22. "(show | open | see) (my) plan", "my plan" → `plan`; "how far", "walking", "how much walking"
 *     → walking summary + route map; "recap", "how did i do", "my day" → recap.
 * 23. "liked", "my likes", "what (have | did) i liked", "show (my) likes", "what i liked" → the liked list.
 * 24. "show | open | go to | take me to | see  (me) (the | my)  map | flyer | schedule | locations |
 *     updates | options | settings | tour" → `open`.
 * 25. "near me", "nearby", "closest", "around me", "near here" → places (the rest of the text
 *     searched among places; all places with coordinates when nothing is left) by distance when the
 *     user's position is known (`map` + cards), else a note + cards in search order.
 * 26. a plan request ([PlanRequest.looksLikePlan]) when plans can be made → `plan_make`.
 * 27. only time words after [SearchQuery.keywords] ("what's on tonight") → `timeline` (not ended).
 * 28. a question ("?" or a question word first) → results for its keywords + a note.
 * 29. otherwise a search; nothing found → NO_MATCH with the suggestion chips.
 * Plan commands (4–16, 21–22) without a plan → NO_PLAN. Every turn's blocks go through
 * [BlockValidator]. Entity names resolve with [resolve].
 */
object AssistantRouter {

    const val ASK_UNAVAILABLE = "Ask needs on-device AI, which isn't available on this phone. Here's what matches."
    const val NO_MATCH_TEXT = "Nothing matches that. Try one of these:"
    const val NO_PLAN_TEXT = "You don't have a plan yet."
    const val CALENDAR_TEXT = "Attnd doesn't add things to your calendar."
    private val FILLER = setOf("the", "a", "an", "thing", "one", "that", "this")
    private const val MIN_BASE_PER_TOKEN = 3.0

    fun route(input: String, ctx: AssistantContext): RoutedTurn {
        val cmd = command(input)
        val plan = ctx.plan
        fun done(intent: AssistantIntent, blocks: List<AssistantBlock>, actions: List<AssistantAction> = emptyList(), edit: PlanEdit? = null) =
            RoutedTurn(intent, AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext), actions), edit)

        if (cmd.isEmpty()) return done(AssistantIntent.EMPTY, listOf(AssistantBlock.Chips(suggestionChips(ctx))))

        if (cmd == "undo" || cmd == "undo that") {
            if (plan == null) return noPlan(ctx)
            return if (ctx.history.canUndo) done(AssistantIntent.PLAN_UNDO, listOf(AssistantBlock.Text("Undid your last plan change.")), listOf(AssistantAction(ActionName.PLAN_UNDO)))
            else done(AssistantIntent.PLAN_UNDO, listOf(AssistantBlock.Text("There's nothing to undo.")))
        }

        if (Regex("""^(?:add|put|save)\s+(.+?)\s+(?:to|in|on)\s+(?:my\s+)?calendar$""").matches(cmd)) {
            val blocks = buildList<AssistantBlock> {
                add(AssistantBlock.Text(CALENDAR_TEXT))
                if (plan != null) add(chips(listOf(Chip("Share my plan", AssistantAction(ActionName.SHARE_PLAN)))))
            }
            return done(AssistantIntent.CALENDAR, blocks)
        }

        planCommand(cmd, ctx)?.let { return it }

        Regex("""^(?:unlike|unheart|un like)\s+(.+)$""").find(cmd)?.let { m -> return like(m.groupValues[1], liked = false, ctx) }
        Regex("""^(?:like|heart|love|save|i like|i love)\s+(.+)$""").find(cmd)?.let { m -> return like(m.groupValues[1], liked = true, ctx) }

        toggle(cmd)?.let { action ->
            return done(AssistantIntent.TOGGLE, listOf(AssistantBlock.Confirm(action, ActionRegistry.confirmPrompt(action))))
        }

        if (Regex("""^share\s+(?:my\s+)?plan$""").matches(cmd)) {
            if (plan == null) return noPlan(ctx)
            return done(AssistantIntent.SHARE, listOf(AssistantBlock.Share("plan")))
        }
        Regex("""^share\s+(.+)$""").find(cmd)?.let { m ->
            return withEntity(m.groupValues[1], setOf(RefKind.SCHEDULE), ctx, AssistantIntent.SHARE, { ref -> Chip(name(ref, ctx), AssistantAction(ActionName.SHARE_ITEM, JsonObject(mapOf("id" to JsonPrimitive(ref.code))))) }) { ref ->
                done(AssistantIntent.SHARE, listOf(AssistantBlock.Share("item:${ref.code}")))
            }
        }

        Regex("""^why\s+(?:isn t|isnt|is not|wasn t|not|no)\s+(.+?)(?:\s+in\s+(?:my\s+)?plan)?$""").find(cmd)?.let { m ->
            return withEntity(m.groupValues[1], setOf(RefKind.SCHEDULE), ctx, AssistantIntent.WHY_NOT, { ref -> Chip(name(ref, ctx), AssistantAction(ActionName.WHY_NOT, JsonObject(mapOf("item" to JsonPrimitive(ref.code))))) }) { ref ->
                done(AssistantIntent.WHY_NOT, whyNotBlocks(ref.id, ctx))
            }
        }

        if (Regex("""\bwhat s next\b|\bnext\b.*\bplan\b""").containsMatchIn(cmd) && (plan != null || cmd.contains("plan"))) {
            if (plan == null) return noPlan(ctx)
            val next = LiveAssist.nextPlanned(plan, ctx.bundle, ctx.now)
                ?: return done(AssistantIntent.PLAN_NEXT, listOf(AssistantBlock.Text("Nothing else is in your plan.")))
            val relative = RelativeTime.relativeStart(next.startTime!!.toInstant(), TimeMatcher.effectiveEnd(next), ctx.now, ctx.bundle.event.zone, ctx.locale)
            return done(
                AssistantIntent.PLAN_NEXT,
                listOf(AssistantBlock.Text("Next in your plan: **${next.name}**, $relative."), AssistantBlock.ItemCard(next.id), AssistantBlock.Countdown(next.id), chips(entityChips(EntityRef(RefKind.SCHEDULE, next.id), ctx))),
            )
        }

        if (Regex("""^(?:(?:show|open|see)\s+)?(?:me\s+)?(?:my\s+)?plan$""").matches(cmd) || cmd == "my plan") {
            if (plan == null) return noPlan(ctx)
            return done(AssistantIntent.PLAN_SHOW, planBlocks(ctx))
        }
        if (Regex("""\b(how far|how much walking|walking)\b""").containsMatchIn(cmd) && !cmd.contains("short")) {
            if (plan == null) return noPlan(ctx)
            return done(AssistantIntent.WALKING, walkingBlocks(plan, ctx))
        }
        if (Regex("""\b(recap|how did i do|my day so far)\b""").containsMatchIn(cmd)) {
            if (plan == null) return noPlan(ctx)
            val today = ctx.now.atZone(ctx.bundle.event.zone).toLocalDate()
            val date = plan.days.lastOrNull { !it.date.isAfter(today) }?.date ?: plan.days.firstOrNull()?.date ?: today
            val recap = LiveAssist.recap(plan, ctx.bundle, ctx.hearts, date, ctx.now, ctx.units, ctx.locale)
            return done(AssistantIntent.RECAP, listOf(AssistantBlock.Text(recap.text), AssistantBlock.Summary("recap"), chips(recap.chips)))
        }

        if (Regex("""^(?:liked|likes|my likes|what (?:have|did) i liked?|show (?:me )?(?:my )?likes|what i liked|things i liked)$""").matches(cmd)) {
            return done(AssistantIntent.LIKED, likedBlocks(ctx))
        }

        Regex("""^(?:show|open|go to|take me to|see)\s+(?:me\s+)?(?:the\s+|my\s+)?(map|flyer|schedule|locations|updates|options|settings|tour)$""").find(cmd)?.let { m ->
            val screen = when (val s = m.groupValues[1]) {
                "settings" -> "options"
                else -> s
            }
            return done(AssistantIntent.OPEN, listOf(AssistantBlock.Text("Opening ${if (screen == "options") "Options" else "the $screen"}.")), listOf(AssistantAction.open(screen)))
        }

        if (Regex("""\b(near me|nearby|closest|around me|near here)\b""").containsMatchIn(cmd)) return nearMe(cmd, ctx)

        if (ctx.canPlan && PlanRequest.looksLikePlan(input)) {
            return done(AssistantIntent.PLAN_MAKE, listOf(AssistantBlock.Text("Making your plan.")), listOf(AssistantAction.planMake(input.trim())))
        }

        val keywords = SearchQuery.keywords(cmd)
        val parsed = SearchQuery.parse(keywords)
        if (parsed.isTimeOnly) return whatsOn(keywords, input, ctx)

        val question = input.trim().endsWith("?") || cmd.substringBefore(' ') in QUESTION_WORDS
        val query = if (question) keywords else cmd
        val results = EventSearch.search(ctx.bundle, query, ctx.now)
        if (results.isEmpty()) {
            return done(AssistantIntent.NO_MATCH, listOf(AssistantBlock.Text(NO_MATCH_TEXT), AssistantBlock.Chips(suggestionChips(ctx))))
        }
        val blocks = searchBlocks(results, ctx)
        return if (question) {
            done(AssistantIntent.QUESTION, listOf(AssistantBlock.Text(if (ctx.modelAvailable) "Here's what matches." else ASK_UNAVAILABLE)) + blocks)
        } else {
            done(AssistantIntent.SEARCH, blocks)
        }
    }

    private val QUESTION_WORDS = setOf("what", "when", "where", "who", "which", "how", "is", "are", "does", "do", "can", "will", "whats")

    /** Lower case, accents dropped, everything but letters, digits and ':' → spaces, collapsed. */
    fun command(input: String): String =
        SearchText.fold(input).replace(Regex("""[^\p{L}\p{N}:]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    // ── Plan commands (4–16) ────────────────────────────────────────────────

    private fun planCommand(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val bundle = ctx.bundle
        fun op(ops: List<PlanOp>) = edit(ops, ctx)
        fun item(phrase: String, makeOps: (Int) -> List<PlanOp>, label: (EntityRef) -> String = { name(it, ctx) }): RoutedTurn {
            if (ctx.plan == null) return noPlan(ctx)
            return withEntity(phrase, setOf(RefKind.SCHEDULE), ctx, AssistantIntent.PLAN_EDIT, { ref ->
                Chip(label(ref), AssistantAction.planEdit(makeOps(ref.id), bundle))
            }, preferPlanned = true) { ref -> op(makeOps(ref.id)) }
        }

        Regex("""^(?:replace|swap)\s+(.+?)\s+(?:with|for)\s+(.+)$""").find(cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            val target = m.groupValues[2]
            val likeMatch = Regex("""^(?:something|anything|one|some)\s+(?:like|similar to)\s+(.+)$""").find(target)
            val tagPhrase = Regex("""^(?:something|some|more)\s+(.+)$""").find(target)?.groupValues?.get(1) ?: target
            val tag = tagOf(tagPhrase, bundle)
            val replacement: (Int) -> List<PlanOp>
            if (likeMatch != null) {
                val like = resolve(likeMatch.groupValues[1], setOf(RefKind.SCHEDULE), ctx)
                val likeId = (like as? Resolution.Found)?.ref?.id ?: (like as? Resolution.Ambiguous)?.refs?.first()?.id
                    ?: return notFound(likeMatch.groupValues[1], ctx)
                replacement = { listOf(PlanOp.Replace(it, like = likeId)) }
            } else if (tag != null) {
                replacement = { listOf(PlanOp.Replace(it, tag = tag)) }
            } else {
                val with = resolve(target, setOf(RefKind.SCHEDULE), ctx)
                val withId = when (with) {
                    is Resolution.Found -> with.ref.id
                    is Resolution.Ambiguous -> return pick(with.refs, ctx, AssistantIntent.PLAN_EDIT) { ref ->
                        val from = (resolve(m.groupValues[1], setOf(RefKind.SCHEDULE), ctx, preferPlanned = true) as? Resolution.Found)?.ref?.id
                        Chip(name(ref, ctx), AssistantAction.planEdit(listOfNotNull(from?.let { PlanOp.Replace(it, with = ref.id) }).ifEmpty { listOf(PlanOp.Add(ref.id)) }, bundle))
                    }
                    Resolution.NotFound -> return notFound(target, ctx)
                }
                replacement = { listOf(PlanOp.Replace(it, with = withId)) }
            }
            return item(m.groupValues[1], replacement)
        }
        Regex("""^(?:remove|drop|skip|cancel)\s+(.+?)(?:\s+from\s+(?:my\s+)?plan)?$""").find(cmd)?.let { m ->
            return item(m.groupValues[1], { listOf(PlanOp.Remove(it)) })
        }
        Regex("""^add\s+(.+?)(?:\s+to\s+(?:my\s+)?plan)?$""").find(cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            return withEntity(m.groupValues[1], setOf(RefKind.SCHEDULE), ctx, AssistantIntent.PLAN_EDIT, { ref ->
                Chip(name(ref, ctx), AssistantAction.planEdit(listOf(PlanOp.Add(ref.id)), bundle))
            }) { ref -> op(listOf(PlanOp.Add(ref.id))) }
        }
        Regex("""^move\s+(.+?)\s+(later|earlier)$""").find(cmd)?.let { m ->
            val later = m.groupValues[2] == "later"
            return item(m.groupValues[1], { listOf(PlanOp.Move(it, later)) })
        }
        Regex("""^(?:unkeep|unpin|don t keep)\s+(.+)$""").find(cmd)?.let { m -> return item(m.groupValues[1], { listOf(PlanOp.Unkeep(it)) }) }
        Regex("""^keep\s+(.+)$""").find(cmd)?.let { m -> return item(m.groupValues[1], { listOf(PlanOp.Keep(it)) }) }

        if (Regex("""\b(already ate|just ate|i ate|had lunch|had dinner|had food|ate already)\b""").containsMatchIn(cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.NoFoodUntil(ctx.now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofHours(3)))))
        }
        if (Regex("""\b(i m tired|im tired|i am tired|tired|exhausted|slow down|take it easy)\b""").containsMatchIn(cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.Pace(PlanPace.RELAXED)))
        }
        Regex("""\b(?:done|finish|finished|home|back|out|leave|leaving)\s+(?:by|at|before)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?\b""").find(cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            val time = clock(m, ctx) ?: return null
            return op(listOf(PlanOp.DoneBy(time)))
        }
        Regex("""\b(?:start|begin|starting)\s+(?:at|after|from|around)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?\b""").find(cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            val time = clock(m, ctx) ?: return null
            return op(listOf(PlanOp.StartAt(time)))
        }
        Regex("""\bwalks? (?:under|less than|up to|of at most) (\d{1,2}) ?(?:min|mins|minutes)\b""").find(cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.MaxWalk(m.groupValues[1].toInt().coerceAtLeast(1))))
        }
        if (Regex("""\b(short walks?|less walking|walk less|shorter walks|no long walks|don t want to walk)\b""").containsMatchIn(cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.MaxWalk(PlanVersionKind.CHILL_MAX_WALK)))
        }
        Regex("""^(?:i m|im|i am)\s+(?:at|by|near|in)\s+(.+)$""").find(cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            return withEntity(m.groupValues[1], setOf(RefKind.LOCATION), ctx, AssistantIntent.PLAN_EDIT, { ref ->
                Chip(name(ref, ctx), AssistantAction.planEdit(listOf(PlanOp.FromLocation(ref.id)), bundle))
            }) { ref -> op(listOf(PlanOp.FromLocation(ref.id))) }
        }
        Regex("""^(?:more|extra|lots of)\s+(.+)$""").find(cmd)?.let { m ->
            tagOf(m.groupValues[1], bundle)?.let { tag -> return if (ctx.plan == null) noPlan(ctx) else op(listOf(PlanOp.MoreTag(tag))) }
        }
        Regex("""^(?:less|fewer|no more|not so much)\s+(.+)$""").find(cmd)?.let { m ->
            tagOf(m.groupValues[1], bundle)?.let { tag -> return if (ctx.plan == null) noPlan(ctx) else op(listOf(PlanOp.LessTag(tag))) }
        }
        val version = when {
            Regex("""\b(chill|relaxed|easy) (version|plan|day)\b""").containsMatchIn(cmd) -> PlanVersionKind.CHILL
            Regex("""\b(see everything|see it all|packed version|packed plan|everything version)\b""").containsMatchIn(cmd) -> PlanVersionKind.EVERYTHING
            else -> null
        }
        if (version != null) {
            val plan = ctx.plan ?: return noPlan(ctx)
            val preview = PlanVersions.make(version, plan, bundle, ctx.hearts, ctx.now)
            return RoutedTurn(
                AssistantIntent.PLAN_VERSION,
                AssistantTurn(
                    BlockValidator.validate(listOf(AssistantBlock.Text("${version.label}: ${describe(preview, bundle)}"), AssistantBlock.PlanDiffBlock(preview.diff), editChips(ctx)), ctx.blockContext),
                    listOf(AssistantAction.planVersion(version)),
                ),
                preview,
            )
        }
        if (Regex("""\bcompare\b.*\b(versions?|plans?)\b""").containsMatchIn(cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return RoutedTurn(
                AssistantIntent.PLAN_COMPARE,
                AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Compare("plan:chill", "plan:everything")), ctx.blockContext)),
            )
        }
        return null
    }

    private fun edit(ops: List<PlanOp>, ctx: AssistantContext): RoutedTurn {
        val plan = ctx.plan ?: return noPlan(ctx)
        val preview = PlanEditor.preview(plan, ops, ctx.bundle, ctx.hearts, ctx.now)
        val blocks = listOf(AssistantBlock.Text(describe(preview, ctx.bundle)), AssistantBlock.PlanDiffBlock(preview.diff), editChips(ctx))
        return RoutedTurn(
            AssistantIntent.PLAN_EDIT,
            AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext), listOf(AssistantAction.planEdit(ops, ctx.bundle))),
            preview,
        )
    }

    private fun editChips(ctx: AssistantContext) = AssistantBlock.Chips(
        listOf(Chip("Undo", AssistantAction(ActionName.PLAN_UNDO)), Chip("Show my plan", AssistantAction(ActionName.PLAN))),
    )

    /**
     * The sentence above a `plan_diff`: "Moved X to 8:00 PM." "Removed A and B." "Added C." "No lunch
     * break." "Added a dinner break." "Moved the lunch break." then each note + "."; "Your plan stays
     * the same." when nothing changed and there are no notes.
     */
    fun describe(edit: PlanEdit, bundle: EventBundle, locale: Locale = Locale.US): String {
        val diff = edit.diff
        fun n(id: Int) = bundle.scheduleItem(id)?.name ?: "an item"
        val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", locale)
        val zone = bundle.event.zone
        val parts = buildList {
            diff.moved.forEach { (a, b) -> add("Moved ${n(a)} to ${bundle.scheduleItem(b)?.startTime?.atZoneSameInstant(zone)?.format(clock) ?: "another time"}.") }
            if (diff.removed.isNotEmpty()) add("Removed ${names(diff.removed.map(::n))}.")
            if (diff.added.isNotEmpty()) add("Added ${names(diff.added.map(::n))}.")
            diff.mealsRemoved.forEach { add("No ${it.kind.label.lowercase()}.") }
            diff.mealsAdded.forEach { add("Added a ${it.kind.label.lowercase()}.") }
            diff.mealsMoved.forEach { add("Moved the ${it.kind.label.lowercase()}.") }
            edit.notes.forEach { add("$it.") }
        }
        return parts.joinToString(" ").ifEmpty { "Your plan stays the same." }
    }

    private fun names(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    private fun clock(m: MatchResult, ctx: AssistantContext): LocalTime? {
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val suffix = m.groupValues[3].replace(" ", "")
        if (hour > 23 || minute > 59) return null
        val h = when {
            suffix == "pm" -> if (hour > 12) return null else hour % 12 + 12
            suffix == "am" -> if (hour > 12) return null else hour % 12
            hour == 0 || hour >= 12 -> hour
            else -> {
                val local = ctx.now.atZone(ctx.bundle.event.zone).toLocalTime()
                if (LocalTime.of(hour, minute).isAfter(local)) hour else hour + 12
            }
        }
        return LocalTime.of(h, minute)
    }

    /** An event tag equal to [phrase], or to it without / with a final "s". */
    fun tagOf(phrase: String, bundle: EventBundle): String? {
        val p = SearchText.normalize(phrase).trim()
        if (p.isEmpty()) return null
        val tags = PlanRequest.tagVocabulary(bundle)
        return tags.firstOrNull { SearchText.normalize(it) == p }
            ?: tags.firstOrNull { SearchText.normalize(it) == p.removeSuffix("s") }
            ?: tags.firstOrNull { SearchText.normalize(it) == p + "s" }
    }

    // ── Entities ────────────────────────────────────────────────────────────

    /**
     * Resolves a name in a command: filler words ("the", "a", "an", "thing", "one", "that", "this") are
     * dropped and the rest searched ([EventSearch], all tokens) among [kinds]; with [preferPlanned]
     * and a plan, planned schedule items win when any match. Base score = score without the now /
     * next / ended boosts. Found = the top result when its base ≥ 3 × the number of text tokens (a
     * time-only phrase like "3pm" needs no score) and no other result has the same base; Ambiguous =
     * up to 5 results tied at the top base; otherwise NotFound.
     */
    fun resolve(phrase: String, kinds: Set<RefKind>, ctx: AssistantContext, preferPlanned: Boolean = false): Resolution {
        val words = command(phrase).split(' ').filter { it.isNotEmpty() && it !in FILLER }
        if (words.isEmpty()) return Resolution.NotFound
        val text = words.joinToString(" ")
        val tokens = SearchQuery.parse(text).tokens.size
        var results = EventSearch.search(ctx.bundle, text, ctx.now).filter { r -> RefKind.of(r.kind)?.let { it in kinds } == true }
        val plan = ctx.plan
        if (preferPlanned && plan != null) {
            val planned = results.filter { it.kind == SearchKind.SCHEDULE && it.id in plan.itemIds }
            if (planned.isNotEmpty()) results = planned
        }
        if (results.isEmpty()) return Resolution.NotFound
        val scored = results.map { it to base(it) }.sortedWith(compareByDescending<Pair<SearchResult, Double>> { it.second }.thenBy { it.first.timing == ItemTiming.ENDED })
        val top = scored.first().second
        if (top < MIN_BASE_PER_TOKEN * tokens) return Resolution.NotFound
        val tied = scored.filter { it.second == top }.map { it.first }
        val refs = tied.mapNotNull { EntityRef.of(it) }
        return if (refs.size == 1) Resolution.Found(refs.single()) else Resolution.Ambiguous(refs.take(5))
    }

    private fun base(result: SearchResult): Double = result.score - when (result.timing) {
        ItemTiming.NOW -> EventSearch.BOOST_NOW
        ItemTiming.NEXT -> EventSearch.BOOST_NEXT
        ItemTiming.ENDED -> EventSearch.PENALTY_ENDED
        null -> 0.0
    }

    private fun withEntity(
        phrase: String,
        kinds: Set<RefKind>,
        ctx: AssistantContext,
        intent: AssistantIntent,
        chip: (EntityRef) -> Chip,
        preferPlanned: Boolean = false,
        found: (EntityRef) -> RoutedTurn,
    ): RoutedTurn = when (val r = resolve(phrase, kinds, ctx, preferPlanned)) {
        is Resolution.Found -> found(r.ref)
        is Resolution.Ambiguous -> pick(r.refs, ctx, intent, chip)
        Resolution.NotFound -> notFound(phrase, ctx)
    }

    /** Ambiguity: "Which one do you mean?" + one chip per candidate (the same command, resolved). */
    private fun pick(refs: List<EntityRef>, ctx: AssistantContext, intent: AssistantIntent, chip: (EntityRef) -> Chip): RoutedTurn {
        val chips = refs.take(BlockValidator.MAX_CHIPS).map { ref ->
            val c = chip(ref)
            val label = if (refs.count { name(it, ctx) == name(ref, ctx) } > 1) "${c.label} · ${shortDetail(ref, ctx)}" else c.label
            c.copy(label = ChipText.fit("", label))
        }
        return RoutedTurn(
            AssistantIntent.PICK,
            AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text("Which one do you mean?"), AssistantBlock.Chips(chips)), ctx.blockContext)),
        )
    }

    private fun notFound(phrase: String, ctx: AssistantContext): RoutedTurn = RoutedTurn(
        AssistantIntent.NO_MATCH,
        AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text("I couldn't find “${phrase.trim()}”."), AssistantBlock.Chips(suggestionChips(ctx))), ctx.blockContext)),
    )

    private fun noPlan(ctx: AssistantContext): RoutedTurn {
        val chips = if (ctx.canPlan) {
            PlanRequest.examples(ctx.bundle, ctx.now).filter { it.isPlan }.map { Chip(ChipText.fit("", it.text), AssistantAction.planMake(it.text)) }
        } else {
            suggestionChips(ctx)
        }
        return RoutedTurn(
            AssistantIntent.NO_PLAN,
            AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text(NO_PLAN_TEXT), AssistantBlock.Chips(chips)), ctx.blockContext)),
        )
    }

    private fun name(ref: EntityRef, ctx: AssistantContext): String = ref.name(ctx.bundle) ?: ref.code

    private fun shortDetail(ref: EntityRef, ctx: AssistantContext): String = when (ref.kind) {
        RefKind.SCHEDULE -> ctx.bundle.scheduleItem(ref.id)?.startTime?.let { EventTime.formatDayTime(it, ctx.bundle.event.zone, ctx.locale) }.orEmpty()
        RefKind.LOCATION -> ctx.bundle.location(ref.id)?.category.orEmpty()
        RefKind.UPDATE -> ""
    }

    // ── Likes ───────────────────────────────────────────────────────────────

    private fun like(phrase: String, liked: Boolean, ctx: AssistantContext): RoutedTurn {
        val intent = if (liked) AssistantIntent.LIKE else AssistantIntent.UNLIKE
        return withEntity(phrase, setOf(RefKind.SCHEDULE, RefKind.LOCATION), ctx, intent, { ref ->
            Chip(name(ref, ctx), AssistantAction.like(ref.kind.searchKind, ref.id, liked))
        }) { ref ->
            val kind = ref.kind.searchKind
            val already = ctx.hearts.has(kind, ref.id) == liked
            val after = ctx.copy(hearts = ctx.hearts.with(kind, ref.id, liked))
            val text = when {
                already && liked -> "You already like ${name(ref, ctx)}."
                already -> "You don't like ${name(ref, ctx)} yet."
                liked -> "Liked ${name(ref, ctx)}."
                else -> "Unliked ${name(ref, ctx)}."
            }
            val clash = if (liked && ref.kind == RefKind.SCHEDULE) ctx.plan?.let { LiveAssist.likeClash(ref.id, it, ctx.bundle) } else null
            val blocks = listOf(AssistantBlock.Text(text), BlockValidator.card(ref), chips(listOfNotNull(clash) + entityChips(ref, after)))
            RoutedTurn(
                intent,
                AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext), if (already) emptyList() else listOf(AssistantAction.like(kind, ref.id, liked))),
            )
        }
    }

    private fun likedBlocks(ctx: AssistantContext): List<AssistantBlock> {
        val refs = EventSearch.hearted(ctx.bundle, ctx.hearts, ctx.now).mapNotNull { EntityRef.of(it) }
        if (refs.isEmpty()) return listOf(AssistantBlock.Text("You haven't liked anything yet."), AssistantBlock.Chips(suggestionChips(ctx)))
        return listOf(AssistantBlock.ListBlock(refs, EventSearch.HEARTED_TITLE))
    }

    // ── Layout (spec §2 rules) ──────────────────────────────────────────────

    /**
     * Search results as blocks, by kind in the order places, schedule items, updates: one → its card,
     * 2–8 → a `carousel`, more → a `list` (titles "Places", "Schedule", "Updates"); places add a `map`
     * after their block when 2 or more have coordinates (≤ 12 pins). An event-only match → a `text`
     * with its dates and address. Then the lead entity's chips (the first place, else item, else update).
     */
    fun searchBlocks(results: List<SearchResult>, ctx: AssistantContext): List<AssistantBlock> {
        val refs = results.mapNotNull { EntityRef.of(it) }
        val places = refs.filter { it.kind == RefKind.LOCATION }
        val items = refs.filter { it.kind == RefKind.SCHEDULE }
        val updates = refs.filter { it.kind == RefKind.UPDATE }
        if (refs.isEmpty()) {
            return if (results.any { it.kind == SearchKind.EVENT }) {
                listOf(AssistantBlock.Text("**${ctx.bundle.event.name}** · ${EventSearch.eventDetail(ctx.bundle.event, ctx.locale)}"), AssistantBlock.Chips(suggestionChips(ctx)))
            } else {
                emptyList()
            }
        }
        val blocks = mutableListOf<AssistantBlock>()
        group(places, "Places")?.let { blocks += it }
        val pins = places.map { it.id }.filter { ctx.bundle.location(it)?.coordinate != null }
        if (pins.size >= 2) blocks += AssistantBlock.MapBlock(pins.take(BlockValidator.MAX_PINS))
        group(items, "Schedule")?.let { blocks += it }
        group(updates, "Updates")?.let { blocks += it }
        val lead = places.firstOrNull() ?: items.firstOrNull() ?: updates.first()
        blocks += chips(entityChips(lead, ctx))
        return blocks
    }

    private fun group(refs: List<EntityRef>, title: String): AssistantBlock? = when {
        refs.isEmpty() -> null
        refs.size == 1 -> BlockValidator.card(refs.single())
        refs.size <= BlockValidator.MAX_CAROUSEL -> AssistantBlock.Carousel(refs, title)
        else -> AssistantBlock.ListBlock(refs, title)
    }

    private fun chips(list: List<Chip>) = AssistantBlock.Chips(list.take(BlockValidator.MAX_CHIPS))

    /**
     * Follow-ups for one entity, in order. Schedule item: "Like" / "Unlike"; "Add to my plan" (a plan
     * exists, the item isn't in it and hasn't started); "Show on map" (its location has coordinates).
     * Place: "Like" / "Unlike"; "Show on map" and "Directions" (with coordinates). Update: "All updates".
     */
    fun entityChips(ref: EntityRef, ctx: AssistantContext): List<Chip> {
        val bundle = ctx.bundle
        return when (ref.kind) {
            RefKind.SCHEDULE -> {
                val item = bundle.scheduleItem(ref.id) ?: return emptyList()
                buildList {
                    add(likeChip(ref, ctx))
                    val plan = ctx.plan
                    if (plan != null && item.id !in plan.itemIds && item.startTime?.toInstant()?.isAfter(ctx.now) == true) {
                        add(Chip("Add to my plan", AssistantAction.planEdit(listOf(PlanOp.Add(item.id)), bundle)))
                    }
                    bundle.location(item.locationId)?.takeIf { it.coordinate != null }?.let {
                        add(Chip("Show on map", AssistantAction.open("map", EntityRef(RefKind.LOCATION, it.id))))
                    }
                }
            }
            RefKind.LOCATION -> {
                val location = bundle.location(ref.id) ?: return emptyList()
                buildList {
                    add(likeChip(ref, ctx))
                    if (location.coordinate != null) {
                        add(Chip("Show on map", AssistantAction.open("map", ref)))
                        add(Chip("Directions", AssistantAction.directions(location.id)))
                    }
                }
            }
            RefKind.UPDATE -> listOf(Chip("All updates", AssistantAction.open("updates")))
        }
    }

    private fun likeChip(ref: EntityRef, ctx: AssistantContext): Chip {
        val liked = ctx.hearts.has(ref.kind.searchKind, ref.id)
        return Chip(if (liked) "Unlike" else "Like", AssistantAction.like(ref.kind.searchKind, ref.id, !liked))
    }

    /**
     * The empty-state chips, ≤ 5 in order: "Happening now" (something is in progress), "Up next"
     * (something starts within 2 h), "Liked" (anything liked), then the categories (each a search).
     */
    fun suggestionChips(ctx: AssistantContext): List<Chip> = buildList {
        val suggestions = EventSearch.suggestions(ctx.bundle, ctx.now)
        val timed = suggestions.filter { it.query == "now" || it.query == "next" }
        timed.forEach { add(Chip(it.label, AssistantAction.search(it.query))) }
        if (!ctx.hearts.isEmpty) add(Chip(EventSearch.HEARTED_TITLE, AssistantAction(ActionName.LIKED)))
        (suggestions - timed.toSet()).forEach { add(Chip(it.label, AssistantAction.search(it.query))) }
    }.take(BlockValidator.MAX_CHIPS)

    // ── Time and place ──────────────────────────────────────────────────────

    /**
     * "What's on tonight": schedule items matching the time words ([EventSearch], time-only), not
     * ended, as a `timeline` titled "Happening now" / "Up next" / the words capitalised ("Tonight").
     * Chips: "Make a plan for this" (plans can be made, no plan yet; `plan_make` with the input), "Show the map".
     */
    private fun whatsOn(keywords: String, input: String, ctx: AssistantContext): RoutedTurn {
        val items = EventSearch.search(ctx.bundle, keywords, ctx.now).filter { it.kind == SearchKind.SCHEDULE && it.timing != ItemTiming.ENDED }.map { it.id }
        val title = when (keywords.trim()) {
            "now", "happening now" -> ScheduleGrouping.NOW_TITLE
            "next", "up next", "soon" -> ScheduleGrouping.NEXT_TITLE
            else -> keywords.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        val blocks = if (items.isEmpty()) {
            listOf(AssistantBlock.Text("Nothing's on then."), AssistantBlock.Chips(suggestionChips(ctx)))
        } else {
            val chips = buildList {
                if (ctx.canPlan && ctx.plan == null) add(Chip("Make a plan for this", AssistantAction.planMake(input.trim())))
                add(Chip("Show the map", AssistantAction.open("map")))
            }
            listOf(AssistantBlock.Timeline(items, title = title), chips(chips))
        }
        return RoutedTurn(AssistantIntent.WHATS_ON, AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext)))
    }

    private fun nearMe(cmd: String, ctx: AssistantContext): RoutedTurn {
        val rest = cmd.replace(Regex("""\b(near me|nearby|closest|around me|near here|what s|whats|the|is|are|places?)\b"""), " ").trim().replace(Regex("""\s+"""), " ")
        val query = SearchQuery.keywords(rest).let { if (it == rest && SearchText.words(it).all { w -> w in SearchQuery.STOP_WORDS }) "" else it }.trim()
        val bundle = ctx.bundle
        val places = if (query.isEmpty()) {
            bundle.locations.filter { it.coordinate != null }.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.id }
        } else {
            EventSearch.search(bundle, query, ctx.now).filter { it.kind == SearchKind.LOCATION }.map { it.id }
        }
        if (places.isEmpty()) {
            return RoutedTurn(AssistantIntent.NO_MATCH, AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text(NO_MATCH_TEXT), AssistantBlock.Chips(suggestionChips(ctx))), ctx.blockContext)))
        }
        val user = ctx.user
        val blocks = mutableListOf<AssistantBlock>()
        val ordered = if (user != null) {
            val (withPoint, without) = places.partition { bundle.location(it)?.coordinate != null }
            withPoint.sortedWith(compareBy<Int> { Geo.distanceMeters(user, bundle.location(it)!!.coordinate!!) }.thenBy { it }) + without
        } else {
            blocks += AssistantBlock.Text("Turn on location to see what's closest.")
            places
        }
        val refs = ordered.map { EntityRef(RefKind.LOCATION, it) }
        if (user != null) {
            val pins = ordered.filter { bundle.location(it)?.coordinate != null }.take(BlockValidator.MAX_PINS)
            if (pins.isNotEmpty()) blocks += AssistantBlock.MapBlock(pins)
        }
        blocks += when {
            refs.size == 1 -> BlockValidator.card(refs.single())
            refs.size <= BlockValidator.MAX_CAROUSEL -> AssistantBlock.Carousel(refs, if (user != null) "Near you" else "Places")
            else -> AssistantBlock.ListBlock(refs, if (user != null) "Near you" else "Places")
        }
        blocks += chips(entityChips(refs.first(), ctx))
        return RoutedTurn(AssistantIntent.NEAR_ME, AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext)))
    }

    // ── Plan views ──────────────────────────────────────────────────────────

    private fun planBlocks(ctx: AssistantContext): List<AssistantBlock> {
        val plan = ctx.plan!!
        val chips = buildList {
            add(Chip("Share my plan", AssistantAction(ActionName.SHARE_PLAN)))
            add(Chip(PlanVersionKind.CHILL.label + " version", AssistantAction.planVersion(PlanVersionKind.CHILL)))
            if (ctx.history.canUndo) add(Chip("Undo", AssistantAction(ActionName.PLAN_UNDO)))
        }
        return listOf(AssistantBlock.Text(Planner.summary(plan, ctx.bundle.event.zone, ctx.locale)), AssistantBlock.PlanBlock(), chips(chips))
    }

    private fun walkingBlocks(plan: Plan, ctx: AssistantContext): List<AssistantBlock> {
        val today = ctx.now.atZone(ctx.bundle.event.zone).toLocalDate()
        val route = DayRoutes.of(plan, ctx.bundle, plan.days.firstOrNull { it.date == today && it.items.isNotEmpty() }?.date)
            ?: return listOf(AssistantBlock.Text("Your plan has no walking yet."))
        val stops = route.stops.map { it.locationId }
        return listOfNotNull(
            AssistantBlock.Text(DayRoutes.totalText(route, today, ctx.units, ctx.locale)),
            AssistantBlock.Summary("walking"),
            stops.takeIf { it.isNotEmpty() }?.let { AssistantBlock.MapBlock(it.distinct(), route = stops) },
        )
    }

    /** `why_not`: the explanation, the item's card and "Swap it in". */
    fun whyNotBlocks(itemId: Int, ctx: AssistantContext): List<AssistantBlock> {
        val answer = WhyNot.explain(itemId, ctx.plan, ctx.bundle, ctx.hearts, ctx.now, ctx.locale) ?: return emptyList()
        return listOfNotNull(
            AssistantBlock.Text(answer.text),
            AssistantBlock.ItemCard(itemId),
            WhyNot.chip(answer, ctx.bundle)?.let { chips(listOf(it)) },
        )
    }

    // ── Toggles ─────────────────────────────────────────────────────────────

    private fun toggle(cmd: String): AssistantAction? {
        val name = when {
            Regex("""\b(remind|reminders?)\b""").containsMatchIn(cmd) -> ActionName.REMINDERS
            Regex("""\b(lock screen|live update|live updates|follow)\b""").containsMatchIn(cmd) -> ActionName.FOLLOW_LOCK_SCREEN
            Regex("""\b(notifications?|notify)\b""").containsMatchIn(cmd) -> ActionName.NOTIFICATIONS
            else -> return null
        }
        val off = Regex("""\b(off|stop|disable|don t)\b|\bno\s""").containsMatchIn("$cmd ")
        return AssistantAction.toggle(name, !off)
    }

    // ── Read actions (model tool calls, chips) ──────────────────────────────

    /**
     * Renders a read action's result with the same layout rules: `search` → [searchBlocks]; `get_item`
     * / `get_location` / `get_update` → the card + chips; `event_info` → a text; `now_next` →
     * `now_next`; `whats_on` → `timeline` (source whats_on); `liked` → the liked list; `plan` → the plan;
     * `near_me` → places by distance; `why_not` → [whyNotBlocks]; `route` → a `map` with the route;
     * `day_route` → the walking summary. Validated; writes → empty.
     */
    fun renderRead(action: AssistantAction, ctx: AssistantContext): List<AssistantBlock> {
        val bundle = ctx.bundle
        val blocks: List<AssistantBlock> = when (action.name) {
            ActionName.SEARCH -> EventSearch.search(bundle, action.string("query").orEmpty(), ctx.now).let { searchBlocks(it, ctx) }
            ActionName.GET_ITEM -> action.id("id", 's')?.let { listOf(AssistantBlock.ItemCard(it), chips(entityChips(EntityRef(RefKind.SCHEDULE, it), ctx))) }.orEmpty()
            ActionName.GET_LOCATION -> action.id("id", 'l')?.let { listOf(AssistantBlock.PlaceCard(it), chips(entityChips(EntityRef(RefKind.LOCATION, it), ctx))) }.orEmpty()
            ActionName.GET_UPDATE -> action.id("id", 'u')?.let { listOf(AssistantBlock.UpdateCard(it)) }.orEmpty()
            ActionName.EVENT_INFO -> listOf(AssistantBlock.Text("**${bundle.event.name}** · ${EventSearch.eventDetail(bundle.event, ctx.locale)}"))
            ActionName.NOW_NEXT -> listOf(AssistantBlock.NowNext(action.id("location", 'l')))
            ActionName.WHATS_ON -> listOf(AssistantBlock.Timeline(source = "whats_on"))
            ActionName.LIKED -> likedBlocks(ctx)
            ActionName.PLAN -> if (ctx.plan != null) planBlocks(ctx) else listOf(AssistantBlock.Text(NO_PLAN_TEXT))
            ActionName.NEAR_ME -> nearMe("near me " + action.string("kind").orEmpty(), ctx).blocks
            ActionName.WHY_NOT -> action.id("item", 's')?.let { whyNotBlocks(it, ctx) }.orEmpty()
            ActionName.ROUTE -> action.id("to_location", 'l')?.let { to ->
                val from = action.id("from", 'l')
                listOf(AssistantBlock.MapBlock(listOfNotNull(from, to).distinct(), route = listOfNotNull(from, to)))
            }.orEmpty()
            ActionName.DAY_ROUTE -> ctx.plan?.let { walkingBlocks(it, ctx) } ?: listOf(AssistantBlock.Text(NO_PLAN_TEXT))
            else -> emptyList()
        }
        return BlockValidator.validate(blocks, ctx.blockContext)
    }
}
