package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WORLD batch: five derived views over UpdateBlock 0x15 / LevelChunk 0x3A, and
 * nine ids that stay impossible with a concrete reason.
 *
 * The tests exist to hold both halves honest: every registry id is accounted
 * for exactly once, no implemented id degrades into a pass-through, and no
 * input shape makes the object drop or corrupt a packet.
 */
class WorldModulesTest {

    private val allIds = listOf(
        "xykell.world.scaffold",
        "xykell.world.nuker",
        "xykell.world.fast_break",
        "xykell.world.fast_place",
        "xykell.world.spawner_protect",
        "xykell.world.block_esp",
        "xykell.world.block_tracer",
        "xykell.world.xray",
        "xykell.world.ore_esp",
        "xykell.world.chunk_borders",
        "xykell.world.chunk_finder",
        "xykell.world.new_chunks",
        "xykell.world.hole_esp",
        "xykell.world.schematic",
    )

    // ------------------------------------------------------------- fixtures

    /** UpdateBlock 0x15: three block coords, runtime id, flags, layer. */
    private fun updateBlock(x: Int, y: Int, z: Int, blockId: Int = 42): ByteArray =
        ModuleWire.build(
            0x15,
            ModuleWire.writeVarInt(x) + ModuleWire.writeVarInt(y) + ModuleWire.writeVarInt(z) +
                ModuleWire.writeVarUInt(blockId) + ModuleWire.writeVarUInt(2) + ModuleWire.writeVarUInt(0),
        )

    /** LevelChunk 0x3A prefix: chunk x, chunk z, dimension, sub-chunk count. */
    private fun levelChunk(chunkX: Int, chunkZ: Int, subChunks: Int = 3): ByteArray =
        ModuleWire.build(
            0x3A,
            ModuleWire.writeVarInt(chunkX) + ModuleWire.writeVarInt(chunkZ) +
                ModuleWire.writeVarInt(0) + ModuleWire.writeVarUInt(subChunks) + byteArrayOf(0),
        )

    private fun world(): ModuleContext {
        val ctx = ModuleContext()
        ctx.updateSelf(1L, 0f, 70f, 0f, true)
        return ctx
    }

    // -------------------------------------------------------------- coverage

    @Test
    fun registryPartitionsEveryWorldIdExactlyOnce() {
        assertEquals(14, allIds.size)
        assertEquals(allIds.toSet(), WorldModules.IMPLEMENTED + WorldModules.IMPOSSIBLE.keys)
        assertTrue(WorldModules.IMPLEMENTED.intersect(WorldModules.IMPOSSIBLE.keys).isEmpty())
    }

    /**
     * Pins the implemented set by name, so demoting one to a pass-through
     * cannot slip through the partition test above.
     */
    @Test
    fun implementedSetIsPinned() {
        assertEquals(
            setOf(
                "xykell.world.block_esp",
                "xykell.world.block_tracer",
                "xykell.world.chunk_borders",
                "xykell.world.chunk_finder",
                "xykell.world.new_chunks",
            ),
            WorldModules.IMPLEMENTED,
        )
    }

    @Test
    fun everyImpossibleIdCarriesAConcreteReason() {
        assertEquals(9, WorldModules.IMPOSSIBLE.size)
        for ((id, reason) in WorldModules.IMPOSSIBLE) {
            assertTrue("$id has no reason", reason.length > 30)
            assertTrue("$id reason does not explain the gap", reason.endsWith("."))
        }
    }

    @Test
    fun theBlockEditingIdsStayImpossible() {
        // These must originate action packets or name a block, neither of which
        // the relay can do from the bytes it terminates.
        for (id in listOf(
            "xykell.world.scaffold",
            "xykell.world.nuker",
            "xykell.world.fast_break",
            "xykell.world.fast_place",
            "xykell.world.spawner_protect",
            "xykell.world.schematic",
            "xykell.world.hole_esp",
            "xykell.world.ore_esp",
            "xykell.world.xray",
        )) {
            assertTrue("$id must stay impossible", id in WorldModules.IMPOSSIBLE)
            assertTrue("$id must not be implemented", id !in WorldModules.IMPLEMENTED)
        }
    }

    // ------------------------------------------------------- derived views

    @Test
    fun blockEspReadsTheChangedBlock() {
        val marks = WorldModules.blockMarkers(world(), updateBlock(4, 69, -6, 77), 64f)
        assertEquals(1, marks.size)
        assertEquals(4, marks[0].x)
        assertEquals(69, marks[0].y)
        assertEquals(-6, marks[0].z)
        assertEquals(77, marks[0].blockRuntimeId)
        // Distance is to the block CENTRE, not its corner: UpdateBlock gives a
        // block position and the nearest point of that block is (x+.5, y+.5, z+.5).
        assertEquals(7.1239f, marks[0].distance, 0.01f)
    }

    @Test
    fun blockEspFiltersOutDistantBlocks() {
        assertEquals(0, WorldModules.blockMarkers(world(), updateBlock(300, 69, 0), 64f).size)
        assertEquals(1, WorldModules.blockMarkers(world(), updateBlock(300, 69, 0), 1000f).size)
    }

    @Test
    fun blockTracerIsALineOutOfThePlayer() {
        val line = WorldModules.blockTracers(world(), updateBlock(4, 69, 0), 64f).single()
        assertEquals(0f, line.fromX, 0.0001f)
        assertEquals(70f, line.fromY, 0.0001f)
        assertEquals(4.5f, line.toX, 0.0001f)
        assertEquals(69.5f, line.toY, 0.0001f)
    }

    @Test
    fun blockViewsRejectAnythingThatIsNotAnUpdateBlock() {
        assertEquals(0, WorldModules.blockMarkers(world(), levelChunk(0, 0), 1000f).size)
        assertEquals(0, WorldModules.blockTracers(world(), ByteArray(0), 1000f).size)
    }

    @Test
    fun chunkFinderReportsWhereTheChunkIs() {
        val marks = WorldModules.chunkMarks(world(), levelChunk(-2, 5, 9), 1000f)
        assertEquals(1, marks.size)
        assertEquals(-2, marks[0].chunkX)
        assertEquals(5, marks[0].chunkZ)
        assertEquals(-32, marks[0].worldX)
        assertEquals(80, marks[0].worldZ)
        assertEquals(9, marks[0].subChunks)
    }

    @Test
    fun chunkFinderFiltersByDistance() {
        assertEquals(0, WorldModules.chunkMarks(world(), levelChunk(60, 0), 64f).size)
        assertEquals(1, WorldModules.chunkMarks(world(), levelChunk(60, 0), 5000f).size)
    }

    @Test
    fun chunkBordersAreFourWorldSpaceEdges() {
        val lines = WorldModules.chunkBorderLines(world(), levelChunk(0, 0))
        assertEquals(4, lines.size)
        assertEquals(
            setOf(0, 16),
            lines.flatMap { listOf(it.fromX, it.toX) }.map { it.toInt() }.toSet(),
        )
        assertEquals(
            setOf(0, 16),
            lines.flatMap { listOf(it.fromZ, it.toZ) }.map { it.toInt() }.toSet(),
        )
    }

    @Test
    fun newChunksReportsOnlyOutOfRadiusChunks() {
        val ctx = world()
        assertEquals(0, WorldModules.newChunkMarks(ctx, levelChunk(0, 0)).size)
        assertEquals(1, WorldModules.newChunkMarks(ctx, levelChunk(12, 0)).size)
        ctx.settings["chunk_radius"] = "16"
        assertEquals(0, WorldModules.newChunkMarks(ctx, levelChunk(12, 0)).size)
    }

    @Test
    fun chunkViewsRejectAnythingThatIsNotALevelChunk() {
        assertEquals(0, WorldModules.chunkMarks(world(), updateBlock(0, 0, 0), 1000f).size)
        assertEquals(0, WorldModules.chunkBorderLines(world(), updateBlock(0, 0, 0)).size)
        assertEquals(0, WorldModules.newChunkMarks(world(), ByteArray(0)).size)
    }

    // ------------------------------------------------------- pass-through

    @Test
    fun transformForwardsEveryIdUntouched() {
        val raw = ModuleWire.build(0x13, ModuleWire.writeVarUInt(1), ModuleWire.writeF32LE(1f))
        for (id in allIds + listOf("xykell.world.unknown")) {
            val out = WorldModules.transform(id, RelayDirection.TO_CLIENT, raw)
            assertEquals(id, 1, out.size)
            assertArrayEquals(id, raw, out[0])
        }
    }

    @Test
    fun transformForwardsThePacketsTheViewsActuallyRead() {
        // The implemented ids have no byte-level rewrite of their own; making
        // that explicit stops a "just in case" drop from creeping in.
        val inputs = listOf(updateBlock(1, 2, 3), levelChunk(1, 2), updateBlock(0, 0, 0, 3000))
        for (id in WorldModules.IMPLEMENTED) {
            for (raw in inputs) {
                for (direction in listOf(RelayDirection.TO_CLIENT, RelayDirection.TO_SERVER)) {
                    val out = WorldModules.transform(id, direction, raw, world())
                    assertEquals(id, 1, out.size)
                    assertArrayEquals(id, raw, out[0])
                }
            }
        }
    }

    @Test
    fun transformNeverDropsOrMutatesMalformedInput() {
        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(0x01),
            ByteArray(64) { 0xff.toByte() },
            ModuleWire.build(0x2A, ModuleWire.writeVarInt(-1)),
        )
        for (raw in inputs) {
            for (id in allIds) {
                val out = WorldModules.transform(id, RelayDirection.TO_CLIENT, raw)
                assertEquals(id, 1, out.size)
                assertArrayEquals(id, raw, out[0])
            }
        }
    }
}