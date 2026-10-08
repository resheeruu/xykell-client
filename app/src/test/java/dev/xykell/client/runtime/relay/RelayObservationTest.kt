package dev.xykell.client.runtime.relay

import dev.xykell.client.runtime.modules.ModuleWire
import dev.xykell.client.runtime.observation.ChatMessage
import dev.xykell.client.runtime.observation.ObservationExternal
import dev.xykell.client.runtime.observation.Population
import dev.xykell.client.runtime.observation.Translated
import dev.xykell.client.runtime.observation.Travelled
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The observation seam on a terminating session.
 *
 * What only this can fail: that observation sees the packet *before* the
 * modules rewrite it, that the clientbound leg is what teaches the translator
 * the self id, and that a throwing sink cannot take the relay down.
 */
class RelayObservationTest {

    private val captured = ArrayList<Translated>()

    /** Chat only: the population report is a separate, expected observation. */
    private fun chats() = captured.filterIsInstance<ChatMessage>()

    @After
    fun tearDown() {
        ObservationExternal.detach()
    }

    private fun attach() = ObservationExternal.attach { captured.add(it) }

    /** Text 0x09 chat: needsTranslation 0, category 1, type 1. */
    private fun chat(message: String): ByteArray = ModuleWire.build(
        0x09,
        byteArrayOf(0),
        byteArrayOf(1),
        byteArrayOf(1),
        ModuleWire.writeVarString("Steve"),
        ModuleWire.writeVarString(message),
        ModuleWire.writeVarString(""), // xboxUserId
        ModuleWire.writeVarString(""), // platformChatId
        byteArrayOf(0), // no filteredMessage
    )

    private val forwarder = RelayListener { _, packet -> listOf(packet) }

    @Test
    fun `a clientbound chat becomes a chat message`() {
        attach()
        val obs = RelayObservation(forwarder, entities = EntityTable(), nowMs = { 1_000L })
        obs.transform(RelayDirection.TO_CLIENT, chat("hi"))

        assertEquals(1, chats().size)
        val item = chats().single()
        assertEquals("hi", (item as ChatMessage).message)
        assertEquals(1_000L, item.observedAtMs)
        assertEquals(1L, obs.snapshot.chatCount)
    }

    @Test
    fun `observation sees the packet before the delegate rewrites it`() {
        attach()
        val dropping = RelayListener { _, _ -> emptyList() }
        val obs = RelayObservation(dropping, entities = EntityTable(), nowMs = { 1L })
        val out = obs.transform(RelayDirection.TO_CLIENT, chat("before"))

        assertTrue("delegate never ran", out.isEmpty())
        assertEquals("observation missed a dropped packet", 1, chats().size)
    }

    /**
     * The translator reports chat by packet type, not by leg, so the player's
     * own outbound chat is observed too. Pinned rather than hidden: it is a
     * known cosmetic echo in the observation feed, and a filter here would be a
     * second place that decides what chat is.
     */
    @Test
    fun `serverbound chat is observed too (known echo)`() {
        attach()
        val obs = RelayObservation(forwarder, entities = EntityTable(), nowMs = { 1L })
        obs.transform(RelayDirection.TO_SERVER, chat("mine"))
        assertEquals(1, chats().size)
        assertEquals("mine", chats().single().message)
    }

    @Test
    fun `an undecodable packet yields nothing and still forwards`() {
        attach()
        val obs = RelayObservation(forwarder, entities = EntityTable(), nowMs = { 1L })
        val junk = byteArrayOf(0x7f, 0x7e, 0x01)
        val out = obs.transform(RelayDirection.TO_CLIENT, junk)

        assertEquals(listOf(junk.contentToString()), out.map { it.contentToString() })
        assertEquals(0, captured.filterIsInstance<Travelled>().size)
    }

    @Test
    fun `a throwing sink cannot take the relay down`() {
        ObservationExternal.attach { throw IllegalStateException("sink boom") }
        val obs = RelayObservation(forwarder, entities = EntityTable(), nowMs = { 1L })
        val out = obs.transform(RelayDirection.TO_CLIENT, chat("boom"))
        assertEquals(1, out.size)
    }

    @Test
    fun `travel is emitted once the self id is learned`() {
        attach()
        val obs = RelayObservation(forwarder, entities = EntityTable(), nowMs = { 1L })
        // The first clientbound MovePlayer is the baseline and teaches the id;
        // only a later position change is travel.
        obs.transform(RelayDirection.TO_CLIENT, movePlayer(runtimeId = 7, x = 1f))
        obs.transform(RelayDirection.TO_CLIENT, movePlayer(runtimeId = 7, x = 5f))

        val travelled = captured.filterIsInstance<Travelled>()
        assertEquals(1, travelled.size)
        assertEquals(4.0, travelled.single().metersTravelled, 0.0001)
    }

    /**
     * The population report is throttled: one line per interval, not one per
     * packet. An unthrottled report would be hundreds of identical
     * observations per second on a busy server.
     */
    @Test
    fun `population is reported once per interval not once per packet`() {
        attach()
        val entities = EntityTable()
        var now = 1_000L
        val obs = RelayObservation(
            forwarder,
            entities = entities,
            nowMs = { now },
        )
        val chat = chat("a")
        obs.transform(RelayDirection.TO_CLIENT, chat)
        val first = captured.filterIsInstance<Population>().size
        assertEquals(1, first)
        repeat(20) { obs.transform(RelayDirection.TO_CLIENT, chat) }
        assertEquals(1, captured.filterIsInstance<Population>().size)
        now += RelayObservation.DEFAULT_POPULATION_INTERVAL_MS + 1
        obs.transform(RelayDirection.TO_CLIENT, chat)
        assertEquals(2, captured.filterIsInstance<Population>().size)
    }

    /** MovePlayer 0x13 in registry field order. */
    private fun movePlayer(runtimeId: Int, x: Float): ByteArray = ModuleWire.build(
        0x13,
        ModuleWire.writeVarUInt(runtimeId),
        ModuleWire.writeF32LE(x),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(0f), // pitch
        ModuleWire.writeF32LE(90f), // yaw
        ModuleWire.writeF32LE(0f), // headYaw
        byteArrayOf(0), // mode: normal
        byteArrayOf(0), // onGround
        ModuleWire.writeVarUInt(0), // riding
        ModuleWire.writeVarUInt(42), // tick
    )
}