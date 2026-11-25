package com.arcu.attnd.data

import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.ScheduleHistory
import com.arcu.attnd.domain.SchedulePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * My schedule per event, on the device only (SharedPreferences, in Auto Backup; kept after leaving): the
 * only stored list (one list, Nov 22, 2026). The stored text is [MySchedule.encode] JSON; every write
 * also mirrors the entries into the old hearts text, so [hearts] (search's Liked list, filters, the
 * planner, the Live Update) and an older build keep working. Next to it: the one Undo history over My
 * schedule ([history], ≤ 20 snapshots), what the planner was told ([preferences]) and the checked-off
 * items ([checkedOff]).
 *
 * Migration on the first read of an event (no metrics): an event without a stored schedule gets its
 * hearts ([MySchedule.migrate]); an older build's stored plan (else the newest of its plan history) is
 * absorbed once ([MySchedule.absorb]: `source: planner`, meal places, the preferences, the check-offs)
 * and its keys deleted, so re-running finds nothing and changes nothing. [version] changes on every write.
 */
class ScheduleStore(private val storage: Storage) {

    /** Where the texts live ([DevicePrefs] in the app, a map in tests). */
    interface Storage {
        fun mySchedule(eventId: Int): String?
        fun setMySchedule(eventId: Int, value: String)
        fun hearts(eventId: Int): String?
        fun setHearts(eventId: Int, value: String)
        fun history(eventId: Int): String?
        fun setHistory(eventId: Int, value: String?)
        fun preferences(eventId: Int): String?
        fun setPreferences(eventId: Int, value: String)
        fun checkedOff(eventId: Int): String?
        fun setCheckedOff(eventId: Int, value: String)

        /** An older build's stored plan and plan history (read once, then [deleteLegacyPlan]). */
        fun legacyPlan(eventId: Int): String?
        fun legacyPlanHistory(eventId: Int): String?
        fun deleteLegacyPlan(eventId: Int)
    }

    private val cache = HashMap<Int, MySchedule>()
    private val histories = HashMap<Int, ScheduleHistory>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** A copy of the event's My schedule (migrated on first read). */
    @Synchronized
    fun schedule(eventId: Int): MySchedule = load(eventId).copy()

    @Synchronized
    fun hearts(eventId: Int): Hearts = load(eventId).hearts

    /**
     * Runs [change] on the event's My schedule; when the entries changed, stores them (and the hearts
     * mirror), pushes the schedule as it was onto the Undo history when [undoable], and bumps [version].
     * Returns what [change] returned.
     */
    @Synchronized
    fun <T> update(eventId: Int, undoable: Boolean = false, change: (MySchedule) -> T): T {
        val current = load(eventId)
        val working = current.copy()
        val result = change(working)
        if (working != current) {
            if (undoable) setHistory(eventId, history(eventId).push(current))
            save(eventId, working)
        }
        return result
    }

    /** The one Undo history over My schedule (one list §3). */
    @Synchronized
    fun history(eventId: Int): ScheduleHistory = histories.getOrPut(eventId) {
        load(eventId)
        ScheduleHistory.decode(storage.history(eventId))
    }

    /** "undo": My schedule as it was before the last change; (before, restored), null with nothing to undo. */
    @Synchronized
    fun undo(eventId: Int): Pair<MySchedule, MySchedule>? {
        val (restored, rest) = history(eventId).pop() ?: return null
        val before = load(eventId).copy()
        setHistory(eventId, rest)
        save(eventId, restored)
        return before to restored.copy()
    }

    @Synchronized
    fun preferences(eventId: Int): SchedulePreferences {
        load(eventId)
        return SchedulePreferences.decode(storage.preferences(eventId)) ?: SchedulePreferences()
    }

    @Synchronized
    fun setPreferences(eventId: Int, preferences: SchedulePreferences) {
        if (preferences == preferences(eventId)) return
        storage.setPreferences(eventId, preferences.encode())
        _version.update { it + 1 }
    }

    @Synchronized
    fun checkedOff(eventId: Int): Set<Int> {
        load(eventId)
        return decodeIds(storage.checkedOff(eventId))
    }

    /** Check-offs (tap or auto): a per-event set, outside the Undo history. True when it changed. */
    @Synchronized
    fun setCheckedOff(eventId: Int, itemId: Int, done: Boolean): Boolean {
        val current = checkedOff(eventId)
        val updated = if (done) current + itemId else current - itemId
        if (updated == current) return false
        storage.setCheckedOff(eventId, updated.sorted().joinToString(","))
        _version.update { it + 1 }
        return true
    }

    private fun setHistory(eventId: Int, history: ScheduleHistory) {
        histories[eventId] = history
        storage.setHistory(eventId, history.takeIf { it.canUndo }?.encode())
    }

    private fun load(eventId: Int): MySchedule = cache.getOrPut(eventId) {
        val stored = storage.mySchedule(eventId)
        val schedule = if (stored != null) MySchedule.decode(stored) else {
            val hearts = Hearts.decode(storage.hearts(eventId))
            MySchedule.migrate(hearts.scheduleItems, hearts.locations, null)
        }
        var changed = stored == null
        val legacy = (PlanCodec.decode(storage.legacyPlan(eventId)) ?: PlanCodec.newestInHistory(storage.legacyPlanHistory(eventId)))
            ?.takeIf { it.eventId == eventId }
        if (legacy != null) {
            changed = schedule.absorb(legacy) || changed
            if (storage.preferences(eventId) == null) storage.setPreferences(eventId, SchedulePreferences.of(legacy.preferences, legacy.constraints).encode())
            val checks = decodeIds(storage.checkedOff(eventId))
            if (!checks.containsAll(legacy.checkedOff)) storage.setCheckedOff(eventId, (checks + legacy.checkedOff).sorted().joinToString(","))
        }
        if (changed) {
            storage.setMySchedule(eventId, schedule.encode())
            storage.setHearts(eventId, schedule.hearts.encode())
        }
        // Deleted after My schedule is saved, so a crash in between re-runs the (idempotent) migration.
        if (storage.legacyPlan(eventId) != null || storage.legacyPlanHistory(eventId) != null) storage.deleteLegacyPlan(eventId)
        schedule
    }

    private fun save(eventId: Int, schedule: MySchedule) {
        cache[eventId] = schedule
        storage.setMySchedule(eventId, schedule.encode())
        storage.setHearts(eventId, schedule.hearts.encode())
        _version.update { it + 1 }
    }

    private fun decodeIds(text: String?): Set<Int> = text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    companion object {
        fun of(prefs: DevicePrefs) = ScheduleStore(object : Storage {
            override fun mySchedule(eventId: Int) = prefs.mySchedule(eventId)
            override fun setMySchedule(eventId: Int, value: String) = prefs.setMySchedule(eventId, value)
            override fun hearts(eventId: Int) = prefs.hearts(eventId)
            override fun setHearts(eventId: Int, value: String) = prefs.setHearts(eventId, value)
            override fun history(eventId: Int) = prefs.scheduleHistory(eventId)
            override fun setHistory(eventId: Int, value: String?) = prefs.setScheduleHistory(eventId, value)
            override fun preferences(eventId: Int) = prefs.plannerPreferences(eventId)
            override fun setPreferences(eventId: Int, value: String) = prefs.setPlannerPreferences(eventId, value)
            override fun checkedOff(eventId: Int) = prefs.checkedOff(eventId)
            override fun setCheckedOff(eventId: Int, value: String) = prefs.setCheckedOff(eventId, value)
            override fun legacyPlan(eventId: Int) = prefs.legacyPlan(eventId)
            override fun legacyPlanHistory(eventId: Int) = prefs.legacyPlanHistory(eventId)
            override fun deleteLegacyPlan(eventId: Int) = prefs.deleteLegacyPlan(eventId)
        })
    }
}
