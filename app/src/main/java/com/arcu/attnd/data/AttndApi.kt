package com.arcu.attnd.data

import retrofit2.http.GET
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

    @GET("{event_id}/locations/")
    suspend fun getLocations(@Path("event_id") eventId: Int): LocationResponse

    @GET("{event_id}/notifications/")
    suspend fun getNotifications(@Path("event_id") eventId: Int): NotificationResponse
}
