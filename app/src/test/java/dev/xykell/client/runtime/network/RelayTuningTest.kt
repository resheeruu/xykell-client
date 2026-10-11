package dev.xykell.client.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Host JVM tests for relay socket/thread/resolution policy.
 *
 * The load-bearing properties here are the ones a reader could otherwise get
 * wrong without noticing: a buffer we asked for must be reported as a *request*
 * rather than as a fact, an unreadable buffer size must be unknown rather than
 * zero, and a failed resolution must not poison the cache.
 */
class RelayTuningTest {

    @Test
    fun `mtu is the unfragmented ipv4 datagram`() {
        // 1500 MTU - 20 IP - 8 UDP.
        assertEquals(1492, RelayTuning.MTU_BYTES)
    }

    @Test
    fun `thread priority is one step up and never above the cap`() {
        val p = RelayTuning.threadPriority()
        assertEquals(Thread.NORM_PRIORITY + 1, p)
        assertTrue("must not starve the UI thread", p <= Thread.NORM_PRIORITY + 1)
    }

    @Test
    fun `buffer request is clamped at both ends`() {
        assertEquals(RelayTuning.MAX_BUFFER_BYTES, RelayTuning.bufferRequest(Int.MAX_VALUE))
        assertEquals(RelayTuning.MTU_BYTES, RelayTuning.bufferRequest(1))
        assertEquals(RelayTuning.MTU_BYTES, RelayTuning.bufferRequest(0))
        assertEquals(RelayTuning.MTU_BYTES, RelayTuning.bufferRequest(-5))
        assertEquals(RelayTuning.REQUESTED_BUFFER_BYTES, RelayTuning.bufferRequest())
    }

    @Test
    fun `an unreadable buffer size is unknown, not zero`() {
        // 0 and negatives mean "we could not read it", which is a different fact
        // from "the buffer is zero bytes".
        assertNull(RelayTuning.bufferGranted(0))
        assertNull(RelayTuning.bufferGranted(-1))
        assertEquals(65536, RelayTuning.bufferGranted(65536))
        assertEquals(RelayTuning.MAX_BUFFER_BYTES, RelayTuning.bufferGranted(Int.MAX_VALUE))
    }

    @Test
    fun `description separates requested from granted`() {
        val d = RelayTuning.describeBuffers(RelayTuning.REQUESTED_BUFFER_BYTES, 131072, 262144)
        assertTrue(d, d.contains("requested 2048KiB"))
        assertTrue(d, d.contains("granted recv 128KiB"))
        assertTrue(d, d.contains("send 256KiB"))
        assertTrue(d, d.contains("mtu 1492B"))
    }

    @Test
    fun `an unreadable grant is described as unknown`() {
        val d = RelayTuning.describeBuffers(RelayTuning.REQUESTED_BUFFER_BYTES, 0, 0)
        assertTrue(d, d.contains("granted unknown"))
    }

    @Test
    fun `resolver caches a successful lookup`() {
        var calls = 0
        val r = RelayTuning.UpstreamResolver(lookup = { host ->
            calls++
            InetAddress.getByName(host)
        })
        val a = r.resolve("localhost")
        assertNotNull(a)
        val b = r.resolve("localhost")
        assertEquals(a, b)
        assertEquals("second resolve must not re-query", 1, calls)
        assertTrue(r.cachedHosts().contains("localhost"))
    }

    @Test
    fun `a failed lookup returns null and is not cached`() {
        val loopback = InetAddress.getLoopbackAddress()
        var calls = 0
        val r = RelayTuning.UpstreamResolver(lookup = { host ->
            calls++
            if (calls == 1) throw java.net.UnknownHostException("transient") else loopback
        })
        assertNull(r.resolve("relay.example"))
        assertTrue("failure must not be sticky", r.cachedHosts().isEmpty())
        assertNotNull("a retry after a transient failure must succeed", r.resolve("relay.example"))
        assertEquals(2, calls)
    }

    @Test
    fun `a throwing resolver does not escape as an exception`() {
        val r = RelayTuning.UpstreamResolver(lookup = { throw RuntimeException("boom") })
        assertNull(r.resolve("relay.invalid"))
    }

    @Test
    fun `a null lookup result is a miss, not a cached null`() {
        val r = RelayTuning.UpstreamResolver(lookup = { null })
        assertNull(r.resolve("relay.invalid"))
        assertTrue(r.cachedHosts().isEmpty())
    }

    @Test
    fun `empty and oversized hosts are refused without a lookup`() {
        var calls = 0
        val r = RelayTuning.UpstreamResolver(lookup = { calls++; InetAddress.getByName(it) })
        assertNull(r.resolve(""))
        assertNull(r.resolve("a".repeat(254)))
        assertEquals(0, calls)
    }

    @Test
    fun `clear drops the cache`() {
        val r = RelayTuning.UpstreamResolver(lookup = { InetAddress.getByName(it) })
        r.resolve("localhost")
        assertTrue(r.cachedHosts().isNotEmpty())
        r.clear()
        assertTrue(r.cachedHosts().isEmpty())
    }
}