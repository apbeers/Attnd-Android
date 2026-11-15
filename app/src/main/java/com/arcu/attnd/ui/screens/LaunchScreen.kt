@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.arcu.attnd.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.NearbyEvent
import com.arcu.attnd.ui.JoinUiState
import com.arcu.attnd.ui.NearbyUiState
import com.arcu.attnd.ui.components.QrScannerDialog
import com.arcu.attnd.domain.DistanceFormat
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.rememberDistanceUnits
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberUserLocation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Home screen before joining: event code entry, QR scanner and nearby events. */
@Composable
fun LaunchScreen(
    join: JoinUiState,
    nearby: NearbyUiState,
    message: String?,
    onJoinCode: (String) -> Unit,
    onJoinEvent: (Event) -> Unit,
    onNearbyLoading: () -> Unit,
    onNearbyLocation: (lat: Double, lng: Double) -> Unit,
    onNearbyError: (String) -> Unit,
    onClearJoinError: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }

    fun findNearby() {
        onNearbyLoading()
        scope.launch {
            val point = currentLocation(context)
            if (point != null) onNearbyLocation(point.first, point.second)
            else onNearbyError("Couldn't determine your location. Try again in a moment.")
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) findNearby() else onNearbyError("Location permission is needed to find nearby events.")
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true else cameraError = "Camera permission is needed to scan QR codes."
    }

    if (showScanner) {
        QrScannerDialog(
            onDismiss = { showScanner = false },
            onScanned = { raw ->
                showScanner = false
                onJoinCode(raw)
            },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Comfortable reading width when unfolded; full width on the folded/cover screen.
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp)) {
                Text("Welcome to Attnd", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        if (join.error != null) onClearJoinError()
                    },
                    label = { Text("Event code") },
                    singleLine = true,
                    enabled = !join.isJoining,
                    isError = join.error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank()) onJoinCode(code) }),
                    trailingIcon = {
                        IconButton(onClick = {
                            cameraError = null
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showScanner = true
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        }) { Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR code") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onJoinCode(code) },
                    enabled = !join.isJoining && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (join.isJoining) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Join event")
                    }
                }
                (join.error ?: cameraError ?: message)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }

                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("  OR  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    HorizontalDivider(Modifier.weight(1f))
                }

                OutlinedButton(
                    onClick = {
                        if (hasLocationPermission(context)) findNearby()
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    enabled = !nearby.isLoading && !join.isJoining,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Find nearby events")
                }
                nearby.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (nearby.isLoading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                if (nearby.events.isNotEmpty()) {
                    Text("Nearby events", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(nearby.events, key = { it.event.id }) { item ->
                            NearbyEventCard(item, enabled = !join.isJoining) { onJoinEvent(item.event) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyEventCard(item: NearbyEvent, enabled: Boolean, onClick: () -> Unit) {
    val event = item.event
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            val now = rememberNow()
            val units = rememberDistanceUnits()
            val user = rememberUserLocation()
            val relative = remember(item, now) { RelativeTime.forNearby(item, now) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Live distance + arrow from the user's position; the server's distance otherwise.
                val coordinate = event.coordinate
                if (user != null && coordinate != null) {
                    DistanceArrow(coordinate, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                } else {
                    item.distanceMiles?.let {
                        Text(DistanceFormat.formatMiles(it, units), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                relative?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
            }
            event.startTime?.let {
                Text(EventTime.formatDateTime(it, event.zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (event.address.isNotEmpty()) {
                Text(event.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (event.isDemo) {
                Text("Demo event", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/** Last known location, or a fresh balanced-accuracy fix. Null without permission or on failure. */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    return try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client.lastLocation.await()
            ?: client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
        location?.let { it.latitude to it.longitude }
    } catch (_: Exception) {
        null
    }
}
