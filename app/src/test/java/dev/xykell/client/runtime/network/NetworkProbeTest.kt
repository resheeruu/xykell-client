package dev.xykell.client.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Latency history and prober tests. The prober is pointed at a loopback
 * ServerSocket opened by the test, so no external host, no DNS lookup and no
 * game endpoint is involved. Failure cases assert that a null latency is
 * reported rather than a fabricated number.
 */
class NetworkProbeTest {

    // --- LatencyHistory

    @Test
    fun historyIsBoundedAndEvictsOldest() {
        val h = NetworkProbe.LatencyHistory(capacity = 3)
        listOf(10L, 20L, 30L, 40L).forEach(h::add)
        assertEquals(listOf(20L, 30L, 40L), h.samples())
    }

    @Test
    fun statisticsAreHonestWhenEmpty() {
        val h = NetworkProbe.LatencyHistory()
        assertNull(h.latest())
        assertNull(h.average())
        assertNull(h.min())
        assertNull(h.max())
        assertTrue(h.samples().isEmpty())
    }

    @Test
    fun statisticsWithSamples() {
        val h = NetworkProbe.LatencyHistory()
        listOf(10L, 20L, 60L).forEach(h::add)
        assertEquals(60L, h.latest())
        assertEquals(30L, h.average())
        assertEquals(10L, h.min())
        assertEquals(60L, h.max())
    }

    @Test
    fun negativeSampleIsNeverRecorded() {
        val h = NetworkProbe.LatencyHistory()
        h.add(-5)
        assertTrue(h.samples().isEmpty())
    }

    @Test
    fun clearResetsHistory() {
        val h = NetworkProbe.LatencyHistory()
        h.add(5)
        h.clear()
        assertNull(h.latest())
    }

    @Test
    fun capacityIsValidated() {
        var threw = false
        try {
            NetworkProbe.LatencyHistory(capacity = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- Prober

    private fun withServer(block: (Int, AutoCloseable) -> Unit) {
        val server = ServerSocket(0)
        val t = Thread {
            try {
                while (!server.isClosed) {
                    val s = server.accept()
                    s.close() // connect only; no payload either way
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
        try {
            block(server.localPort, AutoCloseable { server.close() })
        } finally {
            server.close()
        }
    }

    @Test
    fun reachableHostReportsRealLatency() {
        withServer { port, _ ->
            var got: NetworkProbe.Probe? = null
            val latch = CountDownLatch(1)
            val p = NetworkProbe.Prober(clock = { 1_000L })
            try {
                p.probe("127.0.0.1", port, 2000) {
                    got = it
                    latch.countDown()
                }
                assertTrue(latch.await(10, TimeUnit.SECONDS))
                val r = got!!
                assertTrue("expected reachable", r.reachable)
                assertTrue("expected a real latency", r.latencyMs != null)
                assertTrue(r.ok)
                assertEquals("127.0.0.1", r.host)
                assertEquals(port, r.port)
                p.history().add(r.latencyMs!!)
                assertNotNull(p.history().latest())
            } finally {
                p.close()
            }
        }
    }

    @Test
    fun unreachablePortReportsNoLatency() {
        // Port 1 on loopback: nothing listens, so the connect fails fast.
        val got = arrayOfNulls<NetworkProbe.Probe>(1)
        val latch = CountDownLatch(1)
        val p = NetworkProbe.Prober(clock = { 0L })
        try {
            p.probe("127.0.0.1", 1, 500) {
                got[0] = it
                latch.countDown()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            val r = got[0]!!
            assertFalse(r.reachable)
            assertNull("no round trip means no latency number", r.latencyMs)
            assertFalse(r.ok)
            assertNotNull(r.error)
        } finally {
            p.close()
        }
    }

    @Test
    fun invalidHostAndPortAreRejectedWithoutProbing() {
        val p = NetworkProbe.Prober(clock = { 0L })
        try {
            val bad = arrayOfNulls<NetworkProbe.Probe>(3)
            var n = 0
            val latch = CountDownLatch(3)
            p.probe("", 80) { bad[n++] = it; latch.countDown() }
            p.probe("x".repeat(300), 80) { bad[n++] = it; latch.countDown() }
            p.probe("127.0.0.1", 0) { bad[n++] = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(bad.all { it != null && it.error == "invalid host" || it?.error == "invalid port" })
            bad.filterNotNull().forEach {
                assertNull(it.latencyMs)
                assertFalse(it.reachable)
            }
        } finally {
            p.close()
        }
    }

    @Test
    fun concurrentProbeIsCoalescedNotQueued() {
        val p = NetworkProbe.Prober(clock = { 0L })
        try {
            // First call occupies the prober; the second must be refused
            // rather than opening a second socket.
            val results = java.util.Collections.synchronizedList(
                mutableListOf<NetworkProbe.Probe>(),
            )
            val latch = CountDownLatch(2)
            p.probe("127.0.0.1", 1, 4000) { results.add(it); latch.countDown() }
            p.probe("127.0.0.1", 1, 4000) { results.add(it); latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            assertTrue(results.any { it.error == "probe already running" })
        } finally {
            p.close()
        }
    }

    @Test
    fun failureErrorDoesNotLeakInternalDetail() {
        val p = NetworkProbe.Prober(clock = { 0L })
        try {
            val got = arrayOfNulls<NetworkProbe.Probe>(1)
            val latch = CountDownLatch(1)
            p.probe("this-host-does-not-exist.invalid", 80, 800) { got[0] = it; latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            val err = got[0]!!.error!!
            // A class name, not a resolver message that could echo a path.
            assertTrue(err.isNotEmpty())
            assertFalse(err.contains("/"))
            assertFalse(err.contains("at "))
        } finally {
            p.close()
        }
    }

    @Test
    fun periodIsClampedToASensibleFloor() {
        // scheduleProbes must not be able to create a 1 ms polling loop.
        assertTrue(NetworkProbe.MIN_PERIOD_MS >= 1000L)
    }
}
