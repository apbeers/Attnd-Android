package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng

@Composable
fun MainMapScreen(eventId: Int) {
    val fortWorth = LatLng(32.754695, -97.331605)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fortWorth, 15f)
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = true),
        uiSettings = MapUiSettings(myLocationButtonEnabled = true)
    )
}
