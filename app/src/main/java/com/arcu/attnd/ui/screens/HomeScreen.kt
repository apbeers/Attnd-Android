package com.arcu.attnd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.ComponentCatalogue
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.HomeColumnWidths
import com.arcu.attnd.domain.HomeColumns
import com.arcu.attnd.domain.LayoutScreen
import com.arcu.attnd.domain.LayoutSection
import com.arcu.attnd.domain.ScreenFacts
import com.arcu.attnd.ui.assistant.BlockActions
import com.arcu.attnd.ui.assistant.LocalBlockActions
import com.arcu.attnd.ui.components.ComponentHost
import com.arcu.attnd.ui.components.ComponentView
import com.arcu.attnd.ui.components.LocalComponentHost
import com.arcu.attnd.ui.components.RefreshBox
import com.arcu.attnd.ui.components.rememberNow
import com.arcu.attnd.ui.components.rememberSections

/**
 * The Home tab (iOS `HomeTab`): first and the default outside screenshot mode, no top app bar, nothing but
 * the `home` layout's sections (API-024; the built-in one: header, urgent, countdown, Up next in my
 * schedule, map, updates, FAQ, getting there, about, website) drawn by the one renderer
 * ([ComponentView]). Taps open the item on its tab ([actions]'s `onOpen`). From a 700 dp container: the
 * full-width parts, then two independent columns ([HomeColumns], widths [HomeColumnWidths]).
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
    CompositionLocalProvider(LocalBlockActions provides actions, LocalComponentHost provides host) {
        RefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ) {
                val width = maxWidth.value.toDouble()
                if (width >= TWO_COLUMN_WIDTH) {
                    TwoColumns(sections, width)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(sections, key = { it.type }) { section -> Section(section) }
                    }
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
private fun TwoColumns(sections: List<LayoutSection>, width: Double) {
    val wide = sections.filter { it.type in FULL_WIDTH }
    val columns = HomeColumns.split(sections.filter { it.type !in FULL_WIDTH })
    // Phase 10a adds the reported fold; until then the columns meet at the middle.
    val widths = HomeColumnWidths.of(width, margin = 16.0, gutter = GUTTER, foldMinX = null, foldMaxX = null)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        wide.forEach { Section(it) }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(widths.leading.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { columns.leading.forEach { Section(it) } }
            Spacer(Modifier.width(widths.gap.dp))
            Column(Modifier.width(widths.trailing.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { columns.trailing.forEach { Section(it) } }
        }
    }
}
