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
 * - Migration ([migrate], once per event): hearted items and the stored plan's items become item
 *   entries, hearted places saved places, planned meal breaks with a suggested food place become place
 *   entries at the break's time. The old keys stay readable.
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

    // ── Plans (the planner fills My schedule) ───────────────────────────────

    /**
     * What applying a plan does to My schedule: its items are added; items of the previous plan that the
     * new one dropped (on the new plan's days) are removed; meal breaks with a suggested food place
     * become place entries at the break's time. Returns the changes.
     */
    fun apply(plan: Plan?, previous: Plan?): List<MyScheduleChange> {
        val changes = mutableListOf<MyScheduleChange>()
        val newIds = plan?.itemIds.orEmpty().toSet()
        val newDays = plan?.days.orEmpty().map { it.date }.toSet()
        val newMeals = plan?.days.orEmpty().flatMap { day -> day.entries.filterIsInstance<MealBreak>() }
        // Clearing the plan ("New plan") keeps My schedule as it is.
        if (plan != null) {
            for (day in previous?.days.orEmpty()) {
                if (day.date !in newDays) continue
                for (item in day.items) if (item.itemId !in newIds) remove(ScheduleTarget.item(item.itemId))?.let(changes::add)
                for (meal in day.entries.filterIsInstance<MealBreak>()) {
                    val locationId = meal.locationId ?: continue
                    if (entry(ScheduleEntryKind.PLACE, locationId)?.at != meal.start) continue
                    val stays = newMeals.any { it.locationId == locationId && it.start == meal.start }
                    if (!stays) remove(ScheduleTarget.place(locationId))?.let(changes::add)
                }
            }
        }
        for (id in plan?.itemIds.orEmpty()) add(ScheduleTarget.item(id))?.let(changes::add)
        val previousMeals = previous?.days.orEmpty().flatMap { day -> day.entries.filterIsInstance<MealBreak>() }
        for (meal in newMeals) {
            val locationId = meal.locationId ?: continue
            if (entry(ScheduleEntryKind.PLACE, locationId)?.at == null || previousMeals.any { it.locationId == locationId }) {
                add(ScheduleTarget.place(locationId), meal.start, mealMinutes(meal))?.let(changes::add)
            }
        }
        return changes
    }

    /**
     * The plan kept in line with My schedule after a change made elsewhere: items no longer in My
     * schedule leave it, items added on one of its days join it ("In your schedule"), meal breaks whose
     * place entry was removed lose the place. null stays null.
     */
    fun reconcile(plan: Plan?, bundle: EventBundle): Plan? {
        plan ?: return null
        val zone = bundle.event.zone
        val items = itemIds
        val planned = plan.itemIds.toSet()
        return plan.copy(days = plan.days.map { day ->
            val kept = day.entries.mapNotNull { entry ->
                when (entry) {
                    is PlannedItem -> entry.takeIf { it.itemId in items }
                    is MealBreak -> {
                        val id = entry.locationId
                        if (id != null && entry(ScheduleEntryKind.PLACE, id) == null) entry.copy(locationId = null) else entry
                    }
                }
            }
            val added = (items - planned).sorted().mapNotNull { id ->
                val item = bundle.scheduleItem(id)
                val start = item?.startTime?.toInstant()
                if (item == null || start == null || start.atZone(zone).toLocalDate() != day.date) null
                else PlannedItem(
                    itemId = id, start = start,
                    end = TimeMatcher.effectiveEnd(item) ?: start.plus(ScheduleGrouping.DEFAULT_ITEM_DURATION),
                    reason = IN_SCHEDULE_REASON, walkMinutes = null, alternatives = emptyList(), orInstead = emptyList(),
                )
            }
            day.copy(entries = (kept + added).sortedBy { it.start })
        })
    }

    /**
     * Meal breaks the planner shouldn't add: a timed food place in My schedule during lunch (11:30–13:30)
     * or dinner (17:30–19:30) of that day covers it.
     */
    fun mealsCovered(bundle: EventBundle): Set<MealSlot> {
        val zone = bundle.event.zone
        val slots = mutableSetOf<MealSlot>()
        for (entry in entries) {
            if (entry.kind != ScheduleEntryKind.PLACE) continue
            val at = entry.at ?: continue
            val location = bundle.location(entry.id) ?: continue
            if ((listOf(location.category.orEmpty()) + location.tags).none { it.lowercase().contains("food") }) continue
            val local = at.atZone(zone)
            val minute = local.hour * 60 + local.minute
            if (minute in (11 * 60 + 30)..(13 * 60 + 30)) slots += MealSlot(local.toLocalDate(), MealKind.LUNCH)
            if (minute in (17 * 60 + 30)..(19 * 60 + 30)) slots += MealSlot(local.toLocalDate(), MealKind.DINNER)
        }
        return slots
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

        /** My schedule from the likes and the plan of an older build (once per event). */
        fun migrate(heartedItems: Set<Int>, heartedLocations: Set<Int>, plan: Plan?): MySchedule {
            val items = heartedItems.sorted().map { ScheduleEntry(ScheduleEntryKind.ITEM, it) }.toMutableList()
            for (id in plan?.itemIds.orEmpty()) {
                if (id !in heartedItems && items.none { it.id == id }) items += ScheduleEntry(ScheduleEntryKind.ITEM, id)
            }
            val places = heartedLocations.sorted().map { ScheduleEntry(ScheduleEntryKind.PLACE, it) }.toMutableList()
            for (day in plan?.days.orEmpty()) for (meal in day.entries.filterIsInstance<MealBreak>()) {
                val locationId = meal.locationId ?: continue
                val index = places.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    if (places[index].at == null) places[index] = places[index].copy(at = meal.start, minutes = mealMinutes(meal))
                } else {
                    places += ScheduleEntry(ScheduleEntryKind.PLACE, locationId, at = meal.start, minutes = mealMinutes(meal))
                }
            }
            return MySchedule(items + places)
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
) {
    internal val key: String get() = "${kind.key}$id"

    val target: ScheduleTarget
        get() = if (kind == ScheduleEntryKind.ITEM) ScheduleTarget.item(id) else ScheduleTarget.place(id)

    val reminderMinutes: Int get() = reminder ?: MySchedule.DEFAULT_REMINDER

    companion object {
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
