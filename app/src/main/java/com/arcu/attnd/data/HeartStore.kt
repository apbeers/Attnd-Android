package com.arcu.attnd.data

import com.arcu.attnd.domain.Hearts
import kotlinx.coroutines.flow.StateFlow

/**
 * Hearted schedule items and locations per event, read-only: since phase 3a they mirror My schedule
 * ([ScheduleStore]; changes go through [ScheduleController]). Kept so everything that reads hearts (the
 * UI, the planner, the assistant, the Live Update) is unchanged; [version] is My schedule's.
 */
class HeartStore(private val schedule: ScheduleStore) {

    val version: StateFlow<Int> get() = schedule.version

    fun hearts(eventId: Int): Hearts = schedule.hearts(eventId)
}
