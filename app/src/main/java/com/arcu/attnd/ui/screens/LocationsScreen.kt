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
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LocationGrouping
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.TabListLayout
import com.arcu.attnd.ui.components.rememberLayoutSections
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

/**
 * Locations tab: list with a detail pane. The tab's own layout (API-027 `places_tab`, [TabListLayout]) picks the
 * list (grouped / cards / list) and the organizer's featured places; else `display.locations_style`.
 */
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
    val layoutSections = rememberLayoutSections(LayoutScreen.PLACES_TAB, bundle)
    val tabLayout = remember(layoutSections) { TabListLayout.places(layoutSections) }
    IdListDetail(
        requestedId = focusLocationId,
        onRequestConsumed = onFocusConsumed,
        modifier = modifier,
        list = { selectedId, open ->
            LocationsList(
                bundle = bundle,
                tabLayout = tabLayout,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                selectedId = selectedId,
                onLocationClick = open,
                onSearch = onSearch,
                onScheduleItemClick = onScheduleItemClick,
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
    /** A schedule item tapped in a place's "3 events" disclosure; null = not tappable. */
    onScheduleItemClick: ((Int) -> Unit)? = null,
    /** The Places tab's layout (API-027); null beside the map and without a layout. */
    tabLayout: TabListLayout? = null,
) {
    val display = bundle.display
    val style = tabLayout?.style?.let(LocationsStyle::fromKey) ?: display.locationsStyle
    val hearts = LocalEventActions.current.hearts
    // My Schedule chip: saved and timed places plus the places of My schedule's items (iOS build 169).
    val myPlaces = remember(hearts, bundle.schedule) { MySchedule.placeIds(hearts.locations, hearts.scheduleItems, bundle.schedule) }
    val now = rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var heartedOnly by rememberSaveable { mutableStateOf(false) }
    var peek by rememberSaveable { mutableStateOf<Int?>(null) }
    val categories = remember(bundle.locations, bundle.categories) {
        LocationGrouping.availableCategories(bundle.locations, bundle.categories)
    }
    val filtered = remember(bundle.locations, query, category, heartedOnly, myPlaces) {
        LocationGrouping.filter(bundle.locations, query, category).filter { !heartedOnly || it.id in myPlaces }
    }
    val showHeartedChip = heartedOnly || myPlaces.isNotEmpty()
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
                        heartedOnly && query.isBlank() && category == null -> "Nothing in your schedule yet. Tap ♥ on places and events to see them here."
                        else -> "No locations match your search."
                    }
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    // API-027: the organizer's featured places first (among those the filters keep, not while searching).
                    if (query.isBlank()) {
                        val featured = tabLayout?.featured.orEmpty().mapNotNull { id -> filtered.firstOrNull { it.id == id } }
                        if (featured.isNotEmpty()) {
                            item(key = "h-featured") { SectionHeader(tabLayout?.featuredTitle ?: "Featured") }
                            items(featured, key = { "featured-${it.id}" }) { location ->
                                LocationRow(bundle, location, display.showLocationImages, location.id == selectedId, now, { open(location.id) }, { longPress(location.id) }, onScheduleItemClick)
                                HorizontalDivider()
                            }
                        }
                    }
                    when (style) {
                        LocationsStyle.CARDS -> items(filtered, key = { it.id }) { location ->
                            LocationCard(bundle, location, display.showLocationImages, location.id == selectedId, now, open, longPress, onScheduleItemClick)
                        }
                        LocationsStyle.LIST -> locationRows(bundle, filtered, display.showLocationImages, selectedId, now, open, longPress, onScheduleItemClick)
                        LocationsStyle.GROUPED -> LocationGrouping.byCategory(filtered, bundle.categories).forEach { section ->
                            item(key = section.key) { SectionHeader(section.title) }
                            locationRows(bundle, section.locations, display.showLocationImages, selectedId, now, open, longPress, onScheduleItemClick)
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
    onItem: ((Int) -> Unit)?,
) {
    items(locations, key = { it.id }) { location ->
        LocationRow(bundle, location, showImages, location.id == selectedId, now, { onClick(location.id) }, { onLongClick(location.id) }, onItem)
        HorizontalDivider()
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
    onItem: ((Int) -> Unit)?,
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
                    if (hasSchedule) {
                        NowNextLine(bundle, location, now)
                        PlaceEventsDisclosure(bundle, location.id, now, onItem)
                    }
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
    onItem: ((Int) -> Unit)?,
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
                PlaceEventsDisclosure(bundle, location.id, now, onItem)
            }
            Column(horizontalAlignment = Alignment.End) {
                HeartButton(SearchKind.LOCATION, location.id, name = location.name)
                DistanceArrow(location.coordinate, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}
