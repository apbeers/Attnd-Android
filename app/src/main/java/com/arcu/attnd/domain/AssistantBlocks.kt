package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

/** A follow-up quick action under an answer. */
data class Chip(val label: String, val action: AssistantAction)

/**
 * The assistant's response blocks (assistant spec §2): ids and options only; the app resolves the data
 * from the bundle. Native components render them (and the manual screens reuse them).
 */
sealed interface AssistantBlock {
    val type: String

    /** ≤ 400 characters, plain text with **bold**. */
    data class Text(val text: String) : AssistantBlock { override val type get() = "text" }
    data class ItemCard(val id: Int, val show: List<String> = ITEM_SHOW) : AssistantBlock { override val type get() = "item_card" }
    data class PlaceCard(val id: Int, val show: List<String> = PLACE_SHOW) : AssistantBlock { override val type get() = "place_card" }
    data class UpdateCard(val id: Int) : AssistantBlock { override val type get() = "update_card" }
    data class ListBlock(val ids: List<EntityRef>, val title: String? = null, val limit: Int? = null) : AssistantBlock { override val type get() = "list" }
    data class Carousel(val ids: List<EntityRef>, val title: String? = null) : AssistantBlock { override val type get() = "carousel" }

    /** Time-ordered schedule items with a now line: explicit [ids], or [source] `plan` / `whats_on`. */
    data class Timeline(val ids: List<Int> = emptyList(), val source: String? = null, val title: String? = null) : AssistantBlock { override val type get() = "timeline" }

    /** Location pins (≤ 12) and an optional walking [route] through locations in order. */
    data class MapBlock(val pins: List<Int>, val route: List<Int> = emptyList()) : AssistantBlock { override val type get() = "map" }
    data class Gallery(val ids: List<Int> = emptyList(), val flyer: Boolean = false) : AssistantBlock { override val type get() = "gallery" }
    data class NowNext(val location: Int? = null) : AssistantBlock { override val type get() = "now_next" }
    data class Countdown(val id: Int) : AssistantBlock { override val type get() = "countdown" }

    /** Two schedule items ("s12") or two plan versions ("plan:chill", "plan:everything"). */
    data class Compare(val a: String, val b: String) : AssistantBlock { override val type get() = "compare" }
    data class PlanDiffBlock(val diff: PlanDiff) : AssistantBlock { override val type get() = "plan_diff" }
    data class PlanBlock(val day: LocalDate? = null) : AssistantBlock { override val type get() = "plan" }

    /** `follow_lock_screen`, `reminders` or `notifications`. */
    data class Setting(val setting: String) : AssistantBlock { override val type get() = "setting" }

    /** `walking`, `recap` or `liked_counts`. */
    data class Summary(val kind: String) : AssistantBlock { override val type get() = "summary" }

    /** `plan` or `item:s12`. */
    data class Share(val target: String) : AssistantBlock { override val type get() = "share" }
    data class Chips(val chips: List<Chip>) : AssistantBlock { override val type get() = "chips" }
    data class Confirm(val action: AssistantAction, val prompt: String) : AssistantBlock { override val type get() = "confirm" }

    companion object {
        val ITEM_SHOW = listOf("time", "place", "distance", "like", "actions")
        val PLACE_SHOW = listOf("image", "distance", "now_next", "like", "actions")
        val ITEM_PARTS = setOf("time", "place", "image", "distance", "like", "actions")
        val PLACE_PARTS = setOf("image", "distance", "now_next", "like", "actions")
        val SETTINGS = setOf("follow_lock_screen", "reminders", "notifications")
        val SUMMARIES = setOf("walking", "recap", "liked_counts")
        val TYPES = setOf(
            "text", "item_card", "place_card", "update_card", "list", "carousel", "timeline", "map", "gallery", "now_next",
            "countdown", "compare", "plan_diff", "plan", "setting", "summary", "share", "chips", "confirm",
        )
    }
}

/** One assistant response: blocks to show and actions to run (writes go through the dispatcher). */
data class AssistantTurn(
    val blocks: List<AssistantBlock>,
    val actions: List<AssistantAction> = emptyList(),
)

/** The block JSON shared by the model path, the router and tests (spec §2–3). */
object BlockJson {

    sealed interface Decoded {
        data class Ok(val block: AssistantBlock) : Decoded

        /** A type this build doesn't know: dropped (forward compatible). */
        data object Unknown : Decoded

        /** A known type without its required props, or with wrong types: the model's turn is invalid. */
        data class Malformed(val type: String) : Decoded
    }

    fun encode(turn: AssistantTurn): JsonObject = buildJsonObject {
        put("blocks", JsonArray(turn.blocks.map { encode(it) }))
        put("actions", JsonArray(turn.actions.map { encodeAction(it) }))
    }

    fun encodeAction(action: AssistantAction): JsonObject = buildJsonObject {
        put("name", action.name.key)
        put("params", action.params)
    }

    fun encode(block: AssistantBlock): JsonObject = buildJsonObject {
        put("type", block.type)
        when (block) {
            is AssistantBlock.Text -> put("text", block.text)
            is AssistantBlock.ItemCard -> { put("id", "s${block.id}"); put("show", strings(block.show)) }
            is AssistantBlock.PlaceCard -> { put("id", "l${block.id}"); put("show", strings(block.show)) }
            is AssistantBlock.UpdateCard -> put("id", "u${block.id}")
            is AssistantBlock.ListBlock -> {
                put("ids", strings(block.ids.map { it.code }))
                block.title?.let { put("title", it) }
                block.limit?.let { put("limit", it) }
            }
            is AssistantBlock.Carousel -> { put("ids", strings(block.ids.map { it.code })); block.title?.let { put("title", it) } }
            is AssistantBlock.Timeline -> {
                if (block.ids.isNotEmpty()) put("ids", strings(block.ids.map { "s$it" }))
                block.source?.let { put("source", it) }
                block.title?.let { put("title", it) }
            }
            is AssistantBlock.MapBlock -> {
                put("pins", strings(block.pins.map { "l$it" }))
                if (block.route.isNotEmpty()) put("route", strings(block.route.map { "l$it" }))
            }
            is AssistantBlock.Gallery -> { put("ids", strings(block.ids.map { "l$it" })); if (block.flyer) put("flyer", true) }
            is AssistantBlock.NowNext -> block.location?.let { put("location", "l$it") }
            is AssistantBlock.Countdown -> put("id", "s${block.id}")
            is AssistantBlock.Compare -> { put("a", block.a); put("b", block.b) }
            is AssistantBlock.PlanDiffBlock -> put("diff", encodeDiff(block.diff))
            is AssistantBlock.PlanBlock -> block.day?.let { put("day", it.toString()) }
            is AssistantBlock.Setting -> put("setting", block.setting)
            is AssistantBlock.Summary -> put("kind", block.kind)
            is AssistantBlock.Share -> put("target", block.target)
            is AssistantBlock.Chips -> put("chips", JsonArray(block.chips.map { chip ->
                buildJsonObject { put("label", chip.label); put("action", chip.action.name.key); put("params", chip.action.params) }
            }))
            is AssistantBlock.Confirm -> { put("action", block.action.name.key); put("params", block.action.params); put("prompt", block.prompt) }
        }
    }

    fun encodeDiff(diff: PlanDiff): JsonObject = buildJsonObject {
        put("removed", strings(diff.removed.map { "s$it" }))
        put("added", strings(diff.added.map { "s$it" }))
        put("moved", JsonArray(diff.moved.map { strings(listOf("s${it.first}", "s${it.second}")) }))
        put("meals_removed", strings(diff.mealsRemoved.map { it.encode() }))
        put("meals_added", strings(diff.mealsAdded.map { it.encode() }))
        put("meals_moved", strings(diff.mealsMoved.map { it.encode() }))
    }

    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    /**
     * Reads one block. Required props: `text` (text); `id` (item_card, place_card, update_card,
     * countdown); `ids` array (list, carousel); `ids` array or `source` (timeline); `pins` array (map);
     * `ids` array or `flyer` (gallery); `a` + `b` (compare); `diff` object (plan_diff); `setting`
     * (setting); `kind` (summary); `target` (share); `chips` array of
     * {label, action, params?} (chips; a chip whose action isn't in the registry is dropped); a
     * registry `action` (confirm). An id that has the wrong form (not a number
     * or "s12") is malformed; a well-formed id that isn't in the bundle is dropped later by
     * [BlockValidator].
     */
    fun decode(element: JsonElement?): Decoded {
        val obj = element as? JsonObject ?: return Decoded.Malformed("?")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase() ?: return Decoded.Malformed("?")
        if (type !in AssistantBlock.TYPES) return Decoded.Unknown
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        fun arr(key: String) = obj[key] as? JsonArray
        fun id(key: String, prefix: Char) = EntityRef.idOf(obj[key], prefix)
        fun ids(key: String, prefix: Char): List<Int>? = arr(key)?.map { EntityRef.idOf(it, prefix) ?: return null }
        fun refs(key: String): List<EntityRef>? = arr(key)?.map { e ->
            EntityRef.of(e) ?: return null
        }
        fun show(): List<String>? = arr("show")?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        val malformed = Decoded.Malformed(type)
        val block: AssistantBlock = when (type) {
            "text" -> AssistantBlock.Text(str("text") ?: return malformed)
            "item_card" -> AssistantBlock.ItemCard(id("id", 's') ?: return malformed, show() ?: AssistantBlock.ITEM_SHOW)
            "place_card" -> AssistantBlock.PlaceCard(id("id", 'l') ?: return malformed, show() ?: AssistantBlock.PLACE_SHOW)
            "update_card" -> AssistantBlock.UpdateCard(id("id", 'u') ?: return malformed)
            "list" -> AssistantBlock.ListBlock(refs("ids") ?: return malformed, str("title"), (obj["limit"] as? JsonPrimitive)?.intOrNull)
            "carousel" -> AssistantBlock.Carousel(refs("ids") ?: return malformed, str("title"))
            "timeline" -> {
                val list = if (obj.containsKey("ids")) ids("ids", 's') ?: return malformed else emptyList()
                val source = str("source")?.lowercase()
                if (list.isEmpty() && source == null) return malformed
                AssistantBlock.Timeline(list, source, str("title"))
            }
            "map" -> AssistantBlock.MapBlock(ids("pins", 'l') ?: return malformed, if (obj.containsKey("route")) ids("route", 'l') ?: return malformed else emptyList())
            "gallery" -> {
                val flyer = (obj["flyer"] as? JsonPrimitive)?.booleanOrNull == true || str("flyer") == "true"
                val list = if (obj.containsKey("ids")) ids("ids", 'l') ?: return malformed else emptyList()
                if (list.isEmpty() && !flyer) return malformed
                AssistantBlock.Gallery(list, flyer)
            }
            "now_next" -> AssistantBlock.NowNext(if (obj.containsKey("location") && obj["location"] !is JsonNull) id("location", 'l') ?: return malformed else null)
            "countdown" -> AssistantBlock.Countdown(id("id", 's') ?: return malformed)
            "compare" -> AssistantBlock.Compare(str("a") ?: return malformed, str("b") ?: return malformed)
            "plan_diff" -> AssistantBlock.PlanDiffBlock(decodeDiff(obj["diff"] as? JsonObject ?: return malformed) ?: return malformed)
            "plan" -> AssistantBlock.PlanBlock(str("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return malformed })
            "setting" -> AssistantBlock.Setting(str("setting")?.lowercase() ?: return malformed)
            "summary" -> AssistantBlock.Summary(str("kind")?.lowercase() ?: return malformed)
            "share" -> AssistantBlock.Share(str("target")?.trim()?.lowercase() ?: return malformed)
            "chips" -> AssistantBlock.Chips((arr("chips") ?: return malformed).mapNotNull { e ->
                val chip = e as? JsonObject ?: return malformed
                val label = (chip["label"] as? JsonPrimitive)?.content ?: return malformed
                // An action this build doesn't know (or never offers) only drops that chip.
                val name = ActionName.of((chip["action"] as? JsonPrimitive)?.content) ?: return@mapNotNull null
                Chip(label, AssistantAction(name, chip["params"] as? JsonObject ?: JsonObject(emptyMap())))
            })
            "confirm" -> {
                val name = ActionName.of(str("action")) ?: return malformed
                AssistantBlock.Confirm(AssistantAction(name, obj["params"] as? JsonObject ?: JsonObject(emptyMap())), str("prompt").orEmpty())
            }
            else -> return Decoded.Unknown
        }
        return Decoded.Ok(block)
    }

    fun decodeDiff(obj: JsonObject): PlanDiff? {
        fun ids(key: String): List<Int>? = (obj[key] as? JsonArray ?: return emptyList()).map { EntityRef.idOf(it, 's') ?: return null }
        fun slots(key: String): List<MealSlot> = (obj[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(MealSlot::decode) }
        val moved = (obj["moved"] as? JsonArray).orEmpty().map { pair ->
            val p = pair as? JsonArray ?: return null
            val a = EntityRef.idOf(p.getOrNull(0), 's') ?: return null
            val b = EntityRef.idOf(p.getOrNull(1), 's') ?: return null
            a to b
        }
        return PlanDiff(ids("removed") ?: return null, ids("added") ?: return null, moved, slots("meals_removed"), slots("meals_added"), slots("meals_moved"))
    }
}

/** What [BlockValidator] checks blocks against. */
data class BlockContext(val bundle: EventBundle, val plan: Plan?, val now: Instant)

/**
 * Validation of blocks against the bundle (assistant spec §2), for the model's and the router's
 * output alike. Per block:
 * - `text`: trimmed, dropped when empty, cut to 399 characters + "…" when longer than 400.
 * - cards / `countdown`: the id must exist with the right kind (countdown: an item that hasn't started);
 *   unknown `show` parts are dropped (none left → the default parts).
 * - `list`: existing ids, first occurrence kept, at most 20; `limit` clamped to 1…20.
 * - `carousel`: existing ids, distinct, at most 8; one left → that entity's card; none → dropped.
 * - `timeline`: existing schedule ids, distinct, ordered by (start, end, id), at most 20; `source`
 *   `plan` needs a plan, `whats_on` is fine, anything else is cleared; nothing left → dropped.
 * - `map`: pins = existing locations with coordinates, distinct, at most 12; `route` likewise (kept
 *   only with 2 or more stops); no pins → dropped.
 * - `gallery`: locations with an image, at most 8; `flyer` only when the event has one.
 * - `now_next`: an unknown location is dropped. `compare`: both sides valid ("s12" existing,
 *   "plan:chill" / "plan:everything" with a plan) and different. `plan_diff`, `plan` (day = a plan
 *   day or none) and `share` `plan` need a plan; `share` `item:s12` an existing item. `setting` and
 *   `summary` must be known kinds (`summary` walking needs a plan). `calendar`: existing ids, `plan`
 *   with a plan.
 * - `chips`: label trimmed, 1–60 characters, action valid ([ActionRegistry.check]), distinct labels,
 *   at most 5. `confirm`: a valid mutating action; an empty prompt gets [ActionRegistry.confirmPrompt].
 * Then: only the last `chips` block is kept and moved to the end, and the response is cut to 8 blocks
 * (7 + chips when there are chips).
 */
object BlockValidator {

    const val MAX_BLOCKS = 8
    const val MAX_CHIPS = 5
    const val MAX_TEXT = 400
    const val MAX_CAROUSEL = 8
    const val MAX_LIST = 20
    const val MAX_PINS = 12
    const val MAX_CHIP_LABEL = 60

    fun validate(blocks: List<AssistantBlock>, ctx: BlockContext): List<AssistantBlock> {
        val valid = blocks.mapNotNull { one(it, ctx) }
        val chips = valid.lastOrNull { it is AssistantBlock.Chips }
        val content = valid.filter { it !is AssistantBlock.Chips }
        return if (chips == null) content.take(MAX_BLOCKS) else content.take(MAX_BLOCKS - 1) + chips
    }

    private fun one(block: AssistantBlock, ctx: BlockContext): AssistantBlock? {
        val bundle = ctx.bundle
        val hasPlan = ctx.plan != null
        fun item(id: Int) = bundle.scheduleItem(id) != null
        fun place(id: Int) = bundle.location(id) != null
        fun pinnable(id: Int) = bundle.location(id)?.coordinate != null
        return when (block) {
            is AssistantBlock.Text -> {
                val text = block.text.trim()
                when {
                    text.isEmpty() -> null
                    text.length > MAX_TEXT -> AssistantBlock.Text(text.take(MAX_TEXT - 1).trimEnd() + "…")
                    else -> AssistantBlock.Text(text)
                }
            }
            is AssistantBlock.ItemCard -> block.takeIf { item(it.id) }?.copy(show = parts(block.show, AssistantBlock.ITEM_PARTS, AssistantBlock.ITEM_SHOW))
            is AssistantBlock.PlaceCard -> block.takeIf { place(it.id) }?.copy(show = parts(block.show, AssistantBlock.PLACE_PARTS, AssistantBlock.PLACE_SHOW))
            is AssistantBlock.UpdateCard -> block.takeIf { b -> bundle.notifications.any { it.id == b.id } }
            is AssistantBlock.ListBlock -> {
                val ids = block.ids.filter { it.exists(bundle) }.distinct().take(MAX_LIST)
                if (ids.isEmpty()) null else AssistantBlock.ListBlock(ids, block.title?.trim()?.takeIf { it.isNotEmpty() }, block.limit?.coerceIn(1, MAX_LIST))
            }
            is AssistantBlock.Carousel -> {
                val ids = block.ids.filter { it.exists(bundle) }.distinct().take(MAX_CAROUSEL)
                when (ids.size) {
                    0 -> null
                    1 -> card(ids.single())
                    else -> AssistantBlock.Carousel(ids, block.title?.trim()?.takeIf { it.isNotEmpty() })
                }
            }
            is AssistantBlock.Timeline -> {
                val ids = block.ids.distinct().mapNotNull { bundle.scheduleItem(it) }.filter { it.startTime != null }
                    .sortedWith(Planner.ITEM_ORDER).map { it.id }.take(MAX_LIST)
                val source = block.source?.takeIf { (it == "plan" && hasPlan) || it == "whats_on" }
                if (ids.isEmpty() && source == null) null else AssistantBlock.Timeline(ids, source, block.title?.trim()?.takeIf { it.isNotEmpty() })
            }
            is AssistantBlock.MapBlock -> {
                val pins = block.pins.filter { pinnable(it) }.distinct().take(MAX_PINS)
                val route = block.route.filter { pinnable(it) }.take(DayRoutes.MAX_STOPS).takeIf { it.size >= 2 }.orEmpty()
                if (pins.isEmpty()) null else AssistantBlock.MapBlock(pins, route)
            }
            is AssistantBlock.Gallery -> {
                val ids = block.ids.filter { !bundle.location(it)?.imageUrl.isNullOrBlank() }.distinct().take(MAX_CAROUSEL)
                val flyer = block.flyer && !bundle.event.flyerUrl.isNullOrBlank()
                if (ids.isEmpty() && !flyer) null else AssistantBlock.Gallery(ids, flyer)
            }
            is AssistantBlock.NowNext -> block.takeIf { it.location == null || place(it.location) }
            is AssistantBlock.Countdown -> block.takeIf { bundle.scheduleItem(it.id)?.startTime?.toInstant()?.isAfter(ctx.now) == true }
            is AssistantBlock.Compare -> {
                fun side(s: String): Boolean = when {
                    s.startsWith("plan:") -> hasPlan && PlanVersionKind.of(s.removePrefix("plan:")) != null
                    else -> EntityRef.parse(s)?.let { it.kind == RefKind.SCHEDULE && it.exists(bundle) } == true
                }
                val a = block.a.trim().lowercase()
                val b = block.b.trim().lowercase()
                if (a != b && side(a) && side(b)) AssistantBlock.Compare(a, b) else null
            }
            is AssistantBlock.PlanDiffBlock -> if (!hasPlan) null else {
                val d = block.diff
                AssistantBlock.PlanDiffBlock(d.copy(removed = d.removed.filter(::item), added = d.added.filter(::item), moved = d.moved.filter { item(it.first) && item(it.second) }))
            }
            is AssistantBlock.PlanBlock -> block.takeIf { b -> hasPlan && (b.day == null || ctx.plan.days.any { it.date == b.day }) }
            is AssistantBlock.Setting -> block.takeIf { it.setting in AssistantBlock.SETTINGS }
            is AssistantBlock.Summary -> block.takeIf { it.kind in AssistantBlock.SUMMARIES && (it.kind != "walking" || hasPlan) }
            is AssistantBlock.Share -> when {
                block.target == "plan" -> block.takeIf { hasPlan }
                block.target.startsWith("item:") -> EntityRef.parse(block.target.removePrefix("item:"))
                    ?.takeIf { it.kind == RefKind.SCHEDULE && it.exists(bundle) }?.let { AssistantBlock.Share("item:${it.code}") }
                else -> null
            }
            is AssistantBlock.Chips -> {
                val seen = HashSet<String>()
                val chips = block.chips.mapNotNull { chip ->
                    val label = chip.label.trim()
                    if (label.isEmpty() || label.length > MAX_CHIP_LABEL || !seen.add(label.lowercase())) return@mapNotNull null
                    when (val check = ActionRegistry.check(chip.action.name.key, chip.action.params, bundle, ctx.now)) {
                        is ActionCheck.Valid -> Chip(label, check.action)
                        is ActionCheck.Invalid -> null
                    }
                }.take(MAX_CHIPS)
                if (chips.isEmpty()) null else AssistantBlock.Chips(chips)
            }
            is AssistantBlock.Confirm -> {
                val check = ActionRegistry.check(block.action.name.key, block.action.params, bundle, ctx.now)
                if (check !is ActionCheck.Valid || !block.action.name.mutates) null
                else AssistantBlock.Confirm(check.action, block.prompt.trim().ifEmpty { ActionRegistry.confirmPrompt(check.action) })
            }
        }
    }

    private fun parts(show: List<String>, allowed: Set<String>, default: List<String>): List<String> =
        show.map { it.lowercase() }.filter { it in allowed }.distinct().ifEmpty { default }

    /** The card for one entity. */
    fun card(ref: EntityRef): AssistantBlock = when (ref.kind) {
        RefKind.SCHEDULE -> AssistantBlock.ItemCard(ref.id)
        RefKind.LOCATION -> AssistantBlock.PlaceCard(ref.id)
        RefKind.UPDATE -> AssistantBlock.UpdateCard(ref.id)
    }
}
