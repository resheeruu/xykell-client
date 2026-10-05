package dev.xykell.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A native binding failure has to be visible. These tests pin that contract:
 * a recorded failure is reportable, it is bounded, it names the bridge but not
 * any internal text, and a later success clears it.
 */
class NativeBridgeStatusTest {

    @Before
    fun setUp() = NativeBridgeStatus.clear()

    @Test
    fun healthyWhenNothingFailed() {
        assertTrue(NativeBridgeStatus.healthy("NativeSettings"))
        assertEquals(0, NativeBridgeStatus.failureCount())
        assertTrue(NativeBridgeStatus.failingBridges().isEmpty())
        assertEquals("native bridges: OK", NativeBridgeStatus.summary())
    }

    @Test
    fun failureMakesTheBridgeUnhealthy() {
        NativeBridgeStatus.recordFailure("NativeHud", "getHudLayout")
        assertFalse(NativeBridgeStatus.healthy("NativeHud"))
        assertEquals(1, NativeBridgeStatus.failureCount())
        assertEquals(listOf("NativeHud"), NativeBridgeStatus.failingBridges())
    }

    @Test
    fun successClearsTheFailure() {
        NativeBridgeStatus.recordFailure("NativeHud", "getHudLayout")
        NativeBridgeStatus.recordSuccess("NativeHud", "getHudLayout")
        assertTrue(NativeBridgeStatus.healthy("NativeHud"))
        assertEquals(0, NativeBridgeStatus.failureCount())
    }

    @Test
    fun summaryNamesTheBridgeButNeverTheSymbolDetail() {
        NativeBridgeStatus.recordFailure("RuntimeStatus", "nativeStart")
        NativeBridgeStatus.recordFailure("Observations", "nativeOfferUnknown")
        val s = NativeBridgeStatus.summary()
        assertTrue(s.contains("Observations"))
        assertTrue(s.contains("RuntimeStatus"))
        assertTrue(s.contains("2 unresolved"))
        // No symbol names, no paths, no exception text.
        assertFalse(s.contains("nativeStart"))
        assertFalse(s.contains("UnsatisfiedLinkError"))
        assertFalse(s.contains("/"))
    }

    @Test
    fun oneBridgeWithManyFailuresIsReportedOnce() {
        NativeBridgeStatus.recordFailure("NativeSettings", "settingsCatalog")
        NativeBridgeStatus.recordFailure("NativeSettings", "settingsValues")
        NativeBridgeStatus.recordFailure("NativeSettings", "setSetting")
        assertEquals(3, NativeBridgeStatus.failureCount())
        assertEquals(listOf("NativeSettings"), NativeBridgeStatus.failingBridges())
    }

    @Test
    fun recordIsBounded() {
        // Must not grow without limit in a long-running process.
        repeat(1000) { n ->
            NativeBridgeStatus.recordFailure("Bridge$n", "sym")
        }
        assertTrue(NativeBridgeStatus.failureCount() <= 128)
    }

    @Test
    fun summaryIsStableAcrossCalls() {
        NativeBridgeStatus.recordFailure("NativeThemes", "listThemes")
        assertEquals(NativeBridgeStatus.summary(), NativeBridgeStatus.summary())
    }
}