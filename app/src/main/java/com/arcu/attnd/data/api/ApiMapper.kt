package com.arcu.attnd.data.api

import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventStatus
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.NearbyEvent
import com.arcu.attnd.domain.ScheduleItem

/**
 * Converts v2 DTOs into domain models, applying the documented default for every missing key.
 * Objects without an `id` cannot be addressed and are skipped. Pure JVM code (unit tested).
 */
object ApiMapper {

    /** Decodes a bundle response body. Returns null when it is not valid JSON or has no event. */
    fun parseBundle(json: String): EventBundle? {
        val dto: BundleDto? = try {
            ApiJson.decodeFromString(BundleDto.serializer(), json)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            null
        }
        return dto?.let { bundle(it) }
    }

    fun bundle(dto: BundleDto): EventBundle? {
        val event = event(dto.event) ?: return null
        val categories = dto.categories.orEmpty().filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return EventBundle(
            schemaVersion = dto.schemaVersion ?: 2,
            contentVersion = dto.contentVersion ?: event.contentVersion,
            updatedAt = EventTime.parse(dto.updatedAt),
            event = event,
            display = display(dto.display, event),
            categories = categories,
            locations = dto.locations.orEmpty().mapNotNull { location(it) },
            schedule = dto.schedule.orEmpty().mapNotNull { scheduleItem(it) },
            notifications = dto.notifications.orEmpty().mapNotNull { notification(it) },
        )
    }

    fun event(dto: EventDto?): Event? {
        dto ?: return null
        val id = dto.id ?: return null
        val paid = dto.paid ?: false
        return Event(
            id = id,
            name = dto.name.orEmpty(),
            eventCode = dto.eventCode.orEmpty(),
            description = dto.description.orEmpty(),
            address = dto.address.orEmpty(),
            coordinate = GeoPoint.of(dto.latitude, dto.longitude),
            startTime = EventTime.parse(dto.startTime),
            endTime = EventTime.parse(dto.endTime),
            timezone = dto.timezone?.takeIf { it.isNotBlank() },
            status = when (dto.status?.lowercase()) {
                "archived" -> EventStatus.ARCHIVED
                "published", null -> EventStatus.PUBLISHED
                else -> EventStatus.UNKNOWN
            },
            paid = paid,
            // Older servers only sent `paid`; an unpaid event is a demo.
            isDemo = dto.isDemo ?: !paid,
            flyerUrl = dto.flyerUrl?.takeIf { it.isNotBlank() },
            accentColor = dto.accentColor?.takeIf { DisplayConfig.parseHexColor(it) != null },
            logoUrl = dto.logoUrl?.takeIf { it.isNotBlank() },
            contentVersion = dto.contentVersion ?: 0,
            joinUrl = dto.joinUrl?.takeIf { it.isNotBlank() },
        )
    }

    fun display(dto: DisplayDto?, event: Event): DisplayConfig {
        val hasFlyer = event.flyerUrl != null
        if (dto == null) return DisplayConfig.defaults(hasFlyer, event.accentColor, event.logoUrl)
        return DisplayConfig.resolve(
            rawTabs = dto.tabs,
            rawDefaultTab = dto.defaultTab,
            rawLocationsStyle = dto.locationsStyle,
            rawScheduleStyle = dto.scheduleStyle,
            mapClustering = dto.mapClustering,
            mapFitAll = dto.mapFitAll,
            showLocationImages = dto.showLocationImages,
            enableSearch = dto.enableSearch,
            groupByCategory = dto.groupByCategory,
            rawSecondaryPane = dto.secondaryPane,
            accentColor = dto.accentColor ?: event.accentColor,
            logoUrl = dto.logoUrl ?: event.logoUrl,
            hasFlyer = hasFlyer,
        )
    }

    fun nearby(dto: NearbyItemDto?): NearbyEvent? {
        val event = event(dto?.event) ?: return null
        return NearbyEvent(event, dto?.distanceMiles, dto?.startsInHours)
    }

    fun location(dto: LocationDto?): EventLocation? {
        dto ?: return null
        val id = dto.id ?: return null
        return EventLocation(
            id = id,
            name = dto.name.orEmpty(),
            snippet = dto.snippet.orEmpty(),
            description = dto.description.orEmpty(),
            category = dto.category?.trim()?.takeIf { it.isNotEmpty() },
            website = dto.website?.takeIf { it.isNotBlank() },
            imageUrl = dto.imageUrl?.takeIf { it.isNotBlank() },
            address = dto.address.orEmpty(),
            coordinate = GeoPoint.of(dto.latitude, dto.longitude),
            sortOrder = dto.sortOrder ?: 0,
        )
    }

    fun scheduleItem(dto: ScheduleItemDto?): ScheduleItem? {
        dto ?: return null
        val id = dto.id ?: return null
        return ScheduleItem(
            id = id,
            name = dto.name.orEmpty(),
            description = dto.description.orEmpty(),
            startTime = EventTime.parse(dto.startTime),
            endTime = EventTime.parse(dto.endTime),
            locationId = dto.locationId,
            locationName = dto.locationName?.takeIf { it.isNotBlank() },
            tags = dto.tags.orEmpty().filterNotNull().filter { it.isNotBlank() },
        )
    }

    fun notification(dto: NotificationDto?): EventNotification? {
        dto ?: return null
        val id = dto.id ?: return null
        return EventNotification(
            id = id,
            title = dto.title.orEmpty(),
            body = dto.body.orEmpty(),
            sendTime = EventTime.parse(dto.sendTime),
        )
    }
}
