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
