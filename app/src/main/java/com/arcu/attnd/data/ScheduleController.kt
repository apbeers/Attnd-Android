package com.arcu.attnd.data

import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.MealSlot
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.MyScheduleChange
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.SearchKind
import java.time.Instant

/**
 * My schedule (iOS `AppState` § "My schedule"), on the application scope so screens, the assistant and
 * the Live Update share one place: add / remove / time / reminder, counted with the API-009 metric only
 * when membership really changes (never a time or reminder change, the migration, a restore or a
 * rejoin), the planner filling it ([applyPlan], PlanStore's `onApply`) and the plan kept in line after a
 * change made elsewhere ([MySchedule.reconcile]). The hearts mirror the entries ([HeartStore]).
 *
 * [bundle] is the joined event's current bundle (fingerprints for organizer changes, the reconcile);
 * [plan] / [updatePlan] read and replace the stored plan in place (`PlanStore.plan` / `setPlan`);
 * [track] counts a usage metric (`UsageReporter.track`).
 */
class ScheduleController(
    private val store: ScheduleStore,
    private val bundle: () -> EventBundle?,
    private val plan: (eventId: Int) -> Plan?,
    private val updatePlan: (eventId: Int, Plan) -> Unit,
    private val track: (eventId: Int, name: String, detail: String) -> Unit,
) {
    fun schedule(eventId: Int): MySchedule = store.schedule(eventId)

    fun hearts(eventId: Int): Hearts = store.hearts(eventId)

    fun isLiked(eventId: Int, kind: SearchKind, id: Int): Boolean = ScheduleTarget(kind, id) in store.schedule(eventId)

    /**
     * The heart: adds [id] to (a place without a time) or removes it from My schedule; true when that
     * changed membership. The API-009 count is sent only then, and only when [countUsage] (never in
     * screenshot mode).
     */
    fun setLiked(eventId: Int, kind: SearchKind, id: Int, liked: Boolean, countUsage: Boolean): Boolean {
        val target = ScheduleTarget(kind, id)
        val changes = if (liked) add(eventId, target, countUsage = countUsage) else remove(eventId, target, countUsage)
        return changes.any { it.metric != null }
    }

    /** Adds an item, or a place at a time (null: saved without one); a place already there gets the new time. */
    fun add(eventId: Int, target: ScheduleTarget, at: Instant? = null, minutes: Int? = null, countUsage: Boolean = true): List<MyScheduleChange> {
        val current = currentBundle(eventId)
        return changed(eventId, countUsage, store.update(eventId) { schedule ->
            val change = schedule.add(target, at, minutes)
            if (current != null) schedule.fillFingerprints(current)
            listOfNotNull(change)
        })
    }

    fun remove(eventId: Int, target: ScheduleTarget, countUsage: Boolean = true): List<MyScheduleChange> =
        changed(eventId, countUsage, store.update(eventId) { listOfNotNull(it.remove(target)) })

    /** A place entry's time (null: keep it saved without a time). */
    fun move(eventId: Int, target: ScheduleTarget, at: Instant?): List<MyScheduleChange> =
        changed(eventId, true, store.update(eventId) { listOfNotNull(it.move(target, at)) })

    /** The entry's reminder (minutes before; 0 = off; null = the default 15 min). */
    fun setReminder(eventId: Int, target: ScheduleTarget, minutes: Int?): List<MyScheduleChange> =
        changed(eventId, true, store.update(eventId) { listOfNotNull(it.setReminder(target, minutes)) })

    /** Undo of an add made with the heart (the Undo snackbar, phase 3b): removes [target], counted like any removal. */
    fun undoAdd(eventId: Int, target: ScheduleTarget, countUsage: Boolean = true): List<MyScheduleChange> =
        remove(eventId, target, countUsage)

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

    /** Timed food places in My schedule that cover a lunch or dinner break (the planner's `mealsDone`). */
    fun mealsCovered(bundle: EventBundle): Set<MealSlot> = store.schedule(bundle.event.id).mealsCovered(bundle)

    /**
     * A plan the planner made, changed or undid fills My schedule ([MySchedule.apply]): its new items are
     * added, items an edit dropped on its days removed (both counted); clearing the plan keeps My
     * schedule. The plan itself is not reconciled here (it is the new arrangement).
     */
    fun applyPlan(eventId: Int, plan: Plan?, previous: Plan?): List<MyScheduleChange> {
        val current = currentBundle(eventId)
        val changes = store.update(eventId) { schedule ->
            schedule.apply(plan, previous).also { if (current != null) schedule.fillFingerprints(current) }
        }
        return changed(eventId, countUsage = true, changes = changes, reconcile = false)
    }

    /** Assistant spec §6, like clash: a liked schedule item that overlaps the plan → "Add to plan instead of X?". */
    fun likeClash(bundle: EventBundle, itemId: Int): Chip? {
        val plan = plan(bundle.event.id)?.takeIf { !it.isEmpty } ?: return null
        return LiveAssist.likeClash(itemId, plan, bundle)
    }

    private fun currentBundle(eventId: Int): EventBundle? = bundle()?.takeIf { it.event.id == eventId }

    /** Counts real adds / removes (API-009) and keeps the planner's copy of the day in line. */
    private fun changed(eventId: Int, countUsage: Boolean, changes: List<MyScheduleChange>, reconcile: Boolean = true): List<MyScheduleChange> {
        if (changes.isEmpty()) return changes
        if (countUsage) for (change in changes) change.metric?.let { track(eventId, it, change.target.id.toString()) }
        val current = currentBundle(eventId)
        if (reconcile && current != null) {
            val stored = plan(eventId)
            val reconciled = store.schedule(eventId).reconcile(stored, current)
            if (reconciled != null && reconciled != stored) updatePlan(eventId, reconciled)
        }
        return changes
    }
}
