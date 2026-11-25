package com.arcu.attnd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** A decoded share payload (API-015). [version] > 1 can't be read: join the event only. */
data class SharedPayload(val version: Int, val ids: List<Int>, val day: LocalDate?) {
    val isReadable: Boolean get() = version == 1
}

/**
 * Shared plan / item links (assistant spec §7, attnd-web API-015, `API_V2.md` "shared plan links"):
 * `https://attnd.app/p/<EVENT_CODE>/<payload>`, payload = base64url without padding of compact JSON
 * with keys in the order v, ids, d: `{"v":1,"ids":[101,114],"d":"2026-11-14"}` →
 * `eyJ2IjoxLCJpZHMiOlsxMDEsMTE0XSwiZCI6IjIwMjYtMTEtMTQifQ`; at most 1,500 characters (ids are dropped
 * from the end until it fits). No personal data. Decoding is lenient like the server's: padding and
 * the standard alphabet are accepted, ids may be "s12", a malformed `d` is ignored; not base64url JSON,
 * `ids` not a list or too long → null.
 */
object PlanShare {

    const val MAX_PAYLOAD = 1500
    const val BASE = "https://attnd.app/p/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The exact JSON text (no spaces; v, ids, d). */
    fun json(ids: List<Int>, day: LocalDate?): String =
        "{\"v\":1,\"ids\":[${ids.joinToString(",")}]" + (day?.let { ",\"d\":\"$it\"" } ?: "") + "}"

    fun payload(ids: List<Int>, day: LocalDate? = null): String {
        var kept = ids.distinct()
        while (true) {
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json(kept, day).toByteArray(Charsets.UTF_8))
            if (encoded.length <= MAX_PAYLOAD || kept.isEmpty()) return encoded
            kept = kept.dropLast(1)
        }
    }

    fun link(eventCode: String, ids: List<Int>, day: LocalDate? = null): String = BASE + eventCode.trim() + "/" + payload(ids, day)

    /** `share_plan`: the plan's items in plan order; `d` = its day when it has exactly one day with items. */
    fun planLink(plan: Plan, bundle: EventBundle): String {
        val days = plan.days.filter { it.items.isNotEmpty() }
        return link(bundle.event.eventCode, plan.itemIds, days.singleOrNull()?.date)
    }

    fun itemLink(itemId: Int, bundle: EventBundle): String = link(bundle.event.eventCode, listOf(itemId))

    /**
     * My Schedule's Share (iOS `shareLink("schedule")`): every schedule item in My schedule in time order (start, then
     * id), no day; places aren't in the payload (API-015 shares items). Null when there is no item to share.
     */
    fun scheduleLink(schedule: MySchedule, bundle: EventBundle): String? {
        val ids = schedule.itemIds
        val items = bundle.schedule.filter { it.id in ids }.sortedWith(compareBy({ it.startTime?.toInstant() ?: java.time.Instant.MAX }, { it.id })).map { it.id }
        return if (items.isEmpty()) null else link(bundle.event.eventCode, items)
    }

    fun decode(payload: String?): SharedPayload? {
        val text = payload?.trim()?.trimEnd('/') ?: return null
        if (text.isEmpty() || text.length > MAX_PAYLOAD + 2) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(text.replace('+', '-').replace('/', '_'))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val obj = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val version = (obj["v"] as? JsonPrimitive)?.intOrNull ?: return null
        if (version < 1) return null
        if (version > 1) return SharedPayload(version, emptyList(), null)
        val ids = (obj["ids"] as? JsonArray ?: return null).mapNotNull { EntityRef.idOf(it, 's') }.distinct()
        val day = (obj["d"] as? JsonPrimitive)?.content?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        return SharedPayload(version, ids, day)
    }

    /** The shared ids still in [bundle] (others are gone). */
    fun resolve(shared: SharedPayload, bundle: EventBundle): List<Int> = shared.ids.filter { bundle.scheduleItem(it) != null }
}

/**
 * Group plans (spec §7): several shared plans plus the attendee's own likes → the union of liked
 * items, each weighted +1 per person who liked or planned it ([PlanConstraints.groupLikes], rule
 * 13/20), then the normal planner. The attendee counts as one person (their liked schedule items);
 * each shared plan is one person (its ids, distinct). Ids not in the bundle are dropped.
 */
object GroupPlan {

    fun merge(shared: List<List<Int>>, own: Hearts, bundle: EventBundle): Pair<Hearts, Map<Int, Int>> {
        val counts = HashMap<Int, Int>()
        (listOf(own.scheduleItems.toList()) + shared).forEach { person ->
            person.distinct().filter { bundle.scheduleItem(it) != null }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return own.copy(scheduleItems = counts.keys.toSet()) to counts.toSortedMap()
    }

    fun plan(shared: List<List<Int>>, own: Hearts, bundle: EventBundle, preferences: PlanPreferences, now: Instant, request: String = ""): Plan {
        val (hearts, counts) = merge(shared, own, bundle)
        return Planner(bundle, preferences, hearts, PlanConstraints(groupLikes = counts)).plan(now, request)
    }
}
