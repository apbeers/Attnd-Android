@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.AboutText
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.ContextFacts
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Tags
import com.arcu.attnd.ui.assistant.AssistantIntents
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.WalkTime
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSections
import com.arcu.attnd.ui.sections.FactsSection
import com.arcu.attnd.ui.sections.LinksSection
import com.arcu.attnd.ui.sections.PerformersSection
import com.arcu.attnd.ui.sections.TextSection
import com.arcu.attnd.ui.theme.themedProgressColor
import java.time.Instant

/**
 * A schedule item's detail (iOS `ScheduleItemDetailView`), drawn from the `item_detail` layout (API-024;
 * the built-in one = today's screen): the header (name + heart, day and time, status with progress, tags,
 * Share), about, performers (API-030 Apple Music), good to know, links, the place and a countdown.
 */
@Composable
fun ScheduleItemDetail(
    bundle: EventBundle,
    item: ScheduleItem,
    onBack: (() -> Unit)?,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val sections = rememberSections(LayoutScreen.ITEM_DETAIL, bundle, now, ScreenFacts.subject(item, bundle)) { b, state ->
        ScreenFacts.item(item, b, state)
    }
    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
        DetailSections(sections, LayoutScreen.ITEM_DETAIL) { section ->
            ItemSection(section, bundle, item, now, onLocationClick)
        }
    }
}

private val Padded = Modifier.padding(horizontal = 16.dp)

@Composable
private fun ItemSection(section: LayoutSection, bundle: EventBundle, item: ScheduleItem, now: Instant, onLocationClick: (Int) -> Unit) {
    val context = item.context
    when (section.type) {
        "header" -> ItemHeader(section, bundle, item, now)
        "about" -> TextSection(section.title ?: "About", AboutText.merged(item.description, context?.about), Padded)
        "performers" -> PerformersSection(context?.performers.orEmpty(), Padded, title = section.title)
        "good_to_know" -> FactsSection(ContextFacts.item(context), Padded, title = section.title)
        "links" -> LinksSection(context?.links.orEmpty(), title = section.title, modifier = Padded)
        "place_link" -> PlaceLink(bundle, item, onLocationClick)
        "countdown" -> ItemCountdown(bundle, item, section.title)
        else -> Unit
    }
}

@Composable
private fun ItemHeader(section: LayoutSection, bundle: EventBundle, item: ScheduleItem, now: Instant) {
    val zone = bundle.event.zone
    Column(Padded.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            HeartButton(SearchKind.SCHEDULE, item.id, name = item.name)
        }
        if (section.shows("time")) {
            item.startTime?.let { start ->
                Text(EventTime.formatDayHeader(EventTime.localDate(start, zone)), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "${EventTime.formatTimeRange(item.startTime, item.endTime, zone)} ${EventTime.zoneLabel(zone, now)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (section.shows("status")) {
            RelativeTime.forItem(item, now, zone, showEnd = true)?.let {
                Text(
                    it,
                    color = if (it == RelativeTime.ENDED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Glance.progress(item, now)?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = themedProgressColor)
            }
        }
        if (section.shows("tags") && item.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(Tags.display(tag)) }) }
            }
        }
        ShareButton(bundle, item)
    }
}

/** "Share" (an API-015 item link). Not in screenshot mode. No "Ask about this" and no "Add to calendar" (removed as on iOS). */
@Composable
private fun ShareButton(bundle: EventBundle, item: ScheduleItem) {
    if (LocalScreenshotMode.current) return
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val link = PlanShare.itemLink(item.id, bundle)
        AppLog.info(
            LogCategory.UI, LogEvent.SHARE_CREATED, "Share link made (item)",
            mapOf("target" to "item", "items" to 1, "payload_len" to link.substringAfterLast('/').length, "via" to "share_sheet"),
        )
        AssistantIntents.share(context, link, item.name)
    }) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Share")
    }
}

/** The item's place (tap → its detail) with distance and walk, or "General". */
@Composable
private fun PlaceLink(bundle: EventBundle, item: ScheduleItem, onLocationClick: (Int) -> Unit) {
    val location = bundle.location(item.locationId)
    Column(Padded, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (location != null) {
            OutlinedButton(onClick = { onLocationClick(location.id) }) {
                Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(location.name)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DistanceArrow(location.coordinate, style = MaterialTheme.typography.labelLarge)
                WalkTime(location.coordinate)
            }
        } else {
            Text(
                item.locationName ?: ScheduleGrouping.GENERAL_TITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Until the item starts: "in 12 min" ticking, with the start in the event's zone. */
@Composable
private fun ItemCountdown(bundle: EventBundle, item: ScheduleItem, title: String?) {
    val now = rememberNow(1_000L)
    val zone = bundle.event.zone
    val start = item.startTime?.toInstant()?.takeIf { it.isAfter(now) } ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Padded.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Timer, contentDescription = null)
            Column {
                Text(title ?: "Starts", style = MaterialTheme.typography.labelMedium)
                Text(RelativeTime.relativeStart(start, item.endTime?.toInstant(), now, zone), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(EventTime.formatDayTime(item.startTime, zone), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
