package com.arcu.attnd.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.AboutText
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.Component
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.HomeHeaderText
import com.arcu.attnd.domain.HomeUrgent
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.MyScheduleRow
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.ScheduleEntryKind
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.ui.assistant.AssistantBlockView
import com.arcu.attnd.ui.assistant.BlockActions
import com.arcu.attnd.ui.assistant.Carousel
import com.arcu.attnd.ui.assistant.EntityList
import com.arcu.attnd.ui.assistant.Gallery
import com.arcu.attnd.ui.assistant.ItemCard
import com.arcu.attnd.ui.assistant.LocalBlockActions
import com.arcu.attnd.ui.assistant.MapBlock
import com.arcu.attnd.ui.assistant.NowNextStrip
import com.arcu.attnd.ui.assistant.PlaceCard
import com.arcu.attnd.ui.assistant.Timeline
import com.arcu.attnd.ui.assistant.TurnControls
import com.arcu.attnd.ui.sections.AmenitiesSection
import com.arcu.attnd.ui.sections.FaqSection
import com.arcu.attnd.ui.sections.HoursSection
import com.arcu.attnd.ui.sections.LinksSection
import com.arcu.attnd.ui.sections.MenuSection
import com.arcu.attnd.ui.sections.PerformersSection
import com.arcu.attnd.ui.sections.SectionCard
import com.arcu.attnd.ui.sections.SectionTitle
import com.arcu.attnd.ui.sections.TextSection
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The one renderer (iOS `ComponentView` + `ScreenComponentView`): every component of the catalogue
 * (`domain/Component.kt`) — Home's sections, the same components in chat answers, and the chat's own
 * blocks — drawn by [ComponentView]. Screen components are drawn in the Home tab's style (the iOS
 * reference) and reuse the assistant's cards (`AssistantBlocksUi.kt`, through [LocalBlockActions]);
 * chat blocks go to [AssistantBlockView]. The detail screens draw their own sections (they have more
 * room); see `LocationDetail.kt`, `ScheduleDetail.kt`, `EventInfoScreen.kt`.
 */

/** What screen components need beyond [LocalBlockActions] (Home provides it; chat answers may not). */
@Immutable
class ComponentHost(
    val mySchedule: MySchedule? = null,
    val dismissedUrgent: Set<Int> = emptySet(),
    /** Home's urgent card swiped away (Undo is the caller's). */
    val onDismissUrgent: ((Int) -> Unit)? = null,
    /** The event info screen (FAQ's "All questions"). */
    val onEventInfo: (() -> Unit)? = null,
    /** Home's gear when Home is the Options tab. */
    val onOptions: (() -> Unit)? = null,
)

val LocalComponentHost = staticCompositionLocalOf { ComponentHost() }

/** [BlockActions] with cards opening through [onOpen] (Home: on the item's tab). */
fun BlockActions.withOpen(onOpen: (EntityRef) -> Unit) = BlockActions(
    bundle = bundle, plan = plan, hearts = hearts, onOpen = onOpen,
    onChip = onChip, onOpenTab = onOpenTab, onShowOnMap = onShowOnMap,
    onDirections = onDirections, onShare = onShare, shareLink = shareLink,
    onSetting = onSetting, settingOn = settingOn,
    onCheck = onCheck, onUseVersion = onUseVersion, onOpenFlyer = onOpenFlyer, onOpenPlan = onOpenPlan,
)

/** Draws one component: a chat block (its own renderer) or a screen component. */
@Composable
fun ComponentView(component: Component, modifier: Modifier = Modifier, index: Int = 0, controls: TurnControls = TurnControls()) {
    val block = component.block
    if (block != null && block !is AssistantBlock.ComponentBlock) {
        AssistantBlockView(block, index, controls, modifier)
    } else {
        ScreenComponentView(component, modifier)
    }
}

/** A screen component (Home's sections, and the same components in chat answers), in the Home tab's style. */
@Composable
fun ScreenComponentView(component: Component, modifier: Modifier = Modifier) {
    val actions = LocalBlockActions.current ?: return
    val host = LocalComponentHost.current
    val bundle = actions.bundle
    val event = bundle.event
    val context = event.context
    val now = rememberNow()
    val title = component.title
    fun subject(kind: RefKind): Int? = component.subject?.takeIf { it.kind == kind }?.id
    when (component.type) {
        "header" -> HomeHeader(bundle, host.onOptions, modifier)
        "urgent" -> HomeUrgent.pick(bundle.notifications, host.dismissedUrgent, now)?.let { urgent ->
            UrgentCard(urgent, bundle, onOpen = { actions.onOpen(EntityRef(RefKind.UPDATE, urgent.id)) }, onDismiss = host.onDismissUrgent, modifier = modifier)
        }
        "countdown" -> EventCountdown(bundle, title, modifier)
        "my_schedule" -> MyScheduleUpNext(bundle, host.mySchedule, now, title, component.limit ?: 5, modifier)
        "my_day" -> MyScheduleDay(bundle, host.mySchedule, now, title, modifier)
        "whats_on" -> {
            val ids = remember(bundle, now, component.limit) {
                ScheduleGrouping.sortChronologically(bundle.schedule.filter { it.startTime != null && !ScheduleGrouping.hasEnded(it, now) })
                    .take(component.limit ?: 6).map { it.id }
            }
            if (ids.isNotEmpty()) SectionCard(modifier) { Timeline(ids, title ?: "What's On") }
        }
        "places" -> if (bundle.locations.isNotEmpty()) {
            Carousel(bundle.locations.take(component.limit ?: 8).map { EntityRef(RefKind.LOCATION, it.id) }, title ?: "Places", modifier)
        }
        "updates" -> if (bundle.notifications.isNotEmpty()) {
            val ids = remember(bundle) {
                bundle.notifications.sortedByDescending { it.sendTime?.toInstant() ?: Instant.MIN }.map { EntityRef(RefKind.UPDATE, it.id) }
            }
            SectionCard(modifier) { EntityList(ids, title ?: "Latest Updates", component.limit ?: 3) }
        }
        "faq" -> FaqSection(context?.faq.orEmpty(), modifier, title = title, limit = component.limit ?: 3, onAll = host.onEventInfo)
        "getting_there" -> TextSection(title ?: "Getting There", context?.gettingThere.orEmpty(), modifier)
        "about" -> TextSection(title ?: "About", AboutText.merged(event.description, context?.about), modifier)
        "links" -> LinksSection(context?.links.orEmpty(), title = title, modifier = modifier)
        "flyer" -> if (event.flyerUrl != null) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(title ?: "Flyer")
                Gallery(AssistantBlock.Gallery(flyer = true))
            }
        }
        "website" -> event.websiteUrl?.let { url ->
            SectionCard(modifier) {
                SectionTitle(title ?: "Website")
                EventWebsiteButton(url)
            }
        }
        "menu" -> bundle.location(subject(RefKind.LOCATION))?.let { place ->
            MenuSection(place.context?.menu.orEmpty(), modifier, title = title ?: "Menu · ${place.name}", limit = component.limit)
        }
        "hours" -> bundle.location(subject(RefKind.LOCATION))?.let { place ->
            HoursSection(place.context?.hours.orEmpty(), modifier, title = title ?: "Hours · ${place.name}")
        }
        "performers" -> bundle.scheduleItem(subject(RefKind.SCHEDULE))?.let { item ->
            PerformersSection(item.context?.performers.orEmpty(), modifier, title = title, bioLines = 3)
        }
        "amenities" -> AmenitiesSection(
            context?.amenities.orEmpty(), bundle, onLocation = { actions.onOpen(EntityRef(RefKind.LOCATION, it)) },
            title = title, limit = component.limit ?: 12, modifier = modifier,
        )
        "accessibility" -> TextSection(title ?: "Accessibility", context?.accessibility.orEmpty(), modifier)
        "policies" -> TextSection(title ?: "Policies", context?.policies.orEmpty(), modifier)
        "weather_plan" -> TextSection(title ?: "Weather Plan", context?.weatherPlan.orEmpty(), modifier)
        "now_playing" -> NowNextStrip(null, modifier)
        "now_next" -> NowNextStrip(subject(RefKind.LOCATION), modifier)
        "map" -> {
            val pins = remember(bundle) { bundle.locations.filter { it.coordinate != null }.take(12).map { it.id } }
            if (pins.isNotEmpty()) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(title ?: "Map")
                    MapBlock(AssistantBlock.MapBlock(pins))
                }
            }
        }
        // `update_banner` (catalogue 2, phase 5c) and `status` (the fixed pills stay) draw nothing here yet;
        // `ask_about` draws nothing since Nov 21, 2026; unknown types are skipped.
        else -> Unit
    }
}

/** No card (iOS): the event's name as a title, one line with its day and place, and the gear when Home is the Options tab. */
@Composable
private fun HomeHeader(bundle: EventBundle, onOptions: (() -> Unit)?, modifier: Modifier) {
    val event = bundle.event
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                event.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            HomeHeaderText.line(event)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onOptions != null) IconButton(onClick = onOptions) { Icon(Icons.Filled.Settings, contentDescription = "Options") }
    }
}

/**
 * Home's urgent card (API-011): compact (title + time, one line of body), swiped away sideways (or the
 * Dismiss action for TalkBack); the update stays in Updates, and the caller offers Undo.
 */
@Composable
private fun UrgentCard(notification: EventNotification, bundle: EventBundle, onOpen: () -> Unit, onDismiss: ((Int) -> Unit)?, modifier: Modifier) {
    val offset = remember(notification.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dismiss = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDismiss?.invoke(notification.id)
        Unit
    }
    val draggable = if (onDismiss == null) Modifier else Modifier.draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
        onDragStopped = {
            if (abs(offset.value) > 280f) {
                offset.animateTo(if (offset.value > 0) 1500f else -1500f, tween(200))
                dismiss()
            } else {
                offset.animateTo(0f)
            }
        },
    )
    Column(
        modifier
            .fillMaxWidth()
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .alpha(1f - (abs(offset.value) / 900f).coerceAtMost(0.6f))
            .then(draggable)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Open the update", onClick = onOpen)
            .semantics {
                contentDescription = "Urgent: ${notification.title}"
                if (onDismiss != null) customActions = listOf(CustomAccessibilityAction("Dismiss") { dismiss(); true })
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                notification.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            notification.sendTime?.let {
                Text(EventTime.formatTime(it, bundle.event.zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (notification.body.isNotBlank()) {
            Text(notification.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Until the gates open: "Starts in 2:14:05" and the start in the event's zone. */
@Composable
private fun EventCountdown(bundle: EventBundle, title: String?, modifier: Modifier) {
    val now = rememberNow(1_000L)
    val start = bundle.event.startTime ?: return
    val left = Duration.between(now, start.toInstant())
    if (left.isNegative || left.isZero) return
    val seconds = left.seconds
    val text = "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    SectionCard(modifier.semantics(mergeDescendants = true) {}) {
        Text(title ?: "Starts in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(EventTime.formatDateTime(start, bundle.event.zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What Home's and the chat's My schedule say while it is empty (iOS `MyScheduleText.empty`). */
const val MY_SCHEDULE_EMPTY = "Add things to your schedule to see them here"

/**
 * Home's "Up next in my schedule" (and the chat's `my_schedule`): the next 3–5 entries as cards with
 * time, place and walk; the empty state otherwise; a small line for places saved without a time.
 */
@Composable
private fun MyScheduleUpNext(bundle: EventBundle, schedule: MySchedule?, now: Instant, title: String?, limit: Int, modifier: Modifier) {
    val rows = remember(bundle, schedule, now, limit) { schedule?.upNext(bundle, now, limit.coerceIn(3, 5)).orEmpty() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title ?: "Up Next in My Schedule")
        if (rows.isEmpty()) {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.EventAvailable, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(MY_SCHEDULE_EMPTY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rows, key = { it.id }) { row -> ScheduleRowCard(bundle, row, Modifier.widthIn(min = 240.dp, max = 300.dp)) }
            }
        }
        val saved = schedule?.savedPlaceIds?.size ?: 0
        if (saved > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(if (saved == 1) "1 saved place" else "$saved saved places", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ScheduleRowCard(bundle: EventBundle, row: MyScheduleRow, modifier: Modifier) {
    when (row.entry.kind) {
        ScheduleEntryKind.ITEM -> bundle.scheduleItem(row.entry.id)?.let { ItemCard(it, listOf("time", "place", "image", "distance", "like"), modifier) }
        ScheduleEntryKind.PLACE -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                EventTime.formatTimeRange(row.start.atZone(bundle.event.zone).toOffsetDateTime(), row.end.atZone(bundle.event.zone).toOffsetDateTime(), bundle.event.zone),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            PlaceCard(row.entry.id, listOf("image", "distance", "like"))
        }
    }
}

/** "Your Day" (chat): My schedule's next day with entries, time and place; the empty state otherwise. */
@Composable
private fun MyScheduleDay(bundle: EventBundle, schedule: MySchedule?, now: Instant, title: String?, modifier: Modifier) {
    val actions = LocalBlockActions.current ?: return
    val zone = bundle.event.zone
    val rows = remember(bundle, schedule, now) {
        val upcoming = schedule?.rows(bundle, now).orEmpty().filter { !it.ended }
        val day = upcoming.firstOrNull()?.start?.atZone(zone)?.toLocalDate()
        upcoming.filter { it.start.atZone(zone).toLocalDate() == day }
    }
    SectionCard(modifier) {
        SectionTitle(title ?: "Your Day")
        if (rows.isEmpty()) {
            Text(MY_SCHEDULE_EMPTY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { row ->
            val ref = EntityRef(if (row.entry.kind == ScheduleEntryKind.ITEM) RefKind.SCHEDULE else RefKind.LOCATION, row.entry.id)
            Row(
                Modifier.fillMaxWidth().clickable { actions.onOpen(ref) }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    EventTime.formatTime(row.start.atZone(zone).toOffsetDateTime(), zone),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                Column {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    row.placeName?.takeIf { it != row.name }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
