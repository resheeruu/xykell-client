package dev.xykell.client.runtime.relay

import java.io.ByteArrayOutputStream

/**
 * Builds a StartGame payload from the vendored schema, so the decoder can be
 * tested against bytes it did not produce itself.
 *
 * This is the check that matters. A decoder and its test that share an
 * implementation prove nothing; here the encoder walks
 * [StartGamePalettePlan.PLANS] independently, and the palette is planted at a
 * known offset. If the decoder and the schema ever disagree about the walk, the
 * round trip fails instead of quietly returning a palette one field out.
 */
internal object StartGameEncoder {

    /** Little-endian NBT compound, as Bedrock writes it. */
    fun nbt(vararg pairs: Pair<String, Any?>): ByteArray {
        val out = ByteArrayOutputStream()
        // unnamed root compound
        out.write(0x0a)
        for ((name, value) in pairs) {
            when (value) {
                is Byte -> {
                    out.write(1); out.write(name.encode()); out.write(value.toInt())
                }
                is Int -> {
                    out.write(3); out.write(name.encode()); out.writeLE32(value)
                }
                is Long -> {
                    out.write(4); out.write(name.encode()); out.writeLE64(value)
                }
                is String -> {
                    out.write(8); out.write(name.encode()); out.write(value.encode())
                }
                is Boolean -> {
                    out.write(1); out.write(name.encode()); out.write(if (value) 1 else 0)
                }
                is ByteArray -> {
                    out.write(7); out.write(name.encode()); out.write(varUInt(value.size)); out.write(value)
                }
                is List<*> -> {
                    out.write(9); out.write(name.encode())
                    // element type 3 = TAG_Int, the common case for block states
                    out.write(3); out.write(varUInt(value.size))
                    for (v in value) {
                        out.writeLE32((v as Int))
                    }
                }
                else -> error("unhandled NBT type ${value?.javaClass}")
            }
        }
        out.write(0) // TAG_End
        return out.toByteArray()
    }

    fun varUInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = v and 0x7f
            v = v ushr 7
            if (v != 0) out.write(b or 0x80) else {
                out.write(b)
                break
            }
        }
        return out.toByteArray()
    }

    private fun String.encode(): ByteArray {
        val body = toByteArray(Charsets.UTF_8)
        return varUInt(body.size) + body
    }

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(v and 0xff); write((v ushr 8) and 0xff)
        write((v ushr 16) and 0xff); write((v ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLE64(v: Long) {
        for (s in 0 until 8) write(((v ushr (8 * s)) and 0xff).toInt())
    }

    /**
     * Writes just the walk for [version] -- every field at a zero value -- and
     * returns the cursor. Used to build malformed payloads where the test wants
     * to control everything after the palette without restating the walk twice.
     */
    fun zeroFill(version: String): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        // Same header as a real packet, so a test that appends its own palette
        // still passes the decoder's id check and exercises what it means to.
        out.write(
            dev.xykell.client.runtime.relay.BedrockPacketIds
                .idForVersion(version, "StartGame") ?: error("no StartGame id for $version"),
        )
        for (op in StartGamePalettePlan.PLANS.getValue(version)) {
            when (op) {
                StartGamePalettePlan.Op.BOOL -> out.write(1)
                StartGamePalettePlan.Op.LI16 -> { out.write(0); out.write(0) }
                StartGamePalettePlan.Op.LI32 -> { repeat(4) { out.write(0) } }
                StartGamePalettePlan.Op.LI64 -> { repeat(8) { out.write(0) } }
                StartGamePalettePlan.Op.LF32 -> { repeat(4) { out.write(0) } }
                StartGamePalettePlan.Op.STRING -> out.write(0)
                StartGamePalettePlan.Op.VARINT,
                StartGamePalettePlan.Op.VARINT64,
                StartGamePalettePlan.Op.ZIGZAG32,
                StartGamePalettePlan.Op.ZIGZAG64 -> out.write(0)
                StartGamePalettePlan.Op.EXPERIMENTS -> { repeat(4) { out.write(0) } }
                StartGamePalettePlan.Op.GAME_RULES -> out.write(0)
            }
        }
        return out
    }

    /**
     * Writes a payload the plan should walk exactly, ending in [palette].
     *
     * Every op is emitted at its real width: the point is that the decoder has
     * to consume the same number of bytes the schema says each field occupies.
     */
    fun startGameWith(
        version: String,
        palette: List<Pair<String, ByteArray>>,
        extraTail: ByteArray = ByteArray(0),
    ): ByteArray {
        val plan = StartGamePalettePlan.PLANS[version]
            ?: error("no plan for $version")
        val out = ByteArrayOutputStream()
        // Real packets lead with their id, and the decoder checks it, so the
        // encoder has to produce one or the test proves nothing.
        val id = dev.xykell.client.runtime.relay.BedrockPacketIds
            .idForVersion(version, "StartGame")
            ?: error("no StartGame id for $version")
        out.write(id)
        for (op in plan) {
            when (op) {
                StartGamePalettePlan.Op.BOOL -> out.write(1)
                StartGamePalettePlan.Op.LI16 -> { out.write(0x01); out.write(0x00) }
                StartGamePalettePlan.Op.LI32 -> out.writeLE32(0x01020304)
                StartGamePalettePlan.Op.LI64 -> out.writeLE64(0x0102030405060708L)
                StartGamePalettePlan.Op.LF32 -> out.writeLE32(0)
                StartGamePalettePlan.Op.STRING -> {
                    val body = "abc".toByteArray()
                    out.write(varUInt(body.size))
                    out.write(body)
                }
                StartGamePalettePlan.Op.VARINT,
                StartGamePalettePlan.Op.VARINT64 -> out.write(varUInt(0x7f))
                StartGamePalettePlan.Op.ZIGZAG32,
                StartGamePalettePlan.Op.ZIGZAG64 -> out.write(varUInt(0xfe))
                StartGamePalettePlan.Op.EXPERIMENTS -> {
                    // (name, enabled) pairs, interleaved. Writing the names
                    // first and the flags after reads as plausible but puts the
                    // decoder one field off, which is exactly the drift these
                    // tests exist to catch.
                    out.writeLE32(2)
                    for ((name, enabled) in listOf("e1" to true, "e2" to false)) {
                        val body = name.toByteArray()
                        out.write(varUInt(body.size))
                        out.write(body)
                        out.write(if (enabled) 1 else 0)
                    }
                }
                StartGamePalettePlan.Op.GAME_RULES -> {
                    out.write(varUInt(1)) // one rule
                    val name = "doDaylightCycle".toByteArray()
                    out.write(varUInt(name.size))
                    out.write(name)
                    out.write(varUInt(2)) // kind: int
                    out.write(varUInt(0)) // value 0
                }
            }
        }
        out.write(varUInt(palette.size))
        for ((name, state) in palette) {
            out.write(varUInt(name.toByteArray(Charsets.UTF_8).size))
            out.write(name.toByteArray(Charsets.UTF_8))
            out.write(state)
        }
        out.write(extraTail)
        return out.toByteArray()
    }
}
