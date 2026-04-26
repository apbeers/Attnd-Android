package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.ui.theme.AttndTheme
import com.google.maps.android.compose.*
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng

@Composable
fun MainMapScreen(
    locations: List<EventLocation>,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    cameraPositionState: CameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(32.754695, -97.331605), 15f)
    }
) {
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        contentPadding = contentPadding,
        properties = MapProperties(isMyLocationEnabled = false),
        uiSettings = MapUiSettings(myLocationButtonEnabled = false)
    ) {
        locations.forEach { location ->
            Marker(
                state = rememberMarkerState(position = LatLng(location.latitude, location.longitude)),
                title = location.name,
                snippet = location.snippet
            )
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
        MainMapScreen(locations = dummyLocations)
    }
}
