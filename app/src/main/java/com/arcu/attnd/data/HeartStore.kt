package com.arcu.attnd.data

import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.ScheduleTarget
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Hearted schedule items and locations per event, read-only: since phase 3a they mirror My schedule
 * ([ScheduleStore]; changes go through [ScheduleController]). Kept so everything that reads hearts (the
 * UI, the planner, the assistant, the Live Update) is unchanged; [version] is My schedule's.
 */
class HeartStore(private val schedule: ScheduleStore) {

    val version: StateFlow<Int> get() = schedule.version

    fun hearts(eventId: Int): Hearts = schedule.hearts(eventId)

    /** My schedule itself (the assistant's router: timed places, clashes, reminders). */
    fun schedule(eventId: Int): MySchedule = schedule.schedule(eventId)

    /** The assistant's time / reminder changes (phase 6a): membership doesn't change, so no API-009 count. */
    fun move(eventId: Int, target: ScheduleTarget, at: Instant?) = schedule.update(eventId) { it.move(target, at) }

    fun setReminder(eventId: Int, target: ScheduleTarget, minutes: Int?) = schedule.update(eventId) { it.setReminder(target, minutes) }
}
