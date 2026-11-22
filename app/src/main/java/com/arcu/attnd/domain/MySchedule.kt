package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil

/**
 * "My schedule" (a 1:1 port of iOS `MySchedule.swift`, owner Nov 21, 2026): one personal list per event
 * that replaces likes (hearts) and the stored plan.
 *
 * - Entries: schedule items (their time is the item's), places at a chosen time ("Dinner at Taco Row,
 *   7:00 PM", default 45 min) and places saved without a time ("Saved places").
 * - Each entry has a reminder: null = the default (15 min before), 0 = off, else 5…60 min.
 * - Storage keys, prefs and the API-009 metric names keep "heart": the hearts mirror the entries
 *   ([hearts]: item entries = hearted schedule items, place entries = hearted locations), so everything
 *   that reads hearts follows My schedule.
 * - Adding or removing an item or place returns the API-009 metric only when membership really changes
 *   (never for a time / reminder change, a restore, the migration or a rejoin).
 * - Migration ([migrate], once per event): hearted items become item entries, hearted places saved
 *   places; an older build's stored plan is absorbed once ([absorb], `source: planner`).
 * - One list (owner, Nov 22, 2026; Attnd-Web `docs/specs/one_list_spec.md`): My schedule is the only
 *   stored list. Each entry knows who added it ([ScheduleEntry.source]: you / planner / assistant /
 *   shared) and why ([ScheduleEntry.reason]). The planner is a function: its plan is diffed against My
 *   schedule into a [ScheduleProposal] ([proposal]: adds and removes with reasons; it only removes its
 *   own entries unless asked), previewed, applied ([apply]) and kept on one [ScheduleHistory] (≤ 20
 *   snapshots, hearts included). The [Plan] the assistant and the Live Update read is derived from My
 *   schedule ([dayPlan]), never stored. [SchedulePreferences] keeps what the planner was told.
 */
class MySchedule(entries: List<ScheduleEntry> = emptyList()) {

    /** Unique by (kind, id), first kept, at most [MAX_ENTRIES]. */
    var entries: List<ScheduleEntry> = entries.distinctBy { it.key }.take(MAX_ENTRIES)
        private set

    val isEmpty: Boolean get() = entries.isEmpty()
    val itemIds: Set<Int> get() = entries.filter { it.kind == ScheduleEntryKind.ITEM }.map { it.id }.toSet()
    val placeIds: Set<Int> get() = entries.filter { it.kind == ScheduleEntryKind.PLACE }.map { it.id }.toSet()

    /** Places saved without a time. */
    val savedPlaceIds: List<Int> get() = entries.filter { it.kind == ScheduleEntryKind.PLACE && it.at == null }.map { it.id }

    /** The hearts that mirror the entries (what search, filters, the planner and the Live Update read). */
    val hearts: Hearts get() = Hearts(locations = placeIds, scheduleItems = itemIds)

    fun copy(): MySchedule = MySchedule(entries)

    fun entry(kind: ScheduleEntryKind, id: Int): ScheduleEntry? = entries.firstOrNull { it.kind == kind && it.id == id }

    fun entry(target: ScheduleTarget): ScheduleEntry? = target.entryKind?.let { entry(it, target.id) }

    operator fun contains(target: ScheduleTarget): Boolean = entry(target) != null

    private fun indexOf(kind: ScheduleEntryKind, id: Int): Int = entries.indexOfFirst { it.kind == kind && it.id == id }

    private fun replace(index: Int, entry: ScheduleEntry) {
        entries = entries.toMutableList().also { it[index] = entry }
    }

    // ── Changes ─────────────────────────────────────────────────────────────

    /**
     * Adds an item, or a place (at a time, or saved without one). A place already in My schedule gets
     * the new time (no metric); an item already there changes nothing (null).
     */
    fun add(target: ScheduleTarget, at: Instant? = null, minutes: Int? = null): MyScheduleChange? {
        val kind = target.entryKind ?: return null
        val index = indexOf(kind, target.id)
        if (index >= 0) {
            if (kind != ScheduleEntryKind.PLACE || (at == null && minutes == null)) return null
            var entry = entries[index]
            if (at != null) entry = entry.copy(at = at)
            if (minutes != null) entry = entry.copy(minutes = minutes)
            replace(index, entry)
            return MyScheduleChange(null, target)
        }
        if (entries.size >= MAX_ENTRIES) return null
        val place = kind == ScheduleEntryKind.PLACE
        entries = entries + ScheduleEntry(kind, target.id, at = if (place) at else null, minutes = if (place) minutes else null)
        return MyScheduleChange(if (place) UsageMetric.LOCATION_HEART else UsageMetric.SCHEDULE_ITEM_HEART, target)
    }

    fun remove(target: ScheduleTarget): MyScheduleChange? {
        val kind = target.entryKind ?: return null
        val index = indexOf(kind, target.id)
        if (index < 0) return null
        entries = entries.filterIndexed { i, _ -> i != index }
        return MyScheduleChange(if (kind == ScheduleEntryKind.PLACE) UsageMetric.LOCATION_UNHEART else UsageMetric.SCHEDULE_ITEM_UNHEART, target)
    }

    /** A place entry's time (null = "just save it"). Items have the organizer's time: null. */
    fun move(target: ScheduleTarget, at: Instant?): MyScheduleChange? {
        if (target.entryKind != ScheduleEntryKind.PLACE) return null
        val index = indexOf(ScheduleEntryKind.PLACE, target.id)
        if (index < 0 || entries[index].at == at) return null
        replace(index, entries[index].copy(at = at))
        return MyScheduleChange(null, target)
    }

    /** The reminder of an entry (minutes before; 0 = off; null = the default). */
    fun setReminder(target: ScheduleTarget, minutes: Int?): MyScheduleChange? {
        val kind = target.entryKind ?: return null
        val index = indexOf(kind, target.id)
        if (index < 0) return null
        val value = minutes?.let { if (it in REMINDER_CHOICES) it else nearestReminder(it) }
        if (entries[index].reminder == value) return null
        replace(index, entries[index].copy(reminder = value))
        return MyScheduleChange(null, target)
    }

    // ── Organizer changes ───────────────────────────────────────────────────

    /**
     * Items whose fingerprint no longer matches (moved to a new start, otherwise changed) or that left
     * the schedule (cancelled). Entries without a fingerprint (migrated) are taken as they are.
     */
    fun organizerChanges(bundle: EventBundle, names: Map<Int, String> = emptyMap()): List<OrganizerChange> =
        entries.mapNotNull { entry ->
            if (entry.kind != ScheduleEntryKind.ITEM) return@mapNotNull null
            val print = entry.fingerprint ?: return@mapNotNull null
            val item = bundle.scheduleItem(entry.id)
                ?: return@mapNotNull OrganizerChange(
                    entry.id,
                    names[entry.id] ?: print.split('|').firstOrNull()?.takeIf { it.isNotEmpty() } ?: "An item",
                    OrganizerChange.Kind.Cancelled,
                )
            if (Planner.fingerprint(item) == print) return@mapNotNull null
            val oldStart = print.split('|').getOrNull(1)
            val start = item.startTime?.toInstant()
            if (start != null && oldStart != start.toString()) {
                OrganizerChange(entry.id, item.name, OrganizerChange.Kind.Moved(start))
            } else {
                OrganizerChange(entry.id, item.name, OrganizerChange.Kind.Changed)
            }
        }

    /** "Keep": the item as it is now (its new fingerprint); a cancelled one leaves My schedule. */
    fun keepChange(itemId: Int, bundle: EventBundle) {
        val index = indexOf(ScheduleEntryKind.ITEM, itemId)
        if (index < 0) return
        val item = bundle.scheduleItem(itemId)
        if (item != null) replace(index, entries[index].copy(fingerprint = Planner.fingerprint(item)))
        else entries = entries.filterIndexed { i, _ -> i != index }
    }

    /** Fingerprints for item entries that have none (after the migration or an add without the bundle). */
    fun fillFingerprints(bundle: EventBundle): Boolean {
        var changed = false
        entries = entries.map { entry ->
            if (entry.kind != ScheduleEntryKind.ITEM || entry.fingerprint != null) return@map entry
            val item = bundle.scheduleItem(entry.id) ?: return@map entry
            changed = true
            entry.copy(fingerprint = Planner.fingerprint(item))
        }
        return changed
    }

    // ── Views ───────────────────────────────────────────────────────────────

    /** Timed rows: item entries with a start, and places with a time; by start (then name, id). */
    fun rows(bundle: EventBundle, now: Instant): List<MyScheduleRow> =
        entries.mapNotNull { row(it, bundle, now) }.sortedWith(ROW_ORDER)

    fun row(entry: ScheduleEntry, bundle: EventBundle, now: Instant): MyScheduleRow? = when (entry.kind) {
        ScheduleEntryKind.ITEM -> {
            val item = bundle.scheduleItem(entry.id)
            val start = item?.startTime?.toInstant()
            if (item == null || start == null) null else {
                val end = TimeMatcher.effectiveEnd(item) ?: start.plus(ScheduleGrouping.DEFAULT_ITEM_DURATION)
                val place = bundle.location(item.locationId)
                MyScheduleRow(entry, start, end, item.name, place?.name ?: item.locationName, place?.id, !end.isAfter(now))
            }
        }
        ScheduleEntryKind.PLACE -> {
            val location = bundle.location(entry.id)
            val start = entry.at
            if (location == null || start == null) null else {
                val end = start.plus(Duration.ofMinutes((entry.minutes ?: DEFAULT_MINUTES).toLong()))
                MyScheduleRow(entry, start, end, location.name, location.name, location.id, !end.isAfter(now))
            }
        }
    }

    /** The Schedule tab's section: upcoming (and in progress) rows first, ended ones at the end. */
    fun sectionRows(bundle: EventBundle, now: Instant): List<MyScheduleRow> {
        val all = rows(bundle, now)
        return all.filter { !it.ended } + all.filter { it.ended }
    }

    /** Home's "Up next in my schedule": the next rows that haven't ended (in progress first). */
    fun upNext(bundle: EventBundle, now: Instant, limit: Int = 5): List<MyScheduleRow> =
        rows(bundle, now).filter { !it.ended }.take(maxOf(1, limit))

    /** Rows of one event day (event zone). */
    fun rows(day: LocalDate, bundle: EventBundle, now: Instant): List<MyScheduleRow> {
        val zone = bundle.event.zone
        return rows(bundle, now).filter { it.start.atZone(zone).toLocalDate() == day }
    }

    /** Timed rows overlapping `start…end` (the clash check before an add), not [excluding]. */
    fun clashes(start: Instant, end: Instant, bundle: EventBundle, now: Instant, excluding: ScheduleTarget? = null): List<MyScheduleRow> =
        rows(bundle, now).filter { row ->
            !row.ended && row.target != excluding && row.start.isBefore(end) && start.isBefore(row.end)
        }

    /** When a target would be in My schedule: an item's own time, a place's chosen time. */
    fun span(target: ScheduleTarget, bundle: EventBundle, at: Instant? = null, minutes: Int? = null): Pair<Instant, Instant>? =
        when (target.entryKind) {
            ScheduleEntryKind.ITEM -> {
                val item = bundle.scheduleItem(target.id)
                val start = item?.startTime?.toInstant()
                if (item == null || start == null) null
                else start to (TimeMatcher.effectiveEnd(item) ?: start.plus(ScheduleGrouping.DEFAULT_ITEM_DURATION))
            }
            ScheduleEntryKind.PLACE -> {
                val start = at ?: entry(target)?.at
                start?.let { it to it.plus(Duration.ofMinutes((minutes ?: entry(target)?.minutes ?: DEFAULT_MINUTES).toLong())) }
            }
            null -> null
        }

    /**
     * Times offered by the place sheet's "When?": from now + the walk + 5 min, rounded up to the quarter
     * hour, every 30 min until midnight (event zone), ≤ 8. The suggested one is the first that overlaps
     * nothing in My schedule (else the first).
     */
    fun suggestedTimes(placeId: Int, bundle: EventBundle, now: Instant, walkMinutes: Int?): SuggestedTimes {
        val zone = bundle.event.zone
        val earliest = now.plusSeconds(((walkMinutes ?: 0) + 5) * 60L)
        val quarter = 15 * 60.0
        var start = Instant.ofEpochSecond((ceil(earliest.epochSecond / quarter) * quarter).toLong())
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val times = mutableListOf<Instant>()
        while (start.isBefore(midnight) && times.size < 8) {
            times += start
            start = start.plus(Duration.ofMinutes(30))
        }
        val length = Duration.ofMinutes(DEFAULT_MINUTES.toLong())
        val free = times.firstOrNull { clashes(it, it.plus(length), bundle, now, excluding = ScheduleTarget.place(placeId)).isEmpty() }
        return SuggestedTimes(times, free ?: times.firstOrNull())
    }

    /**
     * The reminder of every timed entry still ahead whose reminder isn't off: an item or a timed place at
     * its start − the entry's lead (default 15 min), by fire time. The texts, the walk and posting them
     * are the Live Update's (phase 3b / 8).
     */
    fun reminders(bundle: EventBundle, now: Instant): List<EntryReminder> =
        rows(bundle, now).mapNotNull { row ->
            val lead = row.entry.reminderMinutes
            if (lead <= 0 || !row.start.isAfter(now)) null
            else EntryReminder(row.target, row.start, row.start.minus(Duration.ofMinutes(lead.toLong())))
        }.sortedBy { it.fireAt }

    /**
     * The planner's input (one list §2): a timed food place in My schedule during lunch (11:30–13:30) or
     * dinner (17:30–19:30) of that day covers that meal break.
     */
    fun mealsCovered(bundle: EventBundle): Set<MealSlot> {
        val zone = bundle.event.zone
        val slots = mutableSetOf<MealSlot>()
        for (entry in entries) {
            if (entry.kind != ScheduleEntryKind.PLACE) continue
            val at = entry.at ?: continue
            val location = bundle.location(entry.id) ?: continue
            if (!isFood(location)) continue
            val kind = mealKind(at, zone) ?: continue
            slots += MealSlot(at.atZone(zone).toLocalDate(), kind)
        }
        return slots
    }

    // ── One list: proposals (the planner proposes edits to My schedule) ─────

    /**
     * One list §6: the stored plan of an older build becomes entries: every planned item not in My
     * schedule (`source: planner`, the plan's reason), each meal break with a food place not in it (a timed
     * place, "Lunch break" / "Dinner break"); a saved place without a time gets the break's time.
     * Idempotent: a second run finds nothing. True when something changed.
     */
    fun absorb(legacy: Plan): Boolean {
        var changed = false
        for (item in legacy.items) {
            if (entry(ScheduleEntryKind.ITEM, item.itemId) != null || entries.size >= MAX_ENTRIES) continue
            entries = entries + ScheduleEntry(ScheduleEntryKind.ITEM, item.itemId, source = ScheduleEntrySource.PLANNER, reason = ScheduleEntry.cleanReason(item.reason))
            changed = true
        }
        for (day in legacy.days) for (meal in day.entries.filterIsInstance<MealBreak>()) {
            val locationId = meal.locationId ?: continue
            val index = indexOf(ScheduleEntryKind.PLACE, locationId)
            if (index >= 0) {
                if (entries[index].at != null) continue
                replace(index, entries[index].copy(at = meal.start, minutes = mealMinutes(meal)))
                changed = true
            } else if (entries.size < MAX_ENTRIES) {
                entries = entries + ScheduleEntry(
                    ScheduleEntryKind.PLACE, locationId, at = meal.start, minutes = mealMinutes(meal),
                    source = ScheduleEntrySource.PLANNER, reason = meal.kind.label,
                )
                changed = true
            }
        }
        return changed
    }

    /**
     * One list §2: a plan (the planner's result, an edit's re-solve, a version, a sequence, a shared plan)
     * as edits to My schedule. Adds: its items not in My schedule (with the planner's reason) and its meal
     * breaks with a place not there at that time. Removes: on the plan's days, entries the planner added
     * that the plan no longer has, and anything in [explicit] (the request asked: "drop the BBQ"); never
     * what the person added unasked. Null plan: nothing.
     */
    fun proposal(plan: Plan?, bundle: EventBundle, explicit: Set<Int> = emptySet()): ScheduleProposal {
        plan ?: return ScheduleProposal()
        val zone = bundle.event.zone
        val days = plan.days.map { it.date }.toSet()
        val planned = plan.itemIds.toSet()
        val adds = mutableListOf<ScheduleProposal.Add>()
        val removes = mutableListOf<ScheduleProposal.Remove>()
        for (item in plan.items) {
            if (entry(ScheduleEntryKind.ITEM, item.itemId) == null && adds.none { it.target == ScheduleTarget.item(item.itemId) }) {
                adds += ScheduleProposal.Add(ScheduleTarget.item(item.itemId), reason = ScheduleEntry.cleanReason(item.reason))
            }
        }
        val meals = plan.days.flatMap { day -> day.entries.filterIsInstance<MealBreak>() }
        for (meal in meals) {
            val locationId = meal.locationId ?: continue
            // A place is in My schedule once: a meal at a place that already has a time keeps that time.
            if (entry(ScheduleEntryKind.PLACE, locationId)?.at != null) continue
            if (adds.any { it.target == ScheduleTarget.place(locationId) }) continue
            adds += ScheduleProposal.Add(ScheduleTarget.place(locationId), at = meal.start, minutes = mealMinutes(meal), reason = meal.kind.label, meal = meal.kind)
        }
        for (entry in entries) when (entry.kind) {
            ScheduleEntryKind.ITEM -> {
                if (entry.id in planned) continue
                if (entry.id in explicit) {
                    removes += ScheduleProposal.Remove(entry.target)
                } else if (entry.source == ScheduleEntrySource.PLANNER) {
                    val start = bundle.scheduleItem(entry.id)?.startTime?.toInstant() ?: continue
                    if (start.atZone(zone).toLocalDate() in days) removes += ScheduleProposal.Remove(entry.target)
                }
            }
            ScheduleEntryKind.PLACE -> {
                if (entry.source != ScheduleEntrySource.PLANNER) continue
                val at = entry.at ?: continue
                if (at.atZone(zone).toLocalDate() !in days || meals.any { it.locationId == entry.id }) continue
                removes += ScheduleProposal.Remove(entry.target, meal = mealKind(at, zone))
            }
        }
        return ScheduleProposal(adds, removes, if (plan.isEmpty) "" else Planner.summary(plan, zone))
    }

    /**
     * Applies a proposal: adds get [source] and the proposal's reason (an entry already there keeps who
     * added it; a place gets the new time), removes leave. Returns the API-009 changes.
     */
    fun apply(proposal: ScheduleProposal, source: ScheduleEntrySource = ScheduleEntrySource.PLANNER): List<MyScheduleChange> {
        val changes = mutableListOf<MyScheduleChange>()
        for (out in proposal.removes) remove(out.target)?.let(changes::add)
        for (new in proposal.adds) {
            val existed = new.target in this
            val change = add(new.target, new.at, new.minutes) ?: continue
            changes += change
            if (!existed) mark(new.target, source, new.reason)
        }
        return changes
    }

    /** Sets who added [target] and why (right after an add). */
    fun mark(target: ScheduleTarget, source: ScheduleEntrySource, reason: String?) {
        val kind = target.entryKind ?: return
        val index = indexOf(kind, target.id)
        if (index >= 0) replace(index, entries[index].copy(source = source, reason = ScheduleEntry.cleanReason(reason)))
    }

    // ── One list: the derived day view (never stored) ───────────────────────

    /**
     * The [Plan] the assistant (the timeline, proposals' previews, plan edits), the Live Update, live hints,
     * WhyNot and sharing read: My schedule's items per event day, laid out by the planner's rules
     * ([Planner.dayView]: walks, swap alternatives), its timed food places at meal times as meal breaks, the
     * entries' own reasons. Null without a timed item.
     */
    fun dayPlan(bundle: EventBundle, preferences: SchedulePreferences = SchedulePreferences(), checkedOff: Set<Int> = emptySet()): Plan? {
        val zone = bundle.event.zone
        val itemsByDay = sortedMapOf<LocalDate, MutableList<Int>>()
        val spans = HashMap<LocalDate, Pair<Instant, Instant>>()
        fun widen(day: LocalDate, start: Instant, end: Instant) {
            val old = spans[day]
            spans[day] = minOf(old?.first ?: start, start) to maxOf(old?.second ?: end, end)
        }
        for (entry in entries) {
            if (entry.kind != ScheduleEntryKind.ITEM) continue
            val item = bundle.scheduleItem(entry.id) ?: continue
            val start = item.startTime?.toInstant() ?: continue
            val day = start.atZone(zone).toLocalDate()
            itemsByDay.getOrPut(day) { mutableListOf() } += entry.id
            widen(day, start, TimeMatcher.effectiveEnd(item) ?: start.plus(ScheduleGrouping.DEFAULT_ITEM_DURATION))
        }
        if (itemsByDay.isEmpty()) return null
        val mealsByDay = HashMap<LocalDate, MutableList<MealBreak>>()
        for (entry in entries) {
            if (entry.kind != ScheduleEntryKind.PLACE) continue
            val at = entry.at ?: continue
            val location = bundle.location(entry.id) ?: continue
            if (!isFood(location)) continue
            val kind = mealKind(at, zone) ?: continue
            val day = at.atZone(zone).toLocalDate()
            if (day !in itemsByDay || mealsByDay[day]?.any { it.kind == kind } == true) continue
            val end = at.plus(Duration.ofMinutes((entry.minutes ?: DEFAULT_MINUTES).toLong()))
            mealsByDay.getOrPut(day) { mutableListOf() } += MealBreak(kind, at, end, entry.id)
            widen(day, at, end)
        }
        val planning = preferences.planPreferences.copy(includeMeals = false)
        val planner = Planner(bundle, planning, hearts, preferences.constraints)
        val reasons = entries.filter { it.kind == ScheduleEntryKind.ITEM && it.reason != null }.associate { it.id to it.reason!! }
        val days = itemsByDay.map { (day, ids) ->
            val (spanStart, spanEnd) = spans.getValue(day)
            val windowStart = preferences.earliest?.let { minOf(day.atTime(it).atZone(zone).toInstant(), spanStart) } ?: spanStart
            val windowEnd = preferences.latest?.let { maxOf(day.atTime(it).atZone(zone).toInstant(), spanEnd) } ?: spanEnd
            val built = planner.dayView(day, ids, mealsByDay[day].orEmpty(), windowStart, windowEnd)
            built.copy(entries = built.entries.map { e -> if (e is PlannedItem) reasons[e.itemId]?.let { e.copy(reason = it) } ?: e else e })
        }
        val prints = days.flatMap { it.items }.mapNotNull { bundle.scheduleItem(it.itemId) }.associate { it.id to Planner.fingerprint(it) }
        val ids = itemsByDay.values.flatten().toSet()
        return Plan(
            eventId = bundle.event.id, createdAt = Instant.EPOCH, request = "", preferences = preferences.planPreferences,
            days = days, fingerprints = prints,
            // Timed food places cover their meal breaks: an edit's re-solve doesn't plan another one there.
            constraints = preferences.constraints.copy(mealsDone = mealsCovered(bundle)),
            checkedOff = checkedOff intersect ids,
        )
    }

    // ── Codec ───────────────────────────────────────────────────────────────

    /** `{"entries":[{"kind":"item","id":5,"reminder":30,"fingerprint":"…"},{"kind":"place","id":6,"at":"…Z","minutes":45}]}`. */
    fun encode(): String = buildJsonObject {
        put("entries", buildJsonArray {
            for (entry in entries) add(buildJsonObject {
                put("kind", entry.kind.key)
                put("id", entry.id)
                entry.at?.let { put("at", it.toString()) }
                entry.minutes?.let { put("minutes", it) }
                entry.reminder?.let { put("reminder", it) }
                entry.fingerprint?.let { put("fingerprint", it) }
                if (entry.source != ScheduleEntrySource.YOU) put("source", entry.source.key)
                entry.reason?.let { put("reason", it) }
            })
        })
    }.toString()

    override fun equals(other: Any?): Boolean = other is MySchedule && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "MySchedule($entries)"

    companion object {
        const val TITLE = "My Schedule"
        const val DEFAULT_MINUTES = 45
        const val DEFAULT_REMINDER = 15

        /** The reminder choices of an entry's menu; 0 = off. */
        val REMINDER_CHOICES = listOf(5, 10, 15, 30, 60, 0)
        const val MAX_ENTRIES = 300

        /** The planner's reason for an item that is in My schedule. */
        const val IN_SCHEDULE_REASON = "In your schedule"

        val ROW_ORDER: Comparator<MyScheduleRow> = compareBy<MyScheduleRow> { it.start }.thenBy { it.name }.thenBy { it.id }

        /** "5 min before", "1 hour before". */
        fun reminderText(minutes: Int): String = if (minutes == 60) "1 hour before" else "$minutes min before"

        /** "remind me 20 min before" → the nearest choice (15); zero or negative → off. */
        fun nearestReminder(minutes: Int): Int {
            if (minutes <= 0) return 0
            return REMINDER_CHOICES.filter { it > 0 }.minByOrNull { abs(it - minutes) } ?: DEFAULT_REMINDER
        }

        /**
         * The places of My schedule on the map and in Places' My Schedule chip: saved and timed places
         * plus the places of its schedule items (iOS build 169: liking an item showed nothing).
         */
        fun placeIds(heartedLocations: Set<Int>, heartedItems: Set<Int>, schedule: List<ScheduleItem>): Set<Int> =
            heartedLocations + schedule.filter { it.id in heartedItems }.mapNotNull { it.locationId }

        /**
         * A clock time ("8", "7:30 pm") as an instant: on the day of [near] when given (an entry being
         * moved), else today in the event's zone (tomorrow when that is more than 30 min past).
         */
        fun date(clock: LocalTime, near: Instant?, now: Instant, zone: ZoneId): Instant {
            val day = (near ?: now).atZone(zone).toLocalDate()
            val date = day.atTime(clock).atZone(zone).toInstant()
            if (near == null && date.isBefore(now.minus(Duration.ofMinutes(30)))) return day.plusDays(1).atTime(clock).atZone(zone).toInstant()
            return date
        }

        /**
         * My schedule from the likes of an older build (once per event); an older build's stored plan, when
         * given, is absorbed too ([absorb]).
         */
        fun migrate(heartedItems: Set<Int>, heartedLocations: Set<Int>, plan: Plan?): MySchedule {
            val schedule = MySchedule(
                heartedItems.sorted().map { ScheduleEntry(ScheduleEntryKind.ITEM, it) } +
                    heartedLocations.sorted().map { ScheduleEntry(ScheduleEntryKind.PLACE, it) },
            )
            if (plan != null) schedule.absorb(plan)
            return schedule
        }

        fun isFood(location: EventLocation): Boolean =
            (listOf(location.category.orEmpty()) + location.tags).any { it.lowercase().contains("food") }

        /** The meal a time belongs to: lunch 11:30–13:30, dinner 17:30–19:30, else none. */
        fun mealKind(at: Instant, zone: ZoneId): MealKind? {
            val local = at.atZone(zone)
            val minute = local.hour * 60 + local.minute
            return when (minute) {
                in (11 * 60 + 30)..(13 * 60 + 30) -> MealKind.LUNCH
                in (17 * 60 + 30)..(19 * 60 + 30) -> MealKind.DINNER
                else -> null
            }
        }

        private fun mealMinutes(meal: MealBreak): Int = maxOf(5, Duration.between(meal.start, meal.end).toMinutes().toInt())

        /** Lenient: unknown keys ignored, an entry without kind or id dropped (the rest kept), bad text → empty. */
        fun decode(text: String?): MySchedule {
            if (text.isNullOrBlank()) return MySchedule()
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return MySchedule()
            val list = root["entries"] as? JsonArray ?: return MySchedule()
            return MySchedule(list.mapNotNull { ScheduleEntry.decode(it) })
        }
    }
}

enum class ScheduleEntryKind(val key: String) {
    ITEM("item"), PLACE("place");

    companion object {
        fun fromKey(key: String?): ScheduleEntryKind? = entries.firstOrNull { it.key == key }
    }
}

/** Who put an entry into My schedule (one list §1). Unknown or missing → [YOU]. */
enum class ScheduleEntrySource(val key: String) {
    YOU("you"), PLANNER("planner"), ASSISTANT("assistant"), SHARED("shared");

    companion object {
        fun of(value: String?): ScheduleEntrySource = entries.firstOrNull { it.key == value?.trim()?.lowercase() } ?: YOU
    }
}

/** What an entry is about: a schedule item or a location (other kinds can't be in My schedule). */
data class ScheduleTarget(val kind: SearchKind, val id: Int) {
    val entryKind: ScheduleEntryKind?
        get() = when (kind) {
            SearchKind.SCHEDULE -> ScheduleEntryKind.ITEM
            SearchKind.LOCATION -> ScheduleEntryKind.PLACE
            else -> null
        }

    companion object {
        fun item(id: Int) = ScheduleTarget(SearchKind.SCHEDULE, id)
        fun place(id: Int) = ScheduleTarget(SearchKind.LOCATION, id)
    }
}

/** One entry of My schedule. */
data class ScheduleEntry(
    val kind: ScheduleEntryKind,
    val id: Int,
    /** Places only: the chosen start; null = a saved place without a time. */
    val at: Instant? = null,
    /** Places only: how long (minutes); null = [MySchedule.DEFAULT_MINUTES]. */
    val minutes: Int? = null,
    /** Minutes before the start; null = [MySchedule.DEFAULT_REMINDER], 0 = off. */
    val reminder: Int? = null,
    /** Items: [Planner.fingerprint] when added (or last kept), to notice organizer changes. */
    val fingerprint: String? = null,
    /** Who added it (one list §1): only `planner` entries may be removed by a new plan unasked. */
    val source: ScheduleEntrySource = ScheduleEntrySource.YOU,
    /** Why ("Because you like jazz", "Lunch break"), ≤ [MAX_REASON] characters. */
    val reason: String? = null,
) {
    internal val key: String get() = "${kind.key}$id"

    val target: ScheduleTarget
        get() = if (kind == ScheduleEntryKind.ITEM) ScheduleTarget.item(id) else ScheduleTarget.place(id)

    val reminderMinutes: Int get() = reminder ?: MySchedule.DEFAULT_REMINDER

    companion object {
        const val MAX_REASON = 80

        fun cleanReason(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON)

        internal fun decode(element: JsonElement): ScheduleEntry? {
            val obj = element as? JsonObject ?: return null
            fun string(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun int(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val kind = ScheduleEntryKind.fromKey(string("kind")) ?: return null
            val id = int("id") ?: return null
            val place = kind == ScheduleEntryKind.PLACE
            return ScheduleEntry(
                kind = kind,
                id = id,
                at = if (place) string("at")?.let { runCatching { Instant.parse(it) }.getOrNull() } else null,
                minutes = int("minutes")?.coerceIn(5, 12 * 60),
                reminder = int("reminder")?.takeIf { it in MySchedule.REMINDER_CHOICES },
                fingerprint = if (place) null else string("fingerprint"),
                source = ScheduleEntrySource.of(string("source")),
                reason = cleanReason(string("reason")),
            )
        }
    }
}

/** A timed row of My schedule, resolved against the bundle. */
data class MyScheduleRow(
    val entry: ScheduleEntry,
    val start: Instant,
    val end: Instant,
    val name: String,
    /** The place's name (an item's location, or the place itself). */
    val placeName: String?,
    val locationId: Int?,
    val ended: Boolean,
) {
    val id: String get() = entry.key
    val target: ScheduleTarget get() = entry.target
}

/** What changed, for the caller to count (API-009) and to refresh what follows My schedule. */
data class MyScheduleChange(
    /** The metric to send; null when membership didn't change (a time or reminder change). */
    val metric: String?,
    val target: ScheduleTarget,
)

/** When to remind about one entry ([MySchedule.reminders]). */
data class EntryReminder(val target: ScheduleTarget, val start: Instant, val fireAt: Instant)

/** The "When?" sheet's times and the one it suggests. */
data class SuggestedTimes(val times: List<Instant>, val suggested: Instant?)

/** An item in My schedule the organizer moved, changed or removed since it was added. */
data class OrganizerChange(val itemId: Int, val name: String, val kind: Kind) {
    sealed interface Kind {
        data class Moved(val start: Instant) : Kind
        data object Changed : Kind
        data object Cancelled : Kind
    }
}

// ── One list (owner, Nov 22, 2026; Attnd-Web docs/specs/one_list_spec.md) ────

/**
 * Edits to My schedule the planner (or an edit, version, swap, sequence, shared plan) proposes:
 * previewed, then applied as one entry on [ScheduleHistory].
 */
data class ScheduleProposal(
    val adds: List<Add> = emptyList(),
    val removes: List<Remove> = emptyList(),
    /** [Planner.summary] of the plan it came from ("8 items, 10:00 AM – 11:00 PM, with a lunch break"). */
    val summary: String = "",
) {
    data class Add(
        val target: ScheduleTarget,
        /** Places only: the time (a meal break); null keeps / saves without one. */
        val at: Instant? = null,
        val minutes: Int? = null,
        val reason: String? = null,
        /** The meal break a place add covers (for the pill). */
        val meal: MealKind? = null,
    )

    data class Remove(val target: ScheduleTarget, val meal: MealKind? = null)

    val isEmpty: Boolean get() = adds.isEmpty() && removes.isEmpty()

    /** "+5 items, +1 lunch break, −2 items" (U+2212 minus); "No change" when empty. */
    val pillText: String
        get() {
            fun count(n: Int, one: String) = "$n ${if (n == 1) one else one + "s"}"
            val parts = mutableListOf<String>()
            adds.count { it.meal == null }.takeIf { it > 0 }?.let { parts += "+" + count(it, "item") }
            for (kind in MealKind.entries) adds.count { it.meal == kind }.takeIf { it > 0 }?.let { parts += "+" + count(it, kind.label.lowercase()) }
            removes.count { it.meal == null }.takeIf { it > 0 }?.let { parts += "\u2212" + count(it, "item") }
            for (kind in MealKind.entries) removes.count { it.meal == kind }.takeIf { it > 0 }?.let { parts += "\u2212" + count(it, kind.label.lowercase()) }
            return if (parts.isEmpty()) "No change" else parts.joinToString(", ")
        }

    /** The proposal card's sentence: "Add 6 things to your schedule" (one list §7). */
    val title: String
        get() = when {
            removes.isEmpty() -> if (adds.size == 1) "Add 1 thing to your schedule" else "Add ${adds.size} things to your schedule"
            adds.isEmpty() -> if (removes.size == 1) "Remove 1 thing from your schedule" else "Remove ${removes.size} things from your schedule"
            else -> "Change your schedule: $pillText"
        }
}

/**
 * One Undo history over My schedule (one list §3): ≤ [MAX] snapshots, newest last. Plans, edits, versions,
 * swaps, sequences, shared plans and hearts all push one; "undo", the Undo pill and the snackbar pop it.
 */
data class ScheduleHistory(val snapshots: List<MySchedule> = emptyList()) {
    val canUndo: Boolean get() = snapshots.isNotEmpty()

    fun push(previous: MySchedule): ScheduleHistory = ScheduleHistory((snapshots + previous.copy()).takeLast(MAX))

    fun pop(): Pair<MySchedule, ScheduleHistory>? = snapshots.lastOrNull()?.let { it.copy() to ScheduleHistory(snapshots.dropLast(1)) }

    /** `[{"entries":[…]}, …]`. */
    fun encode(): String = JsonArray(snapshots.map { Json.parseToJsonElement(it.encode()) }).toString()

    companion object {
        const val MAX = 20

        /** Lenient: unreadable text is an empty history; unreadable snapshots are skipped. */
        fun decode(text: String?): ScheduleHistory {
            if (text.isNullOrBlank()) return ScheduleHistory()
            val list = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return ScheduleHistory()
            return ScheduleHistory(list.filterIsInstance<JsonObject>().map { MySchedule.decode(it.toString()) }.takeLast(MAX))
        }
    }
}

/** What the planner was told for this event (one list §1), kept with My schedule. Everything optional. */
data class SchedulePreferences(
    val pace: PlanPace = PlanPace.BALANCED,
    val kidFriendly: Boolean = false,
    val maxWalkMinutes: Int? = null,
    val likedTags: Set<String> = emptySet(),
    val dislikedTags: Set<String> = emptySet(),
    /** Meal breaks wanted (lunch / dinner). */
    val meals: Set<MealKind> = emptySet(),
    val earliest: LocalTime? = null,
    val latest: LocalTime? = null,
) {
    /** For the planner (a new plan without a request, the day view). */
    val planPreferences: PlanPreferences
        get() = PlanPreferences(
            likedTags = likedTags, dislikedTags = dislikedTags, earliest = earliest, latest = latest,
            pace = pace, includeMeals = meals.isNotEmpty(), kidFriendly = kidFriendly,
        )

    val constraints: PlanConstraints get() = PlanConstraints(maxWalkMinutes = maxWalkMinutes)

    /** `{"pace":"balanced","kid_friendly":true,"max_walk_minutes":10,"liked_tags":[…],"meals":["lunch"],"earliest":"10:00"}`. */
    fun encode(): String = buildJsonObject {
        put("pace", pace.key)
        if (kidFriendly) put("kid_friendly", true)
        maxWalkMinutes?.let { put("max_walk_minutes", it) }
        if (likedTags.isNotEmpty()) put("liked_tags", buildJsonArray { likedTags.sorted().forEach { add(JsonPrimitive(it)) } })
        if (dislikedTags.isNotEmpty()) put("disliked_tags", buildJsonArray { dislikedTags.sorted().forEach { add(JsonPrimitive(it)) } })
        if (meals.isNotEmpty()) put("meals", buildJsonArray { meals.map { it.name.lowercase() }.sorted().forEach { add(JsonPrimitive(it)) } })
        earliest?.let { put("earliest", clock(it)) }
        latest?.let { put("latest", clock(it)) }
    }.toString()

    companion object {
        private fun clock(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)

        /** From a request's preferences and an edit's constraints (a new plan, a migrated one). */
        fun of(preferences: PlanPreferences, constraints: PlanConstraints = PlanConstraints.NONE) = SchedulePreferences(
            pace = constraints.pace ?: preferences.pace,
            kidFriendly = preferences.kidFriendly,
            maxWalkMinutes = constraints.maxWalkMinutes,
            likedTags = preferences.likedTags,
            dislikedTags = preferences.dislikedTags,
            meals = if (preferences.includeMeals) MealKind.entries.toSet() else emptySet(),
            earliest = preferences.earliest,
            latest = preferences.latest,
        )

        /** Lenient: unknown keys ignored, bad values defaulted, bad text → null. */
        fun decode(text: String?): SchedulePreferences? {
            if (text.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            fun string(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun strings(name: String) = (obj[name] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim()?.lowercase()?.takeIf { t -> t.isNotEmpty() } }
            fun time(name: String) = string(name)?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
            val kid = (obj["kid_friendly"] as? JsonPrimitive)?.contentOrNull?.lowercase() == "true"
            return SchedulePreferences(
                pace = PlanPace.of(string("pace")),
                kidFriendly = kid,
                maxWalkMinutes = (obj["max_walk_minutes"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
                likedTags = strings("liked_tags").toSet(),
                dislikedTags = strings("disliked_tags").toSet(),
                meals = strings("meals").mapNotNull { m -> MealKind.entries.firstOrNull { it.name.lowercase() == m } }.toSet(),
                earliest = time("earliest"),
                latest = time("latest"),
            )
        }
    }
}

/**
 * One list §2: the planner as a function. Plans from [now] with [request] preferences (else the stored
 * [preferences]), My schedule's items as the must-includes (rule 7, its hearts) and its timed food places
 * covering their meal breaks, then diffs the result against My schedule. Rules 1–20 and the fixture's
 * numbers are unchanged; only the output is a proposal. The returned plan is the planner's result (its
 * reasons, walks and alternatives, for the preview).
 */
fun Planner.Companion.propose(
    schedule: MySchedule,
    preferences: SchedulePreferences,
    bundle: EventBundle,
    now: Instant,
    request: PlanPreferences? = null,
    text: String = "",
    constraints: PlanConstraints = PlanConstraints.NONE,
): Pair<Plan, ScheduleProposal> {
    val c = constraints.copy(
        mealsDone = constraints.mealsDone + schedule.mealsCovered(bundle),
        maxWalkMinutes = constraints.maxWalkMinutes ?: preferences.maxWalkMinutes,
    )
    val plan = Planner(bundle, request ?: preferences.planPreferences, schedule.hearts, c).plan(now, text)
    return plan to schedule.proposal(plan, bundle)
}

/**
 * An edit of the day view ([MySchedule.dayPlan]) keeps My schedule's timed food places: a meal break of
 * [PlanEdit.before] whose slot My schedule covers ([PlanConstraints.mealsDone]) stays in the result when the
 * re-solve has no meal of that kind that day, so editing the day never drops or moves the person's lunch.
 * Plans that aren't day views (no covered slots) are unchanged.
 */
fun PlanEdit.keepingScheduleMeals(bundle: EventBundle): PlanEdit {
    val covered = before.constraints.mealsDone
    if (covered.isEmpty()) return this
    var changed = false
    val days = after.days.map { day ->
        val kept = before.days.firstOrNull { it.date == day.date }?.entries.orEmpty().filterIsInstance<MealBreak>().filter { meal ->
            meal.locationId != null && MealSlot(day.date, meal.kind) in covered &&
                day.entries.none { it is MealBreak && it.kind == meal.kind }
        }
        if (kept.isEmpty()) day else {
            changed = true
            day.copy(entries = (day.entries + kept).sortedWith(compareBy<PlanEntry> { it.start }.thenBy { it.end }))
        }
    }
    if (!changed) return this
    val updated = after.copy(days = days)
    return copy(after = updated, diff = PlanDiff.between(before, updated, bundle))
}
