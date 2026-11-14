@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arcu.attnd.BuildConfig
import com.arcu.attnd.push.PushNotifications

/** Settings for the joined event: push opt-in, map location dot, and leaving the event. */
@Composable
fun OptionsScreen(
    eventName: String,
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    showUserLocation: Boolean,
    onShowUserLocationChange: (Boolean) -> Unit,
    onLeaveEvent: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) onNotificationsChange(true)
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) onShowUserLocationChange(true)
    }

    // The switch reflects both the preference and the system permission (which can be revoked).
    val pushOn = notificationsEnabled && PushNotifications.hasPermission(context)
    val locationOn = showUserLocation && hasLocationPermission(context)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Options") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(eventName, style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Push notifications") },
                    supportingContent = {
                        Text(
                            if (permissionDenied) "Notifications are blocked. Allow them in system settings."
                            else "Announcements from the organizers"
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = pushOn,
                            onCheckedChange = { checked ->
                                when {
                                    !checked -> onNotificationsChange(false)
                                    PushNotifications.hasPermission(context) -> onNotificationsChange(true)
                                    else -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text("Show my location") },
                    supportingContent = { Text("Your position on the event map") },
                    trailingContent = {
                        Switch(
                            checked = locationOn,
                            onCheckedChange = { checked ->
                                when {
                                    !checked -> onShowUserLocationChange(false)
                                    hasLocationPermission(context) -> onShowUserLocationChange(true)
                                    else -> locationPermission.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                    )
                                }
                            },
                        )
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Attnd ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Button(
                    onClick = { confirmLeave = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Leave event")
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave $eventName?") },
            text = { Text("You'll stop receiving its notifications. You can join again with the event code.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeaveEvent()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}
