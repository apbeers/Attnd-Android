package com.arcu.attnd.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.Event
import com.arcu.attnd.domain.EventSearch

/**
 * The event header (Options, the event info screen): name, dates, address, code, description, and a
 * **Website** button when the event has an Attnd Studio site (API-016) and [showWebsite].
 */
@Composable
fun EventInfoCard(event: Event, modifier: Modifier = Modifier, showWebsite: Boolean = true, showDescription: Boolean = true) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.name, style = MaterialTheme.typography.titleLarge)
            EventSearch.eventDates(event)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.address.isNotBlank()) {
                Text(event.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.eventCode.isNotBlank()) {
                Text("Event code ${event.eventCode}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (showDescription && event.description.isNotBlank()) {
                Text(event.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (showWebsite) event.websiteUrl?.let { url -> EventWebsiteButton(url, Modifier.padding(top = 8.dp)) }
        }
    }
}

/**
 * API-016 **Website**: the system browser (or the user's Custom Tab default), never a WebView: nothing
 * from the app (device id, tokens, cookies) goes to the site, which is on another domain.
 */
@Composable
fun EventWebsiteButton(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AppLog.caught(LogCategory.UI, "event_website", e)
            }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Website")
    }
}
