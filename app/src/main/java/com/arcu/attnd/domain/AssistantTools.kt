package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Android's tool protocol for the on-device model (Gemini Nano through ML Kit's Prompt API has no tool
 * calling): the same ten tools as iOS's Foundation Models session (`EventAssistant.swift`), as plain JSON
 * text. Pure; the platform's loop (phase 6b) only sends prompts and reads replies:
 *
 *  1. The prompt carries [promptSection] (the tools and the rules) above the turn's prompt
 *     ([AssistantModel.toolPrompt], budgeted).
 *  2. The model replies with exactly one JSON object, either a call `{"tool":"find","args":{"query":"tacos"}}`
 *     or the answer `{"answer":"Taco Row is open until 9."}`. [parse] reads the first complete object of the
 *     reply (text around it and anything after it ignored); `name` / `arguments` / `text` are accepted as
 *     aliases; anything else is [ToolReply.Invalid].
 *  3. [ToolTurn.step] runs a call and gives back the line for the next prompt (`RESULT find: …`): reads
 *     answer from the bundle; writes are validated through [ActionRegistry.check] ([AssistantModel.toolAction])
 *     and only recorded (they run after the turn through the [ActionDispatcher], with Undo / confirm);
 *     `show` adds a component ([AssistantModel.showBlock]). A bad call answers "Error: …" so the model can
 *     fix it; after [MAX_ERRORS] errors a tool answers "Error: stop calling …" without running.
 *  4. At most [MAX_ROUNDS] calls per turn; the next reply must then be the answer. An answer (or the round
 *     limit) finishes the turn through [AssistantModel.assemble]; an invalid reply, or a turn that ends with
 *     nothing to show, is [ToolStep.Fail] and the router answers instead.
 */
object AssistantTools {

    const val MAX_ROUNDS = 4
    const val MAX_ERRORS = 2

    enum class Kind { READ, WRITE, SHOW }

    data class Param(val name: String, val about: String, val required: Boolean = true)

    data class Tool(val name: String, val kind: Kind, val about: String, val params: List<Param>, val action: ActionName? = null) {
        /** `add_to_schedule(target, time?)`. */
        val signature: String get() = name + "(" + params.joinToString(", ") { it.name + if (it.required) "" else "?" } + ")"
    }

    val TOOLS: List<Tool> = listOf(
        Tool("find", Kind.READ, "search the schedule, places (menus, performers, amenities, tags) and updates; time words filter the schedule: now, next, tonight, tomorrow, a weekday, 7pm", listOf(Param("query", "a few keywords, e.g. \"tacos\", \"music tonight\""))),
        Tool("event_info", Kind.READ, "the event's own information: about, FAQ, amenities with their place, accessibility, policies, getting there, the weather plan", listOf(Param("topic", "\"faq\", \"amenities\", \"restrooms\", \"accessibility\", \"policies\", \"getting there\", \"weather\" or \"all\"", required = false))),
        Tool("my_schedule", Kind.READ, "what is in the attendee's schedule", listOf(Param("day", "YYYY-MM-DD, or empty for everything ahead", required = false))),
        Tool("now_next", Kind.READ, "what is on now and next, at one place or everywhere", listOf(Param("place", "a place id like \"l3\", or empty", required = false))),
        Tool("add_to_schedule", Kind.WRITE, "add a schedule item (s12) or a place (l3) to the attendee's schedule; places take a time, else they are saved", listOf(Param("target", "\"s12\" or \"l3\""), Param("time", "for places: HH:MM (24-hour)", required = false)), ActionName.SCHEDULE_ADD),
        Tool("remove_from_schedule", Kind.WRITE, "remove an item or place from the attendee's schedule", listOf(Param("target", "\"s12\" or \"l3\"")), ActionName.SCHEDULE_REMOVE),
        Tool("move_entry", Kind.WRITE, "move a place in the attendee's schedule to another time (items keep the organizer's time)", listOf(Param("target", "a place id \"l3\""), Param("time", "HH:MM (24-hour)")), ActionName.SCHEDULE_MOVE),
        Tool("set_reminder", Kind.WRITE, "minutes before an entry the reminder comes: 5, 10, 15, 30, 60, or 0 for none", listOf(Param("target", "\"s12\" or \"l3\""), Param("minutes", "a number")), ActionName.SCHEDULE_REMINDER),
        Tool("plan_day", Kind.WRITE, "plan the attendee's day (or part of it) into their schedule, e.g. \"Saturday around country music\"", listOf(Param("request", "the request in a few words")), ActionName.PLAN_MAKE),
        Tool("show", Kind.SHOW, "show a component under the answer: ${AssistantModel.showTypes}", listOf(Param("component", "the type, e.g. \"carousel\", \"menu\" or \"faq\""), Param("ids", "ids it shows, e.g. [\"l3\"]; empty for event-wide parts", required = false), Param("title", "a short title", required = false))),
    )

    fun tool(name: String?): Tool? = TOOLS.firstOrNull { it.name == name?.trim()?.lowercase() }

    /** The instructions + tool list, the fixed part of every model prompt (≈ [TokenBudget.FIXED]). */
    fun promptSection(): List<String> = buildList {
        add("You are the on-device assistant of the Attnd event app. Answer in one or two short, friendly sentences in the attendee's language. Use only facts from this prompt and the tools; never invent places, times or ids. Ids look like s12 (schedule item), l3 (place), u9 (update). \"It\", \"that\" and \"the second one\" mean the SHOWN entries.")
        add("Reply with exactly one JSON object and nothing else. To call a tool: {\"tool\":\"<name>\",\"args\":{...}}. You get its RESULT and reply again. When done: {\"answer\":\"<text>\"}.")
        add("TOOLS:")
        TOOLS.forEach { tool -> add("- ${tool.signature}: ${tool.about}" + tool.params.joinToString("") { "; ${it.name} = ${it.about}" }) }
        add("RULES: at most $MAX_ROUNDS tool calls, then answer. Every answer that names a schedule item or a place MUST call show with their ids first (card for one, carousel for several, now_next for what's on, timeline for times). With a component, write ONE short sentence and never list what it shows. Never write ids in the answer, no filler like \"Anything else?\". \"Music, food, then more music\" is a plan: call plan_day. \"My plan\", \"itinerary\" and \"agenda\" mean My schedule. If a tool returns Error, fix the call and try once more.")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** What one model reply asks for. */
    sealed interface ToolReply {
        data class Call(val tool: String, val args: JsonObject) : ToolReply
        data class Answer(val text: String) : ToolReply
        data class Invalid(val reason: String) : ToolReply
    }

    /** Reads the first complete JSON object of [raw] (string-aware brace matching), see the file comment. */
    fun parse(raw: String): ToolReply {
        val text = firstObject(raw) ?: return ToolReply.Invalid("no json")
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return ToolReply.Invalid("not an object")
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        (str("answer") ?: str("text"))?.let { return ToolReply.Answer(it) }
        val name = str("tool") ?: str("name") ?: return ToolReply.Invalid("no tool or answer")
        val args = when (val a = obj["args"] ?: obj["arguments"] ?: obj["params"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            is JsonPrimitive -> if (a.isString) runCatching { json.parseToJsonElement(a.content) as? JsonObject }.getOrNull() ?: return ToolReply.Invalid("bad args") else return ToolReply.Invalid("bad args")
            else -> return ToolReply.Invalid("bad args")
        }
        return ToolReply.Call(name.trim().lowercase(), args)
    }

    /** The first balanced `{…}` in [raw], or null. */
    fun firstObject(raw: String): String? {
        val start = raw.indexOf('{').takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (inString) {
                if (escape) escape = false else if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return raw.substring(start, i + 1)
            }
        }
        return null
    }

    /** What [ToolTurn.step] did with one reply. */
    sealed interface ToolStep {
        /** Send [result] (one `RESULT …` line) back to the model and read its next reply. */
        data class Continue(val result: String, val stage: String?) : ToolStep

        /** The turn is answered. */
        data class Finish(val turn: AssistantTurn) : ToolStep

        /** The model failed this turn: the router answers. */
        data class Fail(val reason: String) : ToolStep
    }

    /** The event's own facts for `event_info` (as iOS `EventAssistant.eventFacts`). */
    fun eventFacts(bundle: EventBundle, topic: String): String {
        val context = bundle.event.context
        val want = SearchText.fold(topic).trim()
        fun wanted(words: List<String>) = want.isEmpty() || want == "all" || words.any { want.contains(it) }
        val lines = mutableListOf<String>()
        if (context != null) {
            if (context.about.isNotEmpty() && wanted(listOf("about", "event", "what"))) lines += "About: " + context.about.take(300)
            if (context.faq.isNotEmpty() && wanted(listOf("faq", "question", "bag", "pet", "entry", "rule", "ticket", "polic"))) {
                lines += context.faq.take(8).map { "Q: ${it.question} A: ${it.answer.take(200)}" }
            }
            if (context.amenities.isNotEmpty() && wanted(listOf("amenit", "restroom", "toilet", "water", "atm", "first aid", "charg", "locker", "parking", "shuttle", "food", "merch", "info", "access"))) {
                lines += context.amenities.take(12).map { a ->
                    buildString {
                        append("Amenity: ${a.kind.label} — ${a.name}")
                        bundle.location(a.locationId)?.let { append(" at [l${it.id}] ${it.name}") }
                        if (a.note.isNotEmpty()) append(" (${a.note})")
                    }
                }
            }
            if (context.accessibility.isNotEmpty() && wanted(listOf("access", "wheelchair", "step", "sensory"))) lines += "Accessibility: " + context.accessibility.take(300)
            if (context.policies.isNotEmpty() && wanted(listOf("polic", "rule", "bag", "allowed", "bring"))) lines += "Policies: " + context.policies.take(300)
            if (context.gettingThere.isNotEmpty() && wanted(listOf("getting", "there", "parking", "bus", "train", "shuttle", "drive", "transit"))) lines += "Getting there: " + context.gettingThere.take(300)
            if (context.weatherPlan.isNotEmpty() && wanted(listOf("weather", "rain", "storm", "heat"))) lines += "Weather plan: " + context.weatherPlan.take(200)
        }
        if (lines.isEmpty()) {
            return if (want == "all" || context == null) "The organizer hasn't added venue information for this event." else eventFacts(bundle, "all")
        }
        val out = StringBuilder()
        for (line in lines) if (out.length + line.length < 1_600) out.append(if (out.isEmpty()) "" else "\n").append(line)
        return out.toString()
    }

    /**
     * One model turn's tool calls (pure state; the platform loop feeds it the model's replies). Records the
     * writes and components, counts calls and errors per tool, and finishes through [AssistantModel.assemble].
     */
    class ToolTurn(private val ctx: AssistantContext) {
        private val actions = mutableListOf<AssistantAction>()
        private val shown = mutableListOf<AssistantBlock>()
        private val errors = HashMap<String, Int>()

        var calls = 0
            private set

        val recordedActions: List<AssistantAction> get() = actions
        val shownBlocks: List<AssistantBlock> get() = shown

        fun step(raw: String): ToolStep = when (val reply = parse(raw)) {
            is ToolReply.Invalid -> ToolStep.Fail(reply.reason)
            is ToolReply.Answer -> finish(reply.text)
            is ToolReply.Call -> if (calls >= MAX_ROUNDS) finish("") else call(reply.tool, reply.args)
        }

        /** Ends the turn with [text] (the round limit, or the platform giving up after a model error). */
        fun finish(text: String): ToolStep {
            val turn = AssistantModel.assemble(text, shown, actions, ctx)
            return if (turn.blocks.isEmpty() && turn.actions.isEmpty()) ToolStep.Fail("empty answer") else ToolStep.Finish(turn)
        }

        private fun result(tool: String, text: String, stage: String?) = ToolStep.Continue("RESULT $tool: $text", stage)

        private fun string(args: JsonObject, key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()

        private fun call(name: String, args: JsonObject): ToolStep {
            calls++
            val tool = tool(name) ?: return result(name, "Error: unknown tool. Tools: ${TOOLS.joinToString(", ") { it.name }}.", null)
            if ((errors[tool.name] ?: 0) >= MAX_ERRORS) return result(tool.name, "Error: stop calling ${tool.name} for this request; answer in words.", null)
            return when (tool.kind) {
                Kind.READ -> read(tool.name, args)
                Kind.WRITE -> write(tool, args)
                Kind.SHOW -> show(args)
            }
        }

        private fun fail(tool: String, text: String): ToolStep {
            errors[tool] = (errors[tool] ?: 0) + 1
            return result(tool, text, null)
        }

        private fun read(tool: String, args: JsonObject): ToolStep {
            val bundle = ctx.bundle
            val zone = bundle.event.zone
            return when (tool) {
                "find" -> {
                    val query = string(args, "query")
                    if (query.isEmpty()) return fail(tool, "Error: give a query.")
                    val results = EventSearch.search(bundle, query, ctx.now, limit = 8)
                        .ifEmpty { EventSearch.search(bundle, SearchQuery.keywords(query), ctx.now, SearchMode.ANY_TOKEN, limit = 8) }
                    val lines = results.mapNotNull { r ->
                        val ref = EntityRef.of(r) ?: return@mapNotNull null
                        val detail = EventSearch.detail(r, bundle, ctx.locale)
                        "[${ref.code}] ${r.title}" + (if (detail.isEmpty()) "" else " — $detail") + (AssistantModel.contextLine(ref, bundle)?.let { " — $it" }.orEmpty())
                    }
                    result(tool, if (lines.isEmpty()) "Nothing in the event matches \"$query\"." else lines.joinToString("\n"), "Searching…")
                }
                "event_info" -> result(tool, eventFacts(bundle, string(args, "topic")), "Reading the event info…")
                "my_schedule" -> {
                    val day = runCatching { LocalDate.parse(string(args, "day")) }.getOrNull()
                    val rows = day?.let { ctx.schedule.rows(it, bundle, ctx.now) } ?: ctx.schedule.rows(bundle, ctx.now).filter { !it.ended }
                    val format = DateTimeFormatter.ofPattern("EEE h:mm a", ctx.locale)
                    val lines = rows.map { row ->
                        "[${if (row.entry.kind == ScheduleEntryKind.ITEM) "s" else "l"}${row.entry.id}] ${row.name} — ${row.start.atZone(zone).format(format)}" +
                            if (row.entry.reminderMinutes == 0) " — no reminder" else " — reminder ${row.entry.reminderMinutes} min before"
                    }
                    result(tool, if (lines.isEmpty()) "My schedule is empty." else lines.joinToString("\n"), "Checking your schedule…")
                }
                else -> {
                    val place = EntityRef.parse(string(args, "place"))?.takeIf { it.kind == RefKind.LOCATION }?.id
                    val format = DateTimeFormatter.ofPattern("h:mm a", ctx.locale)
                    val items = bundle.schedule.filter { (place == null || it.locationId == place) && it.startTime != null && TimeMatcher.effectiveEnd(it)?.isAfter(ctx.now) == true }
                        .sortedWith(Planner.ITEM_ORDER).take(6)
                    val lines = items.map { item ->
                        val state = if (!item.startTime!!.toInstant().isAfter(ctx.now)) "now" else item.startTime.atZoneSameInstant(zone).format(format)
                        "[s${item.id}] ${item.name} — $state" + (bundle.location(item.locationId)?.let { " — [l${it.id}] ${it.name}" }.orEmpty())
                    }
                    result(tool, if (lines.isEmpty()) "Nothing is on now or next." else lines.joinToString("\n"), "Checking what's on…")
                }
            }
        }

        private fun write(tool: Tool, args: JsonObject): ToolStep {
            val name = tool.action ?: return fail(tool.name, "Error: not available.")
            if (name == ActionName.PLAN_MAKE) {
                val request = string(args, "request")
                if (request.isEmpty()) return fail(tool.name, "Error: say what the plan is about.")
                actions += AssistantAction.planMake(request)
                return result(tool.name, "Planning now; the plan appears as a preview in the app.", "Planning your day…")
            }
            // Empty strings mean "not given" (the iOS tools' convention); "minutes" may come as "30".
            val params = buildJsonObject {
                for ((key, value) in args) {
                    val p = value as? JsonPrimitive ?: continue
                    if (p is JsonNull || (p.isString && p.content.isBlank())) continue
                    val number = if (key == "minutes") (p.intOrNull ?: p.content.trim().toIntOrNull())?.let { JsonPrimitive(it) } else null
                    put(key, number ?: p)
                }
            }
            return AssistantModel.toolAction(name, params, ctx).fold(
                onSuccess = { action ->
                    actions += action
                    val target = action.scheduleTarget()?.name(ctx.bundle) ?: "it"
                    val stage = when (name) {
                        ActionName.SCHEDULE_ADD -> "Adding $target…"
                        ActionName.SCHEDULE_REMOVE -> "Removing $target…"
                        ActionName.SCHEDULE_MOVE -> "Moving $target…"
                        else -> "Setting a reminder for $target…"
                    }
                    result(tool.name, "Done: ${name.key} $target. The app shows the result with Undo.", stage)
                },
                onFailure = { fail(tool.name, it.message ?: "Error.") },
            )
        }

        private fun show(args: JsonObject): ToolStep {
            val type = string(args, "component").ifEmpty { string(args, "type") }
            val ids: List<String> = when (val raw = args["ids"]) {
                is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> raw.content.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList<String>()
            } + listOfNotNull((args["id"] as? JsonPrimitive)?.content)
            val block = AssistantModel.showBlock(type, ids, string(args, "title"), ctx)
                ?: return fail("show", "Error: can't show $type with those ids. Components: ${AssistantModel.showTypes}.")
            if (shown.size < BlockValidator.MAX_BLOCKS - 1) shown += block
            return result("show", "Shown.", "Showing ${ComponentCatalogue.canonical(type).replace('_', ' ')}…")
        }
    }

    /** A call in the protocol's compact text (tests, examples). */
    fun call(tool: String, args: Map<String, JsonElement> = emptyMap()): String = JsonObject(mapOf("tool" to JsonPrimitive(tool), "args" to JsonObject(args))).toString()
}
