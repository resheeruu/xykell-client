package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for relay decode limits.
 *
 * These bounds are the whole defence against a peer that claims a huge length,
 * so the tests care about two things specifically: an over-limit value is
 * *refused* rather than truncated (a truncated list silently dropping entries is
 * worse than a refusal), and the per-packet budget cannot be resurrected by a
 * later field after it has been spent.
 */
class DecodeLimitsTest {

    @Test
    fun `a list at the limit passes and one past it does not`() {
        assertTrue(DecodeLimits.checkListSize(DecodeLimits.MAX_LIST_SIZE).ok)
        val over = DecodeLimits.checkListSize(DecodeLimits.MAX_LIST_SIZE + 1)
        assertFalse(over.ok)
        assertEquals(DecodeLimits.Refusal.LIST_TOO_LARGE, over.refusalOrNull)
    }

    @Test
    fun `every per-field limit refuses above its own bound`() {
        val over = DecodeLimits.MAX_BYTE_ARRAY_BYTES + 1
        val byteArray = DecodeLimits.checkByteArray(over)
        assertEquals(DecodeLimits.Refusal.BYTE_ARRAY_TOO_LARGE, byteArray.refusalOrNull)

        val nbt = DecodeLimits.checkNetworkNbt(over)
        assertEquals(DecodeLimits.Refusal.NBT_TOO_LARGE, nbt.refusalOrNull)

        val itemNbt = DecodeLimits.checkItemNbt(over)
        assertEquals(DecodeLimits.Refusal.ITEM_NBT_TOO_LARGE, itemNbt.refusalOrNull)

        val str = DecodeLimits.checkStringLength(over)
        assertEquals(DecodeLimits.Refusal.STRING_TOO_LARGE, str.refusalOrNull)

        val depth = DecodeLimits.checkDepth(over)
        assertEquals(DecodeLimits.Refusal.DEPTH_EXCEEDED, depth.refusalOrNull)
    }

    @Test
    fun `a refusal reports the claimed size, not the limit`() {
        val claimed = 99_999_999
        val r = DecodeLimits.checkListSize(claimed)
        assertTrue(r is DecodeLimits.Checked.Refused)
        assertEquals(claimed.toLong(), (r as DecodeLimits.Checked.Refused).claimed)
    }

    @Test
    fun `an accepted value carries the value through`() {
        val r = DecodeLimits.checkListSize(12)
        assertEquals(12, r.valueOrNull)
    }

    @Test
    fun `the per-packet budget accumulates across fields`() {
        val b = DecodeLimits.Budget()
        assertTrue(b.charge(1000))
        assertTrue(b.charge(1000))
        assertEquals(2000L, b.consumed)
        assertFalse(b.exhausted)
    }

    @Test
    fun `the budget is spent once the ceiling is crossed, and stays spent`() {
        val b = DecodeLimits.Budget()
        val half = DecodeLimits.DEFAULT.maxPacketBytes / 2
        assertTrue(b.charge(half.toLong()))
        // Crossing the ceiling is the refusal point.
        val crossed = b.chargeChecked(half.toLong() + 1)
        assertEquals(DecodeLimits.Refusal.PACKET_TOO_LARGE, crossed.refusalOrNull)
        assertTrue(b.exhausted)
        // A later field must not resurrect the packet.
        assertFalse(b.charge(1))
        assertFalse(b.chargeChecked(1).ok)
    }

    @Test
    fun `a negative charge is refused rather than crediting the budget`() {
        val b = DecodeLimits.Budget()
        assertFalse(b.charge(-500))
        assertEquals(0L, b.consumed)
    }

    @Test
    fun `reset reopens a spent budget for the next packet`() {
        val b = DecodeLimits.Budget()
        b.charge(DecodeLimits.DEFAULT.maxPacketBytes.toLong() + 1)
        assertTrue(b.exhausted)
        b.reset()
        assertFalse(b.exhausted)
        assertEquals(0L, b.consumed)
    }

    @Test
    fun `many legal per-field values cannot exceed the per-packet ceiling`() {
        // Each charge is individually legal; only the sum must be refused. This
        // is the property that a per-field-only defence would fail.
        val b = DecodeLimits.Budget()
        val legal = DecodeLimits.MAX_BYTE_ARRAY_BYTES.toLong()
        var charged = 0
        while (b.charge(legal)) {
            charged++
            if (charged > 100) break // ceiling must stop us well before here
        }
        assertTrue("ceiling should hit within a handful of fields", charged <= 8)
        assertTrue(b.exhausted)
    }

    @Test
    fun `incoherent limit sets are refused at construction`() {
        // A per-packet ceiling below a per-field ceiling is policy that can
        // never bind, so it is not allowed to exist.
        var threw = false
        try {
            DecodeLimits.of(maxByteArrayBytes = 1024, maxPacketBytes = 512)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `non-positive limits are refused at construction`() {
        var threw = false
        try {
            DecodeLimits.of(maxListSize = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a valid override is honoured`() {
        val tight = DecodeLimits.of(maxListSize = 10)
        assertTrue(DecodeLimits.checkListSize(10, tight).ok)
        assertFalse(DecodeLimits.checkListSize(11, tight).ok)
    }

    @Test
    fun `describe names every bound it applies`() {
        val d = DecodeLimits.DEFAULT.describe()
        for (k in listOf("list<=", "bytes<=", "nbt<=", "itemNbt<=", "str<=", "packet<=", "depth<=", "batch<=")) {
            assertTrue("missing $k in $d", d.contains(k))
        }
    }

    @Test
    fun `the displayed batch cap is the one the wire path enforces`() {
        // If these ever diverge again the app is showing a policy it does not
        // apply, which is the defect this constant was moved to fix.
        assertEquals(DecodeLimits.MAX_INFLATED_BATCH_BYTES, BedrockBatch.MAX_INFLATED)
        assertTrue(
            "describe() must carry the enforced number",
            DecodeLimits.DEFAULT.describe()
                .contains("batch<=${DecodeLimits.MAX_INFLATED_BATCH_BYTES}"),
        )
    }

    @Test
    fun `the batch cap is its own bound, not the packet bound`() {
        // A batch is a container of packets, so it legitimately decompresses to
        // more than one packet's worth; collapsing the two would either weaken
        // the wire cap or make the packet cap unreachable.
        assertTrue(
            "batch cap should stand on its own",
            DecodeLimits.MAX_INFLATED_BATCH_BYTES != DecodeLimits.MAX_PACKET_BYTES,
        )
        assertTrue(DecodeLimits.MAX_INFLATED_BATCH_BYTES > 0)
    }

    @Test
    fun `a non-positive batch cap is refused`() {
        var threw = false
        try {
            DecodeLimits.of(maxInflatedBatchBytes = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}