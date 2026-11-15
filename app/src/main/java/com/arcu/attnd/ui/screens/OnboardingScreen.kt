@file:OptIn(ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.push.PushNotifications
import com.arcu.attnd.ui.PlanUiState

private enum class TourCard { WELCOME, LOCATION, NOTIFICATIONS, PLAN }

/**
 * The tour (shared spec §7): shown the first time an event is joined and from Options → Tour, never
 * in screenshot mode; Skip closes it at any card. Cards: what the app does; location (walk times,
 * compass, nearby) with the permission prompt; notifications (set-time changes, reminders for hearts)
 * with the permission prompt; and, only while Gemini Nano is available, "Plan your day" with example
 * requests made from this event ([PlanExample]; a plan example makes the plan, a question opens Ask).
 */
@Composable
fun OnboardingScreen(
    bundle: EventBundle,
    planner: PlanUiState,
    onExample: (PlanExample) -> Unit,
    onShowUserLocation: (Boolean) -> Unit,
    onNotificationsGranted: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val cards = remember(planner.available) {
        TourCard.entries.filter { it != TourCard.PLAN || planner.available }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val card = cards[index.coerceIn(0, cards.lastIndex)]
    val isLast = index >= cards.lastIndex
    fun next() {
        if (isLast) onDone() else index += 1
    }

    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var notificationsGranted by remember { mutableStateOf(PushNotifications.hasPermission(context)) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationGranted = result.values.any { it }
        if (locationGranted) onShowUserLocation(true)
        next()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        if (granted) onNotificationsGranted()
        next()
    }
    BackHandler(onBack = onDone)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} of ${cards.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDone) { Text("Skip") }
            }
            Column(
                Modifier
                    .weight(1f)
                    .widthIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (card) {
                    TourCard.WELCOME -> {
                        Title(Icons.Filled.Map, "Welcome to ${bundle.event.name}")
                        Feature(Icons.Filled.Map, "The map shows every place, what's on there now and how far it is.")
                        Feature(Icons.Filled.CalendarMonth, "The schedule counts down to what's next. Heart what you don't want to miss.")
                        Feature(Icons.Filled.Notifications, "Updates from the organizers arrive live, even offline later.")
                        Feature(Icons.Filled.Search, "The search button finds anything at the event.")
                    }
                    TourCard.LOCATION -> {
                        Title(Icons.Filled.MyLocation, "Find your way")
                        Body("With your location, Attnd shows walk times, a compass arrow to each place and what's near you. It stays on your phone.")
                    }
                    TourCard.NOTIFICATIONS -> {
                        Title(Icons.Filled.Notifications, "Don't miss a change")
                        Body("Get set-time changes from the organizers, and a reminder with the walk time before the things you hearted.")
                        Feature(Icons.Filled.Favorite, "You can follow your hearts on the Lock Screen from Options.")
                    }
                    TourCard.PLAN -> {
                        Title(Icons.Filled.AutoAwesome, "Plan your day")
                        Body("Tell Attnd what you're into and your phone builds a plan from the schedule, with walking times. Nothing leaves your phone.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            planner.examples.forEach { example ->
                                SuggestionChip(onClick = { onExample(example) }, label = { Text(example.text) })
                            }
                        }
                    }
                }
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    card == TourCard.LOCATION && !locationGranted -> {
                        Button(onClick = {
                            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Allow location") }
                        TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                    }
                    card == TourCard.NOTIFICATIONS && !notificationsGranted -> {
                        Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow notifications")
                        }
                        TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                    }
                    else -> {
                        if (card == TourCard.LOCATION || card == TourCard.NOTIFICATIONS) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Text("Allowed", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Button(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text(if (isLast) "Done" else "Next") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
