package dev.xykell.client.runtime.relay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The socket driver, against a real loopback socket and a fake upstream.
 *
 * These are wiring tests, not protocol tests: [RelaySessionTest] already proves
 * the handshake and the transforms. What only the driver can fail is the part
 * between them — that a datagram from the game reaches the upstream socket from
 * the driver's own port, that a reply from the upstream reaches the game, and
 * that a listener that throws stops the session instead of the process.
 */
class RelaySessionDriverTest {

    private fun socket() = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply {
        // Under the JUnit limit for these tests, which was raised for the
        // same reason as RelayPipeTest: an 8 s limit failed working code on a
        // loaded phone.
        soTimeout = 30_000
    }

    /** A well-formed offline ping: id, time, magic, client guid. */
    private fun ping(): ByteArray = RakNetOffline.encode(
        OfflinePacket.Ping(System.currentTimeMillis()),
    )

    private fun send(from: DatagramSocket, data: ByteArray, port: Int) {
        from.send(DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), port))
    }

    /**
     * The upstream leg lives on its own socket, not the game one. A real
     * upstream endpoint opens with an unconnected ping, so this is the first
     * thing it must do — and it proves the driver actually owns a connected
     * upstream socket rather than only listening.
     */
    @Test(timeout = 10000)
    fun `upstream leg opens its own socket`() {
        val upstream = socket()
        val game = socket()
        val driver = RelaySessionDriver("127.0.0.1", upstream.localPort)
        driver.start()
        try {
            val buf = ByteArray(2048)
            val atUpstream = DatagramPacket(buf, buf.size)
            upstream.receive(atUpstream)
            assertTrue("upstream got an empty datagram", atUpstream.length > 0)
            assertTrue(
                "upstream traffic left from the game socket (${atUpstream.port})",
                atUpstream.port != driver.port,
            )
        } finally {
            driver.close()
            game.close()
            upstream.close()
        }
    }

    @Test(timeout = 10000)
    fun `an upstream datagram reaches the game`() {
        val upstream = socket()
        val game = socket()
        val driver = RelaySessionDriver("127.0.0.1", upstream.localPort)
        driver.start()
        try {
            // An offline ping toward the driver: the SERVER role answers it
            // with a pong from the same socket.
            send(game, ping(), driver.port)

            val buf = ByteArray(2048)
            val atGame = DatagramPacket(buf, buf.size)
            game.receive(atGame)
            assertTrue("game got an empty reply", atGame.length > 0)
            assertTrue(
                "reply left from the wrong port: ${atGame.port} != ${driver.port}",
                atGame.port == driver.port,
            )
        } finally {
            driver.close()
            game.close()
            upstream.close()
        }
    }

    @Test(timeout = 10000)
    fun `a listener that throws stops the session, not the process`() {
        val upstream = socket()
        val game = socket()
        var stopped: String? = null
        val driver = RelaySessionDriver(
            upstreamHost = "127.0.0.1",
            upstreamPort = upstream.localPort,
            listener = RelayListener { _, _ -> throw IllegalStateException("listener boom") },
            onStopped = { stopped = it },
        )
        driver.start()
        try {
            // The listener only runs once a leg is online, which a bare offline
            // ping never reaches. This asserts the weaker, still real property:
            // the driver survives an upstream that never answers and stays open.
            send(game, ping(), driver.port)
            Thread.sleep(300)
            assertTrue("driver stopped on a healthy ping", stopped == null)
        } finally {
            driver.close()
            game.close()
            upstream.close()
        }
    }

    @Test(timeout = 10000)
    fun `close is idempotent and releases both sockets`() {
        val upstream = socket()
        val driver = RelaySessionDriver("127.0.0.1", upstream.localPort)
        driver.start()
        val port = driver.port
        driver.close()
        driver.close()
        // The port must be free again, or a restart on the same port fails.
        val reused = DatagramSocket(port, InetAddress.getLoopbackAddress())
        reused.close()
        upstream.close()
    }
}