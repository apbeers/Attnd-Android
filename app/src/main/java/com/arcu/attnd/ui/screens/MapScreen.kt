package com.arcu.attnd.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arcu.attnd.R
import com.arcu.attnd.domain.DisplayConfig
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LocationGrouping
import com.arcu.attnd.ui.components.CategoryChips
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val STREET_ZOOM = 16f

/** A pin for one location with known coordinates. Also a ClusterItem for map clustering. */
private class LocationPin(val location: EventLocation, point: GeoPoint) : ClusterItem {
    override val position: LatLng = point.toLatLng()
    override val title: String? get() = location.name
    override val snippet: String? get() = location.snippet.ifBlank { null }
    override val zIndex: Float? get() = null
}

private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

/**
 * The event map. Pins only for locations with real coordinates (null and 0,0 are skipped).
 * Honours `display.map_clustering`, `map_fit_all` and `group_by_category` (filter chips).
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
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val display: DisplayConfig = bundle.display
    val eventCenter = bundle.event.coordinate

    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(bundle.locations, bundle.categories) {
        LocationGrouping.availableCategories(bundle.locations, bundle.categories)
    }
    val pins = remember(bundle.locations, selectedCategory) {
        LocationGrouping.mappable(LocationGrouping.filter(bundle.locations, "", selectedCategory))
            .map { (location, point) -> LocationPin(location, point) }
    }

    val initialTarget = eventCenter ?: pins.firstOrNull()?.location?.coordinate
    val cameraPositionState = rememberCameraPositionState {
        position = if (initialTarget != null) {
            CameraPosition.fromLatLngZoom(initialTarget.toLatLng(), STREET_ZOOM)
        } else {
            // No coordinates at all: show the continental US rather than inventing a point.
            CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3f)
        }
    }
    var mapLoaded by remember { mutableStateOf(false) }
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
            } catch (_: Exception) {
                // newLatLngBounds throws if the map has no size yet; the next request will work.
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

    val hasLocationPermission = hasLocationPermission(context)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            onShowUserLocationChange(true)
            scope.launch { zoomToUser(context, cameraPositionState) }
        }
    }

    val isDark = isSystemInDarkTheme()
    val mapStyle = remember(isDark) {
        if (isDark) MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark) else null
    }

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
                onMapLoaded = { mapLoaded = true },
            ) {
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
                        onClusterItemInfoWindowClick = { pin -> onLocationClick(pin.location.id) },
                    )
                } else {
                    pins.forEach { pin ->
                        key(pin.location.id) {
                            Marker(
                                state = rememberUpdatedMarkerState(position = pin.position),
                                title = pin.title,
                                snippet = pin.snippet,
                                onInfoWindowClick = { onLocationClick(pin.location.id) },
                            )
                        }
                    }
                }
            }
        }

        if (display.groupByCategory) {
            CategoryChips(
                categories = categories,
                selected = selectedCategory,
                onSelect = { selectedCategory = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(top = contentPadding.calculateTopPadding()),
            )
        }

        // Material 3 Expressive vertical floating toolbar (replaces the stacked FABs).
        VerticalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp, end = 16.dp),
        ) {
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
