package com.arcu.attnd.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arcu.attnd.data.NetworkModule
import com.arcu.attnd.ui.theme.AttndTheme
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
@Composable
fun OptionsScreen(
    eventId: Int,
    deviceId: String,
    locationEnabled: Boolean,
    onLocationChanged: (Boolean) -> Unit,
    onLeaveEvent: () -> Unit,
) {
    var pushEnabled by remember { mutableStateOf(value = true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { perms ->
        val granted = (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        onLocationChanged(granted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Options", style = MaterialTheme.typography.headlineMedium)

        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Push Notifications") },
            supportingContent = { Text("Receive updates and alerts") },
            trailingContent = {
                Switch(
                    checked = pushEnabled,
                    onCheckedChange = { checked ->
                        pushEnabled = checked
                        scope.launch {
                            try {
                                if (checked) NetworkModule.api.subscribePush(deviceId)
                                else NetworkModule.api.unsubscribePush(deviceId)
                            } catch (_: Exception) {}
                        }
                    }
                )
            }
        )

        ListItem(
            headlineContent = { Text("Location Access") },
            supportingContent = { Text("Used to show your position on the map") },
            trailingContent = {
                Switch(
                    checked = locationEnabled,
                    onCheckedChange = { checked ->
                        if (!checked) {
                            onLocationChanged(false)
                        } else {
                            val fineGranted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            val coarseGranted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (fineGranted || coarseGranted) {
                                onLocationChanged(true)
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                    }
                )
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                scope.launch {
                    try { NetworkModule.api.leaveEvent(eventId, deviceId) } catch (_: Exception) {}
                    onLeaveEvent()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Leave Event")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OptionsScreenPreview() {
    AttndTheme {
        OptionsScreen(
            eventId = 1,
            deviceId = "test",
            locationEnabled = true,
            onLocationChanged = {},
            onLeaveEvent = {}
        )
    }
}
