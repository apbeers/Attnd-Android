package com.arcu.attnd.ui.components

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
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

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

/** Returns (first pane size, gap) in px. */
private fun splitAt(
    axis: SplitAxis,
    total: Int,
    fraction: Float,
    layout: Rect?,
    hinges: List<Rect>,
): Pair<Int, Int> {
    val default = (total * fraction).roundToInt() to 0
    layout ?: return default
    val hinge = hinges.firstOrNull { it.overlaps(layout) } ?: return default
    val (start, end) = if (axis == SplitAxis.Horizontal) {
        (hinge.left - layout.left) to (hinge.right - layout.left)
    } else {
        (hinge.top - layout.top) to (hinge.bottom - layout.top)
    }
    // Ignore a hinge near the edge: it would leave one pane unusably small.
    if (start < total * 0.2f || end > total * 0.8f) return default
    return start.roundToInt() to (end - start).roundToInt().coerceAtLeast(0)
}
