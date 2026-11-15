package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** How much of the planned window may be booked with items (shared spec §6). */
enum class PlanPace(val key: String, val bookedPercent: Int) {
    RELAXED("relaxed", 50),
    BALANCED("balanced", 70),
    PACKED("packed", 90);

    companion object {
        fun of(value: String?): PlanPace = entries.firstOrNull { it.key == value?.trim()?.lowercase() } ?: BALANCED
    }
}

/**
 * What the attendee wants, extracted from their request by the on-device model ([PlanRequest]) and
 * validated against the bundle: tags lower case and only ones the event uses, categories as the event
 * spells them, ids only of existing schedule items, days only event days.
 */
data class PlanPreferences(
    val likedTags: Set<String> = emptySet(),
    val dislikedTags: Set<String> = emptySet(),
    val likedCategories: Set<String> = emptySet(),
    val dislikedCategories: Set<String> = emptySet(),
    /** Schedule items the attendee named as examples ("more like Prairie Lights"). */
    val similarTo: Set<Int> = emptySet(),
    val days: List<LocalDate> = emptyList(),
    val earliest: LocalTime? = null,
    val latest: LocalTime? = null,
    /** "I've got 3 hours": the window ends this many minutes after it starts. */
    val durationMinutes: Int? = null,
    val pace: PlanPace = PlanPace.BALANCED,
    val includeMeals: Boolean = false,
    val kidFriendly: Boolean = false,
)

enum class MealKind(val label: String, val from: LocalTime, val until: LocalTime) {
    LUNCH("Lunch break", LocalTime.of(12, 0), LocalTime.of(13, 30)),
    DINNER("Dinner break", LocalTime.of(18, 0), LocalTime.of(19, 30)),
}

sealed interface PlanEntry {
    val start: Instant
    val end: Instant
}

/**
 * A schedule item in the plan. [walkMinutes] is the walk from the previous planned item of the day
 * (null for the first one, or when a location has no coordinates; 0 at the same location).
 * [alternatives] are what Swap offers (best first); [orInstead] are hearted items that clash with it.
 */
data class PlannedItem(
    val itemId: Int,
    override val start: Instant,
    override val end: Instant,
    val reason: String,
    val walkMinutes: Int?,
    val alternatives: List<Int>,
    val orInstead: List<Int>,
) : PlanEntry

/** A 45-minute meal break, with a food location to suggest when the event has one. */
data class MealBreak(
    val kind: MealKind,
    override val start: Instant,
    override val end: Instant,
    val locationId: Int?,
) : PlanEntry

data class PlanDay(
    val date: LocalDate,
    val windowStart: Instant,
    val windowEnd: Instant,
    val entries: List<PlanEntry>,
) {
    val items: List<PlannedItem> get() = entries.filterIsInstance<PlannedItem>()
}

/** A plan for one event, stored on the device ([PlanCodec]). */
data class Plan(
    val eventId: Int,
    val createdAt: Instant,
    val request: String,
    val preferences: PlanPreferences,
    val days: List<PlanDay>,
    /** Planned item id → [Planner.fingerprint] when planned, to notice content changes. */
    val fingerprints: Map<Int, String>,
    /** The edits' constraints (assistant spec §4); the planner re-solves with them ([Planner.replan]). */
    val constraints: PlanConstraints = PlanConstraints.NONE,
    /** Items the attendee checked off (by tap or auto check-off, spec §6); they stay in re-solves. */
    val checkedOff: Set<Int> = emptySet(),
) {
    val items: List<PlannedItem> get() = days.flatMap { it.items }
    val itemIds: List<Int> get() = items.map { it.itemId }
    val isEmpty: Boolean get() = items.isEmpty()
}

/** One meal break of one event day ("I already ate" marks the next one done). */
data class MealSlot(val date: LocalDate, val kind: MealKind) {
    /** "2026-11-14:lunch". */
    fun encode(): String = "$date:${kind.name.lowercase()}"

    companion object {
        fun decode(value: String): MealSlot? {
            val date = runCatching { LocalDate.parse(value.substringBefore(':')) }.getOrNull() ?: return null
            val kind = MealKind.entries.firstOrNull { it.name.equals(value.substringAfter(':', ""), ignoreCase = true) } ?: return null
            return MealSlot(date, kind)
        }
    }
}

/** Where the attendee said they are ("I'm at Taco Row") and when they said it. */
data class PlanOrigin(val locationId: Int, val at: Instant)

/**
 * What plan edits add on top of [PlanPreferences] (assistant spec §4, planner rules 13–20). Every
 * field defaults to "no constraint"; [NONE] makes the planner behave exactly as rules 1–12.
 */
data class PlanConstraints(
    /** Items the attendee kept or added: must-includes ahead of liked items (rule 16). */
    val pinned: Set<Int> = emptySet(),
    /** Items the attendee removed or replaced: never candidates, even when liked or pinned (rule 14). */
    val excluded: Set<Int> = emptySet(),
    /** "I already ate": food items and meal breaks may not start before this (rules 14, 19). */
    val noFoodUntil: Instant? = null,
    /** Meal breaks marked done; they are not planned again (rule 19). */
    val mealsDone: Set<MealSlot> = emptySet(),
    /** "Start at 2": the window starts no earlier than this on every day (rule 13). */
    val startAt: LocalTime? = null,
    /** "Done by 9": the window ends no later than this on every day (next day when before 06:00; rule 13). */
    val doneBy: LocalTime? = null,
    /** "Short walks": no walk between consecutive items longer than this (rule 17). */
    val maxWalkMinutes: Int? = null,
    /** "I'm tired": replaces the preferences' pace (rule 18). */
    val pace: PlanPace? = null,
    /** "I'm at Taco Row": the plan restarts from there (rule 15). */
    val origin: PlanOrigin? = null,
    /** "More bbq" / "less dj": lower-case tags with an extra liked (+1) / disliked (−2) indicator (rule 13 score). */
    val moreTags: Set<String> = emptySet(),
    val lessTags: Set<String> = emptySet(),
    /** Group plans (spec §7): item id → how many people liked or planned it (+1 point each). */
    val groupLikes: Map<Int, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = PlanConstraints()
    }
}

/**
 * The planner's deterministic core (shared spec §6; iOS implements exactly these rules). The model only
 * extracts [PlanPreferences]; everything below is plain code, unit tested with `PlannerFixture`.
 *
 * Every rule, in order:
 * 1. **Times**: an item's end is its end, or start + 1 h when missing / not after the start
 *    ([TimeMatcher.effectiveEnd]); its booked length is the whole minutes between (rounded down).
 *    `from` (now, or the re-plan time) is truncated to the minute. All dates and clock times are in
 *    the event's zone.
 * 2. **Days**: the preference days that are event days ([TimeMatcher.eventDays]) on or after `from`'s
 *    date, ascending. With none, the first event day on or after `from`'s date that has a candidate.
 * 3. **Window per day**: start = max(`from`, day at `earliest` or 00:00). End = day at `latest`, on the
 *    next day when `latest` < 06:00 or (`earliest` is set and `latest` ≤ `earliest`); without `latest`
 *    the next midnight. With `durationMinutes`, end = min(end, start + duration). Empty → no day.
 * 4. **Candidates**: items with a start on that day (local date), start ≥ window start and end ≤
 *    window end; with `kidFriendly`, items tagged [ADULT_TAGS] are dropped unless hearted; items whose
 *    score is ≤ 0 are dropped unless hearted. Ordered by (start, end, id).
 * 5. **Score** (integer milli-points, `floor(x × 1000 + 0.5)`): x = 0.5 (base, so a request with no
 *    stated likes still gets a plan) + 3·hearted + 2·S + M + K − 2·D, where S = the highest
 *    similarity to a liked item (the hearted schedule items and `similarTo`, never the item itself;
 *    0 without any), M = 1 when a tag is in `likedTags`, its location's category is in
 *    `likedCategories` (case-insensitive) or its location is hearted, K = 1 with `kidFriendly` and a
 *    tag in [FAMILY_TAGS], D = 1 when a tag is in `dislikedTags` or the category is in
 *    `dislikedCategories`. Each indicator counts once.
 * 6. **Gap**: b may follow a when a.end + walk(a, b) + 5 min ≤ b.start. walk = 0 at the same location
 *    or when either has no coordinates, else [Walking.estimateSeconds] of the straight line, in whole
 *    minutes rounded up (at least 1, [Walking.minutes]).
 * 7. **Hearted items are must-include**: hearted candidates by (score desc, start, end, id); each is
 *    kept when it can be planned with every one kept before it (the gap rule both ways); otherwise it
 *    becomes an "or instead" of the earliest-starting kept item it clashes with (ties: lower id) and is
 *    left out of the plan.
 * 8. **Pace**: span = from max(window start, earliest candidate start) to min(window end, latest
 *    candidate end), in whole minutes; cap = max(floor(span × bookedPercent / 100), booked minutes of
 *    the kept hearted items).
 * 9. **Choice**: among all chains of candidates (in candidate order, each pair following the gap rule)
 *    that contain every kept hearted item and book ≤ cap minutes, the one with the highest total
 *    score; ties: fewer booked minutes, then fewer items, then the lexicographically smallest list of
 *    candidate positions. (Dynamic programming over (last item, booked minutes).)
 * 10. **Meals** (`includeMeals`): for lunch 12:00–13:30 and dinner 18:00–19:30, clipped to the window
 *    and only on a day with planned items, a 45-minute break at the earliest start among {the clipped
 *    window start, each planned end inside it}, each rounded up to a 5-minute clock time, that ends by
 *    the clipped window end and overlaps no planned item. It suggests a food location (category or a
 *    tag containing "food" after [SearchText.normalize]) nearest the planned item that ended last
 *    before the break (ties: sort order, id), else the first food location by (sort order, id).
 * 11. **Reasons**, first that applies: "Liked by <n> of you" (group plans, rule 20, n ≥ 2); "You liked
 *    this" (hearted); "You added this" (pinned, rule 16); "Because you liked <name>" (S ≥ 0.2 from a
 *    hearted item); "Because you like <tag>" (first liked tag in the item's tag order); "Because you
 *    like <category, lower case>"; "At <location>, which you liked"; "Kid-friendly"; "Like <name>"
 *    (S ≥ 0.2 from a `similarTo` item); "Fits your day". The most similar item: highest S, then lower id.
 * 12. **Swap alternatives** (≤ 3): the item's "or instead" items first, then other candidates of the
 *    day (not planned, not left-out hearted) that fit between the previous and next planned items by
 *    the gap rule, by (score desc, start, id). Swapping ignores the pace cap. Entries that start before
 *    the day's window start (frozen by a re-solve, rule 13) are kept as they are.
 *
 * Constraints ([PlanConstraints], assistant spec §4). With [PlanConstraints.NONE] none of these rules
 * changes anything, so rules 1–12 give exactly the results above.
 * 13. **Window and score with constraints**: window start = max(rule 3 start, day at `startAt`); window
 *    end = min(rule 3 end, day at `doneBy`, on the next day when `doneBy` < 06:00). With
 *    `durationMinutes` the end is capped at anchor + duration, where anchor = the window start, or in
 *    a re-solve ([replan]) the day's previous window start. Score x (rule 5) adds + 1 when a tag is in
 *    `moreTags`, − 2 when a tag is in `lessTags`, and + `groupLikes[id]` (rule 20); each once.
 * 14. **Candidates with constraints**: `excluded` items are never candidates (even hearted or pinned);
 *    pinned items are kept like hearted ones by the kid-friendly and score ≤ 0 filters; with
 *    `noFoodUntil`, food items ([isFood]: the location's category or a location tag, or an item tag,
 *    contains "food" after [SearchText.normalize]) starting before it are dropped unless pinned.
 * 15. **Origin** ("I'm at X"): applies to the day of `origin.at` (event zone) when every frozen item of
 *    that day started before `origin.at`. Then a candidate b is kept only when origin.at (truncated to
 *    the minute) + walk(X, b) ≤ b.start (no 5-minute buffer; walk as rule 6 with X as the previous
 *    location, 0 when unknown), and with `maxWalkMinutes` walk(X, b) must not exceed it; the first
 *    planned item after the frozen ones shows walk(X, b) as its walk (null when unknown).
 * 16. **Pinned items are must-includes first**: rule 7 runs on pinned candidates by (start, end, id),
 *    then on the other hearted candidates by (score desc, start, end, id). In a re-solve the frozen
 *    items count as already kept (a must-include that clashes with one becomes its "or instead").
 * 17. **Walk limit**: with `maxWalkMinutes`, b may follow a (rule 6) only when walk(a, b) ≤ the limit;
 *    unknown walks (no coordinates) are not limited.
 * 18. **Pace**: `pace` replaces the preferences' pace in rule 8.
 * 19. **Meals with constraints**: a meal kind whose [MealSlot] is in `mealsDone`, or that a frozen meal
 *    of the day already covers, is not planned; with `noFoodUntil` the rounded-up `noFoodUntil` is an
 *    extra start candidate and starts before `noFoodUntil` are skipped. Frozen meals stay.
 * 20. **Re-solve** ([replan], every plan edit): `from` = now truncated to the minute. Days before
 *    `from`'s date stay unchanged. Re-solved days = the plan's days on or after that date, plus the
 *    dates of pinned items that are event days on or after it (ascending); with none, rule 2. On each
 *    day, **frozen** = its planned items that started before `from` or are checked off (still in the
 *    bundle, not excluded) and its meal breaks that started before `from`; frozen entries stay
 *    verbatim and are not candidates; new candidates must fit every frozen item (rule 6 both ways).
 *    Then rules 3–19 with `from`. A day whose window is empty keeps only its frozen entries. The plan
 *    keeps its request, preferences, creation time and check-offs, and takes the new constraints and
 *    fingerprints. Group plans add + `groupLikes[id]` to x and list "Liked by <n> of you".
 */
class Planner(
    private val bundle: EventBundle,
    private val preferences: PlanPreferences,
    private val hearts: Hearts,
    private val constraints: PlanConstraints = PlanConstraints.NONE,
    private val similarity: ItemSimilarity = TfIdfSimilarity(bundle),
) {
    private val zone: ZoneId = bundle.event.zone
    private val itemsById: Map<Int, ScheduleItem> = bundle.schedule.associateBy { it.id }
    private val likedTags = preferences.likedTags.map { it.lowercase() }.toSet()
    private val dislikedTags = preferences.dislikedTags.map { it.lowercase() }.toSet()
    private val likedCategories = preferences.likedCategories.map { it.lowercase() }.toSet()
    private val dislikedCategories = preferences.dislikedCategories.map { it.lowercase() }.toSet()
    private val heartedLiked: List<ScheduleItem> = hearts.scheduleItems.sorted().mapNotNull { itemsById[it] }
    private val namedLiked: List<ScheduleItem> = preferences.similarTo.sorted().mapNotNull { itemsById[it] }
    private val scores = HashMap<Int, Long>()
    private val moreTags = constraints.moreTags.map { it.lowercase() }.toSet()
    private val lessTags = constraints.lessTags.map { it.lowercase() }.toSet()
    private val pace: PlanPace = constraints.pace ?: preferences.pace

    // ── Public API ──────────────────────────────────────────────────────────

    fun plan(from: Instant, request: String, createdAt: Instant = from): Plan {
        val start = from.truncatedTo(ChronoUnit.MINUTES)
        val today = start.atZone(zone).toLocalDate()
        val eventDays = TimeMatcher.eventDays(bundle, start)
        val wanted = preferences.days.distinct().sorted().filter { it in eventDays && !it.isBefore(today) }
        val days = if (wanted.isNotEmpty()) {
            wanted.mapNotNull { planDay(it, start) }
        } else {
            eventDays.filter { !it.isBefore(today) }.firstNotNullOfOrNull { day ->
                planDay(day, start)?.takeIf { it.items.isNotEmpty() }
            }?.let { listOf(it) } ?: emptyList()
        }
        return Plan(
            eventId = bundle.event.id,
            createdAt = createdAt,
            request = request,
            preferences = preferences,
            days = days,
            fingerprints = fingerprints(days),
            constraints = constraints,
        )
    }

    /**
     * Rule 20: [plan] re-solved at [from] with this planner's preferences and constraints (every plan
     * edit). Without constraints and at the plan's own `from` this is the same plan.
     */
    fun replan(plan: Plan, from: Instant): Plan {
        val start = from.truncatedTo(ChronoUnit.MINUTES)
        val today = start.atZone(zone).toLocalDate()
        val eventDays = TimeMatcher.eventDays(bundle, start)
        val pinnedDays = constraints.pinned.mapNotNull { itemsById[it]?.startTime?.let { s -> EventTime.localDate(s, zone) } }
            .filter { it in eventDays && !it.isBefore(today) }
        val wanted = (plan.days.map { it.date }.filter { !it.isBefore(today) } + pinnedDays).distinct().sorted()
        val past = plan.days.filter { it.date.isBefore(today) }
        val solved = if (wanted.isEmpty()) {
            plan(start, plan.request, plan.createdAt).days
        } else {
            val oldDays = plan.days.associateBy { it.date }
            wanted.mapNotNull { date ->
                val old = oldDays[date]
                val frozen = old?.items.orEmpty().filter {
                    (it.start.isBefore(start) || it.itemId in plan.checkedOff) && it.itemId !in constraints.excluded && itemsById.containsKey(it.itemId)
                }
                val frozenMeals = old?.entries.orEmpty().filterIsInstance<MealBreak>().filter { it.start.isBefore(start) }
                val anchor = old?.windowStart?.takeIf { preferences.durationMinutes != null }
                planDay(date, start, frozen, frozenMeals, anchor)
            }
        }
        val days = past + solved
        return plan.copy(days = days, fingerprints = fingerprints(days), constraints = constraints)
    }

    /** Rule 4 (with 14) for a planned [day]'s window: what could be planned that day. */
    fun dayCandidates(day: PlanDay): List<ScheduleItem> = candidates(day.date, day.windowStart, day.windowEnd)

    /** Rule 6 (with 17): [b] can come right after [a]. */
    fun canFollow(a: ScheduleItem, b: ScheduleItem): Boolean = follows(a, b)

    /** Rules 6/17 in either order: both can be planned. */
    fun fitsWith(a: ScheduleItem, b: ScheduleItem): Boolean = compatible(a, b)

    /** The walk shown between two items (rule 6): 0 at the same location, null without coordinates. */
    fun walkBetween(a: ScheduleItem, b: ScheduleItem): Int? = walkShown(a, b)

    /** Rule 14: dropped by "I already ate". */
    fun foodBlocked(item: ScheduleItem): Boolean {
        val until = constraints.noFoodUntil ?: return false
        return item.id !in constraints.pinned && isFood(bundle, item) && start(item).isBefore(until)
    }

    private fun fingerprints(days: List<PlanDay>): Map<Int, String> =
        days.flatMap { it.items }.filter { itemsById.containsKey(it.itemId) }.associate { it.itemId to fingerprint(itemsById.getValue(it.itemId)) }

    /** Replaces [oldId] by [newId] in [plan] (one of its alternatives); null when either is gone. */
    fun swap(plan: Plan, oldId: Int, newId: Int): Plan? {
        val dayIndex = plan.days.indexOfFirst { day -> day.items.any { it.itemId == oldId } }
        if (dayIndex < 0 || itemsById[newId] == null || newId in plan.itemIds) return null
        val day = plan.days[dayIndex]
        val chosen = day.items.map { if (it.itemId == oldId) newId else it.itemId }
            .mapNotNull { itemsById[it] }
            .sortedWith(ITEM_ORDER)
        val orInstead = day.items.associate { it.itemId to it.orInstead }.toMutableMap()
        // The swapped-out item's clashing hearted items stay with the slot.
        orInstead[newId] = (orInstead.remove(oldId).orEmpty() + oldId).filter { it != newId && hearts.scheduleItems.contains(it) }
        val frozen = day.items.filter { it.start.isBefore(day.windowStart) && it.itemId != oldId }.associateBy { it.itemId }
        val frozenMeals = day.entries.filterIsInstance<MealBreak>().filter { it.start.isBefore(day.windowStart) }
        val rebuilt = buildDay(day.date, day.windowStart, day.windowEnd, chosen, orInstead, candidates(day.date, day.windowStart, day.windowEnd), frozen, frozenMeals, null)
        val days = plan.days.toMutableList().also { it[dayIndex] = rebuilt }
        return plan.copy(days = days, fingerprints = fingerprints(days))
    }

    /** The integer milli-point score of [item] (rule 5). */
    fun score(item: ScheduleItem): Long = scores.getOrPut(item.id) {
        val hearted = if (item.id in hearts.scheduleItems) 1 else 0
        val similar = bestSimilar(item, heartedLiked + namedLiked)?.second ?: 0.0
        val more = if (item.tags.any { it.lowercase() in moreTags }) 1 else 0
        val less = if (item.tags.any { it.lowercase() in lessTags }) 1 else 0
        val group = constraints.groupLikes[item.id] ?: 0
        val raw = BASE + 3.0 * hearted + 2.0 * similar + matches(item) + kid(item) - 2.0 * disliked(item) + more - 2.0 * less + group
        floor(raw * 1000 + 0.5).toLong()
    }

    fun reason(item: ScheduleItem): String {
        val group = constraints.groupLikes[item.id] ?: 0
        if (group >= 2) return "Liked by $group of you"
        if (item.id in hearts.scheduleItems) return "You liked this"
        if (item.id in constraints.pinned) return "You added this"
        bestSimilar(item, heartedLiked)?.takeIf { it.second >= SIMILAR_REASON }?.let { return "Because you liked ${it.first.name}" }
        item.tags.firstOrNull { it.lowercase() in likedTags }?.let { return "Because you like ${it.lowercase()}" }
        val category = category(item)
        if (category != null && category.lowercase() in likedCategories) return "Because you like ${category.lowercase()}"
        val location = bundle.location(item.locationId)
        if (location != null && location.id in hearts.locations) return "At ${location.name}, which you liked"
        if (kid(item) == 1) return "Kid-friendly"
        bestSimilar(item, namedLiked)?.takeIf { it.second >= SIMILAR_REASON }?.let { return "Like ${it.first.name}" }
        return "Fits your day"
    }

    // ── Days ────────────────────────────────────────────────────────────────

    private fun window(day: LocalDate, from: Instant, anchor: Instant? = null): Pair<Instant, Instant>? {
        val earliest = preferences.earliest
        val latest = preferences.latest
        var start = maxOf(from, day.atTime(earliest ?: LocalTime.MIDNIGHT).atZone(zone).toInstant())
        constraints.startAt?.let { start = maxOf(start, day.atTime(it).atZone(zone).toInstant()) }
        var end = if (latest == null) {
            day.plusDays(1).atStartOfDay(zone).toInstant()
        } else {
            val nextDay = latest.isBefore(NIGHT_OWL_UNTIL) || (earliest != null && !latest.isAfter(earliest))
            (if (nextDay) day.plusDays(1) else day).atTime(latest).atZone(zone).toInstant()
        }
        constraints.doneBy?.let { doneBy ->
            end = minOf(end, (if (doneBy.isBefore(NIGHT_OWL_UNTIL)) day.plusDays(1) else day).atTime(doneBy).atZone(zone).toInstant())
        }
        preferences.durationMinutes?.takeIf { it > 0 }?.let { end = minOf(end, (anchor ?: start).plus(Duration.ofMinutes(it.toLong()))) }
        return if (end.isAfter(start)) start to end else null
    }

    private fun candidates(
        day: LocalDate,
        windowStart: Instant,
        windowEnd: Instant,
        frozen: Set<Int> = emptySet(),
        origin: PlanOrigin? = null,
    ): List<ScheduleItem> =
        bundle.schedule.filter { item ->
            val start = item.startTime ?: return@filter false
            val end = TimeMatcher.effectiveEnd(item) ?: return@filter false
            val kept = item.id in hearts.scheduleItems || item.id in constraints.pinned
            item.id !in constraints.excluded && item.id !in frozen &&
                EventTime.localDate(start, zone) == day &&
                !start.toInstant().isBefore(windowStart) && !end.isAfter(windowEnd) &&
                (kept || !(preferences.kidFriendly && item.tags.any { it.lowercase() in ADULT_TAGS })) &&
                (kept || score(item) > 0) &&
                !foodBlocked(item) &&
                (origin == null || reachableFrom(origin, item))
        }.sortedWith(ITEM_ORDER)

    /** Rule 15: [item] can be reached from where the attendee said they are. */
    private fun reachableFrom(origin: PlanOrigin, item: ScheduleItem): Boolean {
        val walk = originWalk(origin, item)
        val limit = constraints.maxWalkMinutes
        if (walk != null && limit != null && walk > limit) return false
        val at = origin.at.truncatedTo(ChronoUnit.MINUTES)
        return !at.plus(Duration.ofMinutes((walk ?: 0).toLong())).isAfter(start(item))
    }

    private fun originWalk(origin: PlanOrigin, item: ScheduleItem): Int? {
        if (item.locationId != null && item.locationId == origin.locationId) return 0
        val from = bundle.location(origin.locationId)?.coordinate ?: return null
        val to = bundle.location(item.locationId)?.coordinate ?: return null
        return Walking.minutes(Walking.estimateSeconds(Geo.distanceMeters(from, to))).toInt()
    }

    private fun planDay(
        day: LocalDate,
        from: Instant,
        frozen: List<PlannedItem> = emptyList(),
        frozenMeals: List<MealBreak> = emptyList(),
        anchor: Instant? = null,
    ): PlanDay? {
        val frozenItems = frozen.mapNotNull { itemsById[it.itemId] }.sortedWith(ITEM_ORDER)
        val frozenEntries: List<PlanEntry> = (frozen + frozenMeals).sortedWith(compareBy<PlanEntry> { it.start }.thenBy { it.end })
        val (windowStart, windowEnd) = window(day, from, anchor)
            ?: return if (frozenEntries.isEmpty()) null else PlanDay(day, from, from, frozenEntries)
        // Rule 15: the origin applies on its day when it is newer than every frozen start.
        val origin = constraints.origin?.takeIf { o ->
            o.at.atZone(zone).toLocalDate() == day && frozenItems.all { start(it).isBefore(o.at) }
        }
        val candidates = candidates(day, windowStart, windowEnd, frozen.map { it.itemId }.toSet(), origin)
        if (candidates.isEmpty() && frozenItems.isEmpty()) return PlanDay(day, windowStart, windowEnd, frozenMeals.sortedBy { it.start })

        // Rules 7 and 16: pinned, then hearted items are must-include unless they clash with a kept one.
        val kept = frozenItems.toMutableList()
        val orInstead = HashMap<Int, MutableList<Int>>()
        val pinned = candidates.filter { it.id in constraints.pinned }.sortedWith(ITEM_ORDER)
        val heartedMust = candidates.filter { it.id in hearts.scheduleItems && it.id !in constraints.pinned }
            .sortedWith(compareByDescending<ScheduleItem> { score(it) }.then(ITEM_ORDER))
        (pinned + heartedMust)
            .forEach { item ->
                val clashes = kept.filter { !compatible(it, item) }
                if (clashes.isEmpty()) {
                    kept += item
                } else {
                    val owner = clashes.minWith(compareBy<ScheduleItem> { start(it) }.thenBy { it.id })
                    orInstead.getOrPut(owner.id) { mutableListOf() } += item.id
                }
            }
        val leftOut = orInstead.values.flatten().toSet()
        val frozenIds = frozenItems.map { it.id }.toSet()
        val pool = candidates.filter { it.id !in leftOut && frozenItems.all { f -> compatible(f, it) } }
        val newKept = kept.filter { it.id !in frozenIds }
        val must = newKept.map { it.id }.toSet()

        // Rule 8: pace cap.
        val chosen = if (candidates.isEmpty()) {
            emptyList()
        } else {
            val spanStart = maxOf(windowStart, candidates.minOf { start(it) })
            val spanEnd = minOf(windowEnd, candidates.maxOf { end(it) })
            val span = Duration.between(spanStart, spanEnd).toMinutes().coerceAtLeast(0)
            val mustMinutes = newKept.sumOf { minutes(it) }
            val cap = maxOf(span * pace.bookedPercent / 100, mustMinutes)
            choose(pool, must, cap)
        }
        return buildDay(
            day, windowStart, windowEnd, (frozenItems + chosen).sortedWith(ITEM_ORDER), orInstead.mapValues { it.value.toList() }, candidates,
            frozen.associateBy { it.itemId }, frozenMeals, origin,
        )
    }

    /** Rule 9: the best chain by dynamic programming over (last item, booked minutes). */
    private fun choose(pool: List<ScheduleItem>, must: Set<Int>, cap: Long): List<ScheduleItem> {
        val n = pool.size
        val isMust = BooleanArray(n) { pool[it].id in must }
        val mustBefore = IntArray(n + 1) // number of must items at positions < i
        for (i in 0 until n) mustBefore[i + 1] = mustBefore[i] + if (isMust[i]) 1 else 0
        val totalMust = mustBefore[n]
        val itemScore = LongArray(n) { score(pool[it]) }
        val itemMinutes = LongArray(n) { minutes(pool[it]) }
        val states = Array(n) { HashMap<Long, Chain>() }

        fun offer(j: Int, chain: Chain) {
            val current = states[j][chain.minutes]
            if (current == null || chain.betterInState(current)) states[j][chain.minutes] = chain
        }

        for (j in 0 until n) {
            if (itemMinutes[j] > cap) continue
            // Start a chain at j: no must item may come before it.
            if (mustBefore[j] == 0) offer(j, Chain(itemScore[j], itemMinutes[j], intArrayOf(j)))
            for (i in 0 until j) {
                if (states[i].isEmpty()) continue
                // Skipping a must item between i and j is not allowed.
                if (mustBefore[j] - mustBefore[i + 1] > 0) continue
                if (!compatible(pool[i], pool[j])) continue
                for (chain in states[i].values) {
                    val minutes = chain.minutes + itemMinutes[j]
                    if (minutes > cap) continue
                    offer(j, Chain(chain.score + itemScore[j], minutes, chain.positions + j))
                }
            }
        }

        var best: Chain? = if (totalMust == 0) Chain(0, 0, IntArray(0)) else null
        for (j in 0 until n) {
            // A chain may end at j only when no must item comes after it.
            if (totalMust - mustBefore[j + 1] > 0) continue
            for (chain in states[j].values) if (best == null || chain.betterFinal(best)) best = chain
        }
        return best?.positions?.map { pool[it] }.orEmpty()
    }

    private class Chain(val score: Long, val minutes: Long, val positions: IntArray) {
        fun betterInState(other: Chain): Boolean = when {
            score != other.score -> score > other.score
            positions.size != other.positions.size -> positions.size < other.positions.size
            else -> lexLess(positions, other.positions)
        }

        fun betterFinal(other: Chain): Boolean = when {
            score != other.score -> score > other.score
            minutes != other.minutes -> minutes < other.minutes
            positions.size != other.positions.size -> positions.size < other.positions.size
            else -> lexLess(positions, other.positions)
        }

        private fun lexLess(a: IntArray, b: IntArray): Boolean {
            for (k in 0 until minOf(a.size, b.size)) if (a[k] != b[k]) return a[k] < b[k]
            return a.size < b.size
        }
    }

    /** Items, walks, alternatives and meals for one day (also after a swap). */
    private fun buildDay(
        day: LocalDate,
        windowStart: Instant,
        windowEnd: Instant,
        chosen: List<ScheduleItem>,
        orInstead: Map<Int, List<Int>>,
        candidates: List<ScheduleItem>,
        frozen: Map<Int, PlannedItem> = emptyMap(),
        frozenMeals: List<MealBreak> = emptyList(),
        origin: PlanOrigin? = null,
    ): PlanDay {
        val chosenIds = chosen.map { it.id }.toSet()
        val leftOut = orInstead.values.flatten().toSet()
        val firstNew = chosen.firstOrNull { it.id !in frozen }?.id
        val planned = chosen.mapIndexed { index, item ->
            frozen[item.id]?.let { return@mapIndexed it }
            val previous = chosen.getOrNull(index - 1)
            val next = chosen.getOrNull(index + 1)
            val clashing = orInstead[item.id].orEmpty()
            val others = candidates
                .filter { it.id !in chosenIds && it.id !in leftOut }
                .filter { (previous == null || follows(previous, it)) && (next == null || follows(it, next)) }
                .sortedWith(compareByDescending<ScheduleItem> { score(it) }.thenBy { start(it) }.thenBy { it.id })
                .map { it.id }
            PlannedItem(
                itemId = item.id,
                start = start(item),
                end = end(item),
                reason = reason(item),
                walkMinutes = if (origin != null && item.id == firstNew) originWalk(origin, item) else previous?.let { walkShown(it, item) },
                alternatives = (clashing + others).distinct().take(MAX_ALTERNATIVES),
                orInstead = clashing,
            )
        }
        val meals = if (preferences.includeMeals && chosen.isNotEmpty()) {
            MealKind.entries
                .filter { kind -> MealSlot(day, kind) !in constraints.mealsDone && frozenMeals.none { it.kind == kind } }
                .mapNotNull { meal(it, day, windowStart, windowEnd, chosen) }
        } else {
            emptyList()
        }
        val entries = (planned + frozenMeals + meals).sortedWith(compareBy<PlanEntry> { it.start }.thenBy { it.end })
        return PlanDay(day, windowStart, windowEnd, entries)
    }

    private fun meal(kind: MealKind, day: LocalDate, windowStart: Instant, windowEnd: Instant, chosen: List<ScheduleItem>): MealBreak? {
        val from = maxOf(windowStart, day.atTime(kind.from).atZone(zone).toInstant())
        val until = minOf(windowEnd, day.atTime(kind.until).atZone(zone).toInstant())
        if (Duration.between(from, until) < MEAL_LENGTH) return null
        val noFood = constraints.noFoodUntil
        val extra = listOfNotNull(noFood?.takeIf { !it.isBefore(from) && it.isBefore(until) })
        val starts = (listOf(from) + chosen.map { end(it) }.filter { !it.isBefore(from) && it.isBefore(until) } + extra)
            .map { roundUpToFiveMinutes(it) }
            .distinct()
            .sorted()
        val start = starts.firstOrNull { s ->
            val e = s.plus(MEAL_LENGTH)
            (noFood == null || !s.isBefore(noFood)) && !e.isAfter(until) && chosen.none { start(it).isBefore(e) && end(it).isAfter(s) }
        } ?: return null
        val before = chosen.filter { !end(it).isAfter(start) }.maxWithOrNull(compareBy<ScheduleItem> { end(it) }.then(ITEM_ORDER))
        return MealBreak(kind, start, start.plus(MEAL_LENGTH), foodLocation(bundle.location(before?.locationId)?.coordinate))
    }

    private fun foodLocation(near: GeoPoint?): Int? {
        val foods = bundle.locations.filter { location ->
            SearchText.normalize(location.category.orEmpty()).contains("food") ||
                location.tags.any { SearchText.normalize(it).contains("food") }
        }.sortedWith(compareBy<EventLocation> { it.sortOrder }.thenBy { it.id })
        if (foods.isEmpty()) return null
        if (near != null) {
            foods.filter { it.coordinate != null }
                .minWithOrNull(compareBy<EventLocation> { Geo.distanceMeters(near, it.coordinate!!) }.thenBy { it.sortOrder }.thenBy { it.id })
                ?.let { return it.id }
        }
        return foods.first().id
    }

    private fun roundUpToFiveMinutes(instant: Instant): Instant {
        val local = instant.atZone(zone).truncatedTo(ChronoUnit.MINUTES)
        val wasExact = local.toInstant() == instant
        val base = if (wasExact) local else local.plusMinutes(1)
        val extra = (5 - base.minute % 5) % 5
        return base.plusMinutes(extra.toLong()).toInstant()
    }

    // ── Scoring helpers ─────────────────────────────────────────────────────

    private fun category(item: ScheduleItem): String? = bundle.location(item.locationId)?.category?.takeIf { it.isNotBlank() }

    private fun matches(item: ScheduleItem): Int {
        val category = category(item)?.lowercase()
        return if (
            item.tags.any { it.lowercase() in likedTags } ||
            (category != null && category in likedCategories) ||
            (item.locationId != null && item.locationId in hearts.locations)
        ) 1 else 0
    }

    private fun kid(item: ScheduleItem): Int =
        if (preferences.kidFriendly && item.tags.any { it.lowercase() in FAMILY_TAGS }) 1 else 0

    private fun disliked(item: ScheduleItem): Int {
        val category = category(item)?.lowercase()
        return if (item.tags.any { it.lowercase() in dislikedTags } || (category != null && category in dislikedCategories)) 1 else 0
    }

    private fun bestSimilar(item: ScheduleItem, liked: List<ScheduleItem>): Pair<ScheduleItem, Double>? =
        liked.filter { it.id != item.id }
            .map { it to similarity.similarity(item, it) }
            .sortedWith(compareByDescending<Pair<ScheduleItem, Double>> { it.second }.thenBy { it.first.id })
            .firstOrNull()

    // ── Time and walking ────────────────────────────────────────────────────

    private fun start(item: ScheduleItem): Instant = item.startTime!!.toInstant()
    private fun end(item: ScheduleItem): Instant = TimeMatcher.effectiveEnd(item)!!
    private fun minutes(item: ScheduleItem): Long = Duration.between(start(item), end(item)).toMinutes()

    /** Rule 6 in either order: the pair can both be planned (the earlier one in candidate order first). */
    private fun compatible(a: ScheduleItem, b: ScheduleItem): Boolean =
        if (ITEM_ORDER.compare(a, b) <= 0) follows(a, b) else follows(b, a)

    /** Rule 6: [b] can come right after [a]. */
    private fun follows(a: ScheduleItem, b: ScheduleItem): Boolean {
        val limit = constraints.maxWalkMinutes
        if (limit != null) walkShown(a, b)?.let { if (it > limit) return false }
        val gap = Duration.ofMinutes(walkMinutes(a, b) + BUFFER_MINUTES)
        return !end(a).plus(gap).isAfter(start(b))
    }

    private fun walkMinutes(a: ScheduleItem, b: ScheduleItem): Long = walkShown(a, b)?.toLong() ?: 0L

    /** The walk shown before [b]: 0 at the same location, null without coordinates. */
    private fun walkShown(a: ScheduleItem, b: ScheduleItem): Int? {
        if (a.locationId != null && a.locationId == b.locationId) return 0
        val from = bundle.location(a.locationId)?.coordinate ?: return null
        val to = bundle.location(b.locationId)?.coordinate ?: return null
        return Walking.minutes(Walking.estimateSeconds(Geo.distanceMeters(from, to))).toInt()
    }

    companion object {
        /** Every candidate's base score, so a request with no stated likes still gets a plan. */
        const val BASE = 0.5
        const val BUFFER_MINUTES = 5L
        const val MAX_ALTERNATIVES = 3
        const val SIMILAR_REASON = 0.2
        val MEAL_LENGTH: Duration = Duration.ofMinutes(45)
        private val NIGHT_OWL_UNTIL: LocalTime = LocalTime.of(6, 0)

        /** Tags that count as kid-friendly (K) with `kidFriendly`. */
        val FAMILY_TAGS = setOf("kid-friendly", "kids", "family", "all ages", "family-friendly")

        /** Tags that are dropped with `kidFriendly` (unless hearted). */
        val ADULT_TAGS = setOf("21+", "18+", "adults", "adults only", "adult")

        val ITEM_ORDER: Comparator<ScheduleItem> = compareBy<ScheduleItem> { it.startTime?.toInstant() }
            .thenBy { TimeMatcher.effectiveEnd(it) }
            .thenBy { it.id }

        /**
         * Rule 14: a food item: its location's category or one of the location's tags, or one of the
         * item's tags, contains "food" after [SearchText.normalize].
         */
        fun isFood(bundle: EventBundle, item: ScheduleItem): Boolean {
            val location = bundle.location(item.locationId)
            fun food(text: String?) = text != null && SearchText.normalize(text).contains("food")
            return food(location?.category) || location?.tags.orEmpty().any { food(it) } || item.tags.any { food(it) }
        }

        /** What a planned item looked like when planned; a different value means the plan changed. */
        fun fingerprint(item: ScheduleItem): String =
            listOf(item.name, item.startTime?.toInstant()?.toString().orEmpty(), TimeMatcher.effectiveEnd(item)?.toString().orEmpty(), item.locationId?.toString().orEmpty())
                .joinToString("|")

        /**
         * One line about [plan]: "8 items, 10:00 AM – 11:00 PM, with a lunch break" (one day) or
         * "5 items over 2 days"; "Nothing fits" when empty.
         */
        fun summary(plan: Plan, zone: ZoneId, locale: java.util.Locale = java.util.Locale.getDefault()): String {
            val items = plan.items
            if (items.isEmpty()) return "Nothing fits"
            val count = "${items.size} ${if (items.size == 1) "item" else "items"}"
            val day = plan.days.singleOrNull { it.items.isNotEmpty() } ?: return "$count over ${plan.days.count { it.items.isNotEmpty() }} days"
            val format = java.time.format.DateTimeFormatter.ofPattern("h:mm a", locale)
            val span = "${day.items.first().start.atZone(zone).format(format)} – ${day.items.maxOf { it.end }.atZone(zone).format(format)}"
            val meals = day.entries.filterIsInstance<MealBreak>().map { it.kind.label.substringBefore(' ').lowercase() }
            val mealText = when (meals.size) {
                0 -> ""
                1 -> ", with a ${meals[0]} break"
                else -> ", with ${meals.joinToString(" and ")} breaks"
            }
            return "$count, $span$mealText"
        }

        /** True when a planned item is gone or changed (name, times, location) since it was planned. */
        fun changed(plan: Plan, bundle: EventBundle): Boolean = plan.fingerprints.any { (id, print) ->
            val item = bundle.scheduleItem(id)
            item == null || fingerprint(item) != print
        }
    }
}
