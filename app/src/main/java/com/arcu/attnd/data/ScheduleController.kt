package com.arcu.attnd.data

import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.MyScheduleChange
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.ScheduleEntrySource
import com.arcu.attnd.domain.ScheduleHistory
import com.arcu.attnd.domain.SchedulePreferences
import com.arcu.attnd.domain.ScheduleProposal
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.UsageMetric
import java.time.Instant

/**
 * My schedule (iOS `AppState` § "My schedule"), on the application scope so screens, the assistant and
 * the Live Update share one place. One list (owner, Nov 22, 2026; Attnd-Web `docs/specs/one_list_spec.md`):
 * My schedule is the only stored list.
 * - add / remove / time / reminder, counted with the API-009 metric only when membership really changes
 *   (never a time or reminder change, the migration, a restore or a rejoin);
 * - every change of the planner (new plans, edits, versions, swaps, sequences, shared plans) arrives as
 *   a [ScheduleProposal] ([applyProposal], [applyPlan]); adds, removes, new times and applied proposals
 *   are one step each on the Undo history ([undo], ≤ 20);
 * - the plan the assistant, the Live Update and the hints read is derived ([dayPlan]), never stored.
 *
 * [bundle] is the joined event's current bundle (fingerprints for organizer changes, the day view);
 * [track] counts a usage metric (`UsageReporter.track`).
 */
class ScheduleController(
    private val store: ScheduleStore,
    private val bundle: () -> EventBundle?,
    private val track: (eventId: Int, name: String, detail: String) -> Unit,
) {
    val version get() = store.version

    fun schedule(eventId: Int): MySchedule = store.schedule(eventId)

    fun hearts(eventId: Int): Hearts = store.hearts(eventId)

    fun history(eventId: Int): ScheduleHistory = store.history(eventId)

    fun preferences(eventId: Int): SchedulePreferences = store.preferences(eventId)

    fun setPreferences(eventId: Int, preferences: SchedulePreferences) = store.setPreferences(eventId, preferences)

    fun checkedOff(eventId: Int): Set<Int> = store.checkedOff(eventId)

    /** A check-off by tap or auto check-off (outside the Undo history); true when it changed. */
    fun setCheckedOff(eventId: Int, itemId: Int, done: Boolean): Boolean = store.setCheckedOff(eventId, itemId, done)

    fun isLiked(eventId: Int, kind: SearchKind, id: Int): Boolean = ScheduleTarget(kind, id) in store.schedule(eventId)

    private var dayCache: Pair<List<Any?>, Plan?>? = null

    /**
     * My schedule's day view ([MySchedule.dayPlan]): the [Plan] the assistant (timeline, proposals, edits),
     * the Live Update, live hints, WhyNot and sharing read. Derived, never stored; cached until My schedule,
     * its preferences, its check-offs or the bundle change. Null without a timed item or for another event.
     */
    @Synchronized
    fun dayPlan(eventId: Int, bundle: EventBundle? = currentBundle(eventId)): Plan? {
        if (bundle == null || bundle.event.id != eventId) return null
        val schedule = store.schedule(eventId)
        val preferences = store.preferences(eventId)
        val checked = store.checkedOff(eventId)
        val key = listOf(System.identityHashCode(bundle), bundle.contentVersion, schedule, preferences, checked)
        dayCache?.let { (k, plan) -> if (k == key) return plan }
        return schedule.dayPlan(bundle, preferences, checked)?.takeIf { !it.isEmpty }.also { dayCache = key to it }
    }

    /**
     * The heart: adds [id] to (a place without a time) or removes it from My schedule; true when that
     * changed membership. The API-009 count is sent only then, and only when [countUsage] (never in
     * screenshot mode). One step on the Undo history.
     */
    fun setLiked(eventId: Int, kind: SearchKind, id: Int, liked: Boolean, countUsage: Boolean): Boolean {
        val target = ScheduleTarget(kind, id)
        val changes = if (liked) add(eventId, target, countUsage = countUsage) else remove(eventId, target, countUsage)
        return changes.any { it.metric != null }
    }

    /**
     * Adds an item, or a place at a time (null: saved without one); a place already there gets the new
     * time. [source] / [reason]: who added it and why (one list §1).
     */
    fun add(
        eventId: Int,
        target: ScheduleTarget,
        at: Instant? = null,
        minutes: Int? = null,
        countUsage: Boolean = true,
        source: ScheduleEntrySource = ScheduleEntrySource.YOU,
        reason: String? = null,
    ): List<MyScheduleChange> {
        val current = currentBundle(eventId)
        return counted(eventId, countUsage, store.update(eventId, undoable = true) { schedule ->
            val change = schedule.add(target, at, minutes)
            if (change?.metric != null) schedule.mark(target, source, reason)
            if (current != null) schedule.fillFingerprints(current)
            listOfNotNull(change)
        })
    }

    fun remove(eventId: Int, target: ScheduleTarget, countUsage: Boolean = true): List<MyScheduleChange> =
        counted(eventId, countUsage, store.update(eventId, undoable = true) { listOfNotNull(it.remove(target)) })

    /** A place entry's time (null: keep it saved without a time). */
    fun move(eventId: Int, target: ScheduleTarget, at: Instant?): List<MyScheduleChange> =
        counted(eventId, true, store.update(eventId, undoable = true) { listOfNotNull(it.move(target, at)) })

    /** The entry's reminder (minutes before; 0 = off; null = the default 15 min). Not on the Undo history. */
    fun setReminder(eventId: Int, target: ScheduleTarget, minutes: Int?): List<MyScheduleChange> =
        counted(eventId, true, store.update(eventId) { listOfNotNull(it.setReminder(target, minutes)) })

    /**
     * One list §3: applies [proposal] to My schedule as one Undo step (adds get [source] and the
     * proposal's reasons; removes leave). Returns the changes (API-009 counted).
     */
    fun applyProposal(eventId: Int, proposal: ScheduleProposal, source: ScheduleEntrySource = ScheduleEntrySource.PLANNER): List<MyScheduleChange> {
        if (proposal.isEmpty) return emptyList()
        val current = currentBundle(eventId)
        return counted(eventId, true, store.update(eventId, undoable = true) { schedule ->
            schedule.apply(proposal, source).also { if (current != null) schedule.fillFingerprints(current) }
        })
    }

    /**
     * Apply (one list §3): a plan (new, edited, a version, a swap, a sequence, shared) becomes edits to My
     * schedule ([MySchedule.proposal]) as one Undo step. [explicit] = items the request asked to drop (an
     * edit's excluded items); [keepPreferences] stores the plan's preferences for later plans; [reason] replaces
     * the planner's reasons of item adds (a shared plan: "In a shared plan"). Returns the
     * proposal applied (empty: nothing changed).
     */
    fun applyPlan(
        eventId: Int,
        plan: Plan,
        explicit: Set<Int> = emptySet(),
        source: ScheduleEntrySource = ScheduleEntrySource.PLANNER,
        keepPreferences: Boolean = true,
        reason: String? = null,
    ): ScheduleProposal {
        val current = currentBundle(eventId) ?: return ScheduleProposal()
        var proposal = store.schedule(eventId).proposal(plan, current, explicit)
        if (reason != null) proposal = proposal.copy(adds = proposal.adds.map { if (it.meal == null) it.copy(reason = reason) else it })
        if (keepPreferences) store.setPreferences(eventId, SchedulePreferences.of(plan.preferences, plan.constraints))
        applyProposal(eventId, proposal, source)
        return proposal
    }

    /**
     * "undo", the Undo pill and the snackbar: My schedule as it was before the last change. Adds and
     * removes it reverses are counted (API-009) like any other. False with nothing to undo.
     */
    fun undo(eventId: Int, countUsage: Boolean = true): Boolean {
        val (before, restored) = store.undo(eventId) ?: return false
        val changes = mutableListOf<MyScheduleChange>()
        (restored.itemIds - before.itemIds).sorted().forEach { changes += MyScheduleChange(UsageMetric.SCHEDULE_ITEM_HEART, ScheduleTarget.item(it)) }
        (before.itemIds - restored.itemIds).sorted().forEach { changes += MyScheduleChange(UsageMetric.SCHEDULE_ITEM_UNHEART, ScheduleTarget.item(it)) }
        (restored.placeIds - before.placeIds).sorted().forEach { changes += MyScheduleChange(UsageMetric.LOCATION_HEART, ScheduleTarget.place(it)) }
        (before.placeIds - restored.placeIds).sorted().forEach { changes += MyScheduleChange(UsageMetric.LOCATION_UNHEART, ScheduleTarget.place(it)) }
        counted(eventId, countUsage, changes)
        return true
    }

    /** Items in My schedule the organizer moved, changed or cancelled (the Schedule tab's hints). */
    fun organizerChanges(bundle: EventBundle): List<OrganizerChange> = store.schedule(bundle.event.id).organizerChanges(bundle)

    /** "Keep" on an organizer change: the item as it is now (a cancelled one leaves My schedule). */
    fun keepOrganizerChange(bundle: EventBundle, itemId: Int) {
        store.update(bundle.event.id) { it.keepChange(itemId, bundle) }
    }

    /** A new bundle: fingerprints for entries that have none (the migration's, adds made without the bundle). */
    fun onBundle(bundle: EventBundle) {
        store.update(bundle.event.id) { it.fillFingerprints(bundle) }
    }

    /** Assistant spec §6, like clash: a liked schedule item that overlaps My schedule's day → "Replace X with this?". */
    fun likeClash(bundle: EventBundle, itemId: Int): Chip? {
        val plan = dayPlan(bundle.event.id, bundle) ?: return null
        return LiveAssist.likeClash(itemId, plan, bundle)
    }

    private fun currentBundle(eventId: Int): EventBundle? = bundle()?.takeIf { it.event.id == eventId }

    /** Counts real adds / removes (API-009). */
    private fun counted(eventId: Int, countUsage: Boolean, changes: List<MyScheduleChange>): List<MyScheduleChange> {
        if (countUsage) for (change in changes) change.metric?.let { track(eventId, it, change.target.id.toString()) }
        return changes
    }
}
