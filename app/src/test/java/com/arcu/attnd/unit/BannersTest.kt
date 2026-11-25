package com.arcu.attnd.unit

import com.arcu.attnd.domain.InAppBanner
import com.arcu.attnd.domain.PermissionRules
import com.arcu.attnd.domain.PermissionStatus
import com.arcu.attnd.domain.StatusBadge
import com.arcu.attnd.domain.StatusBadges
import com.arcu.attnd.domain.SwipeDismissRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe-to-dismiss (iOS `testSwipeDismissRule`, same cases), status pills, the in-app banner and permission rows. */
class BannersTest {

    @Test
    fun swipeDismissRule_iOSCases() {
        assertEquals(-30.0, SwipeDismissRule.offset(-30.0), 0.0)
        assertEquals(10.0, SwipeDismissRule.offset(50.0), 1e-9)
        assertEquals(24.0, SwipeDismissRule.offset(500.0), 0.0)
        assertTrue(SwipeDismissRule.dismisses(translation = -20.0, predicted = -20.0, height = 60.0))
        assertFalse(SwipeDismissRule.dismisses(translation = -10.0, predicted = -20.0, height = 60.0))
        assertTrue("a fast flick", SwipeDismissRule.dismisses(translation = -5.0, predicted = -90.0, height = 60.0))
        assertFalse(SwipeDismissRule.dismisses(translation = 20.0, predicted = 40.0, height = 60.0))
    }

    @Test
    fun swipeDismissRule_predictedFromVelocity() {
        assertEquals(-5.0 - 400.0 * SwipeDismissRule.FLING_PROJECTION_S, SwipeDismissRule.predicted(-5.0, -400.0), 1e-9)
        // A 500 dp/s flick up from 5 dp dismisses a 60 dp pill; a slow one doesn't.
        assertTrue(SwipeDismissRule.dismisses(-5.0, SwipeDismissRule.predicted(-5.0, -500.0), 60.0))
        assertFalse(SwipeDismissRule.dismisses(-5.0, SwipeDismissRule.predicted(-5.0, -100.0), 60.0))
    }

    @Test
    fun statusBadges_orderDismissAndComeBack() {
        val all = StatusBadges.all(ended = true, refreshTitle = "Offline · Updated 2:14 PM · everything still works", offline = true, instantUpdates = true)
        assertEquals(listOf(StatusBadge.Kind.ENDED, StatusBadge.Kind.REFRESH, StatusBadge.Kind.INSTANT), all.map { it.kind })
        assertEquals("no Demo pill", 0, StatusBadges.all(ended = false, refreshTitle = null, offline = false).size)
        val dismissed = setOf("ended", "refresh")
        assertEquals(listOf(StatusBadge.Kind.INSTANT), StatusBadges.visible(all, dismissed).map { it.kind })
        assertEquals("a new problem shows its pill again", setOf("ended"), StatusBadges.afterRefreshChange(dismissed, null, "offline"))
        assertEquals(dismissed, StatusBadges.afterRefreshChange(dismissed, "offline", "offline"))
    }

    @Test
    fun inAppBanner_urgentStaysLonger() {
        assertEquals(6_000L, InAppBanner(3, "Gates open", "").durationMs)
        assertEquals(10_000L, InAppBanner(3, "Set time change", "", urgent = true).durationMs)
    }

    @Test
    fun permissionStatus_neverAskedDeniedAllowed() {
        assertEquals(PermissionStatus.ALLOWED, PermissionStatus.of(granted = true, askedBefore = true, shouldShowRationale = false))
        assertEquals(PermissionStatus.NOT_ASKED, PermissionStatus.of(granted = false, askedBefore = false, shouldShowRationale = false))
        assertEquals("refused once: Android still asks", PermissionStatus.NOT_ASKED, PermissionStatus.of(granted = false, askedBefore = true, shouldShowRationale = true))
        assertEquals(PermissionStatus.DENIED, PermissionStatus.of(granted = false, askedBefore = true, shouldShowRationale = false))
        assertEquals(PermissionRules.Row.SWITCH, PermissionRules.row(PermissionStatus.ALLOWED))
        assertEquals(PermissionRules.Row.ASK_SWITCH, PermissionRules.row(PermissionStatus.NOT_ASKED))
        assertEquals(PermissionRules.Row.OPEN_SETTINGS, PermissionRules.row(PermissionStatus.DENIED))
        assertEquals("Off in Settings", PermissionRules.locationStatusText(PermissionStatus.DENIED))
    }

    @Test
    fun onJoin_turnsOnWhatTheSystemAllows() {
        assertEquals(PermissionRules.OnJoin(notifications = true, location = false), PermissionRules.onJoin(true, false, notificationsOn = false, locationOn = false))
        assertEquals("already on: nothing to do", PermissionRules.OnJoin(false, false), PermissionRules.onJoin(true, true, notificationsOn = true, locationOn = true))
        assertEquals(PermissionRules.OnJoin(false, true), PermissionRules.onJoin(false, true, notificationsOn = false, locationOn = false))
    }
}
