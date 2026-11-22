package com.arcu.attnd.unit

import com.arcu.attnd.domain.ServerSwitch
import com.arcu.attnd.domain.TestServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test server switch (iOS `ServerSwitch`): dev and debug builds only, applied at the next launch. */
class ServerSwitchTest {

    @Test
    fun storeBuildsIgnoreAStoredChoice() {
        assertFalse(ServerSwitch.isSwitchable(devBuild = false))
        assertEquals("attnd.app", ServerSwitch.resolve("attnd.app", "DEV", devBuild = false))
    }

    @Test
    fun devBuildsUseTheStoredChoiceElseTheBuildsHost() {
        assertTrue(ServerSwitch.isSwitchable(devBuild = true))
        assertEquals("attnd.app", ServerSwitch.resolve("dev.attnd.app", "PRODUCTION", devBuild = true))
        assertEquals("dev.attnd.app", ServerSwitch.resolve("attnd.app", "DEV", devBuild = true))
        assertEquals("dev.attnd.app", ServerSwitch.resolve("dev.attnd.app", null, devBuild = true))
        // An unknown stored value (an older build's) falls back to the build's host.
        assertEquals("localhost:8000", ServerSwitch.resolve("localhost:8000", "STAGING", devBuild = true))
    }

    @Test
    fun choiceShowsTheServerInUseAndTheOneForTheNextLaunch() {
        val same = ServerSwitch.choice("dev.attnd.app", null)
        assertEquals(TestServer.DEV, same.current)
        assertNull(same.next)
        assertFalse(same.pendingRestart)
        assertEquals(TestServer.DEV, same.selected)

        // Chosen before this launch and now in use: nothing pending.
        assertFalse(ServerSwitch.choice("attnd.app", "PRODUCTION").pendingRestart)

        val pending = ServerSwitch.choice("dev.attnd.app", "PRODUCTION")
        assertEquals(TestServer.DEV, pending.current)
        assertEquals(TestServer.PRODUCTION, pending.next)
        assertTrue(pending.pendingRestart)
        assertEquals(TestServer.PRODUCTION, pending.selected)
        assertEquals("Restart Attnd to use the production server.", ServerSwitch.restartNote(TestServer.PRODUCTION))

        // A local build host: no selection until one is picked.
        assertNull(ServerSwitch.choice("10.0.2.2:8000", null).selected)
    }

    @Test
    fun onlyARealChangeLeavesTheEvent() {
        assertFalse(ServerSwitch.isChange("dev.attnd.app", null, TestServer.DEV))
        assertTrue(ServerSwitch.isChange("dev.attnd.app", null, TestServer.PRODUCTION))
        assertFalse(ServerSwitch.isChange("dev.attnd.app", "PRODUCTION", TestServer.PRODUCTION))
        // Switching back before restarting is a change too (back to the server in use).
        assertTrue(ServerSwitch.isChange("dev.attnd.app", "PRODUCTION", TestServer.DEV))
        assertEquals("production", TestServer.PRODUCTION.logName)
        assertEquals("dev", TestServer.DEV.logName)
    }
}
