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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.arcu.attnd.domain.FoldPosture
import com.arcu.attnd.domain.FoldRect

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
