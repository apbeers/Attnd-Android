package com.arcu.attnd.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.window.core.layout.WindowSizeClass
import com.arcu.attnd.domain.FoldSplit
import com.arcu.attnd.domain.MapPaneLayout
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.domain.SecondaryPane
import com.arcu.attnd.ui.screens.EventMap
import com.arcu.attnd.ui.screens.LocationsList
import com.arcu.attnd.ui.screens.ScheduleList
import kotlin.math.roundToInt

/**
 * The Map tab's state that must survive fold / unfold / tabletop (iOS `MapUIState`): the selected pin and the
 * chips, plus a one-shot request from the pane to select and centre a pin ([paneFocusId], not saved).
 */
@Stable
class MapUiState(selectedLocationId: Int? = null, selectedCategory: String? = null, heartedOnly: Boolean = false) {
    var selectedLocationId by mutableStateOf(selectedLocationId)
    var selectedCategory by mutableStateOf(selectedCategory)
    var heartedOnly by mutableStateOf(heartedOnly)
    var paneFocusId by mutableStateOf<Int?>(null)

    companion object {
        val Saver = listSaver<MapUiState, Any?>(
            save = { listOf(it.selectedLocationId, it.selectedCategory, it.heartedOnly) },
            restore = { MapUiState(it[0] as Int?, it[1] as String?, it[2] as Boolean) },
        )
    }
}

@Composable
fun rememberMapUiState(): MapUiState = rememberSaveable(saver = MapUiState.Saver) { MapUiState() }

private enum class MapArrangement { ALONE, SIDE, STACKED }

/**
 * The Map tab, adapted to the window (never the device):
 * - folded / cover screen (compact width): the map alone;
 * - unfolded (medium width or more, book posture or flat): the `display.secondary_pane` **leads** and the map
 *   **trails**, meeting on the reported vertical hinge (else the middle; [MapPaneLayout]), no divider;
 * - tabletop: the map on the top half, the pane on the bottom half, split on the horizontal hinge.
 *
 * **One map for every posture** (iOS batch 7 fix round): the pane is an optional sibling in one custom [Layout]
 * whose second slot is always the map, so the `GoogleMap` (one `MapView`, its camera, tiles and selection) keeps
 * its identity when the pane comes and goes or moves from beside to below. With `configChanges` on MainActivity a
 * fold is a resize, not a new activity. The selection and chips live in [MapUiState], so a place tapped in the
 * pane is the selected pin and stays selected after folding.
 *
 * [onSearch], when set, puts the app-wide search FAB into the map's floating toolbar (see EventShell).
 */
@Composable
fun MapTab(state: AppUiState, viewModel: EventViewModel, onSearch: (() -> Unit)? = null) {
    val bundle = state.bundle ?: return
    val ui = rememberMapUiState()
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val posture = adaptiveInfo.windowPosture
    val unfolded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val pane = bundle.display.secondaryPane
    val arrangement = when {
        pane == SecondaryPane.NONE -> MapArrangement.ALONE
        posture.isTabletop -> MapArrangement.STACKED
        unfolded -> MapArrangement.SIDE
        else -> MapArrangement.ALONE
    }
    val hinges = posture.hingeList.map { it.bounds }
    var bounds by remember { mutableStateOf<Rect?>(null) }

    val secondary: @Composable () -> Unit = {
        if (arrangement != MapArrangement.ALONE) {
            Surface(Modifier.fillMaxSize()) {
                when (pane) {
                    SecondaryPane.LOCATIONS -> LocationsList(
                        bundle = bundle,
                        isRefreshing = state.sync.isRefreshing,
                        onRefresh = viewModel::refresh,
                        selectedId = ui.selectedLocationId,
                        // The pane's pick is the map's selected pin (FoldRules: folded, the map shows it).
                        onLocationClick = { ui.paneFocusId = it },
                        onSearch = viewModel::onSearch,
                    )
                    SecondaryPane.SCHEDULE, SecondaryPane.NOW_NEXT -> ScheduleList(
                        bundle = bundle,
                        style = if (pane == SecondaryPane.NOW_NEXT) ScheduleStyle.NOW_NEXT else bundle.display.scheduleStyle,
                        isRefreshing = state.sync.isRefreshing,
                        onRefresh = viewModel::refresh,
                        selectedId = null,
                        onItemClick = viewModel::focusScheduleItem,
                        onSearch = viewModel::onSearch,
                    )
                    SecondaryPane.NONE -> Unit
                }
            }
        }
    }
    val map: @Composable () -> Unit = {
        val external = state.mapFocusLocationId
        EventMap(
            bundle = bundle,
            showUserLocation = state.showUserLocation,
            onShowUserLocationChange = viewModel::setShowUserLocation,
            recenterRequest = state.recenterRequest,
            onLocationClick = viewModel::focusLocation,
            onSearch = onSearch,
            searchIsPlan = state.planButton,
            focusLocationId = external ?: ui.paneFocusId,
            onFocusConsumed = { if (external != null) viewModel.consumeMapFocus() else ui.paneFocusId = null },
            ui = ui,
        )
    }

    Layout(
        contents = listOf(secondary, map),
        modifier = Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() },
    ) { (paneMeasurables, mapMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val origin = bounds
        when (arrangement) {
            MapArrangement.ALONE -> {
                val mapPlaceables = mapMeasurables.map { it.measure(Constraints.fixed(width, height)) }
                layout(width, height) { mapPlaceables.forEach { it.place(0, 0) } }
            }
            MapArrangement.SIDE -> {
                // A vertical hinge crossing this layout, in its own px.
                val fold = origin?.let { o ->
                    hinges.firstOrNull { it.height > it.width && it.left >= o.left && it.right <= o.right && it.top < o.bottom && it.bottom > o.top }
                        ?.let { (it.left - o.left) to (it.right - o.left) }
                }
                val paneWidth = MapPaneLayout.paneWidth(width.toDouble(), fold?.first?.toDouble()).roundToInt()
                val gap = if (fold != null && paneWidth == fold.first.roundToInt()) (fold.second - fold.first).roundToInt() else 0
                val mapWidth = (width - paneWidth - gap).coerceAtLeast(0)
                val panePlaceables = paneMeasurables.map { it.measure(Constraints.fixed(paneWidth, height)) }
                val mapPlaceables = mapMeasurables.map { it.measure(Constraints.fixed(mapWidth, height)) }
                layout(width, height) {
                    panePlaceables.forEach { it.place(0, 0) }
                    mapPlaceables.forEach { it.place(paneWidth + gap, 0) }
                }
            }
            MapArrangement.STACKED -> {
                val fold = origin?.let { o ->
                    hinges.firstOrNull { it.width >= it.height && it.top >= o.top && it.bottom <= o.bottom && it.left < o.right && it.right > o.left }
                        ?.let { (it.top - o.top).toDouble() to (it.bottom - o.top).toDouble() }
                }
                val split = FoldSplit.at(height, 1 - MapPaneLayout.STACKED_PANE_FRACTION, fold?.first, fold?.second)
                val mapHeight = split.first
                val paneHeight = (height - mapHeight - split.gap).coerceAtLeast(0)
                val mapPlaceables = mapMeasurables.map { it.measure(Constraints.fixed(width, mapHeight)) }
                val panePlaceables = paneMeasurables.map { it.measure(Constraints.fixed(width, paneHeight)) }
                layout(width, height) {
                    mapPlaceables.forEach { it.place(0, 0) }
                    panePlaceables.forEach { it.place(0, mapHeight + split.gap) }
                }
            }
        }
    }
}
