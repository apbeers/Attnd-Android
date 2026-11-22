package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One plan edit (assistant spec §4, the `plan_edit(ops[])` action). Item ids are schedule item ids.
 * Every edit is applied by [PlanEditor] as constraints ([PlanConstraints]) plus a re-solve
 * ([Planner.replan], rule 20), never by moving entries by hand.
 */
sealed interface PlanOp {
    /** Swap [item] out for [with], else the best fitting item like [like], else the best fitting item tagged [tag]. */
    data class Replace(val item: Int, val with: Int? = null, val like: Int? = null, val tag: String? = null) : PlanOp
    data class Remove(val item: Int) : PlanOp
    data class Add(val item: Int) : PlanOp

    /** Another showing of the same item (same name) later / earlier the same event. */
    data class Move(val item: Int, val later: Boolean) : PlanOp
    data class Keep(val item: Int) : PlanOp
    data class Unkeep(val item: Int) : PlanOp

    /** "I already ate": [until] = now + 3 h; also marks the next meal break done. */
    data class NoFoodUntil(val until: Instant) : PlanOp
    data class DoneBy(val time: LocalTime) : PlanOp
    data class StartAt(val time: LocalTime) : PlanOp
    data class MaxWalk(val minutes: Int) : PlanOp
    data class Pace(val pace: PlanPace) : PlanOp

    /** "I'm at <place>": the plan restarts from that location now. */
    data class FromLocation(val locationId: Int) : PlanOp
    data class MoreTag(val tag: String) : PlanOp
    data class LessTag(val tag: String) : PlanOp
}

/** A meal break that moved, appeared or disappeared in a [PlanDiff]. */
data class MealChange(val slot: MealSlot, val start: Instant?)

/**
 * What an edit changed (the `plan_diff` block): removed and added items in plan order, [moved] pairs
 * (old id → new id with the same name: another showing), and meal breaks removed / added / moved
 * (same day and kind, different start).
 */
data class PlanDiff(
    val removed: List<Int> = emptyList(),
    val added: List<Int> = emptyList(),
    val moved: List<Pair<Int, Int>> = emptyList(),
    val mealsRemoved: List<MealSlot> = emptyList(),
    val mealsAdded: List<MealSlot> = emptyList(),
    val mealsMoved: List<MealSlot> = emptyList(),
) {
    val isEmpty: Boolean
        get() = removed.isEmpty() && added.isEmpty() && moved.isEmpty() && mealsRemoved.isEmpty() && mealsAdded.isEmpty() && mealsMoved.isEmpty()

    companion object {
        /**
         * [before] → [after]: removed = items of [before] not in [after] (in [before]'s order), added =
         * items of [after] not in [before] (in [after]'s order); then each removed item, in order, is
         * paired with the first unpaired added item whose [SearchText.normalize]d name is equal (moved).
         */
        fun between(before: Plan, after: Plan, bundle: EventBundle): PlanDiff {
            val oldIds = before.itemIds
            val newIds = after.itemIds
            val removed = oldIds.filter { it !in newIds }.toMutableList()
            val added = newIds.filter { it !in oldIds }.toMutableList()
            val moved = mutableListOf<Pair<Int, Int>>()
            for (r in removed.toList()) {
                val name = bundle.scheduleItem(r)?.name?.let(SearchText::normalize) ?: continue
                val match = added.firstOrNull { a -> bundle.scheduleItem(a)?.name?.let(SearchText::normalize) == name } ?: continue
                moved += r to match
                removed.remove(r)
                added.remove(match)
            }
            fun meals(plan: Plan) = plan.days.flatMap { day ->
                day.entries.filterIsInstance<MealBreak>().map { MealSlot(day.date, it.kind) to it.start }
            }.toMap()
            val oldMeals = meals(before)
            val newMeals = meals(after)
            return PlanDiff(
                removed = removed,
                added = added,
                moved = moved,
                mealsRemoved = oldMeals.keys.filter { it !in newMeals }.sortedWith(SLOT_ORDER),
                mealsAdded = newMeals.keys.filter { it !in oldMeals }.sortedWith(SLOT_ORDER),
                mealsMoved = oldMeals.filter { (slot, start) -> newMeals[slot]?.let { it != start } == true }.keys.sortedWith(SLOT_ORDER),
            )
        }

        private val SLOT_ORDER = compareBy<MealSlot> { it.date }.thenBy { it.kind.ordinal }
    }
}

/** A previewed edit: the plan before and after, what changed and anything that could not be done. */
data class PlanEdit(
    val before: Plan,
    val after: Plan,
    val diff: PlanDiff,
    /** Plain sentences such as "Nothing like Neon Armadillo fits there". */
    val notes: List<String>,
)

/**
 * Applies [PlanOp]s (assistant spec §4). Ops are read in order against the plan as it was before the
 * edit, each one changing the accumulated [PlanConstraints]; then the plan is re-solved once at `now`
 * ([Planner.replan]). Rules (iOS implements the same):
 * - **Replace** removes `item` (excluded, unpinned) and pins the replacement: `with` when given; else
 *   the slot's best candidate: candidates of the item's plan day ([Planner.dayCandidates]) that are not
 *   planned, not the item, not `like`, not excluded, and fit between the item's previous and next
 *   planned items ([Planner.canFollow]); `like` → the highest similarity to `like` (ties score
 *   desc, start, id), `tag` → items with that tag by (score desc, start, id). Nothing fits → only the
 *   removal, with the note "Nothing like <name> fits there" / "Nothing tagged <tag> fits there". An
 *   item that isn't planned → no change, note "<name> isn't in your plan".
 * - **Remove** excludes and unpins (note when it wasn't planned). **Add** / **Keep** pin and un-exclude
 *   (an item that has started → no change, note "<name> has already started"). **Unkeep** unpins.
 * - **Move** later / earlier: the first schedule item with the same normalised name starting after
 *   (the last before) the item, not started at `now` and not planned; it replaces the item like `with`. None → note
 *   "<name> has no later time" / "no earlier time", no change.
 * - **NoFoodUntil** sets `noFoodUntil` and marks the plan's next meal break (the first one, over all
 *   days, ending after `now`) done. **DoneBy**, **StartAt**, **MaxWalk** (> 0), **Pace** set their
 *   constraint. **FromLocation** sets the origin (location, `now`). **MoreTag** adds the lower-case tag
 *   to `moreTags` and removes it from `lessTags`; **LessTag** the reverse.
 * - After the re-solve, every item an op pinned that is not in the plan gets the note
 *   "<name> doesn't fit your plan".
 */
object PlanEditor {

    fun preview(plan: Plan, ops: List<PlanOp>, bundle: EventBundle, hearts: Hearts, now: Instant): PlanEdit {
        var c = plan.constraints
        val notes = mutableListOf<String>()
        val pinnedByOps = mutableListOf<Int>()
        fun name(id: Int) = bundle.scheduleItem(id)?.name ?: "That item"
        fun started(id: Int) = bundle.scheduleItem(id)?.startTime?.toInstant()?.isBefore(now) != false
        fun pin(id: Int) {
            c = c.copy(pinned = c.pinned + id, excluded = c.excluded - id)
            pinnedByOps += id
        }
        fun drop(id: Int) {
            c = c.copy(pinned = c.pinned - id, excluded = c.excluded + id)
        }

        for (op in ops) {
            when (op) {
                is PlanOp.Replace -> {
                    if (op.item !in plan.itemIds) {
                        notes += "${name(op.item)} isn't in your plan"
                        continue
                    }
                    val replacement = op.with?.takeIf { bundle.scheduleItem(it) != null }
                        ?: slotChoice(plan, op, bundle, hearts, c)
                    drop(op.item)
                    if (replacement != null) {
                        pin(replacement)
                    } else if (op.like != null) {
                        notes += "Nothing like ${name(op.like)} fits there"
                    } else if (op.tag != null) {
                        notes += "Nothing tagged ${op.tag.lowercase()} fits there"
                    }
                }
                is PlanOp.Remove -> {
                    if (op.item !in plan.itemIds) notes += "${name(op.item)} isn't in your plan"
                    drop(op.item)
                }
                is PlanOp.Add, is PlanOp.Keep -> {
                    val id = if (op is PlanOp.Add) op.item else (op as PlanOp.Keep).item
                    if (bundle.scheduleItem(id) == null) continue
                    if (started(id) && id !in plan.itemIds) {
                        notes += "${name(id)} has already started"
                        continue
                    }
                    pin(id)
                }
                is PlanOp.Unkeep -> c = c.copy(pinned = c.pinned - op.item)
                is PlanOp.Move -> {
                    val other = otherShowing(bundle, op.item, op.later, now, plan.itemIds.toSet())
                    if (other == null) {
                        notes += "${name(op.item)} has no ${if (op.later) "later" else "earlier"} time"
                        continue
                    }
                    drop(op.item)
                    pin(other)
                }
                is PlanOp.NoFoodUntil -> {
                    val next = plan.days.flatMap { day -> day.entries.filterIsInstance<MealBreak>().map { day.date to it } }
                        .firstOrNull { it.second.end.isAfter(now) }
                    c = c.copy(
                        noFoodUntil = op.until,
                        mealsDone = c.mealsDone + listOfNotNull(next?.let { MealSlot(it.first, it.second.kind) }),
                    )
                }
                is PlanOp.DoneBy -> c = c.copy(doneBy = op.time)
                is PlanOp.StartAt -> c = c.copy(startAt = op.time)
                is PlanOp.MaxWalk -> if (op.minutes > 0) c = c.copy(maxWalkMinutes = op.minutes)
                is PlanOp.Pace -> c = c.copy(pace = op.pace)
                is PlanOp.FromLocation -> if (bundle.location(op.locationId) != null) c = c.copy(origin = PlanOrigin(op.locationId, now))
                is PlanOp.MoreTag -> op.tag.lowercase().let { c = c.copy(moreTags = c.moreTags + it, lessTags = c.lessTags - it) }
                is PlanOp.LessTag -> op.tag.lowercase().let { c = c.copy(lessTags = c.lessTags + it, moreTags = c.moreTags - it) }
            }
        }
        val after = Planner(bundle, plan.preferences, hearts, c).replan(plan, now)
        pinnedByOps.distinct().filter { it !in after.itemIds && it in c.pinned }.forEach { notes += "${name(it)} doesn't fit your plan" }
        // One list: a day view's timed food places stay (no-op for a planner's own plan).
        return PlanEdit(plan, after, PlanDiff.between(plan, after, bundle), notes).keepingScheduleMeals(bundle)
    }

    /** The replacement for [op] in its slot (see the class rules), or null. */
    private fun slotChoice(plan: Plan, op: PlanOp.Replace, bundle: EventBundle, hearts: Hearts, c: PlanConstraints): Int? {
        if (op.like == null && op.tag == null) return null
        val day = plan.days.firstOrNull { d -> d.items.any { it.itemId == op.item } } ?: return null
        val planner = Planner(bundle, plan.preferences, hearts, c)
        val index = day.items.indexOfFirst { it.itemId == op.item }
        val previous = day.items.getOrNull(index - 1)?.let { bundle.scheduleItem(it.itemId) }
        val next = day.items.getOrNull(index + 1)?.let { bundle.scheduleItem(it.itemId) }
        val planned = plan.itemIds.toSet()
        val pool = planner.dayCandidates(day).filter { cand ->
            cand.id !in planned && cand.id != op.item && cand.id != op.like && cand.id !in c.excluded &&
                (previous == null || planner.canFollow(previous, cand)) && (next == null || planner.canFollow(cand, next))
        }
        val byScore = compareByDescending<ScheduleItem> { planner.score(it) }.thenBy { it.startTime?.toInstant() }.thenBy { it.id }
        if (op.like != null) {
            val like = bundle.scheduleItem(op.like) ?: return null
            val similarity = TfIdfSimilarity(bundle)
            return pool.map { it to similarity.similarity(it, like) }
                .sortedWith(compareByDescending<Pair<ScheduleItem, Double>> { it.second }.then { a, b -> byScore.compare(a.first, b.first) })
                .firstOrNull()?.first?.id
        }
        val tag = op.tag!!.lowercase()
        return pool.filter { item -> item.tags.any { it.lowercase() == tag } }.sortedWith(byScore).firstOrNull()?.id
    }

    /** Move: another showing (same normalised name) after / before [id], not started at [now], not planned. */
    fun otherShowing(bundle: EventBundle, id: Int, later: Boolean, now: Instant, planned: Set<Int> = emptySet()): Int? {
        val item = bundle.scheduleItem(id) ?: return null
        val start = item.startTime?.toInstant() ?: return null
        val name = SearchText.normalize(item.name)
        val showings = bundle.schedule.filter {
            it.id != id && it.id !in planned && SearchText.normalize(it.name) == name && it.startTime?.toInstant()?.isBefore(now) == false
        }.sortedWith(Planner.ITEM_ORDER)
        return if (later) showings.firstOrNull { it.startTime!!.toInstant().isAfter(start) }?.id
        else showings.lastOrNull { it.startTime!!.toInstant().isBefore(start) }?.id
    }
}

/** Plan versions (assistant spec §4): Chill = relaxed pace + walks ≤ 10 min, See everything = packed. */
enum class PlanVersionKind(val key: String, val label: String) {
    CHILL("chill", "Chill"),
    EVERYTHING("everything", "See everything");

    fun ops(): List<PlanOp> = when (this) {
        CHILL -> listOf(PlanOp.Pace(PlanPace.RELAXED), PlanOp.MaxWalk(CHILL_MAX_WALK))
        EVERYTHING -> listOf(PlanOp.Pace(PlanPace.PACKED))
    }

    companion object {
        const val CHILL_MAX_WALK = 10
        fun of(value: String?): PlanVersionKind? = entries.firstOrNull { it.key == value?.trim()?.lowercase() }
    }
}

object PlanVersions {
    fun make(kind: PlanVersionKind, plan: Plan, bundle: EventBundle, hearts: Hearts, now: Instant): PlanEdit =
        PlanEditor.preview(plan, kind.ops(), bundle, hearts, now)
}

/**
 * The JSON form of [PlanOp]s inside actions and model turns:
 * `{"op":"replace","item":"s103","like":"s114"}`, `{"op":"remove","item":103}`, `{"op":"move","item":"s3","later":true}`,
 * `{"op":"constraint","no_food_until":"13:00"}` (also `done_by`, `start_at` "HH:MM"; `max_walk_min`, `pace`,
 * `from_location` "l6", `more_tag`, `less_tag`). Clock times are today in the event's zone (an ISO
 * instant is accepted for `no_food_until`). Ids may be numbers or "s12" / "l3". Unreadable ops → null.
 */
object PlanOpJson {

    fun parse(element: JsonElement?, zone: ZoneId, now: Instant): List<PlanOp>? {
        val array = element as? JsonArray ?: return null
        val ops = array.map { (it as? JsonObject)?.let { obj -> parseOne(obj, zone, now) } ?: return null }
        return ops
    }

    private fun parseOne(obj: JsonObject, zone: ZoneId, now: Instant): PlanOp? {
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
        fun id(key: String) = EntityRef.idOf(obj[key], 's')
        val item = id("item")
        return when (str("op")?.lowercase()) {
            "replace" -> item?.let { PlanOp.Replace(it, with = id("with"), like = id("like"), tag = str("tag")?.lowercase()) }
            "remove" -> item?.let { PlanOp.Remove(it) }
            "add" -> item?.let { PlanOp.Add(it) }
            "move" -> item?.let {
                val later = when {
                    str("later") == "true" || str("direction") == "later" -> true
                    str("earlier") == "true" || str("direction") == "earlier" -> false
                    else -> return null
                }
                PlanOp.Move(it, later)
            }
            "keep" -> item?.let { PlanOp.Keep(it) }
            "unkeep" -> item?.let { PlanOp.Unkeep(it) }
            "constraint" -> when {
                obj.containsKey("no_food_until") -> instantOrClock(str("no_food_until"), zone, now)?.let { PlanOp.NoFoodUntil(it) }
                obj.containsKey("done_by") -> clock(str("done_by"))?.let { PlanOp.DoneBy(it) }
                obj.containsKey("start_at") -> clock(str("start_at"))?.let { PlanOp.StartAt(it) }
                obj.containsKey("max_walk_min") -> (obj["max_walk_min"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { PlanOp.MaxWalk(it) }
                obj.containsKey("pace") -> PlanPace.entries.firstOrNull { it.key == str("pace")?.lowercase() }?.let { PlanOp.Pace(it) }
                obj.containsKey("from_location") -> EntityRef.idOf(obj["from_location"], 'l')?.let { PlanOp.FromLocation(it) }
                obj.containsKey("more_tag") -> str("more_tag")?.takeIf { it.isNotEmpty() }?.let { PlanOp.MoreTag(it.lowercase()) }
                obj.containsKey("less_tag") -> str("less_tag")?.takeIf { it.isNotEmpty() }?.let { PlanOp.LessTag(it.lowercase()) }
                else -> null
            }
            else -> null
        }
    }

    fun encode(ops: List<PlanOp>, zone: ZoneId): JsonArray = JsonArray(ops.map { encodeOne(it, zone) })

    private fun encodeOne(op: PlanOp, zone: ZoneId): JsonObject = buildJsonObject {
        fun s(id: Int) = "s$id"
        when (op) {
            is PlanOp.Replace -> {
                put("op", "replace"); put("item", s(op.item))
                op.with?.let { put("with", s(it)) }; op.like?.let { put("like", s(it)) }; op.tag?.let { put("tag", it) }
            }
            is PlanOp.Remove -> { put("op", "remove"); put("item", s(op.item)) }
            is PlanOp.Add -> { put("op", "add"); put("item", s(op.item)) }
            is PlanOp.Move -> { put("op", "move"); put("item", s(op.item)); put("direction", if (op.later) "later" else "earlier") }
            is PlanOp.Keep -> { put("op", "keep"); put("item", s(op.item)) }
            is PlanOp.Unkeep -> { put("op", "unkeep"); put("item", s(op.item)) }
            is PlanOp.NoFoodUntil -> { put("op", "constraint"); put("no_food_until", op.until.toString()) }
            is PlanOp.DoneBy -> { put("op", "constraint"); put("done_by", op.time.toString()) }
            is PlanOp.StartAt -> { put("op", "constraint"); put("start_at", op.time.toString()) }
            is PlanOp.MaxWalk -> { put("op", "constraint"); put("max_walk_min", op.minutes) }
            is PlanOp.Pace -> { put("op", "constraint"); put("pace", op.pace.key) }
            is PlanOp.FromLocation -> { put("op", "constraint"); put("from_location", "l${op.locationId}") }
            is PlanOp.MoreTag -> { put("op", "constraint"); put("more_tag", op.tag) }
            is PlanOp.LessTag -> { put("op", "constraint"); put("less_tag", op.tag) }
        }
    }

    private fun clock(text: String?): LocalTime? {
        val match = Regex("""^(\d{1,2}):(\d{2})""").find(text ?: return null) ?: return null
        val h = match.groupValues[1].toInt()
        val m = match.groupValues[2].toInt()
        return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
    }

    private fun instantOrClock(text: String?, zone: ZoneId, now: Instant): Instant? {
        if (text == null) return null
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        val time = clock(text) ?: return null
        return now.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
    }

    /** "1:00 PM" for notes and chips. */
    fun clockText(time: LocalTime, locale: Locale = Locale.getDefault()): String = time.format(DateTimeFormatter.ofPattern("h:mm a", locale))
}
