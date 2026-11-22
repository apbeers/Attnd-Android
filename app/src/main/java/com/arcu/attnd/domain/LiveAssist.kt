package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A position fix with its time (foreground / background location, only when already permitted). */
data class LocationSample(val point: GeoPoint, val at: Instant)

enum class LateState { LEAVE_NOW, CANT_MAKE_IT }

data class RunningLate(
    val itemId: Int,
    val state: LateState,
    val walkMinutes: Int,
    /** next.start − walk − 5 min. */
    val leaveBy: Instant,
    val text: String,
    val chips: List<Chip>,
)

/** "40 free minutes near Trinity Stage: Kolache masterclass is 3 min away". */
data class GapSuggestion(
    val date: LocalDate,
    val gapStart: Instant,
    val gapEnd: Instant,
    val freeMinutes: Long,
    val itemId: Int,
    val walkMinutes: Int?,
    val text: String,
    val chip: Chip,
)

data class Recap(
    val date: LocalDate,
    val checkedOff: List<Int>,
    val likedCount: Int,
    val walkedMeters: Double,
    val text: String,
    val chips: List<Chip>,
)

/** Chip labels stay short: the name is cut (with "…") so the label fits [BlockValidator.MAX_CHIP_LABEL]. */
object ChipText {
    fun fit(prefix: String, name: String, suffix: String = ""): String {
        val room = BlockValidator.MAX_CHIP_LABEL - prefix.length - suffix.length
        val cut = if (name.length <= room) name else name.take((room - 1).coerceAtLeast(1)).trimEnd() + "…"
        return prefix + cut + suffix
    }
}

/**
 * Live plan rules (assistant spec §6; all local, pure, iOS matches). Time and location are inputs.
 * - **Next planned item**: the first planned item (all days, plan order) starting after `now`, not
 *   checked off, not skipped.
 * - **Running late**: with the user's position known, the next item's location having coordinates,
 *   the straight line d to it > 100 m and ≤ 8 km: walk = [Walking.estimateSeconds] in whole minutes
 *   rounded up; leaveBy = start − walk − 5 min. From leaveBy on: LEAVE_NOW when now + walk ≤ start
 *   ("Leave now for X · 8 min walk", chip Directions), else CANT_MAKE_IT ("You can't make X in time ·
 *   8 min walk", chips "Swap for Y" for up to 3 of the item's swap alternatives that aren't planned
 *   and start ≥ now + the walk from the user to them (0 without coordinates), then "Remove X").
 * - **Check-off**: an item is auto-checked when, while it ran ([start, end]), samples within 150 m of
 *   its location span ≥ 10 min in one unbroken run (a sample farther away breaks the run).
 * - **Skipped**: a planned item that has ended, isn't checked off, has samples while it ran and none
 *   within 150 m → chip "You skipped X — fill the gap?" (`plan_edit remove X`, which re-solves from now).
 * - **Gap filler**: today's plan day; consecutive entries a → b with b after now; the gap runs from
 *   max(a.end, now) to b.start and must be ≥ 30 min. Candidates ([Planner.dayCandidates]) not planned,
 *   starting at or after the gap start, fitting after a and before b (rule 6 for items; for a meal
 *   break plain times), with a walk from a's location ≤ 10 min when known; best by (score desc,
 *   start, id). One suggestion per gap, in plan order.
 * - **Like clash**: a liked item not in the plan whose time overlaps planned items → chip
 *   "Add to plan instead of X?" for the first of them by (start, end, id) (`plan_edit replace X with it`).
 * - **Recap**: the day's checked-off items, the liked count (items + places), the walk through the
 *   checked-off stops ([DayRoutes]): "You saw 3 things, added 2 and walked 0.4 mi." Chips: "Tomorrow's
 *   plan is ready" (open plan) when the plan has a later day, else "Plan tomorrow" (plan_make "Plan my
 *   <weekday>") when there is a later event day.
 */
object LiveAssist {

    const val THERE_METERS = 100.0
    const val CHECKOFF_METERS = 150.0
    val CHECKOFF_DWELL: Duration = Duration.ofMinutes(10)
    const val GAP_MINUTES = 30L
    const val GAP_MAX_WALK = 10
    const val MAX_SWAPS = 3

    fun nextPlanned(plan: Plan, bundle: EventBundle, now: Instant, skipped: Set<Int> = emptySet()): ScheduleItem? =
        plan.items.filter { it.start.isAfter(now) && it.itemId !in plan.checkedOff && it.itemId !in skipped }
            .firstNotNullOfOrNull { bundle.scheduleItem(it.itemId) }

    fun runningLate(plan: Plan, bundle: EventBundle, now: Instant, user: GeoPoint?, skipped: Set<Int> = emptySet()): RunningLate? {
        user ?: return null
        val next = nextPlanned(plan, bundle, now, skipped) ?: return null
        val location = bundle.location(next.locationId) ?: return null
        val target = location.coordinate ?: return null
        val meters = Geo.distanceMeters(user, target)
        if (meters <= THERE_METERS || !Walking.isNearby(meters)) return null
        val walk = Walking.minutes(Walking.estimateSeconds(meters)).toInt()
        val start = next.startTime!!.toInstant()
        val leaveBy = start.minus(Duration.ofMinutes(walk + Planner.BUFFER_MINUTES))
        if (now.isBefore(leaveBy)) return null
        val canMake = !now.plus(Duration.ofMinutes(walk.toLong())).isAfter(start)
        return if (canMake) {
            RunningLate(next.id, LateState.LEAVE_NOW, walk, leaveBy, "Leave now for ${next.name} · $walk min walk", listOf(Chip("Directions", AssistantAction.directions(location.id))))
        } else {
            val planned = plan.itemIds.toSet()
            val alternatives = plan.items.firstOrNull { it.itemId == next.id }?.alternatives.orEmpty()
                .mapNotNull { bundle.scheduleItem(it) }
                .filter { alt ->
                    val altStart = alt.startTime?.toInstant() ?: return@filter false
                    val altWalk = bundle.location(alt.locationId)?.coordinate?.let { Walking.minutes(Walking.estimateSeconds(Geo.distanceMeters(user, it))) } ?: 0L
                    alt.id !in planned && !altStart.isBefore(now.plus(Duration.ofMinutes(altWalk)))
                }
                .take(MAX_SWAPS)
            val chips = alternatives.map { Chip(ChipText.fit("Swap for ", it.name), AssistantAction.planEdit(listOf(PlanOp.Replace(next.id, with = it.id)), bundle)) } +
                Chip(ChipText.fit("Remove ", next.name), AssistantAction.planEdit(listOf(PlanOp.Remove(next.id)), bundle))
            RunningLate(next.id, LateState.CANT_MAKE_IT, walk, leaveBy, "You can't make ${next.name} in time · $walk min walk", chips)
        }
    }

    fun autoCheck(item: ScheduleItem, bundle: EventBundle, samples: List<LocationSample>): Boolean {
        val point = bundle.location(item.locationId)?.coordinate ?: return false
        val start = item.startTime?.toInstant() ?: return false
        val end = TimeMatcher.effectiveEnd(item)!!
        var runStart: Instant? = null
        for (sample in samples.filter { !it.at.isBefore(start) && !it.at.isAfter(end) }.sortedBy { it.at }) {
            if (Geo.distanceMeters(sample.point, point) <= CHECKOFF_METERS) {
                val first = runStart ?: sample.at.also { runStart = it }
                if (Duration.between(first, sample.at) >= CHECKOFF_DWELL) return true
            } else {
                runStart = null
            }
        }
        return false
    }

    /** Planned items (started, not checked off) that the samples check off. */
    fun checkOffs(plan: Plan, bundle: EventBundle, samples: List<LocationSample>, now: Instant): Set<Int> =
        plan.items.filter { it.itemId !in plan.checkedOff && !it.start.isAfter(now) }
            .mapNotNull { bundle.scheduleItem(it.itemId) }
            .filter { autoCheck(it, bundle, samples) }
            .map { it.id }.toSet()

    fun skipped(plan: Plan, bundle: EventBundle, samples: List<LocationSample>, now: Instant): List<Int> =
        plan.items.filter { !it.end.isAfter(now) && it.itemId !in plan.checkedOff }.filter { planned ->
            val point = bundle.scheduleItem(planned.itemId)?.let { bundle.location(it.locationId)?.coordinate } ?: return@filter false
            val during = samples.filter { !it.at.isBefore(planned.start) && !it.at.isAfter(planned.end) }
            during.isNotEmpty() && during.none { Geo.distanceMeters(it.point, point) <= CHECKOFF_METERS }
        }.map { it.itemId }

    fun skippedChip(itemId: Int, bundle: EventBundle): Chip? {
        val item = bundle.scheduleItem(itemId) ?: return null
        return Chip(ChipText.fit("You skipped ", item.name, " — fill the gap?"), AssistantAction.planEdit(listOf(PlanOp.Remove(itemId)), bundle))
    }

    fun gaps(plan: Plan, bundle: EventBundle, hearts: Hearts, now: Instant): List<GapSuggestion> {
        val zone = bundle.event.zone
        val today = now.atZone(zone).toLocalDate()
        val day = plan.days.firstOrNull { it.date == today } ?: return emptyList()
        val planner = Planner(bundle, plan.preferences, hearts, plan.constraints)
        val planned = plan.itemIds.toSet()
        val candidates = planner.dayCandidates(day).filter { it.id !in planned }
        val suggestions = mutableListOf<GapSuggestion>()
        val taken = HashSet<Int>()
        for ((a, b) in day.entries.zipWithNext()) {
            if (!b.start.isAfter(now)) continue
            val gapStart = maxOf(a.end, now)
            val free = Duration.between(gapStart, b.start).toMinutes()
            if (free < GAP_MINUTES) continue
            val itemA = (a as? PlannedItem)?.let { bundle.scheduleItem(it.itemId) }
            val itemB = (b as? PlannedItem)?.let { bundle.scheduleItem(it.itemId) }
            val placeId = itemA?.locationId ?: (a as? MealBreak)?.locationId
            val place = bundle.location(placeId)
            fun walkFrom(c: ScheduleItem): Int? {
                if (c.locationId != null && c.locationId == placeId) return 0
                val from = place?.coordinate ?: return null
                val to = bundle.location(c.locationId)?.coordinate ?: return null
                return Walking.minutes(Walking.estimateSeconds(Geo.distanceMeters(from, to))).toInt()
            }
            val best = candidates.filter { c ->
                val cs = c.startTime!!.toInstant()
                val ce = TimeMatcher.effectiveEnd(c)!!
                c.id !in taken && !cs.isBefore(gapStart) &&
                    (if (itemA != null) planner.canFollow(itemA, c) else !cs.isBefore(a.end)) &&
                    (if (itemB != null) planner.canFollow(c, itemB) else !ce.isAfter(b.start)) &&
                    (walkFrom(c)?.let { it <= GAP_MAX_WALK } ?: true)
            }.sortedWith(compareByDescending<ScheduleItem> { planner.score(it) }.thenBy { it.startTime!!.toInstant() }.thenBy { it.id })
                .firstOrNull() ?: continue
            taken += best.id
            val walk = walkFrom(best)
            val where = place?.let { " near ${it.name}" }.orEmpty()
            val distance = when (walk) {
                null -> "fits"
                0 -> "is right here"
                else -> "is $walk min away"
            }
            suggestions += GapSuggestion(
                day.date, gapStart, b.start, free, best.id, walk,
                "$free free minutes$where: ${best.name} $distance",
                Chip(ChipText.fit("Add ", best.name), AssistantAction.planEdit(listOf(PlanOp.Add(best.id)), bundle)),
            )
        }
        return suggestions
    }

    fun likeClash(itemId: Int, plan: Plan, bundle: EventBundle): Chip? {
        if (itemId in plan.itemIds) return null
        val item = bundle.scheduleItem(itemId) ?: return null
        val start = item.startTime?.toInstant() ?: return null
        val end = TimeMatcher.effectiveEnd(item)!!
        val other = plan.items.filter { it.start.isBefore(end) && it.end.isAfter(start) }
            .mapNotNull { bundle.scheduleItem(it.itemId) }
            .minWithOrNull(Planner.ITEM_ORDER) ?: return null
        return Chip(ChipText.fit("Add to plan instead of ", other.name, "?"), AssistantAction.planEdit(listOf(PlanOp.Replace(other.id, with = itemId)), bundle))
    }

    /** Planned items the organizer changed or removed since planning (the inline "Re-plan" card). */
    fun changedItems(plan: Plan, bundle: EventBundle): List<Int> = plan.itemIds.filter { id ->
        val item = bundle.scheduleItem(id)
        val print = plan.fingerprints[id]
        item == null || (print != null && Planner.fingerprint(item) != print)
    }

    fun recap(plan: Plan, bundle: EventBundle, hearts: Hearts, date: LocalDate, now: Instant, units: DistanceUnits, locale: Locale = Locale.getDefault()): Recap {
        val day = plan.days.firstOrNull { it.date == date }
        val checked = day?.items.orEmpty().map { it.itemId }.filter { it in plan.checkedOff }
        val liked = hearts.scheduleItems.size + hearts.locations.size
        val walked = DayRoutes.of(plan, bundle, date, only = checked.toSet())?.totalMeters ?: 0.0
        val things = if (checked.size == 1) "1 thing" else "${checked.size} things"
        val text = "You saw $things, added $liked and walked ${DistanceFormat.format(walked, units, locale)}."
        val chips = buildList {
            val laterPlanned = plan.days.any { it.date.isAfter(date) && it.items.isNotEmpty() }
            val laterDay = TimeMatcher.eventDays(bundle, now).firstOrNull { it.isAfter(date) }
            if (laterPlanned) add(Chip("Tomorrow's plan is ready", AssistantAction.open("plan")))
            else if (laterDay != null) add(Chip("Plan tomorrow", AssistantAction.planMake("Plan my ${laterDay.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}")))
        }
        return Recap(date, checked, liked, walked, text, chips)
    }
}
