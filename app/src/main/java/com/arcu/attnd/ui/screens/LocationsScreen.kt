package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LocationsScreen(eventId: Int, onLocationClick: (Int) -> Unit) {
    // Dummy data for now
    val locations = listOf("Main Stage", "Food Court", "Entrance")

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(locations) { location ->
            ListItem(
                headlineContent = { Text(location) },
                supportingContent = { Text("Click for details") },
                modifier = Modifier.clickable { onLocationClick(1) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun LocationDetailScreen(locationId: Int) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Location Detail", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Details about location $locationId would go here.")
    }
}
