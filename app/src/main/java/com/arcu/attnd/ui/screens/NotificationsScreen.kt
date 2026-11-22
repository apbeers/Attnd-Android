package com.arcu.attnd.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.EventNotification
import com.arcu.attnd.domain.EventTime
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.UpdatesLayout
import com.arcu.attnd.ui.components.SectionHeader
import com.arcu.attnd.ui.components.rememberLayoutSections
import com.arcu.attnd.ui.sections.FaqSection
import com.arcu.attnd.ui.sections.TextSection
import java.time.ZoneId
import com.arcu.attnd.ui.components.EmptyState
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.rememberNow
import java.time.Duration

/**
 * Organizer notifications, newest first (the server already sorts them), or in the organizer's groups
 * (API-027 `updates_tab`, [UpdatesLayout.groups]). [highlightId] (from search
 * or an assistant) scrolls to that notification and briefly highlights it; [onHighlightShown] is
 * called once it is on screen.
 */
@Composable
fun NotificationsScreen(
    bundle: EventBundle,
    isRefreshing: Boolean,
    lastSyncedAt: Long?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    highlightId: Int? = null,
    onHighlightShown: () -> Unit = {},
) {
    val zone = bundle.event.zone
    val now = rememberNow()
    val listState = rememberLazyListState()
    val highlight = remember { Animatable(0f) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    val currentOnHighlightShown by rememberUpdatedState(onHighlightShown)
    val showStale = lastSyncedAt != null && Duration.ofMillis(now.toEpochMilli() - lastSyncedAt).toMinutes() >= 5

    // API-027 `updates_tab`: the organizer's sections (urgent first, pinned, latest, earlier, FAQ / getting
    // there); every update still appears once. No layout (and screenshot mode): newest first, as sent.
    val layoutSections = rememberLayoutSections(LayoutScreen.UPDATES_TAB, bundle)
    val context = bundle.event.context
    val groups = remember(layoutSections, bundle.notifications, now) {
        UpdatesLayout.groups(layoutSections, bundle.notifications, now).filter { group ->
            when (group.type) {
                "faq" -> context?.faq?.isNotEmpty() == true
                "getting_there" -> context?.gettingThere?.isNotBlank() == true
                else -> true
            }
        }
    }
    // The list's keys in order, so a jump finds its row.
    val keys = remember(groups, bundle.notifications, showStale) {
        buildList {
            if (showStale) add("stale")
            if (groups.isEmpty()) {
                bundle.notifications.forEach { add("n-${it.id}") }
            } else {
                groups.forEach { group ->
                    when (group.type) {
                        "faq", "getting_there" -> add("c-${group.type}")
                        else -> {
                            add("h-${group.type}")
                            group.ids.forEach { add("n-$it") }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(highlightId, keys) {
        val id = highlightId ?: return@LaunchedEffect
        val index = keys.indexOf("n-$id")
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index)
        currentOnHighlightShown()
        highlighted = id
        highlight.snapTo(1f)
        highlight.animateTo(0f, tween(durationMillis = HIGHLIGHT_MS, delayMillis = HIGHLIGHT_HOLD_MS))
        highlighted = null
    }

    RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        if (bundle.notifications.isEmpty() && groups.isEmpty()) {
            EmptyState("No announcements yet. Organizer updates will appear here.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (showStale) {
                    val minutes = Duration.ofMillis(now.toEpochMilli() - lastSyncedAt).toMinutes()
                    item(key = "stale") {
                        Text(
                            "Updated $minutes minutes ago. Pull to refresh.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                val row: @Composable (EventNotification) -> Unit = { notification ->
                    NotificationRow(notification, zone, notification.id == highlighted, highlight.value)
                    HorizontalDivider()
                }
                if (groups.isEmpty()) {
                    items(bundle.notifications, key = { "n-${it.id}" }) { row(it) }
                } else {
                    val byId = bundle.notifications.associateBy { it.id }
                    groups.forEach { group ->
                        // FAQ / getting there are cards with their own title.
                        if (group.type != "faq" && group.type != "getting_there") item(key = "h-${group.type}") { SectionHeader(group.title) }
                        when (group.type) {
                            "faq" -> item(key = "c-faq") {
                                FaqSection(context?.faq.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), title = group.title)
                            }
                            "getting_there" -> item(key = "c-getting_there") {
                                TextSection(group.title, context?.gettingThere.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            else -> items(group.ids.mapNotNull { byId[it] }, key = { "n-${it.id}" }) { row(it) }
                        }
                    }
                }
            }
        }
    }
}

/** One update: urgent ones (API-011: cancellations, safety) stand out in the error colours. */
@Composable
private fun NotificationRow(notification: EventNotification, zone: ZoneId, highlighted: Boolean, highlight: Float) {
    val base = if (notification.isUrgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val colors = when {
        highlighted -> ListItemDefaults.colors(
            containerColor = lerp(base, MaterialTheme.colorScheme.primaryContainer, highlight),
        )
        notification.isUrgent -> ListItemDefaults.colors(
            containerColor = base,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            supportingContentColor = MaterialTheme.colorScheme.onErrorContainer,
            overlineContentColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingContentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> ListItemDefaults.colors()
    }
    ListItem(
        overlineContent = if (notification.isUrgent) {
            { Text("Urgent", fontWeight = FontWeight.Bold) }
        } else {
            null
        },
        leadingContent = if (notification.isUrgent) {
            { Icon(Icons.Filled.Warning, contentDescription = "Urgent") }
        } else {
            null
        },
        supportingContent = {
            Column {
                if (notification.body.isNotBlank()) Text(notification.body)
                Text(
                    EventTime.formatDateTime(notification.sendTime, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        colors = colors,
    ) {
        Text(notification.title)
    }
}

private const val HIGHLIGHT_HOLD_MS = 800
private const val HIGHLIGHT_MS = 1_200
