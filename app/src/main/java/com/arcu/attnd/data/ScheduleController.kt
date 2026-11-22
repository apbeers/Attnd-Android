package com.arcu.attnd.data

import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LiveAssist
import com.arcu.attnd.domain.SearchKind

/**
 * The attendee's personal picks for an event (iOS: My schedule in `AppState`), on the application scope
 * so screens, the assistant and the Live Update share one place. For now a thin layer over
 * [HeartStore]: a like or unlike counts the API-009 metric only when the heart actually changes. Phase
 * 3a turns it into My schedule (entries with times and reminders, Undo).
 */
class ScheduleController(
    private val hearts: HeartStore,
    private val plans: PlanStore,
    private val usage: UsageReporter,
) {
    fun hearts(eventId: Int): Hearts = hearts.hearts(eventId)

    fun isLiked(eventId: Int, kind: SearchKind, id: Int): Boolean = hearts.hearts(eventId).has(kind, id)

    /**
     * Likes or unlikes [id]; true when the heart changed. The API-009 count is sent only then, and only
     * when [countUsage] (never in screenshot mode).
     */
    fun setLiked(eventId: Int, kind: SearchKind, id: Int, liked: Boolean, countUsage: Boolean): Boolean {
        if (!hearts.set(eventId, kind, id, liked)) return false
        if (countUsage) Hearts.metric(kind, liked)?.let { usage.track(eventId, it, id.toString()) }
        return true
    }

    /** Assistant spec §6, like clash: a liked schedule item that overlaps the plan → "Add to plan instead of X?". */
    fun likeClash(bundle: EventBundle, itemId: Int): Chip? {
        val plan = plans.plan(bundle.event.id)?.takeIf { !it.isEmpty } ?: return null
        return LiveAssist.likeClash(itemId, plan, bundle)
    }
}
