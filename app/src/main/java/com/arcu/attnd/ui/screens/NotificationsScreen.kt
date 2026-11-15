package com.arcu.attnd.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.rememberNow
import java.time.Duration

/**
 * Organizer notifications, newest first (the server already sorts them). [highlightId] (from search
 * or an assistant) scrolls to that notification and briefly highlights it; [onHighlightShown] is
 * called once it is on screen.
 */
@Composable
fun NotificationsScreen(
    bundle: EventBundle,
    isRefreshing: Boolean,
    lastSyncedAt: Long?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    highlightId: Int? = null,
    onHighlightShown: () -> Unit = {},
) {
    val zone = bundle.event.zone
    val now = rememberNow()
    val listState = rememberLazyListState()
    val highlight = remember { Animatable(0f) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    val currentOnHighlightShown by rememberUpdatedState(onHighlightShown)
    val showStale = lastSyncedAt != null && Duration.ofMillis(now.toEpochMilli() - lastSyncedAt).toMinutes() >= 5

    LaunchedEffect(highlightId, bundle.notifications) {
        val id = highlightId ?: return@LaunchedEffect
        val index = bundle.notifications.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index + if (showStale) 1 else 0)
        currentOnHighlightShown()
        highlighted = id
        highlight.snapTo(1f)
        highlight.animateTo(0f, tween(durationMillis = HIGHLIGHT_MS, delayMillis = HIGHLIGHT_HOLD_MS))
        highlighted = null
    }

    RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        if (bundle.notifications.isEmpty()) {
            EmptyState("No announcements yet. Organizer updates will appear here.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (showStale && lastSyncedAt != null) {
                    val minutes = Duration.ofMillis(now.toEpochMilli() - lastSyncedAt).toMinutes()
                    item(key = "stale") {
                        Text(
                            "Updated $minutes minutes ago. Pull to refresh.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(bundle.notifications, key = { it.id }) { notification ->
                    val colors = if (notification.id == highlighted) {
                        ListItemDefaults.colors(
                            containerColor = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, highlight.value),
                        )
                    } else {
                        ListItemDefaults.colors()
                    }
                    ListItem(
                        supportingContent = {
                            Column {
                                if (notification.body.isNotBlank()) Text(notification.body)
                                Text(
                                    EventTime.formatDateTime(notification.sendTime, zone),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        },
                        colors = colors,
                    ) {
                        Text(notification.title)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private const val HIGHLIGHT_HOLD_MS = 800
private const val HIGHLIGHT_MS = 1_200
