package com.arcu.attnd.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arcu.attnd.data.log.PermissionLog
import com.arcu.attnd.domain.PermissionRules
import com.arcu.attnd.domain.PermissionStatus
import com.arcu.attnd.domain.StatusBadge
import com.arcu.attnd.ui.components.SectionHeader

/**
 * Options → Status (iOS): what the swiped-away pills said, or "Up to date · Updated 2:14 PM".
 * [badges] are every pill, dismissed or not; [lastUpdated] the last successful refresh's time.
 */
@Composable
fun StatusSection(badges: List<StatusBadge>, lastUpdated: String?) {
    SectionHeader("Status")
    val refresh = badges.firstOrNull { it.kind == StatusBadge.Kind.REFRESH }
    if (refresh == null) {
        ListItem(leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null) }) {
            Text(lastUpdated?.let { "Up to date · Updated $it" } ?: "Up to date")
        }
    }
    badges.forEach { badge ->
        ListItem(supportingContent = { Text(badge.description) }) { Text(badge.title) }
    }
}

/**
 * Options → Permissions (iOS `PermissionsSection`): Location and Notifications with the system's
 * status. Allowed → Attnd's own switch (location = the dot and walk times; notifications = the opt-in,
 * sent as `subscribed`); never asked → the switch asks; denied → Open settings. Re-read on every resume.
 */
@Composable
fun PermissionsSection(
    showUserLocation: Boolean,
    onShowUserLocationChange: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    asked: (String) -> Boolean,
    onAsked: (String) -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val location = remember(refresh) { status(context, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, asked = asked) }
    val notifications = remember(refresh) { status(context, Manifest.permission.POST_NOTIFICATIONS, asked = asked) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        PermissionLog.location("options", result)
        refresh++
        if (result.values.any { it }) onShowUserLocationChange(true)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PermissionLog.notifications("options", granted)
        refresh++
        if (granted) onNotificationsChange(true)
    }

    SectionHeader("Permissions")
    PermissionRow(
        title = "Location",
        status = location,
        statusText = PermissionRules.locationStatusText(location),
        icon = if (location == PermissionStatus.DENIED) Icons.Filled.LocationOff else Icons.Filled.LocationOn,
        checked = showUserLocation && location == PermissionStatus.ALLOWED,
        onCheckedChange = { on ->
            when {
                !on -> onShowUserLocationChange(false)
                location == PermissionStatus.ALLOWED -> onShowUserLocationChange(true)
                else -> {
                    onAsked(Manifest.permission.ACCESS_FINE_LOCATION)
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        },
        onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
    )
    PermissionRow(
        title = "Notifications",
        status = notifications,
        statusText = PermissionRules.notificationsStatusText(notifications),
        icon = if (notifications == PermissionStatus.DENIED) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
        checked = notificationsEnabled && notifications == PermissionStatus.ALLOWED,
        onCheckedChange = { on ->
            when {
                !on -> onNotificationsChange(false)
                notifications == PermissionStatus.ALLOWED -> onNotificationsChange(true)
                else -> {
                    onAsked(Manifest.permission.POST_NOTIFICATIONS)
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        },
        onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
    )
    Text(
        PermissionRules.FOOTER,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun PermissionRow(
    title: String,
    status: PermissionStatus,
    statusText: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Column { Text(statusText) } },
        trailingContent = {
            when (PermissionRules.row(status)) {
                PermissionRules.Row.SWITCH, PermissionRules.Row.ASK_SWITCH -> Switch(checked = checked, onCheckedChange = onCheckedChange)
                PermissionRules.Row.OPEN_SETTINGS -> TextButton(onClick = onOpenSettings) { Text("Open settings") }
            }
        },
    ) { Text(title) }
}

/** The system's answer for [permissions] (any granted counts), told apart with the remembered "asked". */
private fun status(context: Context, vararg permissions: String, asked: (String) -> Boolean): PermissionStatus {
    val granted = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val activity = context.findActivity()
    val rationale = activity != null && permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    return PermissionStatus.of(granted, askedBefore = asked(permissions.first()), shouldShowRationale = rationale)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
