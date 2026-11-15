package com.arcu.attnd.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.allHorizontalHingeBounds
import androidx.compose.material3.adaptive.allVerticalHingeBounds
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import com.arcu.attnd.domain.ScheduleStyle
import com.arcu.attnd.domain.SecondaryPane
import com.arcu.attnd.ui.components.FoldAwareSplit
import com.arcu.attnd.ui.components.SplitAxis
import com.arcu.attnd.ui.screens.EventMap
import com.arcu.attnd.ui.screens.LocationsList
import com.arcu.attnd.ui.screens.ScheduleList

/**
 * The Map tab, adapted to the foldable's state:
 * - folded / cover screen (compact width): the map alone;
 * - unfolded inner display (medium width or more, book posture): map beside the
 *   `display.secondary_pane`, split on the fold;
 * - half-opened in tabletop posture: map on the top half, the pane on the bottom half,
 *   split on the horizontal hinge.
 *
 * [onSearch], when set, puts the app-wide search FAB into the map's floating toolbar (see EventShell).
 */
@Composable
fun MapTab(state: AppUiState, viewModel: EventViewModel, onSearch: (() -> Unit)? = null) {
    val bundle = state.bundle ?: return
    // V2 adds the L (1200 dp) and XL (1600 dp) width classes; "at least medium" (600 dp) is unchanged.
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val posture = adaptiveInfo.windowPosture
    val unfolded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val pane = bundle.display.secondaryPane

    val map: @Composable () -> Unit = {
        EventMap(
            bundle = bundle,
            showUserLocation = state.showUserLocation,
            onShowUserLocationChange = viewModel::setShowUserLocation,
            recenterRequest = state.recenterRequest,
            onLocationClick = viewModel::focusLocation,
            onSearch = onSearch,
        )
    }
    val secondary: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize()) {
            when (pane) {
                SecondaryPane.LOCATIONS -> LocationsList(
                    bundle = bundle,
                    isRefreshing = state.sync.isRefreshing,
                    onRefresh = viewModel::refresh,
                    selectedId = null,
                    onLocationClick = viewModel::focusLocation,
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

    when {
        pane == SecondaryPane.NONE -> map()
        posture.isTabletop -> FoldAwareSplit(
            axis = SplitAxis.Vertical,
            hingeBounds = posture.allHorizontalHingeBounds,
            modifier = Modifier.fillMaxSize(),
            first = map,
            second = {
                Surface(Modifier.fillMaxSize()) {
                    HorizontalDivider()
                    secondary()
                }
            },
        )
        unfolded -> FoldAwareSplit(
            axis = SplitAxis.Horizontal,
            hingeBounds = posture.allVerticalHingeBounds,
            modifier = Modifier.fillMaxSize(),
            fraction = 0.6f,
            first = map,
            second = {
                Surface(Modifier.fillMaxSize()) {
                    VerticalDivider()
                    secondary()
                }
            },
        )
        else -> map()
    }
}
