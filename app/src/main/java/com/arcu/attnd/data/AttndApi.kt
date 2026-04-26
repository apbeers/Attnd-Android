package com.arcu.attnd.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface AttndApi {
    @GET("nearby_events/{lat}/{lng}/")
    suspend fun getNearbyEvents(
        @Path("lat") lat: Double,
        @Path("lng") lng: Double
    ): EventResponse

    @GET("lookup/")
    suspend fun lookupEvent(@Query("q") query: String): Event

    @GET("event_by_code_or_name/")
    suspend fun fuzzySearchEvents(@Query("data") query: String): Event

    @GET("{event_id}/")
    suspend fun getEvent(@Path("event_id") eventId: Int): Event

    @GET("{event_id}/locations/")
    suspend fun getLocations(@Path("event_id") eventId: Int): LocationResponse

    @GET("{event_id}/locations/{location_id}/")
    suspend fun getLocationDetails(
        @Path("event_id") eventId: Int,
        @Path("location_id") locationId: Int
    ): EventLocation

    @GET("{event_id}/notifications/")
    suspend fun getNotifications(@Path("event_id") eventId: Int): NotificationResponse

    @GET("{event_id}/notifications/{notification_id}/")
    suspend fun getNotificationDetails(
        @Path("event_id") eventId: Int,
        @Path("notification_id") notificationId: Int
    ): AppNotification

    @POST("{event_id}/join/{device_id}/")
    suspend fun joinEvent(
        @Path("event_id") eventId: Int,
        @Path("device_id") deviceId: String,
        @Body request: JoinRequest
    ): StatusResponse

    @POST("{event_id}/leave/{device_id}/")
    suspend fun leaveEvent(
        @Path("event_id") eventId: Int,
        @Path("device_id") deviceId: String
    ): StatusResponse

    @POST("pushnotifications/subscribe/{device_id}/")
    suspend fun subscribePush(
        @Path("device_id") deviceId: String
    ): StatusResponse

    @POST("pushnotifications/unsubscribe/{device_id}/")
    suspend fun unsubscribePush(
        @Path("device_id") deviceId: String
    ): StatusResponse
}
