package com.arcu.attnd.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.data.NetworkModule
import com.arcu.attnd.data.ScheduledItem
import com.arcu.attnd.ui.theme.AttndTheme
import com.arcu.attnd.util.FormatUtils

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

    LocationDetailContent(
        location = location,
        isLoading = isLoading,
        error = error
    )
}

@Composable
fun LocationDetailContent(
    location: EventLocation?,
    isLoading: Boolean,
    error: String?
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Text(
                "Error loading details: $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            location?.let { loc ->
                // Hero image
                if (loc.imageUrl != null) {
                    AsyncImage(
                        model = loc.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Crop
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(loc.name, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (loc.address.isNotBlank()) {
                        Text(loc.address, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Walking directions button
                    if (loc.latitude != 0.0 || loc.longitude != 0.0) {
                        Button(
                            onClick = {
                                val gmmUri = Uri.parse(
                                    "google.navigation:q=${loc.latitude},${loc.longitude}&mode=w"
                                )
                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmUri).apply {
                                    setPackage("com.google.android.apps.maps")
                                }
                                if (mapIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(mapIntent)
                                } else {
                                    val webUri = Uri.parse(
                                        "https://maps.google.com/maps?daddr=${loc.latitude},${loc.longitude}&dirflg=w"
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.DirectionsWalk,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Get Walking Directions")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(loc.description, style = MaterialTheme.typography.bodyLarge)

                    loc.website?.let { url ->
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }
                        ) {
                            Text("Visit Website")
                        }
                    }

                    // Scheduled items at this location
                    if (loc.scheduledItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Schedule", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        loc.scheduledItems.forEach { item ->
                            ScheduleItemInDetail(item)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleItemInDetail(item: ScheduledItem) {
    val countdown = remember(item.startTime) { FormatUtils.startsInText(item.startTime) }
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = FormatUtils.formatScheduleTime(item.startTime),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (countdown != null) {
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (item.description.isNotBlank()) {
            Text(
                item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val dummyLocation = EventLocation(
        1, "Main Stage", "Live Music",
        "The big stage where all the magic happens.",
        "https://example.com", null, 0.0, 0.0, "123 Main St"
    )
    AttndTheme {
        LocationDetailContent(
            location = dummyLocation,
            isLoading = false,
            error = null
        )
    }
}
