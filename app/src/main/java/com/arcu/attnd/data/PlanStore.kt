package com.arcu.attnd.data

import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.UrgentBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

/**
 * Per-event state of the planner and the Live Update, on the device only (SharedPreferences, in Auto
 * Backup like hearts): the plan, "Follow on Lock Screen", skipped items, the urgent block (API-011) and
 * which reminders were shown. [version] changes on every write, so the UI and the Live Update follow.
 */
class PlanStore(private val prefs: DevicePrefs) {

    private val plans = HashMap<Int, Plan?>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun changed() = _version.update { it + 1 }

    @Synchronized
    fun plan(eventId: Int): Plan? = plans.getOrPut(eventId) { PlanCodec.decode(prefs.plan(eventId))?.takeIf { it.eventId == eventId } }

    @Synchronized
    fun setPlan(eventId: Int, plan: Plan?) {
        plans[eventId] = plan
        prefs.setPlan(eventId, plan?.let(PlanCodec::encode))
        changed()
    }

    /** "Follow on Lock Screen" for the event: the user's choice, else on when they have hearts or a plan. */
    fun follows(eventId: Int, hearts: Hearts): Boolean =
        prefs.followLive(eventId) ?: (!hearts.isEmpty || plan(eventId)?.isEmpty == false)

    fun setFollows(eventId: Int, follow: Boolean) {
        prefs.setFollowLive(eventId, follow)
        if (follow) prefs.setLiveDismissedDay(eventId, null)
        changed()
    }

    /** Items skipped from the Live Update today (event-zone date [day]). */
    fun skipped(eventId: Int, day: LocalDate): Set<Int> {
        val text = prefs.liveSkipped(eventId) ?: return emptySet()
        val date = text.substringBefore('|')
        if (date != day.toString()) return emptySet()
        return text.substringAfter('|', "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun skip(eventId: Int, day: LocalDate, itemId: Int) {
        val ids = skipped(eventId, day) + itemId
        prefs.setLiveSkipped(eventId, "$day|" + ids.sorted().joinToString(","))
        changed()
    }

    fun liveDismissedOn(eventId: Int): String? = prefs.liveDismissedDay(eventId)

    fun setLiveDismissed(eventId: Int, day: LocalDate?) {
        prefs.setLiveDismissedDay(eventId, day?.toString())
        changed()
    }

    fun arrived(eventId: Int, day: LocalDate): Boolean = prefs.liveArrivedDay(eventId) == day.toString()

    fun setArrived(eventId: Int, day: LocalDate) {
        prefs.setLiveArrivedDay(eventId, day.toString())
        changed()
    }

    fun urgent(eventId: Int): UrgentBlock? {
        val parts = prefs.urgent(eventId)?.split('|', limit = 4) ?: return null
        if (parts.size < 4) return null
        val id = parts[0].toIntOrNull() ?: return null
        val sent = parts[1].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
        return UrgentBlock(id, parts[2], parts[3], sent)
    }

    fun setUrgent(eventId: Int, urgent: UrgentBlock) {
        // The title can't contain the separator; the body is last and may.
        prefs.setUrgent(eventId, "${urgent.id}|${urgent.sentAt.toEpochMilli()}|${urgent.title.replace('|', '/')}|${urgent.body}")
        changed()
    }

    fun dismissedUrgent(eventId: Int): Int? = prefs.dismissedUrgent(eventId)

    fun dismissUrgent(eventId: Int, id: Int) {
        prefs.setDismissedUrgent(eventId, id)
        changed()
    }

    fun remindersShown(eventId: Int): Set<Int> =
        prefs.remindersShown(eventId)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()

    fun markReminderShown(eventId: Int, itemId: Int) {
        prefs.setRemindersShown(eventId, (remindersShown(eventId) + itemId).sorted().joinToString(","))
    }
}
