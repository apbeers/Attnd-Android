package com.arcu.attnd.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * Wire format of the v2 API (see API_V2.md).
 *
 * Every response field is nullable with a `null` default: the contract says clients must accept
 * missing keys, and [ApiJson] ignores unknown keys. ApiMapper turns these into the non-null
 * domain models and applies the documented defaults.
 */

/** JSON configuration shared by the API client, the disk cache and the tests. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    // A null for a non-nullable field (or an unknown enum) falls back to the declared default.
    coerceInputValues = true
    isLenient = true
    // Requests: send declared defaults (e.g. platform) but leave out null optionals.
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class EventDto(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("event_code") val eventCode: String? = null,
    val description: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    val timezone: String? = null,
    val status: String? = null,
    val paid: Boolean? = null,
    @SerialName("is_demo") val isDemo: Boolean? = null,
    @SerialName("flyer_url") val flyerUrl: String? = null,
    @SerialName("accent_color") val accentColor: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("content_version") val contentVersion: Int? = null,
    @SerialName("join_url") val joinUrl: String? = null,
    /** API-016: the event's Attnd Studio website, or null. */
    @SerialName("website_url") val websiteUrl: String? = null,
    /** API-018 (`bundle/` only), raw: decoded leniently by [ContextMapper]. */
    val context: JsonElement? = null,
    /** API-023 `{light, dark}` × primary / secondary / tertiary, raw ([ContextMapper.theme]). */
    val theme: JsonElement? = null,
)

@Serializable
data class NearbyResponseDto(
    val count: Int? = null,
    val results: List<NearbyItemDto?>? = null,
)

@Serializable
data class NearbyItemDto(
    val event: EventDto? = null,
    @SerialName("distance_miles") val distanceMiles: Double? = null,
    @SerialName("starts_in_hours") val startsInHours: Double? = null,
)

@Serializable
data class LookupResponseDto(
    val event: EventDto? = null,
)

@Serializable
data class LocationDto(
    val id: Int? = null,
    val name: String? = null,
    val snippet: String? = null,
    val description: String? = null,
    val category: String? = null,
    val website: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
    /** API-010; missing from older servers. */
    val tags: List<String?>? = null,
    /** API-022 / API-031: the credit of a photo Attnd found ("Photo: Google Maps" for a live Google photo). */
    @SerialName("image_credit") val imageCredit: String? = null,
    /** API-031: https JSON with a live Google photo's authors. */
    @SerialName("image_attribution_url") val imageAttributionUrl: String? = null,
    /** API-018, raw ([ContextMapper.locationContext]). */
    val context: JsonElement? = null,
)

@Serializable
data class ScheduleItemDto(
    val id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("location_id") val locationId: Int? = null,
    @SerialName("location_name") val locationName: String? = null,
    val tags: List<String?>? = null,
    /** API-018 (performers with API-030 `apple_music_url`), raw ([ContextMapper.scheduleItemContext]). */
    val context: JsonElement? = null,
)

@Serializable
data class NotificationDto(
    val id: Int? = null,
    val title: String? = null,
    val body: String? = null,
    @SerialName("send_time") val sendTime: String? = null,
    /** API-011: "normal" or "urgent"; anything else (or missing) is normal. */
    val priority: String? = null,
)

@Serializable
data class DisplayDto(
    val tabs: List<String?>? = null,
    @SerialName("default_tab") val defaultTab: String? = null,
    @SerialName("locations_style") val locationsStyle: String? = null,
    @SerialName("schedule_style") val scheduleStyle: String? = null,
    @SerialName("map_clustering") val mapClustering: Boolean? = null,
    @SerialName("map_fit_all") val mapFitAll: Boolean? = null,
    @SerialName("show_location_images") val showLocationImages: Boolean? = null,
    @SerialName("enable_search") val enableSearch: Boolean? = null,
    @SerialName("group_by_category") val groupByCategory: Boolean? = null,
    @SerialName("secondary_pane") val secondaryPane: String? = null,
    @SerialName("accent_color") val accentColor: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
)

@Serializable
data class BundleDto(
    @SerialName("schema_version") val schemaVersion: Int? = null,
    @SerialName("content_version") val contentVersion: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val event: EventDto? = null,
    val display: DisplayDto? = null,
    val categories: List<String?>? = null,
    val locations: List<LocationDto?>? = null,
    val schedule: List<ScheduleItemDto?>? = null,
    val notifications: List<NotificationDto?>? = null,
    /** API-027: what the Live Update follows, raw ([ContextMapper.liveActivity]). */
    @SerialName("live_activity") val liveActivity: JsonElement? = null,
    /**
     * API-024 / API-027 server-driven screens, kept raw until the app sends `X-Attnd-Sections`
     * (it doesn't yet, so current servers leave this null). Mapped in a later phase.
     */
    val layout: JsonElement? = null,
)

// ── Requests ────────────────────────────────────────────────────────────────

const val PLATFORM = "android"

@Serializable
data class JoinRequest(
    @SerialName("device_id") val deviceId: String,
    val platform: String = PLATFORM,
    @SerialName("push_token") val pushToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    /** API-026: the organizer's preview token from a "Preview on my phone" link; left out without one. Never logged. */
    @SerialName("preview_token") val previewToken: String? = null,
)

@Serializable
data class LeaveRequest(
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class DeviceRequest(
    @SerialName("device_id") val deviceId: String,
    val platform: String = PLATFORM,
    @SerialName("push_token") val pushToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val subscribed: Boolean? = null,
)

/**
 * `POST events/<id>/usage/` (API-005): anonymous counts per 5-minute window. Deliberately has no device id or
 * any other identifier; `detail` is left out when absent.
 */
@Serializable
data class UsageRequest(
    val platform: String = PLATFORM,
    val events: List<UsageEntryDto> = emptyList(),
)

@Serializable
data class UsageEntryDto(
    val name: String = "",
    val detail: String? = null,
    val count: Int = 1,
    /** ISO 8601 UTC start of the 5-minute window the counts fell in. */
    val at: String? = null,
)

@Serializable
data class UsageResponseDto(
    val status: String? = null,
    val accepted: Int? = null,
)

/** GET /api/app-status/ (API-003). Unversioned and frozen: only ever gains keys. */
@Serializable
data class AppStatusDto(
    val update: String? = null,
    @SerialName("min_version") val minVersion: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("update_url") val updateUrl: String? = null,
    val message: String? = null,
)

@Serializable
data class StatusResponseDto(
    val status: String? = null,
    @SerialName("content_version") val contentVersion: Int? = null,
    val subscribed: Boolean? = null,
    @SerialName("event_id") val eventId: Int? = null,
    /** API-026 (join answer): true = this is now the event's preview phone, false = joined as an attendee. */
    val preview: Boolean? = null,
)

/** `GET events/<id>/changes/?since=` (API-026, preview phones only). */
@Serializable
data class ChangesResponseDto(
    @SerialName("content_version") val contentVersion: Int? = null,
    val changed: Boolean? = null,
    val preview: Boolean? = null,
    @SerialName("retry_after") val retryAfter: Int? = null,
)
