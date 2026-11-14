@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.IdListDetail
import com.arcu.attnd.ui.components.SearchField
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow
import java.time.Instant
import java.time.ZoneId

/** Schedule tab: list (styled by `display.schedule_style`) with a detail pane. */
@Composable
fun ScheduleTab(
    bundle: EventBundle,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    focusItemId: Int?,
    onFocusConsumed: () -> Unit,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    IdListDetail(
        requestedId = focusItemId,
        onRequestConsumed = onFocusConsumed,
        modifier = modifier,
        list = { selectedId, open ->
            ScheduleList(
                bundle = bundle,
                style = bundle.display.scheduleStyle,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                selectedId = selectedId,
                onItemClick = open,
            )
        },
        detail = { id, showBack, back ->
            val item = bundle.scheduleItem(id)
            if (item == null) {
                EmptyState("This item is no longer on the schedule.")
            } else {
                ScheduleItemDetail(bundle, item, onBack = if (showBack) back else null, onLocationClick = onLocationClick)
            }
        },
        emptyDetail = { EmptyState("Select an item to see its details.") },
    )
}

/**
 * The schedule, grouped by [style]. All times are in the event's time zone. Also used beside the
 * map (with [style] = now_next for the `now_next` secondary pane).
 */
@Composable
fun ScheduleList(
    bundle: EventBundle,
    style: ScheduleStyle,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    selectedId: Int?,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val zone = bundle.event.zone
    val now = rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    // Past items are hidden by default while the event is live; an archived event shows everything.
    var showPast by rememberSaveable(bundle.event.id) { mutableStateOf(bundle.event.isArchived) }

    val sections = remember(bundle.schedule, bundle.locations, style, query, showPast, now) {
        val searched = ScheduleGrouping.filter(bundle.schedule, query)
        val visible = if (showPast || style == ScheduleStyle.NOW_NEXT) searched
        else searched.filterNot { ScheduleGrouping.hasEnded(it, now) }
        ScheduleGrouping.group(style, visible, bundle.locations, zone, now)
    }

    Column(modifier.fillMaxSize()) {
        if (bundle.display.enableSearch) SearchField(query, { query = it }, "Search schedule")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (zone.normalized() != ZoneId.systemDefault().normalized()) {
                Text(
                    "Times shown in ${EventTime.zoneLabel(zone, now)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }
            if (style != ScheduleStyle.NOW_NEXT) {
                FilterChip(selected = showPast, onClick = { showPast = !showPast }, label = { Text("Show past") })
            }
        }
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (sections.isEmpty()) {
                EmptyState(
                    when {
                        bundle.schedule.isEmpty() -> "No schedule has been posted yet."
                        query.isNotBlank() -> "Nothing on the schedule matches your search."
                        style == ScheduleStyle.NOW_NEXT -> "Nothing is happening now or coming up."
                        else -> "No upcoming items. Tap \"Show past\" to see earlier ones."
                    }
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    sections.forEach { section ->
                        if (section.title.isNotEmpty()) item(key = "h-${section.key}") { SectionHeader(section.title) }
                        items(section.items, key = { "${section.key}-${it.id}" }) { item ->
                            ScheduleRow(
                                item = item,
                                zone = zone,
                                now = now,
                                showLocation = style != ScheduleStyle.BY_LOCATION,
                                selected = item.id == selectedId,
                                onClick = { onItemClick(item.id) },
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
private fun ScheduleRow(
    item: ScheduleItem,
    zone: ZoneId,
    now: Instant,
    showLocation: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val live = ScheduleGrouping.isHappeningNow(item, now)
    val badge = if (live) "Now" else EventTime.startsInText(item.startTime, now)
    ListItem(
        overlineContent = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(EventTime.formatTimeRange(item.startTime, item.endTime, zone), fontWeight = FontWeight.SemiBold)
                if (showLocation && item.locationName != null) {
                    Text(item.locationName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        supportingContent = if (item.description.isNotBlank() || item.tags.isNotEmpty()) {
            {
                Column {
                    if (item.description.isNotBlank()) {
                        Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.tags.isNotEmpty()) {
                        Text(
                            item.tags.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            null
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun ScheduleItemDetail(
    bundle: EventBundle,
    item: ScheduleItem,
    onBack: (() -> Unit)?,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = bundle.event.zone
    val now = rememberNow()
    val location = bundle.location(item.locationId)

    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(item.name, style = MaterialTheme.typography.headlineMedium)
            item.startTime?.let { start ->
                Text(
                    EventTime.formatDayHeader(EventTime.localDate(start, zone)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                "${EventTime.formatTimeRange(item.startTime, item.endTime, zone)} ${EventTime.zoneLabel(zone, now)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                ScheduleGrouping.isHappeningNow(item, now) -> Text("Happening now", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                else -> EventTime.startsInText(item.startTime, now)?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            if (location != null) {
                OutlinedButton(onClick = { onLocationClick(location.id) }) {
                    Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(location.name)
                }
            } else {
                Text(
                    item.locationName ?: ScheduleGrouping.GENERAL_TITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyLarge)
            }
            if (item.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(tag) }) }
                }
            }
        }
    }
}
