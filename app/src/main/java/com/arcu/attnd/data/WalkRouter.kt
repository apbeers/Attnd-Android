package com.arcu.attnd.data

import android.content.Context
import android.content.pm.PackageManager
import com.arcu.attnd.data.net.HttpClient
import com.arcu.attnd.data.net.UrlConnectionHttpClient
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DayRoutes
import com.arcu.attnd.domain.EncodedPolyline
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.WalkRoute
import com.arcu.attnd.domain.Walking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Walking routes for the map card: Google Routes API v2 `computeRoutes` (travel mode WALK) over
 * HttpURLConnection, authenticated with the app's Maps key, which is restricted to this app's package
 * and signing certificates; REST calls prove that with `X-Android-Package` / `X-Android-Cert`.
 *
 * Falls back to [Walking.estimate] (a dashed straight line) when offline, on any error (including a
 * key that rejects this build's package or certificate), when there is no route, or when the route is
 * implausibly long ([Walking.accept]). One result is cached per location and reused while the user is
 * within 50 m of where it was computed ([Walking.shouldReroute]); failures are not cached, so the
 * route appears once back online.
 */
class WalkRouter(
    context: Context,
    private val apiKey: String,
    private val http: HttpClient = UrlConnectionHttpClient(connectTimeoutMs = 10_000, readTimeoutMs = 15_000),
) {
    private val appContext = context.applicationContext
    private class Cached(val origin: GeoPoint, val destination: GeoPoint, val route: WalkRoute)

    private val cache = object : LinkedHashMap<Int, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Cached>?): Boolean = size > MAX_CACHED
    }

    suspend fun route(locationId: Int, origin: GeoPoint, destination: GeoPoint): WalkRoute {
        synchronized(cache) { cache[locationId] }
            ?.takeIf { it.destination == destination && !Walking.shouldReroute(it.origin, origin) }
            ?.let { return it.route }
        val straight = Geo.distanceMeters(origin, destination)
        val started = System.nanoTime()
        val fetched = try {
            fetch(origin, destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug(
                LogCategory.NET, LogEvent.ROUTE_FETCHED, "Walking route failed (${AppLog.errorKind(e)}): estimate",
                mapOf("ms" to (System.nanoTime() - started) / 1_000_000, "ok" to false, "fallback" to true, "m" to straight.toInt()),
            )
            return Walking.estimate(origin, destination)
        }
        val accepted = fetched?.takeIf { Walking.accept(it.distanceMeters, straight) }
        AppLog.debug(
            LogCategory.NET, LogEvent.ROUTE_FETCHED, if (accepted != null) "Walking route" else "No usable walking route: estimate",
            mapOf("ms" to (System.nanoTime() - started) / 1_000_000, "ok" to (fetched != null), "fallback" to (accepted == null), "m" to (accepted?.distanceMeters ?: straight).toInt()),
        )
        // A definite answer (a route, or none / an implausible one): cached.
        val route = fetched?.takeIf { Walking.accept(it.distanceMeters, straight) } ?: Walking.estimate(origin, destination)
        synchronized(cache) { cache[locationId] = Cached(origin, destination, route) }
        return route
    }

    private val dayCache = object : LinkedHashMap<String, List<WalkRoute?>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<WalkRoute?>>?): Boolean = size > MAX_DAYS_CACHED
    }

    /**
     * The day's walking legs in one `computeRoutes` call (assistant spec §5): [stops] in order (2–25,
     * [DayRoutes.waypoints]) as origin, intermediates and destination; one entry per leg (null = keep
     * the estimate for that leg). The answer is cached per [key] (e.g. event + day + stops) for the day;
     * a failure returns all nulls and isn't cached. Pass the result to [DayRoutes.withServiceLegs],
     * which applies the 2.5 × plausibility rule per leg.
     */
    suspend fun dayLegs(key: String, stops: List<GeoPoint>): List<WalkRoute?> {
        val legs = stops.size - 1
        if (legs < 1 || stops.size > DayRoutes.MAX_STOPS) return List(legs.coerceAtLeast(0)) { null }
        synchronized(dayCache) { dayCache[key] }?.let { return it }
        val started = System.nanoTime()
        val result = try {
            fetchDay(stops)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug(
                LogCategory.NET, LogEvent.ROUTE_FETCHED, "Day route failed (${AppLog.errorKind(e)}): estimates",
                mapOf("ms" to (System.nanoTime() - started) / 1_000_000, "ok" to false, "fallback" to true, "legs" to legs),
            )
            return List(legs) { null }
        }
        AppLog.debug(
            LogCategory.NET, LogEvent.ROUTE_FETCHED, "Day route",
            mapOf("ms" to (System.nanoTime() - started) / 1_000_000, "ok" to true, "fallback" to result.any { it == null }, "legs" to legs),
        )
        synchronized(dayCache) { dayCache[key] = result }
        return result
    }

    /** Per-leg routes from one multi-waypoint request; throws on network / HTTP errors. */
    private suspend fun fetchDay(stops: List<GeoPoint>): List<WalkRoute?> {
        if (apiKey.isBlank()) throw IllegalStateException("No Maps API key in this build")
        fun waypoint(p: GeoPoint) = Waypoint(WaypointLocation(LatLngDto(p.latitude, p.longitude)))
        val body = routesJson.encodeToString(
            ComputeDayRouteRequest.serializer(),
            ComputeDayRouteRequest(
                origin = waypoint(stops.first()),
                destination = waypoint(stops.last()),
                intermediates = stops.subList(1, stops.size - 1).map { waypoint(it) },
            ),
        )
        val headers = buildMap {
            put("X-Goog-Api-Key", apiKey)
            put("X-Goog-FieldMask", DAY_FIELD_MASK)
            put("X-Android-Package", appContext.packageName)
            AppSignature.sha1(appContext)?.let { put("X-Android-Cert", it) }
        }
        val response = http.execute("POST", ENDPOINT, headers, body)
        if (!response.isSuccessful) throw IllegalStateException("Routes API HTTP ${response.code}")
        val parsed = routesJson.decodeFromString(ComputeDayRouteResponse.serializer(), response.body.orEmpty().ifBlank { "{}" })
        val legs = parsed.routes.firstOrNull()?.legs.orEmpty()
        return List(stops.size - 1) { index -> legs.getOrNull(index)?.toWalkRoute() }
    }

    /** The service's route, null when it has none; throws on network / HTTP errors. */
    private suspend fun fetch(origin: GeoPoint, destination: GeoPoint): WalkRoute? {
        if (apiKey.isBlank()) throw IllegalStateException("No Maps API key in this build")
        val body = routesJson.encodeToString(
            ComputeRoutesRequest.serializer(),
            ComputeRoutesRequest(
                origin = Waypoint(WaypointLocation(LatLngDto(origin.latitude, origin.longitude))),
                destination = Waypoint(WaypointLocation(LatLngDto(destination.latitude, destination.longitude))),
            ),
        )
        val headers = buildMap {
            put("X-Goog-Api-Key", apiKey)
            put("X-Goog-FieldMask", FIELD_MASK)
            put("X-Android-Package", appContext.packageName)
            AppSignature.sha1(appContext)?.let { put("X-Android-Cert", it) }
        }
        val response = http.execute("POST", ENDPOINT, headers, body)
        if (!response.isSuccessful) throw IllegalStateException("Routes API HTTP ${response.code}")
        val parsed = routesJson.decodeFromString(ComputeRoutesResponse.serializer(), response.body.orEmpty().ifBlank { "{}" })
        val first = parsed.routes.firstOrNull() ?: return null
        val points = first.polyline?.encodedPolyline?.let(EncodedPolyline::decode).orEmpty()
        val meters = first.distanceMeters ?: return null
        val seconds = Walking.parseDurationSeconds(first.duration) ?: return null
        if (points.size < 2) return null
        return WalkRoute(meters.toDouble(), seconds, points, isEstimate = false)
    }

    companion object {
        const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
        const val FIELD_MASK = "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline"
        const val DAY_FIELD_MASK = "routes.legs.duration,routes.legs.distanceMeters,routes.legs.polyline.encodedPolyline"
        private const val MAX_CACHED = 64
        private const val MAX_DAYS_CACHED = 8

        private val routesJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class LatLngDto(val latitude: Double, val longitude: Double)

@Serializable
private data class WaypointLocation(val latLng: LatLngDto)

@Serializable
private data class Waypoint(val location: WaypointLocation)

@Serializable
private data class ComputeRoutesRequest(
    val origin: Waypoint,
    val destination: Waypoint,
    val travelMode: String = "WALK",
    val languageCode: String = "en-US",
)

@Serializable
private data class ComputeDayRouteRequest(
    val origin: Waypoint,
    val destination: Waypoint,
    val intermediates: List<Waypoint> = emptyList(),
    val travelMode: String = "WALK",
    val languageCode: String = "en-US",
)

@Serializable
private data class ComputeDayRouteResponse(val routes: List<DayRouteDto> = emptyList())

@Serializable
private data class DayRouteDto(val legs: List<LegDto> = emptyList())

@Serializable
private data class LegDto(
    val distanceMeters: Int? = null,
    val duration: String? = null,
    val polyline: PolylineDto? = null,
) {
    /** A leg as a service route; null when incomplete (that leg keeps its estimate). */
    fun toWalkRoute(): WalkRoute? {
        val meters = distanceMeters ?: return null
        val seconds = Walking.parseDurationSeconds(duration) ?: return null
        val points = polyline?.encodedPolyline?.let(EncodedPolyline::decode).orEmpty()
        if (points.size < 2) return null
        return WalkRoute(meters.toDouble(), seconds, points, isEstimate = false)
    }
}

@Serializable
private data class ComputeRoutesResponse(val routes: List<RouteDto> = emptyList())

@Serializable
private data class RouteDto(
    val distanceMeters: Int? = null,
    /** Protobuf duration, e.g. "165s". */
    val duration: String? = null,
    val polyline: PolylineDto? = null,
)

@Serializable
private data class PolylineDto(@SerialName("encodedPolyline") val encodedPolyline: String? = null)

/** The SHA-1 of the app's current signing certificate, as Google API key restrictions expect it (upper-case hex, no colons). */
object AppSignature {
    @Volatile
    private var cached: String? = null

    fun sha1(context: Context): String? {
        cached?.let { return it }
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray())
                .joinToString("") { "%02X".format(it) }
                .also { cached = it }
        } catch (e: Exception) {
            AppLog.caught(LogCategory.NET, "app_signature", e, LogLevel.WARNING)
            null
        }
    }
}
