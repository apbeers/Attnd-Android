package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.NetworkModule
import com.arcu.attnd.ui.theme.AttndTheme
import kotlinx.coroutines.launch

@Composable
fun OptionsScreen(
    eventId: Int,
    deviceId: String,
    onLeaveEvent: () -> Unit
) {
    var pushEnabled by remember { mutableStateOf(true) }
    var locationEnabled by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Options", style = MaterialTheme.typography.headlineMedium)
        
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Push Notifications") },
            supportingContent = { Text("Receive updates and alerts") },
            trailingContent = {
                Switch(
                    checked = pushEnabled,
                    onCheckedChange = { checked ->
                        pushEnabled = checked
                        scope.launch {
                            try {
                                if (checked) {
                                    NetworkModule.api.subscribePush(deviceId)
                                } else {
                                    NetworkModule.api.unsubscribePush(deviceId)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                )
            }
        )

        ListItem(
            headlineContent = { Text("Location Access") },
            supportingContent = { Text("Used to show your position on the map") },
            trailingContent = {
                Switch(
                    checked = locationEnabled,
                    onCheckedChange = { locationEnabled = it }
                )
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                scope.launch {
                    try {
                        NetworkModule.api.leaveEvent(eventId, deviceId)
                    } catch (_: Exception) {}
                    onLeaveEvent()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Leave Event")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OptionsScreenPreview() {
    AttndTheme {
        OptionsScreen(eventId = 1, deviceId = "test", onLeaveEvent = {})
    }
}
