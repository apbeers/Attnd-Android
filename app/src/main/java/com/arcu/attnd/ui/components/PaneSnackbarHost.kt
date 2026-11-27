package com.arcu.attnd.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.arcu.attnd.domain.DetailColumns
import com.arcu.attnd.domain.UndoToastPane

/** Where the last tap went down, in window px (the Undo toast's pane follows it; iOS `UndoToast.touchX`). */
class TouchTracker {
    var lastWindowX by mutableStateOf<Float?>(null)

    /** The tracked element's left edge in the window (not state: read only when a press comes). */
    internal var originX = 0f
}

/**
 * Records every press inside this element (without consuming it, on the initial pass, so taps still reach the
 * content) into [tracker], in window coordinates.
 */
fun Modifier.trackTouches(tracker: TouchTracker): Modifier =
    this
        .onGloballyPositioned { tracker.originX = it.boundsInWindow().left }
        .pointerInput(tracker) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        event.changes.firstOrNull()?.let { tracker.lastWindowX = tracker.originX + it.position.x }
                    }
                }
            }
        }

/**
 * The Scaffold's snackbar host kept in one pane of an unfolded foldable ([UndoToastPane]): the pane where the
 * last tap was, else the main pane (always leading under `PanePair`), centred
 * there and never across the hinge. Without a vertical hinge a window of 700 dp or more splits in the middle;
 * narrower, the whole width.
 */
@Composable
fun PaneSnackbarHost(hostState: SnackbarHostState, touches: TouchTracker) {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInWindow() }) {
        val box = bounds
        if (box == null || box.width <= 0f) {
            SnackbarHost(hostState)
            return@Box
        }
        fun dp(px: Float) = with(density) { px.toDp().value.toDouble() }
        val width = dp(box.width)
        val hinge = posture.hingeList.map { it.bounds }
            .firstOrNull { it.height > it.width && it.left >= box.left && it.right <= box.right }
        val fold = hinge?.let { dp(it.left - box.left)..dp(it.right - box.left) }
        val mid = if (fold == null && width >= DetailColumns.MIN_WIDTH) width / 2 else null
        val touchX = touches.lastWindowX?.let { dp(it - box.left) }
        val span = UndoToastPane.span(width, fold, mid, touchX)
        SnackbarHost(
            hostState,
            Modifier.padding(start = span.start.dp).width((span.endInclusive - span.start).dp),
        )
    }
}
