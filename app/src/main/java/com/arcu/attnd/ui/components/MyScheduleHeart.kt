package com.arcu.attnd.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.MyScheduleText
import com.arcu.attnd.domain.ScheduleTarget
import com.arcu.attnd.domain.SearchKind
import java.time.Instant

/**
 * My schedule's heart (iOS `HeartButton`) for a schedule item or a place, in rows, cards, details, the map
 * card, search results and chat cards. Outline = not in My schedule: a tap adds it at once (a place saved
 * without a time) with a haptic and the Undo snackbar. Filled = in it: a tap opens the entry's menu —
 * Set a Time… / Change Time… (places: "When?" with suggested times), Remind Me (5 / 10 / 15 / 30 min /
 * 1 hour / Off), Remove from My Schedule. In screenshot mode (no stored schedule) it only toggles.
 */
@Composable
fun HeartButton(kind: SearchKind, id: Int, modifier: Modifier = Modifier, name: String? = null) {
    val actions = LocalEventActions.current
    val added = actions.hearts.has(kind, id)
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var asksTime by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = {
            when {
                !added -> {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    actions.onAdd(kind, id)
                }
                actions.schedule == null -> actions.onToggleHeart(kind, id)
                else -> menu = true
            }
        }) {
            Icon(
                if (added) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = MyScheduleText.heartDescription(name, added),
                tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val schedule = actions.schedule
        if (schedule != null && added) {
            ScheduleEntryMenu(
                expanded = menu,
                onDismiss = { menu = false },
                kind = kind,
                id = id,
                schedule = schedule,
                onAskTime = { menu = false; asksTime = true },
                onReminder = { minutes -> menu = false; actions.onSetReminder(kind, id, minutes) },
                onRemove = {
                    menu = false
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    actions.onRemove(kind, id)
                },
            )
        }
    }
    if (asksTime && kind == SearchKind.LOCATION) {
        ScheduleTimeDialog(placeId = id, onDismiss = { asksTime = false })
    }
}

/** The filled heart's menu: the time (places), Remind Me (a sub-list), Remove. */
@Composable
private fun ScheduleEntryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    kind: SearchKind,
    id: Int,
    schedule: MySchedule,
    onAskTime: () -> Unit,
    onReminder: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val entry = schedule.entry(ScheduleTarget(kind, id))
    val current = entry?.reminderMinutes ?: MySchedule.DEFAULT_REMINDER
    var reminders by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (reminders) {
            MySchedule.REMINDER_CHOICES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(MyScheduleText.reminder(minutes), fontWeight = if (minutes == current) FontWeight.SemiBold else null) },
                    onClick = { onReminder(minutes) },
                    trailingIcon = if (minutes == current) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
                )
            }
            return@DropdownMenu
        }
        if (kind == SearchKind.LOCATION) {
            DropdownMenuItem(
                text = { Text(MyScheduleText.timeAction(entry?.at != null)) },
                onClick = onAskTime,
                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
            )
        }
        DropdownMenuItem(
            text = {
                Column {
                    Text(MyScheduleText.REMIND_ME)
                    Text(MyScheduleText.reminder(current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            onClick = { reminders = true },
            leadingIcon = { Icon(if (current == 0) Icons.Filled.NotificationsOff else Icons.Filled.NotificationsActive, contentDescription = null) },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(MyScheduleText.REMOVE, color = MaterialTheme.colorScheme.error) },
            onClick = onRemove,
            leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        )
    }
}

/**
 * "When?" for a place (iOS `scheduleTimeDialog`), only when asked for: today's times from now + the walk +
 * 5 min on the quarter hour, the first free one marked Suggested ([MySchedule.suggestedTimes]), or "Just
 * Save It" (keeps / saves it without a time).
 */
@Composable
fun ScheduleTimeDialog(placeId: Int, onDismiss: () -> Unit) {
    val actions = LocalEventActions.current
    val bundle = actions.bundle
    val schedule = actions.schedule
    if (bundle == null || schedule == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val user = rememberUserLocation()
    val target = bundle.location(placeId)?.coordinate
    val walk = remember(user, target) { MyScheduleText.walkMinutes(user, target) }
    val now = remember { Instant.now() }
    val choice = remember(schedule, bundle, walk) { schedule.suggestedTimes(placeId, bundle, now, walk) }
    val hasTime = schedule.entry(ScheduleTarget.place(placeId))?.at != null
    val zone = bundle.event.zone
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MyScheduleText.TIME_DIALOG_TITLE) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(MyScheduleText.TIME_DIALOG_MESSAGE, style = MaterialTheme.typography.bodyMedium)
                choice.times.forEach { time ->
                    TextButton(onClick = { actions.onSetPlaceTime(placeId, time); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(MyScheduleText.timeOption(time, choice.suggested, zone), fontWeight = if (time == choice.suggested) FontWeight.SemiBold else null)
                    }
                }
                TextButton(onClick = { actions.onSetPlaceTime(placeId, null); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(MyScheduleText.saveWithoutTime(hasTime))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
