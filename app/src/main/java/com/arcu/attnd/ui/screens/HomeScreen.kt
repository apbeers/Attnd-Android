package com.arcu.attnd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcu.attnd.domain.ComponentCatalogue
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.FoldPosture
import com.arcu.attnd.domain.HomeColumnWidths
import com.arcu.attnd.domain.HomeColumns
import com.arcu.attnd.domain.HomeUrgent
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.ScheduleItem
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.domain.TabletopGlance
import com.arcu.attnd.domain.TabletopSplit
import com.arcu.attnd.domain.TimeMatcher
import com.arcu.attnd.ui.assistant.BlockActions
import com.arcu.attnd.ui.assistant.LocalBlockActions
import com.arcu.attnd.ui.components.ComponentHost
import com.arcu.attnd.ui.components.ComponentView
import com.arcu.attnd.ui.components.FoldAwareBox
import com.arcu.attnd.ui.components.LocalComponentHost
import com.arcu.attnd.ui.components.LocalScreenshotMode
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSections
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The Home tab (iOS `HomeTab`): first and the default outside screenshot mode, no top app bar, nothing but
 * the `home` layout's sections (API-024; the built-in one: header, urgent, countdown, Up next in my
 * schedule, map, updates, FAQ, getting there, about, website) drawn by the one renderer
 * ([ComponentView]). Taps open the item on its tab ([actions]'s `onOpen`). Layouts follow this container and
 * the reported fold ([FoldAwareBox]), never the device:
 * - from a 700 dp container: the full-width parts, then two independent columns ([HomeColumns]) whose widths
 *   meet at the reported vertical hinge, 20 dp each side of it ([HomeColumnWidths]; else two halves 40 dp apart);
 * - tabletop (half-opened, a horizontal hinge across the middle): the top half a glance that never scrolls
 *   ([TabletopGlanceView]), the bottom half the rest as a list ([TabletopSplit.GLANCE_TYPES] left out);
 * - else one column.
 * The section at the top is kept by its key across fold / unfold / tabletop (indices change between the layouts).
 */
@Composable
fun HomeScreen(
    bundle: EventBundle,
    actions: BlockActions,
    host: ComponentHost,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val now = rememberNow()
    val sections = rememberSections(LayoutScreen.HOME, bundle, now) { b, state -> ScreenFacts.event(b, state) }
    // The section at the top of the list, kept across layouts (iOS `scrollPosition(id:)`).
    var topSection by rememberSaveable { mutableStateOf<String?>(null) }
    val onTop: (String) -> Unit = { topSection = it }
    CompositionLocalProvider(LocalBlockActions provides actions, LocalComponentHost provides host) {
        RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            FoldAwareBox(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ) { fold ->
                val width = maxWidth.value.toDouble()
                when {
                    fold is FoldPosture.Tabletop && !LocalScreenshotMode.current -> Column(Modifier.fillMaxSize()) {
                        TabletopGlanceView(bundle, actions, host, Modifier.fillMaxWidth().height(fold.foldMinY.dp))
                        Spacer(Modifier.height((fold.foldMaxY - fold.foldMinY).coerceAtLeast(0.0).dp))
                        OneColumn(sections.filter { it.type !in TabletopSplit.GLANCE_TYPES }, topSection, onTop, Modifier.weight(1f))
                    }
                    width >= TWO_COLUMN_WIDTH -> TwoColumns(sections, width, fold as? FoldPosture.Book, topSection, onTop)
                    else -> OneColumn(sections, topSection, onTop, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Two columns from this container width (an unfolded foldable's inner display). */
private const val TWO_COLUMN_WIDTH = 700.0
private const val GUTTER = 20.0

/** Parts that keep the whole width in two-column mode. */
private val FULL_WIDTH = setOf("header", "urgent", "countdown", "now_next")

@Composable
private fun Section(section: LayoutSection) {
    ComponentView(ComponentCatalogue.component(section))
}

@Composable
private fun OneColumn(sections: List<LayoutSection>, topSection: String?, onTop: (String) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        // Back at the section that was on top in the other layout, then follow the scrolling.
        val index = topSection?.let { key -> sections.indexOfFirst { it.type == key } } ?: -1
        if (index > 0) listState.scrollToItem(index)
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String }
            .filterNotNull()
            .collect { onTop(it) }
    }
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(sections, key = { it.type }) { section -> Section(section) }
    }
}

@Composable
private fun TwoColumns(
    sections: List<LayoutSection>,
    width: Double,
    fold: FoldPosture.Book?,
    topSection: String?,
    onTop: (String) -> Unit,
) {
    val wide = sections.filter { it.type in FULL_WIDTH }
    val columns = HomeColumns.split(sections.filter { it.type !in FULL_WIDTH })
    val widths = HomeColumnWidths.of(width, margin = 16.0, gutter = GUTTER, foldMinX = fold?.foldMinX, foldMaxX = fold?.foldMaxX)
    val scroll = rememberScrollState()
    // Each section's offset in the scrolled content (px), to find and restore the top section by key.
    val offsets = remember { mutableStateMapOf<String, Int>() }
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(scroll) {
        topSection?.takeIf { it != sections.firstOrNull()?.type }?.let { key ->
            val y = snapshotFlow { offsets[key] }.filterNotNull().first()
            scroll.scrollTo(y)
        }
        snapshotFlow { scroll.value to offsets.toMap() }.collect { (value, known) ->
            sections.filter { known[it.type] != null && known[it.type]!! <= value + 1 }
                .maxByOrNull { known[it.type]!! }
                ?.let { onTop(it.type) }
        }
    }
    @Composable
    fun Tracked(section: LayoutSection) {
        Box(
            Modifier.onGloballyPositioned { coords ->
                content?.let { root -> offsets[section.type] = root.localPositionOf(coords, Offset.Zero).y.roundToInt() }
            },
        ) { Section(section) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .onGloballyPositioned { content = it }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        wide.forEach { Tracked(it) }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(widths.leading.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { columns.leading.forEach { Tracked(it) } }
            Spacer(Modifier.width(widths.gap.dp))
            Column(Modifier.width(widths.trailing.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { columns.trailing.forEach { Tracked(it) } }
        }
    }
}

/**
 * The top half when half-folded (iOS `TabletopGlanceView`): the event and the time (Home's gear when Home is the
 * Options tab), the urgent announcement in solid red, Now with its progress and Next with a live countdown, from
 * My schedule first ([TabletopGlance]). Big type that shrinks to fit the half; it never scrolls.
 */
@Composable
private fun TabletopGlanceView(bundle: EventBundle, actions: BlockActions, host: ComponentHost, modifier: Modifier) {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val zone = bundle.event.zone
    val mine = host.mySchedule?.itemIds ?: actions.hearts.scheduleItems
    val glance = TabletopGlance.of(bundle.schedule, mine, now)
    val urgent = HomeUrgent.pick(bundle.notifications, host.dismissedUrgent, now)
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bundle.event.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    EventTime.formatTime(now.atZone(zone).toOffsetDateTime(), zone),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                host.onOptions?.let { onOptions ->
                    IconButton(onClick = onOptions) { Icon(Icons.Outlined.Settings, contentDescription = "Options") }
                }
            }
            urgent?.let { u ->
                Surface(
                    onClick = { actions.onOpen(EntityRef(RefKind.UPDATE, u.id)) },
                    color = UrgentRed,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Urgent: ${u.title}" },
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GlanceSlot("Now", glance.now, glance.nowIsMine, isNext = false, bundle, now, actions, Modifier.weight(1f))
                VerticalDivider()
                GlanceSlot("Next", glance.next, glance.nextIsMine, isNext = true, bundle, now, actions, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GlanceSlot(
    title: String,
    item: ScheduleItem?,
    mine: Boolean,
    isNext: Boolean,
    bundle: EventBundle,
    now: Instant,
    actions: BlockActions,
    modifier: Modifier,
) {
    val zone = bundle.event.zone
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = primary)
            if (mine) Icon(Icons.Filled.Favorite, contentDescription = "In my schedule", tint = primary, modifier = Modifier.size(14.dp))
        }
        if (item == null) {
            Text(
                if (isNext) "Nothing later today" else "Nothing on right now",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().clickable { actions.onOpen(EntityRef(RefKind.SCHEDULE, item.id)) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(
                item.name,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 32.sp),
            )
            (bundle.location(item.locationId)?.name ?: item.locationName)?.let { place ->
                Text(place, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val start = item.startTime?.toInstant()
            if (isNext && start != null && start.isAfter(now)) {
                BasicText(
                    TabletopGlance.countdown(now, start),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, color = primary, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 34.sp),
                )
            } else if (!isNext && start != null) {
                val end = TimeMatcher.effectiveEnd(item)
                if (end != null) {
                    val total = Duration.between(start, end).seconds.coerceAtLeast(1)
                    val done = Duration.between(start, now).seconds.coerceIn(0, total)
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("Ends ${EventTime.formatTime(end.atZone(zone).toOffsetDateTime(), zone)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Solid red for the urgent announcement on the glance (iOS `Color.red`), white text on it. */
private val UrgentRed = Color(0xFFC62828)
