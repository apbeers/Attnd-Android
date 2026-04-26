package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.ui.theme.AttndTheme

@Composable
fun LocationsScreen(
    locations: List<EventLocation>,
    isLoading: Boolean,
    error: String?,
    onLocationClick: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
            Text(text = "Error: $error", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(locations) { location ->
                    ListItem(
                        headlineContent = { Text(location.name) },
                        supportingContent = { Text(location.snippet) },
                        modifier = Modifier.clickable { onLocationClick(location.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LocationsScreenPreview() {
    val dummyLocations = listOf(
        EventLocation(1, "Main Stage", "Live Music", "The big stage", null, null, 0.0, 0.0, "123 Main St"),
        EventLocation(2, "Food Court", "Tacos & More", "Eat here", null, null, 0.0, 0.0, "123 Main St"),
        EventLocation(3, "Entrance", "Tickets & Info", "Start here", null, null, 0.0, 0.0, "123 Main St")
    )
    AttndTheme {
        LocationsScreen(
            locations = dummyLocations,
            isLoading = false,
            error = null,
            onLocationClick = {}
        )
    }
}

@Composable
fun LocationDetailScreen(locationId: Int) {
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(locationId) {
        isLoading = false
    }

    Column(modifier = Modifier.padding(16.dp)) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Text("Location Detail", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Details about location $locationId would be fetched here.")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LocationDetailScreenPreview() {
    AttndTheme {
        LocationDetailScreen(locationId = 42)
    }
}
