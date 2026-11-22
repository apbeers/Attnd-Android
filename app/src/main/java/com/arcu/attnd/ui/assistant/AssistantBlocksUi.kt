@file:OptIn(ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.assistant

import com.arcu.attnd.ui.theme.themedElevatedCardColors
import com.arcu.attnd.ui.theme.themedProgressColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.BlockData
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.CompareSide
import com.arcu.attnd.domain.DayRoutes
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.ItemTiming
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanDiff
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SearchResult
import com.arcu.attnd.domain.Tab
import com.arcu.attnd.domain.Walking
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LiveDot
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.WalkTime
import com.arcu.attnd.ui.components.rememberDistanceUnits
import com.arcu.attnd.ui.components.rememberNow
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What the assistant's components can do, provided once by the assistant screen (and the plan
 * screens that reuse them), so every block doesn't thread a dozen callbacks.
 */
@Immutable
class BlockActions(
    val bundle: EventBundle,
    val plan: Plan?,
    val hearts: Hearts,
    val onOpen: (EntityRef) -> Unit = {},
    val onChip: (Chip) -> Unit = {},
    val onOpenTab: (Tab, EntityRef?) -> Unit = { _, _ -> },
    val onShowOnMap: (Int?) -> Unit = {},
    val onDirections: (Int) -> Unit = {},
    val onShare: (String, String) -> Unit = { _, _ -> },
    val shareLink: (String) -> String? = { null },
    val onSetting: (ActionName, Boolean) -> Unit = { _, _ -> },
    val settingOn: (ActionName) -> Boolean = { false },
    val onCheck: (Int) -> Unit = {},
    val onUseVersion: (String) -> Unit = {},
    val onOpenFlyer: () -> Unit = {},
    val onOpenPlan: () -> Unit = {},
) {
    val tabs: List<Tab> get() = bundle.display.tabs
}

val LocalBlockActions = compositionLocalOf<BlockActions?> { null }

/** The state a turn gives its blocks (Undo / Apply, answered confirmations). */
class TurnControls(
    val canUndo: Boolean = false,
    val undone: Boolean = false,
    val onUndo: () -> Unit = {},
    val onApply: () -> Unit = {},
    val answered: Set<Int> = emptySet(),
    val onConfirm: (Int, Boolean) -> Unit = { _, _ -> },
)

/** One response block (assistant spec §2), as a native Material 3 component. */
@Composable
fun AssistantBlockView(block: AssistantBlock, index: Int, controls: TurnControls, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    when (block) {
        is AssistantBlock.Text -> BlockText(block.text, modifier)
        is AssistantBlock.ItemCard -> bundle.scheduleItem(block.id)?.let { ItemCard(it, block.show, modifier) }
        is AssistantBlock.PlaceCard -> PlaceCard(block.id, block.show, modifier)
        is AssistantBlock.UpdateCard -> UpdateCard(block.id, modifier)
        is AssistantBlock.ListBlock -> EntityList(block.ids, block.title, block.limit, modifier)
        is AssistantBlock.Carousel -> Carousel(block.ids, block.title, modifier)
        is AssistantBlock.Timeline -> {
            val now = rememberNow()
            val ids = remember(block, actions.plan, now) { BlockData.timeline(block, bundle, actions.plan, now) }
            Timeline(ids, block.title ?: if (block.source == "plan") "My plan" else null, modifier)
        }
        is AssistantBlock.MapBlock -> MapBlock(block, modifier)
        is AssistantBlock.Gallery -> Gallery(block, modifier)
        is AssistantBlock.NowNext -> NowNextStrip(block.location, modifier)
        is AssistantBlock.Countdown -> bundle.scheduleItem(block.id)?.let { Countdown(it, modifier) }
        is AssistantBlock.Compare -> CompareBlock(block, modifier)
        is AssistantBlock.PlanDiffBlock -> PlanDiffCard(block.diff, controls, modifier)
        is AssistantBlock.PlanBlock -> actions.plan?.let { PlanTimeline(it, block.day, modifier) }
        is AssistantBlock.Setting -> SettingCard(block.setting, modifier)
        is AssistantBlock.Summary -> SummaryCard(block.kind, modifier)
        is AssistantBlock.Share -> ShareCard(block.target, modifier)
        is AssistantBlock.Chips -> ChipRow(block.chips.filter { !(controls.canUndo && it.action.name == ActionName.PLAN_UNDO) }, modifier)
        is AssistantBlock.Confirm -> ConfirmCard(block, answered = index in controls.answered, onAnswer = { controls.onConfirm(index, it) }, modifier = modifier)
        // Screen components (menu, FAQ, My schedule…) get their renderer in phase 5b; nothing produces them yet.
        is AssistantBlock.ComponentBlock -> Unit
    }
}

// ── Text ────────────────────────────────────────────────────────────────────

/** "**bold**" spans as bold; everything else plain. */
fun boldText(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1 && i < parts.lastIndex) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(if (i % 2 == 1 && i == parts.lastIndex) "**$part" else part)
        if (i % 2 == 1 && i < parts.lastIndex) pop()
    }
}

@Composable
fun BlockText(text: String, modifier: Modifier = Modifier) {
    Text(boldText(text), style = MaterialTheme.typography.bodyLarge, modifier = modifier.fillMaxWidth())
}

@Composable
private fun BlockTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

// ── Cards ───────────────────────────────────────────────────────────────────

/** One schedule item, rich: time + relative time, place, image, distance + walk, like, actions. */
@Composable
fun ItemCard(item: ScheduleItem, show: List<String>, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val zone = bundle.event.zone
    val now = rememberNow()
    val location = bundle.location(item.locationId)
    val ref = EntityRef(RefKind.SCHEDULE, item.id)
    ElevatedCard(onClick = { actions.onOpen(ref) }, modifier = modifier.fillMaxWidth(), colors = themedElevatedCardColors()) {
        if ("image" in show && bundle.display.showLocationImages && location?.imageUrl != null) {
            RemoteImage(location.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(140.dp))
        }
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if ("time" in show) {
                        Text(
                            if (item.startTime != null) EventTime.formatDayTime(item.startTime, zone) else ScheduleGrouping.TIME_TBA_TITLE,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if ("like" in show) HeartButton(SearchKind.SCHEDULE, item.id, name = item.name)
            }
            if ("time" in show) {
                RelativeTime.forItem(item, now, zone, showEnd = true)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (it == RelativeTime.ENDED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                }
                Glance.progress(item, now)?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp), color = themedProgressColor) }
            }
            if ("place" in show) {
                Text(location?.name ?: item.locationName ?: ScheduleGrouping.GENERAL_TITLE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if ("distance" in show && location?.coordinate != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DistanceArrow(location.coordinate)
                    WalkTime(location.coordinate)
                }
            }
            if ("actions" in show) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
                    if (Tab.SCHEDULE in actions.tabs) {
                        OutlinedButton(onClick = { actions.onOpenTab(Tab.SCHEDULE, ref) }, contentPadding = SmallButton) { Text("Open in Schedule") }
                    }
                    if (Tab.MAP in actions.tabs && location?.coordinate != null) {
                        OutlinedButton(onClick = { actions.onShowOnMap(location.id) }, contentPadding = SmallButton) { Text("Show on map") }
                    }
                }
            }
        }
    }
}

private val SmallButton = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/** One location, rich: image, category, now / next there, distance, like, actions. */
@Composable
fun PlaceCard(id: Int, show: List<String>, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val location = bundle.location(id) ?: return
    val now = rememberNow()
    ElevatedCard(onClick = { actions.onOpen(EntityRef(RefKind.LOCATION, id)) }, modifier = modifier.fillMaxWidth(), colors = themedElevatedCardColors()) {
        if ("image" in show && bundle.display.showLocationImages && location.imageUrl != null) {
            RemoteImage(location.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(140.dp))
        }
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    location.category?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    Text(location.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if ("like" in show) HeartButton(SearchKind.LOCATION, id, name = location.name)
            }
            if (location.snippet.isNotBlank()) Text(location.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if ("now_next" in show) {
                Glance.locationLine(bundle, id, now)?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (Glance.isLive(bundle, id, now)) LiveDot()
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if ("distance" in show && location.coordinate != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DistanceArrow(location.coordinate)
                    WalkTime(location.coordinate)
                }
            }
            if ("actions" in show && location.coordinate != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
                    if (Tab.MAP in actions.tabs) OutlinedButton(onClick = { actions.onShowOnMap(id) }, contentPadding = SmallButton) { Text("Show on map") }
                    OutlinedButton(onClick = { actions.onDirections(id) }, contentPadding = SmallButton) { Text("Directions") }
                }
            }
        }
    }
}

/** An organizer update (API-011: urgent ones in the error colours). */
@Composable
fun UpdateCard(id: Int, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val update = bundle.notifications.firstOrNull { it.id == id } ?: return
    val urgent = update.isUrgent
    Card(
        onClick = { actions.onOpen(EntityRef(RefKind.UPDATE, id)) },
        colors = CardDefaults.cardColors(
            containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (urgent) Icons.Filled.Warning else Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    listOfNotNull(if (urgent) "Urgent" else null, update.sendTime?.let { EventTime.formatDateTime(it, bundle.event.zone) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (urgent) FontWeight.Bold else null,
                )
            }
            Text(update.title, style = MaterialTheme.typography.titleMedium)
            if (update.body.isNotBlank()) Text(update.body, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Lists ───────────────────────────────────────────────────────────────────

private fun result(ref: EntityRef, bundle: EventBundle, now: Instant): SearchResult? = when (ref.kind) {
    RefKind.SCHEDULE -> bundle.scheduleItem(ref.id)?.let { SearchResult(SearchKind.SCHEDULE, it.id, it.name, emptyList(), 0.0, EventSearch.timing(it, now)) }
    RefKind.LOCATION -> bundle.location(ref.id)?.let { SearchResult(SearchKind.LOCATION, it.id, it.name, emptyList(), 0.0) }
    RefKind.UPDATE -> bundle.notifications.firstOrNull { it.id == ref.id }?.let { SearchResult(SearchKind.NOTIFICATION, it.id, it.title, emptyList(), 0.0) }
}

private val RefKind.icon: ImageVector
    get() = when (this) {
        RefKind.SCHEDULE -> Icons.Filled.CalendarMonth
        RefKind.LOCATION -> Icons.Filled.Place
        RefKind.UPDATE -> Icons.Filled.Notifications
    }

/** A compact row for one entity: kind icon, name, one detail line, Now/Next/Ended, distance, like. */
@Composable
fun EntityRow(ref: EntityRef, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val now = rememberNow()
    val result = remember(ref, bundle, now) { result(ref, bundle, now) } ?: return
    val detail = remember(result, bundle) { EventSearch.detail(result, bundle) }
    Row(
        modifier.fillMaxWidth().clickable { actions.onOpen(ref) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(ref.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        result.timing?.let {
            Text(it.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (it == ItemTiming.ENDED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
        }
        if (ref.kind == RefKind.LOCATION) DistanceArrow(bundle.location(ref.id)?.coordinate)
        if (ref.kind != RefKind.UPDATE) HeartButton(ref.kind.searchKind, ref.id, name = result.title)
    }
}

@Composable
fun EntityList(ids: List<EntityRef>, title: String?, limit: Int?, modifier: Modifier = Modifier) {
    var all by rememberSaveable(ids) { mutableStateOf(false) }
    val shown = if (all || limit == null) ids else ids.take(limit)
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            title?.let { BlockTitle(it) }
            shown.forEachIndexed { i, ref ->
                if (i > 0) HorizontalDivider()
                EntityRow(ref)
            }
            if (shown.size < ids.size) TextButton(onClick = { all = true }) { Text("Show all ${ids.size}") }
        }
    }
}

/** 2–8 entities as horizontal cards. */
@Composable
fun Carousel(ids: List<EntityRef>, title: String?, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val now = rememberNow()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { BlockTitle(it) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ids, key = { it.code }) { ref ->
                ElevatedCard(onClick = { actions.onOpen(ref) }, modifier = Modifier.width(220.dp), colors = themedElevatedCardColors()) {
                    val location = when (ref.kind) {
                        RefKind.LOCATION -> bundle.location(ref.id)
                        RefKind.SCHEDULE -> bundle.location(bundle.scheduleItem(ref.id)?.locationId)
                        RefKind.UPDATE -> null
                    }
                    if (bundle.display.showLocationImages && ref.kind == RefKind.LOCATION && location?.imageUrl != null) {
                        RemoteImage(location.imageUrl, contentDescription = null, maxDimension = 512, modifier = Modifier.fillMaxWidth().height(100.dp))
                    }
                    Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val result = result(ref, bundle, now)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(result?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (ref.kind != RefKind.UPDATE) HeartButton(ref.kind.searchKind, ref.id, name = result?.title)
                        }
                        if (result != null) {
                            Text(EventSearch.detail(result, bundle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
                            DistanceArrow(location?.coordinate)
                            if (ref.kind == RefKind.SCHEDULE) WalkTime(location?.coordinate)
                        }
                    }
                }
            }
        }
    }
}

// ── Time ────────────────────────────────────────────────────────────────────

/** Time-ordered schedule items with a "Now" line and the walk between consecutive places. */
@Composable
fun Timeline(ids: List<Int>, title: String?, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val zone = bundle.event.zone
    val now = rememberNow()
    val items = ids.mapNotNull { bundle.scheduleItem(it) }
    if (items.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            title?.let { BlockTitle(it) }
            var nowShown = false
            items.forEachIndexed { i, item ->
                val start = item.startTime?.toInstant()
                if (!nowShown && start != null && start.isAfter(now) && i > 0) {
                    nowShown = true
                    NowLine()
                }
                if (start != null && start.isAfter(now)) nowShown = true
                val previous = items.getOrNull(i - 1)
                val walk = previous?.let { walkMinutes(bundle, it, item) }
                if (walk != null && walk > 0) WalkLegRow("$walk min walk")
                TimelineRow(item, zone, now)
            }
        }
    }
}

private fun walkMinutes(bundle: EventBundle, a: ScheduleItem, b: ScheduleItem): Int? {
    if (a.locationId == b.locationId) return null
    val from = bundle.location(a.locationId)?.coordinate ?: return null
    val to = bundle.location(b.locationId)?.coordinate ?: return null
    return Walking.minutes(Walking.estimate(from, to).durationSeconds).toInt()
}

@Composable
private fun NowLine() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = "Now" }) {
        Text("Now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.error, thickness = 2.dp)
    }
}

@Composable
fun WalkLegRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimelineRow(item: ScheduleItem, zone: java.time.ZoneId, now: Instant, checked: Boolean? = null, reason: String? = null) {
    val actions = LocalBlockActions.current ?: return
    val ended = ScheduleGrouping.hasEnded(item, now)
    Row(
        Modifier.fillMaxWidth().clickable { actions.onOpen(EntityRef(RefKind.SCHEDULE, item.id)) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            EventTime.formatTime(item.startTime, zone),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(76.dp),
            color = if (ended) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ScheduleGrouping.isHappeningNow(item, now)) LiveDot()
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (ended) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (checked == true) TextDecoration.LineThrough else null,
                )
            }
            Text(
                listOfNotNull(actions.bundle.location(item.locationId)?.name ?: item.locationName, reason).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (checked != null && item.startTime?.toInstant()?.isAfter(now) == false) {
            IconToggleButton(checked = checked, onCheckedChange = { actions.onCheck(item.id) }) {
                Icon(
                    if (checked) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (checked) "Seen ${item.name}" else "Mark ${item.name} as seen",
                    tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HeartButton(SearchKind.SCHEDULE, item.id, name = item.name)
    }
}

/** Counts down to an item's start ("in 12 min", ticking). */
@Composable
fun Countdown(item: ScheduleItem, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val now = rememberNow(1_000L)
    val zone = actions.bundle.event.zone
    val start = item.startTime?.toInstant() ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Timer, contentDescription = null)
            Column {
                Text(RelativeTime.relativeStart(start, item.endTime?.toInstant(), now, zone), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${item.name} · ${EventTime.formatDayTime(item.startTime, zone)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** What's on now and next, at a location or everywhere. */
@Composable
fun NowNextStrip(locationId: Int?, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val now = rememberNow()
    if (locationId != null) {
        val line = Glance.locationLine(bundle, locationId, now) ?: "Nothing on at ${bundle.location(locationId)?.name.orEmpty()} right now."
        Card(modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(bundle.location(locationId)?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val playing = remember(bundle, now) { Glance.nowPlaying(bundle, now) }
    val next = remember(bundle, now) {
        bundle.schedule.filter { it.startTime?.toInstant()?.isAfter(now) == true }.sortedWith(Planner.ITEM_ORDER).take(3)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (playing.isNotEmpty()) {
            BlockTitle(ScheduleGrouping.NOW_TITLE)
            playing.forEach { EntityRow(EntityRef(RefKind.SCHEDULE, it.item.id)) }
        }
        if (next.isNotEmpty()) {
            BlockTitle(ScheduleGrouping.NEXT_TITLE)
            next.forEach { EntityRow(EntityRef(RefKind.SCHEDULE, it.id)) }
        }
    }
}

// ── Places ──────────────────────────────────────────────────────────────────

@Composable
fun MapBlock(block: AssistantBlock.MapBlock, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val routeIds = block.route
    val numbered = routeIds.size >= 2
    val pins = remember(block, bundle) {
        block.pins.mapNotNull { id ->
            val location = bundle.location(id) ?: return@mapNotNull null
            val point = location.coordinate ?: return@mapNotNull null
            MiniPin(if (numbered) routeIds.indexOf(id).takeIf { it >= 0 }?.plus(1) else null, id, point, location.name)
        }
    }
    val legs = remember(block, bundle) { straightLegs(routeIds.mapNotNull { bundle.location(it)?.coordinate }) }
    val route = rememberServiceRoute(
        remember(block, bundle) {
            val stops = routeIds.mapIndexedNotNull { i, id ->
                bundle.location(id)?.coordinate?.let { com.arcu.attnd.domain.RouteStop(i + 1, id, it, emptyList()) }
            }
            if (stops.size < 2) null else com.arcu.attnd.domain.DayRoute(
                java.time.LocalDate.MIN, stops,
                stops.zipWithNext { a, b -> com.arcu.attnd.domain.RouteLeg(a, b, 0, 0, com.arcu.attnd.domain.Geo.distanceMeters(a.point, b.point), Walking.estimate(a.point, b.point)) },
            )
        },
        key = "${bundle.event.id}:map:" + routeIds.joinToString("-"),
    )
    MiniMap(pins, route?.legs?.map { it.route } ?: legs, onClick = { actions.onShowOnMap(block.pins.firstOrNull()) }, modifier = modifier)
}

@Composable
fun Gallery(block: AssistantBlock.Gallery, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (block.flyer) {
            item(key = "flyer") {
                val url = bundle.event.flyerUrl
                ElevatedCard(onClick = actions.onOpenFlyer, modifier = Modifier.width(160.dp).height(200.dp)) {
                    if (url != null && !url.lowercase().substringBefore('?').endsWith(".pdf")) {
                        RemoteImage(url, contentDescription = "Flyer", modifier = Modifier.fillMaxWidth().height(200.dp))
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Description, contentDescription = null)
                            Text("Open the flyer", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        items(block.ids, key = { it }) { id ->
            val location = bundle.location(id) ?: return@items
            ElevatedCard(onClick = { actions.onOpen(EntityRef(RefKind.LOCATION, id)) }, modifier = Modifier.width(200.dp)) {
                RemoteImage(location.imageUrl, contentDescription = location.name, maxDimension = 512, modifier = Modifier.fillMaxWidth().height(150.dp))
                Text(location.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Plans ───────────────────────────────────────────────────────────────────

/** − / + rows of a plan change, with Undo (or Apply after an Undo). */
@Composable
fun PlanDiffCard(diff: PlanDiff, controls: TurnControls, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val zone = bundle.event.zone
    fun name(id: Int) = bundle.scheduleItem(id)?.name ?: "An item"
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockTitle("Plan changes")
            if (diff.isEmpty) Text("Nothing changed.", style = MaterialTheme.typography.bodyMedium)
            diff.removed.forEach { DiffRow(Icons.Filled.Remove, name(it), MaterialTheme.colorScheme.error, struck = true, label = "Removed") }
            diff.added.forEach { id ->
                val time = bundle.scheduleItem(id)?.startTime?.let { EventTime.formatDayTime(it, zone) }
                DiffRow(Icons.Filled.Add, listOfNotNull(name(id), time).joinToString(" · "), MaterialTheme.colorScheme.primary, label = "Added")
            }
            diff.moved.forEach { (a, b) ->
                val time = bundle.scheduleItem(b)?.startTime?.let { EventTime.formatDayTime(it, zone) }.orEmpty()
                DiffRow(Icons.Filled.SwapHoriz, "${name(a)} → $time", MaterialTheme.colorScheme.tertiary, label = "Moved")
            }
            diff.mealsRemoved.forEach { DiffRow(Icons.Filled.Restaurant, "No ${it.kind.label.lowercase()}", MaterialTheme.colorScheme.error, struck = false, label = "Removed") }
            diff.mealsAdded.forEach { DiffRow(Icons.Filled.Restaurant, it.kind.label, MaterialTheme.colorScheme.primary, label = "Added") }
            diff.mealsMoved.forEach { DiffRow(Icons.Filled.Restaurant, "${it.kind.label} moved", MaterialTheme.colorScheme.tertiary, label = "Moved") }
            if (controls.canUndo) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (controls.undone) Button(onClick = controls.onApply) { Text("Apply") } else FilledTonalButton(onClick = controls.onUndo) { Text("Undo") }
                    TextButton(onClick = actions.onOpenPlan) { Text("Show my plan") }
                }
            }
        }
    }
}

@Composable
private fun DiffRow(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color, label: String, struck: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, textDecoration = if (struck) TextDecoration.LineThrough else null)
    }
}

/**
 * The plan of one day (default: today's, else the first): entries with walk legs ("6 min walk ·
 * 0.3 mi", real routes once fetched), meal breaks, check-off toggles, the day's walking total and the
 * mini map with numbered stops.
 */
@Composable
fun PlanTimeline(plan: Plan, day: java.time.LocalDate?, modifier: Modifier = Modifier, showMap: Boolean = true) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val zone = bundle.event.zone
    val now = rememberNow()
    val units = rememberDistanceUnits()
    val planDay = remember(plan, day, now) { BlockData.planDay(plan, bundle, now, day) } ?: return
    val estimate = remember(plan, planDay.date) { DayRoutes.of(plan, bundle, planDay.date) }
    val route = rememberServiceRoute(estimate, "${bundle.event.id}:${planDay.date}:" + estimate?.stops?.joinToString("-") { it.locationId.toString() })
    val clock = remember { DateTimeFormatter.ofPattern("h:mm a") }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BlockTitle(EventTime.formatDayHeader(planDay.date))
            var lastItem: Int? = null
            planDay.entries.forEach { entry ->
                when (entry) {
                    is PlannedItem -> {
                        val item = bundle.scheduleItem(entry.itemId)
                        val leg = route?.legs?.firstOrNull { it.toItem == entry.itemId && it.fromItem == lastItem }
                        if (leg != null) WalkLegRow(DayRoutes.legText(leg, units))
                        else if (lastItem != null && (entry.walkMinutes ?: 0) > 0) WalkLegRow("${entry.walkMinutes} min walk")
                        if (item != null) TimelineRow(item, zone, now, checked = entry.itemId in plan.checkedOff, reason = entry.reason)
                        lastItem = entry.itemId
                    }
                    is MealBreak -> {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.start.atZone(zone).format(clock), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
                            Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(entry.kind.label + (bundle.location(entry.locationId)?.name?.let { " · $it nearby" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                        }
                        lastItem = null
                    }
                }
            }
            val shown = route ?: estimate
            if (shown != null && shown.legs.isNotEmpty()) {
                Text(
                    DayRoutes.totalText(shown, now.atZone(zone).toLocalDate(), units),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (showMap && shown != null && shown.stops.isNotEmpty()) {
                MiniMap(routePins(shown, bundle), shown.legs.map { it.route }, onClick = { actions.onShowOnMap(shown.stops.firstOrNull()?.locationId) }, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/** Two items or two plan versions side by side; a version can be used. */
@Composable
fun CompareBlock(block: AssistantBlock.Compare, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val bundle = actions.bundle
    val now = rememberNow()
    val sides = remember(block, actions.plan, actions.hearts) {
        listOf(block.a, block.b).map { BlockData.compare(it, actions.plan, bundle, actions.hearts, now) }
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        sides.forEachIndexed { i, side ->
            OutlinedCard(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (side) {
                        is CompareSide.Item -> {
                            Text(side.item.name, style = MaterialTheme.typography.titleSmall)
                            Text(EventSearch.scheduleDetail(side.item, bundle), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { actions.onOpen(EntityRef(RefKind.SCHEDULE, side.item.id)) }) { Text("Open") }
                        }
                        is CompareSide.Version -> {
                            Text(side.kind.label, style = MaterialTheme.typography.titleSmall)
                            Text(Planner.summary(side.edit.after, bundle.event.zone), style = MaterialTheme.typography.bodySmall)
                            side.edit.after.items.take(6).mapNotNull { bundle.scheduleItem(it.itemId) }.forEach {
                                Text("· ${it.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalButton(onClick = { actions.onUseVersion(side.kind.key) }) { Text("Use this") }
                        }
                        null -> Text(listOf(block.a, block.b)[i])
                    }
                }
            }
        }
    }
}

// ── Settings, summaries, sharing ───────────────────────────────────────────

@Composable
fun SettingCard(setting: String, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val name = ActionName.of(setting) ?: return
    val (title, body, icon) = when (name) {
        ActionName.FOLLOW_LOCK_SCREEN -> Triple("Follow on Lock Screen", "What's now and next in your plan, as a Live Update.", Icons.Filled.Event)
        ActionName.REMINDERS -> Triple("Reminders", "A reminder with the walk time before liked and planned items.", Icons.Filled.Schedule)
        else -> Triple("Notifications", "Announcements from the organizers.", Icons.Filled.Notifications)
    }
    val on = actions.settingOn(name)
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, onCheckedChange = { actions.onSetting(name, it) }, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}

@Composable
fun SummaryCard(kind: String, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val now = rememberNow()
    val units = rememberDistanceUnits()
    val text = remember(kind, actions.plan, actions.hearts, now, units) { BlockData.summary(kind, actions.plan, actions.bundle, actions.hearts, now, units) } ?: return
    val icon = when (kind) {
        "walking" -> Icons.AutoMirrored.Filled.DirectionsWalk
        "liked_counts" -> Icons.Filled.Favorite
        else -> Icons.Filled.Info
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The plan's (or an item's) link: a QR code to scan and Share / Copy. */
@Composable
fun ShareCard(target: String, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val link = remember(target, actions.plan) { actions.shareLink(target) } ?: return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (target == "plan") "Share your plan" else "Share this", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.padding(4.dp)) { QrImage(link, size = 220.dp) }
            Text("Scan to open it in Attnd. Only the items are shared, nothing about you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onShare(target, "share_sheet") }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                OutlinedButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Attnd link", link))) }
                    copied = true
                    actions.onShare(target, "copy")
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Copied" else "Copy link")
                }
            }
        }
    }
}

// ── Chips and confirmations ─────────────────────────────────────────────────

@Composable
fun ChipRow(chips: List<Chip>, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    if (chips.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            AssistChip(
                onClick = { actions.onChip(chip) },
                label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = chipIcon(chip.action.name)?.let { icon -> { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) } },
            )
        }
    }
}

private fun chipIcon(name: ActionName): ImageVector? = when (name) {
    ActionName.LIKE, ActionName.UNLIKE, ActionName.LIKED -> Icons.Filled.Favorite
    ActionName.OPEN -> Icons.Filled.Map
    ActionName.DIRECTIONS -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActionName.SHARE_PLAN, ActionName.SHARE_ITEM -> Icons.Filled.Share
    ActionName.PLAN_EDIT, ActionName.PLAN_VERSION -> Icons.Filled.SwapHoriz
    ActionName.PLAN, ActionName.PLAN_MAKE -> Icons.Filled.Event
    else -> null
}

@Composable
fun ConfirmCard(block: AssistantBlock.Confirm, answered: Boolean, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(block.prompt, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(true) }, enabled = !answered) { Text("Yes") }
                OutlinedButton(onClick = { onAnswer(false) }, enabled = !answered) { Text("No") }
            }
        }
    }
}

/** The OpenInNew icon for "Open in Schedule / on Map" style buttons. */
val OpenIcon: ImageVector get() = Icons.AutoMirrored.Filled.OpenInNew

/** Rounded corners for assistant bubbles. */
val BubbleShape = RoundedCornerShape(20.dp)
