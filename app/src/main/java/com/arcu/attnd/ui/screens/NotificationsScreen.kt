@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.AppNotification
import com.arcu.attnd.ui.theme.AttndTheme
import com.arcu.attnd.util.FormatUtils

@Composable
fun NotificationsScreen(
    notifications: List<AppNotification>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    lastRefreshedAt: Long?
) {
    val state = rememberPullToRefreshState()
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Update currentTime every 30 seconds to refresh the "minutes ago" display
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            currentTime = System.currentTimeMillis()
        }
    }
    
    // Logic for "minutes since last refreshed" header
    val showRefreshHeader = remember(lastRefreshedAt, currentTime) {
        lastRefreshedAt?.let { (currentTime - it) / 1000 / 60 >= 5 } ?: false
    }
    val minutesElapsed = remember(lastRefreshedAt, currentTime) {
        lastRefreshedAt?.let { (currentTime - it) / 1000 / 60 } ?: 0L
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = Modifier.fillMaxSize()
    ) {
        if (isLoading && !isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (error != null && notifications.isEmpty()) {
            Text(
                text = "Error: $error", 
                modifier = Modifier.align(Alignment.Center), 
                color = MaterialTheme.colorScheme.error
            )
        } else {
            val sortedNotifications = remember(notifications) {
                notifications.sortedByDescending { it.sendTime }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (showRefreshHeader) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Refresh, 
                                    contentDescription = null, 
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Last updated $minutesElapsed minutes ago. Pull to refresh.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
                
                items(sortedNotifications) { notification ->
                    ListItem(
                        headlineContent = { Text(notification.title) },
                        supportingContent = { 
                            Column {
                                Text(notification.body)
                                Text(
                                    text = FormatUtils.formatNotificationTime(notification.sendTime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationsScreenPreview() {
    val dummyNotifications = listOf(
        AppNotification(1, "Gate B is now open", "Head to Gate B for faster entry.", "2026-06-15T17:45:00+00:00", true),
        AppNotification(2, "Welcome to the Fair!", "Enjoy the art and food.", "2026-06-15T17:00:00+00:00", true)
    )
    AttndTheme {
        NotificationsScreen(
            notifications = dummyNotifications,
            isLoading = false,
            isRefreshing = false,
            error = null,
            onRefresh = {},
            lastRefreshedAt = System.currentTimeMillis() - (6 * 60 * 1000) // 6 minutes ago
        )
    }
}
