package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A suggestion chip for onboarding / the plan card; [isPlan] chips make a plan, others are questions. */
data class PlanExample(val text: String, val isPlan: Boolean)

/**
 * Turning an attendee's request ("Plan my Saturday around live music and BBQ") into [PlanPreferences].
 *
 * The on-device model (Gemini Nano) only extracts preferences: [prompt] asks it for one JSON object,
 * [parse] reads it leniently and validates every value against the bundle (unknown tags, categories,
 * ids and days are dropped). [heuristic] reads the same request with plain rules; it fills whatever the
 * model left empty ([merge]) and replaces the model entirely when its reply can't be read.
 */
object PlanRequest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val PLAN_WORDS = Regex(
        """\b(plan|planner|planning|itinerary|highlights|schedule me|build me|my (day|morning|afternoon|evening|night|weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b|\b\d+(\.\d)? ?(hours?|hrs?)\b|\b(an|one|two|three|four|five|six|a few|a couple of) hours?\b|\b(kid|kids|family) friendly (morning|afternoon|evening|day)\b"""
    )

    /** Whether an Ask request should make a plan rather than answer a question. */
    fun looksLikePlan(request: String): Boolean = PLAN_WORDS.containsMatchIn(SearchText.normalize(request))

    // ── Model prompt ────────────────────────────────────────────────────────

    const val MAX_ITEMS_IN_PROMPT = 60
    const val MAX_TAGS_IN_PROMPT = 40

    fun prompt(bundle: EventBundle, request: String, hearts: Hearts, now: Instant): String {
        val zone = bundle.event.zone
        val local = now.atZone(zone)
        val days = TimeMatcher.eventDays(bundle, now).filter { !it.isBefore(local.toLocalDate()) }
        val items = bundle.schedule.filter { it.startTime != null && TimeMatcher.effectiveEnd(it)!!.isAfter(now) }
            .sortedWith(Planner.ITEM_ORDER)
            .take(MAX_ITEMS_IN_PROMPT)
        val hearted = bundle.schedule.filter { it.id in hearts.scheduleItems }.sortedWith(Planner.ITEM_ORDER)
        return buildString {
            appendLine("You help plan a visit to the event \"${bundle.event.name}\".")
            appendLine("Read the request and reply with only one JSON object and no other text, in this form:")
            appendLine("""{"liked_tags":[],"disliked_tags":[],"liked_categories":[],"disliked_categories":[],"similar_to":[],"days":[],"earliest":null,"latest":null,"hours":null,"pace":"balanced","include_meals":false,"kid_friendly":false}""")
            appendLine("Rules:")
            appendLine("- liked_tags and disliked_tags: only words from TAGS. liked_categories and disliked_categories: only from CATEGORIES.")
            appendLine("- similar_to: the numbers of ITEMS the request names or wants more of.")
            appendLine("- days: dates from DAYS the request asks for, as YYYY-MM-DD; [] when it names none.")
            appendLine("- earliest and latest: \"HH:MM\" (24-hour) when the request gives times. hours: how many hours it has, as a number.")
            appendLine("- pace: \"relaxed\", \"balanced\" or \"packed\".")
            appendLine("- include_meals: true when it mentions lunch, dinner or food breaks. kid_friendly: true for kids or family.")
            appendLine("NOW: ${local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} ${local.toLocalDate()} ${"%02d:%02d".format(local.hour, local.minute)}")
            appendLine("DAYS: " + days.joinToString(", ") { "$it ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}" })
            appendLine("TAGS: " + tagVocabulary(bundle).take(MAX_TAGS_IN_PROMPT).joinToString(", "))
            appendLine("CATEGORIES: " + categories(bundle).joinToString(", "))
            if (hearted.isNotEmpty()) appendLine("HEARTED: " + hearted.joinToString("; ") { "${it.id} ${it.name}" })
            appendLine("ITEMS: " + items.joinToString("; ") { "${it.id} ${it.name}" + (if (it.tags.isEmpty()) "" else " (${it.tags.joinToString(", ")})") })
            appendLine("REQUEST: ${request.trim()}")
            append("JSON:")
        }
    }

    /** Tags of schedule items and locations, lower case, by how many use them (ties alphabetical). */
    fun tagVocabulary(bundle: EventBundle): List<String> {
        val counts = HashMap<String, Int>()
        (bundle.schedule.flatMap { it.tags } + bundle.locations.flatMap { it.tags })
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }

    /** The bundle's categories plus any location category it doesn't list, in that order, without duplicates. */
    fun categories(bundle: EventBundle): List<String> {
        val seen = LinkedHashMap<String, String>()
        (bundle.categories + bundle.locations.mapNotNull { it.category })
            .filter { it.isNotBlank() }
            .forEach { seen.putIfAbsent(it.lowercase(), it) }
        return seen.values.toList()
    }

    // ── Reading the model's reply ───────────────────────────────────────────

    /** The model's JSON (the first `{` to the last `}`), validated against [bundle]; null when unreadable. */
    fun parse(raw: String, bundle: EventBundle, now: Instant): PlanPreferences? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val tagSet = tagVocabulary(bundle).toSet()
        val categoryByLower = categories(bundle).associateBy { it.lowercase() }
        val ids = bundle.schedule.map { it.id }.toSet()
        val days = TimeMatcher.eventDays(bundle, now).toSet()

        fun strings(key: String): List<String> = (obj[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNullString() }
        fun tags(key: String) = strings(key).map { it.trim().lowercase() }.filter { it in tagSet }.toSet()
        fun cats(key: String) = strings(key).mapNotNull { categoryByLower[it.trim().lowercase()] }.toSet()

        val similar = (obj["similar_to"] as? JsonArray).orEmpty().mapNotNull { element ->
            val p = element as? JsonPrimitive ?: return@mapNotNull null
            p.intOrNull ?: p.contentOrNullString()?.trim()?.trimStart('S', 's', '[')?.trimEnd(']')?.toIntOrNull()
        }.filter { it in ids }.toSet()

        return PlanPreferences(
            likedTags = tags("liked_tags"),
            dislikedTags = tags("disliked_tags"),
            likedCategories = cats("liked_categories"),
            dislikedCategories = cats("disliked_categories"),
            similarTo = similar,
            days = strings("days").mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }.filter { it in days }.distinct().sorted(),
            earliest = clock(obj["earliest"]),
            latest = clock(obj["latest"]),
            durationMinutes = hours(obj["hours"]),
            pace = PlanPace.of((obj["pace"] as? JsonPrimitive)?.contentOrNullString()),
            includeMeals = bool(obj["include_meals"]),
            kidFriendly = bool(obj["kid_friendly"]),
        )
    }

    private fun JsonPrimitive.contentOrNullString(): String? = if (this is JsonNull) null else content

    private fun clock(element: JsonElement?): LocalTime? {
        val text = (element as? JsonPrimitive)?.contentOrNullString()?.trim() ?: return null
        val match = Regex("""^(\d{1,2}):(\d{2})""").find(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    private fun hours(element: JsonElement?): Int? {
        val value = (element as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNullString()?.trim()?.toDoubleOrNull() } ?: return null
        return if (value > 0 && value <= 24) (value * 60).roundToInt() else null
    }

    private fun bool(element: JsonElement?): Boolean {
        val p = element as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.contentOrNullString()?.trim()?.lowercase() == "true")
    }

    // ── Plain-rule reading ──────────────────────────────────────────────────

    private val NEGATIONS = listOf("no", "not", "without", "avoid", "skip", "except", "hate", "dislike")
    private val RELAXED_WORDS = setOf("relaxed", "relax", "relaxing", "chill", "easy", "slow", "laid back", "lazy")
    private val PACKED_WORDS = setOf("packed", "busy", "everything", "as much as", "lots", "full day", "nonstop", "non stop")
    private val MEAL_WORDS = setOf("lunch", "dinner", "eat", "eating", "meal", "meals", "food break", "food breaks", "snack", "brunch")
    private val KID_WORDS = setOf("kid", "kids", "child", "children", "family", "toddler", "toddlers")
    private val HOURS = Regex("""\b(\d+(?:\.\d+)?) ?(?:hours?|hrs?)\b""")
    private val WORD_HOURS = mapOf("an" to 60, "one" to 60, "a couple of" to 120, "two" to 120, "a few" to 180, "three" to 180, "four" to 240, "five" to 300, "six" to 360)
    private val NOON: LocalTime = LocalTime.of(12, 0)
    private val EVENING: LocalTime = LocalTime.of(17, 0)
    private val BOUND = Regex("""\b(after|from|before|until|till|til|by) (\d{1,2})(?: (\d{2}))? ?(am|pm|a m|p m)?\b""")

    /**
     * The request read with plain rules (normalised text, whole-phrase matches): event tags and
     * categories it mentions (after "no"/"not"/"without"/"avoid"/"skip"/"except"/"hate"/"dislike" →
     * disliked), schedule item names of 4+ characters (→ similar_to), weekdays/"today"/"tomorrow" that
     * are event days, "after/from 3pm" (earliest) and "before/until/by 9pm" (latest; an hour 1–7
     * without am/pm is pm), else "morning" (latest 12:00), "afternoon" (12:00–17:00),
     * "evening"/"tonight"/"night" (earliest 17:00); "3 hours"/"a couple of hours", pace words, meal
     * words, kid words.
     */
    fun heuristic(request: String, bundle: EventBundle, now: Instant): PlanPreferences {
        val text = " " + SearchText.normalize(request).trim() + " "
        val liked = mutableSetOf<String>()
        val disliked = mutableSetOf<String>()
        for (tag in tagVocabulary(bundle)) {
            val phrase = SearchText.normalize(tag).trim()
            if (phrase.isEmpty()) continue
            when (mention(text, phrase)) {
                Mention.LIKED -> liked += tag
                Mention.DISLIKED -> disliked += tag
                null -> Unit
            }
        }
        val likedCats = mutableSetOf<String>()
        val dislikedCats = mutableSetOf<String>()
        for (category in categories(bundle)) {
            when (mention(text, SearchText.normalize(category).trim())) {
                Mention.LIKED -> likedCats += category
                Mention.DISLIKED -> dislikedCats += category
                null -> Unit
            }
        }
        val similar = bundle.schedule.filter { item ->
            val name = SearchText.normalize(item.name).trim()
            name.length >= 4 && text.contains(" $name ")
        }.map { it.id }.toSet()

        val zone = bundle.event.zone
        val today = now.atZone(zone).toLocalDate()
        val eventDays = TimeMatcher.eventDays(bundle, now)
        val days = linkedSetOf<LocalDate>()
        if (text.contains(" today ") || text.contains(" tonight ")) days += today
        if (text.contains(" tomorrow ")) days += today.plusDays(1)
        DayOfWeek.entries.forEach { dow ->
            if (text.contains(" " + dow.getDisplayName(TextStyle.FULL, Locale.US).lowercase() + " ")) {
                eventDays.filter { it.dayOfWeek == dow && !it.isBefore(today) }.firstOrNull()?.let { days += it }
            }
        }

        var earliest: LocalTime? = null
        var latest: LocalTime? = null
        BOUND.findAll(text).forEach { match ->
            var hour = match.groupValues[2].toInt()
            val minute = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val suffix = match.groupValues[4].replace(" ", "")
            if (hour > 23 || minute > 59) return@forEach
            hour = when {
                suffix == "pm" && hour < 12 -> hour + 12
                suffix == "am" && hour == 12 -> 0
                suffix.isEmpty() && hour in 1..7 -> hour + 12
                else -> hour
            }
            val time = LocalTime.of(hour % 24, minute)
            when (match.groupValues[1]) {
                "after", "from" -> earliest = time
                else -> latest = time
            }
        }

        // Parts of the day, only where no explicit time was given.
        when {
            text.contains(" morning ") -> if (latest == null) latest = NOON
            text.contains(" afternoon ") -> {
                if (earliest == null) earliest = NOON
                if (latest == null) latest = EVENING
            }
            text.contains(" evening ") || text.contains(" tonight ") || text.contains(" night ") ->
                if (earliest == null) earliest = EVENING
        }

        val duration = HOURS.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 && it <= 24 }?.let { (it * 60).roundToInt() }
            ?: WORD_HOURS.entries.firstOrNull { text.contains(" ${it.key} hour") }?.value

        val pace = when {
            RELAXED_WORDS.any { text.contains(" $it ") } -> PlanPace.RELAXED
            PACKED_WORDS.any { text.contains(" $it ") } -> PlanPace.PACKED
            else -> PlanPace.BALANCED
        }
        return PlanPreferences(
            likedTags = liked,
            dislikedTags = disliked,
            likedCategories = likedCats,
            dislikedCategories = dislikedCats,
            similarTo = similar,
            days = days.filter { it in eventDays }.sorted(),
            earliest = earliest,
            latest = latest,
            durationMinutes = duration,
            pace = pace,
            includeMeals = MEAL_WORDS.any { text.contains(" $it ") },
            kidFriendly = KID_WORDS.any { text.contains(" $it ") },
        )
    }

    private enum class Mention { LIKED, DISLIKED }

    /** Whether [phrase] (or its plural) appears as whole words, and whether a negation word is right before it. */
    private fun mention(text: String, phrase: String): Mention? {
        if (phrase.isEmpty()) return null
        for (form in listOf(phrase, phrase + "s")) {
            val index = text.indexOf(" $form ")
            if (index < 0) continue
            val before = text.substring(0, index).trim().split(' ').takeLast(2)
            return if (before.any { it in NEGATIONS }) Mention.DISLIKED else Mention.LIKED
        }
        return null
    }

    /**
     * The model's preferences completed by the plain-rule reading: tag/category/similar sets are
     * unions (a tag both liked and disliked stays disliked only), days/times/duration come from the
     * model when it gave them, the pace is the model's unless it said balanced, and the yes/no
     * preferences are true when either says so.
     */
    fun merge(model: PlanPreferences?, rules: PlanPreferences): PlanPreferences {
        if (model == null) return rules
        val disliked = model.dislikedTags + rules.dislikedTags
        val dislikedCats = model.dislikedCategories + rules.dislikedCategories
        return PlanPreferences(
            likedTags = (model.likedTags + rules.likedTags) - disliked,
            dislikedTags = disliked,
            likedCategories = (model.likedCategories + rules.likedCategories) - dislikedCats,
            dislikedCategories = dislikedCats,
            similarTo = model.similarTo + rules.similarTo,
            days = model.days.ifEmpty { rules.days },
            earliest = model.earliest ?: rules.earliest,
            latest = model.latest ?: rules.latest,
            durationMinutes = model.durationMinutes ?: rules.durationMinutes,
            pace = if (model.pace != PlanPace.BALANCED) model.pace else rules.pace,
            includeMeals = model.includeMeals || rules.includeMeals,
            kidFriendly = model.kidFriendly || rules.kidFriendly,
        )
    }

    // ── Examples ────────────────────────────────────────────────────────────

    /** Tags that say little about taste and never appear in the example chips. */
    private val GENERIC_TAGS = setOf("general", "free", "music", "demo") + Planner.FAMILY_TAGS

    /**
     * Example requests made from this event's data (onboarding and the empty plan card), in order:
     * "Plan my <weekday> around <tag 1> and <tag 2>" (the two most used schedule tags, generic ones
     * skipped; "around <tag>" with one, "Plan my <weekday>" with none; the weekday is the first event
     * day on or after today), "I've got 3 hours, show me the highlights", "Kid-friendly afternoon"
     * (only when an item has a family tag) and the question "What's on near me right now?".
     */
    fun examples(bundle: EventBundle, now: Instant): List<PlanExample> {
        val zone = bundle.event.zone
        val today = now.atZone(zone).toLocalDate()
        val day = TimeMatcher.eventDays(bundle, now).firstOrNull { !it.isBefore(today) } ?: today
        val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        val counts = HashMap<String, Int>()
        bundle.schedule.flatMap { it.tags.map { tag -> tag.trim().lowercase() }.distinct() }
            .filter { it.isNotEmpty() && it !in GENERIC_TAGS }
            .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }.take(2)
        val first = when (top.size) {
            0 -> "Plan my $weekday"
            1 -> "Plan my $weekday around ${top[0]}"
            else -> "Plan my $weekday around ${top[0]} and ${top[1]}"
        }
        val hasFamily = bundle.schedule.any { item -> item.tags.any { it.lowercase() in Planner.FAMILY_TAGS } }
        return buildList {
            add(PlanExample(first, isPlan = true))
            add(PlanExample("I've got 3 hours, show me the highlights", isPlan = true))
            if (hasFamily) add(PlanExample("Kid-friendly afternoon", isPlan = true))
            add(PlanExample("What's on near me right now?", isPlan = false))
        }
    }
}
