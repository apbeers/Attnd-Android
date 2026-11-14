@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.rememberNow
import java.time.Duration

/** Organizer notifications, newest first (the server already sorts them). */
@Composable
fun NotificationsScreen(
    bundle: EventBundle,
    isRefreshing: Boolean,
    lastSyncedAt: Long?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = bundle.event.zone
    val now = rememberNow()
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        if (bundle.notifications.isEmpty()) {
            EmptyState("No announcements yet. Organizer updates will appear here.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (lastSyncedAt != null) {
                    val minutes = Duration.ofMillis(now.toEpochMilli() - lastSyncedAt).toMinutes()
                    if (minutes >= 5) {
                        item(key = "stale") {
                            Text(
                                "Updated $minutes minutes ago. Pull to refresh.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                items(bundle.notifications, key = { it.id }) { notification ->
                    ListItem(
                        headlineContent = { Text(notification.title) },
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
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
