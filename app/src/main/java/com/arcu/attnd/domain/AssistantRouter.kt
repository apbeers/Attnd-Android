package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/** Everything the router (and the model prompt) may use. All local. */
data class AssistantContext(
    val bundle: EventBundle,
    val hearts: Hearts,
    /** My schedule's day view ([MySchedule.dayPlan], one list: derived, never stored). */
    val plan: Plan?,
    val now: Instant,
    /** The user's position, only when location is permitted and known. */
    val user: GeoPoint? = null,
    /** The planner may make plans (model available, not the App Clip). */
    val canPlan: Boolean = true,
    /** Ask (the on-device model) is available; the router is then only the fallback. */
    val modelAvailable: Boolean = false,
    /** The one Undo history over My schedule (one list §3). */
    val history: ScheduleHistory = ScheduleHistory(),
    val units: DistanceUnits = DistanceUnits.IMPERIAL,
    val locale: Locale = Locale.US,
    /** My schedule: timed places, reminders, clashes. [hearts] mirrors its membership. */
    val schedule: MySchedule = MySchedule(),
    /** What the conversation showed ("add it", "the second one"). */
    val memory: ConversationContext = ConversationContext(),
) {
    val blockContext: BlockContext get() = BlockContext(bundle, plan, now)

    /**
     * My schedule as the router sees it: the entries, plus hearts and planned items it doesn't list yet
     * (in the app [plan] is My schedule's day view, so they are the same; tests may pass only hearts and a plan).
     */
    val mine: MySchedule by lazy {
        val merged = schedule.copy()
        for (id in (hearts.scheduleItems + plan?.itemIds.orEmpty()).sorted()) {
            if (ScheduleTarget.item(id) !in merged) merged.add(ScheduleTarget.item(id))
        }
        for (id in hearts.locations.sorted()) if (ScheduleTarget.place(id) !in merged) merged.add(ScheduleTarget.place(id))
        merged
    }
}

enum class AssistantIntent {
    EMPTY, SEARCH, QUESTION, NO_MATCH, PICK, WHATS_ON, NEAR_ME, LIKE, UNLIKE, LIKED,
    PLAN_EDIT, PLAN_UNDO, PLAN_VERSION, PLAN_COMPARE, PLAN_MAKE, PLAN_SHOW, PLAN_NEXT, WALKING, RECAP, NO_PLAN,
    WHY_NOT, TOGGLE, OPEN, SHARE, CALENDAR,

    /** My schedule commands (add / remove / move / remind / what's on my schedule / clashes). */
    SCHEDULE,

    /** An emergency, feeling unwell, a lost child, danger: 911 + the event's help points (never the model). */
    SAFETY,

    /** The organizer's facts: amenities, FAQ / policies, menus, updates, changes, a named entity. */
    FACTS,
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
 * The rule-based assistant (assistant spec §3; every phone; pure), in step with iOS `AssistantRouter.swift`
 * (My schedule grammar, Nov 21, 2026, and the assistant eval's rules, Nov 22, 2026; same cases in
 * `AssistantRouterTest` / `ConversationContextTest` and the shared eval). [route] reads the input as a small
 * command grammar on the folded text (lower case, accents dropped, everything but letters, digits and ':' as
 * spaces; "whats" → "what s"; leading "ok" / "actually" / "so"… dropped), first match wins:
 *  1. empty → EMPTY (suggestion chips).
 *  2. safety (emergencies, feeling unwell, a lost child, danger) → SAFETY: "call 911" + First Aid / info places.
 *  3. "undo" → `plan_undo`.  4. "add <x> to (my) calendar" → "not available" (+ Share my plan).
 *  5. the organizer's facts → FACTS: "how far is X", amenities (bathroom, water, cash, first aid…), menus and
 *     diets, FAQ / policy / weather / getting-there / accessibility / price topics, "when is it" (memory).
 *  6. My schedule ([scheduleCommand]): find <x>; add | put | save | book <x> (to my schedule) (at <time>);
 *     remove | drop | cancel | skip <x>; move <x> to <time>; remind me <n> min before <x> / don't remind me
 *     about <x>; what's on my schedule (<day>); show <x> on the map; directions (to) <x>. Clashes ask
 *     "That overlaps X at 7:00 PM. Replace it or keep both?"; a place without a time is saved at once.
 *  7. plan commands: replace | swap <a> with <b>; move <a> later | earlier; keep / unkeep; already ate;
 *     tired; done by / start at H(:MM); short walks; "i m at <place>"; more | less <tag>; chill / everything
 *     versions; compare versions.
 *  8. "unlike <x>" / "like | heart | love <x>" → My schedule remove / add.
 *  9. toggles and app settings → a `confirm` block (reminders, Lock Screen, notifications, location, leave,
 *     switch server).
 * 10. share my schedule / plan / an item.  11. why isn't <item> in my plan.  12. what's next in my plan.
 * 13. my plan (none: My schedule), walking, recap.  14. the My Schedule list.  15. open a screen.
 * 16. updates / organizer changes / when it ends → FACTS.  17. near me.  18. a plan request → `plan_make`.
 * 19. only time words ("what's on tonight", "sunday schedule", "in an hour") → `timeline`; small talk.
 * 20. a question → results for its keywords + a note; otherwise a search; nothing found → a name inside the
 *     question, everyday synonyms, the FAQ, else NO_MATCH with the suggestion chips.
 * Plan commands without a plan → NO_PLAN. Every turn's blocks go through [BlockValidator]. Names resolve
 * through the conversation memory first, then [resolve].
 */
object AssistantRouter {

    const val ASK_UNAVAILABLE = "Ask needs on-device AI, which isn't available on this phone. Here's what matches."
    const val NO_MATCH_TEXT = "Nothing matches that. Try one of these:"
    const val NO_PLAN_TEXT = "You don't have a plan yet."
    const val CALENDAR_TEXT = "Attnd doesn't add things to your calendar."
    const val EMERGENCY_TEXT = "If anyone is hurt or in danger, call 911 now."
    private val FILLER = setOf("the", "a", "an", "thing", "one", "that", "this", "it", "please")
    private const val MIN_BASE_PER_TOKEN = 3.0
    private val QUESTION_WORDS = setOf("what", "when", "where", "who", "which", "how", "is", "are", "does", "do", "can", "will", "whats")

    private fun matches(pattern: String, text: String) = Regex(pattern).matches(text)
    private fun has(pattern: String, text: String) = Regex(pattern).containsMatchIn(text)
    private fun find(pattern: String, text: String): List<String>? = Regex(pattern).find(text)?.groupValues

    private fun done(intent: AssistantIntent, blocks: List<AssistantBlock>, ctx: AssistantContext, actions: List<AssistantAction> = emptyList(), edit: PlanEdit? = null) =
        RoutedTurn(intent, AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext), actions), edit)

    fun route(input: String, ctx: AssistantContext): RoutedTurn {
        val cmd = withoutLeadingFiller(Regex("""\b(where|what|how|when|who)s\b""").replace(command(input)) { it.groupValues[1] + " s" })
        val plan = ctx.plan

        if (cmd.isEmpty()) return done(AssistantIntent.EMPTY, listOf(AssistantBlock.Chips(suggestionChips(ctx))), ctx)

        // Safety first (the eval, Nov 22, 2026): 911 and the event's help points, never a search.
        safetyTurn(cmd, ctx)?.let { return it }

        if (cmd == "undo" || cmd == "undo that") {
            if (plan == null) return noPlan(ctx)
            return if (ctx.history.canUndo) done(AssistantIntent.PLAN_UNDO, listOf(AssistantBlock.Text("Undid your last schedule change.")), ctx, listOf(AssistantAction(ActionName.PLAN_UNDO)))
            else done(AssistantIntent.PLAN_UNDO, listOf(AssistantBlock.Text("There's nothing to undo.")), ctx)
        }

        if (matches("""(?:add|put|save)\s+(.+?)\s+(?:to|in|on)\s+(?:my\s+)?calendar""", cmd)) {
            val blocks = buildList<AssistantBlock> {
                add(AssistantBlock.Text(CALENDAR_TEXT))
                if (plan != null) add(chips(listOf(Chip("Share my plan", AssistantAction(ActionName.SHARE_PLAN)))))
            }
            return done(AssistantIntent.CALENDAR, blocks, ctx)
        }

        // The organizer's facts (the eval): distances, amenities, menus, FAQ / policy topics.
        (howFarTurn(cmd, ctx) ?: amenityTurn(cmd, ctx) ?: menuTurn(cmd, ctx) ?: topicTurn(cmd, ctx) ?: memoryQuestion(cmd, ctx))?.let { return it }

        scheduleCommand(cmd, ctx)?.let { return it }

        planCommand(cmd, ctx)?.let { return it }

        find("""^(?:unlike|unheart|un like)\s+(.+)$""", cmd)?.let { m -> return like(m[1], liked = false, ctx) }
        find("""^(?:like|heart|love|i like|i love)\s+(.+)$""", cmd)?.let { m -> if (m[1] !in NOT_TARGETS) return like(m[1], liked = true, ctx) }

        (toggle(cmd) ?: settingCommand(cmd))?.let { action ->
            return done(AssistantIntent.TOGGLE, listOf(AssistantBlock.Confirm(action, ActionRegistry.confirmPrompt(action))), ctx)
        }

        if (matches("""share\s+(?:my\s+)?schedule""", cmd)) {
            // My schedule's items as a /p/ link and QR.
            if (ctx.hearts.scheduleItems.isEmpty()) return done(AssistantIntent.SHARE, listOf(AssistantBlock.Text("Add things to your schedule first, then share it.")), ctx)
            return done(AssistantIntent.SHARE, listOf(AssistantBlock.Share("schedule")), ctx)
        }
        if (matches("""share\s+(?:my\s+)?plan""", cmd)) {
            if (plan == null) return noPlan(ctx)
            return done(AssistantIntent.SHARE, listOf(AssistantBlock.Share("plan")), ctx)
        }
        find("""^share\s+(.+)$""", cmd)?.let { m ->
            return withEntity(m[1], setOf(RefKind.SCHEDULE), ctx, AssistantIntent.SHARE, { ref -> Chip(name(ref, ctx), AssistantAction(ActionName.SHARE_ITEM, JsonObject(mapOf("id" to JsonPrimitive(ref.code))))) }) { ref ->
                done(AssistantIntent.SHARE, listOf(AssistantBlock.Share("item:${ref.code}")), ctx)
            }
        }

        find("""^why\s+(?:isn t|isnt|is not|wasn t|not|no)\s+(.+?)(?:\s+in\s+(?:my\s+)?plan)?$""", cmd)?.let { m ->
            return withEntity(m[1], setOf(RefKind.SCHEDULE), ctx, AssistantIntent.WHY_NOT, { ref -> Chip(name(ref, ctx), AssistantAction(ActionName.WHY_NOT, JsonObject(mapOf("item" to JsonPrimitive(ref.code))))) }) { ref ->
                done(AssistantIntent.WHY_NOT, whyNotBlocks(ref.id, ctx), ctx)
            }
        }

        if (has("""\bwhat s next\b|\bnext\b.*\bplan\b""", cmd) && (plan != null || cmd.contains("plan"))) {
            if (plan == null) return noPlan(ctx)
            val next = LiveAssist.nextPlanned(plan, ctx.bundle, ctx.now)
                ?: return done(AssistantIntent.PLAN_NEXT, listOf(AssistantBlock.Text("Nothing else is in your plan.")), ctx)
            val relative = RelativeTime.relativeStart(next.startTime!!.toInstant(), TimeMatcher.effectiveEnd(next), ctx.now, ctx.bundle.event.zone, ctx.locale)
            return done(
                AssistantIntent.PLAN_NEXT,
                listOf(AssistantBlock.Text("Next in your plan: **${next.name}**, $relative."), AssistantBlock.ItemCard(next.id), AssistantBlock.Countdown(next.id), chips(entityChips(EntityRef(RefKind.SCHEDULE, next.id), ctx))),
                ctx,
            )
        }

        if (matches("""(?:(?:show|open|see)\s+)?(?:me\s+)?(?:my\s+)?plan""", cmd) || cmd == "my plan") {
            if (plan == null) return done(AssistantIntent.SCHEDULE, myScheduleBlocks(null, ctx), ctx)
            return done(AssistantIntent.PLAN_SHOW, planBlocks(ctx), ctx)
        }
        if ((has("""\b(how far|how much walking|walking)\b""", cmd) && !cmd.contains("short") && plan != null) || cmd == "how far" || cmd == "how much walking") {
            if (plan == null) return noPlan(ctx)
            return done(AssistantIntent.WALKING, walkingBlocks(plan, ctx), ctx)
        }
        if (has("""\b(recap|how did i do|my day so far)\b""", cmd)) {
            if (plan == null) return noPlan(ctx)
            val today = ctx.now.atZone(ctx.bundle.event.zone).toLocalDate()
            val date = plan.days.lastOrNull { !it.date.isAfter(today) }?.date ?: plan.days.firstOrNull()?.date ?: today
            val recap = LiveAssist.recap(plan, ctx.bundle, ctx.hearts, date, ctx.now, ctx.units, ctx.locale)
            return done(AssistantIntent.RECAP, listOf(AssistantBlock.Text(recap.text), AssistantBlock.Summary("recap"), chips(recap.chips)), ctx)
        }

        if (matches("""liked|likes|my likes|what (?:have|did) i liked?|show (?:me )?(?:my )?likes|what i liked|things i liked|saved|saved places|my saved places|(?:show |see )?(?:me )?(?:my )?(?:saved|favou?rite|hearted) (?:stuff|things|items|list)|my favou?rites|my stuff|my list|what (?:have|did) i saved?""", cmd)) {
            return done(AssistantIntent.LIKED, likedBlocks(ctx), ctx)
        }

        find("""^(?:show|open|go to|take me to|see)\s+(?:me\s+)?(?:the\s+|my\s+)?(map|flyer|schedule|locations|updates|options|settings|tour)$""", cmd)?.let { m ->
            val screen = if (m[1] == "settings") "options" else m[1]
            return done(AssistantIntent.OPEN, listOf(AssistantBlock.Text("Opening ${if (screen == "options") "Options" else "the $screen"}.")), ctx, listOf(AssistantAction.open(screen)))
        }

        (updatesTurn(cmd, ctx) ?: changesTurn(cmd, ctx) ?: endTurn(cmd, ctx))?.let { return it }

        if (has("""\b(near me|nearby|closest|around me|near here)\b""", cmd)) return nearMe(cmd, ctx)

        if (ctx.canPlan && PlanRequest.looksLikePlan(input)) {
            return done(AssistantIntent.PLAN_MAKE, listOf(AssistantBlock.Text("Making your plan.")), ctx, listOf(AssistantAction.planMake(input.trim())))
        }

        val keywords = SearchQuery.keywords(cmd)
        if (SearchQuery.parse(keywords).isTimeOnly) return whatsOn(keywords, input, ctx)
        // "sunday schedule", "what is happening right now", "what's on in an hour".
        val timeWords = timeQuery(cmd, keywords, ctx)
        if (timeWords.isNotEmpty() && SearchQuery.parse(timeWords).isTimeOnly) return whatsOn(timeWords, input, ctx)
        smallTalkTurn(cmd, ctx)?.let { return it }

        val question = input.trim().endsWith("?") || cmd.substringBefore(' ') in QUESTION_WORDS
        val results = EventSearch.search(ctx.bundle, if (question) keywords else cmd, ctx.now)
        if (results.isEmpty()) {
            // A name inside the question, an FAQ answer, a few everyday words ("grub", "edm").
            entityQuestionTurn(cmd, ctx)?.let { return it }
            synonymQuery(cmd)?.let { query ->
                if (SearchQuery.parse(query).isTimeOnly) return whatsOn(query, input, ctx)
                val found = EventSearch.search(ctx.bundle, query, ctx.now)
                if (found.isNotEmpty()) return done(AssistantIntent.SEARCH, searchBlocks(found, ctx), ctx)
            }
            topicTurn(cmd, ctx, faqOnly = true)?.let { return it }
            return done(AssistantIntent.NO_MATCH, listOf(AssistantBlock.Text(NO_MATCH_TEXT), AssistantBlock.Chips(suggestionChips(ctx))), ctx)
        }
        val blocks = searchBlocks(results, ctx)
        return if (question) {
            done(AssistantIntent.QUESTION, listOf(AssistantBlock.Text(if (ctx.modelAvailable) "Here's what matches." else ASK_UNAVAILABLE)) + blocks, ctx)
        } else {
            done(AssistantIntent.SEARCH, blocks, ctx)
        }
    }

    /** Lower case, accents dropped, everything but letters, digits and ':' → spaces, collapsed. */
    fun command(input: String): String =
        SearchText.fold(input).replace(Regex("""[^\p{L}\p{N}:]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    /** Discourse words in front of a command ("actually remove it", "ok add it", "so what's next"). */
    fun withoutLeadingFiller(cmd: String): String {
        val stripped = cmd.replace(Regex("""^(?:(?:actually|ok|okay|hey|so|um|uh|oh|well|also|and|hmm|wait|cool|great|thanks|alright|then)\s+)+"""), "")
        return stripped.ifEmpty { cmd }
    }

    // ── My schedule ─────────────────────────────────────────────────────────

    /** "my schedule", "my plan", "my itinerary", "my agenda", "my day". */
    private const val SCHEDULE_NOUN = """(?:my\s+)?(?:schedule|plan|itinerary|agenda|day)"""
    private const val CLOCK = """(\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?"""
    private val MEAL_WORDS = setOf("dinner", "lunch", "breakfast", "brunch", "food", "eat", "something to eat", "a bite", "snack", "snacks", "drinks")
    private val PLURAL_REFS = setOf("them", "both", "both of them", "those", "these", "all of them", "all of those", "all three", "all")

    /**
     * The My schedule grammar (before the plan rules): find <x>; add / put / save / book <x> (to my schedule)
     * (at <time>); remove / drop / cancel / skip <x> (from my schedule); move <x> to <time>; remind me <n> min
     * before <x> / remind me about <x> <n> min before / don't remind me about <x>; what's on my schedule
     * (<day>); show <x> on the map; directions (to) <x>. Names resolve through the conversation memory first.
     */
    fun scheduleCommand(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val kinds = setOf(RefKind.SCHEDULE, RefKind.LOCATION)
        find("""^(?:find|search for|look for|looking for|i want|i need|get me)\s+(.+)$""", cmd)?.let { m ->
            val phrase = m[1].trim().replace(Regex("""^(?:to\s+)?(?:(?:see|hear|watch|catch|try|check out)\s+)?(?:(?:some|a|an|the)\s+)?"""), "")
            val query = if (phrase.replace(Regex("""^(?:some|a|an|the)\s+"""), "") in MEAL_WORDS) "food" else SearchQuery.keywords(phrase)
            val results = EventSearch.search(ctx.bundle, query, ctx.now)
            if (results.isEmpty()) return done(AssistantIntent.NO_MATCH, listOf(AssistantBlock.Text(NO_MATCH_TEXT), AssistantBlock.Chips(suggestionChips(ctx))), ctx)
            return done(AssistantIntent.SEARCH, searchBlocks(results, ctx), ctx)
        }
        find("""^(?:add|put|save|book|schedule)\s+(.+?)(?:\s+(?:to|in|on|into)\s+$SCHEDULE_NOUN)?(?:\s+(?:at|for)\s+$CLOCK)?$""", cmd)?.let { m ->
            val time = if (m[2].isEmpty()) null else clock(m[2], m[3], m[4], ctx)
            if (m[1].endsWith(" calendar")) return null
            return withScheduleEntity(m[1], kinds, ctx) { refs -> if (refs.size == 1) add(refs[0], time, ctx) else addMany(refs, ctx) }
        }
        find("""^(?:remove|drop|cancel|skip|delete|take off|take out)\s+(.+?)(?:\s+(?:from|off|out of)\s+$SCHEDULE_NOUN)?$""", cmd)?.let { m ->
            return withScheduleEntity(m[1], kinds, ctx, preferScheduled = true) { refs -> remove(refs, ctx) }
        }
        find("""^(?:move|change|reschedule|push|switch)\s+(.+?)\s+to\s+$CLOCK$""", cmd)?.let { m ->
            val time = clock(m[2], m[3], m[4], ctx) ?: return null
            return withScheduleEntity(m[1], kinds, ctx, preferScheduled = true) { refs -> move(refs[0], time, ctx) }
        }
        find("""^remind me\s+(\d{1,3})\s*(min|mins|minutes|m|hour|hours|h)?\s+before\s+(.+)$""", cmd)?.let { m ->
            val minutes = (m[1].toIntOrNull() ?: 15) * (if (m[2].startsWith("h")) 60 else 1)
            return withScheduleEntity(m[3], kinds, ctx, preferScheduled = true) { refs -> remind(refs[0], minutes, ctx) }
        }
        find("""^remind me\s+(?:about|of|before)\s+(.+?)(?:\s+(\d{1,3})\s*(min|mins|minutes|m|hour|hours|h)?\s+before)?$""", cmd)?.let { m ->
            val minutes = if (m[2].isEmpty()) MySchedule.DEFAULT_REMINDER else (m[2].toIntOrNull() ?: 15) * (if (m[3].startsWith("h")) 60 else 1)
            return withScheduleEntity(m[1], kinds, ctx, preferScheduled = true) { refs -> remind(refs[0], minutes, ctx) }
        }
        find("""^(?:don t|do not|dont|stop|no)\s+remind(?:ing)?\s+me\s+(?:about|of)\s+(.+)$""", cmd)?.let { m ->
            return withScheduleEntity(m[1], kinds, ctx, preferScheduled = true) { refs -> remind(refs[0], 0, ctx) }
        }
        find("""^(?:what s|whats|what is|what do i have|show|show me|see)?\s*(?:on|in)?\s*my\s+(?:schedule|itinerary|agenda)(?:\s+(?:for\s+|on\s+)?(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun))?$""", cmd)?.let { m ->
            return done(AssistantIntent.SCHEDULE, myScheduleBlocks(if (m[1].isEmpty()) null else day(m[1], ctx), ctx), ctx)
        }
        find("""^(?:show|see|put|find)\s+(.+?)\s+on\s+(?:the\s+)?map$""", cmd)?.let { m ->
            return withScheduleEntity(m[1], kinds, ctx) { refs ->
                val place = placeOf(refs[0], ctx) ?: return@withScheduleEntity done(AssistantIntent.OPEN, listOf(AssistantBlock.Text("${name(refs[0], ctx)} has no place on the map.")), ctx)
                done(AssistantIntent.OPEN, listOf(AssistantBlock.Text("Showing ${ctx.bundle.location(place)?.name ?: "it"} on the map.")), ctx, listOf(AssistantAction.open("map", EntityRef(RefKind.LOCATION, place))))
            }
        }
        find("""^(?:directions|get directions|walking directions|walk me|take me|how do i get)(?:\s+(?:to|there|here))?\s*(.*)$""", cmd)?.let { m ->
            val phrase = m[1].ifEmpty { "there" }
            return withScheduleEntity(phrase, kinds, ctx) { refs ->
                val place = placeOf(refs[0], ctx)?.takeIf { ctx.bundle.location(it)?.coordinate != null }
                    ?: return@withScheduleEntity done(AssistantIntent.OPEN, listOf(AssistantBlock.Text("There's no map point for ${name(refs[0], ctx)}.")), ctx)
                done(AssistantIntent.OPEN, listOf(AssistantBlock.Text("Directions to ${ctx.bundle.location(place)?.name ?: "it"}.")), ctx, listOf(AssistantAction.directions(place)))
            }
        }
        return null
    }

    /** The place of an entity: a place itself, an item's location. */
    private fun placeOf(ref: EntityRef, ctx: AssistantContext): Int? = when (ref.kind) {
        RefKind.LOCATION -> ref.id
        RefKind.SCHEDULE -> ctx.bundle.scheduleItem(ref.id)?.locationId?.let { ctx.bundle.location(it)?.id }
        RefKind.UPDATE -> null
    }

    /** Resolves for My schedule: memory first; with [preferScheduled], entries win when any match. */
    private fun withScheduleEntity(phrase: String, kinds: Set<RefKind>, ctx: AssistantContext, preferScheduled: Boolean = false, found: (List<EntityRef>) -> RoutedTurn): RoutedTurn {
        ctx.memory.resolve(phrase, kinds, ctx.bundle)?.takeIf { it.isNotEmpty() }?.let { refs ->
            if (refs.size == 1 || command(phrase) in PLURAL_REFS) return found(refs)
            return pick(refs, ctx, AssistantIntent.SCHEDULE) { ref -> Chip(name(ref, ctx), AssistantAction.scheduleAdd(ref)) }
        }
        return when (val r = resolve(phrase, kinds, ctx)) {
            is Resolution.Found -> found(listOf(r.ref))
            is Resolution.Ambiguous -> {
                if (preferScheduled) {
                    val scheduled = r.refs.filter { target(it) in ctx.mine }
                    if (scheduled.size == 1) return found(scheduled)
                }
                // One act or demo at several times ("add Sundown Choir"): the one on now, else the next one.
                sameNameNext(r.refs, ctx)?.let { return found(listOf(it)) }
                pick(r.refs, ctx, AssistantIntent.SCHEDULE) { ref -> Chip(name(ref, ctx), AssistantAction.scheduleAdd(ref)) }
            }
            Resolution.NotFound -> {
                // Small typos ("prairie lihgts"): the search's best result when it is the only one on top.
                val words = command(phrase).split(' ').filter { it.isNotEmpty() && it !in FILLER }
                val results = EventSearch.search(ctx.bundle, words.joinToString(" "), ctx.now).filter { r -> RefKind.of(r.kind)?.let { it in kinds } == true }
                val first = results.firstOrNull()
                val ref = first?.let { EntityRef.of(it) }
                if (ref != null && words.isNotEmpty() && (results.getOrNull(1)?.let { base(it) < base(first) } != false)) found(listOf(ref))
                else notFound(phrase, ctx)
            }
        }
    }

    fun target(ref: EntityRef): ScheduleTarget = ScheduleTarget(ref.kind.searchKind, ref.id)

    private fun clockText(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)

    private fun time(at: Instant, ctx: AssistantContext): String = at.atZone(ctx.bundle.event.zone).format(DateTimeFormatter.ofPattern("h:mm a", ctx.locale))

    /** "Added X" / the clash question; a place without a time is saved (with "Pick a time" chips). */
    private fun add(ref: EntityRef, clock: LocalTime?, ctx: AssistantContext): RoutedTurn {
        val bundle = ctx.bundle
        val entry = ctx.mine.entry(target(ref))
        val card = BlockValidator.card(ref)
        return when (ref.kind) {
            RefKind.SCHEDULE -> {
                if (entry != null) return done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("**${name(ref, ctx)}** is already in your schedule."), card, chips(entityChips(ref, ctx))), ctx)
                ctx.mine.span(target(ref), bundle)?.let { (start, end) ->
                    ctx.mine.clashes(start, end, bundle, ctx.now, excluding = target(ref)).firstOrNull()?.let { return clashTurn(ref, it, null, ctx) }
                }
                done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("Added **${name(ref, ctx)}** to your schedule."), card, chips(addedChips(ref, ctx))), ctx, listOf(AssistantAction.scheduleAdd(ref)))
            }
            RefKind.LOCATION -> {
                if (clock == null) {
                    if (entry != null) {
                        val at = entry.at?.let { " at " + time(it, ctx) }.orEmpty()
                        return done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("**${name(ref, ctx)}** is already in your schedule$at."), card, chips(entityChips(ref, ctx))), ctx)
                    }
                    // Saved at once without a time (owner, Nov 21, 2026: no "When?"); the chips offer a time.
                    val options = ctx.mine.suggestedTimes(ref.id, bundle, ctx.now, walkMinutes(ref.id, ctx))
                    val times = options.times.take(2).toMutableList()
                    options.suggested?.let { if (it !in times) times.add(0, it) }
                    val list = times.take(2).map { at ->
                        Chip("Pick a time: " + time(at, ctx), AssistantAction.scheduleMove(ref, clockText(at.atZone(bundle.event.zone).toLocalTime())))
                    } + addedChips(ref, ctx).filter { it.action.name != ActionName.SCHEDULE_REMINDER }
                    return done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("Saved **${name(ref, ctx)}** to your schedule."), card, chips(list)), ctx, listOf(AssistantAction.scheduleAdd(ref)))
                }
                val at = MySchedule.date(clock, null, ctx.now, bundle.event.zone)
                ctx.mine.span(target(ref), bundle, at)?.let { (start, end) ->
                    ctx.mine.clashes(start, end, bundle, ctx.now, excluding = target(ref)).firstOrNull()?.let { return clashTurn(ref, it, clock, ctx) }
                }
                val verb = if (entry == null) "Added" else "Moved"
                done(
                    AssistantIntent.SCHEDULE,
                    listOf(AssistantBlock.Text("$verb **${name(ref, ctx)}** ${if (entry == null) "to your schedule at" else "to"} ${time(at, ctx)}."), card, chips(addedChips(ref, ctx))),
                    ctx, listOf(AssistantAction.scheduleAdd(ref, clockText(clock))),
                )
            }
            RefKind.UPDATE -> done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("Updates can't go in your schedule.")), ctx)
        }
    }

    /** "That overlaps X at 7:00 PM. Replace it or keep both?" */
    private fun clashTurn(ref: EntityRef, clash: MyScheduleRow, clock: LocalTime?, ctx: AssistantContext): RoutedTurn {
        val other = EntityRef(if (clash.entry.kind == ScheduleEntryKind.ITEM) RefKind.SCHEDULE else RefKind.LOCATION, clash.entry.id)
        val timeText = clock?.let(::clockText)
        return done(
            AssistantIntent.SCHEDULE,
            listOf(
                AssistantBlock.Text("That overlaps **${clash.name}** at ${time(clash.start, ctx)}. Replace it or keep both?"),
                BlockValidator.card(ref),
                chips(listOf(Chip(ChipText.fit("Replace ", clash.name), AssistantAction.scheduleAdd(ref, timeText, other)), Chip("Keep both", AssistantAction.scheduleAdd(ref, timeText)))),
            ),
            ctx,
        )
    }

    private fun addMany(refs: List<EntityRef>, ctx: AssistantContext): RoutedTurn {
        val new = refs.filter { it.kind != RefKind.UPDATE && target(it) !in ctx.mine }
        if (new.isEmpty()) return done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("They're already in your schedule.")), ctx)
        return done(
            AssistantIntent.SCHEDULE,
            listOf(AssistantBlock.Text("Added ${names(new.map { "**${name(it, ctx)}**" })} to your schedule."), AssistantBlock.ListBlock(new, MySchedule.TITLE)),
            ctx, new.map { AssistantAction.scheduleAdd(it) },
        )
    }

    private fun remove(refs: List<EntityRef>, ctx: AssistantContext): RoutedTurn {
        val inSchedule = refs.filter { target(it) in ctx.mine }
        if (inSchedule.isEmpty()) return done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("**${name(refs[0], ctx)}** isn't in your schedule.")), ctx)
        return done(
            AssistantIntent.SCHEDULE,
            listOf(AssistantBlock.Text("Removed ${names(inSchedule.map { "**${name(it, ctx)}**" })} from your schedule.")),
            ctx, inSchedule.map { AssistantAction.scheduleRemove(it) },
        )
    }

    private fun move(ref: EntityRef, clock: LocalTime, ctx: AssistantContext): RoutedTurn {
        val bundle = ctx.bundle
        return when (ref.kind) {
            RefKind.LOCATION -> {
                val entry = ctx.mine.entry(target(ref)) ?: return add(ref, clock, ctx)
                val at = MySchedule.date(clock, entry.at, ctx.now, bundle.event.zone)
                done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("Moved **${name(ref, ctx)}** to ${time(at, ctx)}."), BlockValidator.card(ref)), ctx, listOf(AssistantAction.scheduleMove(ref, clockText(clock))))
            }
            RefKind.SCHEDULE -> {
                val start = bundle.scheduleItem(ref.id)?.startTime?.let { " (${time(it.toInstant(), ctx)})" }.orEmpty()
                val list = if (target(ref) in ctx.mine) listOf(Chip("Remove it", AssistantAction.scheduleRemove(ref))) else emptyList()
                done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("**${name(ref, ctx)}** is at a set time$start; the organizer decides when it is."), BlockValidator.card(ref), chips(list)), ctx)
            }
            RefKind.UPDATE -> done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text("Updates can't go in your schedule.")), ctx)
        }
    }

    private fun remind(ref: EntityRef, minutes: Int, ctx: AssistantContext): RoutedTurn {
        val value = if (minutes == 0) 0 else MySchedule.nearestReminder(minutes)
        val text = if (value == 0) "No reminder for **${name(ref, ctx)}**." else "I'll remind you ${MySchedule.reminderText(value)} **${name(ref, ctx)}**."
        if (target(ref) in ctx.mine) return done(AssistantIntent.SCHEDULE, listOf(AssistantBlock.Text(text)), ctx, listOf(AssistantAction.scheduleReminder(ref, value)))
        if (ref.kind != RefKind.SCHEDULE) return add(ref, null, ctx)
        return done(
            AssistantIntent.SCHEDULE,
            listOf(AssistantBlock.Text("Added **${name(ref, ctx)}** to your schedule. $text"), BlockValidator.card(ref)),
            ctx, listOf(AssistantAction.scheduleAdd(ref), AssistantAction.scheduleReminder(ref, value)),
        )
    }

    private fun addedChips(ref: EntityRef, ctx: AssistantContext): List<Chip> = buildList {
        add(Chip("Remind me 30 min before", AssistantAction.scheduleReminder(ref, 30)))
        add(Chip("Show my schedule", AssistantAction(ActionName.MY_SCHEDULE)))
        placeOf(ref, ctx)?.takeIf { ctx.bundle.location(it)?.coordinate != null }?.let { place ->
            add(Chip("Show on map", AssistantAction.open("map", EntityRef(RefKind.LOCATION, place))))
        }
    }

    private fun walkMinutes(locationId: Int, ctx: AssistantContext): Int? {
        val user = ctx.user ?: return null
        val point = ctx.bundle.location(locationId)?.coordinate ?: return null
        val meters = Geo.distanceMeters(user, point)
        return if (Walking.isNearby(meters)) Walking.minutes(Walking.estimateSeconds(meters)).toInt() else null
    }

    /** today / tonight / tomorrow / a weekday → the event day (the next one on or after today). */
    private fun day(word: String, ctx: AssistantContext): LocalDate? {
        val today = ctx.now.atZone(ctx.bundle.event.zone).toLocalDate()
        return when (word) {
            "today", "tonight" -> today
            "tomorrow" -> today.plusDays(1)
            else -> {
                val wanted = DayOfWeek.entries.firstOrNull { it.getDisplayName(TextStyle.SHORT, Locale.US).lowercase() == word.take(3) } ?: return null
                (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == wanted }
            }
        }
    }

    /** "What's on my schedule (Saturday)": the timed entries (that day, else from now) and saved places. */
    fun myScheduleBlocks(day: LocalDate?, ctx: AssistantContext): List<AssistantBlock> {
        val bundle = ctx.bundle
        val rows = day?.let { ctx.mine.rows(it, bundle, ctx.now) } ?: ctx.mine.rows(bundle, ctx.now).filter { !it.ended }
        val refs = rows.map { EntityRef(if (it.entry.kind == ScheduleEntryKind.ITEM) RefKind.SCHEDULE else RefKind.LOCATION, it.entry.id) }
        val saved = if (day == null) ctx.mine.savedPlaceIds.filter { bundle.location(it) != null }.map { EntityRef(RefKind.LOCATION, it) } else emptyList()
        val planChip = if (ctx.canPlan) listOf(Chip("Plan my day", AssistantAction.planMake("Plan my day"))) else emptyList()
        if (refs.isEmpty() && saved.isEmpty()) {
            return listOf(
                AssistantBlock.Text(if (day == null) "Your schedule is empty. Add things with + or ask me." else "Nothing in your schedule that day."),
                chips(planChip + suggestionChips(ctx)),
            )
        }
        val whenText = day?.let { " on " + it.dayOfWeek.getDisplayName(TextStyle.FULL, ctx.locale) }.orEmpty()
        return buildList {
            add(AssistantBlock.Text(if (refs.size == 1) "1 thing in your schedule$whenText." else "${refs.size} things in your schedule$whenText."))
            if (refs.isNotEmpty()) add(AssistantBlock.ListBlock(refs, MySchedule.TITLE))
            if (saved.isNotEmpty()) add(AssistantBlock.ListBlock(saved, "Saved Places"))
            add(chips(planChip))
        }
    }

    // ── Plan commands ───────────────────────────────────────────────────────

    private fun planCommand(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val bundle = ctx.bundle
        fun op(ops: List<PlanOp>) = edit(ops, ctx)
        fun item(phrase: String, makeOps: (Int) -> List<PlanOp>): RoutedTurn {
            if (ctx.plan == null) return noPlan(ctx)
            return withEntity(phrase, setOf(RefKind.SCHEDULE), ctx, AssistantIntent.PLAN_EDIT, { ref ->
                Chip(name(ref, ctx), AssistantAction.planEdit(makeOps(ref.id), bundle))
            }, preferPlanned = true) { ref -> op(makeOps(ref.id)) }
        }

        find("""^(?:replace|swap)\s+(.+?)\s+(?:with|for)\s+(.+)$""", cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            val target = m[2]
            val likeMatch = find("""^(?:something|anything|one|some)\s+(?:like|similar to)\s+(.+)$""", target)
            val tagPhrase = find("""^(?:something|some|more)\s+(.+)$""", target)?.get(1) ?: target
            val tag = tagOf(tagPhrase, bundle)
            val replacement: (Int) -> List<PlanOp>
            if (likeMatch != null) {
                val like = resolve(likeMatch[1], setOf(RefKind.SCHEDULE), ctx)
                val likeId = (like as? Resolution.Found)?.ref?.id ?: (like as? Resolution.Ambiguous)?.refs?.firstOrNull()?.id
                    ?: return notFound(likeMatch[1], ctx)
                replacement = { listOf(PlanOp.Replace(it, like = likeId)) }
            } else if (tag != null) {
                replacement = { listOf(PlanOp.Replace(it, tag = tag)) }
            } else {
                val withId = when (val with = resolve(target, setOf(RefKind.SCHEDULE), ctx)) {
                    is Resolution.Found -> with.ref.id
                    is Resolution.Ambiguous -> return pick(with.refs, ctx, AssistantIntent.PLAN_EDIT) { ref ->
                        val from = (resolve(m[1], setOf(RefKind.SCHEDULE), ctx, preferPlanned = true) as? Resolution.Found)?.ref?.id
                        Chip(name(ref, ctx), AssistantAction.planEdit(listOfNotNull(from?.let { PlanOp.Replace(it, with = ref.id) }).ifEmpty { listOf(PlanOp.Add(ref.id)) }, bundle))
                    }
                    Resolution.NotFound -> return notFound(target, ctx)
                }
                replacement = { listOf(PlanOp.Replace(it, with = withId)) }
            }
            return item(m[1], replacement)
        }
        // "remove X" / "add X" are My schedule commands ([scheduleCommand]), which the plan follows.
        find("""^move\s+(.+?)\s+(later|earlier)$""", cmd)?.let { m ->
            val later = m[2] == "later"
            return item(m[1]) { listOf(PlanOp.Move(it, later)) }
        }
        find("""^(?:unkeep|unpin|don t keep)\s+(.+)$""", cmd)?.let { m -> return item(m[1]) { listOf(PlanOp.Unkeep(it)) } }
        find("""^keep\s+(.+)$""", cmd)?.let { m -> return item(m[1]) { listOf(PlanOp.Keep(it)) } }

        if (has("""\b(already ate|just ate|i ate|had lunch|had dinner|had food|ate already)\b""", cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.NoFoodUntil(ctx.now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofHours(3)))))
        }
        if (has("""\b(i m tired|im tired|i am tired|tired|exhausted|slow down|take it easy)\b""", cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.Pace(PlanPace.RELAXED)))
        }
        find("""\b(?:done|finish|finished|home|back|out|leave|leaving)\s+(?:by|at|before)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?\b""", cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            val time = clock(m[1], m[2], m[3], ctx) ?: return null
            return op(listOf(PlanOp.DoneBy(time)))
        }
        find("""\b(?:start|begin|starting)\s+(?:at|after|from|around)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?\b""", cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            val time = clock(m[1], m[2], m[3], ctx) ?: return null
            return op(listOf(PlanOp.StartAt(time)))
        }
        find("""\bwalks? (?:under|less than|up to|of at most) (\d{1,2}) ?(?:min|mins|minutes)\b""", cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.MaxWalk(m[1].toInt().coerceAtLeast(1))))
        }
        if (has("""\b(short walks?|less walking|walk less|shorter walks|no long walks|don t want to walk)\b""", cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return op(listOf(PlanOp.MaxWalk(PlanVersionKind.CHILL_MAX_WALK)))
        }
        find("""^(?:i m|im|i am)\s+(?:at|by|near|in)\s+(.+)$""", cmd)?.let { m ->
            if (ctx.plan == null) return noPlan(ctx)
            return withEntity(m[1], setOf(RefKind.LOCATION), ctx, AssistantIntent.PLAN_EDIT, { ref ->
                Chip(name(ref, ctx), AssistantAction.planEdit(listOf(PlanOp.FromLocation(ref.id)), bundle))
            }) { ref -> op(listOf(PlanOp.FromLocation(ref.id))) }
        }
        find("""^(?:more|extra|lots of)\s+(.+)$""", cmd)?.let { m ->
            tagOf(m[1], bundle)?.let { tag -> return if (ctx.plan == null) noPlan(ctx) else op(listOf(PlanOp.MoreTag(tag))) }
        }
        find("""^(?:less|fewer|no more|not so much)\s+(.+)$""", cmd)?.let { m ->
            tagOf(m[1], bundle)?.let { tag -> return if (ctx.plan == null) noPlan(ctx) else op(listOf(PlanOp.LessTag(tag))) }
        }
        val version = when {
            has("""\b(chill|relaxed|easy) (version|plan|day)\b""", cmd) -> PlanVersionKind.CHILL
            has("""\b(see everything|see it all|packed version|packed plan|everything version)\b""", cmd) -> PlanVersionKind.EVERYTHING
            else -> null
        }
        // "plan a chill day" without a plan is a plan request.
        if (version != null && ctx.plan == null && ctx.canPlan && PlanRequest.looksLikePlan(cmd)) return null
        if (version != null) {
            val plan = ctx.plan ?: return noPlan(ctx)
            val preview = PlanVersions.make(version, plan, bundle, ctx.hearts, ctx.now)
            return done(AssistantIntent.PLAN_VERSION, listOf(AssistantBlock.Text("${version.label}: ${describe(preview, bundle)}"), AssistantBlock.PlanDiffBlock(preview.diff), editChips()), ctx, listOf(AssistantAction.planVersion(version)), preview)
        }
        if (has("""\bcompare\b.*\b(versions?|plans?)\b""", cmd)) {
            if (ctx.plan == null) return noPlan(ctx)
            return done(AssistantIntent.PLAN_COMPARE, listOf(AssistantBlock.Compare("plan:chill", "plan:everything")), ctx)
        }
        return null
    }

    private fun edit(ops: List<PlanOp>, ctx: AssistantContext): RoutedTurn {
        val plan = ctx.plan ?: return noPlan(ctx)
        val preview = PlanEditor.preview(plan, ops, ctx.bundle, ctx.hearts, ctx.now)
        return done(AssistantIntent.PLAN_EDIT, listOf(AssistantBlock.Text(describe(preview, ctx.bundle)), AssistantBlock.PlanDiffBlock(preview.diff), editChips()), ctx, listOf(AssistantAction.planEdit(ops, ctx.bundle)), preview)
    }

    private fun editChips() = AssistantBlock.Chips(listOf(Chip("Undo", AssistantAction(ActionName.PLAN_UNDO)), Chip("Show my plan", AssistantAction(ActionName.PLAN))))

    /**
     * The sentence above a `plan_diff`: "Moved X to 8:00 PM." "Removed A and B." "Added C." "No lunch
     * break." "Added a dinner break." "Moved the lunch break." then each note + "."; "Your plan stays
     * the same." when nothing changed and there are no notes.
     */
    fun describe(edit: PlanEdit, bundle: EventBundle, locale: Locale = Locale.US): String {
        val diff = edit.diff
        fun n(id: Int) = bundle.scheduleItem(id)?.name ?: "an item"
        val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
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

    /** am/pm as given (12 am = 00); 13–23 as is; a bare 1–11 is the morning time when still ahead (event zone), else the evening; 12 is noon. */
    private fun clock(hourText: String, minuteText: String, suffixText: String, ctx: AssistantContext): LocalTime? {
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val suffix = suffixText.replace(" ", "")
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
     * Resolves a name in a command: references to what the conversation showed first ("it", "the second
     * one"); else filler words ("the", "a", "an", "thing", "one", "that", "this", "it", "please") are dropped
     * and the rest searched ([EventSearch], all tokens) among [kinds]; with [preferPlanned] and a plan,
     * planned schedule items win when any match. Base score = score without the now / next / ended boosts.
     * Found = the top result when its base ≥ 3 × the number of text tokens (a time-only phrase like "3pm"
     * needs no score) and no other result has the same base; Ambiguous = up to 5 results tied at the top
     * base; otherwise NotFound.
     */
    fun resolve(phrase: String, kinds: Set<RefKind>, ctx: AssistantContext, preferPlanned: Boolean = false): Resolution {
        ctx.memory.resolve(phrase, kinds, ctx.bundle)?.let { refs ->
            return if (refs.size == 1) Resolution.Found(refs.single()) else Resolution.Ambiguous(refs.take(5))
        }
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
        val refs = scored.filter { it.second == top }.mapNotNull { EntityRef.of(it.first) }
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
        return RoutedTurn(AssistantIntent.PICK, AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text("Which one do you mean?"), AssistantBlock.Chips(chips)), ctx.blockContext)))
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
        return RoutedTurn(AssistantIntent.NO_PLAN, AssistantTurn(BlockValidator.validate(listOf(AssistantBlock.Text(NO_PLAN_TEXT), AssistantBlock.Chips(chips)), ctx.blockContext)))
    }

    private fun name(ref: EntityRef, ctx: AssistantContext): String = ref.name(ctx.bundle) ?: ref.code

    private fun shortDetail(ref: EntityRef, ctx: AssistantContext): String = when (ref.kind) {
        RefKind.SCHEDULE -> ctx.bundle.scheduleItem(ref.id)?.startTime?.let { it.atZoneSameInstant(ctx.bundle.event.zone).format(DateTimeFormatter.ofPattern("EEE h:mm a", ctx.locale)) }.orEmpty()
        RefKind.LOCATION -> ctx.bundle.location(ref.id)?.category.orEmpty()
        RefKind.UPDATE -> ""
    }

    // ── Likes (My schedule) ─────────────────────────────────────────────────

    /** "like X" / "unlike X" (older words): add to / remove from My schedule. */
    private fun like(phrase: String, liked: Boolean, ctx: AssistantContext): RoutedTurn =
        withScheduleEntity(phrase, setOf(RefKind.SCHEDULE, RefKind.LOCATION), ctx, preferScheduled = !liked) { refs ->
            if (liked) add(refs[0], null, ctx) else remove(refs, ctx)
        }

    private fun likedBlocks(ctx: AssistantContext): List<AssistantBlock> {
        val refs = EventSearch.hearted(ctx.bundle, ctx.hearts, ctx.now).mapNotNull { EntityRef.of(it) }
        if (refs.isEmpty()) return listOf(AssistantBlock.Text("Your schedule is empty."), AssistantBlock.Chips(suggestionChips(ctx)))
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
     * Follow-ups for one entity, in order. Schedule item: "Add to my schedule" / "Remove from my schedule";
     * "Show on map" (its location has coordinates). Place: the same add / remove; "Show on map" and
     * "Directions" (with coordinates). Update: "All updates".
     */
    fun entityChips(ref: EntityRef, ctx: AssistantContext): List<Chip> {
        val bundle = ctx.bundle
        return when (ref.kind) {
            RefKind.SCHEDULE -> {
                val item = bundle.scheduleItem(ref.id) ?: return emptyList()
                buildList {
                    add(scheduleChip(ref, ctx))
                    bundle.location(item.locationId)?.takeIf { it.coordinate != null }?.let {
                        add(Chip("Show on map", AssistantAction.open("map", EntityRef(RefKind.LOCATION, it.id))))
                    }
                }
            }
            RefKind.LOCATION -> {
                val location = bundle.location(ref.id) ?: return emptyList()
                buildList {
                    add(scheduleChip(ref, ctx))
                    if (location.coordinate != null) {
                        add(Chip("Show on map", AssistantAction.open("map", ref)))
                        add(Chip("Directions", AssistantAction.directions(location.id)))
                    }
                }
            }
            RefKind.UPDATE -> listOf(Chip("All updates", AssistantAction.open("updates")))
        }
    }

    /** "Add to my schedule" / "Remove from my schedule" (was Like / Unlike). */
    private fun scheduleChip(ref: EntityRef, ctx: AssistantContext): Chip =
        if (target(ref) in ctx.mine) Chip("Remove from my schedule", AssistantAction.scheduleRemove(ref))
        else Chip("Add to my schedule", AssistantAction.scheduleAdd(ref))

    /**
     * The empty-state chips, ≤ 5 in order: "Happening now" (something is in progress), "Up next"
     * (something starts within 2 h), "My Schedule" (anything in it), then the categories (each a search).
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
        return done(AssistantIntent.WHATS_ON, blocks, ctx)
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
        if (places.isEmpty()) return done(AssistantIntent.NO_MATCH, listOf(AssistantBlock.Text(NO_MATCH_TEXT), AssistantBlock.Chips(suggestionChips(ctx))), ctx)
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
        return done(AssistantIntent.NEAR_ME, blocks, ctx)
    }

    // ── Plan views ──────────────────────────────────────────────────────────

    private fun planBlocks(ctx: AssistantContext): List<AssistantBlock> {
        val plan = ctx.plan ?: return emptyList()
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

    // ── Toggles and app settings ────────────────────────────────────────────

    private fun toggle(cmd: String): AssistantAction? {
        val name = when {
            has("""\b(remind|reminders?)\b""", cmd) -> ActionName.REMINDERS
            has("""\b(lock screen|live update|live updates|follow)\b""", cmd) -> ActionName.FOLLOW_LOCK_SCREEN
            has("""\b(notifications?|notify)\b""", cmd) -> ActionName.NOTIFICATIONS
            else -> return null
        }
        val off = has("""\b(off|stop|disable|don t)\b|\bno\s""", "$cmd ")
        return AssistantAction.toggle(name, !off)
    }

    /** "show my location" / "hide my location", "leave (this) event", "switch to the dev server": always confirmed first. */
    fun settingCommand(cmd: String): AssistantAction? {
        if (matches("""(?:please\s+)?(?:leave|exit|quit)(?:\s+(?:this|the))?\s+event""", cmd)) return AssistantAction(ActionName.LEAVE)
        find("""\b(?:switch|change|move)\b.*\b(dev|development|test|prod|production|live)\b\s+server""", cmd)?.let { m ->
            val server = if (m[1] in setOf("dev", "development", "test")) "dev" else "production"
            return AssistantAction(ActionName.SWITCH_SERVER, JsonObject(mapOf("server" to JsonPrimitive(server))))
        }
        if (has("""\b(?:my\s+)?location\b""", cmd) && has("""\b(show|hide|turn|share|stop|use|enable|disable)\b""", cmd)) {
            val off = has("""\b(hide|off|stop|disable|don t)\b""", cmd)
            return AssistantAction.toggle(ActionName.LOCATION, !off)
        }
        return null
    }

    // ── Read actions (model tool calls, chips) ──────────────────────────────

    /**
     * Renders a read action's result with the same layout rules: `search` → [searchBlocks]; `get_item`
     * / `get_location` / `get_update` → the card + chips; `event_info` → a text; `now_next` →
     * `now_next`; `whats_on` → `timeline` (source whats_on); `liked` → the My Schedule list; `my_schedule`
     * → [myScheduleBlocks]; `plan` → the plan; `near_me` → places by distance; `why_not` → [whyNotBlocks];
     * `route` → a `map` with the route; `day_route` → the walking summary. Validated; writes → empty.
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
            ActionName.MY_SCHEDULE -> myScheduleBlocks(action.string("day")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }, ctx)
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

    // ── Event facts and safety (the assistant eval, Nov 22, 2026; iOS's rules and answers) ──
    //
    // Every answer here quotes the organizer's own data (amenities, FAQ, policies, menus, updates) and never
    // adds facts; safety answers always say to call 911.

    private const val COMMAND_VERB = """^(?:add|put|save|book|schedule|remove|drop|delete|move|remind|directions|get directions|walk me|take me|share|like|unlike|show|open|swap|replace|turn|switch|enable|disable|stop|mute)\b"""

    private enum class SafetyKind { EMERGENCY, UNWELL, LOST_CHILD, DANGER }

    private fun safetyKind(cmd: String): SafetyKind? = when {
        has("""\b(lost (my |our )?(kid|kids|child|children|son|daughter|baby|toddler|boy|girl)|(kid|child|son|daughter|baby|toddler) (is |went )?(missing|lost|gone)|can t find my (kid|child|son|daughter|baby)|missing (child|kid)|perdi a mi (hijo|hija|nino|nina))\b""", cmd) -> SafetyKind.LOST_CHILD
        has("""\b(emergency|ambulance|call 911|911|collapsed|collapse|unconscious|passed out|not breathing|can t breathe|cant breathe|heart attack|chest pain|seizure|overdose|od d|bleeding|allergic|allergy|anaphyla\w*|stroke|medico|medecin|necesito un medico|emergencia|urgence|socorro|ayuda)\b""", cmd) -> SafetyKind.EMERGENCY
        has("""\b(fight|fighting|followed|following me|stalk\w*|harass\w*|grabbed|groped|assault\w*|attacked|threaten\w*|weapon|gun|knife|fire|unsafe|in danger|scared|police|security)\b""", cmd) -> SafetyKind.DANGER
        has("""\b(sick|nauseous|nausea|throw(ing)? up|vomit\w*|dizzy|faint\w*|hurt|injured|injury|cut (my|myself|his|her)|burn(ed|t)?|bee sting|stung|headache|heat ?stroke|dehydrated|feel (bad|unwell|ill|awful)|unwell|drunk|too much to drink|sprain\w*|twisted my)\b""", cmd) -> SafetyKind.UNWELL
        else -> null
    }

    private val AMENITY_TAGS: Map<AmenityKind, List<String>> = mapOf(
        AmenityKind.RESTROOMS to listOf("restroom", "toilet"), AmenityKind.WATER to listOf("water"), AmenityKind.ATM to listOf("atm"),
        AmenityKind.FIRST_AID to listOf("first aid", "medical"), AmenityKind.CHARGING to listOf("charging"), AmenityKind.LOCKERS to listOf("locker"),
        AmenityKind.INFO to listOf("info", "lost and found"), AmenityKind.MERCH to listOf("merch"), AmenityKind.PARKING to listOf("parking"),
        AmenityKind.SHUTTLE to listOf("shuttle"), AmenityKind.ACCESSIBLE_ENTRANCE to listOf("accessible entrance"),
    )

    /** The event's places for an amenity kind (from its amenities, else a location named / tagged so), nearest first. */
    private fun places(kind: AmenityKind, ctx: AssistantContext, matching: String? = null): Pair<List<Amenity>, List<Int>> {
        var amenities = ctx.bundle.event.context?.amenities.orEmpty().filter { it.kind == kind }
        if (matching != null) {
            val filtered = amenities.filter { "${it.name} ${it.note}".lowercase().contains(matching) }
            if (filtered.isNotEmpty()) amenities = filtered
        }
        var ids = amenities.mapNotNull { a -> a.locationId?.takeIf { ctx.bundle.location(it) != null } }.distinct()
        if (ids.isEmpty()) {
            AMENITY_TAGS[kind]?.let { words ->
                ids = ctx.bundle.locations.filter { loc ->
                    val text = (listOf(loc.name, loc.category.orEmpty()) + loc.tags).joinToString(" ").lowercase()
                    words.any { text.contains(it) }
                }.map { it.id }
            }
        }
        return amenities to nearestFirst(ids, ctx)
    }

    private fun nearestFirst(ids: List<Int>, ctx: AssistantContext): List<Int> {
        val user = ctx.user ?: return ids
        fun d(id: Int) = ctx.bundle.location(id)?.coordinate?.let { Geo.distanceMeters(user, it) } ?: Double.POSITIVE_INFINITY
        return ids.withIndex().sortedWith(compareBy<IndexedValue<Int>> { d(it.value) }.thenBy { it.index }).map { it.value }
    }

    private fun placeBlocks(ids: List<Int>, title: String): List<AssistantBlock> {
        val refs = ids.map { EntityRef(RefKind.LOCATION, it) }
        return when {
            refs.isEmpty() -> emptyList()
            refs.size == 1 -> listOf(BlockValidator.card(refs[0]))
            else -> listOf(AssistantBlock.Carousel(refs.take(BlockValidator.MAX_CAROUSEL), title))
        }
    }

    private fun amenityLine(amenities: List<Amenity>, ctx: AssistantContext): String = amenities.take(4).joinToString("; ") { a ->
        buildString {
            append(a.name)
            ctx.bundle.location(a.locationId)?.name?.takeIf { it.lowercase() != a.name.lowercase() }?.let { append(" at $it") }
            if (a.note.isNotEmpty()) append(" (${a.note})")
        }
    }

    fun safetyTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val kind = safetyKind(cmd) ?: return null
        val (aidAmenities, aidPlaces) = places(AmenityKind.FIRST_AID, ctx)
        val (_, infoPlaces) = places(AmenityKind.INFO, ctx)
        val aidName = aidPlaces.firstOrNull()?.let { ctx.bundle.location(it)?.name }
        val aidNote = aidAmenities.firstOrNull()?.note?.takeIf { it.isNotEmpty() }
        val noteText = aidNote?.let { " ($it)" }.orEmpty()
        val text: String
        var ids: List<Int>
        when (kind) {
            SafetyKind.EMERGENCY -> {
                text = EMERGENCY_TEXT + (aidName?.let { " $it has help on site$noteText." }.orEmpty())
                ids = aidPlaces
            }
            SafetyKind.UNWELL -> {
                text = (aidName?.let { "Head to $it$noteText. " } ?: "Find a staff member. ") + "If it's an emergency, call 911."
                ids = aidPlaces
            }
            SafetyKind.LOST_CHILD -> {
                val lost = ctx.bundle.event.context?.amenities.orEmpty().firstOrNull { "${it.name} ${it.note}".lowercase().contains("lost child") }
                text = "Tell the nearest staff member right away, and call 911 if your child may be in danger." +
                    (lost?.let { " The event says: ${it.name}" + (if (it.note.isEmpty()) "" else " (${it.note})") + "." }.orEmpty())
                ids = aidPlaces + infoPlaces.filter { it !in aidPlaces }
            }
            SafetyKind.DANGER -> {
                text = "If you're in danger, call 911 now. Get to a safe, busy spot and tell any staff member" +
                    (infoPlaces.firstOrNull()?.let { ctx.bundle.location(it)?.name }?.let { ", or go to $it" }.orEmpty()) + "."
                ids = infoPlaces + aidPlaces.filter { it !in infoPlaces }
            }
        }
        ids = ids.take(3)
        val blocks = mutableListOf<AssistantBlock>(AssistantBlock.Text(text))
        blocks += placeBlocks(ids, "Help")
        ids.firstOrNull()?.let { first ->
            val loc = ctx.bundle.location(first)
            if (loc?.coordinate != null) blocks += chips(listOf(Chip("Directions to ${loc.name}", AssistantAction.directions(first))))
        }
        return done(AssistantIntent.SAFETY, blocks, ctx)
    }

    private val AMENITY_WORDS: List<Pair<AmenityKind, Regex>> = listOf(
        AmenityKind.RESTROOMS to """\b(bathrooms?|restrooms?|rest rooms?|toilets?|loo|loos|wc|washrooms?|porta ?(potty|potties|john|johns|loo)|portaloos?|lavatory|lavatories|pee|poop|ladies room|mens room|men s room|diaper|nappy|baby chang\w*|bano|banos|aseos?|toilettes|toiletten|sanitarios)\b""",
        AmenityKind.WATER to """\b(water|refill|refills|drinking fountain|water fountain|agua|eau|wasser)\b""",
        AmenityKind.ATM to """\b(atm|atms|cash|cash machine|cajero)\b""",
        AmenityKind.FIRST_AID to """\b(first aid|medic|medics|medical|band ?aid|bandaid|plaster|sunscreen|nurse|emt|emts|paramedic)\b""",
        AmenityKind.CHARGING to """\b(charge|charging|charger|battery|power bank|outlet|plug in)\b""",
        AmenityKind.LOCKERS to """\b(lockers?|bag check|coat check)\b""",
        AmenityKind.INFO to """\b(info booth|information|info desk|help desk|lost and found|lost found|lost (my |a )?(wallet|phone|keys|bag|purse|jacket|item|something|glasses|card)|report a lost)\b""",
        AmenityKind.ACCESSIBLE_ENTRANCE to """\b(wheelchair entrance|accessible entrance|step free|ramp)\b""",
        AmenityKind.MERCH to """\b(merch|merchandise|t ?shirts?|tee shirts?|souvenirs?|hoodies?|posters?)\b""",
        AmenityKind.SHUTTLE to """\b(shuttles?)\b""",
        AmenityKind.PARKING to """\b(park|parking|car park|parking lot)\b""",
    ).map { (k, p) -> k to Regex(p) }

    private fun amenityLabel(kind: AmenityKind): String = when (kind) {
        AmenityKind.RESTROOMS -> "restrooms"
        AmenityKind.WATER -> "water"
        AmenityKind.ATM -> "an ATM"
        AmenityKind.FIRST_AID -> "first aid"
        AmenityKind.CHARGING -> "phone charging"
        AmenityKind.LOCKERS -> "lockers"
        AmenityKind.INFO -> "an info point"
        AmenityKind.ACCESSIBLE_ENTRANCE -> "an accessible entrance"
        AmenityKind.MERCH -> "merch"
        AmenityKind.PARKING -> "parking"
        AmenityKind.SHUTTLE -> "a shuttle"
        AmenityKind.FOOD, AmenityKind.OTHER -> "that"
    }

    /** Swift's `capitalized`: every word's first letter upper case, the rest lower case. */
    private fun capitalized(text: String) = text.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) } }

    fun amenityTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        if (has(COMMAND_VERB, cmd)) return null
        for ((kind, pattern) in AMENITY_WORDS) {
            if (!pattern.containsMatchIn(cmd)) continue
            if (kind == AmenityKind.PARKING && has("""\b(bike|bikes|bicycle|scooter)\b""", cmd)) continue
            if (kind == AmenityKind.WATER && has("""\b(waterfront|water bottle policy)\b""", cmd)) continue
            val matching = if (kind == AmenityKind.INFO && cmd.contains("lost")) "lost" else null
            val (amenities, found) = places(kind, ctx, matching)
            if (amenities.isEmpty() && found.isEmpty()) {
                val help = places(AmenityKind.INFO, ctx).second
                val blocks = mutableListOf<AssistantBlock>(AssistantBlock.Text("The event doesn't list ${amenityLabel(kind)}." + if (help.isEmpty()) "" else " Ask at the info point."))
                blocks += placeBlocks(help.take(1), "Info")
                return done(AssistantIntent.FACTS, blocks, ctx)
            }
            val blocks = mutableListOf<AssistantBlock>()
            if (amenities.isNotEmpty()) blocks += AssistantBlock.Text(amenityLine(amenities, ctx) + ".")
            blocks += placeBlocks(found, capitalized(amenityLabel(kind)))
            found.firstOrNull()?.takeIf { ctx.bundle.location(it)?.coordinate != null }?.let { first ->
                blocks += chips(listOf(Chip("Directions", AssistantAction.directions(first)), Chip("Show on map", AssistantAction.open("map", EntityRef(RefKind.LOCATION, first)))))
            }
            return done(AssistantIntent.FACTS, blocks, ctx)
        }
        return null
    }

    private enum class Topic { POLICIES, WEATHER, GETTING_THERE, ACCESSIBILITY, PRICE, ABOUT }

    private fun topic(cmd: String): Topic? = when {
        has("""\b(what is this (festival|event)|what s this (festival|event)|tell me about (the|this) (festival|event)|about (the|this) (festival|event))\b""", cmd) -> Topic.ABOUT
        has("""\b(tickets?|admission|entry fee|entrance fee|cover charge|is it free|is entry free|cost to get in|how much (is|are) (it|entry|admission|tickets?)|refunds?|vip)\b""", cmd) -> Topic.PRICE
        has("""\b(rain|rains|raining|storm|storms|lightning|thunder|weather plan|bad weather|heat|so hot|too hot|wind|windy)\b""", cmd) -> Topic.WEATHER
        has("""\b(wheelchair|accessible|accessibility|ada|asl|deaf|blind|disabilit\w*|disabled|sensory|hearing|ear defenders|autis\w*|quiet (place|space|tent|area))\b""", cmd) -> Topic.ACCESSIBILITY
        has("""\b(getting here|get to the (festival|event)|getting to the (festival|event)|bus|buses|train|metro|transit|public transport\w*|address|bike|bikes|bicycle|uber|lyft|rideshare|taxi|cab|drive here|driving)\b""", cmd) -> Topic.GETTING_THERE
        has("""\b(bags?|backpacks?|purse|policy|policies|rules|allowed|bring|drones?|pets?|dogs?|smok\w*|vap\w*|glass|weapons?|outside (food|drinks|alcohol)|coolers?|chairs?|blankets?|re ?entry|come back|leave and come|wristbands?|id|age limit|ages|all ages|kids allowed)\b""", cmd) -> Topic.POLICIES
        else -> null
    }

    private val FAQ_SYNONYMS: Map<String, List<String>> = mapOf(
        "ticket" to listOf("entry", "free"), "tickets" to listOf("entry", "free"), "admission" to listOf("entry", "free"), "cost" to listOf("free", "entry"),
        "price" to listOf("free", "entry"), "dog" to listOf("pet", "animal"), "dogs" to listOf("pet", "animal"), "vape" to listOf("smoke"), "vaping" to listOf("smoke"),
        "smoking" to listOf("smoke"), "backpack" to listOf("bag"), "purse" to listOf("bag"), "ages" to listOf("age"),
        "cards" to listOf("card"), "credit" to listOf("card"), "pay" to listOf("card"), "reentry" to listOf("leave", "come", "back"), "drones" to listOf("drone"),
        "group" to listOf("separated"), "friends" to listOf("separated", "group"), "lost" to listOf("found", "lost"), "rain" to listOf("rain"),
        "hot" to listOf("heat"), "uber" to listOf("rideshare"), "lyft" to listOf("rideshare"), "taxi" to listOf("rideshare"), "cab" to listOf("rideshare"),
        "bicycle" to listOf("bike"), "transit" to listOf("bus"), "wheelchair" to listOf("step"), "deaf" to listOf("asl"), "autistic" to listOf("quiet"), "autism" to listOf("quiet"),
    )
    private val FAQ_GENERIC = setOf("can", "bring", "do", "does", "is", "are", "there", "where", "what", "how", "get", "i", "my", "the", "a", "an", "any", "allowed", "much", "we", "you")

    private fun stem(word: String) = if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

    private fun withSynonyms(words: Set<String>): Set<String> = words + words.flatMap { FAQ_SYNONYMS[it].orEmpty() }

    /** The sentences of an organizer text that mention the question's words (prefixes count: "bus" → "buses"), else its first two sentences. */
    fun relevantSentences(text: String, cmd: String): String {
        val words = withSynonyms(SearchText.words(cmd).filter { it.length >= 3 && it !in FAQ_GENERIC && it !in SearchQuery.STOP_WORDS }.map(::stem).toSet())
        val sentences = text.replace("\n", " ").split('.').map { it.trim() }.filter { it.isNotEmpty() }
        val hits = sentences.filter { sentence ->
            val have = SearchText.words(sentence).map(::stem)
            words.any { w -> have.any { it.startsWith(w) } }
        }
        val picked = if (hits.isEmpty()) sentences.take(2) else hits.take(3)
        return picked.joinToString(". ") + "."
    }

    /** The FAQ entry that best answers [cmd]: content words (and a few synonyms) in its question (×2) and answer (×1); at least one in the question. */
    fun faqAnswer(cmd: String, ctx: AssistantContext, strict: Boolean = false): FaqEntry? {
        val faq = ctx.bundle.event.context?.faq.orEmpty()
        if (faq.isEmpty()) return null
        val words = withSynonyms(SearchText.words(cmd).filter { it !in FAQ_GENERIC && it !in SearchQuery.STOP_WORDS }.map(::stem).toSet())
        if (words.isEmpty()) return null
        var best: FaqEntry? = null
        var bestScore = 0
        for (entry in faq) {
            val q = SearchText.words(entry.question).map(::stem).toSet() - FAQ_GENERIC
            val a = SearchText.words(entry.answer).map(::stem).toSet()
            val inQuestion = words.intersect(q).size
            if (inQuestion == 0) continue
            if (strict && inQuestion < 2) continue
            val score = inQuestion * 2 + words.intersect(a).size
            if (score > bestScore) {
                best = entry
                bestScore = score
            }
        }
        return best
    }

    /** Places whose names appear in [text] (an FAQ answer, a policy), longest names first. */
    private fun placesNamed(text: String, ctx: AssistantContext): List<Int> {
        val folded = " " + command(text) + " "
        return ctx.bundle.locations.sortedByDescending { it.name.length }.filter { folded.contains(" " + command(it.name) + " ") }.map { it.id }
    }

    private fun clip(text: String): String {
        if (text.length <= 380) return text
        val cut = text.take(380)
        val end = cut.lastIndexOf('.')
        return if (end >= 0) cut.substring(0, end + 1) else "$cut…"
    }

    fun topicTurn(cmd: String, ctx: AssistantContext, faqOnly: Boolean = false): RoutedTurn? {
        if (has(COMMAND_VERB, cmd)) return null
        val event = ctx.bundle.event.context
        val found = if (faqOnly) null else topic(cmd)
        if (found == null && !faqOnly) return null
        var text: String? = null
        var component: String? = null
        // Policies and prices answer from the FAQ first; the other topics from their own text.
        if (found == null || found == Topic.POLICIES || found == Topic.PRICE) faqAnswer(cmd, ctx, strict = faqOnly)?.let { text = it.answer }
        fun from(topicText: String?) {
            if (text == null && !topicText.isNullOrEmpty()) text = relevantSentences(topicText, cmd)
        }
        when (found) {
            Topic.POLICIES -> { component = "policies"; from(event?.policies) }
            Topic.WEATHER -> { component = "weather_plan"; from(event?.weatherPlan) }
            Topic.GETTING_THERE -> { component = "getting_there"; from(event?.gettingThere) }
            Topic.ACCESSIBILITY -> { component = "accessibility"; from(event?.accessibility) }
            Topic.ABOUT -> {
                val about = event?.about?.split('\n')?.firstOrNull().orEmpty()
                text = about.ifEmpty { ctx.bundle.event.description }
            }
            Topic.PRICE -> if (text == null) text = "The event's details don't say."
            null -> Unit
        }
        val answer = text?.takeIf { it.isNotEmpty() } ?: return null
        val blocks = mutableListOf<AssistantBlock>(AssistantBlock.Text(clip(answer)))
        component?.let { blocks += AssistantBlock.ComponentBlock(Component(it)) }
        blocks += placeBlocks(placesNamed(answer, ctx).take(3), "Places")
        return done(AssistantIntent.FACTS, blocks, ctx)
    }

    private val DIET_WORDS = listOf("vegan", "vegetarian", "gluten free", "gluten-free", "dairy free", "dairy-free", "halal", "kosher", "nut free")

    fun menuTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        if (has(COMMAND_VERB, cmd)) return null
        val bundle = ctx.bundle
        val menus = bundle.locations.filter { it.context?.menu.orEmpty().isNotEmpty() }
        if (menus.isEmpty()) return null
        // "anything vegan", "gluten free options": menu lines whose note (or name) says so.
        DIET_WORDS.firstOrNull { cmd.contains(it.replace("-", " ")) }?.let { diet ->
            val key = diet.replace("-", " ")
            val lines = mutableListOf<String>()
            val ids = mutableListOf<Int>()
            for (loc in menus) {
                for (entry in loc.context?.menu.orEmpty()) {
                    if (!command("${entry.name} ${entry.note}").contains(key)) continue
                    lines += "${entry.name}${if (entry.price.isEmpty()) "" else " (${entry.price})"} at ${loc.name}"
                    if (loc.id !in ids) ids += loc.id
                }
            }
            for (loc in bundle.locations) if (loc.id !in ids && loc.tags.any { command(it) == key }) ids += loc.id
            if (ids.isEmpty()) return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("The menus don't mark anything $diet.")), ctx)
            val text = if (lines.isEmpty()) "Marked $diet:" else diet.replaceFirstChar { it.uppercase() } + ": " + lines.take(5).joinToString("; ") + "."
            return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text(text)) + placeBlocks(ids, "Places"), ctx)
        }
        // "menu at Taco Row", "what's on the menu", "what do they have" (the place just shown).
        if (has("""\b(menu|menus|what do they (have|sell|serve)|what can i (get|order) (at|there))\b""", cmd)) {
            val place = placesNamed(cmd, ctx).firstOrNull { id -> menus.any { it.id == id } }
                ?: ctx.memory.resolve("there", setOf(RefKind.LOCATION, RefKind.SCHEDULE), bundle)?.firstOrNull()?.let { ref ->
                    if (ref.kind == RefKind.LOCATION) ref.id else bundle.scheduleItem(ref.id)?.locationId
                }
            val loc = place?.let { bundle.location(it) }?.takeIf { it.context?.menu.orEmpty().isNotEmpty() } ?: return null
            val ref = EntityRef(RefKind.LOCATION, loc.id)
            return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("The menu at ${loc.name}."), AssistantBlock.ComponentBlock(Component("menu", listOf(ref))), BlockValidator.card(ref)), ctx)
        }
        // "how much is the brisket plate": a menu line named in the question.
        val words = SearchText.words(cmd).map(::stem).toSet()
        var best: Triple<EventLocation, MenuEntry, Int>? = null
        for (loc in menus) {
            for (entry in loc.context?.menu.orEmpty()) {
                val name = SearchText.words(entry.name).map(::stem).filter { it.length > 2 }
                if (name.isEmpty() || !name.all { it in words }) continue
                if (name.size > (best?.third ?: 0)) best = Triple(loc, entry, name.size)
            }
        }
        best?.let { (loc, entry, score) ->
            if (score >= 2 || has("""\b(how much|price|cost)\b""", cmd)) {
                val price = if (entry.price.isEmpty()) "" else " is ${entry.price}"
                val note = if (entry.note.isEmpty()) "" else " (${entry.note})"
                return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("${entry.name}$price at ${loc.name}$note."), BlockValidator.card(EntityRef(RefKind.LOCATION, loc.id))), ctx)
            }
        }
        return null
    }

    fun updatesTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        if (has(COMMAND_VERB, cmd) || ctx.bundle.notifications.isEmpty() || !has("""\b(announcements?|updates?|news|alerts?|notifications?|messages?)\b""", cmd)) return null
        val sorted = ctx.bundle.notifications.sortedByDescending { it.sendTime?.toInstant() ?: Instant.MIN }
        val urgentOnly = has("""\b(urgent|emergency|important)\b""", cmd)
        val latestOnly = has("""\b(latest|last|newest|recent|most recent)\b""", cmd)
        var picked = if (urgentOnly) sorted.filter { it.isUrgent } else sorted
        if (picked.isEmpty()) picked = sorted
        if (latestOnly || urgentOnly) picked = picked.take(1)
        val refs = picked.map { EntityRef(RefKind.UPDATE, it.id) }
        val blocks = if (refs.size == 1) listOf(BlockValidator.card(refs[0]))
        else listOf(AssistantBlock.ListBlock(refs, "Updates"), chips(listOf(Chip("All updates", AssistantAction.open("updates")))))
        return done(AssistantIntent.FACTS, blocks, ctx)
    }

    /** Updates that name [name] (a moved or cancelled set). */
    private fun updatesNaming(name: String, ctx: AssistantContext): List<Int> {
        val key = command(name)
        return ctx.bundle.notifications.filter { command("${it.title} ${it.body}").contains(key) }.map { it.id }
    }

    private fun stillOn(item: ScheduleItem, ctx: AssistantContext) = TimeMatcher.effectiveEnd(item)?.isAfter(ctx.now) ?: true

    fun changesTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        if (has(COMMAND_VERB, cmd) ||
            !has("""\b(cancel+ed|cancellations?|cancelled|moved|changes?|changed|delayed|delays?|rescheduled|postponed)\b""", cmd) ||
            !has("""^(is|are|was|were|any|anything|did|has|have|what|which|whats|what s)\b""", cmd)
        ) return null
        if (namedEntities(cmd, ctx).isNotEmpty()) return null // "did Velvet Mesquite move": the entity rule
        val cancelled = ctx.bundle.schedule.filter { item ->
            command("${item.description} ${item.context?.about.orEmpty()}").contains("cancel") && stillOn(item, ctx)
        }.map { it.id }
        val notes = ctx.bundle.notifications.filter { has("""\b(cancel\w*|moves?|moved|delay\w*|chang\w*|reschedul\w*)\b""", command("${it.title} ${it.body}")) }.map { it.id }
        if (cancelled.isEmpty() && notes.isEmpty()) return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("The organizer hasn't announced any changes.")), ctx)
        val refs = cancelled.map { EntityRef(RefKind.SCHEDULE, it) } + notes.map { EntityRef(RefKind.UPDATE, it) }
        return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("Changes from the organizer:"), if (refs.size == 1) BlockValidator.card(refs[0]) else AssistantBlock.ListBlock(refs, "Changes")), ctx)
    }

    private val GENERIC_NAME_WORDS = setOf(
        "the", "stage", "row", "lane", "zone", "booth", "garden", "stand", "market", "kitchen",
        "demo", "pit", "north", "south", "tacos", "island", "open", "gates", "trio", "radio", "band",
        "games", "home", "street", "main", "spot", "sweet", "kids", "info", "first", "aid", "lights", "parking",
    )

    /**
     * Schedule items and places named in [cmd]: whole names first (longest first, "the" optional), else a
     * distinctive word that only one name has ("mariachi", "strings").
     */
    fun namedEntities(cmd: String, ctx: AssistantContext): List<EntityRef> {
        val padded = " $cmd "
        val byName = LinkedHashMap<String, MutableList<EntityRef>>()
        for (item in ctx.bundle.schedule) byName.getOrPut(command(item.name)) { mutableListOf() } += EntityRef(RefKind.SCHEDULE, item.id)
        for (loc in ctx.bundle.locations) byName.getOrPut(command(loc.name)) { mutableListOf() } += EntityRef(RefKind.LOCATION, loc.id)
        val names = byName.keys.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        for (name in names) {
            val bare = name.removePrefix("the ")
            if (padded.contains(" $name ") || (bare.length >= 5 && padded.contains(" $bare "))) return byName.getValue(name)
        }
        val owners = HashMap<String, MutableSet<String>>()
        for (name in names) {
            for (word in name.split(' ')) if (word.length >= 5 && word !in GENERIC_NAME_WORDS) owners.getOrPut(word) { mutableSetOf() } += name
        }
        val words = cmd.split(' ').toSet()
        val hits = owners.filter { it.key in words && it.value.size == 1 }.map { it.value.first() }
        if (hits.toSet().size == 1) return byName[hits.first()].orEmpty()
        return emptyList()
    }

    /** The upcoming (or running) instances first, then ended ones, by time. */
    private fun byTiming(refs: List<EntityRef>, ctx: AssistantContext): List<EntityRef> = refs.sortedWith(
        compareBy<EntityRef>({ r ->
            val item = ctx.bundle.scheduleItem(r.id)
            if (item == null) 2 else if (TimeMatcher.effectiveEnd(item)?.isAfter(ctx.now) == false) 1 else 0
        }, { r -> ctx.bundle.scheduleItem(r.id)?.startTime?.toInstant() ?: Instant.MAX }),
    )

    fun entityQuestionTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        var refs = namedEntities(cmd, ctx)
        if (refs.isEmpty()) return null
        val bundle = ctx.bundle
        val blocks = mutableListOf<AssistantBlock>()
        if (refs.all { it.kind == RefKind.SCHEDULE }) {
            refs = byTiming(refs, ctx)
            bundle.scheduleItem(refs[0].id)?.let { item ->
                // "something similar to X": acts sharing a genre tag.
                if (has("""\b(similar|like|sounds like|more like)\b""", cmd)) {
                    val tags = item.tags.toSet() - setOf("live music", "free", "general")
                    val similar = bundle.schedule.filter { other -> other.name != item.name && other.tags.any { it in tags } && stillOn(other, ctx) }
                        .map { EntityRef(RefKind.SCHEDULE, it.id) }
                    if (similar.isNotEmpty()) {
                        val shown = byTiming(similar, ctx)
                        return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("Like ${item.name}:"), if (shown.size == 1) BlockValidator.card(shown[0]) else AssistantBlock.Carousel(shown.take(8), "Similar")), ctx)
                    }
                }
                if (command("${item.description} ${item.context?.about.orEmpty()}").contains("cancel")) blocks += AssistantBlock.Text(item.description)
                val performers = item.context?.performers.orEmpty()
                if (has("""\b(listen|spotify|apple music|stream|song|songs|album)\b""", cmd) && performers.isNotEmpty()) {
                    val linked = performers.any { it.appleMusicUrl != null }
                    if (cmd.contains("spotify") || !linked) blocks += AssistantBlock.Text(if (linked) "Attnd links to Apple Music only." else "There's no music link for ${item.name} here.")
                    blocks += AssistantBlock.ComponentBlock(Component("performers", listOf(refs[0])))
                }
            }
        }
        blocks += if (refs.size == 1) BlockValidator.card(refs[0]) else AssistantBlock.ListBlock(refs.take(BlockValidator.MAX_LIST), "Times")
        refs.firstOrNull()?.name(bundle)?.let { name ->
            updatesNaming(name, ctx).firstOrNull()?.let { blocks += BlockValidator.card(EntityRef(RefKind.UPDATE, it)) }
        }
        refs.firstOrNull()?.let { blocks += chips(entityChips(it, ctx)) }
        return done(AssistantIntent.FACTS, blocks, ctx)
    }

    /** "How far is the Main Stage", "how long to walk to X": the place (its card shows distance and walk). */
    fun howFarTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val m = find("""^(?:how far(?: away)?(?: is| are| to)?|how long (?:to walk|is the walk|does it take) to|how many minutes to)\s+(?:the\s+)?(.+)$""", cmd) ?: return null
        var refs = namedEntities(m[1], ctx)
        if (refs.isEmpty()) (resolve(m[1], setOf(RefKind.LOCATION, RefKind.SCHEDULE), ctx) as? Resolution.Found)?.let { refs = listOf(it.ref) }
        val ref = refs.firstOrNull() ?: return null
        val place = (if (ref.kind == RefKind.LOCATION) ref.id else ctx.bundle.scheduleItem(ref.id)?.locationId) ?: return null
        val loc = ctx.bundle.location(place) ?: return null
        val blocks = mutableListOf<AssistantBlock>()
        val user = ctx.user
        val point = loc.coordinate
        if (user != null && point != null) {
            val meters = Geo.distanceMeters(user, point)
            val minutes = maxOf(1, ceil(meters * 1.2 / 1.3 / 60).toInt())
            blocks += AssistantBlock.Text("${loc.name} is about $minutes min on foot.")
        } else {
            blocks += AssistantBlock.Text("Turn on location to see how far ${loc.name} is.")
        }
        blocks += BlockValidator.card(EntityRef(RefKind.LOCATION, place))
        if (point != null) blocks += chips(listOf(Chip("Directions", AssistantAction.directions(place))))
        return done(AssistantIntent.FACTS, blocks, ctx)
    }

    /** "When does the festival end", "what time does it end tonight". */
    fun endTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        if (!has("""\b(when|what time)\b.*\b(end|ends|finish|finishes|over|close|closes|wrap up)\b""", cmd)) return null
        val zone = ctx.bundle.event.zone
        if (has("""\b(tonight|today)\b""", cmd)) {
            val today = ctx.now.atZone(zone).toLocalDate()
            val last = ctx.bundle.schedule.filter { it.startTime?.atZoneSameInstant(zone)?.toLocalDate() == today }
                .maxByOrNull { TimeMatcher.effectiveEnd(it) ?: Instant.MIN } ?: return null
            val end = TimeMatcher.effectiveEnd(last) ?: return null
            val place = ctx.bundle.location(last.locationId)?.let { " at ${it.name}" }.orEmpty()
            return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("Today's last item, ${last.name}$place, ends at ${time(end, ctx)}."), BlockValidator.card(EntityRef(RefKind.SCHEDULE, last.id))), ctx)
        }
        if (!has("""\b(festival|event|it all|everything|this)\b""", cmd)) return null
        val end = ctx.bundle.event.endTime ?: return null
        val day = end.atZoneSameInstant(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
        return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text("${ctx.bundle.event.name} runs until $day ${time(end.toInstant(), ctx)}.")), ctx)
    }

    /** Small talk the router shouldn't search for ("thanks", "you suck", "I love you"). */
    fun smallTalkTurn(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val text = when {
            matches("""(thanks|thank you|thx|ty|cheers|thanks a lot|thank you so much)""", cmd) -> "You're welcome!"
            matches("""(hi|hello|hey|hey there|hiya|yo|good (morning|afternoon|evening))""", cmd) -> "Hi! Ask me about the schedule, places or your plan."
            has("""\b(you suck|you re useless|youre useless|stupid|dumb bot|hate you)\b""", cmd) -> "Sorry about that. Try one of these:"
            matches("""(i love you|love you|you re great|you are great|good bot)""", cmd) -> "Glad to help! Anything else about the event?"
            matches("""(help|what can you do|what do you do|how does this work|what can i ask|what can i ask you|how do i use this)""", cmd) ->
                "Ask me what's on, where things are (food, restrooms, water), about the event's rules, or say \"add Taco Row to my schedule\"."
            matches("""(ok|okay|cool|got it|nice|great|alright|k|kk|perfect|sounds good)""", cmd) -> "Okay."
            else -> return null
        }
        return done(AssistantIntent.FACTS, listOf(AssistantBlock.Text(text), AssistantBlock.Chips(suggestionChips(ctx))), ctx)
    }

    /** Not a thing to add ("I love you"). */
    val NOT_TARGETS = setOf("you", "u", "ya", "this app", "attnd", "it all", "this", "that", "everything", "everyone", "all of it")

    /** Of several instances of one name (an act playing three times), the one on now, else the next. */
    fun sameNameNext(refs: List<EntityRef>, ctx: AssistantContext): EntityRef? {
        val items = refs.mapNotNull { if (it.kind == RefKind.SCHEDULE) ctx.bundle.scheduleItem(it.id) else null }
        if (items.size != refs.size || items.size <= 1 || items.map { command(it.name) }.toSet().size != 1) return null
        return items.filter { stillOn(it, ctx) }.minByOrNull { it.startTime?.toInstant() ?: Instant.MAX }?.let { EntityRef(RefKind.SCHEDULE, it.id) }
    }

    /** "When is it", "where is that": the thing the conversation just showed. */
    fun memoryQuestion(cmd: String, ctx: AssistantContext): RoutedTurn? {
        val m = find("""^(?:when|where|what time) (?:is|are|does|do) (it|that|this|they|them|this one|that one)(?: start| play| on| begin| open)?$""", cmd) ?: return null
        val ref = ctx.memory.resolve(m[1], setOf(RefKind.SCHEDULE, RefKind.LOCATION), ctx.bundle)?.firstOrNull() ?: return null
        return done(AssistantIntent.FACTS, listOf(BlockValidator.card(ref), chips(entityChips(ref, ctx))), ctx)
    }

    /**
     * The time words of a "what's on" question without the words around them ("sunday schedule",
     * "happening right now"); "in an hour" / "in 30 minutes" → that clock time.
     */
    fun timeQuery(cmd: String, keywords: String, ctx: AssistantContext): String {
        find("""\bin (an|one|a|half an|\d+) (hour|hours|minutes|mins|min)\b""", cmd)?.let { m ->
            val n = m[1].toIntOrNull() ?: if (m[1] == "half an") 30 else 1
            val minutes = if (m[2].startsWith("h")) (if (m[1] == "half an") 30 else n * 60) else n
            val at = ctx.now.plusSeconds(minutes * 60L).atZone(ctx.bundle.event.zone)
            return "${(at.hour + 11) % 12 + 1}:${"%02d".format(at.minute)}${if (at.hour < 12) "am" else "pm"}"
        }
        val stripped = " $keywords ".replace(Regex("""\b(schedule|lineup|line up|program|programme|agenda|events?|timetable|set times|happening|going on|right|currently|on|there|anything|stuff|things|playing|shows?)\b"""), " ")
        val words = stripped.split(' ').filter { it.isNotEmpty() }
        return if (words == keywords.split(' ').filter { it.isNotEmpty() }) "" else words.joinToString(" ")
    }

    private val SYNONYMS: List<Pair<Regex, String>> = listOf(
        """\b(hungry|grub|starving|snacks?|something to eat|eat|eats|munchies|comer|comida|manger|nourriture|essen)\b""" to "food",
        """\b(ahora|maintenant|jetzt|adesso)\b""" to "now",
        """\b(prochain|prochaine|siguiente|proximo|nachste|naechste)\b""" to "next",
        """\b(booze|alcohol|cocktails?|wine|a drink|drinks|thirsty)\b""" to "drinks",
        """\b(edm|electronic|techno|house music|dance music|rave)\b""" to "dj",
        """\b(party|after ?party|late night|nightlife)\b""" to "late night",
        """\b(kids area|kid area|play area|children s area|for kids|for children)\b""" to "kids",
        """\b(bored|what should i do|something to do|anything fun|entertain me)\b""" to "now",
        """^(?:any|some)\s+(.+)$""" to "$1",
    ).map { (p, q) -> Regex(p) to q }

    /** Everyday words the event's tags don't use. */
    fun synonymQuery(cmd: String): String? {
        for ((pattern, query) in SYNONYMS) {
            if (query == "$1") pattern.find(cmd)?.let { return it.groupValues[1] }
            else if (pattern.containsMatchIn(cmd)) return query
        }
        return null
    }
}
