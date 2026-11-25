package com.arcu.attnd.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Live Update follows on its day (shared spec §8): My schedule ("hearts"), else the event's own day.
 * One list (Nov 22, 2026): there is no separate plan source; the planner's items are in My schedule.
 */
enum class LiveSource { HEARTS, EVENT }

/** API-011: an urgent organizer announcement shown front and centre for up to 12 h or until dismissed. */
data class UrgentBlock(val id: Int, val title: String, val body: String, val sentAt: Instant) {
    fun isActive(now: Instant, dismissedId: Int?): Boolean =
        id != dismissedId && now.isBefore(sentAt.plus(LiveFollow.URGENT_FOR)) && !now.isBefore(sentAt.minus(LiveFollow.CLOCK_SKEW))
}

/** A place of My schedule at a time ("Dinner at Taco Row, 7:00 PM"; iOS `FollowPlace`). */
data class FollowPlace(val locationId: Int, val name: String, val start: Instant, val end: Instant)

/** The day the Live Update follows (iOS `FollowDay`). */
data class FollowDay(
    /** The day in the event's time zone, and its start. */
    val date: LocalDate,
    val dayStart: Instant,
    /** True: My schedule's items (and timed places); false: the event's own day. */
    val followsHearts: Boolean,
    /** The followed items of the day (by start, then id), including ones already over. */
    val items: List<ScheduleItem>,
    /** The event's own start when it falls on this day ("gates open"). */
    val gatesOpen: Instant?,
    /** When the Live Update should appear: 2 h before the first followed entry or the gates. */
    val startAt: Instant,
    /** When the last followed entry ends. */
    val endAt: Instant,
    /** My schedule's places with a time on this day (only while following "mine"). */
    val places: List<FollowPlace> = emptyList(),
    /** Every timed item of the day: what's on at the event when My schedule has nothing on. */
    val eventItems: List<ScheduleItem> = emptyList(),
) {
    val followsMine: Boolean get() = followsHearts
    val source: LiveSource get() = if (followsHearts) LiveSource.HEARTS else LiveSource.EVENT
}

/** What [LiveFollow.followDay] gets under the organizer's `live_activity.follows` (API-027; iOS `FollowInputs`). */
data class FollowInputs(
    val schedule: List<ScheduleItem>,
    val hearted: Set<Int>,
    val places: List<FollowPlace>,
)

/** iOS `LiveActivityPlanner.Decision`. */
sealed interface LiveDecision {
    /** Nothing to follow (or too far ahead for iOS's scheduled start). */
    data object Nothing : LiveDecision

    data object StartNow : LiveDecision

    /** Starts at this instant (Android: a wake-up then; the foreground posts at once anyway). */
    data class Schedule(val at: Instant) : LiveDecision
}

/** One row of the Live Update: a schedule item or a timed place of My schedule (iOS `Slot`). */
data class LiveSlot(
    val itemId: Int?,
    val name: String,
    val location: String,
    val locationId: Int?,
    val start: Instant,
    val end: Instant,
    val hearted: Boolean,
    val isPlace: Boolean = false,
)

/**
 * The Live Update's content (iOS `EventActivityAttributes.ContentState`): Now / Next / after (always filled
 * while anything is on, from the event when My schedule has nothing), the gates, the end-of-day summary,
 * the urgent block, the organizer's buttons and stale text (API-027), the empty hint and "Leave now".
 * No direction or distance from the user's position (iOS batch 7).
 */
data class LiveContent(
    val current: LiveSlot? = null,
    val next: LiveSlot? = null,
    val after: LiveSlot? = null,
    val walkMinutesToNext: Int? = null,
    val urgent: UrgentBlock? = null,
    /** The gates while they haven't opened yet today. */
    val opensAt: Instant? = null,
    val followsHearts: Boolean = false,
    val dayComplete: Boolean = false,
    val completedCount: Int? = null,
    /** "schedule" (mine) or "event" ("At the event"). */
    val nowSource: String? = null,
    val nextSource: String? = null,
    /** Next's start while My schedule has a gap ("Free until 3:00"). */
    val freeUntil: Instant? = null,
    /** [LiveFollow.EMPTY_HINT] while nothing is in My schedule. */
    val hint: String? = null,
    /** API-027 `live_activity.buttons` this app draws (Skip / Map). */
    val buttons: List<String> = LiveActivitySettings.DEFAULTS.liveButtons,
    /** API-027 `stale_text` (iOS shows it when the content is out of date; see [LiveFollow.staleAt]). */
    val staleText: String? = null,
    /** Running late for the next planned item (assistant spec §6). */
    val leaveBy: Instant? = null,
    val lateState: LateState? = null,
    val lateText: String? = null,
) {
    val nowIsFromEvent: Boolean get() = nowSource == "event"

    fun showsButton(name: String): Boolean = name in buttons
}

enum class LiveMode {
    /** Nothing has started yet: "Gates open 10:00 · First: X" or Next. */
    BEFORE,

    /** Something is on and/or coming. */
    ACTIVE,

    /** Nothing left today: the day's summary, for half an hour. */
    END_OF_DAY,

    /** [LiveState.urgent] replaces everything else. */
    URGENT,
}

/** A part of the day's progress bar: an entry or a gap between entries, in whole minutes (≥ 1). */
data class LiveSegment(val minutes: Int, val isItem: Boolean)

/** The notification drawn from [LiveContent] ([LiveFollow.render]); times are instants. */
data class LiveState(
    val mode: LiveMode,
    val source: LiveSource,
    val content: LiveContent,
    val title: String,
    val text: String,
    /** The header line: the empty hint, else the event's name. */
    val subText: String,
    /** The status-bar chip (`setShortCriticalText`): "7:30", "Leave" or "Urgent". */
    val chip: String?,
    /** What the chronometer counts down to (Next's start or the gates). */
    val countdownTo: Instant?,
    val segments: List<LiveSegment>,
    /** Minutes since the first segment started, 0…sum of segments. */
    val progress: Int,
    /** Minutes into the day's span at which each entry starts. */
    val points: List<Int>,
    /** The entry a tap acts on: current, else next. */
    val focus: LiveSlot?,
    /** Skip: Next's item when it is one and the organizer kept the button. */
    val skipItemId: Int?,
    /** Map: Next's (else Now's) place when the organizer kept the button. */
    val mapLocationId: Int?,
) {
    val urgent: UrgentBlock? get() = content.urgent.takeIf { mode == LiveMode.URGENT }
}

/** A reminder before an entry of My schedule (iOS `PlannedReminder`). */
data class PlannedReminder(
    /** "attnd.reminder.<event>.<item>" or ".p<place>". */
    val id: String,
    /** 0 for a place. */
    val itemId: Int,
    val fireAt: Instant,
    val title: String,
    val body: String,
    val locationId: Int? = null,
    /** The entry's start (Android: a reminder shown late is still useful until then). */
    val start: Instant,
) {
    /** The key stored once shown: the item id, or −location id for a place. */
    val shownKey: Int get() = locationId?.let { -it } ?: itemId
}

/**
 * The Live Update and reminders (shared spec §8; a port of iOS `LiveActivityPlanner` / `ReminderPlanner`
 * in `LiveFollowLogic.swift`, same rules and test cases). Pure; `live/` posts the notification and
 * schedules wake-ups from these values.
 */
object LiveFollow {

    /** The Live Update appears this long before the day's first followed entry or the gates. */
    val LEAD: Duration = Duration.ofHours(2)

    /** iOS starts scheduled activities only this far ahead. */
    val MAX_SCHEDULE_AHEAD: Duration = Duration.ofHours(36)

    /** The end-of-day summary stays this long. */
    val SUMMARY_LINGER: Duration = Duration.ofMinutes(30)

    val DEFAULT_STALE: Duration = Duration.ofHours(1)
    val STALE_GRACE: Duration = Duration.ofMinutes(1)
    val URGENT_FOR: Duration = Duration.ofHours(12)
    val CLOCK_SKEW: Duration = Duration.ofMinutes(10)

    /** A position older than this isn't used. */
    val MAX_POSITION_AGE: Duration = Duration.ofMinutes(30)

    /** Wake at least this often while the Live Update is up (progress bar and texts). */
    val REFRESH_EVERY: Duration = Duration.ofMinutes(15)

    /** Reminders: this long before an entry unless it has its own lead (0 = none). */
    const val REMINDER_LEAD_MINUTES = 15
    const val MAX_REMINDERS = 40
    const val REMINDER_ID_PREFIX = "attnd.reminder."

    /** The header while nothing is in My schedule (the Live Update starts on join). */
    const val EMPTY_HINT = "Tap ♥ on anything and it shows here"

    // ── The followed day ────────────────────────────────────────────────────

    /** My schedule's timed places, resolved against the bundle (unknown places dropped). */
    fun places(mySchedule: MySchedule, bundle: EventBundle): List<FollowPlace> =
        mySchedule.entries.filter { it.kind == ScheduleEntryKind.PLACE && it.at != null }.mapNotNull { entry ->
            val location = bundle.location(entry.id) ?: return@mapNotNull null
            val start = entry.at!!
            FollowPlace(location.id, location.name, start, start.plus(Duration.ofMinutes((entry.minutes ?: MySchedule.DEFAULT_MINUTES).toLong())))
        }

    /**
     * API-027 `live_activity.follows`: `my_schedule` today's rule; `everything` every item of the day (no
     * hearts or places); `location` only the items at that place (an unknown place → My schedule's).
     */
    fun inputs(settings: LiveActivitySettings?, bundle: EventBundle, hearted: Set<Int>, places: List<FollowPlace>): FollowInputs {
        val s = settings ?: LiveActivitySettings.DEFAULTS
        s.followedLocation(bundle)?.let { location ->
            return FollowInputs(bundle.schedule.filter { it.locationId == location }, emptySet(), emptyList())
        }
        if (s.follows == LiveActivitySettings.Follows.EVERYTHING) return FollowInputs(bundle.schedule, emptySet(), emptyList())
        return FollowInputs(bundle.schedule, hearted, places)
    }

    /**
     * The day to follow: today while one of its items (or timed places) hasn't ended, else the next day
     * with items. My schedule's items and places when one of them hasn't ended, else every item of the day. Items still running from an
     * earlier day count for today. Null: nothing left.
     */
    fun followDay(
        schedule: List<ScheduleItem>,
        hearted: Set<Int>,
        places: List<FollowPlace> = emptyList(),
        eventStart: Instant?,
        now: Instant,
        zone: ZoneId,
    ): FollowDay? {
        val today = now.atZone(zone).toLocalDate()
        val timed = schedule.filter { it.startTime != null }.sortedWith(compareBy({ it.startTime!!.toInstant() }, { it.id }))
        val days = sortedMapOf<LocalDate, MutableList<ScheduleItem>>()
        for (item in timed) {
            var day = item.startTime!!.toInstant().atZone(zone).toLocalDate()
            if (day < today && ScheduleGrouping.isHappeningNow(item, now)) day = today
            days.getOrPut(day) { mutableListOf() } += item
        }
        val placeDays = sortedMapOf<LocalDate, MutableList<FollowPlace>>()
        for (place in places.sortedWith(compareBy({ it.start }, { it.locationId }))) {
            var day = place.start.atZone(zone).toLocalDate()
            if (day < today && !place.start.isAfter(now) && place.end.isAfter(now)) day = today
            placeDays.getOrPut(day) { mutableListOf() } += place
        }
        for (day in (days.keys + placeDays.keys).toSortedSet()) {
            if (day < today) continue
            val all = days[day].orEmpty()
            val open = all.filter { !ScheduleGrouping.hasEnded(it, now) }
            val dayPlaces = placeDays[day].orEmpty()
            val openPlaces = dayPlaces.filter { it.end.isAfter(now) }
            if (open.isEmpty() && openPlaces.isEmpty()) continue
            val followsHearts = open.any { it.id in hearted } || openPlaces.isNotEmpty()
            val items = if (followsHearts) all.filter { it.id in hearted } else all
            val followedPlaces = if (followsHearts) dayPlaces else emptyList()
            val gates = eventStart?.takeIf { it.atZone(zone).toLocalDate() == day }
            val starts = items.map { it.startTime!!.toInstant() } + followedPlaces.map { it.start }
            val dayStart = day.atStartOfDay(zone).toInstant()
            val first = starts.minOrNull() ?: dayStart
            val startAt = minOf(first, gates ?: first).minus(LEAD)
            val ends = items.mapNotNull { TimeMatcher.effectiveEnd(it) } + followedPlaces.map { it.end }
            return FollowDay(
                date = day,
                dayStart = dayStart,
                followsHearts = followsHearts,
                items = items,
                gatesOpen = gates,
                startAt = startAt,
                endAt = ends.maxOrNull() ?: first,
                places = followedPlaces,
                eventItems = all,
            )
        }
        return null
    }

    fun followDay(inputs: FollowInputs, eventStart: Instant?, now: Instant, zone: ZoneId): FollowDay? =
        followDay(inputs.schedule, inputs.hearted, inputs.places, eventStart, now, zone)

    /** [forced]: the user asked to follow now, so the 2 h lead doesn't apply when the day is today. */
    fun decision(day: FollowDay?, now: Instant, zone: ZoneId, forced: Boolean = false): LiveDecision {
        if (day == null || !now.isBefore(day.endAt)) return LiveDecision.Nothing
        val isToday = day.date == now.atZone(zone).toLocalDate()
        if (!now.isBefore(day.startAt) || (forced && isToday)) return LiveDecision.StartNow
        if (Duration.between(now, day.startAt) > MAX_SCHEDULE_AHEAD) return LiveDecision.Nothing
        return LiveDecision.Schedule(day.startAt)
    }

    // ── Content ─────────────────────────────────────────────────────────────

    private data class Entry(val item: ScheduleItem?, val place: FollowPlace?, val start: Instant, val end: Instant, val locationId: Int?) {
        val itemId: Int? get() = item?.id
    }

    private val ENTRY_ORDER = Comparator<Entry> { a, b ->
        when {
            a.start != b.start -> a.start.compareTo(b.start)
            (a.item == null) != (b.item == null) -> if (a.item != null) -1 else 1
            else -> (a.itemId ?: a.locationId ?: 0).compareTo(b.itemId ?: b.locationId ?: 0)
        }
    }

    private fun itemEntry(item: ScheduleItem): Entry? {
        val start = item.startTime?.toInstant() ?: return null
        val end = TimeMatcher.effectiveEnd(item) ?: return null
        return Entry(item, null, start, end, item.locationId)
    }

    /** The day's followed entries (mine: items + places; else the day's items), by start. */
    private fun followedEntries(day: FollowDay): List<Entry> =
        (day.items.mapNotNull(::itemEntry) + day.places.map { Entry(null, it, it.start, it.end, it.locationId) }).sortedWith(ENTRY_ORDER)

    /**
     * The content for [day] at [now] (iOS `content`):
     * - current: My schedule's entry in progress (the one that started last; ties lowest id), else what's
     *   on at the event (`nowSource` "event"): the item in progress nearest to [user], else the earliest
     *   started;
     * - next / after: My schedule's next two entries not started yet (minus [skipped]), else the event's
     *   own; `freeUntil` = Next's start while My schedule has a gap;
     * - walk minutes to Next from [user] (≤ 30 min old by [userAt], within walking range);
     * - `opensAt` while the gates haven't opened; `dayComplete` once nothing is left.
     */
    fun content(
        day: FollowDay,
        locations: List<EventLocation>,
        hearted: Set<Int>,
        skipped: Set<Int>,
        user: GeoPoint?,
        userAt: Instant? = null,
        urgent: UrgentBlock?,
        now: Instant,
    ): LiveContent {
        val byId = locations.associateBy { it.id }
        val position = user?.takeIf { userAt == null || Duration.between(userAt, now) <= MAX_POSITION_AGE }
        val mineEntries = if (day.followsMine) followedEntries(day) else emptyList()
        val eventEntries = day.eventItems.ifEmpty { day.items }.mapNotNull(::itemEntry).sortedWith(ENTRY_ORDER)
        fun happening(e: Entry) = !e.start.isAfter(now) && now.isBefore(e.end)
        fun distance(e: Entry): Double {
            val c = e.locationId?.let { byId[it]?.coordinate } ?: return Double.POSITIVE_INFINITY
            return position?.let { Geo.distanceMeters(it, c) } ?: Double.POSITIVE_INFINITY
        }

        var current = mineEntries.filter(::happening)
            .minWithOrNull(compareByDescending<Entry> { it.start }.thenBy { it.itemId ?: 0 })
        var nowSource = current?.let { "schedule" }
        if (current == null) {
            current = eventEntries.filter(::happening)
                .minWithOrNull(compareBy<Entry> { distance(it) }.thenBy { it.start }.thenBy { it.itemId ?: 0 })
            if (current != null) nowSource = "event"
        }
        val currentId = current?.itemId
        fun upcoming(list: List<Entry>) = list.filter { e ->
            val id = e.itemId
            e.start.isAfter(now) && (id == null || (id !in skipped && id != currentId))
        }
        val mineNext = upcoming(mineEntries)
        val useMine = mineNext.isNotEmpty()
        val nextList = if (useMine) mineNext else upcoming(eventEntries)
        val next = nextList.firstOrNull()
        val after = nextList.getOrNull(1)
        val freeUntil = if (useMine && mineEntries.none(::happening)) next?.start else null

        fun slot(entry: Entry?): LiveSlot? {
            entry ?: return null
            entry.place?.let { return LiveSlot(null, it.name, it.name, it.locationId, it.start, it.end, hearted = true, isPlace = true) }
            val item = entry.item ?: return null
            val location = item.locationId?.let { byId[it] }
            return LiveSlot(
                itemId = item.id,
                name = item.name,
                location = location?.name ?: item.locationName ?: ScheduleGrouping.GENERAL_TITLE,
                locationId = location?.id ?: item.locationId,
                start = entry.start,
                end = entry.end,
                hearted = item.id in hearted,
            )
        }

        val target = next?.locationId?.let { byId[it]?.coordinate }
        val walk = if (position != null && target != null) {
            val meters = Geo.distanceMeters(position, target)
            if (Walking.isNearby(meters)) Walking.minutes(Walking.estimateSeconds(meters)).toInt() else null
        } else null
        val complete = current == null && next == null
        val completed = day.items.count { ScheduleGrouping.hasEnded(it, now) } + day.places.count { !it.end.isAfter(now) }
        return LiveContent(
            current = slot(current),
            next = slot(next),
            after = slot(after),
            walkMinutesToNext = walk,
            urgent = urgent,
            opensAt = day.gatesOpen?.takeIf { it.isAfter(now) },
            followsHearts = day.followsHearts,
            dayComplete = complete,
            completedCount = if (complete) completed else null,
            nowSource = nowSource,
            nextSource = next?.let { if (useMine) "schedule" else "event" },
            freeUntil = freeUntil,
        )
    }

    /**
     * The organizer's buttons and stale text (API-027), the empty hint (nothing in My schedule)
     * and "Leave now" for My schedule's next item ([late], only while following My schedule and when it is Next).
     */
    fun decorate(content: LiveContent, day: FollowDay?, settings: LiveActivitySettings?, hasMine: Boolean, late: RunningLate?): LiveContent {
        val s = settings ?: LiveActivitySettings.DEFAULTS
        var result = content.copy(
            buttons = s.liveButtons,
            staleText = s.staleText,
            hint = if (!hasMine && !content.dayComplete) EMPTY_HINT else null,
        )
        if (late != null && day?.followsHearts == true && content.next?.itemId == late.itemId) {
            result = result.copy(leaveBy = late.leaveBy, lateState = late.state, lateText = late.text, walkMinutesToNext = late.walkMinutes)
        }
        return result
    }

    /** When the content goes out of date: just after the next change (current ends, next starts, gates open), else an hour on. */
    fun staleAt(content: LiveContent, now: Instant): Instant {
        val first = listOfNotNull(content.current?.end, content.next?.start, content.opensAt).filter { it.isAfter(now) }.minOrNull()
            ?: return now.plus(DEFAULT_STALE)
        return first.plus(STALE_GRACE)
    }

    /**
     * The urgent block: the bundle's newest urgent announcement sent within 12 h and not dismissed, else
     * the pushed one (a push before the bundle) while it is within 12 h and not dismissed.
     */
    fun urgent(notifications: List<EventNotification>, dismissed: Set<Int>, pushed: UrgentBlock?, now: Instant): UrgentBlock? {
        val newest = notifications.asSequence()
            .filter { it.isUrgent && it.sendTime != null && it.id !in dismissed }
            .map { UrgentBlock(it.id, it.title, it.body, it.sendTime!!.toInstant()) }
            .filter { it.isActive(now, null) }
            .maxByOrNull { it.sentAt }
        return newest ?: pushed?.takeIf { it.id !in dismissed && it.isActive(now, null) }
    }

    /**
     * The day that ended within the last [SUMMARY_LINGER] today (the end-of-day summary), when [current]
     * (the day followed now) isn't today any more.
     */
    fun endedToday(inputs: FollowInputs, eventStart: Instant?, now: Instant, zone: ZoneId, current: FollowDay?): FollowDay? {
        val today = now.atZone(zone).toLocalDate()
        if (current?.date == today) return null
        return followDay(inputs, eventStart, now.minus(SUMMARY_LINGER), zone)
            ?.takeIf { it.date == today && !it.endAt.isAfter(now) && now.isBefore(it.endAt.plus(SUMMARY_LINGER)) }
    }

    // ── The notification ────────────────────────────────────────────────────

    /**
     * The Android notification for [content] (iOS's Lock Screen view in a promoted ongoing notification):
     * urgent → its title and body; the summary → "That's a wrap for today"; running late → "Leave now for
     * X"; before the gates → "Gates open 10:00 · First: X"; Now → "Now: X · Place" / "At the event: X ·
     * Place" with "Next 8:00 PM: Y · Place" or "Free until 7:00 · Next: Y at Place"; else "Y at 7:00 PM".
     * Absolute times only: the chronometer counts down to Next (or the gates).
     */
    fun render(content: LiveContent, day: FollowDay?, eventName: String, zone: ZoneId, now: Instant, locale: Locale = Locale.getDefault()): LiveState {
        val clock = DateTimeFormatter.ofPattern("h:mm", locale)
        val longClock = DateTimeFormatter.ofPattern("h:mm a", locale)
        fun short(t: Instant) = t.atZone(zone).format(clock)
        fun long(t: Instant) = t.atZone(zone).format(longClock)
        val source = day?.source ?: LiveSource.EVENT
        val spans = day?.let { d -> if (d.followsMine) followedEntries(d) else d.items.mapNotNull(::itemEntry) }.orEmpty().map { it.start to it.end }
        val (segments, progress, points) = timeline(spans, now)
        val current = content.current
        val next = content.next
        val urgent = content.urgent
        val gates = content.opensAt

        val mode: LiveMode
        val title: String
        val text: String
        val chip: String?
        val countdown: Instant?
        when {
            urgent != null -> {
                mode = LiveMode.URGENT
                title = urgent.title.ifBlank { "Urgent update" }
                text = urgent.body
                chip = "Urgent"
                countdown = null
            }
            content.dayComplete -> {
                mode = LiveMode.END_OF_DAY
                val count = content.completedCount ?: 0
                val items = if (count == 1) "item" else "items"
                title = "That's a wrap for today"
                text = when (source) {
                    LiveSource.HEARTS -> "$count $items in your schedule today"
                    LiveSource.EVENT -> eventName
                }
                chip = null
                countdown = null
            }
            content.lateState != null && next != null -> {
                mode = LiveMode.ACTIVE
                title = content.lateText ?: "Leave now for ${next.name}"
                text = "${long(next.start)} · ${next.location}"
                chip = if (content.lateState == LateState.LEAVE_NOW) "Leave" else short(next.start)
                countdown = next.start
            }
            gates != null && current == null -> {
                mode = LiveMode.BEFORE
                title = "Gates open ${short(gates)}" + (next?.let { " · First: ${it.name}" } ?: "")
                text = next?.let { "${it.location} · ${long(it.start)}" } ?: eventName
                chip = short(gates)
                countdown = gates
            }
            current != null -> {
                mode = LiveMode.ACTIVE
                title = (if (content.nowIsFromEvent) "At the event: " else "Now: ") + current.name + " · " + current.location
                val walk = content.walkMinutesToNext?.takeIf { it > 0 }?.let { " · $it min walk" }.orEmpty()
                val free = content.freeUntil
                text = when {
                    next == null -> "Ends ${long(current.end)}"
                    free != null -> "Free until ${short(free)} · Next: ${next.name} at ${next.location}$walk"
                    else -> "Next ${long(next.start)}: ${next.name}" + (if (next.location != current.location) " · ${next.location}" else "") + walk
                }
                chip = short(next?.start ?: current.end)
                countdown = next?.start
            }
            next != null -> {
                mode = LiveMode.BEFORE
                title = "${next.name} at ${long(next.start)}"
                text = buildString {
                    append(next.location)
                    content.walkMinutesToNext?.let { append(if (it == 0) " · ${Walking.YOURE_HERE}" else " · $it min walk") }
                    content.after?.let { append(" · then ${it.name}") }
                }
                chip = short(next.start)
                countdown = next.start
            }
            else -> {
                mode = LiveMode.END_OF_DAY
                title = eventName
                text = ""
                chip = null
                countdown = null
            }
        }
        val drawsButtons = mode != LiveMode.URGENT
        return LiveState(
            mode = mode,
            source = source,
            content = content,
            title = title,
            text = text,
            subText = content.hint ?: eventName,
            chip = chip,
            countdownTo = countdown,
            segments = if (drawsButtons) segments else emptyList(),
            progress = progress,
            points = points,
            focus = current ?: next,
            skipItemId = next?.itemId?.takeIf { drawsButtons && content.showsButton("skip") },
            mapLocationId = (next?.locationId ?: current?.locationId)?.takeIf { drawsButtons && content.showsButton("map") },
        )
    }

    /**
     * The day's progress bar: from the first entry's start to the last one's end, one segment per entry
     * (overlapping ones merge) and per gap, in whole minutes (≥ 1); progress = minutes elapsed, clamped;
     * points = each entry's start offset (distinct, > 0, < total).
     */
    fun timeline(spans: List<Pair<Instant, Instant>>, now: Instant): Triple<List<LiveSegment>, Int, List<Int>> {
        if (spans.isEmpty()) return Triple(emptyList(), 0, emptyList())
        val sorted = spans.sortedBy { it.first }
        val spanStart = sorted.first().first
        val merged = mutableListOf<Pair<Instant, Instant>>()
        sorted.forEach { (s, e) ->
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
        val points = sorted.map { minutes(spanStart, it.first) }.filter { it in 1 until total }.distinct().sorted()
        return Triple(segments, progress, points)
    }

    /**
     * When to wake next: the day's start and end (and the summary's end), every entry's start and end
     * (mine and the event's, since Now falls back to the event), the gates, the urgent block's expiry and
     * [extra] times (reminders, "leave by"), and at least every [REFRESH_EVERY] while [showing]. Null:
     * nothing left.
     */
    fun nextWake(day: FollowDay?, now: Instant, showing: Boolean, urgent: UrgentBlock? = null, extra: List<Instant> = emptyList()): Instant? {
        val candidates = mutableListOf<Instant>()
        if (day != null) {
            candidates += day.startAt
            candidates += day.endAt
            candidates += day.endAt.plus(SUMMARY_LINGER)
            day.gatesOpen?.let { candidates += it }
            (day.items + day.eventItems).forEach { item ->
                item.startTime?.toInstant()?.let { candidates += it }
                TimeMatcher.effectiveEnd(item)?.let { candidates += it }
            }
            day.places.forEach { candidates += it.start; candidates += it.end }
        }
        urgent?.let { candidates += it.sentAt.plus(URGENT_FOR) }
        candidates += extra
        if (showing) candidates += now.plus(REFRESH_EVERY)
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    // ── Reminders ───────────────────────────────────────────────────────────

    /**
     * One reminder per schedule item in [hearted] whose reminder time is still ahead, `leadMinutes[id]`
     * before (default 15, 0 = none), and one per timed place entry of [places]: "Prairie Lights in 15
     * min · 8 min walk" (walk from [user] when within walking range and 20 m or more away), body "Main
     * Stage · 7:30 PM"; places "Taco Row in 15 min · 3 min walk", "In your schedule · 7:00 PM". Soonest
     * first, at most [MAX_REMINDERS].
     */
    fun reminders(
        eventId: Int,
        schedule: List<ScheduleItem>,
        hearted: Set<Int>,
        locations: List<EventLocation>,
        user: GeoPoint?,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
        leadMinutes: Map<Int, Int> = emptyMap(),
        places: List<ScheduleEntry> = emptyList(),
    ): List<PlannedReminder> {
        val byId = locations.associateBy { it.id }
        val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
        fun time(t: Instant) = t.atZone(zone).format(clock)
        fun walk(location: EventLocation?): String? {
            val from = user ?: return null
            val to = location?.coordinate ?: return null
            val meters = Geo.distanceMeters(from, to)
            if (meters < Geo.HERE_METERS || !Walking.isNearby(meters)) return null
            return Walking.walkText(Walking.estimateSeconds(meters))
        }
        val items = schedule.filter { it.id in hearted }.mapNotNull { item ->
            val minutes = leadMinutes[item.id] ?: REMINDER_LEAD_MINUTES
            val start = item.startTime?.toInstant() ?: return@mapNotNull null
            if (minutes <= 0) return@mapNotNull null
            val fire = start.minus(Duration.ofMinutes(minutes.toLong()))
            if (!fire.isAfter(now)) return@mapNotNull null
            val location = item.locationId?.let { byId[it] }
            val place = location?.name ?: item.locationName
            PlannedReminder(
                id = "$REMINDER_ID_PREFIX$eventId.${item.id}",
                itemId = item.id,
                fireAt = fire,
                title = "${item.name} in $minutes min" + (walk(location)?.let { " · $it" } ?: ""),
                body = place?.let { "$it · ${time(start)}" } ?: time(start),
                start = start,
            )
        }
        val timedPlaces = places.mapNotNull { entry ->
            val location = byId[entry.id] ?: return@mapNotNull null
            val start = entry.at ?: return@mapNotNull null
            val minutes = entry.reminderMinutes
            if (entry.kind != ScheduleEntryKind.PLACE || minutes <= 0) return@mapNotNull null
            val fire = start.minus(Duration.ofMinutes(minutes.toLong()))
            if (!fire.isAfter(now)) return@mapNotNull null
            PlannedReminder(
                id = "$REMINDER_ID_PREFIX$eventId.p${location.id}",
                itemId = 0,
                fireAt = fire,
                title = "${location.name} in $minutes min" + (walk(location)?.let { " · $it" } ?: ""),
                body = "In your schedule · ${time(start)}",
                locationId = location.id,
                start = start,
            )
        }
        return (items + timedPlaces)
            .sortedWith(compareBy({ it.fireAt }, { it.itemId }, { it.locationId ?: 0 }))
            .take(MAX_REMINDERS)
    }
}
