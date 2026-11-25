@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arcu.attnd.AppGraph
import com.arcu.attnd.AttndApplication
import com.arcu.attnd.data.HeadingSource
import com.arcu.attnd.data.UserLocationSource
import com.arcu.attnd.domain.DistanceUnits
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Glance
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.MyScheduleText
import com.arcu.attnd.domain.OrganizerChange
import com.arcu.attnd.domain.RelativeTime
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.Walking
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Event-wide actions every row and detail can use (My schedule's heart, "Show on map"), provided once by
 * the event shell so they don't have to be threaded through every list. [onShowOnMap] is null when the
 * event has no Map tab. [schedule] is null in screenshot mode (nothing is stored there): the heart then
 * only toggles.
 */
@Immutable
class EventActions(
    val hearts: Hearts = Hearts.NONE,
    val onToggleHeart: (SearchKind, Int) -> Unit = { _, _ -> },
    val onShowOnMap: ((Int) -> Unit)? = null,
    val bundle: EventBundle? = null,
    val schedule: MySchedule? = null,
    /** The outline heart: adds at once (a place without a time) with the Undo snackbar. */
    val onAdd: (SearchKind, Int) -> Unit = onToggleHeart,
    /** The filled heart's "Remove from My Schedule". */
    val onRemove: (SearchKind, Int) -> Unit = onToggleHeart,
    /** "When?": a place at a time, or saved without one (null). */
    val onSetPlaceTime: (placeId: Int, at: Instant?) -> Unit = { _, _ -> },
    /** "Remind Me": minutes before (0 = off). */
    val onSetReminder: (SearchKind, Int, Int) -> Unit = { _, _, _ -> },
    /** The Schedule tab's organizer changes: Keep (a cancelled item: Remove), Find Another. */
    val onKeepOrganizerChange: (Int) -> Unit = {},
    val onFindAnother: (OrganizerChange) -> Unit = {},
    /** My Schedule's Share (QR): the `/p/` link of its items (null = nothing to share), and Share / Copy counted. */
    val scheduleShareLink: () -> String? = { null },
    val onShareSchedule: (via: String) -> Unit = {},
)

val LocalEventActions = compositionLocalOf { EventActions() }

/**
 * Store-screenshot mode: no location tracking (so no distances, arrows, walk times or routes) and no
 * map glance features (live dots, "Next" labels, Now playing). Provided at the app's root.
 */
val LocalScreenshotMode = staticCompositionLocalOf { false }

// ── Position and heading ────────────────────────────────────────────────────

@Composable
private fun sensorsGraph(): AppGraph? {
    if (LocalInspectionMode.current || LocalScreenshotMode.current) return null
    return (LocalContext.current.applicationContext as? AttndApplication)?.graph
}

/**
 * The user's position while this composable is shown and the app is resumed (null without the
 * location permission). Location updates stop when no visible screen needs them.
 */
@Composable
fun rememberUserLocation(): GeoPoint? {
    val source: UserLocationSource = sensorsGraph()?.userLocation ?: return null
    LifecycleResumeEffect(source) {
        source.acquire()
        onPauseOrDispose { source.release() }
    }
    val location by source.location.collectAsState()
    return location
}

/** The compass heading while shown and resumed: degrees from true north (screen-corrected) and accuracy. */
@Immutable
data class HeadingReading(val degrees: Float, val accuracyDegrees: Double?)

@Composable
fun rememberHeading(): HeadingReading? {
    val source: HeadingSource = sensorsGraph()?.heading ?: return null
    LifecycleResumeEffect(source) {
        source.acquire()
        onPauseOrDispose { source.release() }
    }
    val heading: State<Float?> = source.heading.collectAsState()
    val accuracy: State<Double?> = source.accuracyDegrees.collectAsState()
    return heading.value?.let { HeadingReading(it, accuracy.value) }
}

/** The position fix's accuracy (metres) and whether only approximate location is granted. */
@Composable
private fun rememberFixQuality(): Pair<Double?, Boolean> {
    val source = sensorsGraph()?.userLocation ?: return null to true
    val accuracy by source.accuracyMeters.collectAsState()
    return accuracy to source.isApproximate()
}

/** Miles/feet or km/m from the phone's region. */
@Composable
fun rememberDistanceUnits(): DistanceUnits {
    val locales = LocalConfiguration.current.locales
    return remember(locales) { DistanceUnits.forRegion(locales[0]?.country) }
}

/**
 * "0.6 mi" + an arrow pointing at [target] (bearing − heading, animated the short way round), or
 * "Here" within ~20 m. Nothing without the location permission or a position; no arrow without a
 * heading. Read as "Northeast, 0.6 miles".
 */
@Composable
fun DistanceArrow(
    target: GeoPoint?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    arrowSize: Dp = 14.dp,
) {
    target ?: return
    val user = rememberUserLocation() ?: return
    val heading = rememberHeading()
    val (fixAccuracy, approximate) = rememberFixQuality()
    val units = rememberDistanceUnits()
    val direction = remember(user, target, heading, fixAccuracy, approximate, units) {
        Geo.direction(
            user, target, heading?.degrees?.toDouble(), units,
            headingAccuracy = heading?.accuracyDegrees,
            locationAccuracyMeters = fixAccuracy,
            approximateLocation = approximate,
        )
    }
    Row(
        modifier.clearAndSetSemantics { contentDescription = direction.description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(direction.text, style = style, color = color, maxLines = 1)
        direction.arrowDegrees?.let { degrees ->
            // Unwrapped so 359° → 1° animates +2°, not −358°.
            val unwrapped = remember { mutableFloatStateOf(degrees.toFloat()) }
            LaunchedEffect(degrees) {
                unwrapped.floatValue = Geo.unwrap(unwrapped.floatValue.toDouble(), degrees).toFloat()
            }
            val angle by animateFloatAsState(unwrapped.floatValue, label = "arrow")
            Icon(Icons.Filled.Navigation, contentDescription = null, tint = color, modifier = Modifier.size(arrowSize).rotate(angle))
        }
    }
}

/**
 * "8 min walk" to [target] (straight-line estimate) or "You're here" when the user is within 8 km of
 * it, i.e. at the event; nothing otherwise.
 */
@Composable
fun WalkTime(target: GeoPoint?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    target ?: return
    val user = rememberUserLocation() ?: return
    val text = remember(user, target) { Walking.rowText(Geo.distanceMeters(user, target)) } ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

// ── Hearts ──────────────────────────────────────────────────────────────────

/** A small "live" dot (something is happening now). */
@Composable
fun LiveDot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .background(LiveColor, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

val LiveColor = Color(0xFFE53935)

// ── Peek ────────────────────────────────────────────────────────────────────

/** What a long-pressed row shows in its peek sheet. */
data class PeekTarget(val kind: SearchKind, val id: Int)

/**
 * The peek on a long-pressed location or schedule row: a bottom sheet with the key info and Heart,
 * Show on map (when the event has a Map tab and the place has coordinates) and Open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeekSheet(bundle: EventBundle, target: PeekTarget, now: Instant, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val actions = LocalEventActions.current
    // Hidden → Expanded only (no half-open state), the replacement for skipPartiallyExpanded = true.
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    val zone = bundle.event.zone
    val location = when (target.kind) {
        SearchKind.LOCATION -> bundle.location(target.id)
        SearchKind.SCHEDULE -> bundle.location(bundle.scheduleItem(target.id)?.locationId)
        else -> null
    }
    val item = if (target.kind == SearchKind.SCHEDULE) bundle.scheduleItem(target.id) else null
    val title = item?.name ?: location?.name
    if (title == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val hearted = actions.hearts.has(target.kind, target.id)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item != null) {
                Text(EventSearch.scheduleDetail(item, bundle), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                location?.category?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item != null) {
                    RelativeTime.forItem(item, now, zone, showEnd = true)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                } else if (location != null) {
                    Glance.locationLine(bundle, location.id, now)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                DistanceArrow(location?.coordinate)
                WalkTime(location?.coordinate)
            }
            val description = item?.description ?: location?.let { it.snippet.ifBlank { it.description } }
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (item != null && item.startTime == null) {
                Text(ScheduleGrouping.TIME_TBA_TITLE, style = MaterialTheme.typography.bodyMedium)
            } else if (item != null && location == null) {
                Text(item.locationName ?: ScheduleGrouping.GENERAL_TITLE, style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = { if (hearted) actions.onRemove(target.kind, target.id) else actions.onAdd(target.kind, target.id) }) {
                    Icon(if (hearted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (hearted) MyScheduleText.REMOVE else MyScheduleText.ADD_LONG)
                }
                val showOnMap = actions.onShowOnMap
                if (showOnMap != null && location?.coordinate != null) {
                    OutlinedButton(onClick = { close { showOnMap(location.id) } }) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Show on map")
                    }
                }
                OutlinedButton(onClick = { close(onOpen) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open")
                }
            }
        }
    }
}

// ── Updates banner ──────────────────────────────────────────────────────────

/** The newest unread organizer update at the top of the other tabs (24 h, dismissible). */
@Composable
fun UpdateBanner(notification: EventNotification, bundle: EventBundle, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        // API-011: an urgent update uses the error colours.
        color = if (notification.isUrgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (notification.isUrgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (notification.isUrgent) Icons.Filled.Warning else Icons.Filled.Campaign,
                contentDescription = if (notification.isUrgent) "Urgent" else null,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(notification.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(
                    notification.sendTime?.let { EventTime.formatTime(it, bundle.event.zone) },
                    notification.body.trim().takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss update") }
        }
    }
}
