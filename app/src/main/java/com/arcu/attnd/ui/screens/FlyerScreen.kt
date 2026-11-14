package com.arcu.attnd.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arcu.attnd.domain.ImageCachePlan
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.RemoteImage

/** The event flyer: an image shown inline (cached for offline use), or a PDF opened in the system viewer (online only). */
@Composable
fun FlyerScreen(flyerUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    when {
        flyerUrl == null -> EmptyState("No flyer has been posted for this event.", modifier)
        ImageCachePlan.isPdf(flyerUrl) -> {
            Column(
                modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("The flyer is a PDF.")
                Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, flyerUrl.toUri())) }) {
                    Text("Open flyer")
                }
            }
        }
        else -> RemoteImage(
            url = flyerUrl,
            contentDescription = "Event flyer",
            contentScale = ContentScale.Fit,
            maxDimension = 2048,
            modifier = modifier.fillMaxSize(),
            placeholder = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            },
        )
    }
}
