package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

/** What an entity id points at: `s12` schedule item, `l3` location, `u9` organizer update. */
enum class RefKind(val prefix: Char, val searchKind: SearchKind) {
    SCHEDULE('s', SearchKind.SCHEDULE),
    LOCATION('l', SearchKind.LOCATION),
    UPDATE('u', SearchKind.NOTIFICATION);

    companion object {
        fun of(prefix: Char): RefKind? = entries.firstOrNull { it.prefix == prefix.lowercaseChar() }
        fun of(kind: SearchKind): RefKind? = entries.firstOrNull { it.searchKind == kind }
    }
}

/** A typed id as blocks and actions carry it: "s12", "l3", "u9" (also read as "S12" or "[s12]"). */
data class EntityRef(val kind: RefKind, val id: Int) {
    val code: String get() = "${kind.prefix}$id"

    fun exists(bundle: EventBundle): Boolean = when (kind) {
        RefKind.SCHEDULE -> bundle.scheduleItem(id) != null
        RefKind.LOCATION -> bundle.location(id) != null
        RefKind.UPDATE -> bundle.notifications.any { it.id == id }
    }

    fun name(bundle: EventBundle): String? = when (kind) {
        RefKind.SCHEDULE -> bundle.scheduleItem(id)?.name
        RefKind.LOCATION -> bundle.location(id)?.name
        RefKind.UPDATE -> bundle.notifications.firstOrNull { it.id == id }?.title
    }

    override fun toString(): String = code

    companion object {
        private val REF = Regex("""^\[?([slu])(\d+)]?$""", RegexOption.IGNORE_CASE)

        fun parse(text: String?): EntityRef? {
            val match = REF.find(text?.trim() ?: return null) ?: return null
            val kind = RefKind.of(match.groupValues[1][0]) ?: return null
            return match.groupValues[2].toIntOrNull()?.let { EntityRef(kind, it) }
        }

        fun of(element: JsonElement?): EntityRef? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.let { parse(it.content) }

        fun of(result: SearchResult): EntityRef? = RefKind.of(result.kind)?.let { EntityRef(it, result.id) }

        /**
         * An id of the kind [prefix] from JSON: a number, "12", or "s12" with a matching prefix
         * ("l12" when a schedule id is expected → null).
         */
        fun idOf(element: JsonElement?, prefix: Char): Int? {
            val p = element as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            if (!p.isString) return p.intOrNull
            val text = p.content.trim()
            text.toIntOrNull()?.let { return it }
            val ref = parse(text) ?: return null
            return if (ref.kind.prefix == prefix) ref.id else null
        }
    }
}

/** How a mutating action is confirmed (assistant spec §1–2). */
enum class ConfirmKind {
    /** Applies at once (with Undo where it has one). */
    NONE,

    /** A `confirm` block (Yes / No) first. */
    CHIP,

    /** The OS sheet (share) is the confirmation. */
    SHEET,
}

enum class ParamType { STRING, INT, BOOL, TIME, DAY, KIND, SCHEDULE_ID, LOCATION_ID, UPDATE_ID, ENTITY, ENTITIES, OPS, ENUM }

data class ActionParam(val name: String, val type: ParamType, val required: Boolean = false, val values: List<String> = emptyList())

/** What an action gives back (for the model's catalogue and the renderer). */
enum class ActionResultType { RESULTS, ITEM, LOCATION, UPDATE, EVENT, NOW_NEXT, TIMELINE, LIKED, PLAN, EXPLANATION, ROUTE, DAY_ROUTE, PLAN_DIFF, SETTING, SHEET, NAVIGATION }

private fun p(name: String, type: ParamType, required: Boolean = false, vararg values: String) = ActionParam(name, type, required, values.toList())

/** Screens `open(screen, id?)` navigates to. */
val OPEN_SCREENS = listOf("map", "locations", "schedule", "updates", "flyer", "options", "tour", "plan")

/**
 * The action registry (assistant spec §1): one description used by the assistant (model and rules),
 * buttons, AppFunctions / App Intents, Live Update / Live Activity buttons and widgets. The platform
 * implements [ActionExecutor]; [ActionDispatcher] validates and confirms.
 */
enum class ActionName(
    val key: String,
    val params: List<ActionParam>,
    val mutates: Boolean,
    val confirm: ConfirmKind,
    val result: ActionResultType,
    /** Applies with an Undo (like ↔ unlike, checkoff ↔ un-checkoff, plan changes → `plan_undo`). */
    val undoable: Boolean = false,
    /** Needs the user's location (rejected without it). */
    val needsLocation: Boolean = false,
) {
    // Read
    SEARCH("search", listOf(p("query", ParamType.STRING, true), p("kinds", ParamType.STRING)), false, ConfirmKind.NONE, ActionResultType.RESULTS),
    GET_ITEM("get_item", listOf(p("id", ParamType.SCHEDULE_ID, true)), false, ConfirmKind.NONE, ActionResultType.ITEM),
    GET_LOCATION("get_location", listOf(p("id", ParamType.LOCATION_ID, true)), false, ConfirmKind.NONE, ActionResultType.LOCATION),
    GET_UPDATE("get_update", listOf(p("id", ParamType.UPDATE_ID, true)), false, ConfirmKind.NONE, ActionResultType.UPDATE),
    EVENT_INFO("event_info", emptyList(), false, ConfirmKind.NONE, ActionResultType.EVENT),
    NOW_NEXT("now_next", listOf(p("at", ParamType.TIME), p("location", ParamType.LOCATION_ID)), false, ConfirmKind.NONE, ActionResultType.NOW_NEXT),
    WHATS_ON("whats_on", listOf(p("from", ParamType.TIME), p("to", ParamType.TIME), p("day", ParamType.DAY)), false, ConfirmKind.NONE, ActionResultType.TIMELINE),
    LIKED("liked", emptyList(), false, ConfirmKind.NONE, ActionResultType.LIKED),
    PLAN("plan", listOf(p("day", ParamType.DAY)), false, ConfirmKind.NONE, ActionResultType.PLAN),
    NEAR_ME("near_me", listOf(p("kind", ParamType.STRING)), false, ConfirmKind.NONE, ActionResultType.RESULTS, needsLocation = true),
    WHY_NOT("why_not", listOf(p("item", ParamType.SCHEDULE_ID, true)), false, ConfirmKind.NONE, ActionResultType.EXPLANATION),
    ROUTE("route", listOf(p("to_location", ParamType.LOCATION_ID, true), p("from", ParamType.LOCATION_ID)), false, ConfirmKind.NONE, ActionResultType.ROUTE),
    DAY_ROUTE("day_route", listOf(p("day", ParamType.DAY)), false, ConfirmKind.NONE, ActionResultType.DAY_ROUTE),

    // Write
    LIKE("like", listOf(p("kind", ParamType.KIND, true, "schedule", "location"), p("id", ParamType.ENTITY, true)), true, ConfirmKind.NONE, ActionResultType.ITEM, undoable = true),
    UNLIKE("unlike", listOf(p("kind", ParamType.KIND, true, "schedule", "location"), p("id", ParamType.ENTITY, true)), true, ConfirmKind.NONE, ActionResultType.ITEM, undoable = true),
    PLAN_MAKE("plan_make", listOf(p("request", ParamType.STRING, true)), true, ConfirmKind.NONE, ActionResultType.PLAN, undoable = true),
    PLAN_EDIT("plan_edit", listOf(p("ops", ParamType.OPS, true)), true, ConfirmKind.NONE, ActionResultType.PLAN_DIFF, undoable = true),
    PLAN_UNDO("plan_undo", emptyList(), true, ConfirmKind.NONE, ActionResultType.PLAN),
    PLAN_VERSION("plan_version", listOf(p("kind", ParamType.ENUM, true, "chill", "everything")), true, ConfirmKind.NONE, ActionResultType.PLAN_DIFF, undoable = true),
    CHECKOFF("checkoff", listOf(p("item", ParamType.SCHEDULE_ID, true), p("done", ParamType.BOOL, true)), true, ConfirmKind.NONE, ActionResultType.PLAN, undoable = true),
    FOLLOW_LOCK_SCREEN("follow_lock_screen", listOf(p("on", ParamType.BOOL, true)), true, ConfirmKind.CHIP, ActionResultType.SETTING),
    REMINDERS("reminders", listOf(p("on", ParamType.BOOL, true)), true, ConfirmKind.CHIP, ActionResultType.SETTING),
    NOTIFICATIONS("notifications", listOf(p("on", ParamType.BOOL, true)), true, ConfirmKind.CHIP, ActionResultType.SETTING),
    SHARE_PLAN("share_plan", emptyList(), true, ConfirmKind.SHEET, ActionResultType.SHEET),
    SHARE_ITEM("share_item", listOf(p("id", ParamType.SCHEDULE_ID, true)), true, ConfirmKind.SHEET, ActionResultType.SHEET),
    OPEN("open", listOf(p("screen", ParamType.ENUM, true, *OPEN_SCREENS.toTypedArray()), p("id", ParamType.ENTITY)), false, ConfirmKind.NONE, ActionResultType.NAVIGATION),
    DIRECTIONS("directions", listOf(p("location", ParamType.LOCATION_ID, true)), false, ConfirmKind.NONE, ActionResultType.NAVIGATION);

    companion object {
        fun of(key: String?): ActionName? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }

        /**
         * Manual-only actions: never offered to or accepted from the assistant. `add_to_calendar` as on
         * iOS (no Add to Calendar since Nov 19, 2026).
         */
        val NEVER_AVAILABLE = setOf("leave_event", "debug_mode", "copy_support_info", "add_to_calendar")

        /** The App Clip's rule-based assistant may only like / unlike among the writes. */
        val APP_CLIP_WRITES = setOf(LIKE, UNLIKE)
    }
}

/** An action call: a registry name and its JSON params (ids as "s12" / "l3" / "u9", or numbers). */
data class AssistantAction(val name: ActionName, val params: JsonObject = JsonObject(emptyMap())) {
    fun string(key: String): String? = (params[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    fun bool(key: String): Boolean? = (params[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.lowercase().toBooleanStrictOrNull() }
    fun id(key: String, prefix: Char): Int? = EntityRef.idOf(params[key], prefix)

    /** The kind + id of like/unlike. */
    fun likeTarget(): Pair<SearchKind, Int>? {
        val kind = when (string("kind")?.lowercase()) {
            "schedule" -> SearchKind.SCHEDULE
            "location" -> SearchKind.LOCATION
            else -> return null
        }
        return id("id", if (kind == SearchKind.SCHEDULE) 's' else 'l')?.let { kind to it }
    }

    fun refs(key: String): List<EntityRef> = (params[key] as? JsonArray).orEmpty().mapNotNull { EntityRef.of(it) }

    fun ops(bundle: EventBundle, now: Instant): List<PlanOp>? = PlanOpJson.parse(params["ops"], bundle.event.zone, now)

    companion object {
        fun like(kind: SearchKind, id: Int, liked: Boolean = true) = AssistantAction(
            if (liked) ActionName.LIKE else ActionName.UNLIKE,
            buildJsonObject {
                put("kind", if (kind == SearchKind.SCHEDULE) "schedule" else "location")
                put("id", if (kind == SearchKind.SCHEDULE) "s$id" else "l$id")
            },
        )

        fun search(query: String) = AssistantAction(ActionName.SEARCH, buildJsonObject { put("query", query) })
        fun open(screen: String, ref: EntityRef? = null) = AssistantAction(ActionName.OPEN, buildJsonObject {
            put("screen", screen)
            ref?.let { put("id", it.code) }
        })
        fun toggle(name: ActionName, on: Boolean) = AssistantAction(name, buildJsonObject { put("on", on) })
        fun planEdit(ops: List<PlanOp>, bundle: EventBundle) =
            AssistantAction(ActionName.PLAN_EDIT, JsonObject(mapOf("ops" to PlanOpJson.encode(ops, bundle.event.zone))))
        fun planMake(request: String) = AssistantAction(ActionName.PLAN_MAKE, buildJsonObject { put("request", request) })
        fun planVersion(kind: PlanVersionKind) = AssistantAction(ActionName.PLAN_VERSION, buildJsonObject { put("kind", kind.key) })
        fun directions(locationId: Int) = AssistantAction(ActionName.DIRECTIONS, buildJsonObject { put("location", "l$locationId") })
        fun simple(name: ActionName) = AssistantAction(name)
    }
}

/** Where an action came from: the model and the rule-based router are the assistant; buttons are manual. */
enum class ActionSource { MODEL, RULES, MANUAL }

/** What the dispatcher needs to know about the device and screen. */
data class ActionContext(
    val bundle: EventBundle,
    val now: Instant,
    val hasLocation: Boolean = false,
    val hasPlan: Boolean = false,
    val isAppClip: Boolean = false,
)

/** Result of a validation: the action (params checked) or why it was refused. */
sealed interface ActionCheck {
    data class Valid(val action: AssistantAction) : ActionCheck
    data class Invalid(val reason: String) : ActionCheck
}

object ActionRegistry {

    /**
     * Validates [name] + [params] (from the model, the router or a chip): the name must be in the
     * registry and not [ActionName.NEVER_AVAILABLE]; every required param present; ids must exist in
     * the bundle (like/unlike: `id` of the given `kind`); times "HH:MM"; days event days; enums in
     * their values; `ops` readable plan ops.
     */
    fun check(name: String?, params: JsonObject?, bundle: EventBundle, now: Instant): ActionCheck {
        if (name?.trim()?.lowercase() in ActionName.NEVER_AVAILABLE) return ActionCheck.Invalid("not available to the assistant")
        val action = ActionName.of(name) ?: return ActionCheck.Invalid("unknown action")
        val values = params ?: JsonObject(emptyMap())
        for (param in action.params) {
            val element = values[param.name]?.takeIf { it !is JsonNull }
            if (element == null) {
                if (param.required) return ActionCheck.Invalid("missing ${param.name}")
                continue
            }
            if (!valid(param, element, values, bundle, now)) return ActionCheck.Invalid("bad ${param.name}")
        }
        return ActionCheck.Valid(AssistantAction(action, values))
    }

    private fun valid(param: ActionParam, element: JsonElement, all: JsonObject, bundle: EventBundle, now: Instant): Boolean {
        val primitive = element as? JsonPrimitive
        return when (param.type) {
            ParamType.STRING -> primitive != null && primitive.isString && primitive.content.isNotBlank() && primitive.content.length <= 300
            ParamType.INT -> primitive?.intOrNull != null
            ParamType.BOOL -> primitive != null && (primitive.booleanOrNull != null || primitive.content.lowercase() in setOf("true", "false"))
            ParamType.TIME -> primitive != null && Regex("""^([01]?\d|2[0-3]):[0-5]\d$""").matches(primitive.content.trim())
            ParamType.DAY -> primitive != null && runCatching { LocalDate.parse(primitive.content.trim()) }.getOrNull()
                ?.let { it in TimeMatcher.eventDays(bundle, now) } == true
            ParamType.KIND, ParamType.ENUM -> primitive != null && primitive.content.trim().lowercase() in param.values
            ParamType.SCHEDULE_ID -> EntityRef.idOf(element, 's')?.let { bundle.scheduleItem(it) } != null
            ParamType.LOCATION_ID -> EntityRef.idOf(element, 'l')?.let { bundle.location(it) } != null
            ParamType.UPDATE_ID -> EntityRef.idOf(element, 'u')?.let { id -> bundle.notifications.any { it.id == id } } == true
            ParamType.ENTITY -> {
                val kind = (all["kind"] as? JsonPrimitive)?.content?.lowercase()
                val ref = when (kind) {
                    "schedule" -> EntityRef.idOf(element, 's')?.let { EntityRef(RefKind.SCHEDULE, it) }
                    "location" -> EntityRef.idOf(element, 'l')?.let { EntityRef(RefKind.LOCATION, it) }
                    else -> EntityRef.of(element)
                }
                ref?.exists(bundle) == true
            }
            ParamType.ENTITIES -> element is JsonArray
            ParamType.OPS -> PlanOpJson.parse(element, bundle.event.zone, now)?.isNotEmpty() == true
        }
    }

    /** The Undo of an applied action, or null (spec §2: like/unlike/checkoff/plan edits apply with Undo). */
    fun undoFor(action: AssistantAction): AssistantAction? = when (action.name) {
        ActionName.LIKE -> action.copy(name = ActionName.UNLIKE)
        ActionName.UNLIKE -> action.copy(name = ActionName.LIKE)
        ActionName.CHECKOFF -> action.bool("done")?.let { done ->
            AssistantAction(ActionName.CHECKOFF, JsonObject(action.params + ("done" to JsonPrimitive(!done))))
        }
        ActionName.PLAN_MAKE, ActionName.PLAN_EDIT, ActionName.PLAN_VERSION -> AssistantAction(ActionName.PLAN_UNDO)
        else -> null
    }

    /** The question a `confirm` block asks before a [ConfirmKind.CHIP] action. */
    fun confirmPrompt(action: AssistantAction): String {
        val on = action.bool("on") != false
        return when (action.name) {
            ActionName.FOLLOW_LOCK_SCREEN -> if (on) "Follow your day on the Lock Screen?" else "Stop following on the Lock Screen?"
            ActionName.REMINDERS -> if (on) "Turn on reminders before liked and planned items?" else "Turn off reminders?"
            ActionName.NOTIFICATIONS -> if (on) "Turn on notifications from the organizer?" else "Turn off notifications from the organizer?"
            else -> "Are you sure?"
        }
    }
}

/** What the platform reports after running an action. */
sealed interface ActionOutcome {
    data class Done(val message: String? = null, val blocks: List<AssistantBlock> = emptyList()) : ActionOutcome
    data class Failed(val reason: String) : ActionOutcome
}

/**
 * The platform side of the action layer (Android: the ViewModel / AppFunctions; iOS: AppState /
 * App Intents). It only runs already-validated, already-confirmed actions.
 */
fun interface ActionExecutor {
    fun execute(action: AssistantAction): ActionOutcome
}

/** What [ActionDispatcher.dispatch] did. */
sealed interface Dispatch {
    data class Rejected(val reason: String) : Dispatch

    /** Show this `confirm` block; dispatch again with `confirmed = true` on Yes. */
    data class NeedsConfirmation(val confirm: AssistantBlock.Confirm) : Dispatch

    /** Ran; [undo] is what the Undo button dispatches. */
    data class Executed(val outcome: ActionOutcome, val undo: AssistantAction?) : Dispatch
}

/**
 * Validates, confirms and runs actions (pure apart from [executor]). Order: registry check
 * ([ActionRegistry.check]); assistant sources may not use [ActionName.NEVER_AVAILABLE] (already
 * refused by the check); [ActionName.needsLocation] without a location → rejected; in the App Clip
 * only like/unlike may write; plan edits / undo / versions / check-offs need a plan; a
 * [ConfirmKind.CHIP] action from the assistant needs `confirmed` (manual buttons are their own
 * confirmation); then the executor, with the Undo when the action has one and succeeded.
 */
class ActionDispatcher(private val executor: ActionExecutor) {

    fun dispatch(action: AssistantAction, context: ActionContext, source: ActionSource, confirmed: Boolean = false): Dispatch {
        when (val check = ActionRegistry.check(action.name.key, action.params, context.bundle, context.now)) {
            is ActionCheck.Invalid -> return Dispatch.Rejected(check.reason)
            is ActionCheck.Valid -> Unit
        }
        val name = action.name
        if (name.needsLocation && !context.hasLocation) return Dispatch.Rejected("needs location")
        if (context.isAppClip && name.mutates && name !in ActionName.APP_CLIP_WRITES) return Dispatch.Rejected("not in the App Clip")
        if (name in setOf(ActionName.PLAN_EDIT, ActionName.PLAN_UNDO, ActionName.PLAN_VERSION, ActionName.CHECKOFF, ActionName.SHARE_PLAN) && !context.hasPlan) {
            return Dispatch.Rejected("no plan")
        }
        if (name.confirm == ConfirmKind.CHIP && source != ActionSource.MANUAL && !confirmed) {
            return Dispatch.NeedsConfirmation(AssistantBlock.Confirm(action, ActionRegistry.confirmPrompt(action)))
        }
        val outcome = executor.execute(action)
        val undo = if (name.undoable && outcome is ActionOutcome.Done) ActionRegistry.undoFor(action) else null
        return Dispatch.Executed(outcome, undo)
    }
}

