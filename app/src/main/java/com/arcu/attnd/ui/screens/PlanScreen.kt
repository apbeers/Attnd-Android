@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.arcu.attnd.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.ui.PlanUiState
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow
import java.time.Instant
import java.time.format.DateTimeFormatter

/** What the "My plan" view can do (wired to EventViewModel in AttndApp). */
class PlanActions(
    val onMakePlan: (String) -> Unit = {},
    val onReplan: () -> Unit = {},
    val onSwap: (Int, Int) -> Unit = { _, _ -> },
    val onKeepChanged: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onExample: (PlanExample) -> Unit = {},
    /** Check off (or un-check) a planned item that has started (assistant spec §6). */
    val onCheck: (Int) -> Unit = {},
    /** "Open in assistant" (the same plan, with the assistant's tools). */
    val onOpenAssistant: () -> Unit = {},
)

/**
 * Schedule → My plan (shared spec §6): the plan as a timeline with walk legs ("6 min walk") between
 * items, meal breaks, a Swap menu per item (its alternatives), "Re-plan from now", and a "Your plan
 * changed" notice when a content update moved or removed a planned item. Without a plan: the request
 * card (only offered while Gemini Nano is available on the device).
 */
@Composable
fun MyPlanPane(
    bundle: EventBundle,
    planner: PlanUiState,
    actions: PlanActions,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val plan = planner.plan?.takeIf { !it.isEmpty }
    val zone = bundle.event.zone
    val now = rememberNow()
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (plan == null) {
            item(key = "request") { PlanRequestCard(planner, actions) }
            return@LazyColumn
        }
        if (planner.changed) {
            item(key = "changed") { PlanChangedCard(actions) }
        }
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.request.isNotBlank()) {
                    Text("“${plan.request}”", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    Planner.summary(plan, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = actions.onReplan) { Text("Re-plan from now") }
                    OutlinedButton(onClick = actions.onOpenAssistant) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Open in assistant", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (planner.available) TextButton(onClick = actions.onClear) { Text("New plan") }
                }
                planner.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        plan.days.filter { it.entries.isNotEmpty() }.forEach { day ->
            if (plan.days.size > 1) item(key = "day-${day.date}") { SectionHeader(EventTime.formatDayHeader(day.date)) }
            var previousWasItem = false
            day.entries.forEach { entry ->
                when (entry) {
                    is PlannedItem -> {
                        val walk = entry.walkMinutes
                        if (previousWasItem && walk != null && walk > 0) {
                            item(key = "walk-${day.date}-${entry.itemId}") { WalkLeg(walk) }
                        }
                        item(key = "item-${entry.itemId}") {
                            PlanItemRow(
                                bundle, entry, now,
                                checked = entry.itemId in plan.checkedOff,
                                onClick = { onItemClick(entry.itemId) },
                                onSwap = { actions.onSwap(entry.itemId, it) },
                                onCheck = { actions.onCheck(entry.itemId) },
                            )
                        }
                        previousWasItem = true
                    }
                    is MealBreak -> {
                        item(key = "meal-${day.date}-${entry.kind}") { MealRow(bundle, entry) }
                        previousWasItem = false
                    }
                }
            }
        }
        item(key = "footer") {
            Text(
                "Planned on your phone. Liked items are always included when they fit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PlanRequestCard(planner: PlanUiState, actions: PlanActions) {
    var request by rememberSaveable { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text("Plan your day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (!planner.available) {
                Text(
                    "Planning uses Google's on-device AI (Gemini Nano), which isn't available on this phone right now.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Text(
                "Say what you'd like and your phone builds a plan from the schedule, with your likes and walking times. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                placeholder = { Text("Plan my afternoon around live music") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.onMakePlan(request) }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (planner.isPlanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text("Planning on your phone…")
                }
            } else {
                Button(onClick = { actions.onMakePlan(request) }, enabled = request.isNotBlank()) { Text("Make my plan") }
            }
            planner.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val examples = planner.examples.filter { it.isPlan }
            if (examples.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { example ->
                        SuggestionChip(onClick = { request = example.text; actions.onExample(example) }, label = { Text(example.text) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanChangedCard(actions: PlanActions) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your plan changed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("The organizers moved or removed something in your plan.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onReplan) { Text("Re-plan") }
                TextButton(onClick = actions.onKeepChanged) { Text("Keep as is") }
            }
        }
    }
}

@Composable
private fun WalkLeg(minutes: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$minutes min walk", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanItemRow(bundle: EventBundle, entry: PlannedItem, now: Instant, checked: Boolean, onClick: () -> Unit, onSwap: (Int) -> Unit, onCheck: () -> Unit) {
    val item = bundle.scheduleItem(entry.itemId)
    var menu by remember { mutableStateOf(false) }
    val zone = bundle.event.zone
    val ended = item != null && ScheduleGrouping.hasEnded(item, now)
    ListItem(
        onClick = onClick,
        overlineContent = {
            Text(
                if (item != null) EventTime.formatTimeRange(item.startTime, item.endTime, zone) else "",
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = {
            Column {
                val place = bundle.location(item?.locationId)?.name ?: item?.locationName ?: ScheduleGrouping.GENERAL_TITLE
                Text(place, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item != null && item.startTime?.toInstant()?.isAfter(now) == false) {
                    IconToggleButton(checked = checked, onCheckedChange = { onCheck() }) {
                        Icon(
                            if (checked) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                            contentDescription = if (checked) "Seen ${item.name}" else "Mark ${item.name} as seen",
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (item != null) HeartButton(SearchKind.SCHEDULE, item.id, name = item.name)
                if (entry.alternatives.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap ${item?.name.orEmpty()}")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            entry.alternatives.mapNotNull { bundle.scheduleItem(it) }.forEach { alternative ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(alternative.name)
                                            Text(
                                                EventTime.formatTimeRange(alternative.startTime, alternative.endTime, zone) +
                                                    (bundle.location(alternative.locationId)?.name?.let { " · $it" } ?: "") +
                                                    (if (alternative.id in entry.orInstead) " · liked" else ""),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        menu = false
                                        onSwap(alternative.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        Text(
            item?.name ?: "No longer on the schedule",
            fontWeight = FontWeight.SemiBold,
            color = if (ended || item == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider()
}

@Composable
private fun MealRow(bundle: EventBundle, meal: MealBreak) {
    val zone = bundle.event.zone
    val format = remember { DateTimeFormatter.ofPattern("h:mm a") }
    ListItem(
        leadingContent = { Icon(Icons.Filled.Restaurant, contentDescription = null) },
        supportingContent = {
            Text(
                "${meal.start.atZone(zone).format(format)} – ${meal.end.atZone(zone).format(format)}" +
                    (bundle.location(meal.locationId)?.name?.let { " · $it nearby" } ?: ""),
            )
        },
    ) {
        Text(meal.kind.label, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}
