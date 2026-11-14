@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.arcu.attnd.domain.LocationGrouping
import com.arcu.attnd.domain.LocationsStyle
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.ui.components.CategoryChips
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.IdListDetail
import com.arcu.attnd.ui.components.ImagePlaceholder
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.SearchField
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow

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
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(bundle.locations, bundle.categories) {
        LocationGrouping.availableCategories(bundle.locations, bundle.categories)
    }
    val filtered = remember(bundle.locations, query, category) {
        LocationGrouping.filter(bundle.locations, query, category)
    }

    Column(modifier.fillMaxSize()) {
        if (display.enableSearch) SearchField(query, { query = it }, "Search locations", onSearch = onSearch)
        if (display.groupByCategory) CategoryChips(categories, category, { category = it })
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                EmptyState(
                    if (bundle.locations.isEmpty()) "No locations have been added yet."
                    else "No locations match your search."
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    when (display.locationsStyle) {
                        LocationsStyle.CARDS -> items(filtered, key = { it.id }) { location ->
                            LocationCard(location, display.showLocationImages, location.id == selectedId) {
                                onLocationClick(location.id)
                            }
                        }
                        LocationsStyle.LIST -> locationRows(filtered, display.showLocationImages, selectedId, onLocationClick)
                        LocationsStyle.GROUPED -> LocationGrouping.byCategory(filtered, bundle.categories).forEach { section ->
                            item(key = section.key) { SectionHeader(section.title) }
                            locationRows(section.locations, display.showLocationImages, selectedId, onLocationClick)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.locationRows(
    locations: List<EventLocation>,
    showImages: Boolean,
    selectedId: Int?,
    onClick: (Int) -> Unit,
) {
    items(locations, key = { it.id }) { location ->
        LocationRow(location, showImages, location.id == selectedId) { onClick(location.id) }
        HorizontalDivider()
    }
}

@Composable
private fun LocationRow(location: EventLocation, showImage: Boolean, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(location.name) },
        supportingContent = if (location.snippet.isNotBlank()) {
            { Text(location.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else {
            null
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
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PinPlaceholder() {
    ImagePlaceholder(Modifier.fillMaxSize())
}

@Composable
private fun LocationCard(location: EventLocation, showImage: Boolean, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (showImage && location.imageUrl != null) {
            RemoteImage(
                url = location.imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        Column(Modifier.padding(16.dp)) {
            location.category?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(location.name, style = MaterialTheme.typography.titleLarge)
            if (location.snippet.isNotBlank()) {
                Text(location.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(location.name, style = MaterialTheme.typography.headlineMedium)
                if (location.address.isNotBlank()) {
                    Text(location.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                location.coordinate?.let { point ->
                    Button(
                        onClick = {
                            val uri = "https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}&travelmode=walking".toUri()
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Walking directions")
                    }
                }
                if (location.description.isNotBlank()) {
                    Text(location.description, style = MaterialTheme.typography.bodyLarge)
                }
                location.website?.let { url ->
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Visit website")
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
                                EventTime.startsInText(item.startTime, now)?.let {
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
