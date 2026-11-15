@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.ButtonGroup
import com.arcu.attnd.ui.components.AskAboutButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventLocation
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.LocationGrouping
import com.arcu.attnd.domain.LocationsStyle
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tags
import com.arcu.attnd.ui.components.CategoryChips
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LiveDot
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.PeekSheet
import com.arcu.attnd.ui.components.PeekTarget
import com.arcu.attnd.ui.components.WalkTime
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.IdListDetail
import com.arcu.attnd.ui.components.ImagePlaceholder
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.SearchField
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow
import java.time.Instant

/** Locations tab: list (styled by `display.locations_style`) with a detail pane. */
@Composable
fun LocationsTab(
    bundle: EventBundle,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    focusLocationId: Int?,
    onFocusConsumed: () -> Unit,
    onScheduleItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLocationShown: (Int) -> Unit = {},
    onSearch: () -> Unit = {},
) {
    IdListDetail(
        requestedId = focusLocationId,
        onRequestConsumed = onFocusConsumed,
        modifier = modifier,
        list = { selectedId, open ->
            LocationsList(
                bundle = bundle,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                selectedId = selectedId,
                onLocationClick = open,
                onSearch = onSearch,
            )
        },
        detail = { id, showBack, back ->
            val location = bundle.location(id)
            if (location == null) {
                EmptyState("This location is no longer listed.")
            } else {
                LaunchedEffect(id) { onLocationShown(id) }
                LocationDetail(
                    bundle = bundle,
                    location = location,
                    onBack = if (showBack) back else null,
                    onScheduleItemClick = onScheduleItemClick,
                )
            }
        },
        emptyDetail = { EmptyState("Select a location to see its details.") },
    )
}

/** The locations list with optional search and category chips. Also used beside the map. */
@Composable
fun LocationsList(
    bundle: EventBundle,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    selectedId: Int?,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onSearch: () -> Unit = {},
) {
    val display = bundle.display
    val hearts = LocalEventActions.current.hearts
    val now = rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var heartedOnly by rememberSaveable { mutableStateOf(false) }
    var peek by rememberSaveable { mutableStateOf<Int?>(null) }
    val categories = remember(bundle.locations, bundle.categories) {
        LocationGrouping.availableCategories(bundle.locations, bundle.categories)
    }
    val filtered = remember(bundle.locations, query, category, heartedOnly, hearts) {
        LocationGrouping.filter(bundle.locations, query, category).filter { !heartedOnly || it.id in hearts.locations }
    }
    val showHeartedChip = heartedOnly || hearts.locations.isNotEmpty()
    val open: (Int) -> Unit = onLocationClick
    val longPress: (Int) -> Unit = { peek = it }

    peek?.let { id ->
        PeekSheet(bundle, PeekTarget(SearchKind.LOCATION, id), now, onOpen = { onLocationClick(id) }, onDismiss = { peek = null })
    }

    Column(modifier.fillMaxSize()) {
        if (display.enableSearch) SearchField(query, { query = it }, "Search locations", onSearch = onSearch)
        if (display.groupByCategory || showHeartedChip) {
            CategoryChips(
                categories = if (display.groupByCategory) categories else emptyList(),
                selected = category,
                onSelect = { category = it },
                hearted = heartedOnly,
                onHeartedChange = if (showHeartedChip) ({ value: Boolean -> heartedOnly = value }) else null,
            )
        }
        RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                EmptyState(
                    when {
                        bundle.locations.isEmpty() -> "No locations have been added yet."
                        heartedOnly && query.isBlank() && category == null -> "No liked locations yet. Tap the heart on a location to keep it here."
                        else -> "No locations match your search."
                    }
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    when (display.locationsStyle) {
                        LocationsStyle.CARDS -> items(filtered, key = { it.id }) { location ->
                            LocationCard(bundle, location, display.showLocationImages, location.id == selectedId, now, open, longPress)
                        }
                        LocationsStyle.LIST -> locationRows(bundle, filtered, display.showLocationImages, selectedId, now, open, longPress)
                        LocationsStyle.GROUPED -> LocationGrouping.byCategory(filtered, bundle.categories).forEach { section ->
                            item(key = section.key) { SectionHeader(section.title) }
                            locationRows(bundle, section.locations, display.showLocationImages, selectedId, now, open, longPress)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.locationRows(
    bundle: EventBundle,
    locations: List<EventLocation>,
    showImages: Boolean,
    selectedId: Int?,
    now: Instant,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
) {
    items(locations, key = { it.id }) { location ->
        LocationRow(bundle, location, showImages, location.id == selectedId, now, { onClick(location.id) }, { onLongClick(location.id) })
        HorizontalDivider()
    }
}

/** "Now: X · Next 8:30 Y" for a location with a schedule (stages etc.). */
@Composable
private fun NowNextLine(bundle: EventBundle, location: EventLocation, now: Instant) {
    val line = remember(bundle.schedule, location.id, now) { Glance.locationLine(bundle, location.id, now) } ?: return
    val live = remember(bundle.schedule, location.id, now) { Glance.isLive(bundle, location.id, now) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) LiveDot()
        Text(
            line,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocationRow(
    bundle: EventBundle,
    location: EventLocation,
    showImage: Boolean,
    selected: Boolean,
    now: Instant,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val hasSchedule = remember(bundle.schedule, location.id) { bundle.schedule.any { it.locationId == location.id } }
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "Preview",
        supportingContent = if (location.snippet.isNotBlank() || hasSchedule) {
            {
                Column {
                    if (location.snippet.isNotBlank()) Text(location.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (hasSchedule) NowNextLine(bundle, location, now)
                }
            }
        } else {
            null
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                HeartButton(SearchKind.LOCATION, location.id, name = location.name)
                DistanceArrow(location.coordinate)
            }
        },
        overlineContent = if (location.category != null) {
            { Text(location.category) }
        } else {
            null
        },
        leadingContent = if (showImage) {
            {
                RemoteImage(
                    url = location.imageUrl,
                    contentDescription = null,
                    maxDimension = 256,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    placeholder = { PinPlaceholder() },
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(location.name)
    }
}

@Composable
private fun PinPlaceholder() {
    ImagePlaceholder(Modifier.fillMaxSize())
}

@Composable
private fun LocationCard(
    bundle: EventBundle,
    location: EventLocation,
    showImage: Boolean,
    selected: Boolean,
    now: Instant,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
) {
    Card(
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(CardDefaults.shape)
            .combinedClickable(
                onClick = { onClick(location.id) },
                onLongClick = { onLongClick(location.id) },
                onLongClickLabel = "Preview",
            ),
    ) {
        if (showImage && location.imageUrl != null) {
            RemoteImage(
                url = location.imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                location.category?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(location.name, style = MaterialTheme.typography.titleLarge)
                if (location.snippet.isNotBlank()) {
                    Text(location.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NowNextLine(bundle, location, now)
            }
            Column(horizontalAlignment = Alignment.End) {
                HeartButton(SearchKind.LOCATION, location.id, name = location.name)
                DistanceArrow(location.coordinate, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}

/** Location detail: image, address, walking directions, description, website and its schedule. */
@Composable
fun LocationDetail(
    bundle: EventBundle,
    location: EventLocation,
    onBack: (() -> Unit)?,
    onScheduleItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actions = LocalEventActions.current
    val zone = bundle.event.zone
    val now = rememberNow()
    val items = remember(bundle.schedule, location.id) {
        ScheduleGrouping.sortChronologically(bundle.scheduleAt(location.id))
    }

    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text(location.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (bundle.display.showLocationImages && location.imageUrl != null) {
                RemoteImage(
                    url = location.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                location.category?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(location.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    HeartButton(SearchKind.LOCATION, location.id, name = location.name)
                }
                if (location.address.isNotBlank()) {
                    Text(location.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DistanceArrow(location.coordinate, style = MaterialTheme.typography.labelLarge)
                    WalkTime(location.coordinate)
                }
                NowNextLine(bundle, location, now)
                val directions = location.coordinate?.let { point ->
                    "https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}&travelmode=walking".toUri()
                }
                val website = location.website?.toUri()
                val showOnMap = actions.onShowOnMap?.takeIf { location.coordinate != null }
                if (directions != null || website != null || showOnMap != null) {
                    // Material 3 Expressive button group: the pressed button widens, its neighbour yields.
                    ButtonGroup(
                        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (showOnMap != null) {
                            clickableItem(
                                onClick = { showOnMap(location.id) },
                                label = "Map",
                                icon = { Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                weight = 1f,
                            )
                        }
                        if (directions != null) {
                            clickableItem(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, directions)) },
                                label = "Directions",
                                icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                weight = 1f,
                            )
                        }
                        if (website != null) {
                            clickableItem(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, website)) },
                                label = "Website",
                                icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                weight = 1f,
                            )
                        }
                    }
                }
                actions.onAskAbout?.let { ask -> AskAboutButton { ask(SearchKind.LOCATION, location.id) } }
                if (location.description.isNotBlank()) {
                    Text(location.description, style = MaterialTheme.typography.bodyLarge)
                }
                if (location.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        location.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(Tags.display(tag)) }) }
                    }
                }
                if (items.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Schedule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    items.forEach { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onScheduleItemClick(item.id) }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    EventTime.formatDayTime(item.startTime, zone),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                RelativeTime.forItem(item, now, zone)?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
