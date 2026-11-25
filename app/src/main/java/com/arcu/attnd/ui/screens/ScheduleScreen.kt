@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import com.arcu.attnd.ui.theme.themedProgressColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.ui.assistant.AssistantIntents
import com.arcu.attnd.ui.components.LocalScreenshotMode
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.TabListLayout
import com.arcu.attnd.ui.components.rememberLayoutSections
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tags
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LiveDot
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.PeekSheet
import com.arcu.attnd.ui.components.PeekTarget
import com.arcu.attnd.ui.components.WalkTime
import com.arcu.attnd.ui.components.IdListDetail
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.SearchField
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow
import java.time.Instant
import java.time.ZoneId
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Error
import androidx.compose.ui.draw.alpha
import com.arcu.attnd.domain.MyScheduleRow
import com.arcu.attnd.domain.MyScheduleText
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.ScheduleEntryKind

/**
 * Schedule tab: list with a detail pane. The tab's own layout (API-027 `schedule_tab`, [TabListLayout]) picks the
 * list style and the organizer's featured items; without one (and always in screenshot mode) `display.schedule_style`.
 */
@Composable
fun ScheduleTab(
    bundle: EventBundle,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    focusItemId: Int?,
    onFocusConsumed: () -> Unit,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemShown: (Int) -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val layoutSections = rememberLayoutSections(LayoutScreen.SCHEDULE_TAB, bundle)
    val tabLayout = remember(layoutSections) { TabListLayout.schedule(layoutSections) }
    IdListDetail(
        requestedId = focusItemId,
        onRequestConsumed = onFocusConsumed,
        modifier = modifier,
        list = { selectedId, open ->
            ScheduleList(
                bundle = bundle,
                style = tabLayout?.style?.let(ScheduleStyle::fromKey) ?: bundle.display.scheduleStyle,
                tabLayout = tabLayout,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                selectedId = selectedId,
                onItemClick = open,
                onSearch = onSearch,
                // API-027 `my_schedule` on / off (no layout: on); never in screenshot mode (no stored schedule).
                showsMySchedule = tabLayout?.showsMySchedule ?: true,
                onLocationClick = onLocationClick,
            )
        },
        detail = { id, showBack, back ->
            val item = bundle.scheduleItem(id)
            if (item == null) {
                EmptyState("This item is no longer on the schedule.")
            } else {
                LaunchedEffect(id) { onItemShown(id) }
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
    onSearch: () -> Unit = {},
    /**
     * The Schedule tab's layout (API-027): its featured items first. Null beside the map and without a layout.
     */
    tabLayout: TabListLayout? = null,
    /**
     * The Schedule tab's first sections (iOS `MyScheduleSection`): organizer changes, My schedule's timed
     * entries (upcoming first, ended ones dimmed at the end) and "Saved Places"; each hidden while empty
     * and while searching. Off beside the map.
     */
    showsMySchedule: Boolean = false,
    onLocationClick: (Int) -> Unit = {},
) {
    val zone = bundle.event.zone
    val now = rememberNow()
    val actions = LocalEventActions.current
    var query by rememberSaveable { mutableStateOf("") }
    // Past items are hidden by default while the event is live; an archived event shows everything.
    var showPast by rememberSaveable(bundle.event.id) { mutableStateOf(bundle.event.isArchived) }
    var peek by rememberSaveable { mutableStateOf<Int?>(null) }

    val sections = remember(bundle.schedule, bundle.locations, style, query, showPast, now) {
        val searched = ScheduleGrouping.filter(bundle.schedule, query)
        val visible = if (showPast || style == ScheduleStyle.NOW_NEXT) searched
        else searched.filterNot { ScheduleGrouping.hasEnded(it, now) }
        ScheduleGrouping.group(style, visible, bundle.locations, zone, now)
    }

    peek?.let { id ->
        PeekSheet(bundle, PeekTarget(SearchKind.SCHEDULE, id), now, onOpen = { onItemClick(id) }, onDismiss = { peek = null })
    }

    Column(modifier.fillMaxSize()) {
        if (bundle.display.enableSearch) SearchField(query, { query = it }, "Search schedule", onSearch = onSearch)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (style != ScheduleStyle.NOW_NEXT) {
                    FilterChip(selected = showPast, onClick = { showPast = !showPast }, label = { Text("Show past") })
                }
            }
        }
        RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            val mine = actions.schedule?.takeIf { showsMySchedule && query.isBlank() }
            val mineRows = remember(mine, bundle, now) { mine?.sectionRows(bundle, now).orEmpty() }
            val changes = remember(mine, bundle) { mine?.organizerChanges(bundle).orEmpty() }
            val saved = remember(mine, bundle) { mine?.savedPlaceIds.orEmpty().mapNotNull { bundle.location(it) } }
            val hasMine = mineRows.isNotEmpty() || changes.isNotEmpty() || saved.isNotEmpty()
            if (sections.isEmpty() && !hasMine) {
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
                    if (mineRows.isNotEmpty() || changes.isNotEmpty()) {
                        item(key = "h-mine") { MyScheduleHeader(MyScheduleText.TITLE) }
                        items(changes, key = { "change-${it.itemId}" }) { change ->
                            OrganizerChangeRow(change, zone, onKeep = { actions.onKeepOrganizerChange(change.itemId) }, onRemove = {
                                if (change.kind == OrganizerChange.Kind.Cancelled) actions.onKeepOrganizerChange(change.itemId)
                                else actions.onRemove(SearchKind.SCHEDULE, change.itemId)
                            }, onFindAnother = { actions.onFindAnother(change) })
                            HorizontalDivider()
                        }
                        items(mineRows, key = { "mine-${it.id}" }) { row ->
                            Box(Modifier.alpha(if (row.ended) 0.5f else 1f)) {
                                when (row.entry.kind) {
                                    ScheduleEntryKind.ITEM -> bundle.scheduleItem(row.entry.id)?.let { item ->
                                        ScheduleRow(
                                            item = item,
                                            location = bundle.location(item.locationId),
                                            zone = zone,
                                            now = now,
                                            showLocation = true,
                                            selected = item.id == selectedId,
                                            onClick = { onItemClick(item.id) },
                                            onLongClick = { peek = item.id },
                                        )
                                    }
                                    ScheduleEntryKind.PLACE -> TimedPlaceRow(row, zone, onClick = { onLocationClick(row.entry.id) })
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (saved.isNotEmpty()) {
                        item(key = "h-saved") { SectionHeader(MyScheduleText.SAVED_PLACES) }
                        items(saved, key = { "saved-${it.id}" }) { location ->
                            ListItem(
                                selected = false,
                                onClick = { onLocationClick(location.id) },
                                leadingContent = { Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingContent = { HeartButton(SearchKind.LOCATION, location.id, name = location.name) },
                            ) { Text(location.name, fontWeight = FontWeight.SemiBold) }
                            HorizontalDivider()
                        }
                    }
                    // API-027: the organizer's featured items first (not while searching).
                    if (query.isBlank()) {
                        val featured = tabLayout?.featured.orEmpty().mapNotNull { bundle.scheduleItem(it) }
                        if (featured.isNotEmpty()) {
                            item(key = "h-featured") { SectionHeader(tabLayout?.featuredTitle ?: "Featured") }
                            items(featured, key = { "featured-${it.id}" }) { item ->
                                ScheduleRow(
                                    item = item,
                                    location = bundle.location(item.locationId),
                                    zone = zone,
                                    now = now,
                                    showLocation = true,
                                    selected = item.id == selectedId,
                                    onClick = { onItemClick(item.id) },
                                    onLongClick = { peek = item.id },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    sections.forEach { section ->
                        if (section.title.isNotEmpty()) item(key = "h-${section.key}") { SectionHeader(section.title) }
                        items(section.items, key = { "${section.key}-${it.id}" }) { item ->
                            ScheduleRow(
                                item = item,
                                location = bundle.location(item.locationId),
                                zone = zone,
                                now = now,
                                showLocation = style != ScheduleStyle.BY_LOCATION,
                                selected = item.id == selectedId,
                                onClick = { onItemClick(item.id) },
                                onLongClick = { peek = item.id },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * A schedule row: time range and place, a live relative time ("in 12 min", "Started 5 min ago",
 * updated every 30 s with [now]), a thin progress bar while in progress, the walk time to its
 * location when the user is at the event, and a heart. Long-press opens the peek.
 */
@Composable
private fun ScheduleRow(
    item: ScheduleItem,
    location: EventLocation?,
    zone: ZoneId,
    now: Instant,
    showLocation: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val live = ScheduleGrouping.isHappeningNow(item, now)
    val badge = RelativeTime.forItem(item, now, zone)
    val progress = Glance.progress(item, now)
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "Preview",
        trailingContent = { HeartButton(SearchKind.SCHEDULE, item.id, name = item.name) },
        overlineContent = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(EventTime.formatTimeRange(item.startTime, item.endTime, zone), fontWeight = FontWeight.SemiBold)
                if (showLocation && item.locationName != null) {
                    Text(item.locationName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        supportingContent = {
            Column {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp),
                        color = themedProgressColor,
                        drawStopIndicator = {},
                    )
                }
                if (item.description.isNotBlank()) {
                    Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (item.tags.isNotEmpty()) {
                    Text(
                        item.tags.joinToString(" · ") { Tags.display(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!ScheduleGrouping.hasEnded(item, now)) WalkTime(location?.coordinate, Modifier.padding(top = 2.dp))
            }
        },
        colors = ListItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            if (badge != null) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (live) LiveDot()
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (badge == RelativeTime.ENDED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A place in My schedule at a chosen time: "7:00 – 7:45 PM", the place, its heart (menu: Change Time…). */
@Composable
private fun TimedPlaceRow(row: MyScheduleRow, zone: ZoneId, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        overlineContent = {
            Text(
                EventTime.formatTimeRange(row.start.atZone(zone).toOffsetDateTime(), row.end.atZone(zone).toOffsetDateTime(), zone),
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = row.entry.reason?.let { reason -> { Text(reason, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { HeartButton(SearchKind.LOCATION, row.entry.id, name = row.name) },
    ) { Text(row.name, fontWeight = FontWeight.SemiBold) }
}

/**
 * "X moved to 8:00 PM." with Keep / Remove / Find Another for an item in My schedule the organizer changed
 * (iOS `OrganizerChangeRow`); a cancelled one offers Remove and Find Another.
 */
@Composable
private fun OrganizerChangeRow(change: OrganizerChange, zone: ZoneId, onKeep: () -> Unit, onRemove: () -> Unit, onFindAnother: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(MyScheduleText.organizerChange(change, zone), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (change.kind != OrganizerChange.Kind.Cancelled) OutlinedButton(onClick = onKeep) { Text("Keep") }
            OutlinedButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onFindAnother) { Text("Find Another") }
        }
    }
}
