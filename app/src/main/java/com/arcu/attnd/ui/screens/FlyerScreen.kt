package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.arcu.attnd.ui.theme.AttndTheme
import coil.compose.AsyncImage

@Composable
fun FlyerScreen(flyerUrl: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (flyerUrl != null) {
            AsyncImage(
                model = flyerUrl,
                contentDescription = "Event Flyer",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("No Flyer Available")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FlyerScreenPreview() {
    AttndTheme {
        FlyerScreen(flyerUrl = null)
    }
}
