package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.Event
import com.arcu.attnd.data.JoinRequest
import com.arcu.attnd.data.NetworkModule
import com.arcu.attnd.ui.theme.AttndTheme
import kotlinx.coroutines.launch

@Composable
fun LaunchScreen(
    deviceId: String,
    onEventJoined: (Event) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Welcome to Attnd", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { 
                errorMessage = "Location search coming soon. Use an event code."
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching
        ) {
            Text("Find Nearby Events")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("OR")
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = eventCode,
            onValueChange = { eventCode = it },
            label = { Text("Enter Event Code") },
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null,
            enabled = !isSearching
        )
        
        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                if (eventCode.isBlank()) return@Button
                isSearching = true
                errorMessage = null
                scope.launch {
                    try {
                        val event = NetworkModule.api.lookupEvent(eventCode)
                        // Join the event as per API.md
                        NetworkModule.api.joinEvent(event.id, deviceId, JoinRequest())
                        onEventJoined(event)
                    } catch (e: Exception) {
                        errorMessage = "Event not found or network error: ${e.message}"
                    } finally {
                        isSearching = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching && eventCode.isNotBlank()
        ) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Join Event")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LaunchScreenPreview() {
    AttndTheme {
        LaunchScreen(deviceId = "test-id", onEventJoined = {})
    }
}
