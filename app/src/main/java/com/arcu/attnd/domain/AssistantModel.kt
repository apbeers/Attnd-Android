package com.arcu.attnd.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** One turn of the assistant conversation, stored with the plan per event (on the device only). */
@Serializable
data class ConversationTurn(val fromUser: Boolean, val text: String)

/**
 * The conversation (assistant spec §3): the model sees the last [SHOWN] turns verbatim (each cut to
 * 200 characters) and, beyond them, one line with up to 10 distinct keywords of the earlier user
 * turns. At most [MAX_STORED] turns are kept, with the [memory] of what the answers showed ("add it",
 * "the second one"; as iOS). Stored as `{"turns":[…],"memory":{…}}`; the older bare array is still read.
 */
data class AssistantConversation(val turns: List<ConversationTurn> = emptyList(), val memory: ConversationContext = ConversationContext()) {

    fun with(turn: ConversationTurn): AssistantConversation = copy(turns = (turns + turn).takeLast(MAX_STORED))

    /** The answer's entities and action recorded in the memory. */
    fun remembering(turn: AssistantTurn): AssistantConversation = copy(memory = memory.record(turn))

    fun promptLines(): List<String> {
        val shown = turns.takeLast(SHOWN)
        val earlier = turns.dropLast(SHOWN).filter { it.fromUser }
        return buildList {
            if (earlier.isNotEmpty()) {
                val words = earlier.flatMap { SearchText.words(SearchQuery.keywords(it.text)) }
                    .filter { it !in SearchQuery.STOP_WORDS }.distinct().take(10)
                add("(earlier: ${earlier.size} more requests about ${words.joinToString(", ").ifEmpty { "other things" }})")
            }
            shown.forEach { add((if (it.fromUser) "Attendee: " else "Assistant: ") + it.text.replace(Regex("\\s+"), " ").trim().take(200)) }
        }
    }

    fun encode(): String = JsonObject(
        mapOf(
            "turns" to JsonArray(turns.map { JsonObject(mapOf("fromUser" to JsonPrimitive(it.fromUser), "text" to JsonPrimitive(it.text))) }),
            "memory" to memory.encode(),
        ),
    ).toString()

    companion object {
        const val SHOWN = 6
        const val MAX_STORED = 40
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun decode(text: String?): AssistantConversation {
            if (text.isNullOrBlank()) return AssistantConversation()
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return AssistantConversation()
            val list = (root as? JsonArray) ?: ((root as? JsonObject)?.get("turns") as? JsonArray) ?: JsonArray(emptyList())
            val turns = list.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val fromUser = (o["fromUser"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return@mapNotNull null
                val t = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                ConversationTurn(fromUser, t)
            }
            return AssistantConversation(turns.takeLast(MAX_STORED), ConversationContext.decode((root as? JsonObject)?.get("memory")))
        }
    }
}

/** The model's reply read against the bundle: a turn, or why it was rejected (then the router answers). */
sealed interface ModelReply {
    data class Valid(val turn: AssistantTurn) : ModelReply
    data class Invalid(val reason: String) : ModelReply
}

/** Which path answered a turn. */
enum class TurnSource { MODEL, RULES }

data class ResolvedTurn(val source: TurnSource, val turn: AssistantTurn, val routed: RoutedTurn?)

/**
 * The model path (assistant spec §3; Android Gemini Nano JSON, iOS `@Generable AssistantTurn` with the
 * same schema). The model only understands and picks components: it sees the event facts, the plan
 * summary, likes, the conversation and at most 12 retrieved items — never the whole bundle.
 *
 * Reply schema: `{"blocks":[{"type":…, …}], "actions":[{"name":…, "params":{…}}]}`. [parse] takes the
 * first `{` to the last `}` and is **invalid** (→ [AssistantRouter] for that turn) when: no JSON
 * object; `blocks` missing or not an array; a block that isn't an object, has no `type`, or is a
 * known type without its required props ([BlockJson.decode] Malformed); an action that isn't an
 * object, isn't in the registry, is never available or has invalid params; or nothing is left after
 * validation. Unknown block types and unknown ids are only dropped. Read actions are rendered
 * ([AssistantRouter.renderRead]) after the model's blocks (their chips dropped); `open` and
 * `directions` stay actions (navigation); write actions with [ConfirmKind.CHIP]
 * become `confirm` blocks (unless the model already asked); other writes stay as actions (applied
 * with Undo / an OS sheet). Then [BlockValidator] (≤ 8 blocks, ≤ 5 chips).
 */
object AssistantModel {

    const val MAX_ITEMS = AssistantPrompt.MAX_CONTEXT_ITEMS
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * At most 12 items: when the input's keywords contain time words ("tonight", "3pm"), the schedule
     * items matching only those words (not ended, by start) first; then [AssistantPrompt.context] (any-token
     * matches of the keywords, then what's on now / next); distinct.
     */
    fun retrieve(input: String, ctx: AssistantContext): List<SearchResult> {
        val keywords = SearchQuery.keywords(AssistantRouter.command(input))
        val parsed = SearchQuery.parse(keywords)
        val timed = if (parsed.time.isEmpty) emptyList() else {
            val words = SearchText.words(keywords).filter { w -> SearchQuery.parse(w).isTimeOnly }.joinToString(" ")
            EventSearch.search(ctx.bundle, words.ifEmpty { keywords }, ctx.now).filter { it.kind == SearchKind.SCHEDULE && it.timing != ItemTiming.ENDED }
                .sortedWith(compareBy(Planner.ITEM_ORDER) { ctx.bundle.scheduleItem(it.id)!! })
        }
        return (timed + AssistantPrompt.context(ctx.bundle, input, ctx.now)).distinctBy { it.kind to it.id }.take(MAX_ITEMS)
    }

    fun prompt(input: String, ctx: AssistantContext, conversation: AssistantConversation, retrieved: List<SearchResult>): String {
        val bundle = ctx.bundle
        val zone = bundle.event.zone
        val nowText = ctx.now.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", ctx.locale))
        val writes = ActionName.entries.filter { it.mutates }.joinToString(", ") { describe(it) }
        val reads = ActionName.entries.filter { !it.mutates }.joinToString(", ") { describe(it) }
        return buildString {
            appendLine("You are the assistant in the Attnd app for the event \"${clean(bundle.event.name)}\".")
            appendLine("Choose how to answer the attendee. Reply with only one JSON object and no other text:")
            appendLine("""{"blocks":[{"type":"text","text":"…"}],"actions":[]}""")
            appendLine("BLOCKS (use ids from ITEMS only, like \"s12\", \"l3\", \"u9\"):")
            appendLine("text{text} item_card{id} place_card{id} update_card{id} list{ids,title} carousel{ids,title} timeline{ids} map{pins,route} gallery{ids} now_next{location} countdown{id} compare{a,b} plan{} setting{setting} summary{kind} share{target} chips{chips:[{label,action,params}]} confirm{action,params,prompt}")
            appendLine("READ ACTIONS: $reads")
            appendLine("WRITE ACTIONS (the app asks or offers Undo): $writes")
            appendLine("Rules: at most 8 blocks; text at most 400 characters; end with at most 5 chips; one card for one result, a carousel for 2–8; never invent ids, times or facts.")
            appendLine("All times are local to the event (${zone.id}). NOW: $nowText.")
            appendLine("EVENT: ${clean(bundle.event.name)} — ${EventSearch.eventDetail(bundle.event, ctx.locale)}")
            eventInfo(bundle)?.let { appendLine("EVENT INFO: $it") }
            val plan = ctx.plan
            appendLine("PLAN: " + if (plan == null) "none" else Planner.summary(plan, zone, ctx.locale) + " — " + plan.items.joinToString("; ") { "s${it.itemId}" })
            val liked = EventSearch.hearted(bundle, ctx.hearts, ctx.now).mapNotNull { r -> EntityRef.of(r)?.let { "${it.code} ${clean(r.title)}" } }
            appendLine("LIKED: " + liked.joinToString("; ").ifEmpty { "none" })
            val lines = conversation.promptLines()
            if (lines.isNotEmpty()) {
                appendLine("CONVERSATION:")
                lines.forEach { appendLine(it) }
            }
            appendLine("ITEMS:")
            retrieved.take(MAX_ITEMS).forEach { result -> line(result, ctx)?.let { appendLine(it) } }
            appendLine("INPUT: ${clean(input, 300)}")
            append("JSON:")
        }
    }

    private fun describe(name: ActionName): String =
        name.key + "(" + name.params.joinToString(",") { it.name + if (it.required) "" else "?" } + ")"

    private fun line(result: SearchResult, ctx: AssistantContext): String? {
        val ref = EntityRef.of(result) ?: return null
        val bundle = ctx.bundle
        val detail = EventSearch.detail(result, bundle, ctx.locale)
        val marks = buildList {
            if (ref.kind == RefKind.SCHEDULE && ctx.hearts.has(SearchKind.SCHEDULE, ref.id)) add("liked")
            if (ref.kind == RefKind.LOCATION && ctx.hearts.has(SearchKind.LOCATION, ref.id)) add("liked")
            if (ref.kind == RefKind.SCHEDULE && ctx.plan?.itemIds?.contains(ref.id) == true) add("in plan")
            result.timing?.let { add(it.label.lowercase()) }
        }
        val tags = when (ref.kind) {
            RefKind.SCHEDULE -> bundle.scheduleItem(ref.id)?.tags.orEmpty()
            RefKind.LOCATION -> bundle.location(ref.id)?.tags.orEmpty()
            RefKind.UPDATE -> emptyList()
        }
        return buildString {
            append("[${ref.code}] ").append(clean(result.title))
            if (detail.isNotEmpty()) append(" — ").append(clean(detail))
            if (tags.isNotEmpty()) append(" — tags: ").append(clean(tags.joinToString(", ")))
            if (marks.isNotEmpty()) append(" — ").append(marks.joinToString(", "))
            contextLine(ref, bundle)?.let { append(" — ").append(it) }
        }
    }

    /** API-018 for an ITEMS line, compact: performers with genres, price, age limit (items); hours, age limit, the first menu entries (places). */
    fun contextLine(ref: EntityRef, bundle: EventBundle): String? {
        val parts = mutableListOf<String>()
        when (ref.kind) {
            RefKind.SCHEDULE -> {
                val c = bundle.scheduleItem(ref.id)?.context ?: return null
                if (c.performers.isNotEmpty()) {
                    parts += "performers: " + c.performers.take(3).joinToString(", ") { p -> if (p.genres.isEmpty()) p.name else "${p.name} (${p.genres.take(3).joinToString(", ")})" }
                }
                if (c.price.isNotEmpty()) parts += "price: " + c.price
                if (c.ageLimit.isNotEmpty()) parts += "age: " + c.ageLimit
            }
            RefKind.LOCATION -> {
                val c = bundle.location(ref.id)?.context ?: return null
                if (c.hours.isNotEmpty()) parts += "hours: " + c.hours
                if (c.ageLimit.isNotEmpty()) parts += "age: " + c.ageLimit
                if (c.menu.isNotEmpty()) parts += "menu: " + c.menu.take(4).joinToString(", ") { if (it.price.isEmpty()) it.name else "${it.name} ${it.price}" }
            }
            RefKind.UPDATE -> return null
        }
        return if (parts.isEmpty()) null else clean(parts.joinToString(" — "), 240)
    }

    /** API-018 event facts for the prompt (≤ 400 characters): amenities and the start of the FAQ. */
    fun eventInfo(bundle: EventBundle): String? {
        val c = bundle.event.context ?: return null
        val parts = mutableListOf<String>()
        if (c.amenities.isNotEmpty()) {
            parts += "amenities: " + c.amenities.take(8).joinToString(", ") { a -> bundle.location(a.locationId)?.let { "${a.name} at l${it.id}" } ?: a.name }
        }
        if (c.faq.isNotEmpty()) parts += "FAQ: " + c.faq.take(3).joinToString(" ") { "${it.question} ${it.answer}" }
        return if (parts.isEmpty()) null else clean(parts.joinToString(" — "), 400)
    }

    private fun clean(text: String, max: Int = 160): String {
        val flat = text.replace('[', '(').replace(']', ')').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    fun parse(raw: String, ctx: AssistantContext): ModelReply {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return ModelReply.Invalid("no json")
        val obj = try {
            json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return ModelReply.Invalid("not an object")
        val array = obj["blocks"] as? JsonArray ?: return ModelReply.Invalid("no blocks")
        val blocks = mutableListOf<AssistantBlock>()
        for (element in array) {
            when (val decoded = BlockJson.decode(element)) {
                is BlockJson.Decoded.Ok -> blocks += decoded.block
                BlockJson.Decoded.Unknown -> Unit
                is BlockJson.Decoded.Malformed -> return ModelReply.Invalid("malformed ${decoded.type}")
            }
        }
        val actions = mutableListOf<AssistantAction>()
        for (element in (obj["actions"] as? JsonArray).orEmpty()) {
            val a = element as? JsonObject ?: return ModelReply.Invalid("bad action")
            val name = (a["name"] as? JsonPrimitive)?.content
            when (val check = ActionRegistry.check(name, a["params"] as? JsonObject, ctx.bundle, ctx.now)) {
                is ActionCheck.Invalid -> return ModelReply.Invalid("action ${name.orEmpty()}: ${check.reason}")
                is ActionCheck.Valid -> actions += check.action
            }
        }
        val reads = actions.filter { !it.name.mutates && it.name.result != ActionResultType.NAVIGATION }
        val writes = actions.filter { it.name.mutates || it.name.result == ActionResultType.NAVIGATION }
        val asked = blocks.filterIsInstance<AssistantBlock.Confirm>().map { it.action.name }.toSet()
        val confirms = writes.filter { it.name.confirm == ConfirmKind.CHIP && it.name !in asked }
            .map { AssistantBlock.Confirm(it, ActionRegistry.confirmPrompt(it)) }
        val direct = writes.filter { it.name.confirm != ConfirmKind.CHIP }
        val chips = blocks.filterIsInstance<AssistantBlock.Chips>()
        val content = blocks.filter { it !is AssistantBlock.Chips } + reads.flatMap { AssistantRouter.renderRead(it, ctx).filter { b -> b !is AssistantBlock.Chips } } + confirms
        val validated = BlockValidator.validate(content + chips, ctx.blockContext)
        if (validated.isEmpty() && direct.isEmpty()) return ModelReply.Invalid("nothing left")
        return ModelReply.Valid(AssistantTurn(validated, direct))
    }

    /** One turn: the model's reply when valid, else the rule-based router (also without a reply). */
    fun resolve(input: String, raw: String?, ctx: AssistantContext): ResolvedTurn {
        if (raw != null) {
            val reply = parse(raw, ctx)
            if (reply is ModelReply.Valid) return ResolvedTurn(TurnSource.MODEL, reply.turn, null)
        }
        val routed = AssistantRouter.route(input, ctx)
        return ResolvedTurn(TurnSource.RULES, routed.turn, routed)
    }

    /** For logs: the turn's shape only (types and counts, never text). */
    fun shape(turn: AssistantTurn): Map<String, Any> = mapOf(
        "blocks" to turn.blocks.size,
        "types" to turn.blocks.joinToString(",") { it.type },
        "actions" to turn.actions.joinToString(",") { it.name.key },
    )

    /** Kept for the platform's streaming: blocks that are complete in a partial reply (`{"blocks":[ {...}, {...}`). */
    fun completeBlocks(partial: String, ctx: AssistantContext): List<AssistantBlock> {
        val start = partial.indexOf("\"blocks\"").takeIf { it >= 0 } ?: return emptyList()
        val open = partial.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
        val found = mutableListOf<AssistantBlock>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escape = false
        for (i in open + 1 until partial.length) {
            val ch = partial[i]
            if (inString) {
                if (escape) escape = false else if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val element = runCatching { json.parseToJsonElement(partial.substring(objStart, i + 1)) }.getOrNull()
                        (BlockJson.decode(element) as? BlockJson.Decoded.Ok)?.let { found += it.block }
                        objStart = -1
                    }
                }
                ']' -> if (depth == 0) break
            }
        }
        return BlockValidator.validate(found, ctx.blockContext).filter { it !is AssistantBlock.Chips || found.lastOrNull() is AssistantBlock.Chips }
    }

    // ── Tool-calling turns (iOS, Nov 21, 2026; Android's JSON tool protocol is AssistantTools.kt) ──

    /** The parts of a tool-calling turn's prompt. [history] only for a fresh session (a kept one has the turns). */
    data class PromptParts(
        val header: List<String>,
        val facts: List<String>,
        val schedule: List<String>,
        val memory: List<String>,
        val history: List<String>,
        val items: List<String>,
        val input: String,
        /** Android: the tool list and the protocol's rules ([AssistantTools.promptSection]); never trimmed. */
        val tools: List<String> = emptyList(),
    )

    /**
     * Fits the parts into [limit] tokens: the oldest history lines go first, then retrieved items (from the
     * end), then the facts. The header, My schedule, the memory, the tools and the input stay.
     */
    fun budget(parts: PromptParts, limit: Int): BudgetedPrompt {
        var history = parts.history
        var items = parts.items
        var facts = parts.facts
        fun render(): String = buildList {
            addAll(parts.tools)
            addAll(parts.header)
            addAll(facts)
            addAll(parts.schedule)
            addAll(parts.memory)
            if (history.isNotEmpty()) { add("CONVERSATION:"); addAll(history) }
            add("ITEMS:")
            if (items.isEmpty()) add("(none; use find)") else addAll(items)
            add("INPUT: " + parts.input)
        }.joinToString("\n")
        var text = render()
        var droppedHistory = 0
        var droppedItems = 0
        while (TokenBudget.estimate(text) > limit && history.isNotEmpty()) {
            history = history.drop(1)
            droppedHistory++
            text = render()
        }
        while (TokenBudget.estimate(text) > limit && items.isNotEmpty()) {
            items = items.dropLast(1)
            droppedItems++
            text = render()
        }
        var droppedFacts = false
        if (TokenBudget.estimate(text) > limit && facts.isNotEmpty()) {
            facts = emptyList()
            droppedFacts = true
            text = render()
        }
        val trimmed = buildList {
            if (droppedHistory > 0) add("history:$droppedHistory")
            if (droppedItems > 0) add("items:$droppedItems")
            if (droppedFacts) add("facts")
        }
        return BudgetedPrompt(text, TokenBudget.estimate(text), trimmed)
    }

    /**
     * The prompt of one tool-calling turn: NOW, the event (and its facts in a fresh session), My schedule,
     * what "it" / "the second one" mean (the memory), the conversation (fresh session only), ≤ 12 items and
     * the input, cut to [limit] tokens. [tools] = the tool protocol's section (Android) or nothing (iOS: the
     * session carries the schemas).
     */
    fun toolPrompt(
        input: String,
        ctx: AssistantContext,
        conversation: AssistantConversation,
        retrieved: List<SearchResult>,
        freshSession: Boolean,
        limit: Int,
        tools: List<String> = emptyList(),
    ): BudgetedPrompt {
        val bundle = ctx.bundle
        val zone = bundle.event.zone
        val header = listOf(
            "NOW: " + ctx.now.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", ctx.locale)) + " (event time, ${zone.id})",
            "EVENT: ${clean(bundle.event.name)} — ${EventSearch.eventDetail(bundle.event, ctx.locale)}",
        )
        val facts = if (freshSession) listOfNotNull(eventInfo(bundle)?.let { "EVENT INFO: $it" }) else emptyList()
        val dayTime = DateTimeFormatter.ofPattern("EEE h:mm a", ctx.locale)
        val rows = ctx.schedule.rows(bundle, ctx.now).filter { !it.ended }.take(12).map { row ->
            "[${if (row.entry.kind == ScheduleEntryKind.ITEM) "s" else "l"}${row.entry.id}] ${clean(row.name)} ${row.start.atZone(zone).format(dayTime)}"
        }
        val saved = ctx.schedule.savedPlaceIds.take(6).mapNotNull { id -> bundle.location(id)?.let { "[l$id] ${clean(it.name)} (saved, no time)" } }
        val scheduleLine = "MY SCHEDULE: " + (rows + saved).joinToString("; ").ifEmpty { "empty" }
        val shown = ctx.memory.shown.take(8).mapIndexedNotNull { index, ref ->
            ref.name(bundle)?.let { "${index + 1}. [${ref.code}] ${clean(it)}" + if (ref == ctx.memory.focus) " (it)" else "" }
        }
        val memory = if (shown.isEmpty()) emptyList() else listOf("SHOWN (\"it\", \"that\", \"the second one\" refer to these): " + shown.joinToString("; "))
        val history = if (freshSession) conversation.promptLines() else emptyList()
        val items = retrieved.take(MAX_ITEMS).mapNotNull { line(it, ctx) }
        return budget(PromptParts(header, facts, listOf(scheduleLine), memory, history, items, clean(input, 300), tools), limit)
    }

    /**
     * A tool-calling turn's answer: the model's text, then what it `show`ed, then confirmations for
     * chip-confirmed writes; direct writes run through the dispatcher. Validated like every turn.
     */
    fun assemble(text: String, shown: List<AssistantBlock>, actions: List<AssistantAction>, ctx: AssistantContext): AssistantTurn {
        val raw = text.trim()
        // Components say it better than a list: names found in the text get their cards below.
        val named = namedEntities(raw, ctx)
        val showsComponents = shown.any { it !is AssistantBlock.Chips && it !is AssistantBlock.Text } || named.isNotEmpty()
        val answer = answerText(raw, showsComponents)
        val blocks = mutableListOf<AssistantBlock>()
        if (answer.isNotEmpty()) blocks += AssistantBlock.Text(answer)
        blocks += shown.filter { it !is AssistantBlock.Chips }
        // Safety net (owner, Nov 21, 2026): an answer that names items or places but showed none of them gets
        // their cards (1 → card, 2–8 → carousel), so they can be tapped and "add it" works.
        if (shown.none { it !is AssistantBlock.Chips && ConversationContext.refs(it).isNotEmpty() }) {
            if (named.size == 1) blocks += BlockValidator.card(named[0])
            else if (named.size > 1) blocks += AssistantBlock.Carousel(named.take(8))
        }
        blocks += actions.filter { it.name.confirm == ConfirmKind.CHIP }.map { AssistantBlock.Confirm(it, ActionRegistry.confirmPrompt(it)) }
        blocks += shown.filterIsInstance<AssistantBlock.Chips>()
        return AssistantTurn(BlockValidator.validate(blocks, ctx.blockContext), actions.filter { it.name.confirm != ConfirmKind.CHIP })
    }

    private val ID_GROUP = Regex("""\s*[(\[]\s*[sluSLU]\d{1,7}(\s*,\s*[sluSLU]\d{1,7})*\s*[)\]]""")
    private val BARE_ID = Regex("""\b[slu]\d{1,7}\b""")
    private val FILLERS = listOf(
        """\s*(Is there )?anything else( I can help with| you('d| would) like)?\s*\?\s*$""",
        """\s*Let me know if [^.!?]*[.!?]?\s*$""",
        """\s*(Want|Would you like) (me to|anything) [^.!?]*\?\s*$""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * The model's answer text cleaned up (owner, Nov 21, 2026: components over text): entity ids ("(s998)",
     * "[l3]", "u9") never reach the attendee, filler ("Anything else?", "Let me know if…") is dropped, and with
     * components shown only the first sentence stays (they carry the rest).
     */
    fun answerText(text: String, withComponents: Boolean): String {
        var s = text.replace(ID_GROUP, "").replace(BARE_ID, "")
        s = s.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\s+([,.!?;:])"""), "$1")
        for (filler in FILLERS) {
            val trimmed = s.replace(filler, "")
            if (trimmed.isNotBlank()) s = trimmed
        }
        s = s.trim()
        if (withComponents) firstSentenceEnd(s)?.let { s = s.substring(0, it) }
        // With components, a list after a colon is what the components show ("Here are the sets: A, B" →
        // "Here are the sets."; not a short lead-in like "Right now: …").
        if (withComponents) {
            val colon = s.indexOf(": ")
            if (colon >= 0 && s.substring(0, colon).split(' ').filter { it.isNotEmpty() }.size >= 3 && s.substring(colon + 2).contains(",")) s = s.substring(0, colon) + "."
        }
        // A list intro left dangling ("Here's the updated preview:") goes too.
        if (s.endsWith(":")) s = s.dropLast(1).trim() + "."
        return s.trim()
    }

    /** The end of the first sentence (after its ". " / "! " / "? "), null for one sentence. */
    private fun firstSentenceEnd(s: String): Int? {
        for (i in s.indices) {
            val c = s[i]
            if (c in ".!?" && i + 1 < s.length && (s[i + 1] == ' ' || s[i + 1] == '\n')) {
                // Not after a one-letter initial ("J. Smith").
                val before = s.substring(0, i).split(' ').lastOrNull().orEmpty()
                if (c != '.' || before.length > 1) return i + 1
            }
            if (c == '\n') return i
        }
        return null
    }

    private val GENERIC_NAMES = setOf("general", "stage", "food", "music", "drinks", "gates open", "info", "main", "kids", "family")

    /** Diacritics folded, case kept (iOS `.diacriticInsensitive`). */
    private fun foldMarks(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")

    /**
     * Schedule items and places the text names, in the order they appear (whole-word matches of the
     * normalised name; the longer name wins where two overlap). Names under 4 characters are never matched;
     * a one-word name only when written with a capital letter (a proper noun, not "music" or "food"), and
     * never a generic word ("general", "stage", "food"…).
     */
    fun namedEntities(text: String, ctx: AssistantContext): List<EntityRef> {
        if (text.isEmpty()) return emptyList()
        val padded = " " + SearchText.words(text).joinToString(" ") + " "
        val folded = foldMarks(text)
        data class Candidate(val ref: EntityRef, val name: String, val words: List<String>)
        val candidates = ctx.bundle.locations.map { Candidate(EntityRef(RefKind.LOCATION, it.id), it.name, SearchText.words(it.name)) } +
            ctx.bundle.schedule.map { Candidate(EntityRef(RefKind.SCHEDULE, it.id), it.name, SearchText.words(it.name)) }
        // Longer names first, so "Main Stage" inside "Main Stage Party" doesn't win.
        val sorted = candidates.sortedWith(
            compareByDescending<Candidate> { it.words.joinToString(" ").length }
                .thenBy { if (it.ref.kind == RefKind.LOCATION) 0 else 1 }.thenBy { it.ref.id },
        )
        val taken = mutableListOf<IntRange>()
        val found = mutableListOf<Pair<Int, EntityRef>>()
        val seen = HashSet<EntityRef>()
        for (candidate in sorted) {
            val phrase = candidate.words.joinToString(" ")
            if (phrase.length < 4 || phrase in GENERIC_NAMES) continue
            if (candidate.words.size == 1) {
                // One word: only as a proper noun in the original text.
                if (phrase.length < 5) continue
                val at = folded.indexOf(foldMarks(candidate.name))
                if (at < 0 || !folded[at].isUpperCase()) continue
            }
            val at = padded.indexOf(" $phrase ")
            if (at < 0) continue
            val range = at until at + phrase.length + 2
            if (taken.any { it.first <= range.last && range.first <= it.last } || !seen.add(candidate.ref)) continue
            taken += range
            found += at to candidate.ref
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * The `show` tool's component as a block: a screen component (`menu`, `faq`, `my_schedule`…) or a chat
     * block built from ids (`card`, `carousel`, `list`, `map`, `timeline`, `now_next`, `countdown`,
     * `gallery`). null = unknown type or no usable ids (the tool answers an error).
     */
    fun showBlock(rawType: String, rawIds: List<String>, title: String?, ctx: AssistantContext): AssistantBlock? {
        val type = ComponentCatalogue.canonical(rawType)
        val refs = rawIds.mapNotNull { EntityRef.parse(it) }.filter { it.exists(ctx.bundle) }
        val titleText = title?.trim()?.takeIf { it.isNotEmpty() }
        ComponentCatalogue.chat(type, refs, titleText)?.takeIf { ComponentCatalogue.valid(it, ctx.bundle) }?.let { return AssistantBlock.ComponentBlock(it) }
        val items = refs.filter { it.kind == RefKind.SCHEDULE }.map { it.id }
        val places = refs.filter { it.kind == RefKind.LOCATION }.map { it.id }
        return when (type) {
            "card", "item_card", "place_card", "update_card" -> refs.firstOrNull()?.let(BlockValidator::card)
            "carousel" -> when {
                refs.isEmpty() -> null
                refs.size == 1 -> BlockValidator.card(refs[0])
                else -> AssistantBlock.Carousel(refs, titleText)
            }
            "list" -> if (refs.isEmpty()) null else AssistantBlock.ListBlock(refs, titleText)
            "map" -> if (places.isEmpty()) null else AssistantBlock.MapBlock(places)
            "timeline" -> if (items.isEmpty()) null else AssistantBlock.Timeline(items, title = titleText)
            "now_next" -> AssistantBlock.NowNext(places.firstOrNull())
            "countdown" -> items.firstOrNull()?.let { AssistantBlock.Countdown(it) }
            "gallery" -> if (places.isEmpty()) null else AssistantBlock.Gallery(places)
            else -> null
        }
    }

    /** The type names `show` accepts, for its description. */
    val showTypes: String
        get() = (listOf("card", "carousel", "list", "map", "timeline", "now_next", "countdown", "gallery") + ComponentCatalogue.chatScreenTypes.sorted()).joinToString(", ")

    /** A write tool's call: the action, or the error text the model gets back (it may retry once). */
    fun toolAction(name: ActionName, params: JsonObject, ctx: AssistantContext): Result<AssistantAction> =
        when (val check = ActionRegistry.check(name.key, params, ctx.bundle, ctx.now)) {
            is ActionCheck.Valid -> Result.success(check.action)
            is ActionCheck.Invalid -> Result.failure(ToolError("Error: ${check.reason}. Use an id like \"s12\" or \"l3\" from ITEMS, SHOWN or MY SCHEDULE (or call find), and times as HH:MM."))
        }

    class ToolError(message: String) : Exception(message)
}

/**
 * The on-device model's 4096-token window, budgeted explicitly: the instructions and tool list, the reply,
 * the earlier turns and the next prompt must fit. Estimates are UTF-8 bytes ÷ 3.5 (rounded up), conservative
 * for English and the ids in prompts (as iOS).
 */
object TokenBudget {
    const val WINDOW = 4096

    /** Instructions + the tool schemas. */
    const val FIXED = 1_300

    /** Room kept for the reply (text + tool calls). */
    const val REPLY = 600

    /** The most a prompt may use in a fresh session. */
    const val FRESH_PROMPT = WINDOW - FIXED - REPLY

    fun estimate(text: String): Int = ceil(text.toByteArray(Charsets.UTF_8).size / 3.5).toInt()

    /** A session with [used] tokens of earlier turns takes another prompt of [prompt] tokens. */
    fun fits(used: Int, prompt: Int): Boolean = FIXED + used + prompt + REPLY <= WINDOW
}

/** A prompt cut to its budget, with what was dropped (logged; never silent): "history:3", "items:4", "facts". */
data class BudgetedPrompt(val text: String, val tokens: Int, val trimmed: List<String>)
