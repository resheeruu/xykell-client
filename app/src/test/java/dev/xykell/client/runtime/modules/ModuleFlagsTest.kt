package dev.xykell.client.runtime.modules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile-flag reader. Its whole job is to be wrong in the safe direction:
 * an unreadable or malformed profile must disable everything, never enable a
 * module the user did not switch on.
 */
class ModuleFlagsTest {

    @Test
    fun `an explicit true enables the id`() {
        val json = """{"modules":{"xykell.misc.disabler":true}}"""
        assertTrue(ModuleFlags.isEnabled(json, "xykell.misc.disabler"))
    }

    @Test
    fun `an explicit false disables the id`() {
        val json = """{"modules":{"xykell.misc.disabler":false}}"""
        assertFalse(ModuleFlags.isEnabled(json, "xykell.misc.disabler"))
    }

    @Test
    fun `an absent id is not enabled`() {
        assertFalse(ModuleFlags.isEnabled("""{"modules":{}}""", "xykell.misc.disabler"))
        assertFalse(ModuleFlags.isEnabled("""{"modules":{}}""", "not.a.module"))
    }

    @Test
    fun `a profile without a modules object enables nothing`() {
        assertFalse(ModuleFlags.isEnabled("""{"name":"Default"}""", "xykell.misc.disabler"))
    }

    @Test
    fun `null blank and malformed input enable nothing`() {
        assertFalse(ModuleFlags.isEnabled(null, "xykell.misc.disabler"))
        assertFalse(ModuleFlags.isEnabled("", "xykell.misc.disabler"))
        assertFalse(ModuleFlags.isEnabled("   ", "xykell.misc.disabler"))
        assertFalse(ModuleFlags.isEnabled("not json at all", "xykell.misc.disabler"))
        assertFalse(ModuleFlags.isEnabled("[1,2,3]", "xykell.misc.disabler"))
    }

    @Test
    fun `a non-boolean value does not enable the id`() {
        assertFalse(ModuleFlags.isEnabled("""{"modules":{"a":"yes"}}""", "a"))
        assertFalse(ModuleFlags.isEnabled("""{"modules":{"a":1}}""", "a"))
    }

    @Test
    fun `predicate agrees with isEnabled over a whole profile`() {
        val json = """
            {"modules":{
              "xykell.combat.velocity":true,
              "xykell.misc.disabler":false,
              "xykell.visual.fullbright":true
            }}
        """.trimIndent()
        val p = ModuleFlags.predicate(json)
        assertTrue(p("xykell.combat.velocity"))
        assertFalse(p("xykell.misc.disabler"))
        assertTrue(p("xykell.visual.fullbright"))
        assertFalse(p("xykell.movement.fly"))
    }

    @Test
    fun `malformed profile gives a predicate that disables everything`() {
        val p = ModuleFlags.predicate("{{{")
        for (id in ModuleRuntime.ALL_IMPLEMENTED) {
            assertFalse("$id enabled from an unreadable profile", p(id))
        }
    }

    @Test
    fun `the predicate drives the runtime like the UI switch does`() {
        val json = """{"modules":{"xykell.misc.disabler":true}}"""
        val rt = ModuleRuntime(ModuleFlags.predicate(json), emptyMap(), kotlin.random.Random(7))
        val health = ModuleWire.build(0x2A, ModuleWire.writeVarInt(20))
        assertEquals0(health, rt)
    }

    private fun assertEquals0(health: ByteArray, rt: ModuleRuntime) {
        org.junit.Assert.assertEquals(
            emptyList<String>(),
            rt.transform(
                dev.xykell.client.runtime.relay.RelayDirection.TO_CLIENT,
                health,
            ).map { it.contentToString() },
        )
    }
}