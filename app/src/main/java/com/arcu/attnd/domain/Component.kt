package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * One component catalogue (a 1:1 port of iOS `Component.swift`, tests `ComponentTest`): the assistant's blocks
 * (`AssistantBlock`, spec §2) and the API-024 / API-027 layout sections (`SectionCatalogue`) are one set of
 * `Component` types. Each type says which screens may place it (with what it needs and its parts) and whether a chat
 * answer may show it. Server layouts and the model's `show` tool produce the same `Component` values; the one
 * renderer (phase 5b) draws them all.
 *
 *   - Lenient: unknown types are skipped; older names map across (`ALIASES`: `plan_today` / `liked` → `my_schedule`).
 *   - `SectionCatalogue.types` (the layout side) is built from this catalogue; a new layout type bumps
 *     `SectionCatalogue.VERSION`.
 *   - In a chat answer a screen type is the block `AssistantBlock.ComponentBlock`: its subject is in `ids` ("l3" for a
 *     place's menu, "s12" for an item's performers, none for event-wide ones).
 */

/** One component: a type and its props. Chat-only payloads (text, chips, confirm…) keep their block in `block`. */
data class Component(
    val type: String,
    val ids: List<EntityRef> = emptyList(),
    val title: String? = null,
    val limit: Int? = null,
    val show: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    val block: AssistantBlock? = null,
) {
    /** The subject of a detail-style component (a place's menu, an item's performers). */
    val subject: EntityRef? get() = ids.firstOrNull()
}

/** What one component type is allowed to do. */
data class ComponentSpec(
    /** Screens a layout may place it on, with what it needs there and its parts. */
    val screens: Map<LayoutScreen, SectionType> = emptyMap(),
    /** A chat answer may show it. */
    val chat: Boolean = false,
    /** The kind of subject it needs in chat (null: event-wide, no id). */
    val subject: RefKind? = null,
)

object ComponentCatalogue {
    /** Older names that map to a current type (first wins when both are sent). */
    val ALIASES: Map<String, String> = mapOf("plan_today" to "my_schedule", "liked" to "my_schedule")

    fun canonical(type: String): String {
        val name = type.trim().lowercase()
        return ALIASES[name] ?: name
    }

    private fun s(needs: List<String> = emptyList(), parts: List<String> = emptyList(), show: List<String>? = null) =
        SectionType(needs, parts, show ?: parts)

    val ITEM_HEADER_PARTS = listOf("time", "status", "tags")
    val PLACE_HEADER_PARTS = listOf("image", "category", "tags", "address", "distance", "now_next")

    /** Catalogue 2's list row parts (schedule items, places). */
    val LIST_ITEM_PARTS = listOf("time", "place", "tags", "walk", "progress", "add")
    val LIST_PLACE_PARTS = listOf("image", "category", "now_next", "distance", "events")

    private fun chat(subject: RefKind? = null) = ComponentSpec(chat = true, subject = subject)

    /** Every type. Block types that are chat-only have no screens. */
    val SPECS: Map<String, ComponentSpec> = mapOf(
        // Chat blocks (assistant spec §2)
        "text" to chat(), "item_card" to chat(), "place_card" to chat(), "update_card" to chat(), "list" to chat(),
        "carousel" to chat(),
        "timeline" to ComponentSpec(mapOf(LayoutScreen.SCHEDULE_TAB to s(listOf("schedule"), LIST_ITEM_PARTS)), chat = true),
        "gallery" to chat(), "compare" to chat(), "plan_diff" to chat(),
        "plan" to ComponentSpec(mapOf(LayoutScreen.ONBOARDING to s()), chat = true),
        "setting" to chat(), "summary" to chat(), "share" to chat(), "chips" to chat(), "confirm" to chat(),
        "map" to ComponentSpec(mapOf(LayoutScreen.HOME to s(listOf("pins"))), chat = true),
        "countdown" to ComponentSpec(mapOf(LayoutScreen.ITEM_DETAIL to s(listOf("upcoming")), LayoutScreen.HOME to s(listOf("before_start"))), chat = true),
        "now_next" to ComponentSpec(mapOf(LayoutScreen.PLACE_DETAIL to s(listOf("items_here")), LayoutScreen.HOME to s(listOf("schedule"))), chat = true),
        // Screens and chat
        "header" to ComponentSpec(
            mapOf(
                LayoutScreen.ITEM_DETAIL to s(parts = ITEM_HEADER_PARTS), LayoutScreen.PLACE_DETAIL to s(parts = PLACE_HEADER_PARTS),
                LayoutScreen.EVENT_INFO to s(parts = listOf("website")), LayoutScreen.OPTIONS_EVENT to s(), LayoutScreen.HOME to s(),
            ),
        ),
        "about" to ComponentSpec(
            listOf(LayoutScreen.ITEM_DETAIL, LayoutScreen.PLACE_DETAIL, LayoutScreen.EVENT_INFO, LayoutScreen.HOME)
                .associateWith { s(listOf("description", "context.about")) },
            chat = true,
        ),
        "performers" to ComponentSpec(mapOf(LayoutScreen.ITEM_DETAIL to s(listOf("context.performers"))), chat = true, subject = RefKind.SCHEDULE),
        "good_to_know" to ComponentSpec(
            mapOf(
                LayoutScreen.ITEM_DETAIL to s(listOf("context.price", "context.age_limit", "context.accessibility")),
                LayoutScreen.PLACE_DETAIL to s(listOf("context.age_limit", "context.accessibility")),
            ),
        ),
        "links" to ComponentSpec(
            mapOf(
                LayoutScreen.ITEM_DETAIL to s(listOf("context.links")), LayoutScreen.PLACE_DETAIL to s(listOf("context.links", "website")),
                LayoutScreen.EVENT_INFO to s(listOf("context.links")), LayoutScreen.HOME to s(listOf("context.links")),
            ),
            chat = true,
        ),
        "place_link" to ComponentSpec(mapOf(LayoutScreen.ITEM_DETAIL to s())),
        // No "Ask about this" any more: kept so older layouts validate; draws nothing.
        "ask_about" to ComponentSpec(mapOf(LayoutScreen.ITEM_DETAIL to s(listOf("model_available")), LayoutScreen.PLACE_DETAIL to s(listOf("model_available")))),
        "walk_map" to ComponentSpec(mapOf(LayoutScreen.PLACE_DETAIL to s(listOf("coordinate")))),
        "directions" to ComponentSpec(mapOf(LayoutScreen.PLACE_DETAIL to s(listOf("coordinate")))),
        "hours" to ComponentSpec(mapOf(LayoutScreen.PLACE_DETAIL to s(listOf("context.hours"))), chat = true, subject = RefKind.LOCATION),
        "menu" to ComponentSpec(mapOf(LayoutScreen.PLACE_DETAIL to s(listOf("context.menu"))), chat = true, subject = RefKind.LOCATION),
        "at_this_location" to ComponentSpec(mapOf(LayoutScreen.PLACE_DETAIL to s(listOf("items_here")))),
        "faq" to ComponentSpec(
            listOf(LayoutScreen.EVENT_INFO, LayoutScreen.HOME, LayoutScreen.UPDATES_TAB).associateWith { s(listOf("context.faq")) },
            chat = true,
        ),
        "amenities" to ComponentSpec(mapOf(LayoutScreen.EVENT_INFO to s(listOf("context.amenities"))), chat = true),
        "getting_there" to ComponentSpec(
            listOf(LayoutScreen.EVENT_INFO, LayoutScreen.HOME, LayoutScreen.UPDATES_TAB).associateWith { s(listOf("context.getting_there")) },
            chat = true,
        ),
        "accessibility" to ComponentSpec(mapOf(LayoutScreen.EVENT_INFO to s(listOf("context.accessibility"))), chat = true),
        "policies" to ComponentSpec(mapOf(LayoutScreen.EVENT_INFO to s(listOf("context.policies"))), chat = true),
        "weather_plan" to ComponentSpec(mapOf(LayoutScreen.EVENT_INFO to s(listOf("context.weather_plan"))), chat = true),
        "website" to ComponentSpec(mapOf(LayoutScreen.OPTIONS_EVENT to s(listOf("website")), LayoutScreen.HOME to s(listOf("website"))), chat = true),
        "event_info_link" to ComponentSpec(mapOf(LayoutScreen.OPTIONS_EVENT to s(listOf("context")))),
        "now_playing" to ComponentSpec(mapOf(LayoutScreen.MAP_OVERLAY to s(listOf("now_playing"))), chat = true),
        "selected_place" to ComponentSpec(mapOf(LayoutScreen.MAP_OVERLAY to s())),
        "urgent" to ComponentSpec(mapOf(LayoutScreen.HOME to s(listOf("urgent")), LayoutScreen.UPDATES_TAB to s()), chat = true),
        // My schedule (catalogue version 2): Home's "Up next in my schedule"; its empty state when empty.
        "my_schedule" to ComponentSpec(mapOf(LayoutScreen.HOME to s(), LayoutScreen.SCHEDULE_TAB to s(parts = LIST_ITEM_PARTS)), chat = true),
        // Catalogue 2 (API-027): banners as sections, the tabs' lists, Updates, the first-join cards.
        "status" to ComponentSpec(
            listOf(LayoutScreen.HOME, LayoutScreen.MAP_OVERLAY, LayoutScreen.SCHEDULE_TAB, LayoutScreen.PLACES_TAB, LayoutScreen.UPDATES_TAB)
                .associateWith { s() },
        ),
        "update_banner" to ComponentSpec(
            listOf(LayoutScreen.HOME, LayoutScreen.MAP_OVERLAY, LayoutScreen.SCHEDULE_TAB, LayoutScreen.PLACES_TAB)
                .associateWith { s(listOf("updates")) },
        ),
        "happening_now" to ComponentSpec(mapOf(LayoutScreen.SCHEDULE_TAB to s(listOf("schedule"), LIST_ITEM_PARTS))),
        "up_next" to ComponentSpec(mapOf(LayoutScreen.SCHEDULE_TAB to s(listOf("schedule"), LIST_ITEM_PARTS))),
        "featured" to ComponentSpec(mapOf(LayoutScreen.SCHEDULE_TAB to s(parts = LIST_ITEM_PARTS), LayoutScreen.PLACES_TAB to s(parts = LIST_PLACE_PARTS))),
        "by_day" to ComponentSpec(mapOf(LayoutScreen.SCHEDULE_TAB to s(listOf("schedule"), LIST_ITEM_PARTS))),
        "by_place" to ComponentSpec(mapOf(LayoutScreen.SCHEDULE_TAB to s(listOf("schedule"), LIST_ITEM_PARTS))),
        "nearby" to ComponentSpec(mapOf(LayoutScreen.PLACES_TAB to s(listOf("pins"), LIST_PLACE_PARTS))),
        "by_category" to ComponentSpec(mapOf(LayoutScreen.PLACES_TAB to s(listOf("locations"), LIST_PLACE_PARTS))),
        "all_places" to ComponentSpec(mapOf(LayoutScreen.PLACES_TAB to s(listOf("locations"), LIST_PLACE_PARTS))),
        "pinned" to ComponentSpec(mapOf(LayoutScreen.UPDATES_TAB to s(listOf("updates")))),
        "latest" to ComponentSpec(mapOf(LayoutScreen.UPDATES_TAB to s(listOf("updates")))),
        "earlier" to ComponentSpec(mapOf(LayoutScreen.UPDATES_TAB to s(listOf("updates")))),
        "intro" to ComponentSpec(mapOf(LayoutScreen.ONBOARDING to s())),
        "welcome" to ComponentSpec(mapOf(LayoutScreen.ONBOARDING to s())),
        "location" to ComponentSpec(mapOf(LayoutScreen.ONBOARDING to s())),
        "notifications" to ComponentSpec(mapOf(LayoutScreen.ONBOARDING to s())),
        "lock_screen" to ComponentSpec(mapOf(LayoutScreen.ONBOARDING to s())),
        // The chat's "Here's your day": My schedule's day as a timeline (the plan's, with walks).
        "my_day" to chat(),
        "whats_on" to ComponentSpec(mapOf(LayoutScreen.HOME to s(listOf("schedule"))), chat = true),
        "places" to ComponentSpec(mapOf(LayoutScreen.HOME to s(listOf("locations"))), chat = true),
        "updates" to ComponentSpec(mapOf(LayoutScreen.HOME to s(listOf("updates"))), chat = true),
        "flyer" to ComponentSpec(mapOf(LayoutScreen.HOME to s(listOf("flyer"))), chat = true),
    )

    /** Screen types a chat answer may show as a component (not the chat blocks themselves). */
    val chatScreenTypes: Set<String> by lazy { SPECS.filter { it.value.chat && it.key !in AssistantBlock.TYPES }.keys }

    /** The layout side: per screen, type → what it needs and its parts. */
    val screenTypes: Map<LayoutScreen, Map<String, SectionType>> by lazy {
        val out = LinkedHashMap<LayoutScreen, LinkedHashMap<String, SectionType>>()
        for ((name, spec) in SPECS) for ((screen, type) in spec.screens) out.getOrPut(screen) { LinkedHashMap() }[name] = type
        out
    }

    // Inputs

    /** A layout section as a component; `subject` = the detail screen's item or place. */
    fun component(section: LayoutSection, subject: EntityRef? = null): Component =
        Component(canonical(section.type), listOfNotNull(subject), section.title, section.limit, section.show, section.conditions)

    /** A chat block as a component (a component block is its component). */
    fun component(block: AssistantBlock): Component {
        if (block is AssistantBlock.ComponentBlock) return block.component
        val ids: List<EntityRef> = when (block) {
            is AssistantBlock.ItemCard -> listOf(EntityRef(RefKind.SCHEDULE, block.id))
            is AssistantBlock.Countdown -> listOf(EntityRef(RefKind.SCHEDULE, block.id))
            is AssistantBlock.PlaceCard -> listOf(EntityRef(RefKind.LOCATION, block.id))
            is AssistantBlock.UpdateCard -> listOf(EntityRef(RefKind.UPDATE, block.id))
            is AssistantBlock.ListBlock -> block.ids
            is AssistantBlock.Carousel -> block.ids
            is AssistantBlock.Timeline -> block.ids.map { EntityRef(RefKind.SCHEDULE, it) }
            is AssistantBlock.MapBlock -> block.pins.map { EntityRef(RefKind.LOCATION, it) }
            is AssistantBlock.Gallery -> block.ids.map { EntityRef(RefKind.LOCATION, it) }
            is AssistantBlock.NowNext -> listOfNotNull(block.location?.let { EntityRef(RefKind.LOCATION, it) })
            else -> emptyList()
        }
        return Component(block.type, ids, block = block)
    }

    /**
     * The model's `show(component, ids, title, limit)` and the block JSON's screen types: a chat component, or null
     * (unknown, not allowed in chat, or a subject of the wrong kind / missing).
     */
    fun chat(rawType: String, ids: List<EntityRef> = emptyList(), title: String? = null, limit: Int? = null): Component? {
        val type = canonical(rawType)
        val spec = SPECS[type] ?: return null
        if (!spec.chat || type in AssistantBlock.TYPES) return null
        val refs = spec.subject?.let { kind -> listOf(ids.firstOrNull { it.kind == kind } ?: return null) } ?: emptyList()
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
        return Component(type, refs, cleanTitle, limit?.coerceIn(1, ScreenLayout.MAX_LIMIT))
    }

    /** `{"type":"menu","ids":["l3"],"title":"…","limit":3}` (block JSON and tool arguments). */
    fun decode(obj: JsonObject): Component? {
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: return null
        val ids = (obj["ids"] as? JsonArray).orEmpty().mapNotNull { EntityRef.of(it) }.toMutableList()
        EntityRef.of(obj["id"])?.let { ids.add(0, it) }
        val title = (obj["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        val limit = (obj["limit"] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull
        return chat(type, ids, title, limit)
    }

    fun encode(component: Component): JsonObject = buildJsonObject {
        put("type", component.type)
        if (component.ids.isNotEmpty()) put("ids", buildJsonArray { component.ids.forEach { add(JsonPrimitive(it.code)) } })
        component.title?.let { put("title", it) }
        component.limit?.let { put("limit", it) }
    }

    /** A chat component still drawable with this bundle (its subject exists and has the data). */
    fun valid(component: Component, bundle: EventBundle): Boolean {
        val spec = SPECS[component.type] ?: return false
        if (!spec.chat) return false
        val subject = component.subject
        if (spec.subject != null && (subject == null || !subject.exists(bundle))) return false
        val event = bundle.event.context
        return when (component.type) {
            "menu" -> bundle.location(subject?.id)?.context?.menu?.isNotEmpty() == true
            "hours" -> bundle.location(subject?.id)?.context?.hours?.isNotEmpty() == true
            "performers" -> bundle.scheduleItem(subject?.id)?.context?.performers?.isNotEmpty() == true
            "faq" -> event?.faq?.isNotEmpty() == true
            "amenities" -> event?.amenities?.isNotEmpty() == true
            "getting_there" -> event?.gettingThere?.isNotEmpty() == true
            "accessibility" -> event?.accessibility?.isNotEmpty() == true
            "policies" -> event?.policies?.isNotEmpty() == true
            "weather_plan" -> event?.weatherPlan?.isNotEmpty() == true
            "links" -> event?.links?.isNotEmpty() == true
            "website" -> bundle.event.websiteUrl != null
            "flyer" -> bundle.event.flyerUrl != null
            "updates" -> bundle.notifications.isNotEmpty()
            "places" -> bundle.locations.isNotEmpty()
            else -> true
        }
    }

    /** The type names the model's `show` tool may use, for its description. */
    val chatTypeList: String get() = chatScreenTypes.sorted().joinToString(", ")
}
