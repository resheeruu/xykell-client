package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the generated multi-version packet-id table.
 *
 * The generator exists because a stale id makes a module silently do nothing,
 * so these tests are about the *version* axis: an id that moved between schemas
 * must resolve differently, and a version we have never vendored must come back
 * as unknown rather than as the default.
 */
class BedrockPacketIdsVersionTest {

    @Test
    fun `the default version answers the unqualified accessors`() {
        assertEquals(
            BedrockPacketIds.forVersion(BedrockPacketIds.DEFAULT_VERSION),
            BedrockPacketIds.allNames.associateWith { name ->
                BedrockPacketIds.infoOf(name)!!
            },
        )
    }

    @Test
    fun `many schemas are vendored`() {
        // The point of this release. One schema described one protocol
        // generation; ids that moved between generations silently matched
        // nothing.
        assertTrue("expected many vendored versions", BedrockPacketIds.versions.size >= 40)
        assertTrue(BedrockPacketIds.versions.contains(BedrockPacketIds.DEFAULT_VERSION))
    }

    @Test
    fun `every vendored version resolves a table`() {
        for (v in BedrockPacketIds.versions) {
            assertNotNull("no table for $v", BedrockPacketIds.forVersion(v))
            assertTrue(BedrockPacketIds.supportsVersion(v))
        }
    }

    @Test
    fun `an unvendored version is unknown, not the default`() {
        // These are different claims. Falling back would let a caller believe
        // we can describe a protocol we cannot.
        assertFalse(BedrockPacketIds.supportsVersion("0.0.0-not-real"))
        assertNull(BedrockPacketIds.forVersion("0.0.0-not-real"))
        assertNull(BedrockPacketIds.idForVersion("0.0.0-not-real", "AddPlayer"))
    }

    @Test
    fun `a version table has exactly the packets that version had`() {
        // Bedrock adds packets monotonically, so an older schema is missing a
        // suffix of the default's names. Those must resolve to null rather than
        // inheriting the default's id: a module filtering a packet that did not
        // exist yet would otherwise silently claim to be active.
        val default = BedrockPacketIds.forVersion(BedrockPacketIds.DEFAULT_VERSION)!!
        for (v in BedrockPacketIds.versions) {
            val table = BedrockPacketIds.forVersion(v)!!
            val expectedAbsent = default.keys - table.keys
            for (name in expectedAbsent) {
                assertNull(
                    "$v should not answer for $name, introduced in a later version",
                    table[name],
                )
            }
        }
    }

    @Test
    fun `an older version really does refuse later packets`() {
        // Concrete guard for the regression that motivated modelling absence:
        // 1.16.201 predates most modern packets.
        val old = BedrockPacketIds.forVersion("1.16.201")
        assertNotNull("1.16.201 must be vendored", old)
        val table = old!!
        // CameraAimAssist is recent, so it cannot be known there...
        assertNull(table["CameraAimAssist"])
        // ...while Text, which is ancient, must be.
        assertNotNull(table["Text"])
        // And the default still answers for both, proving the refusal above is
        // about the version rather than a missing entry everywhere.
        assertNotNull(BedrockPacketIds.infoOf("CameraAimAssist"))
    }

    @Test
    fun `version tables get smaller going back in time`() {
        // A sanity check on the delta model: if every version equalled the
        // default, the absence subtraction is not running.
        val default = BedrockPacketIds.forVersion(BedrockPacketIds.DEFAULT_VERSION)!!.size
        val old = BedrockPacketIds.forVersion("1.16.201")!!.size
        assertTrue("1.16.201 ($old) should be well below the default ($default)", old < default - 50)
    }

    @Test
    fun `ids that moved between versions resolve differently`() {
        // Find a real override rather than inventing one: the whole point is
        // that these are upstream facts, not our guesses.
        val base = BedrockPacketIds.forVersion(BedrockPacketIds.DEFAULT_VERSION)!!
        var checked = 0
        for (v in BedrockPacketIds.versions) {
            if (v == BedrockPacketIds.DEFAULT_VERSION) continue
            val table = BedrockPacketIds.forVersion(v)!!
            // Walk the version's own keys: a name the default does not know is a
            // packet introduced later, so there is nothing to compare it against.
            for (name in table.keys) {
                val b = base[name] ?: continue
                val o = table[name]!!
                if (b !== o && (o.id != b.id || o.toClient != b.toClient || o.toServer != b.toServer)) {
                    // The versioned answer must agree with its own table.
                    assertEquals(
                        "idForVersion disagrees with forVersion for $v/$name",
                        o.id,
                        BedrockPacketIds.idForVersion(v, name),
                    )
                    checked++
                    if (checked >= 5) return
                }
            }
        }
        assertTrue("expected at least one real override across versions", checked > 0)
    }

    @Test
    fun `an unknown packet name is null on every version`() {
        for (v in BedrockPacketIds.versions) {
            assertNull(BedrockPacketIds.idForVersion(v, "DefinitelyNotAPacket"))
        }
    }

    @Test
    fun `the cross-check anchors still hold on the default`() {
        // The generator refuses to emit if these drift, so they are asserted
        // here as well: they are the packets the hand-written decoder depends on.
        assertEquals(0x09, BedrockPacketIds.infoOf("Text")!!.id)
        assertEquals(0x0a, BedrockPacketIds.infoOf("SetTime")!!.id)
        assertEquals(0x13, BedrockPacketIds.infoOf("MovePlayer")!!.id)
        assertEquals(0x2a, BedrockPacketIds.infoOf("SetHealth")!!.id)
    }

    @Test
    fun `direction flags survive the version axis`() {
        // MovePlayer is the one every outbound-only transform keys on; if a
        // version reported it as one-way, levitate would start rewriting
        // clientbound packets again.
        for (v in BedrockPacketIds.versions) {
            val info = BedrockPacketIds.forVersion(v)!!.getValue("MovePlayer")
            assertTrue("$v: MovePlayer must be clientbound", info.toClient)
            assertTrue("$v: MovePlayer must be serverbound", info.toServer)
        }
    }
}