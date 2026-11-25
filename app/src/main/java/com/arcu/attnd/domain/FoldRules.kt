package com.arcu.attnd.domain

/**
 * What each screen keeps across fold ↔ unfold (a resize and configuration change, never a rebuild of state): which
 * pane is primary, and what the folded layout shows for a given unfolded state. A port of iOS `FoldRules`.
 */
object FoldRules {
    enum class Screen { MAP, SCHEDULE, PLACES, HOME, CHAT, DETAIL }

    /** The pane that is the screen when folded. */
    fun primary(screen: Screen): String = when (screen) {
        Screen.MAP -> "map"
        Screen.SCHEDULE, Screen.PLACES -> "list"
        Screen.HOME -> "sections"
        Screen.CHAT -> "chat"
        Screen.DETAIL -> "detail"
    }

    /**
     * Folded: a list screen with a selection shows that detail pushed over the list (Back returns to the list);
     * without one, the list. The map shows the pane's item as the selected pin. Unfolded: list + the same detail.
     */
    sealed interface Folded {
        data object ListPane : Folded
        data class PushedDetail(val id: Int) : Folded
        data class Map(val selectedPin: Int?) : Folded
        data object Same : Folded
    }

    fun folded(screen: Screen, selected: Int?): Folded = when (screen) {
        Screen.SCHEDULE, Screen.PLACES -> selected?.let { Folded.PushedDetail(it) } ?: Folded.ListPane
        Screen.MAP -> Folded.Map(selected)
        Screen.HOME, Screen.CHAT, Screen.DETAIL -> Folded.Same
    }
}

/** A fold / hinge rectangle in a container's own coordinates (any unit, the same as the container's size). */
data class FoldRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val midX: Double get() = (left + right) / 2
    val midY: Double get() = (top + bottom) / 2

    /** The part inside `0…w × 0…h`, or null when they don't touch. A zero-width fold (Android's flat hinge) counts. */
    fun clipped(w: Double, h: Double): FoldRect? {
        val l = maxOf(left, 0.0)
        val t = maxOf(top, 0.0)
        val r = minOf(right, w)
        val b = minOf(bottom, h)
        return if (r < l || b < t || (r == l && b == t)) null else FoldRect(l, t, r, b)
    }
}

/**
 * The fold as a container sees it (iOS `FoldPosture.of` from the `.division` regions; Android: the window's
 * hinges, `currentWindowAdaptiveInfoV2().windowPosture`, moved into the container's coordinates): none (flat or
 * the fold elsewhere), a vertical fold (book: side by side) or a horizontal fold across the middle (tabletop: the
 * top half for viewing). Android reports hinges also while the phone is flat open, so a horizontal one only means
 * tabletop when the window is half-opened ([halfOpened] = `windowPosture.isTabletop`); a vertical one splits
 * columns whether flat or half-open.
 */
sealed interface FoldPosture {
    data object Flat : FoldPosture
    data class Book(val foldMinX: Double, val foldMaxX: Double) : FoldPosture
    data class Tabletop(val foldMinY: Double, val foldMaxY: Double) : FoldPosture

    companion object {
        fun of(width: Double, height: Double, folds: List<FoldRect>, halfOpened: Boolean = true): FoldPosture {
            for (raw in folds) {
                val rect = raw.clipped(width, height) ?: continue
                if (rect.width >= rect.height && rect.width >= width * 0.5 &&
                    rect.midY > height * 0.25 && rect.midY < height * 0.75
                ) {
                    if (halfOpened) return Tabletop(rect.top, rect.bottom)
                    continue
                }
                if (rect.height > rect.width && rect.height >= height * 0.5 &&
                    rect.midX > width * 0.25 && rect.midX < width * 0.75
                ) {
                    return Book(rect.left, rect.right)
                }
            }
            return Flat
        }
    }
}

/**
 * Where a two-pane split goes along one axis (`FoldAwareSplit`): on a hinge that crosses the layout (the first
 * pane up to its start, the gap = its width, nothing under it), else [fraction] of the space. A hinge in the
 * outer fifth on either side is ignored (one pane would be unusably small).
 */
object FoldSplit {
    data class At(val first: Int, val gap: Int)

    /** [hingeStart] / [hingeEnd]: the hinge along the axis in the layout's own coordinates; null = none crosses it. */
    fun at(total: Int, fraction: Double, hingeStart: Double?, hingeEnd: Double?): At {
        val default = At(Math.round(total * fraction).toInt(), 0)
        if (hingeStart == null || hingeEnd == null) return default
        if (hingeStart < total * 0.2 || hingeEnd > total * 0.8) return default
        return At(Math.round(hingeStart).toInt(), Math.round(hingeEnd - hingeStart).toInt().coerceAtLeast(0))
    }
}

/**
 * The Map tab's pane beside the map (iOS `MapPaneLayout`): up to the fold when one is reported, else half the
 * width, never under 280 (or half a narrow width) or over 60 % of the width.
 */
object MapPaneLayout {
    fun paneWidth(containerWidth: Double, foldMinX: Double?): Double {
        val wanted = foldMinX ?: (containerWidth / 2)
        return minOf(maxOf(wanted, minOf(280.0, containerWidth * 0.5)), containerWidth * 0.6)
    }

    /** The pane's share of the height when stacked (tabletop without a reported hinge; iOS 45 %). */
    const val STACKED_PANE_FRACTION = 0.45
}
