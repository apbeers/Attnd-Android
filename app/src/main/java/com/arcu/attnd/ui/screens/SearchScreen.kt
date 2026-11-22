@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.RemoteImage

/** What the search sheet's back stack holds: an item's detail. */
data class SearchEntry(val kind: SearchKind, val id: Int) {
    fun encode(): String = "${kind.name}:$id"

    companion object {
        fun decodeStack(value: String): List<SearchEntry> = value.split(',').mapNotNull { part ->
            val (kind, id) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val parsedKind = SearchKind.entries.firstOrNull { it.name == kind } ?: return@mapNotNull null
            id.toIntOrNull()?.let { SearchEntry(parsedKind, it) }
        }

        fun encodeStack(stack: List<SearchEntry>): String = stack.joinToString(",") { it.encode() }
    }
}

/**
 * The floating button on every tab (iOS `floatingActionButton()`): a search icon that opens the search
 * sheet ([SearchSheet]) with the field focused, or with [plan] (Gemini Nano available, downloadable or
 * downloading) the My plan button.
 */
@Composable
fun SearchFab(onClick: () -> Unit, modifier: Modifier = Modifier, plan: Boolean = false) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        if (plan) Icon(Icons.Filled.AutoAwesome, contentDescription = "My plan")
        else Icon(Icons.Filled.Search, contentDescription = "Search")
    }
}

/**
 * The event's top app bar on the organizer tabs (Home has none): the event logo (`display.logo_url`)
 * and name, and the Options gear only on the Options tab's root ([onOptions] null elsewhere; iOS
 * `optionsToolbarItem(for:)`). A wavy progress line shows a pull-to-refresh in progress.
 */
@Composable
fun EventTopBar(bundle: EventBundle, isRefreshing: Boolean, onOptions: (() -> Unit)?) {
    val logoUrl = bundle.display.logoUrl
    Column {
        TopAppBar(
            title = { Text(bundle.event.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (logoUrl != null) {
                    // Sized like an icon button, so the title lines up as it would after one.
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        RemoteImage(
                            url = logoUrl,
                            contentDescription = null,
                            maxDimension = 128,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                            placeholder = {},
                        )
                    }
                }
            },
            actions = {
                if (onOptions != null) IconButton(onClick = onOptions) { Icon(Icons.Filled.Settings, contentDescription = "Options") }
            },
        )
        if (isRefreshing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** An item opened from the assistant's results or cards, on its own back stack. */
@Composable
fun SearchDetail(
    bundle: EventBundle,
    entry: SearchEntry,
    onBack: () -> Unit,
    onPush: (SearchEntry) -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
    showHeader: Boolean = true,
) {
    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to results") }
                Text("Results", style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider()
        }
        when (entry.kind) {
            SearchKind.SCHEDULE -> {
                val item = bundle.scheduleItem(entry.id)
                if (item == null) {
                    EmptyState("This item is no longer on the schedule.")
                } else {
                    LaunchedEffect(item.id) { onScheduleItemShown(item.id) }
                    ScheduleItemDetail(bundle, item, onBack = null, onLocationClick = { onPush(SearchEntry(SearchKind.LOCATION, it)) })
                }
            }
            SearchKind.LOCATION -> {
                val location = bundle.location(entry.id)
                if (location == null) {
                    EmptyState("This location is no longer listed.")
                } else {
                    LaunchedEffect(location.id) { onLocationShown(location.id) }
                    LocationDetail(bundle, location, onBack = null, onScheduleItemClick = { onPush(SearchEntry(SearchKind.SCHEDULE, it)) })
                }
            }
            SearchKind.NOTIFICATION -> {
                val notification = bundle.notifications.firstOrNull { it.id == entry.id }
                if (notification == null) {
                    EmptyState("This update is no longer available.")
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (notification.isUrgent) {
                            Text("Urgent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        Text(notification.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            EventTime.formatDateTime(notification.sendTime, bundle.event.zone),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notification.body.isNotBlank()) Text(notification.body, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            SearchKind.EVENT -> EventInfoScreen(bundle, onLocation = { onPush(SearchEntry(SearchKind.LOCATION, it)) })
        }
    }
}
