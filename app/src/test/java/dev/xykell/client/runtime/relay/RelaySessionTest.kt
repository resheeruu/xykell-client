package dev.xykell.client.runtime.relay

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * End-to-end termination session: the relay plays the SERVER role toward the
 * game and the CLIENT role toward the real server, holding both handshake keys
 * at once. Four scripts, all over scripted wires (no sockets):
 *
 *  1. device leg  game settings -> login -> c2s handshake reaches deviceOnline
 *  2. upstream leg login is re-signed for the relay identity, reaches server
 *  3. packets flow both ways through the listener; handshakes do not
 *  4. device packets queue while the upstream stalls, then flush in order
 */
class RelaySessionTest {

    // ------------------------------------------------------------ test fixtures

    private val gameAddr = RakNetAddress("127.0.0.1", 11111)
    private val devAddr = RakNetAddress("127.0.0.1", 19132)
    private val upAddr = RakNetAddress("127.0.0.1", 11112)
    private val srvAddr = RakNetAddress("127.0.0.1", 19133)

    private val gameKp = keypair()
    private val serverKp = keypair()

    private var clock = 1_000_000L
    private fun now() = clock

    /** Game -> relay. */
    private val qToDevice = ArrayDeque<ByteArray>()

    /** Server -> relay. */
    private val qToSessionUp = ArrayDeque<ByteArray>()

    /** Relay -> game. */
    private val qToGame = ArrayDeque<ByteArray>()

    /** Relay -> server. */
    private val qToServer = ArrayDeque<ByteArray>()

    private var stallUpstream = false
    private var gameRequestedSettings = false
    private var listenerLog = ArrayList<String>()
    private val listener = RelayListener { dir, packet ->
        listenerLog.add("${dir.name}:${packet.contentToString()}")
        listOf(packet)
    }

    private var session: RelaySession? = null

    /** Endpoints owned by the session under test. */
    private var deviceEp: RakNetEndpoint? = null
    private var upstreamEp: RakNetEndpoint? = null

    /** Endpoints owned by the fakes. */
    private var gameEp: RakNetEndpoint? = null
    private var srvEp: RakNetEndpoint? = null

    private var gameSend: BedrockCipher? = null
    private var gameRecv: BedrockCipher? = null
    private var serverSend: BedrockCipher? = null
    private var serverRecv: BedrockCipher? = null

    private val gameGotSettings = ArrayList<ByteArray>()
    private val gameGotPackets = ArrayList<ByteArray>()
    private var gameHandshake: HandshakeInfo? = null
    private var serverGotLogin: LoginInfo? = null
    private val serverGotPackets = ArrayList<ByteArray>()

    private val gameIdentityJson: String =
        """{"chain":["${leafJwt(gameKp.public.encoded, gameKp.private)}"]}"""

    private fun newWorld(l: RelayListener = listener) {
        qToDevice.clear(); qToSessionUp.clear(); qToGame.clear(); qToServer.clear()
        listenerLog = ArrayList()
        stallUpstream = false
        gameRequestedSettings = false
        gameGotSettings.clear(); gameGotPackets.clear()
        serverGotPackets.clear()
        gameHandshake = null; serverGotLogin = null
        gameSend = null; gameRecv = null; serverSend = null; serverRecv = null

        deviceEp = RakNetEndpoint(
            mode = RakNetEndpoint.Mode.SERVER,
            guid = 9L,
            sessionTimeoutMs = 600_000,
            nowMs = { now() },
        )
        gameEp = RakNetEndpoint(
            mode = RakNetEndpoint.Mode.CLIENT,
            guid = 7L,
            serverAddress = devAddr,
            sessionTimeoutMs = 600_000,
            nowMs = { now() },
        )
        upstreamEp = RakNetEndpoint(
            mode = RakNetEndpoint.Mode.CLIENT,
            guid = 7L,
            serverAddress = upAddr,
            maxAttempts = 50,
            sessionTimeoutMs = 600_000,
            nowMs = { now() },
        )
        srvEp = RakNetEndpoint(
            mode = RakNetEndpoint.Mode.SERVER,
            guid = 9L,
            advertisement = "MCPE;UnitTest",
            sessionTimeoutMs = 600_000,
            nowMs = { now() },
        )
        session = RelaySession(deviceEp!!, upstreamEp!!, listener = l)
    }

    // ----------------------------------------------------------------- harness

    /** Deliver one queued datagram per call, upstream leg gated by the stall. */
    private fun pump() {
        val d = qToDevice.removeFirstOrNull()
        if (d != null) {
            route(session!!.onGameDatagram(d, gameAddr))
            return
        }
        val g = qToGame.removeFirstOrNull()
        if (g != null) {
            val r = gameEp!!.onDatagram(g, devAddr)
            qToDevice.addAll(r.sends)
            for (p in r.payloads) onGamePayload(p)
            return
        }
        if (stallUpstream && (qToServer.isNotEmpty() || qToSessionUp.isNotEmpty())) return
        val u = qToSessionUp.removeFirstOrNull()
        if (u != null) {
            route(session!!.onUpstreamDatagram(u, srvAddr))
            return
        }
        val s = qToServer.removeFirstOrNull()
        if (s != null) {
            val r = srvEp!!.onDatagram(s, upAddr)
            qToSessionUp.addAll(r.sends)
            for (p in r.payloads) onServerPayload(p)
        }
    }

    private fun route(r: RelaySession.Result) {
        qToGame.addAll(r.toGame)
        qToServer.addAll(r.toUpstream)
    }

    /**
     * Pump until both legs are online and nothing is in flight. Both legs race
     * independently — one finishing early must not look like "settled", or the
     * other leg never gets its turn.
     */
    private fun settle(maxIter: Int = 500): Boolean {
        repeat(maxIter) {
            step()
            val upstreamBusy = if (stallUpstream) {
                qToServer.isNotEmpty() || qToSessionUp.isNotEmpty()
            } else {
                false
            }
            val quiet = qToDevice.isEmpty() && qToGame.isEmpty() && !upstreamBusy &&
                qToServer.isEmpty() && qToSessionUp.isEmpty()
            if (quiet && session!!.deviceOnline && session!!.upstreamOnline) return true
            clock += 20
        }
        return session!!.deviceOnline && session!!.upstreamOnline
    }

    /** Same loop but returns as soon as [cond] holds; tolerates a stalled leg. */
    private fun runUntil(maxIter: Int, cond: () -> Boolean): Boolean {
        repeat(maxIter) {
            if (cond()) return true
            step()
            clock += 20
        }
        return cond()
    }

    /** One full round: one datagram, both fake ticks, the session tick. */
    private fun step() {
        pump()
        for (d in gameEp!!.onTick().sends) qToDevice.addLast(d)
        for (d in srvEp!!.onTick().sends) qToSessionUp.addLast(d)
        // A real client asks for network settings as soon as the RakNet
        // handshake completes, before it sends anything else.
        if (!gameRequestedSettings && gameEp!!.connected) {
            gameRequestedSettings = true
            gameSendPayload(BedrockHandshake.buildRequestNetworkSettings(776))
        }
        route(session!!.onTick())
    }

    // ------------------------------------------------------------------- fakes

    private fun gameSendPayload(payload: ByteArray) {
        qToDevice.addAll(gameEp!!.send(payload).sends)
    }

    private fun sendServer(payload: ByteArray) {
        qToSessionUp.addAll(srvEp!!.send(payload).sends)
    }

    /** Unencrypted 0xFE envelope the game uses before it has the session key. */
    private fun preKeyFrame(packets: List<ByteArray>): ByteArray =
        byteArrayOf(BedrockBatch.FRAME_ID.toByte(), BedrockBatch.COMPRESSOR_DEFLATE.toByte()) +
            BedrockBatch.deflate(BedrockBatch.buildBatch(packets))

    private fun onGamePayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xff) {
            BedrockHandshake.PACKET_NETWORK_SETTINGS -> {
                gameGotSettings.add(payload)
                gameSendPayload(
                    preKeyFrame(
                        listOf(
                            BedrockHandshake.buildLogin(776, gameIdentityJson, "client-token-1"),
                        ),
                    ),
                )
            }
            BedrockBatch.FRAME_ID -> {
                val recv = gameRecv
                if (recv == null) {
                    val hs = findPacket(payload.copyOfRange(1, payload.size),
                        BedrockHandshake.PACKET_S2C_HANDSHAKE) ?: return
                    val jwt = BedrockHandshake.extractVarString(hs, 1) ?: return
                    val info = BedrockHandshake.parseServerHandshake(jwt)
                    gameHandshake = info
                    val key = BedrockCrypto.deriveKey(
                        info.salt,
                        BedrockCrypto.ecdhShared(
                            gameKp.private.encoded,
                            BedrockHandshake.decodeKeyMaterial(info.x5u),
                        ),
                    )
                    gameSend = BedrockCipher(key)
                    gameRecv = BedrockCipher(key)
                    gameSendPayload(
                        BedrockBatch.buildFrame(
                            listOf(BedrockHandshake.buildClientToServerHandshake()),
                            gameSend!!,
                        ),
                    )
                } else {
                    gameGotPackets.addAll(BedrockBatch.openFrame(payload, recv))
                }
            }
        }
    }

    private fun onServerPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xff) {
            BedrockHandshake.PACKET_REQUEST_NETWORK_SETTINGS -> {
                sendServer(BedrockHandshake.buildNetworkSettings())
            }
            BedrockBatch.FRAME_ID -> {
                val recv = serverRecv
                if (recv == null) {
                    val login = findPacket(payload.copyOfRange(1, payload.size),
                        BedrockHandshake.PACKET_LOGIN) ?: return
                    val parsed = BedrockHandshake.parseLogin(login)
                    serverGotLogin = parsed
                    val salt = ByteArray(16) { (0x51 + it).toByte() }
                    val key = BedrockCrypto.deriveKey(
                        salt,
                        BedrockCrypto.ecdhShared(
                            serverKp.private.encoded,
                            BedrockHandshake.identityPublicKey(parsed.identityJson),
                        ),
                    )
                    serverSend = BedrockCipher(key)
                    serverRecv = BedrockCipher(key)
                    val jwt = BedrockHandshake.signServerHandshake(
                        serverKp.private, serverKp.public.encoded, salt,
                    )
                    val jwtBytes = jwt.toByteArray(Charsets.UTF_8)
                    val token = byteArrayOf(BedrockHandshake.PACKET_S2C_HANDSHAKE.toByte()) +
                        BedrockHandshake.varUInt(jwtBytes.size) + jwtBytes
                    sendServer(preKeyFrame(listOf(token)))
                } else {
                    for (p in BedrockBatch.openFrame(payload, recv)) {
                        if (p.isNotEmpty() && (p[0].toInt() and 0xff) ==
                            BedrockHandshake.PACKET_C2S_HANDSHAKE
                        ) {
                            continue
                        }
                        serverGotPackets.add(p)
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun findPacket(body: ByteArray, id: Int): ByteArray? =
        BedrockBatch.plainCandidates(body).firstNotNullOfOrNull { group ->
            group.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0xff) == id }
        }

    private fun keypair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp384r1"))
    }.generateKeyPair()

    private fun b64url(data: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun leafJwt(pub: ByteArray, priv: java.security.PrivateKey): String {
        val header = """{"x5u":"${b64url(pub)}"}"""
        val payload =
            """{"identityPublicKey":"${Base64.getEncoder().encodeToString(pub)}"}"""
        return BedrockHandshake.signEs384(header, payload, priv)
    }

    private fun pkt(vararg body: Int) = ByteArray(body.size) { body[it].toByte() }

    /** ByteArray has identity equals, so list comparison must be by content. */
    private fun assertPackets(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.map { it.contentToString() }, actual.map { it.contentToString() })
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun `device leg settings login handshake reaches deviceOnline`() {
        newWorld()
        assertTrue("settle timed out", settle())

        assertEquals(1, gameGotSettings.size)
        assertEquals(
            BedrockHandshake.PACKET_NETWORK_SETTINGS,
            gameGotSettings[0][0].toInt() and 0xff,
        )
        assertEquals(BedrockHandshake.PACKET_LOGIN, gameHandshake!!.salt.size / 16)
        assertTrue(session!!.deviceOnline)
        assertNull(session!!.lastError)
    }

    @Test
    fun `upstream login rewritten to relay identity`() {
        newWorld()
        assertTrue("settle timed out", settle())

        val login = serverGotLogin!!
        assertEquals(776, login.protocolVersion)
        assertEquals("client-token-1", login.clientToken)
        assertArrayEquals(
            session!!.identity.publicSpki,
            BedrockHandshake.identityPublicKey(login.identityJson),
        )
        assertTrue(session!!.upstreamOnline)
        assertNull(session!!.lastError)
    }

    @Test
    fun `packets forward both directions through listener`() {
        newWorld()
        assertTrue("settle timed out", settle())
        assertTrue(session!!.upstreamOnline)
        listenerLog = ArrayList()

        val t1 = pkt(0x7f, 0x01)
        val t2 = pkt(0x7f, 0x02)
        val t3 = pkt(0x7f, 0x03)
        gameSendPayload(BedrockBatch.buildFrame(listOf(t1, t2), gameSend!!))
        sendServer(BedrockBatch.buildFrame(listOf(t3), serverSend!!))
        assertTrue("forward settle timed out", settle())

        assertPackets(listOf(t1, t2), serverGotPackets)
        assertPackets(listOf(t3), gameGotPackets)
        assertEquals(3, listenerLog.size)
        assertTrue(listenerLog.toString(), listenerLog.contains("TO_SERVER:[127, 1]"))
        assertTrue(listenerLog.toString(), listenerLog.contains("TO_SERVER:[127, 2]"))
        assertTrue(listenerLog.toString(), listenerLog.contains("TO_CLIENT:[127, 3]"))
    }

    @Test
    fun `device packets queue until upstream online then flush in order`() {
        newWorld()
        stallUpstream = true
        assertTrue("device leg never came up", runUntil(300) { session!!.deviceOnline })

        val t1 = pkt(0x7e, 0x11)
        val t2 = pkt(0x7e, 0x22)
        gameSendPayload(BedrockBatch.buildFrame(listOf(t1), gameSend!!))
        gameSendPayload(BedrockBatch.buildFrame(listOf(t2), gameSend!!))
        pump()
        assertPackets(emptyList(), serverGotPackets)

        stallUpstream = false
        assertTrue("upstream settle timed out", settle(500))
        assertTrue(session!!.upstreamOnline)
        assertPackets(listOf(t1, t2), serverGotPackets)
    }
}