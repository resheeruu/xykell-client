package dev.xykell.client.runtime.privacy

import dev.xykell.client.runtime.world.WaypointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAndWorldTest {

    // --- PrivacySettings

    @Test
    fun defaultsAreAllOff() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        assertFalse(p.streamerMode)
        assertFalse(p.privacyMode)
        assertFalse(p.hideHud)
    }

    @Test
    fun togglesPersistThroughTheStore() {
        val store = PrivacySettings.MemoryStore()
        val p = PrivacySettings(store)
        p.streamerMode = true
        p.privacyMode = true
        p.hideHud = true
        assertTrue(PrivacySettings(store).streamerMode)
        assertTrue(PrivacySettings(store).privacyMode)
        assertTrue(PrivacySettings(store).hideHud)
    }

    @Test
    fun resetTurnsEverythingOff() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        p.streamerMode = true
        p.privacyMode = true
        p.hideHud = true
        p.reset()
        assertFalse(p.streamerMode || p.privacyMode || p.hideHud)
    }

    @Test
    fun fullRedactionWithBothModesOn() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        p.streamerMode = true
        p.privacyMode = true
        val r = p.redactionPolicy()
        assertFalse(r.includeAccountName)
        assertFalse(r.includeDiagnostics)
        assertFalse(r.includeDeviceModel)
        // The version is never withheld: an unidentifiable report is useless.
        assertTrue(r.includeVersion)
        assertTrue(r.anythingIncluded)
    }

    @Test
    fun privacyModeAloneWithholdsDiagnosticsAndIdentity() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        p.privacyMode = true
        val r = p.redactionPolicy()
        assertFalse(r.includeDiagnostics)
        // Privacy mode covers account identity as well as diagnostics.
        assertFalse(r.includeAccountName)
        // It is not a device-anonymity mode.
        assertTrue(r.includeDeviceModel)
        assertTrue(r.includeVersion)
    }

    @Test
    fun streamerModeAloneWithholdsDeviceModelAndIdentity() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        p.streamerMode = true
        val r = p.redactionPolicy()
        assertFalse(r.includeDeviceModel)
        // The account name is part of the Xykell identity streamer mode hides.
        assertFalse(r.includeAccountName)
        // Streamer mode is not a diagnostics-redaction mode.
        assertTrue(r.includeDiagnostics)
    }

    @Test
    fun allModesOffIncludesEverything() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        val r = p.redactionPolicy()
        assertTrue(r.includeAccountName && r.includeDiagnostics &&
            r.includeDeviceModel && r.includeVersion)
    }

    @Test
    fun knownKeysAreEnumerated() {
        val p = PrivacySettings(PrivacySettings.MemoryStore())
        assertTrue(p.isKnown(PrivacySettings.KEY_STREAMER))
        assertTrue(p.isKnown(PrivacySettings.KEY_PRIVACY))
        assertTrue(p.isKnown(PrivacySettings.KEY_HIDE_HUD))
        assertFalse(p.isKnown("something_else"))
    }

    // --- CountdownTimer

    @Test
    fun timerIsIdleUntilStarted() {
        var now = 0L
        val t = CountdownTimer { now }
        assertEquals(CountdownTimer.Phase.IDLE, t.snapshot().state)
        assertEquals("--:--", t.format())
    }

    @Test
    fun timerCountsDownFromAnInjectedClock() {
        var now = 1_000L
        val t = CountdownTimer { now }
        assertTrue(t.start(65_000L) is ApiResult.Ok)
        assertEquals("01:05", t.format())
        now += 30_000
        assertEquals("00:35", t.format())
        assertTrue(t.snapshot().active)
    }

    @Test
    fun timerFinishesExactlyOnce() {
        var now = 0L
        val t = CountdownTimer { now }
        t.start(1_000L)
        now += 999
        assertEquals(CountdownTimer.Phase.RUNNING, t.snapshot().state)
        now += 2
        assertEquals(CountdownTimer.Phase.FINISHED, t.snapshot().state)
        // Still finished on re-read; a caller cannot double-count it.
        assertEquals(CountdownTimer.Phase.FINISHED, t.snapshot().state)
        assertEquals("00:00", t.format())
    }

    @Test
    fun timerRejectsAbsurdDurations() {
        val t = CountdownTimer { 0L }
        assertTrue(t.start(0L) is ApiResult.Invalid)
        assertTrue(t.start(999L) is ApiResult.Invalid)
        assertTrue(t.start(CountdownTimer.MAX_DURATION + 1) is ApiResult.Invalid)
        assertEquals(CountdownTimer.Phase.IDLE, t.snapshot().state)
    }

    @Test
    fun timerStopReturnsToIdle() {
        var now = 0L
        val t = CountdownTimer { now }
        t.start(10_000L)
        t.stop()
        assertEquals(CountdownTimer.Phase.IDLE, t.snapshot().state)
        assertEquals("--:--", t.format())
    }

    @Test
    fun timerRestartReusesThePreviousDuration() {
        var now = 0L
        val t = CountdownTimer { now }
        t.start(30_000L)
        now += 10_000
        assertTrue(t.restart() is ApiResult.Ok)
        assertEquals("00:30", t.format())
        // Restart with no prior duration is refused, not defaulted to zero.
        val fresh = CountdownTimer { 0L }
        assertTrue(fresh.restart() is ApiResult.Invalid)
    }

    // --- WaypointStore

    private fun wp(id: String = "w1", name: String = "Base") = WaypointStore.Waypoint(
        id = id,
        name = name,
        dimension = WaypointStore.DIM_OVERWORLD,
        x = 100.0,
        y = 64.0,
        z = -200.0,
        colorArgb = WaypointStore.DEFAULT_COLOR,
        showOnMap = true,
    )

    @Test
    fun waypointRoundTrips() {
        val s = WaypointStore()
        assertTrue(s.put(wp()) is WaypointStore.Result.Ok)
        assertTrue(s.put(wp("w2", "Mine")) is WaypointStore.Result.Ok)
        val back = WaypointStore()
        val r = back.deserialize(s.serialize())
        assertTrue(r is WaypointStore.Result.Ok)
        assertEquals(2, (r as WaypointStore.Result.Ok).value)
        assertEquals(100.0, back.get("w1")!!.x, 1e-9)
        assertEquals(-200.0, back.get("w1")!!.z, 1e-9)
    }

    @Test
    fun serializationIsDeterministic() {
        val s = WaypointStore()
        s.put(wp("b", "Zulu"))
        s.put(wp("a", "Alpha"))
        assertEquals(s.serialize(), s.serialize())
        // Sorted by name: Alpha before Zulu, not insertion order.
        assertEquals(listOf("Alpha", "Zulu"), s.sorted().map { it.name })
    }

    @Test
    fun invalidWaypointIsRefused() {
        val s = WaypointStore()
        assertTrue(s.put(wp(id = "")) is WaypointStore.Result.Invalid)
        assertTrue(s.put(wp(name = "")) is WaypointStore.Result.Invalid)
        assertTrue(s.put(wp().copy(dimension = "aether")) is WaypointStore.Result.Invalid)
        assertTrue(s.put(wp().copy(x = Double.NaN)) is WaypointStore.Result.Invalid)
        assertTrue(s.put(wp().copy(x = 1e12)) is WaypointStore.Result.Invalid)
        assertTrue(s.put(wp().copy(y = 1e9)) is WaypointStore.Result.Invalid)
        assertEquals(0, s.size())
    }

    @Test
    fun oversizedNamesAreRefused() {
        val s = WaypointStore()
        assertTrue(s.put(wp(name = "x".repeat(200))) is WaypointStore.Result.Invalid)
        assertTrue(s.put(wp(id = "x".repeat(200))) is WaypointStore.Result.Invalid)
    }

    @Test
    fun malformedDocumentIsRefusedWholesaleAndKeepsTheOldBook() {
        val s = WaypointStore()
        s.put(wp())
        assertTrue(s.deserialize("{not json") is WaypointStore.Result.Invalid)
        assertEquals("previous book survives a refused load", 1, s.size())
        assertTrue(s.deserialize("""{"waypoints":[1,2]}""") is WaypointStore.Result.Invalid)
        assertEquals(1, s.size())
        assertTrue(s.deserialize("""{"schemaVersion":99,"waypoints":[]}""") is WaypointStore.Result.Invalid)
        assertEquals(1, s.size())
    }

    @Test
    fun oneInvalidEntryRejectsTheWholeDocument() {
        val s = WaypointStore()
        val bad = """{"schemaVersion":1,"waypoints":[
          {"id":"ok","name":"Ok","dimension":"overworld","x":1,"y":2,"z":3},
          {"id":"bad","name":"Bad","dimension":"nether","x":1e99,"y":2,"z":3}]}"""
        assertTrue(s.deserialize(bad) is WaypointStore.Result.Invalid)
        assertEquals(0, s.size())
    }

    @Test
    fun unknownFieldsAreTolerated() {
        val s = WaypointStore()
        val json = """{"schemaVersion":1,"future":true,"waypoints":[
          {"id":"a","name":"A","dimension":"overworld","x":1,"y":2,"z":3,"extra":42}]}"""
        assertTrue(s.deserialize(json) is WaypointStore.Result.Ok)
        assertEquals(1, s.size())
    }

    @Test
    fun emptyDocumentClearsTheBook() {
        val s = WaypointStore()
        s.put(wp())
        assertTrue(s.deserialize("") is WaypointStore.Result.Ok)
        assertEquals(0, s.size())
    }

    @Test
    fun missingArrayIsRefused() {
        assertTrue(WaypointStore().deserialize("""{"schemaVersion":1}""") is WaypointStore.Result.Invalid)
    }

    @Test
    fun removeAndClearWork() {
        val s = WaypointStore()
        s.put(wp("a"))
        s.put(wp("b"))
        assertTrue(s.remove("a"))
        assertFalse(s.remove("a"))
        assertEquals(1, s.size())
        s.clear()
        assertEquals(0, s.size())
        assertNull(s.get("b"))
    }
}
