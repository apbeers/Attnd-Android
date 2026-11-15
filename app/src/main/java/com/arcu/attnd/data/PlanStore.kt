package com.arcu.attnd.data

import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.AssistantTranscript
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanCodec
import com.arcu.attnd.domain.PlanHistory
import com.arcu.attnd.domain.StoredTurn
import com.arcu.attnd.domain.UrgentBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

/**
 * Per-event state of the planner, the assistant and the Live Update, on the device only
 * (SharedPreferences, in Auto Backup like hearts): the plan, its undo stack, the assistant's
 * conversation and transcript, shared plans opened, "Follow on Lock Screen", reminders, skipped items,
 * the urgent block (API-011) and which reminders were shown. [version] changes on every write, so the UI and the Live Update follow.
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

    /**
     * Applies a plan change with Undo (assistant spec §4): the replaced plan goes onto the undo stack
     * (≤ 20). Returns the new stack depth.
     */
    @Synchronized
    fun applyPlan(eventId: Int, plan: Plan?): Int {
        val previous = plan(eventId)
        val history = if (previous != null && !previous.isEmpty) history(eventId).push(previous) else history(eventId)
        setHistory(eventId, history)
        setPlan(eventId, plan)
        return history.plans.size
    }

    /** Undo: restores the last replaced plan; null when there is nothing to undo. */
    @Synchronized
    fun undoPlan(eventId: Int): Plan? {
        val (restored, rest) = history(eventId).pop() ?: return null
        setHistory(eventId, rest)
        setPlan(eventId, restored)
        return restored
    }

    @Synchronized
    fun history(eventId: Int): PlanHistory = histories.getOrPut(eventId) { PlanHistory.decode(prefs.planHistory(eventId)) }

    private fun setHistory(eventId: Int, history: PlanHistory) {
        histories[eventId] = history
        prefs.setPlanHistory(eventId, history.takeIf { it.canUndo }?.encode())
    }

    private val histories = HashMap<Int, PlanHistory>()

    fun conversation(eventId: Int): AssistantConversation = AssistantConversation.decode(prefs.assistantConversation(eventId))

    fun setConversation(eventId: Int, conversation: AssistantConversation) =
        prefs.setAssistantConversation(eventId, conversation.turns.takeIf { it.isNotEmpty() }?.let { conversation.encode() })

    fun transcript(eventId: Int): List<StoredTurn> = AssistantTranscript.decode(prefs.assistantTranscript(eventId))

    fun setTranscript(eventId: Int, turns: List<StoredTurn>) =
        prefs.setAssistantTranscript(eventId, turns.takeIf { it.isNotEmpty() }?.let { AssistantTranscript.encode(it) })

    /** Shared plans (API-015) opened for this event, oldest first (≤ 10), each its schedule ids. */
    fun sharedPlans(eventId: Int): List<List<Int>> = prefs.sharedPlans(eventId).orEmpty().lines()
        .map { line -> line.split(',').mapNotNull { it.trim().toIntOrNull() } }.filter { it.isNotEmpty() }

    fun addSharedPlan(eventId: Int, ids: List<Int>) {
        val all = (sharedPlans(eventId).filter { it != ids } + listOf(ids)).takeLast(MAX_SHARED)
        prefs.setSharedPlans(eventId, all.joinToString("\n") { it.joinToString(",") })
    }

    /** Reminders before liked and planned items: the user's choice, else on while following on the Lock Screen. */
    fun reminders(eventId: Int, hearts: Hearts): Boolean = prefs.reminders(eventId) ?: follows(eventId, hearts)

    fun setReminders(eventId: Int, on: Boolean) {
        prefs.setReminders(eventId, on)
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

    private companion object {
        const val MAX_SHARED = 10
    }
}
