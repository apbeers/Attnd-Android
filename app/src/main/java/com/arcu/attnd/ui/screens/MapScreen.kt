package com.arcu.attnd.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.R
import com.arcu.attnd.data.log.PermissionLog
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.LocationGrouping
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.arcu.attnd.domain.NowPlaying
import com.arcu.attnd.domain.PerfMetric
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.WalkRoute
import com.arcu.attnd.domain.Walking
import com.arcu.attnd.ui.components.CategoryChips
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LiveDot
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.rememberDistanceUnits
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberUserLocation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant

private const val STREET_ZOOM = 16f

/**
 * A pin for one location with known coordinates. Also a ClusterItem for map clustering. The glance
 * fields (live dot, "Next 7:30", selected) are part of the pin, so a change re-renders it.
 */
private data class LocationPin(
    val location: EventLocation,
    val point: GeoPoint,
    val live: Boolean,
    val subtitle: String?,
    val selected: Boolean,
) : ClusterItem {
    override val position: LatLng get() = point.toLatLng()
    override val title: String? get() = location.name
    override val snippet: String? get() = subtitle
    override val zIndex: Float? get() = if (selected) 1f else null
}

private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

/**
 * The event map. Pins only for locations with real coordinates (null and 0,0 are skipped).
 * Honours `display.map_clustering`, `map_fit_all` and `group_by_category` (filter chips), plus a
 * "Liked" chip when anything is liked. Pins show a live dot when something is on there now and
 * "Next 7:30" when something starts within 3 h; a "Now playing" strip lists what is on. Tapping a pin
 * selects it: a card with its now/next, distance + arrow, heart, Details and Directions, and (within
 * 8 km, with location permission) a walking route drawn on the map ([WalkRouter]: the Routes API,
 * or a dashed straight line with "about 8 min walk").
 */
@OptIn(MapsComposeExperimentalApi::class)
@SuppressLint("MissingPermission") // isMyLocationEnabled is only set after the permission check
@Composable
fun EventMap(
    bundle: EventBundle,
    showUserLocation: Boolean,
    onShowUserLocationChange: (Boolean) -> Unit,
    recenterRequest: Int,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Set: the app-wide search FAB is the floating toolbar's FAB (the toolbar + FAB pattern). */
    onSearch: (() -> Unit)? = null,
    /** One-shot: select this location ("Show on map"). */
    focusLocationId: Int? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val display: DisplayConfig = bundle.display
    val eventCenter = bundle.event.coordinate
    val hearts = LocalEventActions.current.hearts
    val now = rememberNow()
    // Store screenshots: plain pins, no Now playing strip (and no location tracking, see LocalScreenshotMode).
    val glance = !LocalScreenshotMode.current

    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var heartedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val categories = remember(bundle.locations, bundle.categories) {
        LocationGrouping.availableCategories(bundle.locations, bundle.categories)
    }
    val pins = remember(bundle, selectedCategory, heartedOnly, hearts, selectedId, now, glance) {
        LocationGrouping.mappable(LocationGrouping.filter(bundle.locations, "", selectedCategory))
            .filter { (location, _) -> !heartedOnly || location.id in hearts.locations }
            .map { (location, point) ->
                LocationPin(
                    location = location,
                    point = point,
                    live = glance && Glance.isLive(bundle, location.id, now),
                    subtitle = if (glance) Glance.pinSubtitle(bundle, location.id, now) else null,
                    selected = location.id == selectedId,
                )
            }
    }
    val nowPlaying = remember(bundle, now, glance) { if (glance) Glance.nowPlaying(bundle, now) else emptyList() }
    val selected = selectedId?.let { bundle.location(it) }?.takeIf { it.coordinate != null }

    val initialTarget = eventCenter ?: pins.firstOrNull()?.point
    val cameraPositionState = rememberCameraPositionState {
        position = if (initialTarget != null) {
            CameraPosition.fromLatLngZoom(initialTarget.toLatLng(), STREET_ZOOM)
        } else {
            // No coordinates at all: show the continental US rather than inventing a point.
            CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3f)
        }
    }
    var mapLoaded by remember { mutableStateOf(false) }
    // map.first_render (logging spec §1/§6): from the map's first composition to onMapLoaded.
    val renderStart = remember { MapRenderTiming.start() }
    var initialFitDone by rememberSaveable { mutableStateOf(false) }
    val paddingPx = with(density) { 48.dp.roundToPx() }

    fun homeUpdate(): CameraUpdate? {
        val points = pins.map { it.position }
        return when {
            display.mapFitAll && points.size >= 2 -> {
                val builder = LatLngBounds.builder()
                points.forEach { builder.include(it) }
                CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx)
            }
            display.mapFitAll && points.size == 1 -> CameraUpdateFactory.newLatLngZoom(points.first(), STREET_ZOOM)
            eventCenter != null -> CameraUpdateFactory.newLatLngZoom(eventCenter.toLatLng(), STREET_ZOOM)
            else -> null
        }
    }

    fun goHome(animate: Boolean) {
        val update = homeUpdate() ?: return
        scope.launch {
            try {
                if (animate) cameraPositionState.animate(update) else cameraPositionState.move(update)
            } catch (e: Exception) {
                // newLatLngBounds throws if the map has no size yet; the next request will work.
                AppLog.caught(LogCategory.MAP, "map.camera", e, LogLevel.DEBUG)
            }
        }
    }

    /** Selects a location (pin, "Now playing" chip, "Show on map") and, if asked, moves the camera to it. */
    fun select(location: EventLocation, center: Boolean) {
        selectedId = location.id
        val point = location.coordinate ?: return
        if (!center) return
        scope.launch {
            try {
                cameraPositionState.animate(CameraUpdateFactory.newLatLng(point.toLatLng()))
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(mapLoaded) {
        if (mapLoaded && !initialFitDone) {
            initialFitDone = true
            if (display.mapFitAll) goHome(animate = false)
        }
    }
    LaunchedEffect(recenterRequest) {
        if (recenterRequest > 0 && mapLoaded) goHome(animate = true)
    }
    LaunchedEffect(focusLocationId, mapLoaded) {
        val id = focusLocationId ?: return@LaunchedEffect
        if (!mapLoaded) return@LaunchedEffect
        bundle.location(id)?.takeIf { it.coordinate != null }?.let { location ->
            // A hidden pin (filtered out) would leave the card without its pin: clear the filters.
            if (heartedOnly && id !in hearts.locations) heartedOnly = false
            if (selectedCategory != null && location.category?.trim() != selectedCategory) selectedCategory = null
            select(location, center = true)
        }
        onFocusConsumed()
    }

    // Walking route to the selected location, from where the user is (re-routed after 50 m).
    val user = if (selected != null) rememberUserLocation() else null
    val units = rememberDistanceUnits()
    val router = remember { (context.applicationContext as? AttndApplication)?.graph?.walkRouter }
    var routeOrigin by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(user) {
        if (user != null && Walking.shouldReroute(routeOrigin, user)) routeOrigin = user
    }
    val route by produceState<Pair<Int, WalkRoute>?>(null, selected?.id, routeOrigin, router) {
        val target = selected
        val origin = routeOrigin
        val destination = target?.coordinate
        if (target == null || origin == null || destination == null || router == null || !Walking.isNearby(origin, destination) ||
            Geo.distanceMeters(origin, destination) < Geo.HERE_METERS
        ) {
            value = null
            return@produceState
        }
        if (value?.first != target.id) value = Walking.estimate(origin, destination).let { target.id to it }
        value = target.id to router.route(target.id, origin, destination)
    }
    val shownRoute = route?.takeIf { it.first == selected?.id }?.second

    val hasLocationPermission = hasLocationPermission(context)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        PermissionLog.location("map", result)
        if (result.values.any { it }) {
            onShowUserLocationChange(true)
            scope.launch { zoomToUser(context, cameraPositionState) }
        }
    }

    val isDark = isSystemInDarkTheme()
    val mapStyle = remember(isDark) {
        if (isDark) MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark) else null
    }
    val pinColor = MaterialTheme.colorScheme.primary
    val selectedPinColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.surface
    val onLabelColor = MaterialTheme.colorScheme.onSurface
    val routeColor = MaterialTheme.colorScheme.primary

    Box(modifier.fillMaxSize()) {
        if (LocalInspectionMode.current) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("Map preview") }
        } else {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                contentPadding = contentPadding,
                properties = MapProperties(
                    isMyLocationEnabled = showUserLocation && hasLocationPermission,
                    mapStyleOptions = mapStyle,
                ),
                uiSettings = MapUiSettings(
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                    mapToolbarEnabled = false,
                ),
                onMapLoaded = {
                    if (!mapLoaded) MapRenderTiming.loaded(context, renderStart, pins.size, display.mapClustering)
                    mapLoaded = true
                },
                onMapClick = { selectedId = null },
            ) {
                shownRoute?.let { walk ->
                    Polyline(
                        points = walk.points.map { it.toLatLng() },
                        color = routeColor,
                        width = with(density) { 5.dp.toPx() },
                        pattern = if (walk.isEstimate) listOf(Dash(with(density) { 10.dp.toPx() }), Gap(with(density) { 6.dp.toPx() })) else null,
                        jointType = JointType.ROUND,
                        startCap = RoundCap(),
                        endCap = RoundCap(),
                    )
                }
                if (display.mapClustering) {
                    Clustering(
                        items = pins,
                        onClusterClick = { cluster ->
                            val builder = LatLngBounds.builder()
                            cluster.items.forEach { builder.include(it.position) }
                            scope.launch {
                                try {
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx))
                                } catch (_: Exception) {
                                }
                            }
                            true
                        },
                        onClusterItemClick = { pin ->
                            select(pin.location, center = false)
                            true
                        },
                        clusterItemContent = { pin ->
                            PinMarker(pin, if (pin.selected) selectedPinColor else pinColor, labelColor, onLabelColor)
                        },
                    )
                } else {
                    pins.forEach { pin ->
                        key(pin.location.id) {
                            MarkerComposable(
                                pin.live, pin.subtitle.orEmpty(), pin.selected,
                                state = rememberUpdatedMarkerState(position = pin.position),
                                title = pin.title,
                                contentDescription = listOfNotNull(pin.location.name, if (pin.live) "happening now" else null, pin.subtitle)
                                    .joinToString(", "),
                                zIndex = if (pin.selected) 1f else 0f,
                                onClick = {
                                    select(pin.location, center = false)
                                    true
                                },
                            ) {
                                PinMarker(pin, if (pin.selected) selectedPinColor else pinColor, labelColor, onLabelColor)
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            val showHeartedChip = heartedOnly || hearts.locations.isNotEmpty()
            if (display.groupByCategory || showHeartedChip) {
                CategoryChips(
                    categories = if (display.groupByCategory) categories else emptyList(),
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it },
                    hearted = heartedOnly,
                    onHeartedChange = if (showHeartedChip) ({ value: Boolean -> heartedOnly = value }) else null,
                )
            }
            NowPlayingStrip(nowPlaying, onSelect = { select(it.location, center = true) })
        }

        // Material 3 Expressive vertical floating toolbar (replaces the stacked FABs). With the search
        // FAB it becomes a floating toolbar with a FAB below it, so the map controls and search share
        // the bottom-end corner without overlapping.
        val toolbarModifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp, end = 16.dp)
        val toolbarContent: @Composable ColumnScope.() -> Unit = {
            IconButton(onClick = { goHome(animate = true) }) {
                Icon(Icons.Filled.Place, contentDescription = "Show the whole event")
            }
            IconButton(
                onClick = {
                    if (hasLocationPermission) {
                        onShowUserLocationChange(true)
                        scope.launch { zoomToUser(context, cameraPositionState) }
                    } else {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "My location")
            }
        }
        if (onSearch != null) {
            VerticalFloatingToolbar(
                expanded = true,
                floatingActionButton = {
                    // Same colours as the Scaffold's search FAB on the other tabs.
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = onSearch,
                        containerColor = FloatingActionButtonDefaults.containerColor,
                        contentColor = contentColorFor(FloatingActionButtonDefaults.containerColor),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                modifier = toolbarModifier,
                content = toolbarContent,
            )
        } else {
            VerticalFloatingToolbar(expanded = true, modifier = toolbarModifier, content = toolbarContent)
        }

        if (selected != null) {
            // Beside the floating toolbar (it stays usable), above the navigation.
            SelectedLocationCard(
                bundle = bundle,
                location = selected,
                now = now,
                route = shownRoute,
                units = units,
                onDetails = { onLocationClick(selected.id) },
                onClose = { selectedId = null },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 96.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp)
                    .widthIn(max = 420.dp),
            )
        }
    }
}

/** A map pin: the place icon (a live dot when something is on), with "Next 7:30" above it. */
@Composable
private fun PinMarker(pin: LocationPin, color: Color, labelColor: Color, onLabelColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        pin.subtitle?.let {
            Surface(shape = RoundedCornerShape(50), color = labelColor, contentColor = onLabelColor, shadowElevation = 2.dp) {
                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Box {
            Icon(Icons.Filled.Place, contentDescription = null, tint = color, modifier = Modifier.size(if (pin.selected) 44.dp else 36.dp))
            if (pin.live) LiveDot(Modifier.align(Alignment.TopEnd), size = 12.dp)
        }
    }
}

/** "Now playing": what is on right now, by location; tapping a chip selects that location. */
@Composable
private fun NowPlayingStrip(items: List<NowPlaying>, onSelect: (NowPlaying) -> Unit) {
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { playing ->
            AssistChip(
                onClick = { onSelect(playing) },
                label = { Text(playing.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { LiveDot() },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = AssistChipDefaults.assistChipElevation(elevation = 2.dp),
            )
        }
    }
}

/** The selected pin's card: now/next, distance + arrow, walk time / route, heart, Details, Directions. */
@Composable
private fun SelectedLocationCard(
    bundle: EventBundle,
    location: EventLocation,
    now: Instant,
    route: WalkRoute?,
    units: DistanceUnits,
    onDetails: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ElevatedCard(modifier) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    location.category?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(location.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HeartButton(SearchKind.LOCATION, location.id, name = location.name)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Glance.locationLine(bundle, location.id, now)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Row(
                Modifier.padding(top = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DistanceArrow(location.coordinate)
                if (route != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(Walking.cardText(route, units), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDetails) { Text("Details") }
                location.coordinate?.let { point ->
                    // Starts navigation in Google Maps; the app itself never navigates.
                    OutlinedButton(onClick = {
                        val uri = "https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}&travelmode=walking".toUri()
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            AppLog.caught(LogCategory.UI, "map.directions", e, LogLevel.WARNING)
                        }
                    }) { Text("Directions") }
                }
            }
        }
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private suspend fun zoomToUser(context: Context, camera: CameraPositionState) {
    if (!hasLocationPermission(context)) return
    try {
        val location = LocationServices.getFusedLocationProviderClient(context).lastLocation.await() ?: return
        camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), STREET_ZOOM))
    } catch (_: Exception) {
    }
}

/** `map.first_render`: an async Trace section and a log line from first composition to the first loaded map. */
private object MapRenderTiming {
    class Start(val atMs: Long, val cookie: Int)

    fun start() = Start(SystemClock.uptimeMillis(), AppLog.beginAsync(SECTION))

    private const val SECTION = "map_first_render"

    fun loaded(context: Context, start: Start, pins: Int, clustering: Boolean) {
        AppLog.endAsync(SECTION, start.cookie)
        val ms = SystemClock.uptimeMillis() - start.atMs
        AppLog.info(LogCategory.MAP, LogEvent.MAP_FIRST_RENDER, "Map loaded in $ms ms", mapOf("ms" to ms, "pins" to pins, "clustered" to clustering))
        (context.applicationContext as? AttndApplication)?.graph?.perf?.record(PerfMetric.MAP_FIRST_RENDER, PerfMetric.mapBucket(ms))
    }
}
