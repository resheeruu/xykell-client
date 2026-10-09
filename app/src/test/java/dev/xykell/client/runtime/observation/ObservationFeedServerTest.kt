package dev.xykell.client.runtime.observation

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the loopback app->game feed server (Phase F).
 * Real sockets on 127.0.0.1 with ephemeral ports; bounded timeouts so a
 * dead server fails the assertion instead of hanging the suite.
 *
 * These were the one flaky suite: a 10 s client read timeout expired under the
 * load of a full run on the phone and failed a server that was fine. The
 * timeout is now sized for a loaded phone rather than for an idle desktop.
 */
class ObservationFeedServerTest {

    private var server: ObservationFeedServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    private fun startServer(): ObservationFeedServer =
        ObservationFeedServer(0).also { it.start(); server = it }

    // Persistent reader per socket: a fresh BufferedReader per read would
    // swallow a coalesced next line buffered during the previous read.
    private class Client(socket: Socket) : AutoCloseable {
        private val socket = socket
        private val reader =
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        fun readLine(): String = reader.readLine()
        override fun close() = socket.close()
    }

    private fun connect(port: Int): Client =
        Client(Socket(InetAddress.getLoopbackAddress(), port).apply {
            // Generous on purpose. All 57 suites share one JVM on a phone, so a
            // 10 s read can expire purely from load and fail a server that is
            // working perfectly. Still bounded, so a genuinely dead server fails
            // instead of hanging the suite.
            soTimeout = 45_000
            tcpNoDelay = true
        })

    private fun readLine(client: Client): String = client.readLine()

    // --- lifecycle ---
    @Test
    fun lifecycle_boundEphemeralAndClose() {
        val s = startServer()
        assertTrue(s.boundPort > 0)
        s.close()
        // Port released: a fresh bind on the same port must succeed.
        java.net.ServerSocket(s.boundPort).use { rebound ->
            assertEquals(s.boundPort, rebound.localPort)
        }
    }

    // --- replay: a client connecting after publish gets current state ---
    @Test
    fun replay_lastLinePerTypeOnConnect() {
        val s = startServer()
        s.publish("travel", """{"t":"travel","id":"r1","at":1,"x":0,"y":64,"z":0,"yaw":0,"m":0,"method":0}""")
        s.publish("chat", """{"t":"chat","id":"r2","at":2,"sender":"Steve","msg":"hi"}""")
        // Second travel publish replaces the replayed travel line.
        s.publish("travel", """{"t":"travel","id":"r3","at":3,"x":9,"y":64,"z":9,"yaw":0,"m":0,"method":0}""")

        connect(s.boundPort).use { c ->
            assertEquals("""{"t":"travel","id":"r3","at":3,"x":9,"y":64,"z":9,"yaw":0,"m":0,"method":0}""", readLine(c))
            assertEquals("""{"t":"chat","id":"r2","at":2,"sender":"Steve","msg":"hi"}""", readLine(c))
        }
    }

    // --- live broadcast: connected clients receive new lines ---
    @Test
    fun broadcast_reachesAllConnectedClients() {
        val s = startServer()
        val a = connect(s.boundPort)
        val b = connect(s.boundPort)
        // Accept is async: publish once the servers have accepted (poll via replay).
        s.publish("chat", """{"t":"chat","id":"w1","at":1,"sender":"A","msg":"one"}""")
        // Both sockets read the SAME line (it may arrive as replay or broadcast
        // depending on accept timing; either way exactly one copy per client).
        assertEquals("""{"t":"chat","id":"w1","at":1,"sender":"A","msg":"one"}""", readLine(a))
        assertEquals("""{"t":"chat","id":"w1","at":1,"sender":"A","msg":"one"}""", readLine(b))
        s.publish("chat", """{"t":"chat","id":"w2","at":2,"sender":"A","msg":"two"}""")
        assertEquals("""{"t":"chat","id":"w2","at":2,"sender":"A","msg":"two"}""", readLine(a))
        assertEquals("""{"t":"chat","id":"w2","at":2,"sender":"A","msg":"two"}""", readLine(b))
        a.close()
        b.close()
    }

    // --- dead client: publish must not throw, survivors keep working ---
    @Test
    fun deadClient_droppedSurvivorsUnaffected() {
        val s = startServer()
        val dead = connect(s.boundPort)
        val alive = connect(s.boundPort)
        s.publish("chat", """{"t":"chat","id":"d0","at":0,"sender":"A","msg":"warm"}""")
        assertEquals("""{"t":"chat","id":"d0","at":0,"sender":"A","msg":"warm"}""", readLine(alive))
        dead.close()
        s.publish("chat", """{"t":"chat","id":"d1","at":1,"sender":"A","msg":"after"}""")
        assertEquals("""{"t":"chat","id":"d1","at":1,"sender":"A","msg":"after"}""", readLine(alive))
        alive.close()
    }

    // --- wire contract: field names must match native applyLine (feed_client) ---
    @Test
    fun wire_travelFieldsMatchNativeContract() {
        val item = Travelled("w1", 42L, 1.0, 64.0, -2.5, 90.0, 12.5, 2)
        val (key, line) = ObservationFeedWire.encode(item)!!
        assertEquals("travel", key)
        val o = JSONObject(line)
        assertEquals("travel", o.getString("t"))
        assertEquals("w1", o.getString("id"))
        assertEquals(42L, o.getLong("at"))
        assertEquals(1.0, o.getDouble("x"), 0.0)
        assertEquals(64.0, o.getDouble("y"), 0.0)
        assertEquals(-2.5, o.getDouble("z"), 0.0)
        assertEquals(90.0, o.getDouble("yaw"), 0.0)
        assertEquals(12.5, o.getDouble("m"), 0.0)
        assertEquals(2, o.getInt("method"))
    }

    @Test
    fun wire_chatFieldsMatchNativeContract() {
        val item = ChatMessage("c1", 7L, "Steve", "hello")
        val (key, line) = ObservationFeedWire.encode(item)!!
        assertEquals("chat", key)
        val o = JSONObject(line)
        assertEquals("chat", o.getString("t"))
        assertEquals("c1", o.getString("id"))
        assertEquals(7L, o.getLong("at"))
        assertEquals("Steve", o.getString("sender"))
        assertEquals("hello", o.getString("msg"))
    }

    /**
     * Vitals are the first optional-field line on the wire. The native
     * applyVitals reads a MISSING key as "not observed", so an absent field must
     * be absent from the JSON — writing 0 would be read as a real zero health.
     */
    @Test
    fun wire_vitalsOmitUnobservedFieldsRatherThanZeroingThem() {
        val (key, line) = ObservationFeedWire.encode(Vitals("v1", 9L, 18, null))!!
        assertEquals("vitals", key)
        val o = JSONObject(line)
        assertEquals("vitals", o.getString("t"))
        assertEquals("v1", o.getString("id"))
        assertEquals(9L, o.getLong("at"))
        assertEquals(18, o.getInt("health"))
        assertFalse("clock was never observed but was written", o.has("ticks"))

        val clockOnly = JSONObject(ObservationFeedWire.encode(Vitals("v2", 10L, null, 6000))!!.second)
        assertFalse(clockOnly.has("health"))
        assertEquals(6000, clockOnly.getInt("ticks"))

        // A real zero must survive as a written value, distinct from absence.
        val zero = JSONObject(ObservationFeedWire.encode(Vitals("v3", 11L, 0, null))!!.second)
        assertTrue(zero.has("health"))
        assertEquals(0, zero.getInt("health"))
    }

    @Test
    fun wire_unknownFrameNotForwarded() {
        assertNull(ObservationFeedWire.encode(UnknownFrame("u1", 1L, 10, "reason")))
    }

    // --- close: publish after close is a no-op, not an exception ---
    @Test
    fun publishAfterClose_isSafeNoOp() {
        val s = startServer()
        s.close()
        s.publish("chat", """{"t":"chat","id":"x","at":1,"sender":"A","msg":"late"}""")
        assertFalse(s.isOpen)
        assertTrue(s.boundPort > 0) // last bound port still reported
    }
}
