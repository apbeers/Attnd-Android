package com.arcu.attnd.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A day named in a query: `today`, `tomorrow` or a weekday (`saturday`, `sat`). */
sealed interface DayWord {
    data object Today : DayWord
    data object Tomorrow : DayWord
    data class Weekday(val day: DayOfWeek) : DayWord
}

/**
 * The time words of a query (always in the event's time zone). Every part that is set must hold
 * for a schedule item; [now] and [next] together mean "now or next".
 */
data class TimeFilter(
    val now: Boolean = false,
    val next: Boolean = false,
    val tonight: Boolean = false,
    val days: Set<DayWord> = emptySet(),
    val clocks: List<LocalTime> = emptyList(),
) {
    val isEmpty: Boolean get() = !now && !next && !tonight && days.isEmpty() && clocks.isEmpty()
}

/** A parsed search query: the text tokens (normalised) and the time words. */
data class SearchQuery(
    val tokens: List<String>,
    val time: TimeFilter,
) {
    val isEmpty: Boolean get() = tokens.isEmpty() && time.isEmpty

    /** Only time words ("now", "tonight 9pm"): only schedule items are searched. */
    val isTimeOnly: Boolean get() = tokens.isEmpty() && !time.isEmpty

    companion object {
        /**
         * Words that carry no meaning on their own. Only [keywords] drops them (questions to the
         * assistant / AppFunctions); the search field searches every word, as the spec says.
         */
        val STOP_WORDS = setOf(
            "a", "an", "the", "at", "in", "on", "of", "for", "and", "or", "to", "is", "are", "be",
            "what", "whats", "when", "where", "who", "which", "how", "does", "do", "s",
            "can", "i", "me", "my", "we", "find", "get", "show", "tell", "about", "there", "any",
            "it", "this", "that", "with",
        )

        private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
        )

        /** Abbreviations are only time words when no indexed text uses them as a word (e.g. "Sun Stage"). */
        private val WEEKDAY_ABBREVIATIONS: Map<String, DayOfWeek> = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY,
            "thurs" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )

        // 3pm, 3 pm, 3:30pm, 3:30 p.m., 12am
        private val CLOCK_12H = Regex("""(?<![\w:])(\d{1,2})(?::([0-5]\d))?\s*([ap])\.?\s?m\.?(?![\w])""")

        // 3:30, 15:00 (an hour of 1–12 without am/pm means both the AM and the PM time)
        private val CLOCK_24H = Regex("""(?<![\w:])([01]?\d|2[0-3]):([0-5]\d)(?![\w:])""")
        private val NOON = Regex("""(?<![\w])noon(?![\w])""")
        private val MIDNIGHT = Regex("""(?<![\w])midnight(?![\w])""")

        /**
         * Splits [query] into text tokens and time words. [vocabulary] is the set of normalised words
         * in the searched content; a weekday abbreviation that is also a word there stays text.
         */
        fun parse(query: String, vocabulary: Set<String> = emptySet()): SearchQuery {
            var rest = SearchText.fold(query)
            val clocks = mutableListOf<LocalTime>()

            rest = CLOCK_12H.replace(rest) { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                if (hour !in 1..12) return@replace match.value
                val pm = match.groupValues[3] == "p"
                val hour24 = when {
                    hour == 12 && !pm -> 0
                    hour == 12 -> 12
                    pm -> hour + 12
                    else -> hour
                }
                clocks += LocalTime.of(hour24, minute)
                " "
            }
            rest = CLOCK_24H.replace(rest) { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                clocks += LocalTime.of(hour, minute)
                if (hour in 1..11) clocks += LocalTime.of(hour + 12, minute)
                " "
            }
            rest = NOON.replace(rest) {
                clocks += LocalTime.NOON
                " "
            }
            rest = MIDNIGHT.replace(rest) {
                clocks += LocalTime.MIDNIGHT
                " "
            }

            val words = SearchText.words(rest)
            val tokens = mutableListOf<String>()
            var now = false
            var next = false
            var tonight = false
            val days = linkedSetOf<DayWord>()
            var i = 0
            while (i < words.size) {
                val word = words[i]
                val following = words.getOrNull(i + 1)
                when {
                    word == "happening" && following == "now" -> { now = true; i++ }
                    word == "now" -> now = true
                    word == "up" && following == "next" -> { next = true; i++ }
                    word == "next" || word == "soon" -> next = true
                    word == "today" -> days += DayWord.Today
                    word == "tonight" -> tonight = true
                    word == "tomorrow" -> days += DayWord.Tomorrow
                    word in WEEKDAYS -> days += DayWord.Weekday(WEEKDAYS.getValue(word))
                    word in WEEKDAY_ABBREVIATIONS && word !in vocabulary ->
                        days += DayWord.Weekday(WEEKDAY_ABBREVIATIONS.getValue(word))
                    else -> tokens += word
                }
                i++
            }
            val time = TimeFilter(now = now, next = next, tonight = tonight, days = days, clocks = clocks.distinct())
            return SearchQuery(tokens.distinct(), time)
        }

        /**
         * The keywords of a question ("Where can I get tacos?" → "tacos"): stop words dropped,
         * time words and clock times kept. Used by the on-device assistant's context retrieval and
         * by AppFunctions, never by the search field. Returns [question] unchanged when nothing
         * but stop words is left.
         */
        fun keywords(question: String): String {
            val words = SearchText.fold(question).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val kept = words.filterNot { word -> SearchText.words(word).all { it in STOP_WORDS } }
            return if (kept.isEmpty()) question else kept.joinToString(" ")
        }
    }
}

/** Evaluates a [TimeFilter] against schedule items, in the event's time zone. */
class TimeMatcher(
    private val filter: TimeFilter,
    private val zone: ZoneId,
    private val now: Instant,
    private val eventDays: List<LocalDate>,
) {
    private val today: LocalDate = now.atZone(zone).toLocalDate()

    /** Dates named by the day words (null = no day word). */
    private val namedDays: Set<LocalDate>? = filter.days.takeIf { it.isNotEmpty() }?.flatMap { day ->
        when (day) {
            DayWord.Today -> listOf(today)
            DayWord.Tomorrow -> listOf(today.plusDays(1))
            is DayWord.Weekday -> eventDays.filter { it.dayOfWeek == day.day }.ifEmpty {
                listOf((0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == day.day })
            }
        }
    }?.toSet()

    fun matches(item: ScheduleItem): Boolean {
        if (filter.isEmpty) return true
        val start = item.startTime?.toInstant() ?: return false
        val end = effectiveEnd(item) ?: return false
        if (filter.now || filter.next) {
            val isNow = !start.isAfter(now) && end.isAfter(now)
            val isNext = start.isAfter(now) && !start.isAfter(now.plus(ScheduleGrouping.UP_NEXT_WINDOW))
            if (!((filter.now && isNow) || (filter.next && isNext))) return false
        }
        if (filter.tonight) {
            val local = start.atZone(zone)
            if (local.toLocalDate() != today || local.toLocalTime().isBefore(TONIGHT_STARTS)) return false
        }
        if (filter.clocks.isNotEmpty()) {
            val days = namedDays ?: (if (filter.tonight) setOf(today) else eventDays.toSet())
            val atClock = days.any { day ->
                filter.clocks.any { clock ->
                    val moment = day.atTime(clock).atZone(zone).toInstant()
                    val inProgress = !start.isAfter(moment) && end.isAfter(moment)
                    val startsSoonAfter = !start.isBefore(moment) && !start.isAfter(moment.plus(CLOCK_WINDOW))
                    inProgress || startsSoonAfter
                }
            }
            if (!atClock) return false
        } else if (namedDays != null) {
            val overlaps = namedDays.any { day ->
                val dayStart = day.atStartOfDay(zone).toInstant()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
                start.isBefore(dayEnd) && end.isAfter(dayStart)
            }
            if (!overlaps) return false
        }
        return true
    }

    companion object {
        val TONIGHT_STARTS: LocalTime = LocalTime.of(17, 0)

        /** A clock time also finds items starting up to this long after it. */
        val CLOCK_WINDOW: Duration = Duration.ofMinutes(30)

        private const val MAX_EVENT_DAYS = 31L

        fun effectiveEnd(item: ScheduleItem): Instant? {
            val start = item.startTime?.toInstant() ?: return null
            val end = item.endTime?.toInstant()
            return if (end != null && end.isAfter(start)) end else start.plus(ScheduleGrouping.DEFAULT_ITEM_DURATION)
        }

        /**
         * The event's calendar days in its zone: start date to end date, else the days schedule items
         * start on, else today.
         */
        fun eventDays(bundle: EventBundle, now: Instant): List<LocalDate> {
            val zone = bundle.event.zone
            val start = bundle.event.startTime
            if (start != null) {
                val first = EventTime.localDate(start, zone)
                val last = bundle.event.endTime?.let { EventTime.localDate(it, zone) }
                    ?.takeIf { !it.isBefore(first) && !it.isAfter(first.plusDays(MAX_EVENT_DAYS)) } ?: first
                return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
            }
            val fromItems = bundle.schedule.mapNotNull { it.startTime?.let { s -> EventTime.localDate(s, zone) } }
                .distinct().sorted()
            return fromItems.ifEmpty { listOf(now.atZone(zone).toLocalDate()) }
        }
    }
}
