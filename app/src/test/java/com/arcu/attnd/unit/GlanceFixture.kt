package com.arcu.attnd.unit

import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.ScheduleItem
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The glance/hearts fixture (based on the search spec's; the same decisions as iOS): an event in
 * America/Chicago (CST, UTC−6) on Sat Nov 14 – Sun Nov 15, 2026; "now" is Sat 2:00 PM local (20:00 UTC).
 */
object GlanceFixture {

    val now: Instant = Instant.parse("2026-11-14T20:00:00Z")

    /** A local Chicago time on Nov 14 (Sat) or 15 (Sun), as stored by the API (with offset). */
    fun sat(time: String): OffsetDateTime = EventTime.parse("2026-11-14T$time:00-06:00")!!
    fun sun(time: String): OffsetDateTime = EventTime.parse("2026-11-15T$time:00-06:00")!!

    fun item(
        id: Int,
        name: String,
        start: OffsetDateTime?,
        end: OffsetDateTime? = null,
        locationId: Int? = null,
        locationName: String? = null,
        tags: List<String> = emptyList(),
    ) = ScheduleItem(id, name, "", start, end, locationId, locationName, tags)

    fun location(id: Int, name: String, category: String? = null, snippet: String = "", coordinate: GeoPoint? = null) =
        EventLocation(id, name, snippet, "", category, null, null, "", coordinate, id)

    val bundle = EventBundle(
        schemaVersion = 2,
        contentVersion = 1,
        updatedAt = null,
        event = Event(
            id = 77, name = "Fort Worth Food & Music Festival", eventCode = "FWFM2026",
            description = "Food and music downtown.", address = "Sundance Square, Fort Worth, TX",
            coordinate = GeoPoint(32.75, -97.33), startTime = sat("10:00"), endTime = sun("22:00"),
            timezone = "America/Chicago", status = EventStatus.PUBLISHED, paid = true, isDemo = false,
            flyerUrl = null, accentColor = null, logoUrl = null, contentVersion = 1, joinUrl = null,
        ),
        display = DisplayConfig.resolve(
            null, null, null, null, null, null, null, null, null, null, null, null, hasFlyer = false,
        ),
        categories = listOf("Stage", "Food"),
        locations = listOf(
            location(10, "Main Stage", category = "Stage", snippet = "Live music", coordinate = GeoPoint(32.751, -97.331)),
            location(11, "Taco Truck", category = "Food", snippet = "Street tacos", coordinate = GeoPoint(32.752, -97.332)),
            location(12, "Café Olé", category = "Food", snippet = "Coffee & pastries", coordinate = null),
        ),
        schedule = listOf(
            item(1, "Roaming Mariachi", sat("12:30"), sat("15:00"), 10, "Main Stage", listOf("live music")), // now
            item(9, "Sound Bath", sat("13:30"), sat("14:30"), 10, "Main Stage", listOf("wellness")), // now, started later
            item(2, "Taco Tasting", sat("14:30"), sat("15:30"), 11, "Taco Truck", listOf("Food")), // next
            item(3, "Jazz Night", sat("19:00"), sat("21:00"), 10, "Main Stage", listOf("Music")), // later, tonight
            item(4, "Morning Yoga", sat("08:00"), sat("09:00")), // ended, no location
            item(5, "Sunday Brunch", sun("10:00"), sun("12:00"), 12, "Café Olé", listOf("Food")),
            item(6, "Late set", sat("21:30"), null, 10, "Main Stage", listOf("Music")), // later, no end
            item(7, "Sound Check", sat("09:00"), sat("10:00"), 10, "Main Stage", listOf("Music")), // ended
            item(8, "Drum Circle", sat("14:00"), sat("14:45"), tags = listOf("live music")), // starts now, no location
        ),
        notifications = listOf(
            EventNotification(20, "Gates open", "Come on in through the north entrance", sat("09:55")),
            EventNotification(21, "Weather update", "Rain expected around 4pm, bring a poncho", sat("11:00")),
        ),
    )
}
