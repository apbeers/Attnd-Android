package com.arcu.attnd.domain

/**
 * The map's location button (iOS batch 7, like Apple / Google Maps): off → follow the user → follow with the
 * heading (the camera turns with the phone) → off. A pan by the user ends following ([afterGesture]).
 */
enum class MapFollowMode {
    OFF, FOLLOW, FOLLOW_HEADING;

    fun next(): MapFollowMode = when (this) {
        OFF -> FOLLOW
        FOLLOW -> FOLLOW_HEADING
        FOLLOW_HEADING -> OFF
    }

    /** A camera move the user started (drag, pinch, rotate) ends following. */
    fun afterGesture(): MapFollowMode = OFF

    val contentDescription: String
        get() = when (this) {
            OFF -> "My location"
            FOLLOW -> "Following your location. Tap to follow your heading"
            FOLLOW_HEADING -> "Following your heading. Tap to stop following"
        }
}

/** How the camera frames a set of pins (iOS `MapRegionMath.filterRegion`). */
sealed interface MapFit {
    data object Nothing : MapFit

    /** One pin: centred at street level. */
    data class Single(val point: GeoPoint) : MapFit

    /** Several: the bounds of all of them (fitted with padding on the device). */
    data class Bounds(val southwest: GeoPoint, val northeast: GeoPoint, val count: Int) : MapFit
}

/** Pure camera rules for the Map (`MapCameraTest`). */
object MapCamera {

    /**
     * Clustering merges only pins that would overlap (iOS batch 7: < 24 pt apart at the current zoom), so pins
     * stay separate until they would actually collide; maps-utils' default merges everything within 100 dp.
     */
    const val CLUSTER_DISTANCE_DP = 24

    /**
     * The frame for [points] (a category / My Schedule chip refits the camera to the pins left; clearing it fits
     * every pin): none → [MapFit.Nothing], one → [MapFit.Single], several → [MapFit.Bounds]. Identical points
     * count as one.
     */
    fun fit(points: List<GeoPoint>): MapFit {
        val distinct = points.distinct()
        return when (distinct.size) {
            0 -> MapFit.Nothing
            1 -> MapFit.Single(distinct.first())
            else -> MapFit.Bounds(
                southwest = GeoPoint(distinct.minOf { it.latitude }, distinct.minOf { it.longitude }),
                northeast = GeoPoint(distinct.maxOf { it.latitude }, distinct.maxOf { it.longitude }),
                count = distinct.size,
            )
        }
    }

    /**
     * Whether a filter change should move the camera: only once the map has done its first framing, and only when
     * the filter really changed (not on the first composition, a fold or a recomposition).
     */
    fun refitsOnFilter(previous: Pair<String?, Boolean>?, current: Pair<String?, Boolean>): Boolean =
        previous != null && previous != current
}

/** The main-thread watchdog's rule (iOS `MainThreadWatchdog`: a 50 ms ping, `perf.hang` over 100 ms). */
object HangRule {
    const val PING_INTERVAL_MS = 50L
    const val THRESHOLD_MS = 100L

    fun isHang(stalledMs: Long): Boolean = stalledMs > THRESHOLD_MS
}
