package com.arcu.attnd.domain

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Ask about this event" with the on-device model (Gemini Nano via ML Kit's Prompt API): the
 * context is retrieved locally from the bundle with [EventSearch] and written into the prompt; the
 * model cites items as `[S12]` (schedule), `[L5]` (location), `[U9]` (update) or `[E]` (the event),
 * and those citations become tappable results. Nothing leaves the device.
 */
object AssistantPrompt {

    const val MAX_CONTEXT_ITEMS = 12
    private const val MAX_TEXT = 160
    private val CITATION = Regex("""\[([SLUE])(\d*)]""")

    /** Items that may answer [question]: any-token matches first, then what is on now / next. */
    fun context(bundle: EventBundle, question: String, now: Instant): List<SearchResult> {
        val matches = EventSearch.search(bundle, SearchQuery.keywords(question), now, SearchMode.ANY_TOKEN, limit = MAX_CONTEXT_ITEMS)
        if (matches.size >= MAX_CONTEXT_ITEMS) return matches
        val seen = matches.mapTo(HashSet()) { it.kind to it.id }
        val live = ScheduleGrouping.sortChronologically(bundle.schedule)
            .mapNotNull { item -> EventSearch.timing(item, now)?.takeIf { it != ItemTiming.ENDED }?.let { item to it } }
            .sortedBy { (_, timing) -> timing.ordinal }
            .map { (item, timing) -> SearchResult(SearchKind.SCHEDULE, item.id, item.name, emptyList(), 0.0, timing) }
            .filter { (it.kind to it.id) !in seen }
        return (matches + live).take(MAX_CONTEXT_ITEMS)
    }

    fun build(
        bundle: EventBundle,
        question: String,
        context: List<SearchResult>,
        now: Instant,
        locale: Locale = Locale.getDefault(),
    ): String {
        val event = bundle.event
        val zone = event.zone
        val nowText = now.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", locale))
        return buildString {
            appendLine("You are the assistant in the Attnd app for the event \"${clean(event.name)}\".")
            appendLine("Answer the attendee's question using only the event information below.")
            appendLine("If the information does not contain the answer, say that you don't know.")
            appendLine("Answer in at most three short sentences. After each fact, cite the item it comes from")
            appendLine("in square brackets, for example [S12].")
            appendLine("All times are local to the event (${zone.id}). It is now $nowText.")
            appendLine()
            appendLine("Event information:")
            append("[E] ").append(clean(event.name))
            EventSearch.eventDetail(event, locale).takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
            if (event.isArchived) append(" — the event has ended")
            clean(event.description).takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
            appendLine()
            for (result in context) {
                line(result, bundle, locale)?.let { appendLine(it) }
            }
            appendLine()
            appendLine("Question: ${clean(question, max = 300)}")
            append("Answer:")
        }
    }

    private fun line(result: SearchResult, bundle: EventBundle, locale: Locale): String? {
        val detail = EventSearch.detail(result, bundle, locale)
        return when (result.kind) {
            SearchKind.SCHEDULE -> bundle.scheduleItem(result.id)?.let { item ->
                buildString {
                    append("[S${item.id}] ").append(clean(item.name)).append(" — ").append(detail)
                    result.timing?.let { append(" — ").append(timingText(it)) }
                    if (item.tags.isNotEmpty()) append(" — tags: ").append(clean(item.tags.joinToString(", ")))
                    clean(item.description).takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
                }
            }
            SearchKind.LOCATION -> bundle.location(result.id)?.let { location ->
                buildString {
                    append("[L${location.id}] ").append(clean(location.name))
                    if (detail.isNotEmpty()) append(" — ").append(detail)
                    clean(location.address).takeIf { it.isNotEmpty() && it != detail }?.let { append(" — ").append(it) }
                    clean(location.description).takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
                }
            }
            SearchKind.NOTIFICATION -> bundle.notifications.firstOrNull { it.id == result.id }?.let { notification ->
                buildString {
                    append("[U${notification.id}] Organizer update: ").append(clean(notification.title))
                    notification.sendTime?.let { append(" — sent ").append(EventTime.formatDayTime(it, bundle.event.zone, locale)) }
                    clean(notification.body).takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
                }
            }
            SearchKind.EVENT -> null // always included as [E]
        }
    }

    private fun timingText(timing: ItemTiming): String = when (timing) {
        ItemTiming.NOW -> "happening now"
        ItemTiming.NEXT -> "starting soon"
        ItemTiming.ENDED -> "already ended"
    }

    /** One line, no brackets (so organizer text can't fake a citation), at most [max] characters. */
    private fun clean(text: String, max: Int = MAX_TEXT): String {
        val flat = text.replace('[', '(').replace(']', ')').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    /** The items the answer cites, in order of first mention, limited to those that were in [context] or the event. */
    fun citations(answer: String, context: List<SearchResult>, bundle: EventBundle): List<SearchResult> {
        val byKey = context.associateBy { it.kind to it.id }
        val cited = LinkedHashMap<Pair<SearchKind, Int>, SearchResult>()
        for (match in CITATION.findAll(answer)) {
            val kind = when (match.groupValues[1]) {
                "S" -> SearchKind.SCHEDULE
                "L" -> SearchKind.LOCATION
                "U" -> SearchKind.NOTIFICATION
                else -> SearchKind.EVENT
            }
            val key = if (kind == SearchKind.EVENT) kind to bundle.event.id else kind to (match.groupValues[2].toIntOrNull() ?: continue)
            if (key in cited) continue
            val result = byKey[key]
                ?: if (kind == SearchKind.EVENT) SearchResult(SearchKind.EVENT, bundle.event.id, bundle.event.name, emptyList(), 0.0) else null
            if (result != null) cited[key] = result
        }
        return cited.values.toList()
    }

    /** The answer without citation markers, for display. */
    fun displayText(answer: String): String =
        answer.replace(CITATION, "").replace(Regex("[ \\t]+([.,;:!?])"), "$1").replace(Regex("[ \\t]{2,}"), " ").trim()
}
