@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import android.Manifest
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.SupportInfo
import com.arcu.attnd.push.PushNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Settings for the joined event: push opt-in, map location dot, leaving the event, and
 * About & support (API-004) with a test-build warning when not on the production server.
 */
@Composable
fun OptionsScreen(
    eventName: String,
    supportInfo: SupportInfo,
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
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (supportInfo.isTestBuild) TestBuildWarning(supportInfo.serverHost)
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
                Spacer(Modifier.size(8.dp))
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
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AboutSection(supportInfo)
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

/** Shown at the top of Options when this build talks to a server other than attnd.app. */
@Composable
private fun TestBuildWarning(serverHost: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Internal test build", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "This build uses the dev server ($serverHost). Events and data here are separate from the live app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** API-004: build, server, device and event details, the support code and "Copy support info". */
@Composable
private fun AboutSection(info: SupportInfo) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_CONFIRMATION_MS)
            copied = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("About & support", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Support code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                info.supportCode,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "Give this code to support if you report a problem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                InfoRow("Version", "${info.appVersion} (${info.versionCode})")
                InfoRow("Build type", info.buildType)
                InfoRow("Server", info.serverHost)
                InfoRow("API version", "v${info.apiVersion}")
                InfoRow("Android", info.osVersion)
                InfoRow("Device", info.device)
                InfoRow("Event", info.eventName ?: "None")
                info.eventCode?.let { InfoRow("Event code", it) }
                info.eventId?.let { InfoRow("Event ID", it.toString()) }
                info.contentVersion?.let { InfoRow("Content version", it.toString()) }
                InfoRow("Last refresh", info.lastRefreshText(zone))
            }
        }
        Spacer(Modifier.size(4.dp))
        OutlinedButton(
            onClick = {
                val text = info.plainText(zone)
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Attnd support info", text)))
                    copied = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (copied) "Copied" else "Copy support info")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private const val COPIED_CONFIRMATION_MS = 2_000L
