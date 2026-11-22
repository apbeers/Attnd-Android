package com.arcu.attnd.data

import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.AssistantConversation
import com.arcu.attnd.domain.ChatTranscript
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.UrgentBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Per-event state of the assistant and the Live Update, on the device only: the Ask Attnd chat's transcript
 * and the model's conversation as files in [dir] (`filesDir/assistant/transcript-<id>.json`,
 * `conversation-<id>.json`, as iOS's `attnd-assistant/`; the conversation moves there from its old
 * preference on first read), and in SharedPreferences (Auto Backup like hearts) the shared plans opened,
 * "Follow on Lock Screen", reminders, skipped items, the urgent block (API-011) and which reminders were shown. [version]
 * changes on every write, so the UI and the Live Update follow. No plan: since the one-list change
 * (Nov 22, 2026) My schedule ([ScheduleStore]) is the only stored list and its Undo history.
 */
class PlanStore(private val prefs: DevicePrefs, private val dir: File? = null) {

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun changed() = _version.update { it + 1 }

    @Synchronized
    fun conversation(eventId: Int): AssistantConversation {
        read("conversation-$eventId.json")?.let { return AssistantConversation.decode(it) }
        // Older builds kept it in the preferences: move it to its file once.
        val old = prefs.assistantConversation(eventId) ?: return AssistantConversation()
        val conversation = AssistantConversation.decode(old)
        if (dir != null && write("conversation-$eventId.json", conversation.encode())) prefs.setAssistantConversation(eventId, null)
        return conversation
    }

    @Synchronized
    fun setConversation(eventId: Int, conversation: AssistantConversation) {
        val text = conversation.turns.takeIf { it.isNotEmpty() }?.let { conversation.encode() }
        if (dir == null) {
            prefs.setAssistantConversation(eventId, text)
            return
        }
        if (text == null) delete("conversation-$eventId.json") else write("conversation-$eventId.json", text)
        prefs.setAssistantConversation(eventId, null)
    }

    /** The Ask Attnd chat (≤ 40 turns, kept after leaving like the conversation). */
    @Synchronized
    fun chat(eventId: Int): ChatTranscript = ChatTranscript.decode(read("transcript-$eventId.json"))

    @Synchronized
    fun setChat(eventId: Int, chat: ChatTranscript) {
        // The old answer card's transcript preference was never shown: drop it.
        prefs.setAssistantTranscript(eventId, null)
        if (chat.entries.isEmpty()) delete("transcript-$eventId.json") else write("transcript-$eventId.json", chat.encode())
    }

    private fun read(name: String): String? {
        val file = dir?.resolve(name) ?: return null
        return try {
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            AppLog.caught(LogCategory.ASSISTANT, "plans.read", e, LogLevel.WARNING)
            null
        }
    }

    /** Atomic: a temp file renamed into place. */
    private fun write(name: String, text: String): Boolean {
        val folder = dir ?: return false
        return try {
            folder.mkdirs()
            val temp = File(folder, "$name.tmp")
            temp.writeText(text)
            if (!temp.renameTo(File(folder, name))) {
                temp.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            AppLog.caught(LogCategory.ASSISTANT, "plans.write", e, LogLevel.WARNING)
            false
        }
    }

    private fun delete(name: String) {
        dir?.resolve(name)?.delete()
    }

    /** Shared plans (API-015) opened for this event, oldest first (≤ 10), each its schedule ids. */
    fun sharedPlans(eventId: Int): List<List<Int>> = prefs.sharedPlans(eventId).orEmpty().lines()
        .map { line -> line.split(',').mapNotNull { it.trim().toIntOrNull() } }.filter { it.isNotEmpty() }

    fun addSharedPlan(eventId: Int, ids: List<Int>) {
        val all = (sharedPlans(eventId).filter { it != ids } + listOf(ids)).takeLast(MAX_SHARED)
        prefs.setSharedPlans(eventId, all.joinToString("\n") { it.joinToString(",") })
    }

    /** Reminders before the entries of My schedule: the user's choice, else on while following on the Lock Screen. */
    fun reminders(eventId: Int, hearts: Hearts): Boolean = prefs.reminders(eventId) ?: follows(eventId, hearts)

    fun setReminders(eventId: Int, on: Boolean) {
        prefs.setReminders(eventId, on)
        changed()
    }

    /** "Follow on Lock Screen" for the event: the user's choice, else on (iOS default, batch 7). */
    @Suppress("UNUSED_PARAMETER") // kept so callers don't change; the default no longer depends on likes
    fun follows(eventId: Int, hearts: Hearts): Boolean =
        prefs.followLive(eventId) ?: true

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
