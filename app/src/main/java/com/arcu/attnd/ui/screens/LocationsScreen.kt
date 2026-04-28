package com.arcu.attnd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.data.NetworkModule
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
                        leadingContent = {
                            if (location.imageUrl != null) {
                                AsyncImage(
                                    model = location.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onLocationClick(location.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun LocationDetailScreen(eventId: Int, locationId: Int) {
    var location by remember { mutableStateOf<EventLocation?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(locationId) {
        try {
            location = NetworkModule.api.getLocationDetails(eventId, locationId)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        if (isLoading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text("Error loading details: $error", color = MaterialTheme.colorScheme.error)
        } else {
            location?.let {
                if (it.imageUrl != null) {
                    AsyncImage(
                        model = it.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(it.name, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(it.address, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(it.description, style = MaterialTheme.typography.bodyLarge)
                
                it.website?.let { url ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Website: $url", color = MaterialTheme.colorScheme.primary)
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
        EventLocation(2, "Food Court", "Tacos & More", "Eat here", null, null, 0.0, 0.0, "123 Main St")
    )
    AttndTheme {
        LocationsScreen(dummyLocations, false, null) {}
    }
}

@Preview(showBackground = true)
@Composable
fun LocationDetailScreenPreview() {
    AttndTheme {
        LocationDetailScreen(eventId = 1, locationId = 42)
    }
}
