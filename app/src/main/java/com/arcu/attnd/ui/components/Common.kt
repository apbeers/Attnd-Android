@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.arcu.attnd.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.SyncState
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.LiveUpdates
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** The current time, re-emitted every [intervalMs] so countdowns and "now/next" stay live. */
@Composable
fun rememberNow(intervalMs: Long = 30_000L): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(intervalMs)
            value = Instant.now()
        }
    }
    return now
}

/**
 * Event status pills: "Demo event" (`is_demo`) and "Event ended" (`status == archived`). Small and
 * always visible; the caller overlays them (e.g. top-centre of the tab content) so they take no
 * layout space. They keep clear of the status bar and display cutouts. Emits nothing when neither applies.
 */
@Composable
fun EventStatusPills(event: Event, modifier: Modifier = Modifier) {
    if (!event.isDemo && !event.isArchived) return
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (event.isDemo) {
            StatusPill(
                icon = Icons.Filled.Science,
                text = "Demo event",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        if (event.isArchived) {
            StatusPill(
                icon = Icons.Filled.EventBusy,
                text = "Event ended",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StatusPill(icon: ImageVector, text: String, container: Color, content: Color) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * Slim offline notice above the tab content while the last refresh failed (see [SyncState.error]),
 * with the time of the last successful refresh in the device's time zone and 12/24-hour setting,
 * e.g. "Offline · last updated 2:14 PM".
 */
@Composable
fun OfflineNotice(sync: SyncState, modifier: Modifier = Modifier) {
    val error = sync.error ?: return
    val context = LocalContext.current
    val now = rememberNow(60_000L)
    val message = LiveUpdates.offlineNoticeText(
        error = error,
        offline = sync.offline,
        lastSyncedAt = sync.lastSyncedAt,
        now = now.toEpochMilli(),
        zone = ZoneId.systemDefault(),
        is24Hour = DateFormat.is24HourFormat(context),
    )
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(message, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * API-005 `search` usage for a search input: counts once per submitted or settled (~1.5 s) non-empty
 * query, never the text. Returns the "submitted" callback for the IME search action. Shared by the
 * per-tab [SearchField]s and the app-wide search screen, so both count exactly the same way.
 */
@Composable
fun rememberSearchReporter(query: String, onSearch: () -> Unit): () -> Unit {
    val currentOnSearch by rememberUpdatedState(onSearch)
    val currentQuery by rememberUpdatedState(query)
    val reported = rememberSaveable { mutableStateOf("") }
    val report: (String) -> Unit = remember(reported) {
        { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && trimmed != reported.value) {
                reported.value = trimmed
                currentOnSearch()
            }
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            reported.value = ""
        } else {
            delay(SEARCH_SETTLE_MS)
            report(query)
        }
    }
    return remember(report) { { report(currentQuery) } }
}

@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val submit = rememberSearchReporter(query, onSearch)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private const val SEARCH_SETTLE_MS = 1_500L

/** Pull to refresh with the Material 3 Expressive loading indicator. */
@Composable
fun RefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}

/**
 * Horizontally scrolling filter chips: "Hearted" first when [onHeartedChange] is set, then "All" +
 * one per category (none when [categories] is empty). Emits nothing when there is neither.
 */
@Composable
fun CategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    hearted: Boolean = false,
    onHeartedChange: ((Boolean) -> Unit)? = null,
) {
    if (categories.isEmpty() && onHeartedChange == null) return
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onHeartedChange != null) HeartedChip(hearted, onHeartedChange)
        if (categories.isEmpty()) return@Row
        CategoryChip(label = "All", isSelected = selected == null) { onSelect(null) }
        categories.forEach { category ->
            CategoryChip(label = category, isSelected = selected == category) {
                onSelect(if (selected == category) null else category)
            }
        }
    }
}

/** "Hearted": show only hearted items / places. */
@Composable
fun HeartedChip(selected: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text("Hearted") },
        leadingIcon = {
            Icon(
                if (selected) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun CategoryChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (isSelected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
