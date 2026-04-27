package com.arcu.attnd.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.ui.theme.AttndTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@SuppressLint("MissingPermission")
@Composable
fun MainMapScreen(
    locations: List<EventLocation>,
    eventCenter: LatLng,
    recenterTrigger: Int = 0,
    onLocationClick: (Int) -> Unit = {},
    locationEnabled: Boolean = false,
    onLocationEnabled: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    cameraPositionState: CameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(eventCenter, 15f)
    }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun animateToEventCenter() {
        scope.launch {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(eventCenter, 15f)
            )
        }
    }

    // React to external recenter triggers (e.g. from bottom nav)
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0) {
            animateToEventCenter()
        }
    }

    fun zoomToUserLocation() {
        scope.launch {
            try {
                val location = fusedClient.lastLocation.await()
                if (location != null) {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(location.latitude, location.longitude), 16f
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onLocationEnabled()
            zoomToUserLocation()
        }
    }

    val onMyLocationTap = {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            onLocationEnabled()
            zoomToUserLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            contentPadding = contentPadding,
            properties = MapProperties(isMyLocationEnabled = locationEnabled),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false
            )
        ) {
            locations.forEach { location ->
                Marker(
                    state = rememberMarkerState(
                        position = LatLng(location.latitude, location.longitude)
                    ),
                    title = location.name,
                    snippet = location.snippet,
                    onInfoWindowClick = {
                        onLocationClick(location.id)
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Re-center to Event FAB
            FloatingActionButton(
                onClick = { animateToEventCenter() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Place, contentDescription = "Event Center")
            }

            // My Location FAB
            FloatingActionButton(
                onClick = onMyLocationTap
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "My Location")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainMapScreenPreview() {
    val dummyLocations = listOf(
        EventLocation(1, "Main Stage", "Live Music", "The big stage", null, null, 32.754800, -97.331700, "123 Main St"),
        EventLocation(2, "Food Court", "Tacos & More", "Eat here", null, null, 32.754600, -97.331500, "123 Main St")
    )
    AttndTheme {
        MainMapScreen(
            locations = dummyLocations,
            eventCenter = LatLng(32.754695, -97.331605)
        )
    }
}
