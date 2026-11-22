package com.arcu.attnd.data

import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.PlanCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * My schedule per event, on the device only (SharedPreferences, in Auto Backup; kept after leaving).
 * The stored text is [MySchedule.encode] JSON; every write also mirrors the entries into the old hearts
 * text, so [hearts] (search's Liked list, filters, the planner, the Live Update) and an older build
 * keep working. The first read of an event without a stored schedule migrates its hearts and stored
 * plan once ([MySchedule.migrate], no metrics). [version] changes on every write.
 */
class ScheduleStore(private val storage: Storage) {

    /** Where the texts live ([DevicePrefs] in the app, a map in tests). */
    interface Storage {
        fun mySchedule(eventId: Int): String?
        fun setMySchedule(eventId: Int, value: String)
        fun hearts(eventId: Int): String?
        fun setHearts(eventId: Int, value: String)
        fun plan(eventId: Int): String?
    }

    private val cache = HashMap<Int, MySchedule>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** A copy of the event's My schedule (migrated on first read). */
    @Synchronized
    fun schedule(eventId: Int): MySchedule = load(eventId).copy()

    @Synchronized
    fun hearts(eventId: Int): Hearts = load(eventId).hearts

    /**
     * Runs [change] on the event's My schedule; when the entries changed, stores them (and the hearts
     * mirror) and bumps [version]. Returns what [change] returned.
     */
    @Synchronized
    fun <T> update(eventId: Int, change: (MySchedule) -> T): T {
        val current = load(eventId)
        val working = current.copy()
        val result = change(working)
        if (working != current) save(eventId, working)
        return result
    }

    private fun load(eventId: Int): MySchedule = cache.getOrPut(eventId) {
        val stored = storage.mySchedule(eventId)
        if (stored != null) {
            MySchedule.decode(stored)
        } else {
            val hearts = Hearts.decode(storage.hearts(eventId))
            val plan = PlanCodec.decode(storage.plan(eventId))?.takeIf { it.eventId == eventId }
            MySchedule.migrate(hearts.scheduleItems, hearts.locations, plan).also {
                storage.setMySchedule(eventId, it.encode())
                storage.setHearts(eventId, it.hearts.encode())
            }
        }
    }

    private fun save(eventId: Int, schedule: MySchedule) {
        cache[eventId] = schedule
        storage.setMySchedule(eventId, schedule.encode())
        storage.setHearts(eventId, schedule.hearts.encode())
        _version.update { it + 1 }
    }

    companion object {
        fun of(prefs: DevicePrefs) = ScheduleStore(object : Storage {
            override fun mySchedule(eventId: Int) = prefs.mySchedule(eventId)
            override fun setMySchedule(eventId: Int, value: String) = prefs.setMySchedule(eventId, value)
            override fun hearts(eventId: Int) = prefs.hearts(eventId)
            override fun setHearts(eventId: Int, value: String) = prefs.setHearts(eventId, value)
            override fun plan(eventId: Int) = prefs.plan(eventId)
        })
    }
}
