package com.arcu.attnd.domain

/*
 * API-018 rich context (`event.context`, `locations[].context`, `schedule[].context`): compact
 * text the organizer's AI import extracts for the on-device assistant and the detail screens.
 * Decoded leniently by [com.arcu.attnd.data.api.ContextMapper] exactly like iOS (`Models.swift`):
 * every key may be missing; strings are trimmed (missing → ""), list entries without their
 * required text are dropped, links are absolute `https` only, an unknown amenity kind is `other`,
 * and an object with nothing in it is null.
 */

/** A link the organizer added ("Bag policy", "Artist site"); `https` with a host only. */
data class ContextLink(val title: String, val url: String)

/** One FAQ entry ("Can I bring a bag?" · "Clear bags only."). */
data class FaqEntry(val question: String, val answer: String)

enum class AmenityKind(val key: String, val label: String) {
    RESTROOMS("restrooms", "Restrooms"),
    WATER("water", "Water"),
    ATM("atm", "ATM"),
    FIRST_AID("first_aid", "First aid"),
    CHARGING("charging", "Phone charging"),
    LOCKERS("lockers", "Lockers"),
    INFO("info", "Information"),
    ACCESSIBLE_ENTRANCE("accessible_entrance", "Accessible entrance"),
    FOOD("food", "Food"),
    MERCH("merch", "Merch"),
    PARKING("parking", "Parking"),
    SHUTTLE("shuttle", "Shuttle"),
    OTHER("other", "Other"),
    ;

    companion object {
        /** Case-insensitive; anything unknown is [OTHER]. */
        fun of(key: String?): AmenityKind = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: OTHER
    }
}

/** Restrooms, water, first aid… optionally at one of the event's locations. */
data class Amenity(
    val kind: AmenityKind,
    val name: String,
    val locationId: Int? = null,
    val note: String = "",
)

data class EventContext(
    val about: String = "",
    val faq: List<FaqEntry> = emptyList(),
    val amenities: List<Amenity> = emptyList(),
    val accessibility: String = "",
    val policies: String = "",
    val gettingThere: String = "",
    val weatherPlan: String = "",
    val links: List<ContextLink> = emptyList(),
) {
    val isEmpty: Boolean get() = this == EventContext()
}

/** One menu line ("Brisket plate" · "$14" · "Gluten-free"). */
data class MenuEntry(val name: String, val price: String = "", val note: String = "")

data class LocationContext(
    val about: String = "",
    val menu: List<MenuEntry> = emptyList(),
    val hours: String = "",
    val accessibility: String = "",
    val ageLimit: String = "",
    val links: List<ContextLink> = emptyList(),
) {
    val isEmpty: Boolean get() = this == LocationContext()
}

/** A performer, speaker or vendor of a schedule item. */
data class Performer(
    val name: String,
    val bio: String = "",
    /** As sent ("country", "Texas swing"), trimmed, empty ones dropped. */
    val genres: List<String> = emptyList(),
    /** API-030: the artist page in Apple Music, only an https link to music.apple.com (or a subdomain). */
    val appleMusicUrl: String? = null,
)

data class ScheduleItemContext(
    val about: String = "",
    val performers: List<Performer> = emptyList(),
    val ageLimit: String = "",
    val price: String = "",
    val accessibility: String = "",
    val links: List<ContextLink> = emptyList(),
) {
    val isEmpty: Boolean get() = this == ScheduleItemContext()

    /** Every performer's genres, distinct (case-insensitively), in order. */
    val genres: List<String>
        get() {
            val seen = HashSet<String>()
            return performers.flatMap { it.genres }.filter { seen.add(it.lowercase()) }
        }
}

/** API-030: only an absolute https link to Apple Music (music.apple.com or a subdomain), else null. */
object AppleMusicLinks {
    fun of(raw: String?): String? {
        val url = WebLinks.httpsUrl(raw) ?: return null
        val host = WebLinks.host(url)?.lowercase() ?: return null
        return url.takeIf { host == "music.apple.com" || host.endsWith(".music.apple.com") }
    }
}

// ── API-023 theme ────────────────────────────────────────────────────────────

/**
 * The three colours of one appearance, each "#RRGGBB" upper-cased (anything else → null: the
 * default for that role). `primary` = the tint, `secondary` = soft surfaces, `tertiary` =
 * highlights (progress, badges). The server already adjusted them for contrast.
 */
data class ThemeColors(val primary: String? = null, val secondary: String? = null, val tertiary: String? = null) {
    val isEmpty: Boolean get() = primary == null && secondary == null && tertiary == null

    companion object {
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        /** "#RRGGBB" (upper-cased), else null. */
        fun hex(value: String?): String? = value?.trim()?.takeIf { HEX.matches(it) }?.uppercase()
    }
}

/** `event.theme` (API-023): light and dark colours; a missing mode or role uses the defaults. Null when empty. */
data class EventTheme(val light: ThemeColors? = null, val dark: ThemeColors? = null) {
    val isEmpty: Boolean get() = light == null && dark == null

    fun colors(dark: Boolean): ThemeColors? = if (dark) this.dark else light
}

// ── API-027 `live_activity` ─────────────────────────────────────────────────

/**
 * `bundle.live_activity`: what the Live Update follows, its buttons and stale text. Lenient:
 * missing or unknown values keep today's behaviour (iOS `LiveActivitySettings`).
 */
data class LiveActivitySettings(
    val follows: Follows = Follows.MY_SCHEDULE,
    val locationId: Int? = null,
    /** In order, from `add`, `skip`, `map`; null = the defaults. */
    val buttons: List<String>? = null,
    /** ≤ 60 characters. */
    val staleText: String? = null,
) {
    enum class Follows(val key: String) {
        MY_SCHEDULE("my_schedule"), EVERYTHING("everything"), LOCATION("location");

        companion object {
            fun of(key: String?): Follows? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
        }
    }

    /** The location to follow, when it exists in the bundle (else My schedule's rule). */
    fun followedLocation(bundle: EventBundle): Int? =
        locationId?.takeIf { follows == Follows.LOCATION && bundle.location(it) != null }

    /** The buttons this app draws: Skip and Map (`add` is accepted and not drawn, as on iOS). */
    val liveButtons: List<String> get() = (buttons ?: listOf("skip", "map")).filter { it == "skip" || it == "map" }

    companion object {
        val DEFAULTS = LiveActivitySettings()
        val KNOWN_BUTTONS = listOf("add", "skip", "map")
        const val MAX_STALE_TEXT = 60
    }
}
