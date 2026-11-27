package com.arcu.attnd.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.domain.SecondaryPane
import com.arcu.attnd.ui.components.PanePair
import com.arcu.attnd.ui.screens.EventMap
import com.arcu.attnd.ui.screens.LocationsList
import com.arcu.attnd.ui.screens.ScheduleList

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

/**
 * The Map tab: the map is the main pane of a [PanePair] (Material3 adaptive's supporting-pane scaffold, iOS
 * `PanePair` / `ArrangementView`), and `display.secondary_pane` its supporting pane where the window has room:
 * - compact (folded, the cover screen, ordinary phones): the map alone;
 * - unfolded (medium width or more): the map leading, the pane beside it, the scaffold keeping both off the
 *   hinge;
 * - tabletop: where the scaffold reflows the pane, below the map.
 *
 * **One map for every posture**: the map is always the scaffold's main pane, so the `GoogleMap` (one `MapView`,
 * its camera, tiles and selection) keeps its identity when the pane comes and goes. With `configChanges` on
 * MainActivity a fold is a resize, not a new activity. The selection and chips live in [MapUiState], so a place
 * tapped in the pane is the selected pin and stays selected after folding.
 *
 * [onSearch], when set, puts the app-wide search FAB into the map's floating toolbar (see EventShell).
 */
@Composable
fun MapTab(state: AppUiState, viewModel: EventViewModel, onSearch: (() -> Unit)? = null) {
    val bundle = state.bundle ?: return
    val ui = rememberMapUiState()
    val pane = bundle.display.secondaryPane

    PanePair(
        showsSupporting = pane != SecondaryPane.NONE,
        modifier = Modifier.fillMaxSize(),
        twoPanesOnMediumWidth = true,
        main = {
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
        },
        supporting = {
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
        },
    )
}
