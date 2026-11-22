package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The assistant's memory of what it showed (a 1:1 port of iOS `ConversationContext.swift`, same cases in
 * `ConversationContextTest`): each answer records the entities it showed, in order, the focus entity (what
 * the turn acted on, else the first shown) and the last action. The router resolves references against it
 * before its other rules: "it" / "that" / "this one" / "there" / "here" → the focus; "them" / "both" → the
 * shown ones; "the first" / "second" / "last" / "option 3" / "number 2" → by position; a description ("the
 * taco place") → the shown entity it fits. Stored per event with the conversation (≤ 40 turns).
 */
data class ConversationContext(
    /** Entities of the last answer that showed any, in order. */
    val shown: List<EntityRef> = emptyList(),
    /** What the last turn was about (an action's target, else the first shown). */
    val focus: EntityRef? = null,
    /** The last action's name ("schedule_add"). */
    val lastAction: String? = null,
) {
    val isEmpty: Boolean get() = shown.isEmpty() && focus == null

    /**
     * Records an answer: its blocks' entities in order and the focus. A turn that shows nothing (a sentence,
     * chips) keeps the earlier entities, so "add it" still works after "When?".
     */
    fun record(turn: AssistantTurn): ConversationContext {
        val refs = turn.blocks.flatMap(::refs).distinct()
        val acted = turn.actions.firstNotNullOfOrNull(::target)
        return ConversationContext(
            shown = if (refs.isNotEmpty()) refs.take(MAX_SHOWN) else shown,
            focus = acted ?: refs.firstOrNull() ?: focus,
            lastAction = turn.actions.firstOrNull()?.name?.key ?: lastAction,
        )
    }

    /** The entities [phrase] refers to (of [kinds]), null when it isn't a reference to what was shown. */
    fun resolve(phrase: String, kinds: Set<RefKind>, bundle: EventBundle): List<EntityRef>? {
        val text = AssistantRouter.command(phrase)
        if (text.isEmpty() || (shown.isEmpty() && focus == null)) return null
        val candidates = shown.filter { it.kind in kinds && it.exists(bundle) }
        if (text in SINGULAR) {
            focus?.takeIf { it.kind in kinds && it.exists(bundle) }?.let { return listOf(it) }
            return candidates.firstOrNull()?.let { listOf(it) }
        }
        if (text in PLURAL) {
            if (candidates.isEmpty()) return null
            return if (text.startsWith("both")) candidates.take(2) else candidates
        }
        position(text, shown.size)?.let { index ->
            val ref = shown.getOrNull(index) ?: return null
            return if (ref.kind in kinds && ref.exists(bundle)) listOf(ref) else null
        }
        // A description: every content word appears in a shown entity's name, category or tags.
        val words = text.split(' ').filter { it !in GENERIC }
        if (!(text.startsWith("the ") || text.startsWith("that ") || text.startsWith("this ")) || words.isEmpty()) return null
        val fits = candidates.filter { ref ->
            val haystack = SearchText.normalize(description(ref, bundle))
            val hay = SearchText.words(haystack).toSet()
            words.all { word ->
                word in hay || "${word}s" in hay || (word.endsWith("s") && word.dropLast(1) in hay) || haystack.contains(word)
            }
        }
        return fits.ifEmpty { null }
    }

    fun encode(): JsonObject = buildJsonObject {
        put("shown", JsonArray(shown.map { JsonPrimitive(it.code) }))
        focus?.let { put("focus", it.code) }
        lastAction?.let { put("last_action", it) }
    }

    companion object {
        const val MAX_SHOWN = 12

        private val SINGULAR = setOf(
            "it", "that", "this", "this one", "that one", "the one", "there", "here", "that place", "this place",
            "that thing", "this thing", "the place", "that show", "this show", "that spot", "it there",
        )
        private val PLURAL = setOf("them", "both", "both of them", "those", "these", "all of them", "all of those", "all three", "all")
        private val ORDINALS = mapOf(
            "first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2, "fourth" to 3, "4th" to 3,
            "fifth" to 4, "5th" to 4, "sixth" to 5, "6th" to 5, "seventh" to 6, "eighth" to 7, "ninth" to 8, "tenth" to 9,
        )
        private val NUMBERS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8)

        /** Words a description may carry that say nothing about which entity it is. */
        private val GENERIC = setOf("the", "a", "an", "one", "place", "spot", "thing", "that", "this", "show", "option", "of", "those", "these")

        /** The entities a block shows, in order (cards, lists, timelines, pins, chips' targets). */
        fun refs(block: AssistantBlock): List<EntityRef> = when (block) {
            is AssistantBlock.NowNext -> listOfNotNull(block.location?.let { EntityRef(RefKind.LOCATION, it) })
            is AssistantBlock.Compare -> listOfNotNull(EntityRef.parse(block.a), EntityRef.parse(block.b))
            is AssistantBlock.ComponentBlock -> block.component.ids
            is AssistantBlock.Chips -> block.chips.mapNotNull { target(it.action) }
            is AssistantBlock.Confirm -> listOfNotNull(target(block.action))
            else -> AssistantFlow.refs(block)
        }

        /** The entity an action is about (`target`, `id` with `kind`, `item`, `location`). */
        fun target(action: AssistantAction): EntityRef? {
            EntityRef.of(action.params["target"])?.let { return it }
            action.likeTarget()?.let { (kind, id) -> return EntityRef(RefKind.of(kind) ?: RefKind.SCHEDULE, id) }
            action.id("item", 's')?.let { return EntityRef(RefKind.SCHEDULE, it) }
            action.id("location", 'l')?.let { return EntityRef(RefKind.LOCATION, it) }
            return null
        }

        /** "the first (one)", "second", "the last one", "option 3", "number 2", "#2" → index (a bare "3" is not a position). */
        fun position(text: String, count: Int): Int? {
            var words = text.split(' ').filter { it.isNotEmpty() }
            val explicit = "option" in words || "number" in words
            words = words.filter { it !in setOf("the", "please", "option", "number", "no") }
            if (explicit && words == listOf("one")) return 0
            words = words.filter { it != "one" }
            val word = words.singleOrNull() ?: return null
            if (word == "last") return if (count > 0) count - 1 else null
            ORDINALS[word]?.let { return it }
            if (explicit) {
                val n = word.toIntOrNull() ?: NUMBERS[word]
                if (n != null && n >= 1) return n - 1
            }
            return null
        }

        private fun description(ref: EntityRef, bundle: EventBundle): String = when (ref.kind) {
            RefKind.SCHEDULE -> bundle.scheduleItem(ref.id)?.let { item ->
                (listOf(item.name, item.locationName.orEmpty(), bundle.location(item.locationId)?.name.orEmpty()) + item.tags).joinToString(" ")
            }.orEmpty()
            RefKind.LOCATION -> bundle.location(ref.id)?.let { l ->
                (listOf(l.name, l.category.orEmpty(), l.snippet) + l.tags).joinToString(" ")
            }.orEmpty()
            RefKind.UPDATE -> bundle.notifications.firstOrNull { it.id == ref.id }?.title.orEmpty()
        }

        fun decode(element: JsonElement?): ConversationContext {
            val obj = element as? JsonObject ?: return ConversationContext()
            return ConversationContext(
                shown = (obj["shown"] as? JsonArray).orEmpty().mapNotNull { EntityRef.of(it) }.take(MAX_SHOWN),
                focus = EntityRef.of(obj["focus"]),
                lastAction = (obj["last_action"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }
}
