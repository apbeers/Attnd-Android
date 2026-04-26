package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.AppNotification
import com.arcu.attnd.ui.theme.AttndTheme

@Composable
fun NotificationsScreen(
    notifications: List<AppNotification>,
    isLoading: Boolean,
    error: String?
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
            Text(text = "Error: $error", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(notifications) { notification ->
                    ListItem(
                        headlineContent = { Text(notification.title) },
                        supportingContent = { Text(notification.body) }
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
            error = null
        )
    }
}
