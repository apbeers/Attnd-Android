package com.arcu.attnd.domain

import kotlin.math.ln
import kotlin.math.sqrt

/** How alike two schedule items are, 0 (nothing shared) … 1 (same words). Used by the [Planner]. */
fun interface ItemSimilarity {
    fun similarity(a: ScheduleItem, b: ScheduleItem): Double
}

/**
 * TF-IDF cosine similarity over the schedule (shared spec §6, the same on iOS so plans match).
 *
 * - Document of an item: its name, tags, its location's category, description and location name
 *   (the bundle's location name, else the item's `location_name`), joined with spaces.
 * - Terms: [SearchText.words] (lower case, accents folded, punctuation → spaces), keeping words of 2+
 *   characters that are not in [SearchQuery.STOP_WORDS].
 * - Corpus: every schedule item in the bundle (N items). `df(t)` = items containing t;
 *   `idf(t) = ln((1 + N) / (1 + df(t))) + 1` (natural log); weight = raw count × idf.
 * - Similarity = cosine of the two weight vectors; 0 when either has no terms. An item is 1 with itself.
 *
 * ML Kit GenAI has an on-device embedding API (`genai-embedding` 1.0.0-alpha1, Oct 2026), but it is an
 * alpha whose availability can't be tested here, and iOS would use a different model: TF-IDF keeps plans
 * identical on both platforms and testable. Swap in another [ItemSimilarity] later if wanted.
 */
class TfIdfSimilarity(bundle: EventBundle) : ItemSimilarity {

    private val vectors: Map<Int, Map<String, Double>>
    private val norms: Map<Int, Double>

    init {
        val docs: Map<Int, List<String>> = bundle.schedule.associate { it.id to terms(document(bundle, it)) }
        val n = docs.size
        val df = HashMap<String, Int>()
        docs.values.forEach { words -> words.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        vectors = docs.mapValues { (_, words) ->
            val counts = words.groupingBy { it }.eachCount()
            counts.mapValues { (term, count) -> count * (ln((1.0 + n) / (1.0 + (df[term] ?: 0))) + 1.0) }
        }
        // Summed in sorted term order so the result is the same on every platform.
        norms = vectors.mapValues { (_, v) -> sqrt(v.keys.sorted().sumOf { v.getValue(it) * v.getValue(it) }) }
    }

    override fun similarity(a: ScheduleItem, b: ScheduleItem): Double {
        if (a.id == b.id) return 1.0
        val va = vectors[a.id] ?: return 0.0
        val vb = vectors[b.id] ?: return 0.0
        val na = norms[a.id] ?: 0.0
        val nb = norms[b.id] ?: 0.0
        if (na == 0.0 || nb == 0.0) return 0.0
        val dot = va.keys.filter { it in vb }.sorted().sumOf { va.getValue(it) * vb.getValue(it) }
        return (dot / (na * nb)).coerceIn(0.0, 1.0)
    }

    companion object {
        fun document(bundle: EventBundle, item: ScheduleItem): String {
            val location = bundle.location(item.locationId)
            return listOf(
                item.name,
                item.tags.joinToString(" "),
                location?.category.orEmpty(),
                item.description,
                location?.name ?: item.locationName.orEmpty(),
            ).joinToString(" ")
        }

        fun terms(text: String): List<String> =
            SearchText.words(text).filter { it.length >= 2 && it !in SearchQuery.STOP_WORDS }
    }
}
