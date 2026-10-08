package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutomationModules is mostly a refusal list, so the tests mostly hold the
 * refusal list honest: every one of the 19 AUTOMATION ids must be classified
 * exactly once with a non-empty reason, the two sets must not overlap, and the
 * one real transform (ghost) must actually drop chat rather than pass it on.
 */
class AutomationModulesTest {

    /** Text 0x09, the layout BedrockPackets.text() decodes. */
    private fun text(type: Int, message: String, category: Int = 0): ByteArray {
        var body = byteArrayOf(0, category.toByte(), type.toByte())
        if (category == 1) body += ModuleWire.writeVarString("Steve")
        body += ModuleWire.writeVarString(message)
        if (category == 2) body += ModuleWire.writeVarUInt(0)
        body += ModuleWire.writeVarString("")
        body += ModuleWire.writeVarString("")
        body += byteArrayOf(0)
        return ModuleWire.build(0x09, body)
    }

    private fun asStrings(out: List<ByteArray>): List<String> = out.map { it.contentToString() }

    // ------------------------------------------------------------- ghost

    @Test
    fun ghost_dropsPlayerChat() {
        val packet = text(1, "hello world", category = 1)
        assertEquals(emptyList<String>(), asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)))
    }

    @Test
    fun ghost_dropsWhisper() {
        val packet = text(7, "psst", category = 1)
        assertEquals(emptyList<String>(), asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)))
    }

    @Test
    fun ghost_dropsEmptyChatMessageToo() {
        val packet = text(1, "", category = 1)
        assertEquals(emptyList<String>(), asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)))
    }

    @Test
    fun ghost_keepsSystemMessage() {
        val packet = text(5, "Server closed")
        val out = AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)
        assertEquals(listOf(packet.contentToString()), asStrings(out))
    }

    @Test
    fun ghost_keepsTranslationMessage() {
        val packet = text(2, "chat.type.text", category = 2)
        val out = AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)
        assertEquals(listOf(packet.contentToString()), asStrings(out))
    }

    /** Not a Text packet: nothing to hide, must forward byte-identical. */
    @Test
    fun ghost_keepsNonTextPacket() {
        val packet = ModuleWire.build(0x0A, ModuleWire.writeVarInt(6000))
        val out = AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)
        assertEquals(listOf(packet.contentToString()), asStrings(out))
    }

    /** Body too short to hold the type byte: forward rather than guess. */
    @Test
    fun ghost_keepsTruncatedTextPacket() {
        val packet = ModuleWire.build(0x09, byteArrayOf(0, 1))
        val out = AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)
        assertEquals(listOf(packet.contentToString()), asStrings(out))
    }

    /**
     * A chat type byte followed by a varstring length that overruns the packet:
     * the type is still at its fixed offset, so this one is dropped. Pins that
     * the transform reads the prefix only and does not validate the tail.
     */
    @Test
    fun ghost_dropsChatWithTruncatedTail() {
        val packet = ModuleWire.build(0x09, byteArrayOf(0, 0, 1, -56, 1, 2))
        assertEquals(emptyList<String>(), asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)))
    }

    @Test
    fun ghost_keepsEmptyPacket() {
        val packet = ByteArray(0)
        assertEquals(
            listOf(packet.contentToString()),
            asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet)),
        )
    }

    /**
     * Every id claiming to be implemented must be reachable through transform
     * and must do something. Fails if a new IMPLEMENTED entry is added without
     * a drop/rewrite branch, or if ghost's branch is deleted.
     */
    @Test
    fun everyImplementedIdActuallyChangesAChatPacket() {
        // Only ghost is a chat filter. The other IMPLEMENTED ids rewrite
        // PlayerAction or produce tap plans, so they must forward chat
        // untouched — checking them here would assert the opposite of correct.
        val chat = text(1, "visible", category = 1)
        val chatFilters = setOf("xykell.automation.ghost")
        assertEquals(chatFilters, AutomationModules.IMPLEMENTED.intersect(chatFilters))
        for (id in chatFilters) {
            val out = AutomationModules.transform(id, RelayDirection.TO_CLIENT, chat)
            assertTrue(
                "$id is in IMPLEMENTED but forwarded the chat packet unchanged",
                asStrings(out) != listOf(chat.contentToString()),
            )
        }
        for (id in AutomationModules.IMPLEMENTED - chatFilters) {
            val out = AutomationModules.transform(id, RelayDirection.TO_CLIENT, chat)
            assertEquals(
                "$id must leave an unrelated chat packet untouched",
                listOf(chat.contentToString()),
                asStrings(out),
            )
        }
    }

    // ----------------------------------------------------- classification

    @Test
    fun implementedSetIsExactlyGhost() {
        // Pinned so a new id cannot be added to IMPLEMENTED without this
        // failing and forcing a deliberate decision (and a test for it).
        assertEquals(
            setOf(
                "xykell.automation.auto_eat",
                "xykell.automation.auto_fish",
                "xykell.automation.ghost",
                "xykell.automation.no_break_delay",
            ),
            AutomationModules.IMPLEMENTED,
        )
    }

    @Test
    fun everyAutomationIdIsClassified() {
        val expected = listOf(
            "auto_eat", "auto_fish", "auto_refill", "auto_steal", "auto_tool",
            "auto_equip", "auto_armor", "auto_sign", "auto_sell", "auto_mine",
            "auto_dig", "inventory_cleaner", "no_break_delay", "ghost",
            "auto_tool_swap", "auto_gg", "command_hotkey", "text_hotkey",
            "inventory_lock",
        ).map { "xykell.automation.$it" }.toSet()
        val classified = AutomationModules.IMPLEMENTED + AutomationModules.IMPOSSIBLE.keys
        assertEquals(expected, classified)
    }

    @Test
    fun impossibleAndImplementedDoNotOverlap() {
        for (id in AutomationModules.IMPLEMENTED) {
            assertTrue("$id is both implemented and impossible", !AutomationModules.IMPOSSIBLE.containsKey(id))
        }
    }

    @Test
    fun everyImpossibleIdCarriesAReason() {
        for ((id, reason) in AutomationModules.IMPOSSIBLE) {
            assertTrue("$id has no reason", reason.isNotBlank())
            assertTrue("$id reason is too short to be useful", reason.length > 40)
        }
    }

    /** An id outside this batch is never touched, even if it looks like one. */
    @Test
    fun unknownIdForwardsUntouched() {
        val packet = text(1, "hello", category = 1)
        for (id in listOf("xykell.automation.not_a_module", "xykell.player.ghost", "")) {
            val out = AutomationModules.transform(id, RelayDirection.TO_CLIENT, packet)
            assertEquals(listOf(packet.contentToString()), asStrings(out))
        }
    }

    /** Every IMPOSSIBLE id must forward untouched: a refusal is not a hook. */
    @Test
    fun impossibleIdsForwardUntouched() {
        val packet = text(1, "hello", category = 1)
        for (id in AutomationModules.IMPOSSIBLE.keys) {
            val out = AutomationModules.transform(id, RelayDirection.TO_CLIENT, packet)
            assertEquals("id=$id", listOf(packet.contentToString()), asStrings(out))
        }
    }

    @Test
    fun transformIsPureAcrossRepeatedCalls() {
        val packet = text(1, "same", category = 1)
        val first = asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet))
        val second = asStrings(AutomationModules.transform("xykell.automation.ghost", RelayDirection.TO_CLIENT, packet))
        assertEquals(first, second)
        assertEquals(emptyList<String>(), first)
    }

    @Test
    fun `ghost keeps the players own outbound chat`() {
        val chat = text(1, "secret")
        val out = AutomationModules.transform(
            "xykell.automation.ghost", RelayDirection.TO_SERVER, chat,
        )
        assertEquals(listOf(chat.contentToString()), out.map { it.contentToString() })
    }
}
