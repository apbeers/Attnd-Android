package com.arcu.attnd.data.api

import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.data.net.HttpClient
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.data.net.UrlConnectionHttpClient
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.Redact
import com.arcu.attnd.domain.SectionCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/** Supplies the bearer token. Implemented by the app with Play Integrity attestation + static key fallback. */
interface AuthTokenProvider {
    suspend fun token(): String

    /** Called after a 401: drop the cached token and get a fresh one, or null to give up. */
    suspend fun reauthenticate(): String?
}

/** Result of `GET events/<id>/bundle/`: 200 with a body, 304 without, or an error code. */
class BundleResponse(val code: Int, val json: String?, val etag: String?)

/**
 * One finished API exchange, for the API-014 performance counts ([AttndApi.callListener]).
 * [path] is the path template (`/api/v2/events/{id}/bundle/`); [status] is null when no response came
 * ([timedOut] or offline).
 */
class ApiCall(
    val method: String,
    val path: String,
    val status: Int?,
    val ms: Long,
    val bytes: Long,
    val timedOut: Boolean = false,
) {
    val isBundle: Boolean get() = path.endsWith("/bundle/")

    /** Diagnostics uploads: not counted in the performance counts (their 429s are expected back-pressure). */
    val isDiagnostics: Boolean get() = "/diagnostics/" in path
}

/**
 * Attnd REST API v2 (see API_V2.md) over plain HttpURLConnection + kotlinx.serialization.
 *
 * Every request carries `Authorization: Bearer <token>`. On a 401 the token provider re-attests
 * and the request is retried exactly once.
 *
 * Logging spec §2: every call sends a random `X-Request-Id` (and `X-Diag-Session` while a debug session
 * runs, [diagSession]) and logs `net.request.started` / `finished` / `failed` with the path template,
 * status, ms, bytes, 304 and the request id (never the query string, token or body).
 */
class AttndApi(
    private val tokens: AuthTokenProvider,
    private val baseUrl: String = BASE_URL,
    private val http: HttpClient = UrlConnectionHttpClient(),
) {

    /** Set when any call answers 410/426: this build's API version has been retired. */
    val updateRequired = MutableStateFlow(false)

    /** The debug code sent as `X-Diag-Session` (dev builds always, production only in debug mode). */
    @Volatile
    var diagSession: () -> String? = { null }

    /** Called after every exchange (API-014 performance counts). Must not throw or block. */
    @Volatile
    var callListener: ((ApiCall) -> Unit)? = null

    /**
     * `GET /api/app-status/` (API-003). Unversioned and unauthenticated, so a build whose API
     * version was retired, or whose attestation fails, can still learn that it must update.
     */
    suspend fun appStatus(appVersion: String): AppStatusDto {
        val url = baseUrl.substringBeforeLast("v$API_VERSION/") + "app-status/" +
            "?platform=android&version=${encode(appVersion)}&api=$API_VERSION"
        val response = exchange("GET", url, mapOf("Accept" to "application/json"), quiet = false) { h ->
            http.execute("GET", url, h, null)
        }
        return decode(response, AppStatusDto.serializer())
    }

    suspend fun nearbyEvents(lat: Double, lng: Double): NearbyResponseDto =
        get("events/nearby/?lat=$lat&lng=$lng", NearbyResponseDto.serializer())

    /** Exact code (case-insensitive) or exact name. Throws [ApiException] 404 when nothing live matches. */
    suspend fun lookup(query: String): LookupResponseDto =
        get("events/lookup/?q=${encode(query)}", LookupResponseDto.serializer())

    /**
     * Conditional bundle download. [etag] goes out as `If-None-Match`; [deviceId] as `X-Device-Id`
     * so the server can record last-seen for organizer stats. `X-Attnd-Sections` (API-024 / API-027)
     * names the layout catalogue this build draws: the server then sends `layout` and Home in
     * `display.tabs` (its ETag is per catalogue version).
     */
    suspend fun bundle(eventId: Int, etag: String?, deviceId: String?): BundleResponse {
        val headers = buildMap {
            if (etag != null) put("If-None-Match", etag)
            if (deviceId != null) put("X-Device-Id", deviceId)
            put(SECTIONS_HEADER, SectionCatalogue.VERSION.toString())
        }
        val response = send("GET", "events/$eventId/bundle/", body = null, extraHeaders = headers)
        return BundleResponse(response.code, response.body, response.header("ETag"))
    }

    suspend fun join(eventId: Int, request: JoinRequest): StatusResponseDto =
        post("events/$eventId/join/", ApiJson.encodeToString(JoinRequest.serializer(), request))

    /**
     * API-026 long-poll for the event's preview phone: answers at once when the event's content version
     * is past [since], else after about 25 s.
     */
    suspend fun changes(eventId: Int, since: Int, deviceId: String): ChangesResponseDto = decode(
        send("GET", "events/$eventId/changes/?since=$since", body = null, extraHeaders = mapOf("X-Device-Id" to deviceId)),
        ChangesResponseDto.serializer(),
    )

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

    /**
     * API-013: a gzip-compressed diagnostics upload (`diagnostics/logs/` or `diagnostics/reports/`).
     * Not logged itself (a log upload that logs would never run dry); the caller reads status and Retry-After.
     */
    suspend fun uploadDiagnostics(path: String, gzipJson: ByteArray): HttpResponse {
        val url = baseUrl + path
        val extra = mapOf("Content-Type" to "application/json", "Content-Encoding" to "gzip")
        val send: suspend (Map<String, String>) -> HttpResponse = { h -> http.executeBytes("POST", url, h, gzipJson) }
        val first = exchange("POST", url, headers(tokens.token()) + extra, quiet = true, call = send)
        if (first.code != 401) return first
        val fresh = tokens.reauthenticate() ?: return first
        return exchange("POST", url, headers(fresh) + extra, quiet = true, attempt = 2, call = send)
    }

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
        val send: suspend (Map<String, String>) -> HttpResponse = { h -> http.execute(method, url, h, body) }
        val first = exchange(method, url, headers(tokens.token()) + extraHeaders, quiet = false, attempt = 1, call = send)
        if (first.code != 401) return first.also(::checkRetired)
        val fresh = tokens.reauthenticate() ?: return first
        // A retry is a new request with a new id.
        val retryId = newRequestId()
        AppLog.info(
            LogCategory.NET, LogEvent.REQUEST_RETRY, "401 from ${Redact.path(url)}: retrying after re-attesting",
            mapOf("path" to Redact.path(url), "req_id" to retryId, "reason" to "401"),
        )
        return exchange(method, url, headers(fresh) + extraHeaders, quiet = false, attempt = 2, requestId = retryId, call = send)
            .also(::checkRetired)
    }

    /** One HTTP exchange with its own request id, timed and logged (unless [quiet]). */
    private suspend fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        quiet: Boolean,
        attempt: Int = 1,
        requestId: String = newRequestId(),
        call: suspend (Map<String, String>) -> HttpResponse,
    ): HttpResponse {
        val path = Redact.path(url)
        val all = buildMap {
            putAll(headers)
            put("X-Request-Id", requestId)
            diagSession()?.let { put("X-Diag-Session", it) }
        }
        if (!quiet) {
            AppLog.trace(LogCategory.NET, LogEvent.REQUEST_STARTED, "$method $path", mapOf("method" to method, "path" to path, "req_id" to requestId))
        }
        val started = System.nanoTime()
        try {
            val response = call(all)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (!quiet) {
                val level = when {
                    response.code >= 500 -> LogLevel.ERROR
                    response.code >= 400 && response.code != 404 -> LogLevel.WARNING
                    else -> LogLevel.DEBUG
                }
                val cache = when {
                    response.code == 304 -> "304"
                    headers.containsKey("If-None-Match") -> "etag"
                    else -> "none"
                }
                AppLog.log(
                    level, LogCategory.NET, LogEvent.REQUEST_FINISHED, "$method $path ${response.code} in $ms ms",
                    mapOf(
                        "method" to method, "path" to path, "status" to response.code, "ms" to ms, "req_id" to requestId,
                        "bytes" to response.bytes, "cache" to cache, "attempt" to attempt,
                    ),
                )
            }
            notify(ApiCall(method, path, response.code, ms, response.bytes))
            return response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ms = (System.nanoTime() - started) / 1_000_000
            val kind = AppLog.errorKind(e)
            if (!quiet) {
                AppLog.warning(
                    LogCategory.NET, LogEvent.REQUEST_FAILED, "$method $path failed after $ms ms ($kind)",
                    mapOf("method" to method, "path" to path, "req_id" to requestId, "ms" to ms, "error" to kind, "domain" to e.javaClass.simpleName),
                )
            }
            notify(ApiCall(method, path, null, ms, 0, timedOut = kind == "timeout"))
            throw e
        }
    }

    private fun notify(call: ApiCall) {
        try {
            callListener?.invoke(call)
        } catch (_: Exception) {
            // Performance counting must never fail a request.
        }
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

    /** 16 random hex characters (the server accepts 8–64 of `[A-Za-z0-9._:-]`). */
    private fun newRequestId(): String = java.lang.Long.toHexString(Random.nextLong()).padStart(16, '0')

    companion object {
        /** The API version this build speaks. Bump it together with [BASE_URL]. */
        const val API_VERSION = 2

        /** The layout catalogue header (API-024), its value [SectionCatalogue.VERSION]. */
        const val SECTIONS_HEADER = "X-Attnd-Sections"

        /**
         * Host from the ATTND_SERVER build setting (develop builds use the dev server), or the test server chosen in a
         * dev / debug build ([com.arcu.attnd.data.net.ServerHost], fixed per process).
         */
        val BASE_URL: String get() = "${com.arcu.attnd.data.net.ServerHost.origin}/api/v$API_VERSION/"
    }
}
