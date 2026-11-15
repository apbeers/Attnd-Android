@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.data.AssistantAvailability
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.ItemTiming
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SearchResult
import com.arcu.attnd.ui.AssistantUiState
import com.arcu.attnd.ui.SearchRequest
import com.arcu.attnd.ui.components.DistanceArrow
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.LocalEventActions
import com.arcu.attnd.ui.components.EventInfoCard
import com.arcu.attnd.ui.components.RemoteImage
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSearchReporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/** What the search screen's own back stack holds above the results: an item's detail. */
data class SearchEntry(val kind: SearchKind, val id: Int) {
    fun encode(): String = "${kind.name}:$id"

    companion object {
        fun decodeStack(value: String): List<SearchEntry> = value.split(',').mapNotNull { part ->
            val (kind, id) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val parsedKind = SearchKind.entries.firstOrNull { it.name == kind } ?: return@mapNotNull null
            id.toIntOrNull()?.let { SearchEntry(parsedKind, it) }
        }

        fun encodeStack(stack: List<SearchEntry>): String = stack.joinToString(",") { it.encode() }
    }
}

/**
 * The app-wide search (reachable from every tab): the search FAB ([SearchFab]) expands [state] into
 * this [ExpandedFullScreenSearchBar], with the text field focused and the keyboard up. It searches
 * the local bundle ([EventSearch]); tapped results open their detail on the search screen's own back
 * stack, so Back returns to the results and items whose tab `display.tabs` hides still open. With
 * on-device Gemini Nano available, "Ask" answers questions from the same local data.
 *
 * Always composed while an event is shown (the expanded bar is a dialog that exists only while
 * [state] is expanded), so [request] (from a shortcut or an assistant) can open it with a query and
 * optionally an item.
 */
@Composable
fun EventSearch(
    state: SearchBarState,
    bundle: EventBundle,
    request: SearchRequest?,
    assistant: AssistantUiState,
    onRequestConsumed: () -> Unit,
    onSearch: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
    onCheckAssistant: () -> Unit,
    onDownloadAssistant: () -> Unit,
    onAsk: (String) -> Unit,
    onClearAnswer: () -> Unit,
    onOpenPlan: () -> Unit = {},
) {
    val searchBarState = state
    val textFieldState = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { SearchEntry.decodeStack(stackText) }
    var askMode by rememberSaveable { mutableStateOf(false) }
    val query = textFieldState.text.toString()
    val expanded = searchBarState.currentValue == SearchBarValue.Expanded
    // API-005 `search`, counted exactly like the per-tab search fields (submit or 1.5 s settle).
    val submit = rememberSearchReporter(query, onSearch)
    val assistantUsable = assistant.availability in USABLE
    val fieldFocus = remember { FocusRequester() }

    // Closing the search empties its back stack. Declared before the request effect, which may fill it.
    LaunchedEffect(expanded) {
        if (expanded) {
            onCheckAssistant()
        } else {
            stackText = ""
        }
    }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        textFieldState.setTextAndPlaceCursorAtEnd(pending.query)
        stackText = pending.detail?.let { SearchEntry.encodeStack(listOf(it)) }.orEmpty()
        // An onboarding example question opens Ask with it (and asks right away when the model is ready).
        askMode = pending.ask && assistantUsable
        searchBarState.animateToExpanded()
        if (askMode && pending.query.isNotBlank() && assistant.availability == AssistantAvailability.AVAILABLE) {
            submit()
            onAsk(pending.query)
        }
        // Consumed only once open: clearing it re-keys (cancels) this effect.
        onRequestConsumed()
    }

    val placeholder = if (askMode && assistantUsable) "Ask about ${bundle.event.name}" else "Search ${bundle.event.name}"
    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { text ->
                submit()
                if (askMode && assistantUsable) {
                    stackText = ""
                    onAsk(text)
                }
            },
            modifier = Modifier.focusRequester(fieldFocus),
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                IconButton(onClick = { scope.launch { searchBarState.animateToCollapsed() } }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                }
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { textFieldState.clearText() }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            } else {
                null
            },
        )
    }

    // There is no collapsed bar any more (the FAB opens it), so the expanded bar is the only place
    // the input field lives. Back (system or predictive) collapses it through the dialog itself.
    ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) {
        // Composed once per opening, inside the search dialog's window.
        FocusFieldOnOpen(fieldFocus, showKeyboard = remember { stack.isEmpty() })
        SearchContent(
            bundle = bundle,
            query = query,
            textFieldState = textFieldState,
            stack = stack,
            onPush = { entry -> stackText = SearchEntry.encodeStack(stack + entry) },
            onPop = { stackText = SearchEntry.encodeStack(stack.dropLast(1)) },
            askMode = askMode && assistantUsable,
            assistantUsable = assistantUsable,
            onAskModeChange = { ask ->
                askMode = ask
                if (!ask) onClearAnswer()
            },
            assistant = assistant,
            onDownloadAssistant = onDownloadAssistant,
            onAsk = {
                submit()
                onAsk(query)
            },
            onLocationShown = onLocationShown,
            onScheduleItemShown = onScheduleItemShown,
            onOpenPlan = onOpenPlan,
        )
    }
}

/**
 * Focuses the search field and shows the keyboard as soon as the search opens. The expanded search
 * bar requests focus itself, but in its brand-new dialog window that can happen before the window
 * has input focus, when the IME won't attach; so this waits for the dialog window to be focused,
 * then requests focus and shows the keyboard explicitly. Opened straight onto a detail (a shortcut
 * or assistant link to an item), the keyboard stays hidden instead.
 */
@Composable
private fun FocusFieldOnOpen(focusRequester: FocusRequester, showKeyboard: Boolean) {
    val windowInfo = LocalWindowInfo.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        // One frame, so the field is attached and the search bar's own focus request has run.
        withFrameNanos { }
        if (showKeyboard) {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Not attached (the search closed again within the frame): nothing to focus.
            }
            keyboard?.show()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
}

/**
 * The search entry point: a Material 3 Expressive floating action button with a search icon. It
 * opens the full-screen search ([EventSearch]) with the field focused.
 */
@Composable
fun SearchFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Search, contentDescription = "Search")
    }
}

/**
 * The event's top app bar on every tab: the event logo (`display.logo_url`) and name, and the
 * Options action (Options never goes into the navigation bar). A wavy progress line shows a
 * pull-to-refresh in progress.
 */
@Composable
fun EventTopBar(bundle: EventBundle, isRefreshing: Boolean, onOptions: () -> Unit) {
    val logoUrl = bundle.display.logoUrl
    Column {
        TopAppBar(
            title = { Text(bundle.event.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (logoUrl != null) {
                    // Sized like an icon button, so the title lines up as it would after one.
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        RemoteImage(
                            url = logoUrl,
                            contentDescription = null,
                            maxDimension = 128,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                            placeholder = {},
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = onOptions) { Icon(Icons.Filled.Settings, contentDescription = "Options") }
            },
        )
        if (isRefreshing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
    }
}

private val USABLE = setOf(AssistantAvailability.AVAILABLE, AssistantAvailability.DOWNLOADABLE, AssistantAvailability.DOWNLOADING)

@Composable
private fun SearchContent(
    bundle: EventBundle,
    query: String,
    textFieldState: TextFieldState,
    stack: List<SearchEntry>,
    onPush: (SearchEntry) -> Unit,
    onPop: () -> Unit,
    askMode: Boolean,
    assistantUsable: Boolean,
    onAskModeChange: (Boolean) -> Unit,
    assistant: AssistantUiState,
    onDownloadAssistant: () -> Unit,
    onAsk: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
    onOpenPlan: () -> Unit,
) {
    val now = rememberNow()
    val top = stack.lastOrNull()
    if (top != null) {
        // Higher priority than the search bar's own back handling (registered later): Back pops the detail.
        BackHandler(onBack = onPop)
        SearchDetail(bundle, top, onPop, onPush, onLocationShown, onScheduleItemShown)
        return
    }
    val open: (SearchResult) -> Unit = { onPush(SearchEntry(it.kind, it.id)) }
    Column(Modifier.fillMaxSize()) {
        if (assistantUsable) ModeToggle(askMode, onAskModeChange)
        if (askMode) {
            AskPanel(bundle, query, assistant, now, onDownloadAssistant, onAsk, open, onOpenPlan)
        } else {
            ResultsPanel(bundle, query, now, open) { textFieldState.setTextAndPlaceCursorAtEnd(it) }
        }
    }
}

/** Search | Ask, as a Material 3 Expressive connected button group. */
@Composable
private fun ModeToggle(askMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ToggleButton(
            checked = !askMode,
            onCheckedChange = { onChange(false) },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
            Text("Search")
        }
        ToggleButton(
            checked = askMode,
            onCheckedChange = { onChange(true) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
            Text("Ask")
        }
    }
}

@Composable
private fun ResultsPanel(
    bundle: EventBundle,
    query: String,
    now: Instant,
    onOpen: (SearchResult) -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val hearts = LocalEventActions.current.hearts
    var showHearted by rememberSaveable { mutableStateOf(false) }
    if (query.isBlank() && showHearted) {
        // The "Hearted" entry: the hearted list (not a query).
        val hearted = remember(bundle, hearts, now) { EventSearch.hearted(bundle, hearts, now) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(EventSearch.HEARTED_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showHearted = false }) { Text("Done") }
            }
            if (hearted.isEmpty()) {
                EmptyState("Nothing hearted yet. Tap the heart on a place or schedule item to keep it here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) { resultRows(hearted, bundle, onOpen) }
            }
        }
        return
    }
    if (query.isBlank()) {
        val suggestions = remember(bundle, now) { EventSearch.suggestions(bundle, now) }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Search the schedule, places and updates. Try \"tonight\", \"7pm\" or \"food saturday\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hearts.isEmpty) {
                    SuggestionChip(
                        onClick = { showHearted = true },
                        label = { Text(EventSearch.HEARTED_TITLE) },
                        icon = { Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                suggestions.forEach { suggestion ->
                    SuggestionChip(onClick = { onSuggestion(suggestion.query) }, label = { Text(suggestion.label) })
                }
            }
        }
        return
    }
    val results = remember(bundle, query, now) { EventSearch.search(bundle, query, now) }
    if (results.isEmpty()) {
        EmptyState("Nothing in this event matches \"${query.trim()}\".")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        resultRows(results, bundle, onOpen)
    }
}

private fun LazyListScope.resultRows(results: List<SearchResult>, bundle: EventBundle, onOpen: (SearchResult) -> Unit) {
    items(results, key = { "${it.kind}-${it.id}" }) { result ->
        SearchResultRow(result, bundle) { onOpen(result) }
    }
}

/** Kind icon; title with the matched part emphasised; one line of secondary info; Now/Next/Ended. */
@Composable
private fun SearchResultRow(result: SearchResult, bundle: EventBundle, onClick: () -> Unit) {
    val detail = remember(result, bundle) { EventSearch.detail(result, bundle) }
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    val timing = result.timing
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(result.kind.icon, contentDescription = result.kind.label) },
        supportingContent = if (detail.isNotEmpty()) {
            { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = if (timing != null || result.kind == SearchKind.LOCATION || result.kind == SearchKind.SCHEDULE) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (timing != null) {
                        Text(
                            timing.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (timing == ItemTiming.ENDED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (result.kind == SearchKind.LOCATION) DistanceArrow(bundle.location(result.id)?.coordinate)
                    if (result.kind == SearchKind.LOCATION || result.kind == SearchKind.SCHEDULE) {
                        HeartButton(result.kind, result.id, name = result.title)
                    }
                }
            }
        } else {
            null
        },
    ) {
        Text(highlighted(result.title, result.titleHighlights, highlight), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (range in ranges) {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(style, start, end)
    }
}

private val SearchKind.icon: ImageVector
    get() = when (this) {
        SearchKind.SCHEDULE -> Icons.Filled.CalendarMonth
        SearchKind.LOCATION -> Icons.Filled.Place
        SearchKind.NOTIFICATION -> Icons.Filled.Notifications
        SearchKind.EVENT -> Icons.Filled.Info
    }

private val SearchKind.label: String
    get() = when (this) {
        SearchKind.SCHEDULE -> "Schedule"
        SearchKind.LOCATION -> "Location"
        SearchKind.NOTIFICATION -> "Update"
        SearchKind.EVENT -> "Event"
    }

/**
 * "Ask about this event": Gemini Nano on the device answers from the local bundle. Shown only when the
 * model is available or can be downloaded; otherwise the search screen is a plain search.
 */
@Composable
private fun AskPanel(
    bundle: EventBundle,
    query: String,
    assistant: AssistantUiState,
    now: Instant,
    onDownload: () -> Unit,
    onAsk: () -> Unit,
    onOpen: (SearchResult) -> Unit,
    onOpenPlan: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (assistant.availability) {
            AssistantAvailability.DOWNLOADABLE -> AssistantCard {
                Text("Answers on your phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Download Google's on-device AI model (Gemini Nano) once to ask questions about this event. Your questions never leave your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                assistant.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FilledTonalButton(onClick = onDownload) { Text("Download") }
            }
            AssistantAvailability.DOWNLOADING -> AssistantCard {
                Text("Downloading on-device AI…", style = MaterialTheme.typography.titleMedium)
                val total = assistant.totalBytes
                val done = assistant.downloadedBytes
                if (total != null && total > 0 && done != null) {
                    LinearWavyProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("You can keep searching meanwhile.", style = MaterialTheme.typography.bodySmall)
            }
            else -> when {
                assistant.isAnswering -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContainedLoadingIndicator()
                        Text("Thinking on your phone…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                assistant.answer != null -> {
                    AssistantCard {
                        Text(assistant.answer, style = MaterialTheme.typography.bodyLarge)
                        if (assistant.madePlan) FilledTonalButton(onClick = onOpenPlan) { Text("Open My plan") }
                        Text(
                            "Answered on your phone from this event's info. AI can make mistakes; check the details.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Sources("From the event", assistant.sources, bundle, onOpen)
                }
                assistant.error != null -> {
                    Text(assistant.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    Sources("Matches", assistant.sources, bundle, onOpen)
                }
                else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Ask a question like \"Where can I get coffee?\" or \"What's on the main stage tonight?\", or ask for a plan " +
                            "(\"Plan my afternoon around live music\"), then press search. " +
                            "Answers come only from this event's info on your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (query.isNotBlank()) TextButton(onClick = onAsk) { Text("Ask") }
                }
            }
        }
        if (assistant.availability != AssistantAvailability.AVAILABLE && query.isNotBlank()) {
            // Not ready yet: plain search results meanwhile.
            val results = remember(bundle, query, now) { EventSearch.search(bundle, query, now) }
            Sources("Search results", results, bundle, onOpen)
        }
    }
}

@Composable
private fun AssistantCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Sources(title: String, results: List<SearchResult>, bundle: EventBundle, onOpen: (SearchResult) -> Unit) {
    if (results.isEmpty()) return
    SectionHeader(title)
    results.forEach { result -> SearchResultRow(result, bundle) { onOpen(result) } }
}

/** An item opened from the results, on the search screen's own back stack. */
@Composable
private fun SearchDetail(
    bundle: EventBundle,
    entry: SearchEntry,
    onBack: () -> Unit,
    onPush: (SearchEntry) -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to results") }
            Text("Results", style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider()
        when (entry.kind) {
            SearchKind.SCHEDULE -> {
                val item = bundle.scheduleItem(entry.id)
                if (item == null) {
                    EmptyState("This item is no longer on the schedule.")
                } else {
                    LaunchedEffect(item.id) { onScheduleItemShown(item.id) }
                    ScheduleItemDetail(bundle, item, onBack = null, onLocationClick = { onPush(SearchEntry(SearchKind.LOCATION, it)) })
                }
            }
            SearchKind.LOCATION -> {
                val location = bundle.location(entry.id)
                if (location == null) {
                    EmptyState("This location is no longer listed.")
                } else {
                    LaunchedEffect(location.id) { onLocationShown(location.id) }
                    LocationDetail(bundle, location, onBack = null, onScheduleItemClick = { onPush(SearchEntry(SearchKind.SCHEDULE, it)) })
                }
            }
            SearchKind.NOTIFICATION -> {
                val notification = bundle.notifications.firstOrNull { it.id == entry.id }
                if (notification == null) {
                    EmptyState("This update is no longer available.")
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (notification.isUrgent) {
                            Text("Urgent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        Text(notification.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            EventTime.formatDateTime(notification.sendTime, bundle.event.zone),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notification.body.isNotBlank()) Text(notification.body, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            SearchKind.EVENT -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                EventInfoCard(bundle.event)
            }
        }
    }
}
