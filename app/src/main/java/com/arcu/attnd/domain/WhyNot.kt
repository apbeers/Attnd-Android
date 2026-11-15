package com.arcu.attnd.domain

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Why a schedule item isn't in the plan, in the planner's own terms (assistant spec §7). */
sealed interface WhyNotReason {
    data object NoPlan : WhyNotReason
    data object InPlan : WhyNotReason
    data object Excluded : WhyNotReason
    data object Ended : WhyNotReason
    data object Started : WhyNotReason
    data object OtherDay : WhyNotReason
    data object OutsideWindow : WhyNotReason
    data class KidFriendly(val tag: String) : WhyNotReason
    data object Food : WhyNotReason
    data object Disliked : WhyNotReason
    data class Clash(val withId: Int, val liked: Boolean, val pinned: Boolean, val overlaps: Boolean, val walkLimit: Boolean) : WhyNotReason
    data object LowScore : WhyNotReason
}

data class WhyNotAnswer(val itemId: Int, val reason: WhyNotReason, val text: String, val canSwapIn: Boolean)

/**
 * "Why isn't X in my plan?" — the first that applies, in order: no plan; already planned; removed by
 * the attendee (`excluded`); ended; started; on a day the plan doesn't cover; outside the plan day's
 * window; kid-friendly filter (an [Planner.ADULT_TAGS] tag); "I already ate" ([Planner.foodBlocked]);
 * a score ≤ 0 (disliked); a clash with a planned item of its day (the first by (start, end, id) that
 * [Planner.fitsWith] rejects: "clashes with" when the times overlap, else the walk limit or "There
 * isn't time to walk between"); else the pace / score choice. Liked and pinned items skip the three filters, like
 * the planner. "Swap it in" (`plan_edit add`) is offered unless there is no plan, it is planned, or
 * it has started or ended.
 */
object WhyNot {

    fun explain(itemId: Int, plan: Plan?, bundle: EventBundle, hearts: Hearts, now: Instant, locale: Locale = Locale.getDefault()): WhyNotAnswer? {
        val item = bundle.scheduleItem(itemId) ?: return null
        val name = item.name
        fun answer(reason: WhyNotReason, text: String, swap: Boolean = true) = WhyNotAnswer(itemId, reason, text, swap)
        if (plan == null) return answer(WhyNotReason.NoPlan, "You don't have a plan yet.", swap = false)
        if (itemId in plan.itemIds) return answer(WhyNotReason.InPlan, "$name is already in your plan.", swap = false)
        val c = plan.constraints
        if (itemId in c.excluded) return answer(WhyNotReason.Excluded, "You took $name out of your plan.")
        val start = item.startTime?.toInstant() ?: return answer(WhyNotReason.OtherDay, "$name has no time yet.", swap = false)
        val end = TimeMatcher.effectiveEnd(item)!!
        if (!end.isAfter(now)) return answer(WhyNotReason.Ended, "$name has ended.", swap = false)
        if (start.isBefore(now)) return answer(WhyNotReason.Started, "$name has already started.", swap = false)
        val zone = bundle.event.zone
        val date = EventTime.localDate(item.startTime, zone)
        val day = plan.days.firstOrNull { it.date == date }
        if (day == null) {
            val covered = plan.days.map { it.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale) }.distinct().joinToString(" and ")
            return answer(WhyNotReason.OtherDay, "$name is on ${date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, and your plan covers $covered.")
        }
        if (start.isBefore(day.windowStart) || end.isAfter(day.windowEnd)) {
            val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
            return answer(
                WhyNotReason.OutsideWindow,
                "$name is outside your plan's hours (${day.windowStart.atZone(zone).format(clock)} – ${day.windowEnd.atZone(zone).format(clock)}).",
            )
        }
        val planner = Planner(bundle, plan.preferences, hearts, c)
        val kept = item.id in hearts.scheduleItems || item.id in c.pinned
        if (!kept && plan.preferences.kidFriendly) {
            item.tags.firstOrNull { it.lowercase() in Planner.ADULT_TAGS }?.let {
                return answer(WhyNotReason.KidFriendly(it), "$name is $it, and you asked for kid-friendly.")
            }
        }
        if (planner.foodBlocked(item)) {
            val until = c.noFoodUntil!!.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", locale))
            return answer(WhyNotReason.Food, "You said you already ate, so food waits until $until.")
        }
        if (!kept && planner.score(item) <= 0) return answer(WhyNotReason.Disliked, "$name has something you said you don't like.")
        val clash = day.items.mapNotNull { bundle.scheduleItem(it.itemId) }.sortedWith(Planner.ITEM_ORDER)
            .firstOrNull { !planner.fitsWith(it, item) }
        if (clash != null) {
            val otherStart = clash.startTime!!.toInstant()
            val otherEnd = TimeMatcher.effectiveEnd(clash)!!
            val overlaps = start.isBefore(otherEnd) && end.isAfter(otherStart)
            val liked = clash.id in hearts.scheduleItems
            val pinned = clash.id in c.pinned
            val walk = planner.walkBetween(clash, item)
            val limit = c.maxWalkMinutes
            val overLimit = !overlaps && walk != null && limit != null && walk > limit
            val qualifier = when {
                liked -> " (liked)"
                pinned -> " (you added it)"
                else -> ""
            }
            val text = when {
                overlaps -> "$name clashes with ${clash.name}$qualifier."
                overLimit -> "The walk between $name and ${clash.name}$qualifier is longer than your $limit-minute limit."
                else -> "There isn't time to walk between $name and ${clash.name}$qualifier."
            }
            return answer(WhyNotReason.Clash(clash.id, liked, pinned, overlaps, overLimit), text)
        }
        return answer(WhyNotReason.LowScore, "$name fits, but your plan already has things you'd probably like more.")
    }

    /** The "Swap it in" chip, when offered. */
    fun chip(answer: WhyNotAnswer, bundle: EventBundle): Chip? =
        if (!answer.canSwapIn) null else Chip("Swap it in", AssistantAction.planEdit(listOf(PlanOp.Add(answer.itemId)), bundle))
}
