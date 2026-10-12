package dev.xykell.client.runtime.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [StartGamePaletteDecoder].
 *
 * The payload is built by [StartGameEncoder] from the same generated plan the
 * decoder follows, so a disagreement between decoder and schema surfaces here
 * rather than as a palette that names the wrong blocks at runtime.
 */
class StartGamePaletteDecoderTest {

    private val decodable = StartGamePalettePlan.PLANS.keys.sorted()

    @Test
    fun `plan covers the versions the audit found decodable`() {
        // Guard against the generator silently losing every version: the audit
        // says exactly two, and they must both be present with plans.
        assertEquals(listOf("1.16.201", "1.16.210"), decodable)
    }

    @Test
    fun `every audited version is either planned or refused`() {
        // No version may fall through both maps; an unmentioned version would
        // look like an unknown protocol rather than a checked one.
        val known = decodable + StartGamePalettePlan.REFUSED.keys
        assertEquals(known.size, known.toSet().size)
        assertTrue("audit should be substantial", StartGamePalettePlan.REFUSED.size >= 48)
    }

    @Test
    fun `round-trips a palette of real block names and states`() {
        val states = listOf(
            StartGameEncoder.nbt("version" to 1945679631, "name" to "minecraft:stone"),
            StartGameEncoder.nbt("version" to 1905797891, "name" to "minecraft:oak_log"),
            StartGameEncoder.nbt(
                "version" to 17959425,
                "name" to "minecraft:water",
                "liquid_depth" to 8,
            ),
        )
        val names = listOf("minecraft:stone", "minecraft:oak_log", "minecraft:water")

        for (version in decodable) {
            val payload = StartGameEncoder.startGameWith(version, names.zip(states))
            val result = StartGamePaletteDecoder.decode(version, payload)
            assertTrue("$version refused: $result", result is StartGamePaletteDecoder.Result.Ok)
            val ok = result as StartGamePaletteDecoder.Result.Ok
            assertEquals(version, ok.version)
            assertEquals(names.size, ok.entries.size)
            ok.entries.forEachIndexed { i, e ->
                assertEquals("$version entry $i name", names[i], e.name)
                assertArrayEquals("$version entry $i state", states[i], e.state)
            }
        }
    }

    @Test
    fun `walks past fields the palette sits behind`() {
        // The two versions insert different fields before the palette, so a
        // decoder that ignored the plan would land at different offsets and one
        // of these would decode garbage. Asserting the names pins the offset.
        val payload = StartGameEncoder.startGameWith(
            "1.16.210",
            listOf("minecraft:stone" to StartGameEncoder.nbt("version" to 1)),
        )
        val ok = StartGamePaletteDecoder.decode("1.16.210", payload)
                as StartGamePaletteDecoder.Result.Ok
        assertEquals(listOf("minecraft:stone"), ok.entries.map { it.name })
    }

    @Test
    fun `stops at the palette and leaves trailing fields alone`() {
        // Itemstates and later fields follow the palette; decoding must not
        // require them, so an exact-length payload suffices.
        val payload = StartGameEncoder.startGameWith(
            "1.16.201",
            listOf("minecraft:stone" to StartGameEncoder.nbt("version" to 1)),
        )
        val ok = StartGamePaletteDecoder.decode("1.16.201", payload)
                as StartGamePaletteDecoder.Result.Ok
        assertEquals(1, ok.entries.size)
    }

    @Test
    fun `refuses a version with no palette in the handshake`() {
        val payload = StartGameEncoder.startGameWith(
            "1.16.201",
            listOf("minecraft:stone" to StartGameEncoder.nbt("version" to 1)),
        )
        val result = StartGamePaletteDecoder.decode("1.26.45", payload)
        assertTrue(result is StartGamePaletteDecoder.Result.Refused)
        val refused = result as StartGamePaletteDecoder.Result.Refused
        assertEquals(
            StartGamePaletteDecoder.Refusal.NO_PALETTE_IN_HANDSHAKE,
            refused.reason,
        )
        // The reason must say *why*, not just that it failed.
        assertTrue("detail should explain: ${refused.detail}", refused.detail.isNotBlank())
    }

    @Test
    fun `refuses an unknown version as unknown, not as no-palette`() {
        val result = StartGamePaletteDecoder.decode("9.9.9", byteArrayOf(1, 2, 3))
        assertTrue(result is StartGamePaletteDecoder.Result.Refused)
        assertEquals(
            StartGamePaletteDecoder.Refusal.UNKNOWN_VERSION,
            (result as StartGamePaletteDecoder.Result.Refused).reason,
        )
    }

    @Test
    fun `refuses an empty payload`() {
        val result = StartGamePaletteDecoder.decode("1.16.201", ByteArray(0))
        assertEquals(
            StartGamePaletteDecoder.Refusal.EMPTY_PAYLOAD,
            (result as StartGamePaletteDecoder.Result.Refused).reason,
        )
    }

    @Test
    fun `refuses a truncated payload`() {
        val full = StartGameEncoder.startGameWith(
            "1.16.201",
            listOf("minecraft:stone" to StartGameEncoder.nbt("version" to 1)),
        )
        // Cut inside the walk; every truncation point must refuse, never decode.
        for (cut in 1 until full.size) {
            val result = StartGamePaletteDecoder.decode("1.16.201", full.copyOf(cut))
            if (result is StartGamePaletteDecoder.Result.Ok) {
                throw AssertionError("truncation at $cut of ${full.size} decoded a palette")
            }
        }
    }

    @Test
    fun `refuses a zero-entry palette`() {
        val plan = StartGamePalettePlan.PLANS.getValue("1.16.201")
        val payload = StartGameEncoder.startGameWith("1.16.201", emptyList())
        // The encoder wrote count 0, which the decoder must refuse rather than
        // hand back an empty palette that later resolves every id to nothing.
        val result = StartGamePaletteDecoder.decode("1.16.201", payload)
        assertEquals(
            StartGamePaletteDecoder.Refusal.COUNT_OUT_OF_RANGE,
            (result as StartGamePaletteDecoder.Result.Refused).reason,
        )
        assertTrue(plan.isNotEmpty())
    }

    @Test
    fun `refuses an implausible palette count`() {
        val out = StartGameEncoder.zeroFill("1.16.201")
        // varint for a huge count: 0xFF... -> beyond MAX_DEFINITIONS
        out.write(0xff); out.write(0xff); out.write(0xff); out.write(0x7f)
        val result = StartGamePaletteDecoder.decode("1.16.201", out.toByteArray())
        assertEquals(
            StartGamePaletteDecoder.Refusal.COUNT_OUT_OF_RANGE,
            (result as StartGamePaletteDecoder.Result.Refused).reason,
        )
    }

    @Test
    fun `an unknown NBT tag refuses instead of skipping blindly`() {
        // Tag 99 is not a type. Stepping over it would land mid-field and the
        // next entry's name would be garbage that still parses as a string.
        val out = StartGameEncoder.zeroFill("1.16.201")
        out.write(StartGameEncoder.varUInt(1))  // one entry
        val name = "x".toByteArray()
        out.write(StartGameEncoder.varUInt(name.size))
        out.write(name)
        out.write(99) // bogus NBT tag; the decoder must refuse, not step over it
        val result = StartGamePaletteDecoder.decode("1.16.201", out.toByteArray())
        assertEquals(
            StartGamePaletteDecoder.Refusal.MALFORMED,
            (result as StartGamePaletteDecoder.Result.Refused).reason,
        )
    }
}
