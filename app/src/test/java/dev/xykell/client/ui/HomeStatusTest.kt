package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStatusTest {
    private fun rows(
        mc: String? = "1.21.0",
        profile: String = "Default",
        summary: String = "Runtime: active",
        obs: String = "IDLE"
    ) = HomeStatus.rows(mc, profile, "0.1.0-m1.5", "0.1.0-m1", summary, obs)

    @Test
    fun rowOrderFixed() {
        assertEquals(
            listOf("Minecraft", "Profile", "Xykell", "Runtime", "Observation"),
            rows().map { it.label }
        )
        assertEquals(5, rows().size)
    }

    @Test
    fun installedMinecraftIsOk() {
        val row = rows().first()
        assertEquals("1.21.0 (installed)", row.value)
        assertEquals(StatusTone.OK, row.tone)
    }

    @Test
    fun missingMinecraftIsWarn() {
        val row = rows(mc = null).first()
        assertEquals("not installed", row.value)
        assertEquals(StatusTone.WARN, row.tone)
    }

    @Test
    fun blankProfileIsMuted() {
        val row = rows(profile = "  ").first { it.label == "Profile" }
        assertEquals("none", row.value)
        assertEquals(StatusTone.MUTED, row.tone)
    }

    @Test
    fun presentProfileIsOk() {
        val row = rows().first { it.label == "Profile" }
        assertEquals("Default", row.value)
        assertEquals(StatusTone.OK, row.tone)
    }

    @Test
    fun xykellRowAlwaysOk() {
        val row = rows().first { it.label == "Xykell" }
        assertEquals("0.1.0-m1.5 (native 0.1.0-m1)", row.value)
        assertEquals(StatusTone.OK, row.tone)
    }

    @Test
    fun runtimeUnavailableIsError() {
        val summary = "Runtime: unavailable (native bridge missing)"
        assertEquals("unavailable (native bridge missing)", HomeStatus.runtimeLine(summary))
        assertEquals(StatusTone.ERROR, HomeStatus.runtimeTone(summary))
    }

    @Test
    fun runtimeLineFallsBackToUnknown() {
        assertEquals("unknown", HomeStatus.runtimeLine(""))
        assertEquals("active", HomeStatus.runtimeLine("Runtime: active"))
        assertEquals("active", HomeStatus.runtimeLine("Runtime: active\nProvider: fake"))
        assertEquals(StatusTone.MUTED, HomeStatus.runtimeTone("Runtime: active"))
    }

    @Test
    fun observationTones() {
        assertEquals(StatusTone.OK, HomeStatus.obsTone("OBSERVING"))
        assertEquals(StatusTone.WARN, HomeStatus.obsTone("STARTING"))
        assertEquals(StatusTone.ERROR, HomeStatus.obsTone("FAILED"))
        assertEquals(StatusTone.MUTED, HomeStatus.obsTone("IDLE"))
        assertEquals(StatusTone.MUTED, HomeStatus.obsTone("STOPPED"))
    }
}
