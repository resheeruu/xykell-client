package dev.xykell.client.runtime.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability gating: a script may only reach what the host granted it. */
class ScriptApiTest {

    private val readOnly = setOf(
        ScriptCapability.PROFILE_READ, ScriptCapability.SETTINGS_READ,
        ScriptCapability.MODULES_READ, ScriptCapability.THEME_READ,
        ScriptCapability.HUD_READ, ScriptCapability.DIAGNOSTICS_READ,
        ScriptCapability.SESSION_READ,
    )

    @Test
    fun ungrantedCallIsDeniedNotSilentlyDropped() {
        val api = GuardedScriptApi(FakeScriptApi(), readOnly)
        val r = api.writeSetting("client", "theme", "Xykell Dark")
        assertTrue(r is ApiResult.Denied)
        assertEquals(ScriptCapability.SETTINGS_WRITE, (r as ApiResult.Denied).capability)
        assertTrue(r.errorText()!!.contains("SETTINGS_WRITE"))
    }

    @Test
    fun everyWriteIsDeniedUnderReadOnlyGrant() {
        val api = GuardedScriptApi(FakeScriptApi(), readOnly)
        assertTrue(api.switchProfile("Default") is ApiResult.Denied)
        assertTrue(api.resetProfile() is ApiResult.Denied)
        assertTrue(api.setModuleEnabled("fps", true) is ApiResult.Denied)
        assertTrue(api.switchTheme("Xykell Dark") is ApiResult.Denied)
        assertTrue(api.setHudElementVisible("fps", true) is ApiResult.Denied)
        assertTrue(api.setHudElementPosition("fps", 1, 1) is ApiResult.Denied)
        assertTrue(api.setHudElementScale("fps", 1f) is ApiResult.Denied)
        assertTrue(api.emitDiagnostic("e", "d") is ApiResult.Denied)
        assertTrue(api.notify("t", "m") is ApiResult.Denied)
    }

    @Test
    fun grantedReadsPassThrough() {
        val backing = FakeScriptApi().apply {
            settings["client/theme"] = "Xykell Dark"
            modules["fps"] = true
            hud["fps"] = HudElementState("fps", true, 1, 2, 1f)
        }
        val api = GuardedScriptApi(backing, readOnly)
        assertEquals("Xykell Dark", api.readSetting("client", "theme").valueOrNull())
        assertEquals(true, api.isModuleEnabled("fps").valueOrNull())
        assertEquals(1, api.readHudElement("fps").valueOrNull()!!.x)
        assertEquals("Default", api.activeProfile().valueOrNull())
        assertTrue(api.readSession().isOk)
    }

    @Test
    fun deniedCallDoesNotReachTheBackingStore() {
        val backing = FakeScriptApi()
        GuardedScriptApi(backing, readOnly).writeSetting("client", "theme", "leaked")
        assertTrue(backing.settings.isEmpty())
    }

    @Test
    fun defaultGrantCoversEveryCapability() {
        val api = GuardedScriptApi(FakeScriptApi(), DEFAULT_SCRIPT_CAPABILITIES)
        assertEquals(ScriptCapability.values().size, api.capabilities.size)
        assertTrue(api.writeSetting("client", "theme", "v").isOk)
    }

    @Test
    fun unavailableApiReportsUnavailableRatherThanFakingSuccess() {
        val api = UnavailableScriptApi
        assertTrue(api.activeProfile() is ApiResult.Unavailable)
        assertTrue(api.switchProfile("x") is ApiResult.Unavailable)
        assertTrue(api.notify("t", "m") is ApiResult.Unavailable)
        assertNull(api.activeProfile().valueOrNull())
        assertTrue(api.capabilities.isEmpty())
    }

    @Test
    fun backingStoreRefusalsPropagateAsTypedErrors() {
        val backing = FakeScriptApi().apply {
            unknownProfile = "ghost"
            unknownTheme = "Ghost"
            modules["fps"] = false
            settings["client/theme"] = "Xykell Dark"
            hud["fps"] = HudElementState("fps", true, 0, 0, 1f)
        }
        val api = GuardedScriptApi(backing, DEFAULT_SCRIPT_CAPABILITIES)
        assertTrue(api.isModuleEnabled("ghost_module") is ApiResult.Invalid)
        assertTrue(api.switchProfile("ghost") is ApiResult.Invalid)
        assertTrue(api.switchTheme("Ghost") is ApiResult.Invalid)
        assertTrue(api.readSetting("client", "absent") is ApiResult.Invalid)
        assertTrue(api.readHudElement("not_an_element") is ApiResult.Invalid)
    }

    @Test
    fun resetProfileIsNotExposedAsAScriptAction() {
        // A rule may switch profile but must not be able to wipe one.
        val api = GuardedScriptApi(FakeScriptApi(), DEFAULT_SCRIPT_CAPABILITIES)
        assertTrue(api.resetProfile() is ApiResult.Unavailable)
    }

    @Test
    fun errorTextIsSafeForDiagnostics() {
        val denied = GuardedScriptApi(FakeScriptApi(), readOnly)
            .switchProfile("x") as ApiResult.Denied
        assertFalse(denied.errorText()!!.contains("token"))
        assertFalse(denied.errorText()!!.contains("secret"))
    }
}
