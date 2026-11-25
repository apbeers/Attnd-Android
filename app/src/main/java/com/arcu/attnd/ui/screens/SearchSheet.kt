@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.arcu.attnd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventSearch
import com.arcu.attnd.domain.MyScheduleText
import com.arcu.attnd.ui.SearchList
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.SearchKind
import com.arcu.attnd.domain.SearchQuery
import com.arcu.attnd.domain.SearchResult
import com.arcu.attnd.ui.SearchRequest
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.HeartButton
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSearchReporter
import kotlinx.coroutines.delay

/**
 * The search sheet (iOS `EventSearchSheet`; no AI): a full-height sheet with its own back stack and a
 * Close button. The field is focused with the keyboard up on open, unless a request opened it straight
 * onto an item. One search across schedule items, places, updates and the event (`EventSearch`, the
 * shared rules); a question that matches nothing as typed shows the results for its keywords. Empty:
 * suggestions (Liked once anything is liked, Happening now, Up next, categories). A result pushes its
 * detail on the sheet's stack; Back pops, then closes.
 */
@Composable
fun SearchSheet(
    bundle: EventBundle,
    hearts: Hearts,
    request: SearchRequest?,
    /** My schedule's places saved without a time (Home's "3 saved places" row opens them). */
    savedPlaces: Set<Int> = emptySet(),
    onRequestConsumed: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onLocationShown: (Int) -> Unit,
    onScheduleItemShown: (Int) -> Unit,
) {
    val sheet = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var query by rememberSaveable { mutableStateOf("") }
    var stackText by rememberSaveable { mutableStateOf("") }
    var listName by rememberSaveable { mutableStateOf<String?>(null) }
    val list = listName?.let { name -> SearchList.entries.firstOrNull { it.name == name } }
    val showLiked = list != null
    val stack = remember(stackText) { if (stackText.isEmpty()) emptyList() else SearchEntry.decodeStack(stackText) }
    fun push(entry: SearchEntry) {
        AppLog.debug(LogCategory.SEARCH, LogEvent.SEARCH_OPEN, "Search result opened", mapOf("kind" to entry.kind.logName, "id" to entry.id))
        stackText = SearchEntry.encodeStack(stack + entry)
    }
    fun pop() {
        stackText = SearchEntry.encodeStack(stack.dropLast(1))
    }
    // Opened onto an item (a link to a hidden tab) the keyboard stays hidden.
    var focusField by remember { mutableStateOf(request?.detail == null) }
    LaunchedEffect(request?.serial) {
        val r = request ?: return@LaunchedEffect
        query = r.query
        listName = r.list?.name
        if (r.detail != null) {
            focusField = false
            stackText = SearchEntry.encodeStack(listOf(r.detail))
        }
        onRequestConsumed()
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, dragHandle = null) {
        BackHandler {
            when {
                stack.isNotEmpty() -> pop()
                showLiked -> listName = null
                else -> onClose()
            }
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty() || showLiked) {
                    IconButton(onClick = { if (stack.isNotEmpty()) pop() else listName = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    when {
                        stack.isNotEmpty() || list == null -> "Search"
                        list == SearchList.SAVED_PLACES -> MyScheduleText.SAVED_PLACES
                        else -> EventSearch.HEARTED_TITLE
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            val top = stack.lastOrNull()
            if (top != null) {
                Box(Modifier.fillMaxSize()) {
                    SearchDetail(
                        bundle = bundle,
                        entry = top,
                        onBack = ::pop,
                        onPush = ::push,
                        onLocationShown = onLocationShown,
                        onScheduleItemShown = onScheduleItemShown,
                        showHeader = false,
                    )
                }
                return@Column
            }
            if (!showLiked) SearchInput(query, { query = it }, focus = focusField, onSearch = onSearch)
            val listHearts = if (list == SearchList.SAVED_PLACES) Hearts(locations = savedPlaces) else hearts
            SearchResults(bundle, listHearts, if (showLiked) null else query, showLiked, onLiked = { listName = SearchList.MY_SCHEDULE.name }, onSuggestion = { query = it }, onOpen = ::push)
        }
    }
}

private val SearchKind.logName: String
    get() = when (this) {
        SearchKind.SCHEDULE -> "schedule"
        SearchKind.LOCATION -> "location"
        SearchKind.NOTIFICATION -> "notification"
        SearchKind.EVENT -> "event"
    }

@Composable
private fun SearchInput(query: String, onQueryChange: (String) -> Unit, focus: Boolean, onSearch: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val submit = rememberSearchReporter(query, onSearch, source = "sheet")
    LaunchedEffect(focus) {
        if (!focus) return@LaunchedEffect
        // The sheet animates in first; focusing too early drops the keyboard request.
        delay(150)
        runCatching { focusRequester.requestFocus() }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search the event") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun SearchResults(
    bundle: EventBundle,
    hearts: Hearts,
    query: String?,
    showLiked: Boolean,
    onLiked: () -> Unit,
    onSuggestion: (String) -> Unit,
    onOpen: (SearchEntry) -> Unit,
) {
    val now = rememberNow()
    var settled by remember { mutableStateOf(query.orEmpty()) }
    LaunchedEffect(query) {
        delay(150)
        settled = query.orEmpty()
    }
    val results = remember(bundle, hearts, settled, showLiked, now) {
        when {
            showLiked -> EventSearch.hearted(bundle, hearts, now)
            settled.isBlank() -> emptyList()
            // A question that matches nothing as typed searches its keywords (iOS `keywords(fromQuestion:)`).
            else -> EventSearch.search(bundle, settled, now).ifEmpty {
                val keywords = SearchQuery.keywords(settled)
                if (keywords != settled) EventSearch.search(bundle, keywords, now) else emptyList()
            }
        }
    }
    if (!showLiked && settled.isBlank()) {
        val suggestions = remember(bundle, now) { EventSearch.suggestions(bundle, now) }
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hearts.isEmpty) {
                AssistChip(onClick = onLiked, label = { Text(EventSearch.HEARTED_TITLE) }, leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) })
            }
            suggestions.forEach { suggestion -> AssistChip(onClick = { onSuggestion(suggestion.query) }, label = { Text(suggestion.label) }) }
        }
        return
    }
    if (results.isEmpty()) {
        EmptyState(if (showLiked) MyScheduleText.NOTHING_YET else "Nothing matches “${settled.trim()}”.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { "${it.kind}-${it.id}" }) { result ->
            ResultRow(bundle, result, onClick = { onOpen(SearchEntry(result.kind, result.id)) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ResultRow(bundle: EventBundle, result: SearchResult, onClick: () -> Unit) {
    val icon: ImageVector = when (result.kind) {
        SearchKind.SCHEDULE -> Icons.Filled.CalendarMonth
        SearchKind.LOCATION -> Icons.Filled.Place
        SearchKind.NOTIFICATION -> Icons.Filled.Campaign
        SearchKind.EVENT -> Icons.Filled.Event
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = {
            val line = listOfNotNull(EventSearch.detail(result, bundle).takeIf { it.isNotEmpty() }, result.timing?.label).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (result.kind == SearchKind.SCHEDULE || result.kind == SearchKind.LOCATION) HeartButton(result.kind, result.id, name = result.title)
        },
    ) {
        Text(highlighted(result), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The title with the matched ranges in bold primary. */
@Composable
private fun highlighted(result: SearchResult): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(result.title)
        result.titleHighlights.forEach { range ->
            val start = range.first.coerceIn(0, result.title.length)
            val end = (range.last + 1).coerceIn(start, result.title.length)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primary), start, end)
        }
    }
}
