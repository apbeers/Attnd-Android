package com.arcu.attnd.ui.assistant

import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.domain.DayRoute
import com.arcu.attnd.domain.DayRoutes
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.QrCode
import com.arcu.attnd.domain.WalkRoute
import com.arcu.attnd.domain.Walking
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.rememberUserLocation
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

private fun GeoPoint.latLng() = LatLng(latitude, longitude)

/** One numbered pin of a mini map. */
data class MiniPin(val number: Int?, val locationId: Int, val point: GeoPoint, val name: String)

/**
 * The real walking legs of [route] (assistant spec §5): the straight-line estimates at once, upgraded
 * by one Routes API call for the day ([com.arcu.attnd.data.WalkRouter.dayLegs], cached per [key]);
 * a leg the service can't route, or routes implausibly, keeps its dashed estimate. No network in
 * screenshot mode or previews.
 */
@Composable
fun rememberServiceRoute(route: DayRoute?, key: String): DayRoute? {
    val context = LocalContext.current
    val quiet = LocalInspectionMode.current || LocalScreenshotMode.current
    val upgraded by produceState(route, route, key) {
        value = route
        if (route == null || quiet) return@produceState
        val stops = DayRoutes.waypoints(route) ?: return@produceState
        val router = (context.applicationContext as? AttndApplication)?.graph?.walkRouter ?: return@produceState
        value = DayRoutes.withServiceLegs(route, router.dayLegs(key, stops))
    }
    return upgraded
}

/**
 * The `map` / `plan` blocks' mini map: a lite-mode Google map (static, cheap in a scrolling list)
 * with numbered stops, the walking legs in the accent colour (dashed while estimated), the user's
 * position as a dot (when permitted; never in screenshot mode). Tapping it opens the full map.
 */
@Composable
fun MiniMap(
    pins: List<MiniPin>,
    legs: List<WalkRoute>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    if (pins.isEmpty()) return
    val description = "Map of " + pins.joinToString(", ") { pin -> pin.number?.let { "$it ${pin.name}" } ?: pin.name }
    val shape = RoundedCornerShape(16.dp)
    if (LocalInspectionMode.current) {
        Box(modifier.fillMaxWidth().height(height).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("Map preview")
        }
        return
    }
    val user = if (LocalScreenshotMode.current) null else rememberUserLocation()
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val camera = rememberCameraPositionState()
    val points = remember(pins, legs) { pins.map { it.point } + legs.flatMap { it.points } }
    Box(modifier.fillMaxWidth().height(height).clip(shape).semantics { contentDescription = description }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
            uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false, myLocationButtonEnabled = false),
            onMapClick = { onClick() },
            onMapLoaded = {
                val padding = with(density) { 36.dp.roundToPx() }
                try {
                    if (points.size == 1) {
                        camera.move(CameraUpdateFactory.newLatLngZoom(points.single().latLng(), 16f))
                    } else {
                        val bounds = LatLngBounds.builder().apply { points.forEach { include(it.latLng()) } }.build()
                        camera.move(CameraUpdateFactory.newLatLngBounds(bounds, padding))
                    }
                } catch (_: IllegalStateException) {
                    // No size yet: the default camera stays.
                }
            },
        ) {
            legs.forEach { leg ->
                Polyline(
                    points = leg.points.map { it.latLng() },
                    color = accent,
                    width = with(density) { 4.dp.toPx() },
                    pattern = if (leg.isEstimate) listOf(Dash(with(density) { 8.dp.toPx() }), Gap(with(density) { 5.dp.toPx() })) else null,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                )
            }
            pins.forEach { pin ->
                key(pin.locationId, pin.number ?: 0) {
                    MarkerComposable(
                        pin.number ?: 0, accent,
                        state = rememberUpdatedMarkerState(position = pin.point.latLng()),
                        title = pin.name,
                        onClick = {
                            onClick()
                            true
                        },
                    ) { NumberedPin(pin.number, accent) }
                }
            }
            user?.let { point ->
                MarkerComposable(state = rememberUpdatedMarkerState(position = point.latLng()), title = "You") {
                    Box(Modifier.size(14.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).background(Color(0xFF1A73E8), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberedPin(number: Int?, color: Color) {
    if (number == null) {
        Icon(Icons.Filled.Place, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        return
    }
    Surface(shape = CircleShape, color = color, contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 2.dp) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Pins and legs of a day route for [MiniMap]. */
fun routePins(route: DayRoute, bundle: EventBundle): List<MiniPin> =
    route.stops.map { stop -> MiniPin(stop.number, stop.locationId, stop.point, bundle.location(stop.locationId)?.name.orEmpty()) }

/** A route through [locationIds] in order (the `map` block's `route`), as estimate legs. */
fun straightLegs(points: List<GeoPoint>): List<WalkRoute> = points.zipWithNext { a, b -> Walking.estimate(a, b) }

/**
 * A QR code of [text] drawn in-house ([QrCode], no library): dark modules on white with the standard
 * 4-module quiet zone, so it scans in dark mode too.
 */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val code = remember(text) { QrCode.encode(text) } ?: return
    Canvas(modifier.size(size).semantics { contentDescription = "QR code of the link" }) {
        val quiet = 4
        val cells = code.size + quiet * 2
        val cell = this.size.minDimension / cells
        drawRect(Color.White)
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code[x, y]) drawRect(Color.Black, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
