package com.arcu.attnd.ui.components

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * List/detail built on Material3 adaptive's [NavigableListDetailPaneScaffold], keyed by an Int id.
 *
 * Folded (compact width) it shows one pane at a time with system back from detail to list. On the
 * unfolded inner display it shows both side by side; the scaffold's default directive keeps panes
 * off a separating fold/hinge.
 *
 * [requestedId] is a one-shot request (e.g. a map pin tap) to open a detail; [onRequestConsumed] clears it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun IdListDetail(
    requestedId: Int?,
    onRequestConsumed: () -> Unit,
    list: @Composable (selectedId: Int?, open: (Int) -> Unit) -> Unit,
    detail: @Composable (id: Int, showBack: Boolean, back: () -> Unit) -> Unit,
    emptyDetail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Int>()
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestedId) {
        if (requestedId != null) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, requestedId)
            onRequestConsumed()
        }
    }

    val selectedId = navigator.currentDestination?.contentKey
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        listPane = {
            AnimatedPane {
                list(selectedId) { id ->
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val id = navigator.currentDestination?.contentKey
                if (id != null) {
                    detail(id, listHidden) { scope.launch { navigator.navigateBack() } }
                } else {
                    emptyDetail()
                }
            }
        },
    )
}
