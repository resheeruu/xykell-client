package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpoint-level interop: real client-role and server-role endpoints talking
 * to each other over a scripted wire (no sockets), mirroring the Cloudburst
 * handshake order — Req1/Reply1/Req2/Reply2, CR/CRA/NIC, ping/pong.
 */
class RakNetEndpointTest {

    private val clientAddr = RakNetAddress("127.0.0.1", 11111)
    private val serverAddr = RakNetAddress("127.0.0.1", 19132)

    private fun client(
        t: () -> Long = { 1_000_000L },
        mtuCap: Int = 1400,
        retryIntervalMs: Long = 1000,
        maxAttempts: Int = 10,
    ) = RakNetEndpoint(
        mode = RakNetEndpoint.Mode.CLIENT,
        guid = 7L,
        serverAddress = serverAddr,
        mtuCap = mtuCap,
        retryIntervalMs = retryIntervalMs,
        maxAttempts = maxAttempts,
        nowMs = t,
    )

    private fun server(t: () -> Long = { 1_000_000L }, mtuCap: Int = 1400) = RakNetEndpoint(
        mode = RakNetEndpoint.Mode.SERVER,
        guid = 9L,
        advertisement = "MCPE;UnitTest",
        mtuCap = mtuCap,
        nowMs = t,
    )

    /** Deliver queued datagrams both ways until neither endpoint produces more. */
    private fun settle(c: RakNetEndpoint, s: RakNetEndpoint, maxIter: Int = 500) {
        val queue = ArrayDeque<Pair<RakNetEndpoint, ByteArray>>()
        fun enqueue(recv: RakNetEndpoint, d: ByteArray) {
            queue.add(recv to d)
        }
        fun deliver(recv: RakNetEndpoint, d: ByteArray) {
            val from = if (recv === c) serverAddr else clientAddr
            val other = if (recv === c) s else c
            val r = recv.onDatagram(d, from)
            for (x in r.sends) enqueue(other, x)
        }
        for (d in c.onTick().sends) enqueue(s, d)
        var i = 0
        while (i++ < maxIter) {
            if (queue.isNotEmpty()) {
                val (recv, d) = queue.removeFirst()
                deliver(recv, d)
                continue
            }
            val tc = c.onTick()
            val ts = s.onTick()
            for (d in tc.sends) enqueue(s, d)
            for (d in ts.sends) enqueue(c, d)
            if (tc.sends.isEmpty() && ts.sends.isEmpty()) break
        }
    }

    private fun connectedPair(mtuCap: Int = 1400): Pair<RakNetEndpoint, RakNetEndpoint> {
        val c = client(mtuCap = mtuCap)
        val s = server(mtuCap = mtuCap)
        settle(c, s)
        assertEquals(RakNetEndpoint.State.CONNECTED, c.state)
        assertEquals(RakNetEndpoint.State.CONNECTED, s.state)
        return c to s
    }

    private fun connectRequestDatagram(guid: Long): ByteArray {
        val w = RakNetWriter()
        w.u8(0x09)
        w.u64(guid)
        w.u64(1L)
        w.u8(0) // security = false
        val f = RakFrame(
            reliability = RakReliability.RELIABLE_ORDERED,
            payload = w.toByteArray(),
            reliableIndex = 0,
            orderingIndex = 0,
            orderingChannel = 0,
        )
        return RakNetConnected.encodeDatagram(RakNetSession().outbound(listOf(f), nowMs = 0))
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `full handshake reaches CONNECTED on both sides`() {
        val (c, s) = connectedPair()
        assertTrue(c.connected)
        assertTrue(s.connected)
        assertNullError(c)
        assertNullError(s)
    }

    @Test
    fun `application payload roundtrips both directions`() {
        val (c, s) = connectedPair()

        val up = c.send("hello".toByteArray())
        assertEquals(1, up.sends.size)
        val gotUp = s.onDatagram(up.sends[0], clientAddr)
        assertEquals(1, gotUp.payloads.size)
        assertTrue(gotUp.payloads[0].contentEquals("hello".toByteArray()))

        val down = s.send("world".toByteArray())
        assertEquals(1, down.sends.size)
        val gotDown = c.onDatagram(down.sends[0], serverAddr)
        assertEquals(1, gotDown.payloads.size)
        assertTrue(gotDown.payloads[0].contentEquals("world".toByteArray()))
    }

    @Test
    fun `outbound payload splits at mtu and reassembles whole`() {
        val (c, s) = connectedPair(mtuCap = 576)
        val payload = ByteArray(4000) { (it % 251).toByte() }

        val out = c.send(payload)
        assertEquals(8, out.sends.size) // chunk = 576 - 4 - 28 = 544

        val got = ArrayList<ByteArray>()
        for (d in out.sends) {
            val r = s.onDatagram(d, clientAddr)
            got += r.payloads
            for (x in r.sends) c.onDatagram(x, serverAddr)
        }
        assertEquals(1, got.size)
        assertEquals(4000, got[0].size)
        assertTrue(got[0].contentEquals(payload))
    }

    @Test
    fun `out-of-order datagrams deliver in ordering-index order`() {
        val (c, s) = connectedPair()
        val a = s.send(byteArrayOf(0x0a)).sends[0]
        val b = s.send(byteArrayOf(0x0b)).sends[0]

        val first = c.onDatagram(b, serverAddr)
        assertTrue(first.payloads.isEmpty()) // held in ordering backlog

        val second = c.onDatagram(a, serverAddr)
        assertEquals(2, second.payloads.size)
        assertEquals(0x0a, second.payloads[0][0].toInt())
        assertEquals(0x0b, second.payloads[1][0].toInt())
    }

    @Test
    fun `connected ping answered with 0x03 pong`() {
        val (c, s) = connectedPair()
        val ping = c.sendPing()
        assertEquals(1, ping.sends.size)

        val atServer = s.onDatagram(ping.sends[0], clientAddr)
        assertTrue(atServer.payloads.isEmpty())
        assertTrue(atServer.sends.size >= 2) // pong frame + ack of the ping datagram
        val pong = RakNetConnected.decode(atServer.sends[0]) as ConnectedPacket.Datagram
        val pp = pong.datagram.frames[0].payload
        assertEquals(0x03, pp[0].toInt())
        assertEquals(17, pp.size)

        val atClient = c.onDatagram(atServer.sends[0], serverAddr)
        assertTrue(atClient.payloads.isEmpty())
        assertFalse(atClient.disconnected)
    }

    @Test
    fun `0x15 disconnect payload closes peer`() {
        val (c, s) = connectedPair()
        val out = c.send(byteArrayOf(0x15))
        val r = s.onDatagram(out.sends[0], clientAddr)
        assertTrue(r.disconnected)
        assertEquals(RakNetEndpoint.State.CLOSED, s.state)
        assertFalse(s.connected)
    }

    @Test
    fun `denial id inside frame payload closes peer with message`() {
        val (c, s) = connectedPair()
        val out = c.send(byteArrayOf(0x14))
        val r = s.onDatagram(out.sends[0], clientAddr)
        assertTrue(r.disconnected)
        assertEquals("no free incoming connections", s.lastError)
        assertEquals(RakNetEndpoint.State.CLOSED, s.state)
    }

    @Test
    fun `raw denial datagram closes client`() {
        val c = client()
        val r = c.onDatagram(byteArrayOf(0x12), serverAddr)
        assertTrue(r.disconnected)
        assertEquals("already connected", c.lastError)
        assertEquals(RakNetEndpoint.State.CLOSED, c.state)
        assertFalse(c.connected)
    }

    @Test
    fun `client gives up after maxAttempts`() {
        var t = 5_000L
        val c = client(t = { t }, retryIntervalMs = 100, maxAttempts = 3)
        assertTrue(c.onTick().sends.isNotEmpty()) // attempt 1
        t += 100; c.onTick()                      // attempt 2
        t += 100; c.onTick()                      // attempt 3
        t += 100
        val last = c.onTick()                     // attempt 4 > 3
        assertTrue(last.disconnected)
        assertEquals("connect timeout", c.lastError)
        assertEquals(RakNetEndpoint.State.CLOSED, c.state)
    }

    @Test
    fun `server rejects connection request with wrong guid`() {
        val s = server()
        val req1 = RakNetOffline.encode(OfflinePacket.OpenRequest1(RakNetOffline.PROTOCOL_VERSION, 1400))
        assertEquals(1, s.onDatagram(req1, clientAddr).sends.size)
        val req2 = RakNetOffline.encode(OfflinePacket.OpenRequest2(serverAddr, 1400, 42L))
        assertEquals(1, s.onDatagram(req2, clientAddr).sends.size)
        assertEquals(RakNetEndpoint.State.HANDSHAKE_2, s.state)

        val r = s.onDatagram(connectRequestDatagram(guid = 43L), clientAddr)
        assertTrue(r.disconnected)
        assertEquals("connection request failed", s.lastError)
        assertEquals(RakNetEndpoint.State.CLOSED, s.state)
        val denied = RakNetConnected.decode(r.sends[0]) as ConnectedPacket.Datagram
        assertEquals(0x11, denied.datagram.frames[0].payload[0].toInt())
    }

    @Test
    fun `duplicate request2 resends reply2 instead of denying`() {
        val s = server()
        val req1 = RakNetOffline.encode(OfflinePacket.OpenRequest1(RakNetOffline.PROTOCOL_VERSION, 1400))
        s.onDatagram(req1, clientAddr)
        val req2 = RakNetOffline.encode(OfflinePacket.OpenRequest2(serverAddr, 1400, 42L))

        val first = s.onDatagram(req2, clientAddr)
        assertEquals(1, first.sends.size)
        val second = s.onDatagram(req2, clientAddr)
        assertEquals(1, second.sends.size)
        assertTrue(RakNetOffline.decode(second.sends[0]) is OfflinePacket.OpenReply2)
        assertEquals(RakNetEndpoint.State.HANDSHAKE_2, s.state)
        assertEquals(null, s.lastError)
    }

    @Test
    fun `offline ping answered with pong carrying motd`() {
        var t = 1_234L
        val s = server(t = { t })
        val r = s.onDatagram(RakNetOffline.encode(OfflinePacket.Ping(77L)), clientAddr)
        assertEquals(1, r.sends.size)
        val pong = RakNetOffline.decode(r.sends[0]) as OfflinePacket.Pong
        assertEquals(1_234L, pong.time)
        assertEquals(9L, pong.guid)
        assertEquals("MCPE;UnitTest", pong.motd)
    }

    @Test
    fun `client resends request2 while awaiting reply2`() {
        var t = 1_000_000L
        val c = client(t = { t })
        val s = server(t = { t })
        val req1 = c.onTick().sends[0]
        val reply1 = s.onDatagram(req1, clientAddr).sends[0]
        val r = c.onDatagram(reply1, serverAddr)
        assertEquals(RakNetEndpoint.State.HANDSHAKE_2, c.state)
        assertTrue(r.sends.isNotEmpty())

        t += 1000
        val retry = c.onTick()
        assertEquals(1, retry.sends.size)
        assertTrue(RakNetOffline.decode(retry.sends[0]) is OfflinePacket.OpenRequest2)
        assertEquals(RakNetEndpoint.State.HANDSHAKE_2, c.state)
    }

    private fun assertNullError(e: RakNetEndpoint) {
        assertEquals(null, e.lastError)
    }
}
