@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.arcu.attnd.domain.AssistantBlock
import com.arcu.attnd.domain.Chip
import com.arcu.attnd.domain.Component
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.MySchedule
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanProposal
import com.arcu.attnd.domain.PlanTabFlow
import com.arcu.attnd.domain.PreviewRow
import com.arcu.attnd.domain.PreviewRows
import com.arcu.attnd.domain.ProposalKind
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.RowMark
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.TurnPath
import com.arcu.attnd.ui.AssistantUiState
import com.arcu.attnd.ui.PlanUiState
import com.arcu.attnd.ui.SearchRequest
import com.arcu.attnd.ui.components.PanePair
import com.arcu.attnd.ui.components.ComponentHost
import com.arcu.attnd.ui.components.LocalComponentHost
import com.arcu.attnd.ui.components.ScreenComponentView
import com.arcu.attnd.ui.components.rememberSearchReporter
import com.arcu.attnd.ui.screens.SearchDetail
import com.arcu.attnd.ui.screens.SearchEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/*
 * Ask Attnd (phase 6b; iOS `AssistantView.swift` → `PlanChatView`): one continuous chat per event over the
 * tabs. Typing does nothing until Return; the message is a right bubble, then a working line ("Searching…",
 * "Adding Taco Row…") that becomes the turn's done line ("Added Taco Row") with the answer: text and
 * components inline and interactive. The last turn's chips are the follow-up chips above the composer. A
 * pending change to My schedule is the proposal card (Apply per option, Cancel), then the Undo card. Before
 * anything is asked the chat shows Your Day (My schedule's day with walks). Expanded windows (a PanePair,
 * off the hinge): the chat beside the last shown component, large. Cards open their item on the chat's own
 * back stack (Back returns). On a phone whose Gemini Nano still has to download, a small card offers the
 * download (with progress); the chat runs on the rule-based router meanwhile.
 */

/** The detail stack of the chat's cards (saveable as text). */
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

/** [BlockActions] with cards opening through [onOpen]. */
private fun BlockActions.opening(onOpen: (EntityRef) -> Unit) = BlockActions(
    bundle = bundle, plan = plan, hearts = hearts, onOpen = onOpen,
    onChip = onChip, onOpenTab = onOpenTab, onShowOnMap = onShowOnMap,
    onDirections = onDirections, onShare = onShare, shareLink = shareLink,
    onSetting = onSetting, settingOn = settingOn,
    onCheck = onCheck, onUseVersion = onUseVersion, onOpenFlyer = onOpenFlyer, onOpenPlan = onOpenPlan,
)


@Composable
fun AskAttndChat(
    controller: AssistantController,
    bundle: EventBundle,
    model: AssistantUiState,
    planner: PlanUiState,
    mySchedule: MySchedule?,
    request: SearchRequest?,
    focusInput: Int,
    actions: BlockActions,
    onRequestConsumed: () -> Unit,
    onSearch: () -> Unit,
    onDownloadModel: () -> Unit,
    onClose: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { DetailStack(stackText) { stackText = it } }
    val wrapped = remember(actions, stack) { actions.opening { stack.push(it.entry()) } }
    val host = remember(mySchedule) { ComponentHost(mySchedule = mySchedule) }

    BackHandler {
        when {
            stack.top != null -> stack.pop()
            state.proposal != null -> controller.cancelProposal()
            else -> onClose()
        }
    }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        if (pending.detail != null) {
            stack.push(pending.detail)
            onRequestConsumed()
        } else {
            stackText = ""
        }
    }

    CompositionLocalProvider(LocalBlockActions provides wrapped, LocalComponentHost provides host) {
        stack.top?.takeIf { request?.let { it.detail == null } != true }?.let { top ->
            Surface(Modifier.fillMaxSize()) {
                SearchDetail(bundle, top, onBack = stack::pop, onPush = stack::push, onLocationShown, onScheduleItemShown)
            }
            return@CompositionLocalProvider
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ask Attnd") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                    actions = {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                enabled = state.turns.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    controller.clearConversation()
                                },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            // The chat is the main pane (always the same list, so folding keeps the conversation and its scroll);
            // on an expanded window the last shown component is the supporting pane beside it (PanePair: Material3
            // adaptive's supporting-pane scaffold, off the hinge; iOS: ArrangementView).
            PanePair(
                showsSupporting = true,
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                main = {
                    ChatColumn(controller, state, bundle, model, planner, request, focusInput, onRequestConsumed, onSearch, onDownloadModel, Modifier.fillMaxSize())
                },
                supporting = { LastComponentPane(state, actions.plan, Modifier.fillMaxSize()) },
            )
        }
    }
}

// ── The chat column ─────────────────────────────────────────────────────────

@Composable
private fun ChatColumn(
    controller: AssistantController,
    state: AssistantState,
    bundle: EventBundle,
    model: AssistantUiState,
    planner: PlanUiState,
    request: SearchRequest?,
    focusInput: Int,
    onRequestConsumed: () -> Unit,
    onSearch: () -> Unit,
    onDownloadModel: () -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Follows new lines only while the person is at the bottom (their own send always follows).
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom, list.isScrollInProgress) { if (list.isScrollInProgress) follow = atBottom }
    LaunchedEffect(state.serial, state.stage, state.proposal, state.undoOffer) {
        if (follow) list.layoutInfo.totalItemsCount.takeIf { it > 0 }?.let { list.animateScrollToItem(it - 1) }
    }
    var downloadDismissed by rememberSaveable { mutableStateOf(false) }
    val showDownload = !downloadDismissed && model.availability in setOf(AssistantAvailability.DOWNLOADABLE, AssistantAvailability.DOWNLOADING)

    Column(modifier.imePadding()) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showDownload) item(key = "download") { DownloadCard(model, onDownloadModel, onDismiss = { downloadDismissed = true }) }
            liveHints(controller, state)
            if (state.turns.isEmpty()) item(key = "your-day") { YourDay(planner.plan) }
            state.turns.forEachIndexed { index, turn ->
                item(key = "t${turn.id}") { ChatTurn(controller, turn, stage = state.stage, last = index == state.turns.lastIndex) }
            }
            if (state.turns.none { it.streaming } && state.stage != null) {
                item(key = "working") { WorkingLine(state.stage) }
            }
            state.proposal?.let { proposal ->
                item(key = "proposal") { ProposalCard(controller, proposal, bundle, planner.plan, state.proposalNote) }
            }
            state.undoOffer?.takeIf { state.proposal == null }?.let { offer ->
                item(key = "undo${offer.serial}") { UndoCard(offer.label, onUndo = controller::undoLast, onDismiss = { controller.dismissUndo(offer.serial) }) }
            }
        }
        val chips = if (state.turns.isEmpty()) planner.examples.map { it.text to it.isPlan } else state.followUps.map { it.label to false }
        FollowUpChips(
            chips = chips,
            onTap = { i ->
                if (state.turns.isEmpty()) {
                    val example = planner.examples[i]
                    controller.submit(example.text, if (example.isPlan) TurnPath.PLAN else null)
                } else {
                    state.followUps.getOrNull(i)?.let(controller::chip)
                }
                follow = true
            },
        )
        Composer(
            controller = controller,
            state = state,
            bundle = bundle,
            modelOn = model.availability == AssistantAvailability.AVAILABLE,
            request = request?.takeIf { it.detail == null },
            focusInput = focusInput,
            autoFocus = state.turns.isEmpty(),
            onSearch = onSearch,
            onRequestConsumed = onRequestConsumed,
            onSent = {
                follow = true
                scope.launch { list.layoutInfo.totalItemsCount.takeIf { it > 0 }?.let { list.animateScrollToItem(it - 1) } }
            },
        )
    }
}

/** Running late, the recap, gap fillers and skipped items, at the top of the chat (assistant spec §6). */
private fun androidx.compose.foundation.lazy.LazyListScope.liveHints(controller: AssistantController, state: AssistantState) {
    val live = state.live
    live.runningLate?.let { late ->
        item(key = "late") {
            RunningLateCard(late.text, late.state == LateState.CANT_MAKE_IT, late.chips, onChip = { controller.run(it.action, it.label) })
        }
    }
    if (live.recapReady) item(key = "recap") { SummaryCard("recap") }
    if (live.gaps.isNotEmpty() || live.skipped.isNotEmpty()) {
        item(key = "hints") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    }
}

/** Before anything is asked: My schedule's day (the derived plan with walks), else the `my_day` component. */
@Composable
private fun YourDay(plan: Plan?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan != null && !plan.isEmpty) {
            Text("Your Day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PlanTimeline(plan, day = null)
        } else {
            ScreenComponentView(Component("my_day"))
        }
        Text(
            "Ask about the event, or change your schedule: \"add Taco Row at 7\", \"what's on now?\", \"plan my Saturday\".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One turn: the bubble, then the working line or the done line and the blocks (chips go above the composer). */
@Composable
private fun ChatTurn(controller: AssistantController, turn: AssistantTurnUi, stage: String?, last: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turn.input?.let { Bubble(it) }
        if (turn.streaming) {
            WorkingLine(stage)
            return@Column
        }
        turn.status?.let { DoneLine(it) }
        val controls = TurnControls(
            canUndo = turn.applied != null,
            undone = turn.undone,
            onUndo = { controller.undo(turn.id) },
            onApply = { controller.reapply(turn.id) },
            answered = turn.answered,
            onConfirm = { index, yes -> controller.answer(turn.id, index, yes) },
        )
        turn.blocks.forEachIndexed { index, block ->
            if (block !is AssistantBlock.Chips) AssistantBlockView(block, index, controls)
        }
        // Chips of earlier turns stay where they were (only the last turn's move above the composer).
        if (!last) turn.blocks.filterIsInstance<AssistantBlock.Chips>().lastOrNull()?.let { AssistantBlockView(it, turn.blocks.indexOf(it), controls) }
        if (turn.applied != null && turn.applied.name != ActionName.PLAN_EDIT && turn.applied.name != ActionName.PLAN_VERSION) {
            TextButton(onClick = { if (turn.undone) controller.reapply(turn.id) else controller.undo(turn.id) }) { Text(if (turn.undone) "Redo" else "Undo") }
        }
    }
}

@Composable
private fun Bubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 520.dp).padding(start = 48.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** "Searching…" / "Adding Taco Row…" while the turn runs; the text cross-fades between stages. */
@Composable
private fun WorkingLine(stage: String?) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoadingIndicator(Modifier.size(24.dp))
        AnimatedContent(targetState = stage ?: "Thinking…", transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "✓ Added Taco Row". */
@Composable
private fun DoneLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The model still has to download: a small card (Download / Not now) and its progress. */
@Composable
private fun DownloadCard(model: AssistantUiState, onDownload: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Answers on your phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            if (model.availability == AssistantAvailability.DOWNLOADING) {
                Text("Downloading Google's on-device AI…", style = MaterialTheme.typography.bodyMedium)
                val total = model.totalBytes
                val done = model.downloadedBytes
                if (total != null && total > 0 && done != null) LinearWavyProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Text("Ask Attnd works meanwhile; it answers more once this finishes.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "Download Gemini Nano once to ask questions in your own words. Your questions never leave your phone. Until then Ask Attnd understands commands and searches.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                model.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDownload) { Text("Download") }
                    TextButton(onClick = onDismiss) { Text("Not now") }
                }
            }
        }
    }
}

// ── The proposal card and the Undo card ─────────────────────────────────────

/**
 * A pending change to My schedule: its sentence, the changed rows of its day (added, removed, moved; the
 * options of an add / replace each with its own button), Apply / Cancel. Typing "do it", "the second one"
 * or "no" works too.
 */
@Composable
private fun ProposalCard(controller: AssistantController, proposal: PlanProposal, bundle: EventBundle, plan: Plan?, note: String?) {
    val rows = remember(plan, proposal) {
        val day = proposal.day ?: PreviewRows.days(plan, proposal).firstOrNull()
        day?.let { PreviewRows.of(plan, proposal, it, bundle) }.orEmpty()
            .filter { it is PreviewRow.Option || (it is PreviewRow.Item && it.mark != RowMark.NONE) || (it is PreviewRow.Meal && it.mark != RowMark.NONE) }
            .take(MAX_PREVIEW_ROWS)
    }
    val perOption = proposal.options.size > 1 && (proposal.kind == ProposalKind.ADD || proposal.kind == ProposalKind.REPLACE)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.EditCalendar, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(proposal.pill, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Text(proposal.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            rows.forEach { row ->
                when (row) {
                    is PreviewRow.Item -> ChangedRow(bundle, row)
                    is PreviewRow.Meal -> MealRow(bundle, row)
                    is PreviewRow.Option -> OptionRow(bundle, row, proposal, onApply = { controller.applyProposal(row.index) })
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = controller::cancelProposal) { Text("Cancel") }
                when {
                    proposal.kind == ProposalKind.SHARED -> proposal.options.forEachIndexed { i, option ->
                        if (i == 0) Button(onClick = { controller.applyProposal(i) }) { Text(option.label) }
                        else FilledTonalButton(onClick = { controller.applyProposal(i) }) { Text(option.label) }
                    }
                    !perOption -> Button(onClick = { controller.applyProposal(0) }) { Text("Apply") }
                }
            }
        }
    }
}

private const val MAX_PREVIEW_ROWS = 8

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
private fun ChangedRow(bundle: EventBundle, row: PreviewRow.Item) {
    val actions = LocalBlockActions.current ?: return
    val item = bundle.scheduleItem(row.itemId) ?: return
    val zone = bundle.event.zone
    val struck = row.mark == RowMark.REMOVED || row.mark == RowMark.REPLACED
    val badge = row.movedTo?.let { bundle.scheduleItem(it) }?.let { "Moves to ${EventTime.formatTime(it.startTime, zone)}" } ?: markLabel(row.mark)
    val color = markColor(row.mark)
    Row(
        Modifier.fillMaxWidth().clickable { actions.onOpen(EntityRef(RefKind.SCHEDULE, item.id)) }.padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { if (badge != null) contentDescription = "$badge: ${item.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(EventTime.formatTime(item.startTime, zone), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp), color = color)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color, textDecoration = if (struck) TextDecoration.LineThrough else null)
            Text(
                listOfNotNull(badge, bundle.location(item.locationId)?.name ?: item.locationName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MealRow(bundle: EventBundle, row: PreviewRow.Meal) {
    val clock = remember { DateTimeFormatter.ofPattern("h:mm a") }
    val color = markColor(row.mark)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(row.start.atZone(bundle.event.zone).format(clock), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp), color = color)
        Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(markLabel(row.mark), row.slot.kind.label + (bundle.location(row.locationId)?.name?.let { " · $it nearby" } ?: "")).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = color,
            textDecoration = if (row.mark == RowMark.REMOVED) TextDecoration.LineThrough else null,
        )
    }
}

/** A dashed suggested row (an add or a replacement option): tap to apply it. */
@Composable
private fun OptionRow(bundle: EventBundle, row: PreviewRow.Option, proposal: PlanProposal, onApply: () -> Unit) {
    val item = bundle.scheduleItem(row.itemId) ?: return
    val zone = bundle.event.zone
    val outline = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val verb = if (proposal.kind == ProposalKind.REPLACE) "Swap in" else "Add"
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
            .semantics(mergeDescendants = true) { contentDescription = "Option: ${item.name}, $time. Tap to ${verb.lowercase()}." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp), color = outline)
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

/** After Apply: "Schedule updated · Undo" until the next turn. */
@Composable
private fun UndoCard(label: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}

// ── Chips and composer ──────────────────────────────────────────────────────

/** The follow-up chips (the last turn's), or the event's examples before anything is asked. */
@Composable
private fun FollowUpChips(chips: List<Pair<String, Boolean>>, onTap: (Int) -> Unit) {
    if (chips.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEachIndexed { i, (label, _) ->
            AssistChip(onClick = { onTap(i) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

@Composable
private fun Composer(
    controller: AssistantController,
    state: AssistantState,
    bundle: EventBundle,
    modelOn: Boolean,
    request: SearchRequest?,
    focusInput: Int,
    autoFocus: Boolean,
    onSearch: () -> Unit,
    onRequestConsumed: () -> Unit,
    onSent: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submitReport = rememberSearchReporter(text, onSearch, source = "assistant", countResults = { q -> EventSearch.search(bundle, q, Instant.now()).size })
    val busy = state.turns.any { it.streaming }

    fun send(value: String = text, forced: TurnPath? = null) {
        if (value.isBlank() || busy) return
        submitReport()
        controller.submit(value, forced)
        text = ""
        onSent()
    }

    var focusAsked by remember { mutableIntStateOf(0) }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        if (pending.detail != null) return@LaunchedEffect
        when {
            pending.ask && pending.query.isNotBlank() -> {
                controller.submit(pending.query, if (modelOn) TurnPath.ASK else null)
                onSent()
            }
            pending.query.isNotBlank() -> send(pending.query)
            else -> focusAsked++
        }
        onRequestConsumed()
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

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(PlanTabFlow.PLACEHOLDER, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyUp && event.key == Key.Escape -> {
                            if (state.proposal != null) controller.cancelProposal() else text = ""
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed -> {
                            send()
                            true
                        }
                        else -> false
                    }
                },
            )
            IconButton(onClick = { send() }, enabled = text.isNotBlank() && !busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

// ── Wide windows: the last component, large ─────────────────────────────────

@Composable
private fun LastComponentPane(state: AssistantState, plan: Plan?, modifier: Modifier) {
    val block = state.lastComponent
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (block != null) {
            AssistantBlockView(block, 0, TurnControls())
        } else {
            YourDay(plan)
        }
    }
}

/** "Leave now for X · 8 min walk" (or "You can't make X in time") with its chips; also over the tabs. */
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
