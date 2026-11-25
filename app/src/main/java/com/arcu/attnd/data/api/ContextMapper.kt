package com.arcu.attnd.data.api

import com.arcu.attnd.domain.Amenity
import com.arcu.attnd.domain.AmenityKind
import com.arcu.attnd.domain.AppleMusicLinks
import com.arcu.attnd.domain.ContextLink
import com.arcu.attnd.domain.EventContext
import com.arcu.attnd.domain.EventTheme
import com.arcu.attnd.domain.FaqEntry
import com.arcu.attnd.domain.LiveActivitySettings
import com.arcu.attnd.domain.LocationContext
import com.arcu.attnd.domain.MenuEntry
import com.arcu.attnd.domain.Performer
import com.arcu.attnd.domain.ScheduleItemContext
import com.arcu.attnd.domain.ThemeColors
import com.arcu.attnd.domain.WebLinks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Lenient decoding of the bundle's nested objects that arrive as raw JSON ([JsonElement] in the
 * DTOs, so a value of the wrong type can never fail the whole bundle): API-018 context (with
 * API-030 Apple Music links), API-023 `event.theme` and API-027 `live_activity`. The rules match
 * iOS `Models.swift` / `ScreenLayout.swift` one for one (`ContextDecodingTest` ports its cases):
 * a wrong type is the same as a missing key, list entries that don't decode are skipped, and an
 * object with nothing usable in it is null.
 */
object ContextMapper {

    fun eventContext(element: JsonElement?): EventContext? {
        val o = element as? JsonObject ?: return null
        return EventContext(
            about = o.text("about").orEmpty(),
            faq = o.list("faq").mapNotNull(::faqEntry),
            amenities = o.list("amenities").mapNotNull(::amenity),
            accessibility = o.text("accessibility").orEmpty(),
            policies = o.text("policies").orEmpty(),
            gettingThere = o.text("getting_there").orEmpty(),
            weatherPlan = o.text("weather_plan").orEmpty(),
            links = links(o),
        ).takeUnless { it.isEmpty }
    }

    fun locationContext(element: JsonElement?): LocationContext? {
        val o = element as? JsonObject ?: return null
        return LocationContext(
            about = o.text("about").orEmpty(),
            menu = o.list("menu").mapNotNull(::menuEntry),
            hours = o.text("hours").orEmpty(),
            accessibility = o.text("accessibility").orEmpty(),
            ageLimit = o.text("age_limit").orEmpty(),
            links = links(o),
        ).takeUnless { it.isEmpty }
    }

    fun scheduleItemContext(element: JsonElement?): ScheduleItemContext? {
        val o = element as? JsonObject ?: return null
        return ScheduleItemContext(
            about = o.text("about").orEmpty(),
            performers = o.list("performers").mapNotNull(::performer),
            ageLimit = o.text("age_limit").orEmpty(),
            price = o.text("price").orEmpty(),
            accessibility = o.text("accessibility").orEmpty(),
            links = links(o),
        ).takeUnless { it.isEmpty }
    }

    fun performer(element: JsonElement?): Performer? {
        val o = element as? JsonObject ?: return null
        val name = o.text("name") ?: return null
        return Performer(
            name = name,
            bio = o.text("bio").orEmpty(),
            genres = o.list("genres").mapNotNull { it.string()?.trim()?.takeIf(String::isNotEmpty) },
            appleMusicUrl = AppleMusicLinks.of(o.text("apple_music_url")),
        )
    }

    /** API-023: `event.theme`; a bad colour or a missing role / mode is the default for it; nothing usable → null. */
    fun theme(element: JsonElement?): EventTheme? {
        val o = element as? JsonObject ?: return null
        return EventTheme(light = themeColors(o["light"]), dark = themeColors(o["dark"])).takeUnless { it.isEmpty }
    }

    /** API-027 `live_activity`: unknown values keep the defaults; not an object → null. */
    fun liveActivity(element: JsonElement?): LiveActivitySettings? {
        val o = element as? JsonObject ?: return null
        val buttons = if ("buttons" in o) {
            o.list("buttons").mapNotNull { it.string()?.lowercase() }
                .filter { it in LiveActivitySettings.KNOWN_BUTTONS }.distinct()
        } else {
            null
        }
        return LiveActivitySettings(
            follows = LiveActivitySettings.Follows.of(o.text("follows")) ?: LiveActivitySettings.Follows.MY_SCHEDULE,
            locationId = o.int("location_id"),
            buttons = buttons,
            staleText = o.text("stale_text")?.take(LiveActivitySettings.MAX_STALE_TEXT),
        )
    }

    private fun themeColors(element: JsonElement?): ThemeColors? {
        val o = element as? JsonObject ?: return null
        return ThemeColors(
            primary = ThemeColors.hex(o.text("primary")),
            secondary = ThemeColors.hex(o.text("secondary")),
            tertiary = ThemeColors.hex(o.text("tertiary")),
        ).takeUnless { it.isEmpty }
    }

    private fun faqEntry(element: JsonElement): FaqEntry? {
        val o = element as? JsonObject ?: return null
        val q = o.text("q") ?: return null
        val a = o.text("a") ?: return null
        return FaqEntry(q, a)
    }

    private fun amenity(element: JsonElement): Amenity? {
        val o = element as? JsonObject ?: return null
        val rawKind = o.text("kind")
        val kind = AmenityKind.of(rawKind)
        val name = o.text("name") ?: kind.label.takeIf { rawKind != null } ?: return null
        return Amenity(kind = kind, name = name, locationId = o.int("location_id"), note = o.text("note").orEmpty())
    }

    private fun menuEntry(element: JsonElement): MenuEntry? {
        val o = element as? JsonObject ?: return null
        val name = o.text("name") ?: return null
        // A number price ("12.5") is shown as sent.
        val price = o.text("price") ?: (o["price"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toString()
        return MenuEntry(name = name, price = price.orEmpty(), note = o.text("note").orEmpty())
    }

    private fun links(o: JsonObject): List<ContextLink> = o.list("links").mapNotNull { element ->
        val link = element as? JsonObject ?: return@mapNotNull null
        val url = WebLinks.httpsUrl(link.text("url")) ?: return@mapNotNull null
        ContextLink(title = link.text("title") ?: WebLinks.host(url) ?: url, url = url)
    }

    // ── Lenient accessors ───────────────────────────────────────────────────

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A trimmed string, or null when missing, not a string or empty. */
    private fun JsonObject.text(key: String): String? = this[key]?.string()?.trim()?.takeIf { it.isNotEmpty() }

    /** A JSON number that is an integer (a string id is not an id). */
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
}
