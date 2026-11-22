package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the Live Update's items come from (shared spec §8): the plan, else hearts, else the event's own day. */
enum class LiveSource { PLAN, HEARTS, EVENT }

/** API-011: an urgent organizer announcement shown front and centre for up to 12 h or until dismissed. */
data class UrgentBlock(val id: Int, val title: String, val body: String, val sentAt: Instant) {
    fun isActive(now: Instant, dismissedId: Int?): Boolean =
        id != dismissedId && now.isBefore(sentAt.plus(LiveFollow.URGENT_FOR)) && !now.isBefore(sentAt.minus(LiveFollow.CLOCK_SKEW))
}

enum class LiveMode {
    /** Nothing has started yet today: "Gates open in …" (the event's start) or "Up next …". */
    BEFORE,

    /** An item is in progress and/or another is coming. */
    ACTIVE,

    /** Nothing left today: the day's summary, until the window closes. */
    END_OF_DAY,

    /** [LiveState.urgent] replaces everything else. */
    URGENT,
}

/** A part of the day's progress bar: an item or a gap between items, in whole minutes (≥ 1). */
data class LiveSegment(val minutes: Int, val isItem: Boolean)

/**
 * What the Live Update shows (shared spec §8; all fields optional in spirit so an urgent-only update
 * works). Times are instants; the notification formats them in the event's zone.
 */
data class LiveState(
    val mode: LiveMode,
    val source: LiveSource,
    val current: ScheduleItem?,
    val next: ScheduleItem?,
    val after: ScheduleItem?,
    /** Straight-line walk estimate from the current item's location (else the user) to the next one's. */
    val walkMinutesToNext: Int?,
    /** The event's start when [mode] is BEFORE and it opens later today ("Gates open in …"). */
    val gatesOpen: Instant?,
    val title: String,
    val text: String,
    /** The status-bar chip (`setShortCriticalText`): a short clock time like "7:30", or "Urgent". */
    val chip: String?,
    /** What the chronometer counts down to (the next start or the gates), when there is one. */
    val countdownTo: Instant?,
    val segments: List<LiveSegment>,
    /** Minutes since the first segment started, 0…sum of segments. */
    val progress: Int,
    /** Minutes into the day's span at which each item starts (progress points). */
    val points: List<Int>,
    val urgent: UrgentBlock?,
    /** The item a tap, Heart or Show on map acts on: current, else next. */
    val focus: ScheduleItem?,
    val itemsToday: Int,
)

/**
 * Device-driven Live Update rules (shared spec §8). Pure; `live/` posts the notification and schedules
 * wake-ups from these values.
 *
 * - **Items of a day**: the plan's items on that day (event-zone date), else the hearted schedule items
 *   starting that day, else every schedule item starting that day; minus items the user skipped. Sorted
 *   by (start, end, id).
 * - **Window**: from 2 h before the first relevant start (the plan's/hearts' first item; with the event
 *   source, the earlier of the first item and the event's start when that is the same day) to 30 min
 *   after the last relevant end. Outside it there is no Live Update (unless an urgent block is active).
 * - **Current** = the earliest-starting relevant item in progress (ties: end, id); **next** = the first
 *   relevant item starting after now; **after** = the one following next.
 */
object LiveFollow {

    val LEAD: Duration = Duration.ofHours(2)
    val TAIL: Duration = Duration.ofMinutes(30)
    val URGENT_FOR: Duration = Duration.ofHours(12)
    val CLOCK_SKEW: Duration = Duration.ofMinutes(10)

    /** Reminders fire this long before a hearted / planned item, plus the walk there. */
    val REMINDER_LEAD: Duration = Duration.ofMinutes(15)
    private val MAX_REMINDER_LEAD: Duration = Duration.ofMinutes(45)

    /** Wake at least this often while a Live Update is up (progress bar and texts). */
    val REFRESH_EVERY: Duration = Duration.ofMinutes(15)

    fun source(plan: Plan?, hearts: Hearts, day: LocalDate): LiveSource = when {
        plan != null && plan.days.any { it.date == day && it.items.isNotEmpty() } -> LiveSource.PLAN
        hearts.scheduleItems.isNotEmpty() -> LiveSource.HEARTS
        else -> LiveSource.EVENT
    }

    fun items(bundle: EventBundle, plan: Plan?, hearts: Hearts, day: LocalDate, skipped: Set<Int>): Pair<LiveSource, List<ScheduleItem>> {
        val zone = bundle.event.zone
        fun onDay(item: ScheduleItem) = item.startTime?.let { EventTime.localDate(it, zone) } == day
        val planIds = plan?.days?.filter { it.date == day }?.flatMap { it.items }?.map { it.itemId }.orEmpty()
        val planItems = planIds.mapNotNull { bundle.scheduleItem(it) }.filter { it.startTime != null }
        val heartedItems = bundle.schedule.filter { it.id in hearts.scheduleItems && onDay(it) }
        val (source, list) = when {
            planItems.isNotEmpty() -> LiveSource.PLAN to planItems
            heartedItems.isNotEmpty() -> LiveSource.HEARTS to heartedItems
            else -> LiveSource.EVENT to bundle.schedule.filter { onDay(it) }
        }
        return source to list.filter { it.id !in skipped }.sortedWith(Planner.ITEM_ORDER)
    }

    /** The Live Update window of [day], or null when the day has no relevant items. */
    fun window(bundle: EventBundle, plan: Plan?, hearts: Hearts, day: LocalDate, skipped: Set<Int> = emptySet()): Pair<Instant, Instant>? {
        val (source, items) = items(bundle, plan, hearts, day, skipped)
        if (items.isEmpty()) return null
        var first = items.minOf { it.startTime!!.toInstant() }
        val eventStart = bundle.event.startTime
        if (source == LiveSource.EVENT && eventStart != null && EventTime.localDate(eventStart, bundle.event.zone) == day) {
            first = minOf(first, eventStart.toInstant())
        }
        val last = items.maxOf { TimeMatcher.effectiveEnd(it)!! }
        return first.minus(LEAD) to last.plus(TAIL)
    }

    /** The first window (today or a later event day) that hasn't ended at [now]. */
    fun nextWindow(bundle: EventBundle, plan: Plan?, hearts: Hearts, now: Instant, skipped: Set<Int> = emptySet()): Pair<Instant, Instant>? {
        val today = now.atZone(bundle.event.zone).toLocalDate()
        return TimeMatcher.eventDays(bundle, now)
            .filter { !it.isBefore(today.minusDays(1)) }
            .firstNotNullOfOrNull { day ->
                window(bundle, plan, hearts, day, if (day == today) skipped else emptySet())?.takeIf { it.second.isAfter(now) }
            }
    }

    /**
     * The Live Update at [now], or null when there should be none (outside every window and no active
     * urgent block). [user] gives the walk to the next item when nothing is in progress.
     */
    fun state(
        bundle: EventBundle,
        plan: Plan?,
        hearts: Hearts,
        now: Instant,
        skipped: Set<Int> = emptySet(),
        urgent: UrgentBlock? = null,
        dismissedUrgentId: Int? = null,
        user: GeoPoint? = null,
        locale: Locale = Locale.getDefault(),
    ): LiveState? {
        val zone = bundle.event.zone
        val activeUrgent = urgent?.takeIf { it.isActive(now, dismissedUrgentId) }
        // The window that contains now (it may have started the evening before for a late item).
        val window = nextWindow(bundle, plan, hearts, now, skipped)?.takeIf { !it.first.isAfter(now) }
        if (window == null && activeUrgent == null) return null
        // The day whose items make the window: the one its first item starts on (else today).
        val day = (window?.first?.plus(LEAD) ?: now).atZone(zone).toLocalDate()
        val (source, items) = items(bundle, plan, hearts, day, skipped)
        val inProgress = items.filter { ScheduleGrouping.isHappeningNow(it, now) }
        val current = inProgress.firstOrNull()
        val upcoming = items.filter { it.startTime!!.toInstant().isAfter(now) }
        val next = upcoming.firstOrNull()
        val after = upcoming.getOrNull(1)
        val walk = next?.let { n ->
            val to = bundle.location(n.locationId)?.coordinate
            val from = bundle.location(current?.locationId)?.coordinate ?: user
            if (current != null && current.locationId != null && current.locationId == n.locationId) 0
            else if (to != null && from != null && Walking.isNearby(from, to)) {
                Walking.minutes(Walking.estimateSeconds(Geo.distanceMeters(from, to))).toInt()
            } else null
        }
        val eventStart = bundle.event.startTime?.toInstant()
        val gates = eventStart?.takeIf { source == LiveSource.EVENT && it.isAfter(now) && it.atZone(zone).toLocalDate() == day && current == null }
        val anyStarted = items.any { !it.startTime!!.toInstant().isAfter(now) }

        val (segments, progress, points) = timeline(items, now)
        val clock = DateTimeFormatter.ofPattern("h:mm", locale)
        val longClock = DateTimeFormatter.ofPattern("h:mm a", locale)
        fun place(item: ScheduleItem) = bundle.location(item.locationId)?.name ?: item.locationName

        if (activeUrgent != null) {
            return LiveState(
                mode = LiveMode.URGENT, source = source, current = current, next = next, after = after,
                walkMinutesToNext = walk, gatesOpen = null,
                title = activeUrgent.title.ifBlank { "Urgent update" },
                text = activeUrgent.body,
                chip = "Urgent", countdownTo = null,
                segments = segments, progress = progress, points = points,
                urgent = activeUrgent, focus = current ?: next, itemsToday = items.size,
            )
        }

        val mode = when {
            current == null && next == null -> LiveMode.END_OF_DAY
            current == null && !anyStarted -> LiveMode.BEFORE
            else -> LiveMode.ACTIVE
        }
        val title: String
        val text: String
        val chip: String?
        val countdown: Instant?
        when {
            mode == LiveMode.END_OF_DAY -> {
                title = "That's a wrap for today"
                text = when (source) {
                    LiveSource.PLAN -> "${items.size} ${if (items.size == 1) "item" else "items"} in your plan today"
                    LiveSource.HEARTS -> "${items.size} liked ${if (items.size == 1) "item" else "items"} today"
                    LiveSource.EVENT -> bundle.event.name
                }
                chip = null
                countdown = null
            }
            gates != null && mode == LiveMode.BEFORE -> {
                // Absolute times: the chronometer counts down, so the text never goes stale between updates.
                title = "Gates open at ${gates.atZone(zone).format(longClock)}"
                text = next?.let { "First up: ${it.name}" + (place(it)?.let { p -> " · $p" } ?: "") } ?: bundle.event.name
                chip = gates.atZone(zone).format(clock)
                countdown = gates
            }
            current != null -> {
                val ends = TimeMatcher.effectiveEnd(current)!!
                title = "Now: ${current.name}" + (place(current)?.let { " · $it" } ?: "")
                text = next?.let { n ->
                    buildString {
                        append("Up next ${n.startTime!!.toInstant().atZone(zone).format(longClock)}: ${n.name}")
                        place(n)?.takeIf { it != place(current) }?.let { append(" · $it") }
                        walk?.takeIf { it > 0 }?.let { append(" · $it min walk") }
                    }
                } ?: "Ends ${ends.atZone(zone).format(longClock)}"
                chip = next?.startTime?.toInstant()?.atZone(zone)?.format(clock) ?: ends.atZone(zone).format(clock)
                countdown = next?.startTime?.toInstant()
            }
            else -> {
                val n = next!!
                val start = n.startTime!!.toInstant()
                title = "${n.name} at ${start.atZone(zone).format(longClock)}"
                text = buildString {
                    append(place(n) ?: ScheduleGrouping.GENERAL_TITLE)
                    walk?.let { append(if (it == 0) " · You're here" else " · $it min walk") }
                    after?.let { append(" · then ${it.name}") }
                }
                chip = start.atZone(zone).format(clock)
                countdown = start
            }
        }
        return LiveState(
            mode = mode, source = source, current = current, next = next, after = after,
            walkMinutesToNext = walk, gatesOpen = gates, title = title, text = text, chip = chip,
            countdownTo = countdown, segments = segments, progress = progress, points = points,
            urgent = null, focus = current ?: next, itemsToday = items.size,
        )
    }

    /**
     * The day's progress bar: from the first item's start to the last item's end, one segment per item
     * (overlapping items merge) and per gap, in whole minutes (≥ 1); progress = minutes elapsed,
     * clamped; points = each item's start offset (distinct, > 0, < total).
     */
    fun timeline(items: List<ScheduleItem>, now: Instant): Triple<List<LiveSegment>, Int, List<Int>> {
        if (items.isEmpty()) return Triple(emptyList(), 0, emptyList())
        val spanStart = items.minOf { it.startTime!!.toInstant() }
        val intervals = items.map { it.startTime!!.toInstant() to TimeMatcher.effectiveEnd(it)!! }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Instant, Instant>>()
        intervals.forEach { (s, e) ->
            val last = merged.lastOrNull()
            if (last != null && !s.isAfter(last.second)) merged[merged.lastIndex] = last.first to maxOf(last.second, e)
            else merged += s to e
        }
        fun minutes(a: Instant, b: Instant) = Duration.between(a, b).toMinutes().toInt()
        val segments = mutableListOf<LiveSegment>()
        var cursor = spanStart
        merged.forEach { (s, e) ->
            if (s.isAfter(cursor)) segments += LiveSegment(minutes(cursor, s).coerceAtLeast(1), isItem = false)
            segments += LiveSegment(minutes(s, e).coerceAtLeast(1), isItem = true)
            cursor = e
        }
        val total = segments.sumOf { it.minutes }
        val progress = minutes(spanStart, now).coerceIn(0, total)
        val points = items.map { minutes(spanStart, it.startTime!!.toInstant()) }.filter { it in 1 until total }.distinct().sorted()
        return Triple(segments, progress, points)
    }

    /**
     * When to wake next while following: the next item start/end, window start/end, reminder or urgent
     * expiry after [now], but at least every [REFRESH_EVERY] while a Live Update is up. Null = nothing
     * left to do for this event.
     */
    fun nextWake(
        bundle: EventBundle,
        plan: Plan?,
        hearts: Hearts,
        now: Instant,
        skipped: Set<Int> = emptySet(),
        urgent: UrgentBlock? = null,
        dismissedUrgentId: Int? = null,
        reminders: List<Pair<Int, Instant>> = emptyList(),
    ): Instant? {
        val window = nextWindow(bundle, plan, hearts, now, skipped)
        val candidates = mutableListOf<Instant>()
        val showing = (window != null && !window.first.isAfter(now)) || urgent?.isActive(now, dismissedUrgentId) == true
        if (window != null) {
            candidates += window.first
            candidates += window.second
            val day = window.first.plus(LEAD).atZone(bundle.event.zone).toLocalDate()
            items(bundle, plan, hearts, day, skipped).second.forEach {
                candidates += it.startTime!!.toInstant()
                candidates += TimeMatcher.effectiveEnd(it)!!
            }
        }
        urgent?.takeIf { it.isActive(now, dismissedUrgentId) }?.let { candidates += it.sentAt.plus(URGENT_FOR) }
        reminders.forEach { candidates += it.second }
        if (showing) candidates += now.plus(REFRESH_EVERY)
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    // ── Reminders ───────────────────────────────────────────────────────────

    /**
     * Local reminders for hearted and planned items starting after [now] (not skipped), each at start −
     * ([REMINDER_LEAD] + the walk from [user] when within walking range), the lead capped at 45 min.
     * Sorted by time, then item id.
     */
    fun reminders(bundle: EventBundle, plan: Plan?, hearts: Hearts, now: Instant, user: GeoPoint?, skipped: Set<Int> = emptySet()): List<Pair<Int, Instant>> {
        val ids = (hearts.scheduleItems + plan?.itemIds.orEmpty()).toSet() - skipped
        return bundle.schedule.filter { it.id in ids && it.startTime?.toInstant()?.isAfter(now) == true }
            .map { item ->
                val start = item.startTime!!.toInstant()
                val walk = walkSeconds(bundle, item, user)?.let { Duration.ofMinutes(Walking.minutes(it)) } ?: Duration.ZERO
                val lead = minOf(REMINDER_LEAD.plus(walk), MAX_REMINDER_LEAD)
                item.id to start.minus(lead)
            }
            .sortedWith(compareBy<Pair<Int, Instant>> { it.second }.thenBy { it.first })
    }

    private fun walkSeconds(bundle: EventBundle, item: ScheduleItem, user: GeoPoint?): Long? {
        val to = bundle.location(item.locationId)?.coordinate ?: return null
        val from = user ?: return null
        val meters = Geo.distanceMeters(from, to)
        return if (Walking.isNearby(meters) && meters >= Geo.HERE_METERS) Walking.estimateSeconds(meters) else null
    }

    /** "Prairie Lights in 15 min · 8 min walk" (title) and "Main Stage · 7:30 – 8:30 PM" (text). */
    fun reminderText(bundle: EventBundle, item: ScheduleItem, now: Instant, user: GeoPoint?, locale: Locale = Locale.getDefault()): Pair<String, String> {
        val zone = bundle.event.zone
        val start = item.startTime!!.toInstant()
        val title = buildString {
            append(item.name).append(' ')
            append(RelativeTime.relativeStart(start, TimeMatcher.effectiveEnd(item), now, zone, locale))
            walkSeconds(bundle, item, user)?.let { append(" · ").append(Walking.walkText(it)) }
        }
        val place = bundle.location(item.locationId)?.name ?: item.locationName ?: ScheduleGrouping.GENERAL_TITLE
        return title to "$place · ${EventTime.formatTimeRange(item.startTime, item.endTime, zone, locale)}"
    }
}
