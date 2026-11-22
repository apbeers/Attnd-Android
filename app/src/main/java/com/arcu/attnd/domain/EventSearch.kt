package com.arcu.attnd.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a search result points at. The order is the tie-break order. */
enum class SearchKind { SCHEDULE, LOCATION, NOTIFICATION, EVENT }

/** Live state of a schedule item (event time zone). */
enum class ItemTiming(val label: String) { NOW("Now"), NEXT("Next"), ENDED("Ended") }

data class SearchResult(
    val kind: SearchKind,
    /** Schedule item / location / notification id; the event id for [SearchKind.EVENT]. */
    val id: Int,
    val title: String,
    /** Character ranges of [title] that matched the query, for emphasis. */
    val titleHighlights: List<IntRange>,
    val score: Double,
    /** Schedule items only. */
    val timing: ItemTiming? = null,
)

/** All-token search (the search screen) or any-token retrieval (context for the on-device assistant). */
enum class SearchMode { ALL_TOKENS, ANY_TOKEN }

/**
 * Local search across the joined event's bundle (shared spec with iOS; no network, works offline).
 *
 *
 * - Both sides are normalised ([SearchText]); every query token must match somewhere in an item
 *   (AND across fields). A token matches a word that is equal to it, starts with it, contains it,
 *   or (tokens of 5+ characters) is one edit away.
 * - Time words ([SearchQuery]) filter schedule items in the event's time zone; when the query is only
 *   time words, only schedule items are returned.
 * - Score: field weight × match quality per token (weights: name 3; tags (items and locations), location name, category,
 *   snippet, address, event code 2; descriptions and notification bodies 1; quality: exact word 4,
 *   prefix 3, substring 2, typo 1), + 8 for items happening now, + 4 for next, − 1000 for ended
 *   (always last). Ties: kind order, then title, then id. Same choices as iOS.
 * - Schedule items without a location are searchable under "General".
 */
object EventSearch {

    const val WEIGHT_PRIMARY = 3.0
    const val WEIGHT_SECONDARY = 2.0
    const val WEIGHT_TERTIARY = 1.0
    const val BOOST_NOW = 8.0
    const val BOOST_NEXT = 4.0
    const val PENALTY_ENDED = -1000.0

    private enum class Quality(val value: Double) { EXACT(4.0), PREFIX(3.0), SUBSTRING(2.0), TYPO(1.0) }

    private class Field(val text: String, val weight: Double, val isTitle: Boolean = false) {
        val normalized: NormalizedText = SearchText.mapped(text)
    }

    private class Candidate(val kind: SearchKind, val id: Int, val title: String, val fields: List<Field>, val item: ScheduleItem? = null)

    fun search(
        bundle: EventBundle,
        query: String,
        now: Instant,
        mode: SearchMode = SearchMode.ALL_TOKENS,
        limit: Int = Int.MAX_VALUE,
    ): List<SearchResult> {
        val candidates = candidates(bundle)
        val vocabulary = candidates.flatMapTo(HashSet()) { c -> c.fields.flatMap { f -> f.normalized.words.map { it.second } } }
        val parsed = SearchQuery.parse(query, vocabulary)
        if (parsed.isEmpty) return emptyList()
        val tokens = parsed.tokens
        val timeOnly = parsed.isTimeOnly
        val zone = bundle.event.zone
        val matcher = TimeMatcher(parsed.time, zone, now, TimeMatcher.eventDays(bundle, now))

        val results = candidates.mapNotNull { candidate ->
            if (timeOnly && candidate.kind != SearchKind.SCHEDULE) return@mapNotNull null
            val item = candidate.item
            if (item != null && !matcher.matches(item)) return@mapNotNull null
            score(candidate, tokens, mode)?.let { (score, highlights) ->
                val timing = item?.let { timing(it, now) }
                val boost = when (timing) {
                    ItemTiming.NOW -> BOOST_NOW
                    ItemTiming.NEXT -> BOOST_NEXT
                    ItemTiming.ENDED -> PENALTY_ENDED
                    null -> 0.0
                }
                SearchResult(candidate.kind, candidate.id, candidate.title, highlights, score + boost, timing)
            }
        }
        return results
            .sortedWith(
                compareBy<SearchResult>({ it.timing == ItemTiming.ENDED }, { -it.score }, { it.kind.ordinal })
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            .take(limit)
    }

    /** NOW / NEXT (starts within 2 h) / ENDED, or null for later and untimed items. */
    fun timing(item: ScheduleItem, now: Instant): ItemTiming? {
        val start = item.startTime?.toInstant() ?: return null
        val end = TimeMatcher.effectiveEnd(item) ?: return null
        return when {
            !end.isAfter(now) -> ItemTiming.ENDED
            !start.isAfter(now) -> ItemTiming.NOW
            !start.isAfter(now.plus(ScheduleGrouping.UP_NEXT_WINDOW)) -> ItemTiming.NEXT
            else -> null
        }
    }

    /**
     * Returns the total score and title highlights, or null when the candidate does not match
     * (ALL_TOKENS: a token matched nowhere; ANY_TOKEN: no token matched). No tokens = time-only query.
     */
    private fun score(candidate: Candidate, tokens: List<String>, mode: SearchMode): Pair<Double, List<IntRange>>? {
        if (tokens.isEmpty()) return 0.0 to emptyList()
        var total = 0.0
        var matched = 0
        val highlights = mutableListOf<IntRange>()
        for (token in tokens) {
            var best = 0.0
            for (field in candidate.fields) {
                for ((offset, word) in field.normalized.words) {
                    val quality = quality(token, word) ?: continue
                    best = maxOf(best, field.weight * quality.value)
                    if (field.isTitle) highlight(field.normalized, offset, word, token, quality)?.let { highlights += it }
                }
            }
            if (best == 0.0) {
                if (mode == SearchMode.ALL_TOKENS) return null
            } else {
                matched++
                total += best
            }
        }
        if (matched == 0) return null
        return total to merge(highlights)
    }

    private fun quality(token: String, word: String): Quality? = when {
        word == token -> Quality.EXACT
        word.startsWith(token) -> Quality.PREFIX
        word.contains(token) -> Quality.SUBSTRING
        token.length >= 5 && withinOneEdit(token, word) -> Quality.TYPO
        else -> null
    }

    private fun highlight(text: NormalizedText, offset: Int, word: String, token: String, quality: Quality): IntRange? =
        when (quality) {
            Quality.EXACT, Quality.PREFIX -> text.sourceRange(offset, offset + token.length)
            Quality.SUBSTRING -> word.indexOf(token).let { at -> text.sourceRange(offset + at, offset + at + token.length) }
            Quality.TYPO -> text.sourceRange(offset, offset + word.length)
        }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        for (range in sorted.drop(1)) {
            val last = merged.last()
            if (range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            else merged += range
        }
        return merged
    }

    /** Levenshtein distance ≤ 1 (one insertion, deletion or substitution). */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (long.length - short.length > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            if (short.length == long.length) i++
            j++
        }
        return edits + (long.length - j) + (short.length - i) <= 1
    }

    private fun candidates(bundle: EventBundle): List<Candidate> = buildList {
        for (item in bundle.schedule) {
            val locationName = item.locationName?.takeIf { it.isNotBlank() } ?: bundle.location(item.locationId)?.name
                ?: ScheduleGrouping.GENERAL_TITLE
            add(
                Candidate(
                    kind = SearchKind.SCHEDULE,
                    id = item.id,
                    title = item.name,
                    fields = listOfNotNull(
                        Field(item.name, WEIGHT_PRIMARY, isTitle = true),
                        Field(item.tags.joinToString(" "), WEIGHT_SECONDARY),
                        Field(locationName, WEIGHT_SECONDARY),
                        Field(item.description, WEIGHT_TERTIARY),
                        // API-018 (as iOS): performer names and genres weigh like tags; their bios and the about text like descriptions.
                        item.context?.let { c -> Field((c.performers.map { it.name } + c.genres).joinToString(" "), WEIGHT_SECONDARY) },
                        item.context?.let { c -> Field((listOf(c.about) + c.performers.map { it.bio }).joinToString(" "), WEIGHT_TERTIARY) },
                    ),
                    item = item,
                )
            )
        }
        for (location in bundle.locations) {
            add(
                Candidate(
                    kind = SearchKind.LOCATION,
                    id = location.id,
                    title = location.name,
                    fields = listOfNotNull(
                        Field(location.name, WEIGHT_PRIMARY, isTitle = true),
                        location.category?.let { Field(it, WEIGHT_SECONDARY) },
                        Field(location.tags.joinToString(" "), WEIGHT_SECONDARY),
                        Field(location.snippet, WEIGHT_SECONDARY),
                        Field(location.address, WEIGHT_SECONDARY),
                        Field(location.description, WEIGHT_TERTIARY),
                    ),
                )
            )
        }
        for (notification in bundle.notifications) {
            add(
                Candidate(
                    kind = SearchKind.NOTIFICATION,
                    id = notification.id,
                    title = notification.title,
                    fields = listOf(
                        Field(notification.title, WEIGHT_PRIMARY, isTitle = true),
                        Field(notification.body, WEIGHT_TERTIARY),
                    ),
                )
            )
        }
        val event = bundle.event
        add(
            Candidate(
                kind = SearchKind.EVENT,
                id = event.id,
                title = event.name,
                fields = listOf(
                    Field(event.name, WEIGHT_PRIMARY, isTitle = true),
                    Field(event.eventCode, WEIGHT_SECONDARY),
                    Field(event.address, WEIGHT_SECONDARY),
                    Field(event.description, WEIGHT_TERTIARY),
                ),
            )
        )
    }

    // ── Result rows ─────────────────────────────────────────────────────────

    private const val SEPARATOR = " · "
    private const val BODY_PREVIEW_LENGTH = 80

    /**
     * The one line of secondary info under a result: schedule → "Sat 7:30–8:30 PM · Main Stage";
     * location → "Food · Street tacos"; notification → "Sat 3:55 PM · Come on in…";
     * event → "Nov 14–15 · Sundance Square". Times are in the event's time zone.
     */
    fun detail(result: SearchResult, bundle: EventBundle, locale: Locale = Locale.getDefault()): String {
        val zone = bundle.event.zone
        return when (result.kind) {
            SearchKind.SCHEDULE -> bundle.scheduleItem(result.id)?.let { scheduleDetail(it, bundle, locale) }.orEmpty()
            SearchKind.LOCATION -> bundle.location(result.id)?.let { location ->
                listOfNotNull(location.category?.trim(), location.snippet.trim()).filter { it.isNotEmpty() }
                    .joinToString(SEPARATOR)
                    .ifEmpty { location.address.trim() }
            }.orEmpty()
            SearchKind.NOTIFICATION -> bundle.notifications.firstOrNull { it.id == result.id }?.let { notification ->
                listOfNotNull(
                    notification.sendTime?.let { EventTime.formatDayTime(it, zone, locale) },
                    preview(notification.body).takeIf { it.isNotEmpty() },
                ).joinToString(SEPARATOR)
            }.orEmpty()
            SearchKind.EVENT -> eventDetail(bundle.event, locale)
        }
    }

    fun scheduleDetail(item: ScheduleItem, bundle: EventBundle, locale: Locale = Locale.getDefault()): String {
        val zone = bundle.event.zone
        val start = item.startTime
        val time = if (start == null) ScheduleGrouping.TIME_TBA_TITLE
        else "${dayFormat(start, zone, locale)} ${compactTimeRange(start, item.endTime, zone, locale)}"
        val place = item.locationName?.takeIf { it.isNotBlank() } ?: bundle.location(item.locationId)?.name
            ?: ScheduleGrouping.GENERAL_TITLE
        return time + SEPARATOR + place
    }

    /** "Nov 14–15 · Sundance Square, Fort Worth" (dates in the event's zone; either part may be missing). */
    fun eventDetail(event: Event, locale: Locale = Locale.getDefault()): String =
        listOfNotNull(eventDates(event, locale), event.address.trim().takeIf { it.isNotEmpty() }).joinToString(SEPARATOR)

    /** "Sat, Nov 14", "Nov 14–15", "Nov 30 – Dec 2", or null without a start time. */
    fun eventDates(event: Event, locale: Locale = Locale.getDefault()): String? {
        val zone = event.zone
        val start = event.startTime?.let { EventTime.localDate(it, zone) } ?: return null
        val end = event.endTime?.let { EventTime.localDate(it, zone) }?.takeIf { it.isAfter(start) }
        val monthDay = DateTimeFormatter.ofPattern("MMM d", locale)
        return when {
            end == null -> start.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
            end.year == start.year && end.month == start.month -> "${start.format(monthDay)}–${end.dayOfMonth}"
            else -> "${start.format(monthDay)} – ${end.format(monthDay)}"
        }
    }

    /** "7:30–8:30 PM", "11:30 AM–12:30 PM", "9:00 PM–Sun 1:00 AM", or the start alone without an end. */
    fun compactTimeRange(start: OffsetDateTime, end: OffsetDateTime?, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val s = start.atZoneSameInstant(zone)
        val full = DateTimeFormatter.ofPattern("h:mm a", locale)
        if (end == null || !end.isAfter(start)) return s.format(full)
        val e = end.atZoneSameInstant(zone)
        if (s.toLocalDate() != e.toLocalDate()) {
            return "${s.format(full)}–${e.format(DateTimeFormatter.ofPattern("EEE h:mm a", locale))}"
        }
        val sameHalf = (s.hour < 12) == (e.hour < 12)
        return if (sameHalf) "${s.format(DateTimeFormatter.ofPattern("h:mm", locale))}–${e.format(full)}"
        else "${s.format(full)}–${e.format(full)}"
    }

    private fun dayFormat(time: OffsetDateTime, zone: ZoneId, locale: Locale): String =
        time.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEE", locale))

    private fun preview(body: String): String {
        val flat = body.trim().replace(Regex("\\s+"), " ")
        return if (flat.length <= BODY_PREVIEW_LENGTH) flat else flat.take(BODY_PREVIEW_LENGTH).trimEnd() + "…"
    }

    // ── Suggestions ─────────────────────────────────────────────────────────

    /** A chip shown for an empty query; tapping it searches [query]. */
    data class Suggestion(val label: String, val query: String)

    /** "My Schedule" since My schedule replaced likes (iOS, Nov 21, 2026). */
    const val HEARTED_TITLE = "My Schedule"

    /**
     * The search screen's "Liked" entry (shown once anything is hearted; it opens this list, it is
     * not a query): hearted schedule items in time order (with their Now/Next/Ended), then hearted
     * locations in the organizer's order. Ids no longer in the bundle are skipped.
     */
    fun hearted(bundle: EventBundle, hearts: Hearts, now: Instant): List<SearchResult> =
        ScheduleGrouping.sortChronologically(bundle.schedule.filter { it.id in hearts.scheduleItems }).map { item ->
            SearchResult(SearchKind.SCHEDULE, item.id, item.name, emptyList(), 0.0, timing(item, now))
        } + bundle.locations.filter { it.id in hearts.locations }.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { location ->
            SearchResult(SearchKind.LOCATION, location.id, location.name, emptyList(), 0.0)
        }

    /** "Happening now" / "Up next" (when something is), then the categories. */
    fun suggestions(bundle: EventBundle, now: Instant): List<Suggestion> = buildList {
        val timings = bundle.schedule.mapNotNull { timing(it, now) }.toSet()
        if (ItemTiming.NOW in timings) add(Suggestion(ScheduleGrouping.NOW_TITLE, "now"))
        if (ItemTiming.NEXT in timings) add(Suggestion(ScheduleGrouping.NEXT_TITLE, "next"))
        LocationGrouping.availableCategories(bundle.locations, bundle.categories).forEach { add(Suggestion(it, it)) }
    }
}
