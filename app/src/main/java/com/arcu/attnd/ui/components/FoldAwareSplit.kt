package com.arcu.attnd.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.arcu.attnd.domain.FoldPosture
import com.arcu.attnd.domain.FoldRect
import com.arcu.attnd.domain.FoldSplit

enum class SplitAxis {
    /** Side by side (book posture / unfolded inner display): split across a vertical fold. */
    Horizontal,

    /** Stacked (tabletop posture): split across a horizontal fold, first pane on top. */
    Vertical,
}

/**
 * Two panes split along [axis]. When a fold/hinge from [hingeBounds] (window coordinates, from
 * `currentWindowAdaptiveInfoV2().windowPosture`) crosses this layout, the split is placed exactly on
 * it and nothing is drawn under the hinge; otherwise the first pane gets [fraction] of the space.
 * Must be given bounded constraints (e.g. fillMaxSize()).
 */
@Composable
fun FoldAwareSplit(
    axis: SplitAxis,
    hingeBounds: List<Rect>,
    modifier: Modifier = Modifier,
    fraction: Float = 0.5f,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    var layoutBounds by remember { mutableStateOf<Rect?>(null) }
    Layout(
        contents = listOf(first, second),
        modifier = modifier.onGloballyPositioned { layoutBounds = it.boundsInWindow() },
    ) { (firstMeasurables, secondMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val total = if (axis == SplitAxis.Horizontal) width else height
        val (firstSize, gap) = splitAt(axis, total, fraction, layoutBounds, hingeBounds)
        val secondSize = (total - firstSize - gap).coerceAtLeast(0)

        fun fixed(main: Int) =
            if (axis == SplitAxis.Horizontal) Constraints.fixed(main, height) else Constraints.fixed(width, main)

        val firstPlaceables = firstMeasurables.map { it.measure(fixed(firstSize)) }
        val secondPlaceables = secondMeasurables.map { it.measure(fixed(secondSize)) }
        layout(width, height) {
            firstPlaceables.forEach { it.place(0, 0) }
            secondPlaceables.forEach {
                if (axis == SplitAxis.Horizontal) it.place(firstSize + gap, 0) else it.place(0, firstSize + gap)
            }
        }
    }
}

/** Returns (first pane size, gap) in px: the pure rule is [FoldSplit.at]. */
private fun splitAt(
    axis: SplitAxis,
    total: Int,
    fraction: Float,
    layout: Rect?,
    hinges: List<Rect>,
): Pair<Int, Int> {
    val hinge = layout?.let { l -> hinges.firstOrNull { touches(it, l) } }
    val (start, end) = when {
        layout == null || hinge == null -> null to null
        axis == SplitAxis.Horizontal -> (hinge.left - layout.left).toDouble() to (hinge.right - layout.left).toDouble()
        else -> (hinge.top - layout.top).toDouble() to (hinge.bottom - layout.top).toDouble()
    }
    val at = FoldSplit.at(total, fraction.toDouble(), start, end)
    return at.first to at.gap
}

/** Inclusive overlap, so a zero-width (flat, non-occluding) hinge counts too (`Rect.overlaps` is false for it). */
private fun touches(hinge: Rect, layout: Rect): Boolean =
    hinge.left <= layout.right && hinge.right >= layout.left && hinge.top <= layout.bottom && hinge.bottom >= layout.top

/**
 * The window's hinges (`currentWindowAdaptiveInfoV2().windowPosture`) in this box's own dp coordinates, as a
 * [FoldPosture] (book = a vertical fold to split columns on, tabletop = half-opened with a horizontal one), for
 * layouts that follow the fold inside their own container (Home's columns, detail columns, the tabletop glance).
 */
@Composable
fun FoldAwareBox(modifier: Modifier = Modifier, content: @Composable BoxWithConstraintsScope.(FoldPosture) -> Unit) {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        val origin = bounds
        val fold = if (origin == null) {
            FoldPosture.Flat
        } else {
            val folds = posture.hingeList.map { hinge ->
                val b = hinge.bounds
                with(density) {
                    FoldRect(
                        (b.left - origin.left).toDp().value.toDouble(),
                        (b.top - origin.top).toDp().value.toDouble(),
                        (b.right - origin.left).toDp().value.toDouble(),
                        (b.bottom - origin.top).toDp().value.toDouble(),
                    )
                }
            }
            FoldPosture.of(maxWidth.value.toDouble(), maxHeight.value.toDouble(), folds, halfOpened = posture.isTabletop)
        }
        content(fold)
    }
}
