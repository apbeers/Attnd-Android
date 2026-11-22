package com.arcu.attnd.domain

/**
 * The drag rule for the top pills and banners (iOS `SwipeDismissRule` / `SwipeUpToDismiss`): dragged
 * up they follow the finger; pulled down they rubber-band (a fifth of the pull, at most 24 dp);
 * released past 30 % of their height up, or flung so that the predicted end is a full height up, they
 * slide off the top and are dismissed; otherwise they spring back. Values in dp, up = negative.
 */
object SwipeDismissRule {
    /** Most a pill moves down when pulled the wrong way. */
    const val MAX_PULL_DOWN = 24.0

    /** How far a fling carries past the release point (seconds of the release velocity), like iOS's predicted end. */
    const val FLING_PROJECTION_S = 0.2

    fun offset(translation: Double): Double =
        if (translation <= 0) translation else minOf(translation * 0.2, MAX_PULL_DOWN)

    fun dismisses(translation: Double, predicted: Double, height: Double): Boolean =
        -translation > height * 0.3 || -predicted > height

    /** iOS's predicted end translation from the release velocity (dp/s, up = negative). */
    fun predicted(translation: Double, velocity: Double): Double = translation + velocity * FLING_PROJECTION_S
}

/**
 * A floating status pill at the top of the content (iOS `EventStatusBadgeKind`), top to bottom:
 * the event ended, the last refresh failed, and (API-026, phase 9) instant updates on a preview
 * phone. Each can be swiped away for the session ([StatusBadges.visible]); Options → Status keeps
 * the same information. No Demo pill (iOS removed it, Nov 21, 2026).
 */
data class StatusBadge(val kind: Kind, val title: String, val description: String) {
    enum class Kind(
        /** What a swipe dismisses (iOS `dismissedStatusBadges` ids). */
        val id: String,
    ) { ENDED("ended"), REFRESH("refresh"), INSTANT("instant") }
}

object StatusBadges {
    /**
     * Every pill, in order. [refreshTitle] is the failed refresh's text ([LiveUpdates.offlineNoticeText])
     * or null while refreshes work; [instantUpdates] is the preview phone's state (phase 9 sets it).
     */
    fun all(ended: Boolean, refreshTitle: String?, offline: Boolean, instantUpdates: Boolean = false): List<StatusBadge> = buildList {
        if (ended) add(StatusBadge(StatusBadge.Kind.ENDED, "Event ended", "This event has ended. You can still browse everything."))
        if (refreshTitle != null) {
            add(
                StatusBadge(
                    StatusBadge.Kind.REFRESH,
                    refreshTitle,
                    if (offline) "You're offline. Everything still works." else "Attnd can't reach the server right now. Everything still works.",
                ),
            )
        }
        if (instantUpdates) {
            add(
                StatusBadge(
                    StatusBadge.Kind.INSTANT,
                    "Instant updates on",
                    "Instant updates are on: this phone shows the organizer's changes as they make them.",
                ),
            )
        }
    }

    /** The pills not swiped away this session. */
    fun visible(badges: List<StatusBadge>, dismissed: Set<String>): List<StatusBadge> = badges.filter { it.kind.id !in dismissed }

    /**
     * The dismissed set after the refresh state changed: a new refresh problem (or the end of one)
     * shows its pill again after a swipe away.
     */
    fun afterRefreshChange(dismissed: Set<String>, before: String?, after: String?): Set<String> =
        if (before != after) dismissed - StatusBadge.Kind.REFRESH.id else dismissed
}

/**
 * An organizer update that arrived while the app was open (iOS `InAppBanner`): shown at the top for
 * [durationMs], tap → Updates at it, a swipe up dismisses it (it stays in Updates and the shade).
 */
data class InAppBanner(val notificationId: Int?, val title: String, val body: String, val urgent: Boolean = false) {
    val durationMs: Long get() = if (urgent) 10_000L else 6_000L
}
