package dev.xykell.client.runtime.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class RelayPipeTest {

    private lateinit var fakeServer: DatagramSocket
    private lateinit var client: DatagramSocket
    private lateinit var pipe: RelayPipe

    @Before
    fun setUp() {
        fakeServer = DatagramSocket(0, InetAddress.getLoopbackAddress())
        fakeServer.soTimeout = 3000
        client = DatagramSocket(0, InetAddress.getLoopbackAddress())
        client.soTimeout = 3000
        pipe = RelayPipe("127.0.0.1", fakeServer.localPort)
        pipe.start()
    }

    @After
    fun tearDown() {
        pipe.close()
        client.close()
        fakeServer.close()
    }

    private fun send(sock: DatagramSocket, data: ByteArray, destPort: Int) {
        sock.send(DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), destPort))
    }

    private fun receive(sock: DatagramSocket): DatagramPacket {
        val buf = ByteArray(65536)
        val pkt = DatagramPacket(buf, buf.size)
        sock.receive(pkt)
        return pkt
    }

    @Test(timeout = 8000)
    fun `forwards client datagram to upstream from pipe port and response back`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        send(client, payload, pipe.port)

        val atServer = receive(fakeServer)
        assertArrayEquals(payload, atServer.data.copyOf(atServer.length))
        assertEquals(pipe.port, atServer.port)

        val reply = byteArrayOf(0x1c, 0x7f)
        fakeServer.send(DatagramPacket(reply, reply.size, atServer.socketAddress))

        val atClient = receive(client)
        assertArrayEquals(reply, atClient.data.copyOf(atClient.length))
        assertEquals(pipe.port, atClient.port)
    }

    @Test(timeout = 8000)
    fun `preserves sequential stream contents and order`() {
        val n = 20
        for (i in 0 until n) {
            send(client, byteArrayOf(i.toByte()), pipe.port)
        }
        val got = ArrayList<Byte>(n)
        repeat(n) { got.add(receive(fakeServer).let { p -> p.data[p.offset] }) }
        assertEquals((0 until n).map { it.toByte() }, got)
    }

    @Test(timeout = 8000)
    fun `close stops relay thread without error`() {
        send(client, byteArrayOf(0x09), pipe.port)
        receive(fakeServer)
        pipe.close()
        pipe.close()
        val thread = Thread.getAllStackTraces().keys.firstOrNull { it.name == "relay-pipe" }
        assertTrue(thread == null || !thread.isAlive)
    }

    @Test(timeout = 8000)
    fun `observer sees datagrams in both directions`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<ByteArray, Boolean>>()
        val observed = RelayPipe(
            "127.0.0.1", fakeServer.localPort,
            observer = { data, fromClient -> seen.add(data to fromClient) }
        )
        observed.start()
        try {
            val payload = byteArrayOf(0x0a, 0x0b)
            send(client, payload, observed.port)
            val atServer = receive(fakeServer)
            assertArrayEquals(payload, atServer.data.copyOf(atServer.length))

            val reply = byteArrayOf(0x1d, 0x1e)
            fakeServer.send(DatagramPacket(reply, reply.size, atServer.socketAddress))
            val atClient = receive(client)
            assertArrayEquals(reply, atClient.data.copyOf(atClient.length))

            assertEquals(2, seen.size)
            assertTrue(seen[0].second)
            assertArrayEquals(payload, seen[0].first)
            assertTrue(!seen[1].second)
            assertArrayEquals(reply, seen[1].first)
        } finally {
            observed.close()
        }
    }

    @Test(timeout = 8000)
    fun `observer throw does not stop forwarding`() {
        val throwing = RelayPipe(
            "127.0.0.1", fakeServer.localPort,
            observer = { _, _ -> throw IllegalStateException("observer bug") },
        )
        throwing.start()
        try {
            val first = byteArrayOf(0x01, 0x02)
            send(client, first, throwing.port)
            val atServer = receive(fakeServer)
            assertArrayEquals(first, atServer.data.copyOf(atServer.length))

            // Second datagram still forwarded after observer exception.
            val second = byteArrayOf(0x03, 0x04)
            send(client, second, throwing.port)
            val atServer2 = receive(fakeServer)
            assertArrayEquals(second, atServer2.data.copyOf(atServer2.length))
        } finally {
            throwing.close()
        }
    }

    @Test(timeout = 8000)
    fun `upstream unsolicited packet before client known is dropped`() {
        val orphan = byteArrayOf(0x55)
        fakeServer.send(
            DatagramPacket(
                orphan, orphan.size, InetAddress.getLoopbackAddress(), pipe.port
            )
        )
        try {
            val pkt = receive(client)
            throw AssertionError("expected drop, got ${pkt.length} bytes")
        } catch (_: SocketTimeoutException) {
            // dropped as designed
        }
    }
}
