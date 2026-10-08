package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.xykell.client.runtime.modules.ModuleWire

/**
 * EntityTable is the load-bearing piece for every targeting and ESP module:
 * before it, "the relay sees no world" was true. These tests pin the four
 * packets it consumes against the generated [BedrockPacketIds] table, so a
 * protocol bump that moves an id fails here instead of silently emptying the
 * table in production.
 */
class EntityTableTest {

    private fun header(id: Int): ByteArray = ModuleWire.writeVarUInt(id)

    private fun varLong(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) {
                out.write(x.toInt())
                return out.toByteArray()
            }
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun varString(s: String): ByteArray =
        ModuleWire.writeVarUInt(s.length) + s.toByteArray()

    private fun addPlayer(runtimeId: Long, name: String, x: Float, y: Float, z: Float): ByteArray =
        header(BedrockPacketIds.infoOf("AddPlayer")!!.id) +
            ByteArray(16) + // uuid
            varString(name) +
            varLong(runtimeId) +
            byteArrayOf(0) + // platform_chat_id
            ModuleWire.writeF32LE(x) + ModuleWire.writeF32LE(y) + ModuleWire.writeF32LE(z) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + // velocity
            ModuleWire.writeF32LE(10f) + ModuleWire.writeF32LE(90f) + ModuleWire.writeF32LE(90f) // rot

    private fun addEntity(runtimeId: Long, type: String, x: Float, y: Float, z: Float): ByteArray =
        header(BedrockPacketIds.infoOf("AddEntity")!!.id) +
            varLong(runtimeId) + // unique id
            varLong(runtimeId) + // runtime id
            varString(type) +
            ModuleWire.writeF32LE(x) + ModuleWire.writeF32LE(y) + ModuleWire.writeF32LE(z) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f)

    private fun movePlayer(runtimeId: Long, x: Float, y: Float, z: Float): ByteArray =
        header(BedrockPacketIds.infoOf("MovePlayer")!!.id) +
            varLong(runtimeId) +
            ModuleWire.writeF32LE(x) + ModuleWire.writeF32LE(y) + ModuleWire.writeF32LE(z) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) +
            byteArrayOf(0, 1) // mode, onGround

    private fun removeEntity(runtimeId: Long): ByteArray =
        header(BedrockPacketIds.infoOf("RemoveEntity")!!.id) + varLong(runtimeId)

    // ------------------------------------------------------------------- tests

    @Test
    fun `generated ids match the upstream protocol`() {
        assertEquals(0x0c, BedrockPacketIds.infoOf("AddPlayer")!!.id)
        assertEquals(0x0d, BedrockPacketIds.infoOf("AddEntity")!!.id)
        assertEquals(0x0e, BedrockPacketIds.infoOf("RemoveEntity")!!.id)
        assertEquals(0x13, BedrockPacketIds.infoOf("MovePlayer")!!.id)
        assertEquals(0x1c, BedrockPacketIds.infoOf("MobEffect")!!.id)
        assertEquals(0x24, BedrockPacketIds.infoOf("PlayerAction")!!.id)
        assertEquals(244, BedrockPacketIds.allNames.size)
    }

    @Test
    fun `direction flags come from upstream bounds`() {
        assertTrue(BedrockPacketIds.infoOf("MobEffect")!!.toClient)
        assertTrue(!BedrockPacketIds.infoOf("MobEffect")!!.toServer)
        assertTrue(BedrockPacketIds.infoOf("PlayerAction")!!.toServer)
        assertTrue(!BedrockPacketIds.infoOf("PlayerAction")!!.toClient)
    }

    @Test
    fun `add player records name and position`() {
        val t = EntityTable()
        assertTrue(t.observe(addPlayer(7, "steve", 1f, 2f, 3f)))

        val e = t.get(7)!!
        assertEquals("steve", e.name)
        assertTrue(e.isPlayer)
        assertEquals(1f, e.x, 0.0001f)
        assertEquals(2f, e.y, 0.0001f)
        assertEquals(3f, e.z, 0.0001f)
    }

    @Test
    fun `add entity records type and position`() {
        val t = EntityTable()
        assertTrue(t.observe(addEntity(9, "minecraft:pig", 10f, 20f, 30f)))

        val e = t.get(9)!!
        assertEquals("minecraft:pig", e.type)
        assertTrue(!e.isPlayer)
        assertEquals(20f, e.y, 0.0001f)
    }

    @Test
    fun `move player updates an existing entity`() {
        val t = EntityTable()
        t.observe(addPlayer(7, "steve", 0f, 0f, 0f))
        assertTrue(t.observe(movePlayer(7, 5f, 6f, 7f)))

        val e = t.get(7)!!
        assertEquals(5f, e.x, 0.0001f)
        assertEquals(6f, e.y, 0.0001f)
        // A move must not clobber the identity a spawn established.
        assertEquals("steve", e.name)
    }

    @Test
    fun `move for an unseen entity records it rather than dropping the position`() {
        val t = EntityTable()
        assertTrue(t.observe(movePlayer(42, 1f, 2f, 3f)))
        assertNotNull(t.get(42))
        assertEquals(EntityTable.TYPE_UNKNOWN, t.get(42)!!.type)
    }

    @Test
    fun `remove entity drops it`() {
        val t = EntityTable()
        t.observe(addEntity(9, "minecraft:pig", 0f, 0f, 0f))
        assertEquals(1, t.size)
        assertTrue(t.observe(removeEntity(9)))
        assertEquals(0, t.size)
        assertNull(t.get(9))
    }

    @Test
    fun `nearest orders by true distance`() {
        val t = EntityTable()
        t.observe(addEntity(1, "a", 1f, 0f, 0f))
        t.observe(addEntity(2, "b", 3f, 0f, 0f))
        t.observe(addEntity(3, "c", 20f, 0f, 0f))

        assertEquals(listOf(1L, 2L, 3L), t.near(0f, 0f, 0f, 100f).map { it.runtimeId })
        assertEquals(1L, t.nearest(0f, 0f, 0f, 100f)!!.runtimeId)
        // A radius that excludes everything returns null rather than the farthest.
        assertNull(t.nearest(0f, 0f, 0f, 0.5f))
    }

    @Test
    fun `nearest can exclude an id`() {
        val t = EntityTable()
        t.observe(addEntity(1, "a", 1f, 0f, 0f))
        t.observe(addEntity(2, "b", 2f, 0f, 0f))
        assertEquals(2L, t.nearest(0f, 0f, 0f, 100f, excludeId = 1L)!!.runtimeId)
    }

    @Test
    fun `the local player is never recorded as a target`() {
        val t = EntityTable()
        // RelaySession learns the self id from client-bound moves; anything
        // carrying it must be skipped or every distance check is measured
        // from the player to themselves.
        assertTrue(!t.observe(addPlayer(5, "me", 0f, 0f, 0f), selfRuntimeId = 5L))
        assertEquals(0, t.size)
        assertTrue(!t.observe(movePlayer(5, 9f, 9f, 9f), selfRuntimeId = 5L))
    }

    @Test
    fun `table is bounded and evicts the least recently seen`() {
        val t = EntityTable(maxEntities = 4)
        for (i in 1L..4L) {
            t.observe(addEntity(i, "e", i.toFloat(), 0f, 0f))
            t.onTick()
        }
        assertEquals(4, t.size)

        t.observe(addEntity(99, "new", 0f, 0f, 0f))
        assertEquals(4, t.size)
        assertNotNull(t.get(99))
        assertNull("the oldest entry should have been evicted", t.get(1L))
    }

    @Test
    fun `unknown and malformed packets are ignored not thrown`() {
        val t = EntityTable()
        assertTrue(!t.observe(byteArrayOf()))
        assertTrue(!t.observe(byteArrayOf(0x09, 0x00))) // Text: not ours
        assertTrue(!t.observe(addPlayer(7, "steve", 0f, 0f, 0f).copyOf(8))) // truncated
        assertEquals(0, t.size)
    }

    @Test
    fun `an empty string field must not shift the fields after it`() {
        // Regression: an empty var-length string returned null WITHOUT moving
        // the cursor, so every field after AddPlayer's (normally empty)
        // platform_chat_id was read one byte early and every entity position
        // came out as a denormal float.
        val t = EntityTable()
        assertTrue(t.observe(addPlayer(7, "steve", 1f, 2f, 3f)))
        val e = t.get(7)!!
        assertEquals(1f, e.x, 0.0001f)
        assertEquals(2f, e.y, 0.0001f)
        assertEquals(3f, e.z, 0.0001f)
        assertEquals(10f, e.pitch, 0.0001f)
        assertEquals(90f, e.yaw, 0.0001f)
        assertEquals(90f, e.headYaw, 0.0001f)
    }

    @Test
    fun `a non-empty string field also parses to the end of the packet`() {
        val t = EntityTable()
        // AddEntity puts the type string right after two var longs; a name with
        // a multi-byte varuint length prefix exercises the same cursor path.
        val name = "minecraft:zombie_pigman"
        assertTrue(t.observe(addEntity(3, name, 4f, 5f, 6f)))
        assertEquals(name, t.get(3)!!.type)
        assertEquals(4f, t.get(3)!!.x, 0.0001f)
    }

    @Test
    fun `clear resets the session`() {
        val t = EntityTable()
        t.observe(addEntity(1, "a", 0f, 0f, 0f))
        t.clear()
        assertEquals(0, t.size)
    }
}