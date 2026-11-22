package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.ItemTiming
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow

/**
 * The Home tab (iOS `HomeTab`): first and the default outside screenshot mode, no top app bar (the
 * gear sits on its header row when Home is the Options tab).
 *
 * **Temporary (phase 4):** a fixed list of today's essentials — the header, the newest update, My
 * plan, what's on now and up next. Phase 5b replaces it with the `home` layout's sections drawn by
 * the component renderer.
 */
@Composable
fun HomeScreen(
    bundle: EventBundle,
    plan: Plan?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    showOptions: Boolean,
    onOptions: () -> Unit,
    onOpenPlan: () -> Unit,
    onScheduleItem: (Int) -> Unit,
    onNotification: (Int) -> Unit,
) {
    val now = rememberNow()
    val timed = remember(bundle, now) {
        bundle.schedule.mapNotNull { item -> EventSearch.timing(item, now)?.let { item to it } }
            .sortedWith(compareBy({ it.first.startTime?.toInstant() }, { it.first.id }))
    }
    val happening = timed.filter { it.second == ItemTiming.NOW }.map { it.first }.take(5)
    val next = timed.filter { it.second == ItemTiming.NEXT }.map { it.first }.take(3)
    val latest = remember(bundle) {
        bundle.notifications.filter { it.sendTime?.toInstant()?.isAfter(now) == false }.maxByOrNull { it.sendTime!!.toInstant() }
    }
    RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        ) {
            item(key = "header") { HomeHeader(bundle, showOptions, onOptions) }
            latest?.let { update ->
                item(key = "update") {
                    ElevatedCard(onClick = { onNotification(update.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    if (update.isUrgent) Icons.Filled.Warning else Icons.Filled.Campaign,
                                    contentDescription = if (update.isUrgent) "Urgent" else null,
                                    tint = if (update.isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            },
                            supportingContent = { if (update.body.isNotBlank()) Text(update.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        ) { Text(update.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item(key = "plan") {
                ElevatedCard(onClick = onOpenPlan, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        supportingContent = {
                            Text(plan?.takeIf { !it.isEmpty }?.let { Planner.summary(it, bundle.event.zone) } ?: "Plan your day from what you like")
                        },
                    ) { Text("My plan") }
                }
            }
            if (happening.isNotEmpty()) {
                item(key = "now-title") { SectionHeader("Happening now") }
                items(happening, key = { "now-${it.id}" }) { item -> ItemRow(bundle, item, onScheduleItem) }
            }
            if (next.isNotEmpty()) {
                item(key = "next-title") { SectionHeader("Up next") }
                items(next, key = { "next-${it.id}" }) { item -> ItemRow(bundle, item, onScheduleItem) }
            }
        }
    }
}

@Composable
private fun HomeHeader(bundle: EventBundle, showOptions: Boolean, onOptions: () -> Unit) {
    val logoUrl = bundle.display.logoUrl
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (logoUrl != null) {
            Box(Modifier.padding(end = 12.dp)) {
                RemoteImage(url = logoUrl, contentDescription = null, maxDimension = 128, modifier = Modifier.size(40.dp).clip(CircleShape), placeholder = {})
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(bundle.event.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(EventSearch.eventDates(bundle.event), bundle.event.address.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showOptions) IconButton(onClick = onOptions) { Icon(Icons.Filled.Settings, contentDescription = "Options") }
    }
}

@Composable
private fun ItemRow(bundle: EventBundle, item: ScheduleItem, onClick: (Int) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onClick(item.id) },
        supportingContent = { Text(EventSearch.scheduleDetail(item, bundle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
