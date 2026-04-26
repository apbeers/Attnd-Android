package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun FlyerScreen(flyerUrl: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (flyerUrl != null) {
            AsyncImage(
                model = flyerUrl,
                contentDescription = "Event Flyer",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("No Flyer Available")
        }
    }
}
