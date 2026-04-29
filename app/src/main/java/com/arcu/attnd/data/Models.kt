package com.arcu.attnd.data

import com.google.gson.annotations.SerializedName

data class EventResponse(
    val count: Int,
    val results: List<NearbyEvent>,
)

data class NearbyEvent(
    val event: Event,
    @SerializedName("distance_miles") val distanceMiles: Double,
    @SerializedName("starts_in_hours") val startsInHours: Double
)

data class Event(
    val id: Int,
    val name: String,
    @SerializedName("event_code") val eventCode: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("start_time") val startTime: String,
    val paid: Boolean,
    @SerializedName("flyer_url") val flyerUrl: String?,
    val address: String
)

data class LocationResponse(
    val count: Int,
    val results: List<EventLocation>
)

data class EventLocation(
    val id: Int,
    val name: String,
    val snippet: String,
    val description: String,
    val website: String?,
    @SerializedName("image_url") val imageUrl: String?,
    val latitude: Double,
    val longitude: Double,
    val address: String
)

data class NotificationResponse(
    val count: Int,
    val results: List<AppNotification>
)

data class AppNotification(
    val id: Int,
    val title: String,
    val body: String,
    @SerializedName("send_time") val sendTime: String,
    val sent: Boolean
)

data class JoinRequest(
    val platform: String = "android"
)

data class StatusResponse(
    val status: String
)
