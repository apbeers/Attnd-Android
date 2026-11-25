package com.arcu.attnd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.time.Duration
import java.time.Instant

/*
 * Server-driven screens (API-024 + API-027 catalogue 2, attnd-web `API_V2.md` → Layout; a 1:1 port of iOS
 * `ScreenLayout.swift`, tests `ScreenLayoutTest` = iOS `ScreenLayoutTests` and attnd-web `events/tests_layout.py`).
 * The bundle's `layout` lists, per screen, the sections to show in order (`{type, show?, title?, limit?, when?,
 * ids?, text?, image?}`), plus overrides per place category or tag. The app draws only section types compiled
 * into it (`SectionCatalogue`, built from the one component catalogue, `Component.kt`; its `VERSION` is what
 * `X-Attnd-Sections` will send from phase 5b) and skips everything else. Pure rules:
 *
 *   1. Validation (`validate`): types unknown for the screen are dropped, a type appears once (the first wins;
 *      older names map across: `plan_today` / `liked` → `my_schedule`), `show` parts unknown for the type are
 *      dropped (none left → the type's default parts), `title` trimmed and cut to 60 characters, `limit` clamped to
 *      1…20, `ids` deduplicated (≤ 20), `text` ≤ 300, `image` only an absolute https URL with a host (≤ 500),
 *      ≤ 24 sections.
 *   2. Fallback: no layout, no entry for the screen, or no section left after validation → the built-in default
 *      (`ScreenLayout.DEFAULTS`, today's fixed screen as a layout; catalogue 2's tab and onboarding screens have
 *      none: nothing = today's tab). Screenshot mode always passes no layout. Home always ends with `website`.
 *   3. Overrides: the first of ≤ 8 overrides whose `match` fits the subject replaces the sections
 *      (`category:<name>`, case-insensitive, against the place's category or a schedule item's place's category;
 *      `tag:<tag>` against the subject's tags). An override with no valid section left is skipped.
 *   4. Visibility (`resolve`): a section shows when the data it draws is there (`SectionType.needs`, any of those
 *      facts) and every `when` condition holds. Conditions are a closed set: `has:<fact>`, `has_plan`,
 *      `model_available`, `now_playing`, `within_walk`, `before_start` / `during` / `after_end`, `day:YYYY-MM-DD`
 *      (today in the event's zone) and `not:<condition>`. An unknown condition is false, negated too.
 *   5. `ScreenFacts` computes the facts of an item, a place or the event from the bundle.
 */

/** The screens a layout can describe. */
enum class LayoutScreen(val key: String) {
    HOME("home"),
    EVENT_INFO("event_info"),
    ITEM_DETAIL("item_detail"),
    PLACE_DETAIL("place_detail"),
    MAP_OVERLAY("map_overlay"),
    OPTIONS_EVENT("options_event"),

    // Catalogue 2 (API-027): the tabs' lists, Updates and the first-join cards.
    SCHEDULE_TAB("schedule_tab"),
    PLACES_TAB("places_tab"),
    UPDATES_TAB("updates_tab"),
    ONBOARDING("onboarding");

    companion object {
        fun of(key: String?): LayoutScreen? = entries.firstOrNull { it.key == key }
    }
}

/** One section of a screen, as sent (before validation) or resolved. `conditions` is the JSON's `when`. */
data class LayoutSection(
    val type: String,
    val show: List<String> = emptyList(),
    val title: String? = null,
    val limit: Int? = null,
    val conditions: List<String> = emptyList(),
    /** Catalogue 2: `featured` / `pinned` ids in the organizer's order (≤ 20). */
    val ids: List<Int> = emptyList(),
    /** Catalogue 2: the `welcome` card's text (≤ 300) and image (absolute https, ≤ 500). */
    val text: String? = null,
    val image: String? = null,
) {
    fun shows(part: String): Boolean = part in show
}

/** Sections that replace a screen's for subjects of one category or tag. */
data class LayoutOverride(val match: String, val sections: List<LayoutSection>)

data class ScreenSpec(val sections: List<LayoutSection>, val overrides: List<LayoutOverride> = emptyList())

/** `bundle.layout` (API-024). Decode the raw bundle value (phase 1's `BundleDto.layout`) with [ScreenLayouts.decode]. */
data class ScreenLayouts(
    /** The catalogue version the server built it for. */
    val catalogue: Int = 0,
    val screens: Map<LayoutScreen, ScreenSpec> = emptyMap(),
) {
    companion object {
        /**
         * Lenient: unknown keys ignored, unknown screens and bad entries dropped (each section, override and screen
         * on its own), a non-number `catalogue` = 0. Null / `null` / not an object → null (no layout: built-in).
         */
        fun decode(element: JsonElement?): ScreenLayouts? {
            val obj = element as? JsonObject ?: return null
            val catalogue = obj.int("catalogue") ?: 0
            val screens = LinkedHashMap<LayoutScreen, ScreenSpec>()
            (obj["screens"] as? JsonObject)?.forEach { (key, value) ->
                val screen = LayoutScreen.of(key) ?: return@forEach
                val spec = value as? JsonObject ?: return@forEach
                screens[screen] = ScreenSpec(
                    sections = (spec["sections"] as? JsonArray).orEmpty().mapNotNull(::section),
                    overrides = (spec["overrides"] as? JsonArray).orEmpty().mapNotNull(::override),
                )
            }
            return ScreenLayouts(catalogue, screens)
        }

        private fun section(element: JsonElement): LayoutSection? {
            val obj = element as? JsonObject ?: return null
            val type = obj.nonEmptyString("type") ?: return null
            val conditions = obj.nonEmptyString("when")?.let { listOf(it) } ?: obj.strings("when")
            return LayoutSection(
                type = type,
                show = obj.strings("show"),
                title = obj.nonEmptyString("title"),
                limit = obj.int("limit"),
                conditions = conditions,
                ids = (obj["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull },
                text = obj.nonEmptyString("text"),
                image = obj.nonEmptyString("image"),
            )
        }

        private fun override(element: JsonElement): LayoutOverride? {
            val obj = element as? JsonObject ?: return null
            val match = obj.nonEmptyString("match") ?: return null
            return LayoutOverride(match, (obj["sections"] as? JsonArray).orEmpty().mapNotNull(::section))
        }
    }
}

/** What a section type needs and which parts it has. */
data class SectionType(
    /** Any one of these facts makes the section drawable; empty = always. */
    val needs: List<String> = emptyList(),
    /** The parts `show` may list, and the default when it lists none. */
    val parts: List<String> = emptyList(),
    val defaultShow: List<String> = emptyList(),
)

/** The section types this build draws, per screen: the layout side of the one component catalogue. */
object SectionCatalogue {
    /**
     * What `X-Attnd-Sections` sends (from phase 5b); the server only sends sections of this version or older.
     * 2 (Nov 21, 2026): `my_schedule` on Home (`plan_today` / `liked` map to it), catalogue 2's screens.
     */
    const val VERSION = 2

    val ITEM_HEADER_PARTS get() = ComponentCatalogue.ITEM_HEADER_PARTS
    val PLACE_HEADER_PARTS get() = ComponentCatalogue.PLACE_HEADER_PARTS

    val types: Map<LayoutScreen, Map<String, SectionType>> get() = ComponentCatalogue.screenTypes

    fun type(name: String, screen: LayoutScreen): SectionType? =
        ComponentCatalogue.screenTypes[screen]?.get(ComponentCatalogue.canonical(name))

    /** Every fact name a `has:` condition may use. */
    val FACTS: Set<String> = setOf(
        "description", "image", "coordinate", "website", "tags", "category", "flyer", "logo",
        "context", "context.about", "context.menu", "context.hours", "context.accessibility", "context.age_limit",
        "context.price", "context.performers", "context.links", "context.faq", "context.amenities",
        "context.getting_there", "context.policies", "context.weather_plan",
        "items_here", "likes", "plan", "updates", "urgent", "locations", "pins", "schedule", "upcoming",
    )

    /** Conditions without arguments (each is also a fact of that name). */
    val STATES: Set<String> = setOf("model_available", "now_playing", "within_walk", "before_start", "during", "after_end")
}

object ScreenLayout {
    const val MAX_SECTIONS = 24
    const val MAX_OVERRIDES = 8
    const val MAX_TITLE = 60
    const val MAX_LIMIT = 20
    const val MAX_TEXT = 300
    const val MAX_IMAGE_URL = 500

    private fun s(type: String, show: List<String> = emptyList(), limit: Int? = null) = LayoutSection(type, show = show, limit = limit)

    /** Today's fixed screens, as layouts (also the fallback for anything missing or invalid). */
    val DEFAULTS: Map<LayoutScreen, List<LayoutSection>> = mapOf(
        LayoutScreen.ITEM_DETAIL to listOf(
            s("header", ComponentCatalogue.ITEM_HEADER_PARTS), s("about"), s("performers"), s("good_to_know"), s("links"),
            s("place_link"),
        ),
        LayoutScreen.PLACE_DETAIL to listOf(
            s("header", ComponentCatalogue.PLACE_HEADER_PARTS), s("walk_map"), s("about"), s("hours"), s("good_to_know"),
            s("menu"), s("links"), s("at_this_location"),
        ),
        LayoutScreen.EVENT_INFO to listOf(
            s("header", listOf("website")), s("about"), s("faq"), s("amenities"), s("getting_there"), s("accessibility"),
            s("policies"), s("weather_plan"), s("links"),
        ),
        LayoutScreen.OPTIONS_EVENT to listOf(s("header"), s("website"), s("event_info_link")),
        LayoutScreen.MAP_OVERLAY to listOf(s("now_playing"), s("selected_place")),
        // No now_next by default (owner, Nov 21, 2026); organizers can still add it.
        LayoutScreen.HOME to listOf(
            s("header"), s("urgent"), s("countdown"), s("my_schedule"), s("map"), s("updates", limit = 3),
            s("faq", limit = 3), s("getting_there"), s("about"),
        ),
    )

    /** Who a detail screen is about, for the overrides. */
    data class Subject(val category: String? = null, val tags: List<String> = emptyList()) {
        companion object {
            val EMPTY = Subject()
        }
    }

    /** Rule 1: known types for the screen, once each, parts / title / limit / ids / text / image cleaned, ≤ 24. */
    fun validate(sections: List<LayoutSection>, screen: LayoutScreen): List<LayoutSection> {
        val seen = HashSet<String>()
        val out = ArrayList<LayoutSection>()
        for (raw in sections) {
            val name = ComponentCatalogue.canonical(raw.type)
            val type = SectionCatalogue.type(name, screen) ?: continue
            if (!seen.add(name)) continue
            val shown = raw.show.map { it.lowercase() }.filter { it in type.parts }.distinct()
            out += LayoutSection(
                type = name,
                show = shown.ifEmpty { type.defaultShow },
                title = raw.title?.trim()?.takeIf { it.isNotEmpty() }?.let { cut(it, MAX_TITLE) },
                limit = raw.limit?.coerceIn(1, MAX_LIMIT),
                conditions = raw.conditions.map { it.trim() }.filter { it.isNotEmpty() },
                ids = raw.ids.distinct().take(MAX_LIMIT),
                text = raw.text?.trim()?.takeIf { it.isNotEmpty() }?.let { cut(it, MAX_TEXT) },
                image = raw.image?.takeIf { isHttpsUrl(it) && it.length <= MAX_IMAGE_URL },
            )
            if (out.size == MAX_SECTIONS) break
        }
        return out
    }

    private fun cut(text: String, max: Int): String = if (text.length > max) text.take(max - 1) + "…" else text

    private fun isHttpsUrl(text: String): Boolean = runCatching {
        val uri = URI(text)
        uri.scheme?.lowercase() == "https" && !uri.host.isNullOrEmpty()
    }.getOrDefault(false)

    /** Rules 2–3: the sections for a screen and subject, before the facts decide visibility. */
    fun sections(screen: LayoutScreen, layouts: ScreenLayouts?, subject: Subject = Subject.EMPTY): List<LayoutSection> {
        val sections = layoutSections(screen, layouts, subject)
        // Batch 7 (owner): the event's website is always near the bottom of Home (shown only when it has one).
        return if (screen == LayoutScreen.HOME && sections.none { it.type == "website" }) sections + LayoutSection("website") else sections
    }

    private fun layoutSections(screen: LayoutScreen, layouts: ScreenLayouts?, subject: Subject): List<LayoutSection> {
        val fallback = DEFAULTS[screen].orEmpty()
        val spec = layouts?.screens?.get(screen) ?: return fallback
        for (override in spec.overrides.take(MAX_OVERRIDES)) {
            if (!matches(override.match, subject)) continue
            val kept = validate(override.sections, screen)
            if (kept.isNotEmpty()) return kept
        }
        return validate(spec.sections, screen).ifEmpty { fallback }
    }

    /** Rule 3: `category:<name>` / `tag:<tag>`. */
    fun matches(match: String, subject: Subject): Boolean {
        val trimmed = match.trim()
        val colon = trimmed.indexOf(':')
        if (colon < 0) return false
        val kind = trimmed.substring(0, colon).lowercase()
        val value = trimmed.substring(colon + 1).trim().lowercase()
        if (value.isEmpty()) return false
        return when (kind) {
            "category" -> subject.category?.trim()?.lowercase() == value
            "tag" -> subject.tags.any { it.lowercase() == value }
            else -> false
        }
    }

    /** Rules 1–4: the sections to draw, in order. */
    fun resolve(screen: LayoutScreen, layouts: ScreenLayouts?, facts: Set<String>, subject: Subject = Subject.EMPTY): List<LayoutSection> =
        sections(screen, layouts, subject).filter { section ->
            val type = SectionCatalogue.type(section.type, screen) ?: return@filter false
            if (type.needs.isNotEmpty() && type.needs.none { it in facts }) return@filter false
            section.conditions.all { holds(it, facts) }
        }

    /** Rule 4: one condition. */
    fun holds(condition: String, facts: Set<String>): Boolean {
        val c = condition.trim().lowercase()
        if (c.startsWith("not:")) {
            val inner = c.removePrefix("not:")
            // An unknown condition stays false when negated too (fail closed).
            return isKnown(inner) && !holds(inner, facts)
        }
        if (c == "has_plan") return "plan" in facts
        if (c.startsWith("has:")) {
            val fact = c.removePrefix("has:")
            return fact in SectionCatalogue.FACTS && fact in facts
        }
        if (c.startsWith("day:")) return isDate(c.removePrefix("day:")) && c in facts
        return c in SectionCatalogue.STATES && c in facts
    }

    private fun isKnown(c: String): Boolean = when {
        c.startsWith("not:") -> isKnown(c.removePrefix("not:"))
        c == "has_plan" || c in SectionCatalogue.STATES -> true
        c.startsWith("has:") -> c.removePrefix("has:") in SectionCatalogue.FACTS
        c.startsWith("day:") -> isDate(c.removePrefix("day:"))
        else -> false
    }

    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private fun isDate(s: String): Boolean = DATE.matches(s)
}

/** The facts the visibility rules read, from the bundle (rule 5). */
object ScreenFacts {
    /** What the app knows beyond the bundle. */
    data class State(
        val now: Instant,
        val modelAvailable: Boolean = false,
        val hasPlan: Boolean = false,
        val hasLikes: Boolean = false,
        /** Within walking range of the subject (8 km, with a position). */
        val withinWalk: Boolean = false,
    )

    /** "context" plus `context.<part>` for each non-empty part. */
    private fun MutableSet<String>.addContext(vararg parts: Pair<String, Boolean>) {
        add("context")
        parts.filter { it.second }.forEach { add("context.${it.first}") }
    }

    /** The event-wide facts every screen shares: the event phase, today, the model, plan, likes, now playing. */
    fun common(bundle: EventBundle, state: State): Set<String> {
        val facts = HashSet<String>()
        val event = bundle.event
        val now = state.now
        val start = event.startTime?.toInstant()
        val end = event.endTime?.toInstant()
        if (start != null && now.isBefore(start)) facts += "before_start"
        if (end != null && !now.isBefore(end)) facts += "after_end"
        else if (start != null && !now.isBefore(start)) facts += "during"
        facts += "day:" + now.atZone(event.zone).toLocalDate().toString()
        if (state.modelAvailable) facts += "model_available"
        if (state.hasPlan) facts += "plan"
        if (state.hasLikes) facts += "likes"
        if (state.withinWalk) facts += "within_walk"
        if (bundle.schedule.any { ScheduleGrouping.isHappeningNow(it, now) && bundle.location(it.locationId)?.coordinate != null }) {
            facts += "now_playing"
        }
        return facts
    }

    fun item(item: ScheduleItem, bundle: EventBundle, state: State): Set<String> {
        val facts = common(bundle, state).toMutableSet()
        if (item.description.isNotEmpty()) facts += "description"
        if (item.tags.isNotEmpty()) facts += "tags"
        item.startTime?.toInstant()?.let { if (it.isAfter(state.now)) facts += "upcoming" }
        bundle.location(item.locationId)?.let { location ->
            if (location.coordinate != null) facts += "coordinate"
            if (location.category != null) facts += "category"
        }
        item.context?.takeIf { !it.isEmpty }?.let { c ->
            facts.addContext(
                "about" to c.about.isNotEmpty(), "performers" to c.performers.isNotEmpty(), "price" to c.price.isNotEmpty(),
                "age_limit" to c.ageLimit.isNotEmpty(), "accessibility" to c.accessibility.isNotEmpty(), "links" to c.links.isNotEmpty(),
            )
        }
        return facts
    }

    fun place(location: EventLocation, bundle: EventBundle, state: State): Set<String> {
        val facts = common(bundle, state).toMutableSet()
        if (location.description.isNotEmpty()) facts += "description"
        if (location.tags.isNotEmpty()) facts += "tags"
        if (location.category != null) facts += "category"
        if (location.imageUrl != null) facts += "image"
        if (location.coordinate != null) facts += "coordinate"
        if (location.website != null) facts += "website"
        if (bundle.schedule.any { it.locationId == location.id }) facts += "items_here"
        location.context?.takeIf { !it.isEmpty }?.let { c ->
            facts.addContext(
                "about" to c.about.isNotEmpty(), "menu" to c.menu.isNotEmpty(), "hours" to c.hours.isNotEmpty(),
                "age_limit" to c.ageLimit.isNotEmpty(), "accessibility" to c.accessibility.isNotEmpty(), "links" to c.links.isNotEmpty(),
            )
        }
        return facts
    }

    fun event(bundle: EventBundle, state: State): Set<String> {
        val facts = common(bundle, state).toMutableSet()
        val event = bundle.event
        if (event.description.isNotEmpty()) facts += "description"
        if (event.websiteUrl != null) facts += "website"
        if (event.flyerUrl != null) facts += "flyer"
        if (event.logoUrl != null || bundle.display.logoUrl != null) facts += "logo"
        if (event.coordinate != null) facts += "coordinate"
        if (bundle.schedule.isNotEmpty()) facts += "schedule"
        if (bundle.locations.isNotEmpty()) facts += "locations"
        if (bundle.locations.any { it.coordinate != null }) facts += "pins"
        if (bundle.notifications.isNotEmpty()) facts += "updates"
        if (bundle.notifications.any { n -> n.isUrgent && n.sendTime?.let { sentWithin(it.toInstant(), state.now, UpdatesLayout.URGENT_WINDOW) } == true }) {
            facts += "urgent"
        }
        event.context?.takeIf { !it.isEmpty }?.let { c ->
            facts.addContext(
                "about" to c.about.isNotEmpty(), "faq" to c.faq.isNotEmpty(), "amenities" to c.amenities.isNotEmpty(),
                "getting_there" to c.gettingThere.isNotEmpty(), "accessibility" to c.accessibility.isNotEmpty(),
                "policies" to c.policies.isNotEmpty(), "weather_plan" to c.weatherPlan.isNotEmpty(), "links" to c.links.isNotEmpty(),
            )
        }
        return facts
    }

    /** The override subject of a schedule item: its place's category, its own tags. */
    fun subject(item: ScheduleItem, bundle: EventBundle): ScreenLayout.Subject =
        ScreenLayout.Subject(bundle.location(item.locationId)?.category, item.tags)

    fun subject(location: EventLocation): ScreenLayout.Subject = ScreenLayout.Subject(location.category, location.tags)

    /** `now − sent < window` (a send time in the future counts as within it, like iOS). */
    internal fun sentWithin(sent: Instant, now: Instant, window: Duration): Boolean = Duration.between(sent, now) < window
}

// MARK: Two columns on wide containers (unfolded foldables)

/**
 * Detail screens in two columns from a container width of 700 dp (an unfolded foldable's inner display; never a
 * device check): the header and the "where / when" parts leading, everything else trailing, each in layout order.
 * Null = one column (also with fewer than 3 sections for the trailing side).
 */
object DetailColumns {
    const val MIN_WIDTH = 700.0

    val LEADING_TYPES: Map<LayoutScreen, Set<String>> = mapOf(
        LayoutScreen.PLACE_DETAIL to setOf("header", "walk_map", "directions", "now_next"),
        LayoutScreen.ITEM_DETAIL to setOf("header", "place_link", "countdown"),
    )

    data class Split(val leading: List<LayoutSection>, val trailing: List<LayoutSection>)

    fun split(sections: List<LayoutSection>, screen: LayoutScreen, width: Double): Split? {
        if (width < MIN_WIDTH) return null
        val types = LEADING_TYPES[screen] ?: return null
        val (leading, trailing) = sections.partition { it.type in types }
        if (leading.isEmpty() || trailing.size < 3) return null
        return Split(leading, trailing)
    }
}

/**
 * Home's two columns (unfolded): each section goes to the column that is shorter so far (rough heights by type),
 * keeping the order within each column.
 */
object HomeColumns {
    fun weight(type: String): Int = when (type) {
        "map", "my_schedule", "my_day", "faq", "whats_on", "places" -> 3
        "updates", "getting_there", "amenities", "about" -> 2
        else -> 1
    }

    fun split(sections: List<LayoutSection>): DetailColumns.Split {
        val leading = ArrayList<LayoutSection>()
        val trailing = ArrayList<LayoutSection>()
        var left = 0
        var right = 0
        for (section in sections) {
            if (left <= right) {
                leading += section; left += weight(section.type)
            } else {
                trailing += section; right += weight(section.type)
            }
        }
        return DetailColumns.Split(leading, trailing)
    }
}

/**
 * Home's two column widths on a wide container: with a reported vertical fold the gap is the fold's own width plus
 * `gutter` on each side; without one (or one leaving a column under 160) the columns meet at the middle with
 * `2 × gutter` between them. `margin` is the page's side padding.
 */
object HomeColumnWidths {
    data class Widths(val leading: Double, val gap: Double, val trailing: Double)

    fun of(width: Double, margin: Double, gutter: Double, foldMinX: Double?, foldMaxX: Double?): Widths {
        val inner = maxOf(width - 2 * margin, 0.0)
        if (foldMinX != null && foldMaxX != null && foldMaxX > foldMinX &&
            foldMinX - margin - gutter > 160 && width - margin - (foldMaxX + gutter) > 160
        ) {
            val leading = foldMinX - margin - gutter
            val gap = (foldMaxX - foldMinX) + 2 * gutter
            return Widths(leading, gap, maxOf(inner - leading - gap, 0.0))
        }
        val gap = 2 * gutter
        val half = maxOf((inner - gap) / 2, 0.0)
        return Widths(half, gap, half)
    }
}

/**
 * Home in tabletop posture (the fold across the middle): the top half for viewing (header, urgent, countdown,
 * now / next, the map), the bottom half the rest as a list. Null = nothing for one of the halves.
 */
object TabletopSplit {
    val TOP_TYPES = setOf("header", "urgent", "countdown", "now_next", "map")

    /** What the tabletop glance already shows on the top half (it never scrolls; the map goes to the list). */
    val GLANCE_TYPES = setOf("header", "urgent", "countdown", "now_next", "now_playing")

    data class Halves(val top: List<LayoutSection>, val bottom: List<LayoutSection>)

    fun home(sections: List<LayoutSection>): Halves? {
        val (top, bottom) = sections.partition { it.type in TOP_TYPES }
        if (top.isEmpty() || bottom.isEmpty()) return null
        return Halves(top, bottom)
    }
}

/**
 * An "About" section's text from the description and the API-018 `context.about`, once: the same or contained text
 * isn't shown twice. The context's about wins; the description joins it only when it says something more.
 */
object AboutText {
    fun merged(description: String, about: String?): String {
        val d = description.trim()
        val a = about.orEmpty().trim()
        if (a.isEmpty()) return d
        if (d.isEmpty()) return a
        val nd = normalized(d)
        val na = normalized(a)
        if (na.contains(nd)) return a
        if (nd.contains(na)) return d
        return a + "\n\n" + d
    }

    private val SEPARATORS = Regex("""[^\p{L}\p{M}\p{N}]+""")

    private fun normalized(text: String): String = text.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }.joinToString(" ")
}

// MARK: Catalogue 2 screens (API-027)

/**
 * What the Schedule and Places tabs draw from their layouts (`schedule_tab`, `places_tab`): which list (the existing
 * styles), whether My Schedule shows, and the organizer's featured ids. Null = no layout for the tab (today's screen,
 * from `display`). Row parts (`show`) aren't drawn yet.
 */
data class TabListLayout(
    /** `display.schedule_style` / `locations_style` values. */
    val style: String? = null,
    val showsMySchedule: Boolean = true,
    val featured: List<Int> = emptyList(),
    val featuredTitle: String? = null,
) {
    companion object {
        fun schedule(sections: List<LayoutSection>): TabListLayout? {
            if (sections.isEmpty()) return null
            var out = TabListLayout(showsMySchedule = sections.any { it.type == "my_schedule" })
            for (section in sections) {
                out = when (section.type) {
                    "timeline" -> out.copy(style = out.style ?: "timeline")
                    "by_day" -> out.copy(style = out.style ?: "by_day")
                    "by_place" -> out.copy(style = out.style ?: "by_location")
                    "happening_now", "up_next" -> out.copy(style = out.style ?: "now_next")
                    "featured" -> out.copy(featured = section.ids, featuredTitle = section.title)
                    else -> out
                }
            }
            return out
        }

        fun places(sections: List<LayoutSection>): TabListLayout? {
            if (sections.isEmpty()) return null
            var out = TabListLayout(showsMySchedule = false)
            for (section in sections) {
                out = when (section.type) {
                    "by_category" -> out.copy(style = out.style ?: "grouped")
                    "all_places", "nearby" -> out.copy(style = out.style ?: if (section.shows("image")) "cards" else "list")
                    "featured" -> out.copy(featured = section.ids, featuredTitle = section.title)
                    else -> out
                }
            }
            return out
        }
    }
}

/**
 * The Updates tab from `updates_tab` (urgent first, the organizer's pinned ones, the latest day, earlier): each
 * notification once, in the first section that takes it, newest first; anything no section took goes last.
 */
object UpdatesLayout {
    /** How long an urgent announcement counts as urgent (API-011). */
    val URGENT_WINDOW: Duration = Duration.ofHours(12)
    private val LATEST_WINDOW: Duration = Duration.ofHours(24)

    data class Group(val type: String, val title: String, val ids: List<Int>)

    fun groups(sections: List<LayoutSection>, notifications: List<EventNotification>, now: Instant): List<Group> {
        val newestFirst = notifications.sortedWith(
            compareByDescending<EventNotification> { it.sendTime?.toInstant() ?: Instant.MIN }.thenByDescending { it.id },
        )
        val byId = LinkedHashMap<Int, EventNotification>()
        newestFirst.forEach { byId.putIfAbsent(it.id, it) }
        val used = HashSet<Int>()
        val out = ArrayList<Group>()
        fun take(list: List<EventNotification>): List<Int> = list.map { it.id }.filter { used.add(it) }
        for (section in sections) {
            val ids: List<Int>
            val title: String
            when (section.type) {
                "urgent" -> {
                    ids = take(newestFirst.filter { n -> n.isUrgent && n.sendTime?.let { ScreenFacts.sentWithin(it.toInstant(), now, URGENT_WINDOW) } == true })
                    title = "Urgent"
                }
                "pinned" -> {
                    ids = take(section.ids.mapNotNull { byId[it] })
                    title = "Pinned"
                }
                "latest" -> {
                    ids = take(newestFirst.filter { n -> n.sendTime?.let { ScreenFacts.sentWithin(it.toInstant(), now, LATEST_WINDOW) } ?: true })
                    title = "Latest"
                }
                "earlier" -> {
                    ids = take(newestFirst)
                    title = "Earlier"
                }
                "faq", "getting_there" -> {
                    out += Group(section.type, section.title ?: if (section.type == "faq") "FAQ" else "Getting There", emptyList())
                    continue
                }
                else -> continue
            }
            val limited = section.limit?.let { ids.take(it) } ?: ids
            if (limited.isNotEmpty()) out += Group(section.type, section.title ?: title, limited)
        }
        val rest = take(newestFirst)
        if (rest.isNotEmpty() && sections.any { it.type in setOf("urgent", "pinned", "latest", "earlier") }) {
            out += Group("earlier", "Earlier", rest)
        }
        return out
    }
}

/** The first-join cards from `onboarding` (null = the built-in cards): their order, which ones, the welcome card. */
data class OnboardingLayout(
    val cards: List<Card>,
    val welcomeTitle: String? = null,
    val welcomeText: String? = null,
    val welcomeImage: String? = null,
) {
    enum class Card(val key: String) {
        INTRO("intro"), WELCOME("welcome"), LOCATION("location"), NOTIFICATIONS("notifications"),
        LOCK_SCREEN("lock_screen"), PLAN("plan");

        companion object {
            fun of(key: String): Card? = entries.firstOrNull { it.key == key }
        }
    }

    companion object {
        fun of(sections: List<LayoutSection>): OnboardingLayout? {
            val cards = sections.mapNotNull { Card.of(it.type) }
            if (cards.isEmpty()) return null
            val welcome = sections.firstOrNull { it.type == "welcome" }
            // A welcome card with nothing to say is skipped.
            val usable = cards.filter { it != Card.WELCOME || welcome?.title != null || welcome?.text != null }
            return OnboardingLayout(usable, welcome?.title, welcome?.text, welcome?.image)
        }
    }
}

// Lenient JSON helpers (type mismatches read as missing, like iOS `lenient` / `lossyArray`).

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

private fun JsonObject.nonEmptyString(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && p.isString }?.content }

