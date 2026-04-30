package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.data.ScheduledItem
import com.arcu.attnd.ui.theme.AttndTheme
import com.arcu.attnd.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    items: List<ScheduledItem>,
    locations: List<EventLocation>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onItemClick: (locationId: Int) -> Unit
) {
    var hideEnded by remember { mutableStateOf(true) }
    var selectedLocationId by remember { mutableStateOf<Int?>(null) }
    var filterMenuExpanded by remember { mutableStateOf(false) }

    val filteredItems = remember(items, hideEnded, selectedLocationId) {
        var result = items
        if (hideEnded) {
            result = result.filter { !FormatUtils.hasEnded(it.startTime) }
        }
        if (selectedLocationId != null) {
            result = result.filter { it.locationId == selectedLocationId }
        }
        result
    }

    val filterActive = !hideEnded || selectedLocationId != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule") },
                actions = {
                    Box {
                        IconButton(onClick = { filterMenuExpanded = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = "Filter",
                                tint = if (filterActive) MaterialTheme.colorScheme.primary
                                       else LocalContentColor.current
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (hideEnded) "Show All Items" else "Hide Ended Items")
                                },
                                onClick = {
                                    hideEnded = !hideEnded
                                    filterMenuExpanded = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selectedLocationId == null) {
                                            Text("✓ ", color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text("All Locations")
                                    }
                                },
                                onClick = {
                                    selectedLocationId = null
                                    filterMenuExpanded = false
                                }
                            )
                            if (locations.isNotEmpty()) {
                                HorizontalDivider()
                                locations.forEach { loc ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (selectedLocationId == loc.id) {
                                                    Text("✓ ", color = MaterialTheme.colorScheme.primary)
                                                }
                                                Text(loc.name)
                                            }
                                        },
                                        onClick = {
                                            selectedLocationId = loc.id
                                            filterMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Text(
                    text = "Error: $error",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error
                )
                filteredItems.isEmpty() -> {
                    val message = when {
                        hideEnded && selectedLocationId != null ->
                            "No upcoming items at this location.\nTap the filter to show all."
                        hideEnded ->
                            "No upcoming items.\nTap the filter to see past items."
                        selectedLocationId != null ->
                            "No items scheduled at this location."
                        else -> "Schedule items will appear here once loaded."
                    }
                    Text(
                        text = message,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (isRefreshing) {
                            item {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        items(filteredItems, key = { it.id }) { item ->
                            ScheduleItemRow(
                                item = item,
                                showLocation = selectedLocationId == null,
                                onClick = { onItemClick(item.locationId) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleItemRow(
    item: ScheduledItem,
    showLocation: Boolean,
    onClick: () -> Unit
) {
    val countdown = remember(item.startTime) { FormatUtils.startsInText(item.startTime) }

    ListItem(
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (countdown != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        },
        overlineContent = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = FormatUtils.formatScheduleTime(item.startTime),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (showLocation) {
                    Text(
                        text = item.locationName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Preview(showBackground = true)
@Composable
fun ScheduleScreenPreview() {
    val items = listOf(
        ScheduledItem(1, "Opening Act", "Kick off the fair.", "2026-07-04T18:00:00+00:00", 1, "Main Stage"),
        ScheduledItem(2, "Food Contest", "Who can eat the most?", "2026-07-04T19:00:00+00:00", 2, "Food Court"),
        ScheduledItem(3, "Headliner", "Main performance.", "2026-07-04T20:30:00+00:00", 1, "Main Stage"),
    )
    val locations = listOf(
        EventLocation(1, "Main Stage", "Live Music", "", null, null, 0.0, 0.0, ""),
        EventLocation(2, "Food Court", "Tacos", "", null, null, 0.0, 0.0, ""),
    )
    AttndTheme {
        ScheduleScreen(
            items = items,
            locations = locations,
            isLoading = false,
            isRefreshing = false,
            error = null,
            onRefresh = {},
            onItemClick = {}
        )
    }
}
