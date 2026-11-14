package com.arcu.attnd.data.api

import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.data.net.HttpClient
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.data.net.UrlConnectionHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Supplies the bearer token. Implemented by the app with Play Integrity attestation + static key fallback. */
interface AuthTokenProvider {
    suspend fun token(): String

    /** Called after a 401: drop the cached token and get a fresh one, or null to give up. */
    suspend fun reauthenticate(): String?
}

/** Result of `GET events/<id>/bundle/`: 200 with a body, 304 without, or an error code. */
class BundleResponse(val code: Int, val json: String?, val etag: String?)

/**
 * Attnd REST API v2 (see API_V2.md) over plain HttpURLConnection + kotlinx.serialization.
 *
 * Every request carries `Authorization: Bearer <token>`. On a 401 the token provider re-attests
 * and the request is retried exactly once.
 */
class AttndApi(
    private val tokens: AuthTokenProvider,
    private val baseUrl: String = BASE_URL,
    private val http: HttpClient = UrlConnectionHttpClient(),
) {

    /** Set when any call answers 410/426: this build's API version has been retired. */
    val updateRequired = MutableStateFlow(false)

    /**
     * `GET /api/app-status/` (API-003). Unversioned and unauthenticated, so a build whose API
     * version was retired, or whose attestation fails, can still learn that it must update.
     */
    suspend fun appStatus(appVersion: String): AppStatusDto {
        val url = baseUrl.substringBeforeLast("v$API_VERSION/") + "app-status/" +
            "?platform=android&version=${encode(appVersion)}&api=$API_VERSION"
        return decode(http.execute("GET", url, mapOf("Accept" to "application/json"), null), AppStatusDto.serializer())
    }

    suspend fun nearbyEvents(lat: Double, lng: Double): NearbyResponseDto =
        get("events/nearby/?lat=$lat&lng=$lng", NearbyResponseDto.serializer())

    /** Exact code (case-insensitive) or exact name. Throws [ApiException] 404 when nothing live matches. */
    suspend fun lookup(query: String): LookupResponseDto =
        get("events/lookup/?q=${encode(query)}", LookupResponseDto.serializer())

    /**
     * Conditional bundle download. [etag] goes out as `If-None-Match`; [deviceId] as `X-Device-Id`
     * so the server can record last-seen for organizer stats.
     */
    suspend fun bundle(eventId: Int, etag: String?, deviceId: String?): BundleResponse {
        val headers = buildMap {
            if (etag != null) put("If-None-Match", etag)
            if (deviceId != null) put("X-Device-Id", deviceId)
        }
        val response = send("GET", "events/$eventId/bundle/", body = null, extraHeaders = headers)
        return BundleResponse(response.code, response.body, response.header("ETag"))
    }

    suspend fun join(eventId: Int, request: JoinRequest): StatusResponseDto =
        post("events/$eventId/join/", ApiJson.encodeToString(JoinRequest.serializer(), request))

    suspend fun leave(eventId: Int, request: LeaveRequest): StatusResponseDto =
        post("events/$eventId/leave/", ApiJson.encodeToString(LeaveRequest.serializer(), request))

    /** Registers or updates the device (push token, subscription preference). */
    suspend fun registerDevice(request: DeviceRequest): StatusResponseDto =
        post("devices/", ApiJson.encodeToString(DeviceRequest.serializer(), request))

    /** API-005: anonymous usage counts for [eventId]. Never carries a device id. */
    suspend fun sendUsage(eventId: Int, request: UsageRequest): UsageResponseDto =
        decode(
            send("POST", "events/$eventId/usage/", body = ApiJson.encodeToString(UsageRequest.serializer(), request)),
            UsageResponseDto.serializer(),
        )

    // ── Plumbing ────────────────────────────────────────────────────────────

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        decode(send("GET", path, body = null), serializer)

    private suspend fun post(path: String, body: String): StatusResponseDto =
        decode(send("POST", path, body = body), StatusResponseDto.serializer())

    private suspend fun send(
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val url = baseUrl + path
        val first = http.execute(method, url, headers(tokens.token()) + extraHeaders, body)
        if (first.code != 401) return first.also(::checkRetired)
        val fresh = tokens.reauthenticate() ?: return first
        return http.execute(method, url, headers(fresh) + extraHeaders, body).also(::checkRetired)
    }

    private fun checkRetired(response: HttpResponse) {
        if (response.code == 410 || response.code == 426) updateRequired.value = true
    }

    private fun headers(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
        "X-Requested-With" to "com.arcu.attnd",
    )

    private fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T {
        if (!response.isSuccessful) throw ApiException(response.code)
        val body = response.body ?: throw IOException("Empty response body")
        return try {
            ApiJson.decodeFromString(serializer, body)
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed response: ${e.message}", e)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        /** The API version this build speaks. Bump it together with [BASE_URL]. */
        const val API_VERSION = 2

        /** Host comes from the ATTND_SERVER build setting: develop builds use the dev server. */
        val BASE_URL = "https://${com.arcu.attnd.BuildConfig.ATTND_SERVER}/api/v$API_VERSION/"
    }
}
