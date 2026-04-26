package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LaunchScreen(
    onEventSelected: (Int) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }

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
            onClick = { /* TODO: Use location to find nearby */ },
            modifier = Modifier.fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = { /* TODO: API Lookup */ onEventSelected(1) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Event")
        }
    }
}
