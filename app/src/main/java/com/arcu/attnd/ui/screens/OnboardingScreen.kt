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
import com.arcu.attnd.data.log.PermissionLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.OnboardingLayout
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.rememberLayoutSections
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.push.PushNotifications
import com.arcu.attnd.ui.PlanUiState

private enum class TourCard { INTRO, WELCOME, LOCATION, NOTIFICATIONS, LOCK_SCREEN, PLAN }

/**
 * The cards in order (iOS `OnboardingView.cards`): the organizer's (API-027 `onboarding`: which cards, their order,
 * the welcome card; [OnboardingLayout]), else the built-in ones. The plan card needs the planner either way; a
 * layout with no card left shows the intro alone.
 */
private fun tourCards(layout: OnboardingLayout?, planAvailable: Boolean): List<TourCard> {
    if (layout == null) {
        return listOf(TourCard.INTRO, TourCard.LOCATION, TourCard.NOTIFICATIONS, TourCard.PLAN)
            .filter { it != TourCard.PLAN || planAvailable }
    }
    val mapped = layout.cards.mapNotNull { card ->
        when (card) {
            OnboardingLayout.Card.INTRO -> TourCard.INTRO
            OnboardingLayout.Card.WELCOME -> TourCard.WELCOME
            OnboardingLayout.Card.LOCATION -> TourCard.LOCATION
            OnboardingLayout.Card.NOTIFICATIONS -> TourCard.NOTIFICATIONS
            OnboardingLayout.Card.LOCK_SCREEN -> TourCard.LOCK_SCREEN
            OnboardingLayout.Card.PLAN -> TourCard.PLAN.takeIf { planAvailable }
        }
    }
    return mapped.ifEmpty { listOf(TourCard.INTRO) }
}

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
    /** "Follow on Lock Screen" for the event (the `lock_screen` card). */
    followsLive: Boolean = false,
    onFollowLive: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val layoutSections = rememberLayoutSections(LayoutScreen.ONBOARDING, bundle)
    val layout = remember(layoutSections) { OnboardingLayout.of(layoutSections) }
    val cards = remember(layout, planner.available) { tourCards(layout, planner.available) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val card = cards[index.coerceIn(0, cards.lastIndex)]
    val isLast = index >= cards.lastIndex
    fun next() {
        if (isLast) onDone() else index += 1
    }

    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var notificationsGranted by remember { mutableStateOf(PushNotifications.hasPermission(context)) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        PermissionLog.location("onboarding", result)
        locationGranted = result.values.any { it }
        if (locationGranted) onShowUserLocation(true)
        next()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PermissionLog.notifications("onboarding", granted)
        notificationsGranted = granted
        if (granted) onNotificationsGranted()
        next()
    }
    // The lock_screen card: Follow on Lock Screen needs POST_NOTIFICATIONS first.
    val followPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PermissionLog.notifications("onboarding", granted)
        notificationsGranted = granted
        if (granted) onFollowLive(true)
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
                        // The organizer's welcome card (title / text / https image; skipped when it says nothing).
                        layout?.welcomeImage?.let { image ->
                            RemoteImage(
                                image,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)),
                            )
                        }
                        Text(
                            layout?.welcomeTitle ?: bundle.event.name.ifBlank { "Welcome" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        layout?.welcomeText?.let { Body(it) }
                    }
                    TourCard.LOCK_SCREEN -> {
                        Title(Icons.Filled.Lock, "Follow on your Lock Screen")
                        Body("See what's on now and next, with a countdown and the walk, without opening the app.")
                        if (followsLive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Text("On your Lock Screen", color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            OutlinedButton(onClick = {
                                if (PushNotifications.hasPermission(context)) onFollowLive(true)
                                else followPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }) { Text("Follow on Lock Screen") }
                        }
                    }
                    TourCard.INTRO -> {
                        Title(Icons.Filled.Map, "Welcome to ${bundle.event.name}")
                        Feature(Icons.Filled.Map, "The map shows every place, what's on there now and how far it is.")
                        Feature(Icons.Filled.CalendarMonth, "The schedule counts down to what's next. Like what you don't want to miss.")
                        Feature(Icons.Filled.Notifications, "Updates from the organizers arrive live, even offline later.")
                        Feature(Icons.Filled.Search, "The search button finds anything at the event.")
                    }
                    TourCard.LOCATION -> {
                        Title(Icons.Filled.MyLocation, "Find your way")
                        Body("With your location, Attnd shows walk times, a compass arrow to each place and what's near you. It stays on your phone.")
                    }
                    TourCard.NOTIFICATIONS -> {
                        Title(Icons.Filled.Notifications, "Don't miss a change")
                        Body("Get set-time changes from the organizers, and a reminder with the walk time before the things you liked.")
                        Feature(Icons.Filled.Favorite, "You can follow your likes on the Lock Screen from Options.")
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
