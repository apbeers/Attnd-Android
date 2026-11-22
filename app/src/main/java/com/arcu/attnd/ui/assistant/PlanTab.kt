@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.DayRoutes
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanProposal
import com.arcu.attnd.domain.PlanTabFlow
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.PreviewRow
import com.arcu.attnd.domain.PreviewRows
import com.arcu.attnd.domain.ProposalKind
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.RowMark
import com.arcu.attnd.domain.ScheduleGrouping
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.TurnPath
import com.arcu.attnd.ui.AssistantUiState
import com.arcu.attnd.ui.PlanUiState
import com.arcu.attnd.ui.SearchRequest
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LiveDot
import com.arcu.attnd.ui.components.rememberDistanceUnits
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSearchReporter
import com.arcu.attnd.ui.screens.SearchDetail
import com.arcu.attnd.ui.screens.SearchEntry
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The detail stack of the bar's results and cards (saveable as text), shared by both hosts. */
private class DetailStack(text: String, val set: (String) -> Unit) {
    val entries: List<SearchEntry> = SearchEntry.decodeStack(text)
    val top: SearchEntry? get() = entries.lastOrNull()
    fun push(entry: SearchEntry) = set(SearchEntry.encodeStack(entries + entry))
    fun pop() = set(SearchEntry.encodeStack(entries.dropLast(1)))
}

private fun EntityRef.entry(): SearchEntry = SearchEntry(
    when (kind) {
        RefKind.SCHEDULE -> SearchKind.SCHEDULE
        RefKind.LOCATION -> SearchKind.LOCATION
        RefKind.UPDATE -> SearchKind.NOTIFICATION
    },
    id,
)

/** [actions] with cards opening on [stack]. */
private fun BlockActions.opening(onOpen: (EntityRef) -> Unit) = BlockActions(
    bundle = bundle, plan = plan, hearts = hearts, onOpen = onOpen,
    onChip = onChip, onOpenTab = onOpenTab, onShowOnMap = onShowOnMap,
    onDirections = onDirections, onShare = onShare, shareLink = shareLink,
    onSetting = onSetting, settingOn = settingOn,
    onCheck = onCheck, onUseVersion = onUseVersion, onOpenFlyer = onOpenFlyer, onOpenPlan = onOpenPlan,
)

/**
 * The My plan tab (plan tab spec): the plan edited in place, never a transcript. Header (day
 * switcher, summary, Share, Undo), the live hints, the timeline with any previewed edit merged in
 * ([PreviewRows]: dashed suggestions, crossed-out rows, moved badges), the day's mini map; without a
 * plan the example chips and the liked items. At the bottom the input bar ("Change your plan or
 * search…") with the answer card, the diff pill and the Undo pill above it. Works without
 * on-device AI (the rule-based router and plans from likes).
 */
@Composable
fun MyPlanTab(
    controller: AssistantController,
    bundle: EventBundle,
    model: AssistantUiState,
    planner: PlanUiState,
    request: SearchRequest?,
    focusInput: Int,
    actions: BlockActions,
    onRequestConsumed: () -> Unit,
    onSearch: () -> Unit,
    onDownloadModel: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { DetailStack(stackText) { stackText = it } }
    val wrapped = remember(actions, stack) { actions.opening { stack.push(it.entry()) } }
    val plan = planner.plan?.takeIf { !it.isEmpty }
    val noPlan = plan == null && state.proposal == null

    BackHandler(enabled = stack.top != null || state.proposal != null) {
        if (stack.top != null) stack.pop() else controller.cancelProposal()
    }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        // An item opens on the stack; a query goes to the dock (shown over no detail).
        if (pending.detail != null) {
            stack.push(pending.detail)
            onRequestConsumed()
        } else {
            stackText = ""
        }
    }

    CompositionLocalProvider(LocalBlockActions provides wrapped) {
        stack.top?.takeIf { request?.let { it.detail == null } != true }?.let { top ->
            SearchDetail(bundle, top, onBack = stack::pop, onPush = stack::push, onLocationShown, onScheduleItemShown)
            return@CompositionLocalProvider
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            PlanTimeline(controller, state, bundle, plan, planner, model, onDownloadModel, Modifier.weight(1f))
            AssistantDock(
                controller = controller,
                state = state,
                bundle = bundle,
                modelOn = model.availability == AssistantAvailability.AVAILABLE,
                request = request?.takeIf { it.detail == null },
                focusInput = focusInput,
                autoFocus = noPlan,
                onSearch = onSearch,
                onClose = null,
                onRequestConsumed = onRequestConsumed,
            )
        }
    }
}

/**
 * The input bar over another tab (the floating search button): the same bar and answer card on top
 * of that tab; a plan edit typed here switches to My plan with the preview shown
 * ([AssistantHost.onProposal]). Tapped results open full screen on its own back stack.
 */
@Composable
fun AssistantBarOverlay(
    controller: AssistantController,
    bundle: EventBundle,
    model: AssistantUiState,
    request: SearchRequest?,
    actions: BlockActions,
    onRequestConsumed: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { DetailStack(stackText) { stackText = it } }
    val wrapped = remember(actions, stack) { actions.opening { stack.push(it.entry()) } }
    BackHandler { if (stack.top != null) stack.pop() else onClose() }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        // Queries are run by the dock below (over no detail).
        if (pending.detail != null) {
            stack.push(pending.detail)
            onRequestConsumed()
        } else {
            stackText = ""
        }
    }
    CompositionLocalProvider(LocalBlockActions provides wrapped) {
        stack.top?.takeIf { request?.let { it.detail == null } != true }?.let { top ->
            Surface(Modifier.fillMaxSize()) {
                SearchDetail(bundle, top, onBack = stack::pop, onPush = stack::push, onLocationShown, onScheduleItemShown)
            }
            return@CompositionLocalProvider
        }
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            AssistantDock(
                controller = controller,
                state = state,
                bundle = bundle,
                modelOn = model.availability == AssistantAvailability.AVAILABLE,
                request = request?.takeIf { it.detail == null },
                focusInput = 0,
                autoFocus = request?.detail == null,
                onSearch = onSearch,
                onClose = onClose,
                onRequestConsumed = onRequestConsumed,
            )
        }
    }
}

// ── Timeline ────────────────────────────────────────────────────────────────

@Composable
private fun PlanTimeline(
    controller: AssistantController,
    state: AssistantState,
    bundle: EventBundle,
    plan: Plan?,
    planner: PlanUiState,
    model: AssistantUiState,
    onDownloadModel: () -> Unit,
    modifier: Modifier,
) {
    val actions = LocalBlockActions.current ?: return
    val proposal = state.proposal
    val zone = bundle.event.zone
    val now = rememberNow()
    val days = remember(plan, proposal) { PreviewRows.days(plan, proposal) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    // The preview's day is shown; otherwise today's plan day (else the first), or the one picked.
    LaunchedEffect(proposal?.day) { proposal?.day?.let { picked = it.toString() } }
    val today = now.atZone(zone).toLocalDate()
    val day: LocalDate? = picked?.let(LocalDate::parse)?.takeIf { it in days } ?: days.firstOrNull { it == today } ?: days.firstOrNull()
    val rows = remember(plan, proposal, day) { day?.let { PreviewRows.of(plan, proposal, it, bundle) }.orEmpty() }
    val planned = remember(plan) { plan?.items?.associateBy { it.itemId }.orEmpty() }
    val busy = state.busy == TurnPath.PLAN || planner.isPlanning
    val live = state.live

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "header") {
            PlanHeader(controller, state, bundle, plan, days, day, onDay = { picked = it.toString() })
        }
        if (busy) {
            item(key = "busy") {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text(if (model.availability == AssistantAvailability.AVAILABLE) "Planning on your phone…" else "Planning…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (plan != null) {
            live.runningLate?.let { late ->
                item(key = "late") {
                    RunningLateCard(late.text, late.state == LateState.CANT_MAKE_IT, late.chips, onChip = { controller.run(it.action, it.label, ActionSource.RULES) })
                }
            }
            if (live.recapReady) item(key = "recap") { SummaryCard("recap") }
            if (live.gaps.isNotEmpty() || live.skipped.isNotEmpty()) item(key = "hints") { HintChips(controller, state) }
        }
        items(rows, key = { row -> rowKey(row) }) { row ->
            when (row) {
                is PreviewRow.Item -> {
                    val entry = planned[row.itemId]
                    if (row.mark == RowMark.NONE && entry != null && (entry.walkMinutes ?: 0) > 0 && rows.firstOrNull { it is PreviewRow.Item } != row) {
                        WalkLegRow("${entry.walkMinutes} min walk")
                    }
                    ItemRow(bundle, row, entry, plan, now, onCheck = { controller.toggleCheckOff(row.itemId) })
                }
                is PreviewRow.Meal -> MealRow(bundle, row)
                is PreviewRow.Option -> OptionRow(bundle, row, proposal, onApply = { controller.applyProposal(row.index) })
            }
        }
        if (plan != null && day != null && proposal == null) {
            item(key = "map") { DayMap(plan, bundle, day, actions) }
        }
        if (plan == null && proposal == null && !busy) {
            item(key = "empty") { NoPlan(controller, bundle, planner, model, onDownloadModel) }
        }
    }
}

private fun rowKey(row: PreviewRow): String = when (row) {
    is PreviewRow.Item -> "i${row.itemId}"
    is PreviewRow.Meal -> "m${row.slot.encode()}"
    is PreviewRow.Option -> "o${row.index}:${row.itemId}"
}

@Composable
private fun PlanHeader(controller: AssistantController, state: AssistantState, bundle: EventBundle, plan: Plan?, days: List<LocalDate>, day: LocalDate?, onDay: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask Attnd", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val summary = plan?.let { Planner.summary(it, bundle.event.zone) } ?: state.proposal?.summary ?: "Plan your day: your schedule, walks and meals included."
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (plan != null) {
                IconButton(onClick = { controller.run(AssistantAction(ActionName.SHARE_PLAN), null, ActionSource.MANUAL) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share my schedule")
                }
            }
            IconButton(onClick = controller::undoLast, enabled = state.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo the last schedule change")
            }
        }
        if (days.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                days.forEach { d ->
                    FilterChip(selected = d == day, onClick = { onDay(d) }, label = { Text(EventTime.formatDayHeader(d)) })
                }
            }
        }
    }
}

/** Gap fillers and "You skipped X" (each previews in place when tapped). */
@Composable
private fun HintChips(controller: AssistantController, state: AssistantState) {
    val live = state.live
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        live.gaps.forEach { gap -> Text(gap.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            live.gaps.forEach { gap -> AssistChip(onClick = { controller.run(gap.chip.action, gap.chip.label) }, label = { Text(gap.chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            live.skipped.forEach { chip ->
                InputChip(
                    selected = false,
                    onClick = { controller.run(chip.action, chip.label) },
                    label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp).clickable { controller.dismissSkipped(chip) })
                    },
                )
            }
        }
    }
}

@Composable
private fun markColor(mark: RowMark) = when (mark) {
    RowMark.REMOVED, RowMark.REPLACED -> MaterialTheme.colorScheme.error
    RowMark.ADDED -> MaterialTheme.colorScheme.primary
    RowMark.MOVED -> MaterialTheme.colorScheme.tertiary
    RowMark.NONE -> MaterialTheme.colorScheme.onSurface
}

private fun markLabel(mark: RowMark): String? = when (mark) {
    RowMark.REMOVED -> "Removed"
    RowMark.REPLACED -> "Replaced"
    RowMark.ADDED -> "Added"
    RowMark.MOVED -> "Moved"
    RowMark.NONE -> null
}

@Composable
private fun ItemRow(bundle: EventBundle, row: PreviewRow.Item, entry: PlannedItem?, plan: Plan?, now: Instant, onCheck: () -> Unit) {
    val actions = LocalBlockActions.current ?: return
    val item = bundle.scheduleItem(row.itemId) ?: return
    val zone = bundle.event.zone
    val struck = row.mark == RowMark.REMOVED || row.mark == RowMark.REPLACED
    val ended = ScheduleGrouping.hasEnded(item, now)
    val checked = plan?.checkedOff?.contains(item.id) == true
    val movedTo = row.movedTo?.let { bundle.scheduleItem(it) }
    val badge = when {
        movedTo != null -> "Moves to ${EventTime.formatTime(movedTo.startTime, zone)}"
        else -> markLabel(row.mark)
    }
    val color = if (row.mark == RowMark.NONE) (if (ended) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface) else markColor(row.mark)
    Row(
        Modifier.fillMaxWidth().clickable { actions.onOpen(EntityRef(RefKind.SCHEDULE, item.id)) }.padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { if (badge != null) contentDescription = "$badge: ${item.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(EventTime.formatTime(item.startTime, zone), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(76.dp), color = color)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.mark == RowMark.NONE && ScheduleGrouping.isHappeningNow(item, now)) LiveDot()
                Text(
                    item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color,
                    textDecoration = if (struck || checked) TextDecoration.LineThrough else null,
                )
            }
            Text(
                listOfNotNull(badge, bundle.location(item.locationId)?.name ?: item.locationName, entry?.reason?.takeIf { row.mark == RowMark.NONE }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.mark == RowMark.NONE && entry != null && item.startTime?.toInstant()?.isAfter(now) == false) {
            IconToggleButton(checked = checked, onCheckedChange = { onCheck() }) {
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

@Composable
private fun MealRow(bundle: EventBundle, row: PreviewRow.Meal) {
    val clock = remember { DateTimeFormatter.ofPattern("h:mm a") }
    val struck = row.mark == RowMark.REMOVED
    val color = markColor(row.mark)
    val badge = markLabel(row.mark)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(row.start.atZone(bundle.event.zone).format(clock), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp), color = color)
        Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(badge, row.slot.kind.label + (bundle.location(row.locationId)?.name?.let { " · $it nearby" } ?: "")).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = color,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        )
    }
}

/** A dashed "suggested" row (an add or a replacement option): tap to apply it. */
@Composable
private fun OptionRow(bundle: EventBundle, row: PreviewRow.Option, proposal: PlanProposal?, onApply: () -> Unit) {
    val item = bundle.scheduleItem(row.itemId) ?: return
    val zone = bundle.event.zone
    val outline = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val verb = if (proposal?.kind == ProposalKind.REPLACE) "Swap in" else "Add"
    val time = EventTime.formatTime(item.startTime, zone)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .drawBehind {
                val stroke = with(density) { 1.5.dp.toPx() }
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(with(density) { 12.dp.toPx() }),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(with(density) { 6.dp.toPx() }, with(density) { 4.dp.toPx() }))),
                )
            }
            .clickable(onClickLabel = "$verb ${item.name}") { onApply() }
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Suggested: ${item.name}, $time. Tap to ${verb.lowercase()}." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(68.dp), color = outline)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(bundle.location(item.locationId)?.name ?: item.locationName, row.reason).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.Add, contentDescription = null, tint = outline)
    }
}

@Composable
private fun DayMap(plan: Plan, bundle: EventBundle, day: LocalDate, actions: BlockActions) {
    val units = rememberDistanceUnits()
    val now = rememberNow()
    val estimate = remember(plan, day) { DayRoutes.of(plan, bundle, day) }
    val route = rememberServiceRoute(estimate, "${bundle.event.id}:$day:" + estimate?.stops?.joinToString("-") { it.locationId.toString() })
    val shown = route ?: estimate ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shown.legs.isNotEmpty()) {
            Text(DayRoutes.totalText(shown, now.atZone(bundle.event.zone).toLocalDate(), units), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.stops.isNotEmpty()) {
            MiniMap(routePins(shown, bundle), shown.legs.map { it.route }, onClick = { actions.onShowOnMap(shown.stops.firstOrNull()?.locationId) })
        }
    }
}

/** No plan yet: example chips, the on-device model card, and the liked items. */
@Composable
private fun NoPlan(controller: AssistantController, bundle: EventBundle, planner: PlanUiState, model: AssistantUiState, onDownload: () -> Unit) {
    val actions = LocalBlockActions.current ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            Text("Say what you'd like below, or start from one of these.", style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            planner.examples.forEach { example ->
                AssistChip(onClick = { controller.submit(example.text, if (example.isPlan) TurnPath.PLAN else null) }, label = { Text(example.text) })
            }
        }
        when (model.availability) {
            AssistantAvailability.DOWNLOADABLE -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Answers on your phone", style = MaterialTheme.typography.titleMedium)
                    Text("Download Google's on-device AI model (Gemini Nano) once to ask questions and plan from what you type. Your questions never leave your phone.", style = MaterialTheme.typography.bodyMedium)
                    model.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FilledTonalButton(onClick = onDownload) { Text("Download") }
                }
            }
            AssistantAvailability.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Downloading on-device AI…", style = MaterialTheme.typography.titleSmall)
                val total = model.totalBytes
                val done = model.downloadedBytes
                if (total != null && total > 0 && done != null) LinearWavyProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> Unit
        }
        val liked = remember(actions.hearts, bundle) { EventSearch.hearted(bundle, actions.hearts, Instant.now()) }
        if (liked.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("In your schedule (plans start from these)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Column {
                liked.forEachIndexed { i, result ->
                    if (i > 0) HorizontalDivider()
                    EntityRef.of(result)?.let { EntityRow(it) }
                }
            }
        }
    }
}

// ── The dock: answer card, pills, input bar ─────────────────────────────────

@Composable
private fun AssistantDock(
    controller: AssistantController,
    state: AssistantState,
    bundle: EventBundle,
    modelOn: Boolean,
    request: SearchRequest?,
    focusInput: Int,
    autoFocus: Boolean,
    onSearch: () -> Unit,
    onClose: (() -> Unit)?,
    onRequestConsumed: (() -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submitReport = rememberSearchReporter(text, onSearch, source = "assistant", countResults = { q -> EventSearch.search(bundle, q, Instant.now()).size })

    fun send(value: String = text, forced: TurnPath? = state.forced) {
        if (value.isBlank()) return
        submitReport()
        controller.submit(value, forced)
        text = ""
    }

    // Search / assistant requests (details are opened by the host).
    var focusAsked by remember { mutableIntStateOf(0) }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        if (pending.detail != null) return@LaunchedEffect
        when {
            pending.ask && pending.query.isNotBlank() -> controller.submit(pending.query, if (modelOn) TurnPath.ASK else null)
            pending.query.isNotBlank() -> send(pending.query, null)
            else -> focusAsked++
        }
        onRequestConsumed?.invoke()
    }
    LaunchedEffect(focusInput, autoFocus, focusAsked) {
        if (focusInput > 0 || autoFocus || focusAsked > 0) {
            delay(120)
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
            }
            keyboard?.show()
        }
    }
    state.undoOffer?.let { offer -> LaunchedEffect(offer.serial) { delay(UNDO_PILL_MS); controller.dismissUndo(offer.serial) } }

    Column(Modifier.fillMaxWidth()) {
        val answer = state.answer
        when {
            text.isNotBlank() -> AnswerCard(onDismiss = { text = "" }) { LiveResults(bundle, text) }
            answer != null -> AnswerCard(onDismiss = controller::dismissAnswer) { AnswerBlocks(controller, answer) }
        }
        state.proposalNote?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        state.proposal?.let { proposal -> ProposalPill(proposal, onApply = { controller.applyProposal(0) }, onCancel = controller::cancelProposal) }
        if (state.proposal == null) state.undoOffer?.let { offer -> UndoPill(offer.label, onUndo = controller::undoLast) }
        InputBar(
            text = text,
            onText = { value ->
                // Typing again hides the answer card.
                if (value.isNotEmpty() && text.isEmpty()) controller.dismissAnswer()
                text = value
            },
            controller = controller,
            forced = state.forced,
            modelOn = modelOn,
            focus = focus,
            onSend = { send() },
            onEscape = { if (state.proposal != null) controller.cancelProposal() else if (text.isNotEmpty()) text = "" else onClose?.invoke() },
            onClose = onClose,
        )
    }
}

private const val UNDO_PILL_MS = 8_000L

/** ≤ a third of the window, scrollable inside; swipe down (or ✕) to dismiss. */
@Composable
private fun AnswerCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    var dragged by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta -> dragged += delta }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .draggable(drag, Orientation.Vertical, onDragStarted = { dragged = 0f }, onDragStopped = { if (dragged > DISMISS_DRAG_PX) onDismiss() }),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().heightIn(max = windowHeight / 3).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 40.dp, top = 12.dp, bottom = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss answer")
            }
        }
    }
}

private const val DISMISS_DRAG_PX = 80f

@Composable
private fun AnswerBlocks(controller: AssistantController, turn: AssistantTurnUi) {
    val controls = TurnControls(
        canUndo = turn.applied != null,
        undone = turn.undone,
        onUndo = { controller.undo(turn.id) },
        onApply = { controller.reapply(turn.id) },
        answered = turn.answered,
        onConfirm = { index, yes -> controller.answer(turn.id, index, yes) },
    )
    turn.blocks.forEachIndexed { index, block -> AssistantBlockView(block, index, controls) }
    if (turn.streaming && turn.blocks.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(28.dp))
            Text("Thinking on your phone…", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (turn.applied != null && turn.applied.name != ActionName.PLAN_EDIT && turn.applied.name != ActionName.PLAN_VERSION) {
        TextButton(onClick = { if (turn.undone) controller.reapply(turn.id) else controller.undo(turn.id) }) { Text(if (turn.undone) "Redo" else "Undo") }
    }
}

/** Live search results while typing (nothing is sent until Enter). */
@Composable
private fun LiveResults(bundle: EventBundle, query: String) {
    var settled by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        delay(200)
        settled = query
    }
    val now = rememberNow()
    val results = remember(bundle, settled, now) { EventSearch.search(bundle, settled, now) }
    if (results.isEmpty()) {
        Text("Nothing matches yet. Press send to ask or change your schedule.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    results.take(20).forEachIndexed { i, result ->
        if (i > 0) HorizontalDivider()
        val ref = EntityRef.of(result)
        if (ref != null) EntityRow(ref) else ListItem(supportingContent = { Text(EventSearch.detail(result, bundle)) }) { Text(result.title) }
    }
}

/** The previewed edit's summary ("−2 items, +1 lunch break") with Apply / Cancel. */
@Composable
private fun ProposalPill(proposal: PlanProposal, onApply: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = "Preview: ${proposal.summary}"
        },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EditCalendar, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(proposal.pill, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
            if (proposal.options.size == 1 || proposal.kind == ProposalKind.CHANGE || proposal.kind == ProposalKind.SHARED) {
                Button(onClick = onApply) { Text(if (proposal.kind == ProposalKind.SHARED) proposal.first.label else "Apply") }
            }
        }
    }
}

@Composable
private fun UndoPill(label: String, onUndo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}

/** "Leave now for X · 8 min walk" (or "You can't make X in time") with its chips. */
@Composable
fun RunningLateCard(text: String, cantMake: Boolean, chips: List<Chip>, onChip: (Chip) -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (cantMake) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (cantMake) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip -> AssistChip(onClick = { onChip(chip) }, label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
        }
    }
}

@Composable
private fun InputBar(
    text: String,
    onText: (String) -> Unit,
    controller: AssistantController,
    forced: TurnPath?,
    modelOn: Boolean,
    focus: FocusRequester,
    onSend: () -> Unit,
    onEscape: () -> Unit,
    onClose: (() -> Unit)?,
) {
    var predicted by remember { mutableStateOf(TurnPath.SEARCH) }
    LaunchedEffect(text, forced) {
        delay(250)
        predicted = controller.predict(text)
    }
    val shown = forced ?: predicted
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The intent chip: what Enter will do; tap to switch (Search → Ask (with AI) → Plan → automatic).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val order = listOfNotNull(TurnPath.SEARCH, if (modelOn) TurnPath.ASK else null, TurnPath.PLAN, null)
                        val next = order[(order.indexOf(forced) + 1) % order.size]
                        controller.setForced(next)
                    },
                    label = { Text(shown.label + if (forced == null) "" else " (chosen)") },
                    leadingIcon = {
                        Icon(
                            when (shown) {
                                TurnPath.SEARCH -> Icons.Filled.Search
                                TurnPath.ASK -> Icons.Filled.AutoAwesome
                                TurnPath.PLAN -> Icons.Filled.EditCalendar
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = "${shown.label} Tap to switch" },
                )
                Spacer(Modifier.weight(1f))
                if (onClose != null) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = { Text(PlanTabFlow.PLACEHOLDER, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    trailingIcon = if (text.isNotEmpty()) {
                        { IconButton(onClick = { onText("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") } }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                            onEscape()
                            true
                        } else {
                            false
                        }
                    },
                )
                IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

