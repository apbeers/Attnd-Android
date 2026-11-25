package com.arcu.attnd.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The stored form of a [Plan] in older builds (one JSON text per event in the device's preferences, plus a
 * plan history of such texts). Since the one-list change (Nov 22, 2026) plans are never stored: this only
 * reads an older build's plan once, for the migration into My schedule ([MySchedule.absorb]). Lenient like
 * the API models: unknown keys are ignored, missing ones defaulted, and a broken text decodes to null.
 */
object PlanCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(plan: Plan): String = json.encodeToString(StoredPlan.serializer(), StoredPlan.of(plan))

    fun decode(text: String?): Plan? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(StoredPlan.serializer(), text).toPlan()
        } catch (_: Exception) {
            null
        }
    }

    /** The newest plan of an older build's plan history (a JSON list of [encode] texts); null when none is readable. */
    fun newestInHistory(text: String?): Plan? {
        if (text.isNullOrBlank()) return null
        val list = runCatching { json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrNull() ?: return null
        return list.asReversed().firstNotNullOfOrNull { decode(it) }
    }

    @Serializable
    private data class StoredPlan(
        val eventId: Int = 0,
        val createdAt: String = "",
        val request: String = "",
        val preferences: StoredPreferences = StoredPreferences(),
        val days: List<StoredDay> = emptyList(),
        val fingerprints: Map<String, String> = emptyMap(),
        val constraints: StoredConstraints = StoredConstraints(),
        val checkedOff: List<Int> = emptyList(),
    ) {
        fun toPlan(): Plan? {
            if (eventId == 0) return null
            return Plan(
                eventId = eventId,
                createdAt = instant(createdAt) ?: return null,
                request = request,
                preferences = preferences.toPreferences(),
                days = days.mapNotNull { it.toDay() },
                fingerprints = fingerprints.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
                constraints = constraints.toConstraints(),
                checkedOff = checkedOff.toSet(),
            )
        }

        companion object {
            fun of(plan: Plan) = StoredPlan(
                eventId = plan.eventId,
                createdAt = plan.createdAt.toString(),
                request = plan.request,
                preferences = StoredPreferences.of(plan.preferences),
                days = plan.days.map { StoredDay.of(it) },
                fingerprints = plan.fingerprints.mapKeys { it.key.toString() },
                constraints = StoredConstraints.of(plan.constraints),
                checkedOff = plan.checkedOff.sorted(),
            )
        }
    }

    @Serializable
    private data class StoredPreferences(
        val likedTags: List<String> = emptyList(),
        val dislikedTags: List<String> = emptyList(),
        val likedCategories: List<String> = emptyList(),
        val dislikedCategories: List<String> = emptyList(),
        val similarTo: List<Int> = emptyList(),
        val days: List<String> = emptyList(),
        val earliest: String? = null,
        val latest: String? = null,
        val durationMinutes: Int? = null,
        val pace: String = PlanPace.BALANCED.key,
        val includeMeals: Boolean = false,
        val kidFriendly: Boolean = false,
    ) {
        fun toPreferences() = PlanPreferences(
            likedTags = likedTags.toSet(),
            dislikedTags = dislikedTags.toSet(),
            likedCategories = likedCategories.toSet(),
            dislikedCategories = dislikedCategories.toSet(),
            similarTo = similarTo.toSet(),
            days = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() },
            earliest = earliest?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            latest = latest?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            durationMinutes = durationMinutes,
            pace = PlanPace.of(pace),
            includeMeals = includeMeals,
            kidFriendly = kidFriendly,
        )

        companion object {
            fun of(p: PlanPreferences) = StoredPreferences(
                likedTags = p.likedTags.sorted(),
                dislikedTags = p.dislikedTags.sorted(),
                likedCategories = p.likedCategories.sorted(),
                dislikedCategories = p.dislikedCategories.sorted(),
                similarTo = p.similarTo.sorted(),
                days = p.days.map { it.toString() },
                earliest = p.earliest?.toString(),
                latest = p.latest?.toString(),
                durationMinutes = p.durationMinutes,
                pace = p.pace.key,
                includeMeals = p.includeMeals,
                kidFriendly = p.kidFriendly,
            )
        }
    }

    /** [PlanConstraints]; times as ISO instants / "HH:MM", meal slots as "2026-11-14:lunch". */
    @Serializable
    private data class StoredConstraints(
        val pinned: List<Int> = emptyList(),
        val excluded: List<Int> = emptyList(),
        val noFoodUntil: String? = null,
        val mealsDone: List<String> = emptyList(),
        val startAt: String? = null,
        val doneBy: String? = null,
        val maxWalkMinutes: Int? = null,
        val pace: String? = null,
        val originLocationId: Int? = null,
        val originAt: String? = null,
        val moreTags: List<String> = emptyList(),
        val lessTags: List<String> = emptyList(),
        val groupLikes: Map<String, Int> = emptyMap(),
    ) {
        fun toConstraints() = PlanConstraints(
            pinned = pinned.toSet(),
            excluded = excluded.toSet(),
            noFoodUntil = noFoodUntil?.let { instant(it) },
            mealsDone = mealsDone.mapNotNull { MealSlot.decode(it) }.toSet(),
            startAt = startAt?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            doneBy = doneBy?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            maxWalkMinutes = maxWalkMinutes?.takeIf { it > 0 },
            pace = pace?.let { p -> PlanPace.entries.firstOrNull { it.key == p } },
            origin = originLocationId?.let { id -> originAt?.let { instant(it) }?.let { PlanOrigin(id, it) } },
            moreTags = moreTags.toSet(),
            lessTags = lessTags.toSet(),
            groupLikes = groupLikes.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
        )

        companion object {
            fun of(c: PlanConstraints) = StoredConstraints(
                pinned = c.pinned.sorted(),
                excluded = c.excluded.sorted(),
                noFoodUntil = c.noFoodUntil?.toString(),
                mealsDone = c.mealsDone.map { it.encode() }.sorted(),
                startAt = c.startAt?.toString(),
                doneBy = c.doneBy?.toString(),
                maxWalkMinutes = c.maxWalkMinutes,
                pace = c.pace?.key,
                originLocationId = c.origin?.locationId,
                originAt = c.origin?.at?.toString(),
                moreTags = c.moreTags.sorted(),
                lessTags = c.lessTags.sorted(),
                groupLikes = c.groupLikes.mapKeys { it.key.toString() },
            )
        }
    }

    @Serializable
    private data class StoredDay(
        val date: String = "",
        val windowStart: String = "",
        val windowEnd: String = "",
        val entries: List<StoredEntry> = emptyList(),
    ) {
        fun toDay(): PlanDay? {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            val start = instant(windowStart) ?: return null
            val end = instant(windowEnd) ?: return null
            return PlanDay(day, start, end, entries.mapNotNull { it.toEntry() })
        }

        companion object {
            fun of(day: PlanDay) = StoredDay(
                date = day.date.toString(),
                windowStart = day.windowStart.toString(),
                windowEnd = day.windowEnd.toString(),
                entries = day.entries.map { StoredEntry.of(it) },
            )
        }
    }

    @Serializable
    private data class StoredEntry(
        /** "item" or "meal". */
        val type: String = "item",
        val start: String = "",
        val end: String = "",
        val itemId: Int? = null,
        val reason: String = "",
        val walkMinutes: Int? = null,
        val alternatives: List<Int> = emptyList(),
        val orInstead: List<Int> = emptyList(),
        val meal: String? = null,
        val locationId: Int? = null,
    ) {
        fun toEntry(): PlanEntry? {
            val s = instant(start) ?: return null
            val e = instant(end) ?: return null
            return when (type) {
                "meal" -> MealBreak(
                    kind = MealKind.entries.firstOrNull { it.name.equals(meal, ignoreCase = true) } ?: return null,
                    start = s,
                    end = e,
                    locationId = locationId,
                )
                else -> PlannedItem(
                    itemId = itemId ?: return null,
                    start = s,
                    end = e,
                    reason = reason,
                    walkMinutes = walkMinutes,
                    alternatives = alternatives,
                    orInstead = orInstead,
                )
            }
        }

        companion object {
            fun of(entry: PlanEntry) = when (entry) {
                is PlannedItem -> StoredEntry(
                    type = "item", start = entry.start.toString(), end = entry.end.toString(), itemId = entry.itemId,
                    reason = entry.reason, walkMinutes = entry.walkMinutes, alternatives = entry.alternatives, orInstead = entry.orInstead,
                )
                is MealBreak -> StoredEntry(
                    type = "meal", start = entry.start.toString(), end = entry.end.toString(),
                    meal = entry.kind.name.lowercase(), locationId = entry.locationId,
                )
            }
        }
    }

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
