package com.arcu.attnd.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter

/** One turn of the assistant conversation, stored with the plan per event (on the device only). */
@Serializable
data class ConversationTurn(val fromUser: Boolean, val text: String)

/**
 * The conversation (assistant spec §3): the model sees the last [SHOWN] turns verbatim (each cut to
 * 200 characters) and, beyond them, one line with up to 10 distinct keywords of the earlier user
 * turns. At most [MAX_STORED] turns are kept.
 */
data class AssistantConversation(val turns: List<ConversationTurn> = emptyList()) {

    fun with(turn: ConversationTurn): AssistantConversation = AssistantConversation((turns + turn).takeLast(MAX_STORED))

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

    fun encode(): String = json.encodeToString(ListSerializer(ConversationTurn.serializer()), turns)

    companion object {
        const val SHOWN = 6
        const val MAX_STORED = 40
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): AssistantConversation =
            if (text.isNullOrBlank()) AssistantConversation()
            else AssistantConversation(runCatching { json.decodeFromString(ListSerializer(ConversationTurn.serializer()), text) }.getOrDefault(emptyList()).takeLast(MAX_STORED))
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
        }
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
}
