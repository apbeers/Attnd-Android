package com.arcu.attnd.unit

import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.ScheduleItem
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The planner / Live Update fixture (shared spec §6–8; iOS uses the same data and expectations): a
 * DEMO-like festival in America/Chicago (CST, UTC−6) on Sat Nov 14 – Sun Nov 15, 2026, gates at
 * 10:00 AM Saturday. "now" is Sat 10:00 AM local (16:00 UTC). Coordinates are the DEMO layout's
 * (metres north/east of 32.7495, −97.3316); the Info Booth has none.
 *
 * Hearted: The Prairie Lights (101, 7–8:30 PM) and Neon Armadillo (114, 8–9 PM), which clash.
 */
object PlannerFixture {

    val now: Instant = Instant.parse("2026-11-14T16:00:00Z")

    fun sat(time: String): OffsetDateTime = EventTime.parse("2026-11-14T$time:00-06:00")!!
    fun sun(time: String): OffsetDateTime = EventTime.parse("2026-11-15T$time:00-06:00")!!

    private fun location(id: Int, name: String, category: String, tags: List<String>, coordinate: GeoPoint?) =
        EventLocation(id, name, "", "", category, null, null, "", coordinate, id, tags)

    private fun item(id: Int, name: String, start: OffsetDateTime, end: OffsetDateTime?, locationId: Int?, tags: List<String>, description: String = "") =
        ScheduleItem(id, name, description, start, end, locationId, locations.firstOrNull { it.id == locationId }?.name, tags)

    val locations = listOf(
        location(1, "Main Stage", "Stage", listOf("live music", "headliners", "free"), GeoPoint(32.750039, -97.3320272)),
        location(2, "Trinity Stage", "Stage", listOf("live music", "local bands", "acoustic", "free"), GeoPoint(32.7487814, -97.3303183)),
        location(3, "Panther Island Pavilion", "Stage", listOf("live music", "dj", "late night"), GeoPoint(32.7507576, -97.3294638)),
        location(4, "Chef Demo Kitchen", "Food", listOf("food", "cooking demo", "free tastings"), GeoPoint(32.7495898, -97.3309591)),
        location(5, "BBQ Pit Lane", "Food", listOf("food", "bbq"), GeoPoint(32.7485119, -97.3318136)),
        location(6, "Taco Row", "Food", listOf("food", "tacos", "vegetarian"), GeoPoint(32.7492305, -97.3325613)),
        location(7, "Kids Zone", "Family", listOf("kid-friendly", "family", "games"), GeoPoint(32.750578, -97.3327749)),
        location(8, "Craft Beer Garden", "Drinks", listOf("drinks", "beer", "21+"), GeoPoint(32.7503085, -97.3309591)),
        location(9, "Info Booth", "Services", listOf("services", "maps"), null),
    )

    val schedule = listOf(
        item(111, "Gates open", sat("10:00"), sat("10:30"), null, listOf("general", "free"), "Welcome! Grab a map at the Info Booth."),
        item(109, "Juniper Lane", sat("11:00"), sat("12:00"), 1, listOf("live music", "folk")),
        item(108, "Face painting & games", sat("11:00"), sat("13:00"), 7, listOf("family", "kid-friendly", "free")),
        item(106, "Brisket 101", sat("12:00"), sat("12:45"), 4, listOf("food", "demo", "bbq"), "Low and slow Texas brisket."),
        item(102, "Ana Morales Trio", sat("13:00"), sat("14:00"), 2, listOf("live music", "jazz")),
        item(107, "Street tacos at home", sat("14:15"), sat("15:00"), 4, listOf("food", "demo", "tacos")),
        item(103, "Big Sky Radio", sat("15:00"), sat("16:00"), 2, listOf("live music", "country"), "Texas country radio hits."),
        item(110, "Kolache masterclass", sat("16:15"), sat("17:00"), 4, listOf("food", "demo", "desserts")),
        item(105, "Velvet Mesquite", sat("17:00"), sat("18:00"), 1, listOf("live music", "blues")),
        item(112, "Beer tasting", sat("18:00"), sat("19:00"), 8, listOf("drinks", "beer", "21+")),
        item(113, "Sundown Choir", sat("18:15"), sat("18:45"), 2, listOf("live music", "gospel")),
        item(101, "The Prairie Lights", sat("19:00"), sat("20:30"), 1, listOf("live music", "country"), "Texas country headliners."),
        item(114, "Neon Armadillo", sat("20:00"), sat("21:00"), 2, listOf("live music", "rock")),
        item(104, "DJ Lone Star", sat("21:00"), sat("23:00"), 3, listOf("live music", "dj")),
        item(203, "Morning yoga", sun("09:00"), sun("10:00"), null, listOf("general", "free")),
        item(201, "Los Hermanos Flores", sun("12:00"), sun("13:00"), 1, listOf("live music", "tejano")),
        item(202, "Kid Rodeo", sun("14:00"), sun("15:00"), 1, listOf("live music", "rock")),
    )

    val hearts = Hearts(scheduleItems = setOf(101, 114))

    val bundle = EventBundle(
        schemaVersion = 2,
        contentVersion = 3,
        updatedAt = null,
        event = Event(
            id = 42, name = "Fort Worth Food & Music Festival", eventCode = "DEMO",
            description = "Food and music downtown.", address = "Panther Island, Fort Worth, TX",
            coordinate = GeoPoint(32.7495, -97.3316), startTime = sat("10:00"), endTime = sun("18:00"),
            timezone = "America/Chicago", status = EventStatus.PUBLISHED, paid = false, isDemo = true,
            flyerUrl = null, accentColor = null, logoUrl = null, contentVersion = 3, joinUrl = null,
        ),
        display = DisplayConfig.resolve(
            null, null, null, null, null, null, null, null, null, null, null, null, hasFlyer = false,
        ),
        categories = listOf("Stage", "Food", "Family", "Drinks", "Services"),
        locations = locations,
        schedule = schedule,
        notifications = listOf(
            EventNotification(31, "Set time change", "Velvet Mesquite moves to 8:30 pm on the Main Stage.", sat("09:20"),
                com.arcu.attnd.domain.NotificationPriority.URGENT),
        ),
    )
}
