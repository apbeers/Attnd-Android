package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.AssistantFlow
import com.arcu.attnd.domain.AssistantIntent
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.AssistantTurn
import com.arcu.attnd.domain.BlockData
import com.arcu.attnd.domain.BlockJson
import com.arcu.attnd.domain.ConfirmKind
import com.arcu.attnd.domain.ConversationTurn
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.PlanCommands
import com.arcu.attnd.domain.PlanProposal
import com.arcu.attnd.domain.PlanProposals
import com.arcu.attnd.domain.PlanTabFlow
import com.arcu.attnd.domain.ProposalReply
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.SwapCommand
import com.arcu.attnd.domain.TimeMatcher
import com.arcu.attnd.domain.TurnPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The event assistant question eval (attnd-web docs/assistant_eval/README.md), ported from iOS
 * `AttndTests/AssistantEval.swift`: the shared catalogue (questions.json) run against the DEMO bundle
 * (demo_bundle.json), both copied into src/test/resources/assistant_eval/ by scripts/assistant_eval/sync_data.py.
 * Router mode only on the JVM (the model path needs Gemini Nano on a phone). Each question runs in a fresh
 * conversation, its `follows` first (ungraded). A turn mirrors `AssistantController.submit`, plus iOS's
 * swap and sequence commands: a pending proposal's reply, "swap …", sequences, the router, `AssistantFlow.path`.
 * Actions are recorded, never executed, except My schedule adds / removes (the conversation's own state).
 */

// ── Catalogue ───────────────────────────────────────────────────────────────

data class EvalActionExpect(val name: String, val target: String?)

data class EvalExpect(
    val entitiesAny: List<String>? = null,
    val entitiesAll: List<String>? = null,
    val entitiesNone: List<String>? = null,
    val componentsAny: List<String>? = null,
    val chipsAny: List<String>? = null,
    val textAny: List<String>? = null,
    val actionsAny: List<EvalActionExpect>? = null,
    val anyOf: List<EvalExpect>? = null,
    val noWrite: Boolean = false,
    val noSilentWrite: Boolean = false,
    val maxWrites: Int? = null,
    val confirm: Boolean = false,
    val plan: Boolean = false,
    val mustNot: List<String>? = null,
) {
    /** Every entity name it refers to (catalogue check). */
    val names: List<String>
        get() = entitiesAny.orEmpty() + entitiesAll.orEmpty() + entitiesNone.orEmpty() +
            actionsAny.orEmpty().mapNotNull { it.target } + anyOf.orEmpty().flatMap { it.names }

    val rules: List<String> get() = mustNot.orEmpty() + anyOf.orEmpty().flatMap { it.rules }

    companion object {
        private fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }
        private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

        fun of(o: JsonObject): EvalExpect = EvalExpect(
            entitiesAny = o.strings("entities_any"),
            entitiesAll = o.strings("entities_all"),
            entitiesNone = o.strings("entities_none"),
            componentsAny = o.strings("components_any"),
            chipsAny = o.strings("chips_any"),
            textAny = o.strings("text_any"),
            actionsAny = (o["actions_any"] as? JsonArray)?.map {
                val a = it.jsonObject
                EvalActionExpect(a.getValue("name").jsonPrimitive.content, a["target"]?.jsonPrimitive?.contentOrNull)
            },
            anyOf = (o["any_of"] as? JsonArray)?.map { of(it.jsonObject) },
            noWrite = o.flag("no_write"),
            noSilentWrite = o.flag("no_silent_write"),
            maxWrites = (o["max_writes"] as? JsonPrimitive)?.intOrNull,
            confirm = o.flag("confirm"),
            plan = o.flag("plan"),
            mustNot = o.strings("must_not"),
        )
    }
}

data class EvalQuestion(
    val id: String,
    val text: String,
    val category: String,
    val follows: List<String>,
    val critical: Boolean,
    val expect: EvalExpect,
    val expectModel: EvalExpect?,
    val expectRouter: EvalExpect?,
    val notes: String,
)

data class EvalFixture(val now: String, val locale: String, val userAt: String?, val mySchedule: List<Pair<String, String>>)

class EvalCatalogue(val version: Int, val fixture: EvalFixture, val questions: List<EvalQuestion>) {
    private val byId = questions.associateBy { it.id }

    fun question(id: String): EvalQuestion? = byId[id]

    companion object {
        fun resource(name: String): String =
            checkNotNull(EvalCatalogue::class.java.getResource("/assistant_eval/$name")) {
                "assistant_eval/$name missing: run scripts/assistant_eval/sync_data.py"
            }.readText()

        fun load(): EvalCatalogue {
            val root = Json.parseToJsonElement(resource("questions.json")).jsonObject
            val f = root.getValue("fixture").jsonObject
            val fixture = EvalFixture(
                now = f.getValue("now").jsonPrimitive.content,
                locale = f.getValue("locale").jsonPrimitive.content,
                userAt = f["user_at"]?.jsonPrimitive?.contentOrNull,
                mySchedule = f["my_schedule"]?.jsonArray.orEmpty().map {
                    it.jsonObject.getValue("item").jsonPrimitive.content to it.jsonObject.getValue("start").jsonPrimitive.content
                },
            )
            val questions = root.getValue("questions").jsonArray.map { e ->
                val q = e.jsonObject
                EvalQuestion(
                    id = q.getValue("id").jsonPrimitive.content,
                    text = q.getValue("text").jsonPrimitive.content,
                    category = q.getValue("category").jsonPrimitive.content,
                    follows = q["follows"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                    critical = (q["critical"] as? JsonPrimitive)?.booleanOrNull == true,
                    expect = EvalExpect.of(q.getValue("expect").jsonObject),
                    expectModel = (q["expect_model"] as? JsonObject)?.let(EvalExpect::of),
                    expectRouter = (q["expect_router"] as? JsonObject)?.let(EvalExpect::of),
                    notes = q["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            return EvalCatalogue(root.getValue("version").jsonPrimitive.intOrNull ?: 0, fixture, questions)
        }
    }
}

// ── One answered turn ───────────────────────────────────────────────────────

/** What one turn produced, in platform-neutral terms (the report's shape, the same as iOS's). */
data class EvalOutcome(
    var path: String = "router",
    var intent: String? = null,
    var blocks: List<AssistantBlock> = emptyList(),
    var actions: List<AssistantAction> = emptyList(),
    var proposalText: String? = null,
    var proposalOptions: List<String> = emptyList(),
    var proposalApplied: String? = null,
    var ms: Long = 0,
)

class AssistantEvalRunner {
    val catalogue = EvalCatalogue.load()
    val bundleJson = EvalCatalogue.resource("demo_bundle.json")
    val bundle: EventBundle = checkNotNull(ApiMapper.parseBundle(bundleJson)) { "demo_bundle.json doesn't decode" }
    val now: Instant = Instant.parse(catalogue.fixture.now)

    /** The fixture's context: now, the user at `user_at`, My schedule's entries (no plan, no model). */
    fun baseContext(): AssistantContext {
        val schedule = MySchedule()
        val items = mutableSetOf<Int>()
        for ((name, start) in catalogue.fixture.mySchedule) {
            val at = Instant.parse(start)
            val item = checkNotNull(bundle.schedule.firstOrNull { it.name == name && it.startTime?.toInstant() == at }) {
                "eval fixture: no $name at $start"
            }
            schedule.add(ScheduleTarget(RefKind.SCHEDULE.searchKind, item.id))
            items += item.id
        }
        val user = catalogue.fixture.userAt?.let { name -> bundle.locations.firstOrNull { it.name == name }?.coordinate }
        val locale = Locale.forLanguageTag(catalogue.fixture.locale.replace('_', '-'))
        return AssistantContext(
            bundle = bundle, hearts = Hearts(scheduleItems = items), plan = null, now = now, user = user,
            modelAvailable = false, units = DistanceUnits.forRegion(locale.country), locale = locale, schedule = schedule,
        )
    }

    /** One question's conversation: its `follows` first, then the question. */
    class Conversation(var ctx: AssistantContext) {
        var conversation = AssistantConversation()
        var proposal: PlanProposal? = null
    }

    fun run(question: EvalQuestion): EvalOutcome {
        val c = Conversation(baseContext())
        for (id in chain(question)) {
            val earlier = checkNotNull(catalogue.question(id)) { "eval: ${question.id} follows unknown $id" }
            turn(earlier.text, c)
        }
        return turn(question.text, c)
    }

    /** The `follows` chain, earliest first (follows of follows included). */
    fun chain(question: EvalQuestion): List<String> = question.follows.flatMap { id ->
        catalogue.question(id)?.let { chain(it) }.orEmpty() + id
    }

    /** My schedule's writes change the conversation's state; everything else is only recorded. */
    private fun apply(actions: List<AssistantAction>, c: Conversation) {
        for (action in actions) {
            val ref = action.scheduleTarget() ?: continue
            val target = ScheduleTarget(ref.kind.searchKind, ref.id)
            val hearts = c.ctx.hearts
            when (action.name) {
                ActionName.SCHEDULE_ADD -> {
                    c.ctx.schedule.add(target)
                    c.ctx = c.ctx.copy(
                        hearts = if (ref.kind == RefKind.SCHEDULE) hearts.copy(scheduleItems = hearts.scheduleItems + ref.id)
                        else hearts.copy(locations = hearts.locations + ref.id),
                    )
                }
                ActionName.SCHEDULE_REMOVE -> {
                    c.ctx.schedule.remove(target)
                    c.ctx = c.ctx.copy(hearts = Hearts(hearts.locations - ref.id, hearts.scheduleItems - ref.id))
                }
                else -> Unit
            }
        }
    }

    /** One turn, as `AssistantController.submit` runs it (with iOS's swap and sequence commands). */
    fun turn(input: String, c: Conversation): EvalOutcome {
        val started = System.nanoTime()
        val text = input.trim()
        c.ctx = c.ctx.copy(memory = c.conversation.memory)
        val ctx = c.ctx
        val out = EvalOutcome()

        fun done(input: String?, turn: AssistantTurn?): EvalOutcome {
            out.ms = (System.nanoTime() - started) / 1_000_000
            if (input != null) c.conversation = c.conversation.with(ConversationTurn(fromUser = true, text = input))
            if (turn != null) {
                c.conversation = c.conversation.with(ConversationTurn(fromUser = false, text = AssistantFlow.replyText(turn, ctx.bundle)))
                    .remembering(turn)
            }
            apply(out.actions, c)
            return out
        }

        fun propose(p: PlanProposal): EvalOutcome {
            out.proposalText = p.summary
            out.proposalOptions = p.options.map { it.label }
            c.proposal = p
            return done(text, AssistantTurn(listOf(AssistantBlock.Text(p.summary))))
        }

        c.proposal?.let { proposal ->
            if (text.isNotEmpty()) {
                when (val reply = ProposalReply.parse(text, proposal.options.size)) {
                    is ProposalReply.Apply -> {
                        c.proposal = null
                        out.path = "proposal_reply"
                        val option = proposal.options[reply.index]
                        out.proposalApplied = option.label
                        out.actions = listOfNotNull(option.action)
                        return done(text, null)
                    }
                    ProposalReply.Cancel -> {
                        c.proposal = null
                        out.path = "proposal_reply"
                        out.proposalApplied = "cancelled"
                        return done(text, null)
                    }
                    is ProposalReply.Invalid -> {
                        out.path = "proposal_reply"
                        out.blocks = listOf(AssistantBlock.Text(reply.text))
                        return done(text, null)
                    }
                    ProposalReply.None -> c.proposal = null
                }
            }
        }
        if (text.isEmpty()) {
            val routed = AssistantRouter.route("", ctx)
            out.intent = AssistantIntent.EMPTY.name.lowercase()
            out.blocks = routed.blocks
            return done(null, routed.turn)
        }
        PlanCommands.swap(text, ctx)?.let { swap ->
            out.intent = "plan_edit"
            return when (swap) {
                is SwapCommand.Proposal -> propose(swap.proposal)
                is SwapCommand.Message -> {
                    out.blocks = listOf(AssistantBlock.Text(swap.text))
                    done(text, AssistantTurn(out.blocks))
                }
            }
        }
        PlanCommands.sequence(text, ctx)?.let {
            out.intent = "sequence"
            return propose(it)
        }
        val routed = AssistantRouter.route(text, ctx)
        out.intent = routed.intent.name.lowercase()
        val turn = when (AssistantFlow.path(routed.intent, text, ctx.modelAvailable)) {
            TurnPath.SEARCH -> PlanTabFlow.withoutModel(routed, text, ctx)
            TurnPath.PLAN -> AssistantFlow.planTurn(text, routed, ctx)
            TurnPath.ASK -> error("router mode never asks the model")
        }
        PlanProposals.fromTurn(turn, ctx)?.let { return propose(it) }
        out.blocks = turn.blocks
        out.actions = turn.actions
        return done(text, turn)
    }
}

// ── Grading ─────────────────────────────────────────────────────────────────

class EvalGrade(val failures: List<String>) {
    val passed: Boolean get() = failures.isEmpty()
}

class AssistantEvalGrader(private val bundle: EventBundle, private val now: Instant, bundleJson: String) {
    private val bundleText = bundleJson.lowercase()

    /** The answer's own words: text blocks, confirm prompts and a proposal's sentence (bold markers dropped). */
    fun answerText(o: EvalOutcome): String = (
        o.blocks.mapNotNull {
            when (it) {
                is AssistantBlock.Text -> it.text.replace("**", "")
                is AssistantBlock.Confirm -> it.prompt
                else -> null
            }
        } + listOfNotNull(o.proposalText)
        ).joinToString("\n")

    fun chipLabels(o: EvalOutcome): List<String> =
        o.blocks.filterIsInstance<AssistantBlock.Chips>().flatMap { b -> b.chips.map { it.label } } + o.proposalOptions

    /** Every entity a block shows (cards, lists, maps, timelines incl. their sources, components). */
    fun shownRefs(o: EvalOutcome): List<EntityRef> {
        val refs = mutableListOf<EntityRef>()
        fun add(block: AssistantBlock) {
            refs += AssistantFlow.refs(block)
            when (block) {
                is AssistantBlock.Timeline -> if (block.ids.isEmpty() && block.source != null) {
                    refs += BlockData.timeline(block, bundle, null, now).map { EntityRef(RefKind.SCHEDULE, it) }
                }
                is AssistantBlock.NowNext -> {
                    block.location?.let { refs += EntityRef(RefKind.LOCATION, it) }
                    refs += nowNextIds(block.location).map { EntityRef(RefKind.SCHEDULE, it) }
                }
                is AssistantBlock.ComponentBlock -> {
                    refs += block.component.ids
                    block.component.block?.let(::add)
                }
                else -> Unit
            }
        }
        o.blocks.forEach(::add)
        return refs
    }

    /** What a now/next block shows: items in progress and the next two starts (per place when given). */
    private fun nowNextIds(location: Int?): List<Int> {
        val items = bundle.schedule.filter { location == null || it.locationId == location }
        val on = items.filter { item ->
            val start = item.startTime?.toInstant() ?: return@filter false
            val end = TimeMatcher.effectiveEnd(item) ?: return@filter false
            !start.isAfter(now) && end.isAfter(now)
        }
        val next = items.filter { it.startTime?.toInstant()?.isAfter(now) == true }.sortedBy { it.startTime!!.toInstant() }.take(2)
        return on.map { it.id } + next.map { it.id }
    }

    fun shownNames(o: EvalOutcome): List<String> = shownRefs(o).mapNotNull { it.name(bundle) }.distinct()

    private fun actionTargets(action: AssistantAction): List<String> =
        action.params.values.mapNotNull { v -> EntityRef.of(v)?.name(bundle) }

    private fun writes(o: EvalOutcome): List<AssistantAction> = o.actions.filter { it.name.mutates && it.name.confirm == ConfirmKind.NONE }

    fun blockTypes(o: EvalOutcome): Set<String> =
        o.blocks.map { it.type }.toSet() + o.blocks.filterIsInstance<AssistantBlock.ComponentBlock>().map { it.component.type }

    fun grade(q: EvalQuestion, o: EvalOutcome): EvalGrade =
        EvalGrade(check(q.expect, o, "") + (q.expectRouter?.let { check(it, o, "router: ") }.orEmpty()))

    fun check(e: EvalExpect, o: EvalOutcome, label: String): List<String> {
        val failures = mutableListOf<String>()
        val text = answerText(o)
        val lower = text.lowercase()
        val shown = shownNames(o)
        val mentionable = shown.map { it.lowercase() }.toSet()
        fun seen(name: String) = name.lowercase() in mentionable || lower.contains(name.lowercase())
        e.entitiesAny?.let { names ->
            if (names.isNotEmpty() && names.none(::seen)) failures += "${label}none of $names shown (shown: ${shown.take(6)})"
        }
        e.entitiesAll?.let { names ->
            val missing = names.filterNot(::seen)
            if (missing.isNotEmpty()) failures += "${label}missing $missing"
        }
        e.entitiesNone?.let { names ->
            val wrong = names.filter { it.lowercase() in mentionable }
            if (wrong.isNotEmpty()) failures += "${label}should not show $wrong"
        }
        e.componentsAny?.let { types ->
            val got = blockTypes(o)
            if (types.none { it in got }) failures += "${label}no component of $types (got ${got.sorted()})"
        }
        e.chipsAny?.let { wanted ->
            val chips = chipLabels(o).map { it.lowercase() }
            if (wanted.none { w -> chips.any { it.contains(w.lowercase()) } }) failures += "${label}no chip like $wanted"
        }
        e.textAny?.let { words ->
            if (words.none { lower.contains(it.lowercase()) }) failures += "${label}text has none of $words"
        }
        e.actionsAny?.let { wanted ->
            val found = wanted.any { w -> o.actions.any { a -> a.name.key == w.name && (w.target == null || w.target in actionTargets(a)) } }
            if (!found) {
                val got = o.actions.map { "${it.name.key}${actionTargets(it)}" }
                failures += "${label}no action like ${wanted.map { "${it.name} ${it.target.orEmpty()}" }} (got $got)"
            }
        }
        e.anyOf?.takeIf { it.isNotEmpty() }?.let { alternatives ->
            val results = alternatives.map { check(it, o, "") }
            if (results.none { it.isEmpty() }) {
                failures += "${label}none of the alternatives: " + results.joinToString(" | ") { it.joinToString("; ") }
            }
        }
        if (e.noWrite) {
            val w = writes(o)
            if (w.isNotEmpty()) failures += "${label}unexpected write ${w.map { it.name.key }}"
            if (o.proposalText != null) failures += "${label}unexpected plan proposal"
        }
        if (e.noSilentWrite) {
            val w = o.actions.filter { it.name.mutates }
            if (w.isNotEmpty()) failures += "${label}write without a confirm ${w.map { it.name.key }}"
        }
        e.maxWrites?.let { max -> if (writes(o).size > max) failures += "$label${writes(o).size} writes (max $max)" }
        if (e.confirm) {
            val confirms = o.blocks.any { it is AssistantBlock.Confirm }
            if (!confirms) failures += "${label}no confirm chip"
            val silent = o.actions.filter { it.name.mutates && it.name.confirm != ConfirmKind.NONE }
            if (silent.isNotEmpty() && !confirms) failures += "${label}setting changed without a confirm"
        }
        if (e.plan) {
            val planned = o.proposalText != null ||
                o.actions.any { it.name in setOf(ActionName.PLAN_MAKE, ActionName.PLAN_EDIT, ActionName.PLAN_VERSION) }
            if (!planned) failures += "${label}no plan (proposal or plan_make)"
        }
        for (rule in e.mustNot.orEmpty()) violation(rule, text)?.let { failures += "$label$rule: $it" }
        if (o.blocks.isEmpty() && o.actions.isEmpty() && o.proposalText == null && o.proposalApplied == null) {
            failures += "${label}empty answer"
        }
        return failures
    }

    /** null = fine; else what broke the rule. */
    fun violation(rule: String, text: String): String? {
        val lower = text.lowercase()
        fun matches(pattern: String): List<String> = Regex(pattern, RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
        fun has(pattern: String) = Regex(pattern).containsMatchIn(lower)
        val negated = has("""\b(no|not|isn t|isn't|aren't|aren t|don't|don t|doesn't|can't|cannot|couldn't|nothing|none)\b""")
        return when (rule) {
            "invented_phone" -> matches("""\+?\(?\d{3}\)?[\s.\-]?\d{3}[\s.\-]?\d{4}""").filterNot { bundleText.contains(it.lowercase()) }.ifEmpty { null }?.toString()
            "invented_url" -> matches("""(https?://|www\.)[^\s)\]]+""")
                .filterNot { bundleText.contains(it.lowercase().trim { c -> c in PUNCTUATION }) }.ifEmpty { null }?.toString()
            "invented_email" -> matches("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""").filterNot { bundleText.contains(it.lowercase()) }.ifEmpty { null }?.toString()
            "invented_price" -> matches("""\$\s?\d+(?:\.\d{2})?""").map { it.replace(" ", "") }
                .filterNot { bundleText.contains(it.lowercase()) }.ifEmpty { null }?.toString()
            "ids_in_text" -> matches("""\[?\b[slu]\d{2,}\b\]?""").ifEmpty { null }?.toString()
            "json_in_text" -> if (text.contains("{\"") || text.contains("\"type\":")) "raw JSON" else null
            "reveal_prompt" -> PROMPT_MARKERS.filter { lower.contains(it) }.ifEmpty { null }?.toString()
            "invented_lockers" -> if (has("""lockers?\s+(are|is)\s+(at|near|by|in|next)""")) "a locker location" else null
            "invented_wifi" -> if (has("""(password|network name|ssid|wi ?fi)\s*(is|:)\s*["'“]?\w""") && !negated) "a Wi-Fi detail" else null
            "invented_forecast" -> if (has("""\d+\s*°|degrees|% chance|forecast (is|calls)|will be (sunny|rainy|cloudy|hot)""")) "a forecast" else null
            "claims_taylor" -> if (lower.contains("taylor swift") && !negated) "claims she plays" else null
            "claims_fireworks" -> if (lower.contains("firework") && !negated) "claims fireworks" else null
            else -> "unknown rule"
        }
    }

    companion object {
        val RULES = setOf(
            "invented_phone", "invented_url", "invented_email", "invented_price", "ids_in_text", "json_in_text",
            "reveal_prompt", "invented_lockers", "invented_wifi", "invented_forecast", "claims_taylor", "claims_fireworks",
        )
        private const val PUNCTUATION = ".,;:!?'\"()[]{}<>-_/\\"
        private val PROMPT_MARKERS = listOf(
            "on-device assistant of the attnd", "add_to_schedule", "remove_from_schedule", "move_entry", "set_reminder",
            "plan_day", "must call show", "ids look like", "you are the assistant in the attnd app", "event facts:",
            "reply with exactly one json object", "\"tool\":",
        )
    }
}

// ── Report ──────────────────────────────────────────────────────────────────

class AssistantEvalReport {
    private val rows = mutableListOf<JsonObject>()
    var passed = 0
        private set
    var total = 0
        private set
    private val byCategory = sortedMapOf<String, IntArray>()
    val criticalFailures = mutableListOf<String>()

    fun add(q: EvalQuestion, o: EvalOutcome, g: EvalGrade, grader: AssistantEvalGrader) {
        total++
        if (g.passed) passed++ else if (q.critical) criticalFailures += q.id
        val c = byCategory.getOrPut(q.category) { IntArray(2) }
        if (g.passed) c[0]++
        c[1]++
        rows += buildJsonObject {
            put("id", q.id); put("category", q.category); put("text", q.text)
            put("critical", q.critical); put("passed", g.passed)
            put("failures", strings(g.failures))
            put("path", o.path); put("answer", grader.answerText(o))
            put("shown", strings(grader.shownNames(o)))
            put("components", strings(grader.blockTypes(o).sorted()))
            put("chips", strings(grader.chipLabels(o)))
            put("actions", JsonArray(o.actions.map(BlockJson::encodeAction)))
            put("blocks", JsonArray(o.blocks.map(BlockJson::encode)))
            put("ms", o.ms); put("notes", q.notes)
            o.intent?.let { put("intent", it) }
            o.proposalText?.let { put("proposal", it) }
            o.proposalApplied?.let { put("proposal_applied", it) }
        }
    }

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    fun json(now: String): String = buildJsonObject {
        put("platform", "android"); put("mode", "router"); put("fixture_now", now)
        put("passed", passed); put("total", total)
        put("categories", buildJsonObject {
            byCategory.forEach { (k, v) -> put(k, buildJsonObject { put("passed", v[0]); put("total", v[1]) }) }
        })
        put("critical_failures", strings(criticalFailures))
        put("results", JsonArray(rows))
    }.let { PRETTY.encodeToString(JsonElement.serializer(), it) }

    val summary: String
        get() = buildList {
            add("Assistant eval (router): $passed/$total passed")
            byCategory.forEach { (k, v) -> add("  " + k.padEnd(16) + "${v[0]}/${v[1]}") }
            if (criticalFailures.isNotEmpty()) add("  critical failures: ${criticalFailures.joinToString(", ")}")
        }.joinToString("\n")

    /** Writes the report into [dir] (default build/assistant-eval of the app module); returns the file. */
    fun write(now: String, dir: File): File {
        dir.mkdirs()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        return File(dir, "assistant-eval-android-router-$stamp.json").also { it.writeText(json(now) + "\n") }
    }

    companion object {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = " " }
    }
}
