package com.arcu.attnd.unit

import com.arcu.attnd.domain.DeviceIdentity
import com.arcu.attnd.domain.DeviceIdentity.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/** The device id survives a reinstall through Block Store (iOS BusinessLogicTests' DeviceIdentity cases, Keychain → Block Store). */
class DeviceIdentityTest {

    @Test
    fun existingInstallKeepsItsId_andFillsBlockStore() {
        assertEquals(Resolution("A", savePrefs = false, saveBlockStore = true), DeviceIdentity.resolve("A", null) { "NEW" })
        assertEquals(Resolution("A", savePrefs = false, saveBlockStore = false), DeviceIdentity.resolve("A", "A") { "NEW" })
    }

    @Test
    fun reinstallTakesBlockStoresId() {
        assertEquals(Resolution("K", savePrefs = true, saveBlockStore = false), DeviceIdentity.resolve(null, "K") { "NEW" })
        assertEquals("K", DeviceIdentity.resolve("  ", "K") { "NEW" }.id)
        assertEquals("trimmed", "K", DeviceIdentity.resolve(null, " K\n") { "NEW" }.id)
    }

    @Test
    fun theAppsOwnCopyWinsOverAnOlderBlockStoreId() {
        // The server knows this install by its own id; Block Store is brought in line with it.
        assertEquals(Resolution("APP", savePrefs = false, saveBlockStore = true), DeviceIdentity.resolve("APP", "OLD") { "NEW" })
    }

    @Test
    fun firstLaunchMakesOne_andStoresItTwice() {
        assertEquals(Resolution("NEW", savePrefs = true, saveBlockStore = true), DeviceIdentity.resolve(null, null) { "NEW" })
        assertEquals(Resolution("NEW", savePrefs = true, saveBlockStore = true), DeviceIdentity.resolve("", " ") { "NEW" })
    }
}
