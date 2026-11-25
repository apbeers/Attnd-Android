package com.arcu.attnd.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.PlaceEvents
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import java.time.Instant

/**
 * A place's "3 events ⌄" disclosure on the Places list (iOS `PlaceEvents`): the place's upcoming items
 * inline (time in the event's zone, name, relative time), or "2 ended", greyed, when nothing is ahead;
 * nothing for a place without items. A tapped item opens through [onItem] (when given).
 */
@Composable
fun PlaceEventsDisclosure(bundle: EventBundle, locationId: Int, now: Instant, onItem: ((Int) -> Unit)?) {
    val events = remember(bundle.schedule, locationId, now) { PlaceEvents.make(locationId, bundle.schedule, now) } ?: return
    var open by rememberSaveable(locationId) { mutableStateOf(false) }
    val zone = bundle.event.zone
    Column(Modifier.animateContentSize()) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
            Text(events.toggleTitle, style = MaterialTheme.typography.labelLarge)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (open) {
            events.items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (onItem != null) Modifier.clickable { onItem(item.id) } else Modifier)
                        .padding(vertical = 4.dp)
                        .alpha(if (events.allEnded) 0.55f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.startTime?.let { EventTime.formatTime(it, zone) } ?: ScheduleGrouping.TIME_TBA_TITLE,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 64.dp),
                    )
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!events.allEnded) {
                        RelativeTime.forItem(item, now, zone)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
