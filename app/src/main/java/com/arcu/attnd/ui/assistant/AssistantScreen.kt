@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.domain.ActionName
import com.arcu.attnd.domain.ActionSource
import com.arcu.attnd.domain.AssistantAction
import com.arcu.attnd.domain.AssistantRouter
import com.arcu.attnd.domain.EntityRef
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.LateState
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanExample
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.domain.RefKind
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.TurnPath
import com.arcu.attnd.ui.AssistantUiState
import com.arcu.attnd.ui.PlanUiState
import com.arcu.attnd.ui.SearchRequest
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSearchReporter
import com.arcu.attnd.ui.screens.SearchDetail
import com.arcu.attnd.ui.screens.SearchEntry
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The event assistant (assistant spec §8): the floating search button's target on every tab. Top: the
 * pinned plan (collapsible; live hints; "Make a plan" with examples when the planner is available).
 * Middle: the conversation of blocks. Bottom: one input bar (focused on open, keyboard up) with the
 * intent chip ("Searching…" / "Asking…" / "Editing plan…", tap to switch). Typing shows live search
 * results; send runs the full turn. Without on-device AI the same screen works with the rule-based
 * router (plain search, commands, likes, navigation, plan edits of an existing plan).
 * Cards open their detail on the screen's own back stack (so hidden tabs work); Back pops it, then closes.
 */
@Composable
fun AssistantScreen(
    controller: AssistantController,
    bundle: EventBundle,
    model: AssistantUiState,
    planner: PlanUiState,
    request: SearchRequest?,
    actions: BlockActions,
    onRequestConsumed: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onDownloadModel: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
    onOpenPlanTab: () -> Unit,
    onReplan: () -> Unit,
    onKeepChanged: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { SearchEntry.decodeStack(stackText) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val modelOn = model.availability == AssistantAvailability.AVAILABLE
    val submitReport = rememberSearchReporter(text, onSearch, source = "assistant", countResults = { q -> EventSearch.search(bundle, q, Instant.now()).size })

    fun push(ref: EntityRef) {
        val kind = when (ref.kind) {
            RefKind.SCHEDULE -> SearchKind.SCHEDULE
            RefKind.LOCATION -> SearchKind.LOCATION
            RefKind.UPDATE -> SearchKind.NOTIFICATION
        }
        stackText = SearchEntry.encodeStack(stack + SearchEntry(kind, ref.id))
        keyboard?.hide()
    }

    fun send(value: String = text, forced: TurnPath? = state.forced) {
        if (value.isBlank()) return
        submitReport()
        controller.submit(value, forced)
        text = ""
    }

    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        stackText = pending.detail?.let { SearchEntry.encodeStack(listOf(it)) }.orEmpty()
        when {
            pending.about != null -> controller.askAbout(pending.about)
            pending.ask && pending.query.isNotBlank() -> controller.submit(pending.query, if (modelOn) TurnPath.ASK else null)
            else -> text = pending.query
        }
        onRequestConsumed()
    }
    LaunchedEffect(state.serial) {
        // Items: the pinned plan, then the turns (and the busy row): scroll to the newest.
        if (state.turns.isNotEmpty() && text.isBlank()) list.animateScrollToItem(state.turns.size + if (state.busy != null) 1 else 0)
    }
    LaunchedEffect(Unit) {
        if (stack.isEmpty() && request?.detail == null) {
            delay(120)
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
            }
            keyboard?.show()
        }
    }

    BackHandler {
        if (stack.isNotEmpty()) stackText = SearchEntry.encodeStack(stack.dropLast(1)) else onClose()
    }

    val wrapped = remember(actions, stack) {
        BlockActions(
            bundle = actions.bundle, plan = actions.plan, hearts = actions.hearts,
            onOpen = ::push,
            onChip = actions.onChip, onOpenTab = actions.onOpenTab, onShowOnMap = actions.onShowOnMap,
            onDirections = actions.onDirections, onShare = actions.onShare, shareLink = actions.shareLink,
            onCalendar = actions.onCalendar, onSetting = actions.onSetting, settingOn = actions.settingOn,
            onCheck = actions.onCheck, onUseVersion = actions.onUseVersion, onOpenFlyer = actions.onOpenFlyer,
            onOpenPlan = actions.onOpenPlan,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalBlockActions provides wrapped) {
        Scaffold(
            topBar = {
                var menu by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text(if (modelOn) "Assistant" else "Search", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { if (stack.isNotEmpty()) stackText = SearchEntry.encodeStack(stack.dropLast(1)) else onClose() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (stack.isNotEmpty()) "Back to the conversation" else "Close")
                        }
                    },
                    actions = {
                        if (state.turns.isNotEmpty()) {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Clear conversation") }, onClick = { menu = false; controller.clearConversation() })
                            }
                        }
                    },
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                val top = stack.lastOrNull()
                if (top != null) {
                    Box(Modifier.weight(1f)) {
                        SearchDetail(bundle, top, onBack = { stackText = SearchEntry.encodeStack(stack.dropLast(1)) }, onPush = { stackText = SearchEntry.encodeStack(stack + it) }, onLocationShown, onScheduleItemShown, showHeader = false)
                    }
                    return@Column
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "plan") {
                        PinnedPlan(controller, planner, state, onExample = { send(it.text, TurnPath.PLAN) }, onOpenPlanTab = onOpenPlanTab, onReplan = onReplan, onKeepChanged = onKeepChanged)
                    }
                    if (text.isNotBlank()) {
                        item(key = "live") { LiveResults(bundle, text, onOpen = ::push) }
                    } else {
                        if (state.turns.isEmpty()) {
                            item(key = "empty") { EmptyAssistant(controller, model, planner, onDownloadModel, onSend = { send(it) }) }
                        }
                        items(state.turns, key = { it.id }) { turn -> TurnView(controller, turn) }
                        if (state.busy != null) {
                            item(key = "busy") { Busy(state.busy!!) }
                        }
                    }
                }
                InputBar(
                    text = text,
                    onText = { text = it },
                    controller = controller,
                    forced = state.forced,
                    modelOn = modelOn,
                    placeholder = if (modelOn) "Ask, search or change your plan" else "Search ${bundle.event.name}",
                    focus = focus,
                    onSend = { send() },
                )
            }
        }
        state.calendar?.let { entries -> CalendarSheet(entries, controller) }
    }
}


@Composable
private fun TurnView(controller: AssistantController, turn: AssistantTurnUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turn.input?.let { input ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(shape = BubbleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.widthIn(max = 320.dp)) {
                    Text(input, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        val controls = TurnControls(
            canUndo = turn.applied != null && turn.applied.name in UNDO_IN_DIFF,
            undone = turn.undone,
            onUndo = { controller.undo(turn.id) },
            onApply = { controller.reapply(turn.id) },
            answered = turn.answered,
            onConfirm = { index, yes -> controller.answer(turn.id, index, yes) },
        )
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            turn.blocks.forEachIndexed { index, block -> AssistantBlockView(block, index, controls) }
            if (turn.streaming && turn.blocks.isEmpty()) Busy(TurnPath.ASK)
            if (turn.applied != null && turn.applied.name !in UNDO_IN_DIFF) {
                TextButton(onClick = { if (turn.undone) controller.reapply(turn.id) else controller.undo(turn.id) }) { Text(if (turn.undone) "Redo" else "Undo") }
            }
            turn.shared?.takeIf { !it.handled }?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.useShared(turn.id, merge = false) }) { Text("Use this plan") }
                    OutlinedButton(onClick = { controller.useShared(turn.id, merge = true) }) { Text("Merge with mine") }
                }
            }
        }
    }
}

private val UNDO_IN_DIFF = setOf(ActionName.PLAN_EDIT, ActionName.PLAN_VERSION)

@Composable
private fun Busy(path: TurnPath) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        LoadingIndicator(Modifier.size(32.dp))
        Text(
            when (path) {
                TurnPath.ASK -> "Thinking on your phone…"
                TurnPath.PLAN -> "Planning on your phone…"
                TurnPath.SEARCH -> "Searching…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Live search results while typing (nothing is sent until Enter). */
@Composable
private fun LiveResults(bundle: EventBundle, query: String, onOpen: (EntityRef) -> Unit) {
    var settled by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        delay(200)
        settled = query
    }
    val now = rememberNow()
    val results = remember(bundle, settled, now) { EventSearch.search(bundle, settled, now) }
    Column(Modifier.fillMaxWidth()) {
        if (results.isEmpty()) {
            Text("Nothing matches yet. Press send to ask.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        results.take(20).forEachIndexed { i, result ->
            if (i > 0) HorizontalDivider()
            val ref = EntityRef.of(result)
            if (ref != null) {
                EntityRow(ref)
            } else {
                ListItem(supportingContent = { Text(EventSearch.detail(result, bundle)) }) { Text(result.title) }
            }
        }
    }
}

/** The empty conversation: suggestion chips and, when it can be downloaded, the on-device model card. */
@Composable
private fun EmptyAssistant(controller: AssistantController, model: AssistantUiState, planner: PlanUiState, onDownload: () -> Unit, onSend: (String) -> Unit) {
    val ctx = remember(planner.plan) { controller.context() } ?: return
    val chips = remember(ctx) { AssistantRouter.suggestionChips(ctx) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (model.availability == AssistantAvailability.AVAILABLE) "Ask anything about the event, search it, or change your plan. Everything stays on your phone."
            else "Search the schedule, places and updates. Try \"tonight\", \"food near me\" or \"like the main stage\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { chip ->
                AssistChip(
                    onClick = { controller.chip(chip) },
                    label = { Text(chip.label) },
                    leadingIcon = if (chip.action.name == ActionName.LIKED) ({ Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                )
            }
            planner.examples.filter { !it.isPlan }.forEach { example ->
                AssistChip(onClick = { onSend(example.text) }, label = { Text(example.text) })
            }
        }
        when (model.availability) {
            AssistantAvailability.DOWNLOADABLE -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Answers on your phone", style = MaterialTheme.typography.titleMedium)
                    Text("Download Google's on-device AI model (Gemini Nano) once to ask questions and plan your day. Your questions never leave your phone.", style = MaterialTheme.typography.bodyMedium)
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
        if (model.availability == AssistantAvailability.AVAILABLE) {
            Text("Answers are made on your phone from this event's info. AI can make mistakes; check the details.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The pinned plan at the top: collapsible; running late, organizer changes, skipped items, gap
 * fillers and the day's recap from the live hints; "Make a plan" with examples when there is no plan
 * and the planner (on-device AI) is available.
 */
@Composable
private fun PinnedPlan(
    controller: AssistantController,
    planner: PlanUiState,
    state: AssistantState,
    onExample: (PlanExample) -> Unit,
    onOpenPlanTab: () -> Unit,
    onReplan: () -> Unit,
    onKeepChanged: () -> Unit,
) {
    val actions = LocalBlockActions.current ?: return
    val plan: Plan? = planner.plan?.takeIf { !it.isEmpty }
    if (plan == null) {
        if (!planner.available) return
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text("Make a plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("Say what you'd like below, or start from one of these. Your likes and the walking times are included.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    planner.examples.filter { it.isPlan }.forEach { example -> AssistChip(onClick = { onExample(example) }, label = { Text(example.text) }) }
                }
            }
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val bundle = actions.bundle
    val live = state.live
    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.EditCalendar, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("My plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(Planner.summary(plan, bundle.event.zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Collapse plan" else "Expand plan")
            }
            live.runningLate?.let { late -> RunningLateCard(late.text, late.state == LateState.CANT_MAKE_IT, late.chips, onChip = { controller.run(it.action, it.label, ActionSource.MANUAL) }) }
            if (!expanded) return@Column
            if (live.changed.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your plan changed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            live.changed.joinToString(", ") { id -> bundle.scheduleItem(id)?.name ?: "an item that was removed" } + " changed since you planned.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onReplan) { Text("Re-plan") }
                            TextButton(onClick = onKeepChanged) { Text("Keep as is") }
                        }
                    }
                }
            }
            if (live.recapReady) SummaryCard("recap")
            val now = rememberNow()
            val upcoming = remember(plan, now) { plan.items.filter { it.end.isAfter(now) }.take(3) }
            upcoming.forEach { EntityRow(EntityRef(RefKind.SCHEDULE, it.itemId)) }
            val hints = live.gaps.map { it.text to it.chip } + live.skipped.map { it.label to it }
            if (hints.isNotEmpty()) {
                live.gaps.forEach { gap ->
                    Text(gap.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    live.gaps.forEach { gap -> AssistChip(onClick = { controller.run(gap.chip.action, gap.chip.label, ActionSource.MANUAL) }, label = { Text(gap.chip.label) }) }
                    live.skipped.forEach { chip ->
                        InputChip(
                            selected = false,
                            onClick = { controller.run(chip.action, chip.label, ActionSource.MANUAL) },
                            label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp).clickable { controller.dismissSkipped(chip) })
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { controller.run(AssistantAction(ActionName.PLAN), "Show my plan") }) { Text("Show full plan") }
                TextButton(onClick = onOpenPlanTab) { Text("Open in Schedule") }
            }
        }
    }
}

/** "Leave now for X · 8 min walk" (or "You can't make X in time") with its chips. */
@Composable
fun RunningLateCard(text: String, cantMake: Boolean, chips: List<com.arcu.attnd.domain.Chip>, onChip: (com.arcu.attnd.domain.Chip) -> Unit, modifier: Modifier = Modifier) {
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
    placeholder: String,
    focus: FocusRequester,
    onSend: () -> Unit,
) {
    var predicted by remember { mutableStateOf(TurnPath.SEARCH) }
    LaunchedEffect(text, forced) {
        delay(250)
        predicted = controller.predict(text)
    }
    val shown = forced ?: predicted
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    trailingIcon = if (text.isNotEmpty()) {
                        { IconButton(onClick = { onText("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") } }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** `add_to_calendar` for the plan: one "Add" per item (each opens the calendar app's insert screen). */
@Composable
private fun CalendarSheet(entries: List<com.arcu.attnd.domain.CalendarEntry>, controller: AssistantController) {
    val actions = LocalBlockActions.current ?: return
    val zone = actions.bundle.event.zone
    ModalBottomSheet(onDismissRequest = controller::dismissCalendar) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Add to Calendar", style = MaterialTheme.typography.titleLarge)
            Text("Each item opens your calendar app, ready to save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEach { entry ->
                ListItem(
                    supportingContent = {
                        Text(
                            com.arcu.attnd.domain.EventTime.formatDayTime(entry.start.atZone(zone).toOffsetDateTime(), zone) + if (entry.location.isNotBlank()) " · ${entry.location}" else "",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = { TextButton(onClick = { controller.addToCalendar(entry) }) { Text("Add") } },
                ) { Text(entry.title) }
            }
        }
    }
}
