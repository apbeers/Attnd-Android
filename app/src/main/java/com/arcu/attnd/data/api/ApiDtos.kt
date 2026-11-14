package com.arcu.attnd.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
)

@Serializable
data class NotificationDto(
    val id: Int? = null,
    val title: String? = null,
    val body: String? = null,
    @SerialName("send_time") val sendTime: String? = null,
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
)

// ── Requests ────────────────────────────────────────────────────────────────

const val PLATFORM = "android"

@Serializable
data class JoinRequest(
    @SerialName("device_id") val deviceId: String,
    val platform: String = PLATFORM,
    @SerialName("push_token") val pushToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
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

@Serializable
data class StatusResponseDto(
    val status: String? = null,
    @SerialName("content_version") val contentVersion: Int? = null,
    val subscribed: Boolean? = null,
    @SerialName("event_id") val eventId: Int? = null,
)
