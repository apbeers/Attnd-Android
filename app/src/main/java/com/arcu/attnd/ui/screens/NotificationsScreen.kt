package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NotificationsScreen(eventId: Int) {
    // Dummy data
    val notifications = listOf("Welcome to the event!", "Gate B is now open")

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(notifications) { notification ->
            ListItem(
                headlineContent = { Text(notification) },
                supportingContent = { Text("Just now") }
            )
            HorizontalDivider()
        }
    }
}
