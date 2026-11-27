package com.arcu.attnd.ui.components

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AdaptStrategy
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's one two-pane container (iOS `PanePair`, which is SwiftUI's `ArrangementView`): a screen made of a
 * main pane (the map, Home's first column, the chat) and an optional supporting pane, placed by Material3
 * adaptive's [SupportingPaneScaffold] — Google's canonical "supporting pane" layout. Nothing here reads the hinge:
 * the scaffold's directive ([calculatePaneScaffoldDirective]) knows the window size class, keeps panes off a
 * separating vertical hinge (its `excludedBounds`), and in tabletop posture offers two vertical partitions, where
 * the supporting pane's default adapt strategy (`Reflow` onto the main pane) puts it below the main one. On a
 * compact window (a folded phone, the cover screen, any ordinary phone) the main pane is alone.
 *
 * The main pane is always the scaffold's main pane, so it keeps its state when the supporting pane comes and goes
 * (one `MapView`, one chat list, Home's scroll position). [main] is told whether the supporting pane is on screen
 * beside or below it, so a pane alone can show what the other would.
 *
 * [twoPanesOnMediumWidth]: also split on a medium-width window (an unfolded foldable held upright); Google's
 * default ([calculatePaneScaffoldDirective]) splits from expanded width only.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PanePair(
    showsSupporting: Boolean,
    modifier: Modifier = Modifier,
    twoPanesOnMediumWidth: Boolean = false,
    main: @Composable (split: Boolean) -> Unit,
    supporting: @Composable () -> Unit,
) {
    val info = currentWindowAdaptiveInfoV2()
    val directive = if (twoPanesOnMediumWidth) {
        calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(info)
    } else {
        calculatePaneScaffoldDirective(info)
    }
    // The main pane is the destination (the last item has the highest priority), the supporting pane the one
    // before it: shown only where the directive leaves room for it.
    val history = buildList {
        // Only where it can sit beside the main pane, or below it in tabletop: on a phone the main pane is alone.
        val room = directive.maxHorizontalPartitions > 1 || info.windowPosture.isTabletop
        if (showsSupporting && room) add(ThreePaneScaffoldDestinationItem<Nothing>(SupportingPaneScaffoldRole.Supporting))
        add(ThreePaneScaffoldDestinationItem<Nothing>(SupportingPaneScaffoldRole.Main))
    }
    val value = calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = directive.maxHorizontalPartitions,
        // Below the main pane only in tabletop (two vertical partitions); otherwise a supporting pane that
        // doesn't fit beside the main one is hidden, never stacked under it on a phone.
        adaptStrategies = if (directive.maxVerticalPartitions > 1) {
            SupportingPaneScaffoldDefaults.adaptStrategies()
        } else {
            SupportingPaneScaffoldDefaults.adaptStrategies(supportingPaneAdaptStrategy = AdaptStrategy.Hide)
        },
        destinationHistory = history,
        maxVerticalPartitions = directive.maxVerticalPartitions,
    )
    val split = value[SupportingPaneScaffoldRole.Supporting] != PaneAdaptedValue.Hidden
    SupportingPaneScaffold(
        directive = directive,
        value = value,
        modifier = modifier,
        mainPane = { AnimatedPane { main(split) } },
        supportingPane = { AnimatedPane { supporting() } },
    )
}
