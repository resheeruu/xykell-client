package dev.xykell.client.runtime.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/** The relay's own EC P-384 identity — the keypair it signs upstream logins with. */
class BedrockIdentity(val keyPair: KeyPair) {
    val publicSpki: ByteArray get() = keyPair.public.encoded
    val privateKey: PrivateKey get() = keyPair.private

    companion object {
        fun generate(): BedrockIdentity {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp384r1"))
            return BedrockIdentity(gen.generateKeyPair())
        }
    }
}

/**
 * TO_SERVER = travelling game -> upstream server. TO_CLIENT = travelling
 * upstream server -> game.
 */
enum class RelayDirection { TO_SERVER, TO_CLIENT }

/**
 * Packet hook applied to forwarded traffic only — handshake packets (0xc1, 0x8f,
 * 0x01, 0x03, 0x04) never reach it, so a module can assume gameplay shape.
 * Returning an empty list drops the packet, one output rewrites it, several
 * spawn extras; that is all a transform can meaningfully do.
 */
fun interface RelayListener {
    fun transform(direction: RelayDirection, packet: ByteArray): List<ByteArray>

    companion object {
        val PASS = RelayListener { _, packet -> listOf(packet) }
    }
}

/**
 * Full Bedrock termination leg: SERVER role toward the game, CLIENT role
 * toward the upstream server, both handshake keys held at once (PROXY-DESIGN §6).
 * Transparent UDP forwarding cannot do this — the relay has to end both
 * sessions to re-sign the client's identity — so it owns a [RakNetEndpoint] per
 * leg and moves plaintext between them.
 *
 * Wire facts per leg: game sends 0xc1 -> relay answers 0x8f -> game sends 0x01
 * (pre-key) -> relay answers pre-key 0x03 and seals the game's 0x04 onward.
 * Upstream: relay sends 0xc1 -> server answers 0x8f -> relay sends the login
 * with the identity leaf swapped for its own -> server answers pre-key 0x03 ->
 * relay seals 0x04 and flushes. Four ciphers, because each leg has its own key.
 *
 * Packets that arrive before both legs are online queue in plaintext (capped);
 * each direction flushes as one sealed frame the moment its far side is up.
 * No sockets and no threads here — the caller feeds datagrams, ticks, and
 * sends the returned bytes.
 */
class RelaySession(
    private val deviceEp: RakNetEndpoint,
    private val upstreamEp: RakNetEndpoint,
    val identity: BedrockIdentity = BedrockIdentity.generate(),
    private val listener: RelayListener = RelayListener.PASS,
    /**
     * Called once, when the upstream reaches PLAY, with the protocol version
     * the client's own login announced.
     *
     * Injected rather than called directly because the production sink crosses
     * JNI into the native observation consumer, which a host JVM test cannot
     * load. Failing to notify must never fail the session: the connection is
     * real whether or not anything is watching.
     */
    private val onOnline: (protocolVersion: Int) -> Unit = {},
) {
    private enum class DevState { WAIT_SETTINGS, WAIT_LOGIN, WAIT_C2S, ONLINE, FAILED }

    // WAIT_CONNECTED: RakNet handshake to the upstream still running.
    private enum class UpState { WAIT_CONNECTED, WAIT_NS, WAIT_LOGIN_SENT, ONLINE, FAILED }

    data class Result(
        val toGame: List<ByteArray> = emptyList(),
        val toUpstream: List<ByteArray> = emptyList(),
        val disconnected: Boolean = false,
    )

    private var devState = DevState.WAIT_SETTINGS
    private var upState = UpState.WAIT_CONNECTED
    var lastError: String? = null
        private set
    private var deviceProto: Int? = null
    private var loginInfo: LoginInfo? = null

    private var devC2s: BedrockCipher? = null
    private var devS2c: BedrockCipher? = null
    private var upC2s: BedrockCipher? = null
    private var upS2c: BedrockCipher? = null

    private val toServerQueue = ArrayList<ByteArray>()
    private val toGameQueue = ArrayList<ByteArray>()
    private val pendingToGame = ArrayList<ByteArray>()
    private val pendingToUpstream = ArrayList<ByteArray>()

    val deviceOnline: Boolean get() = devState == DevState.ONLINE
    val upstreamOnline: Boolean get() = upState == UpState.ONLINE

    // ------------------------------------------------------------- entry points

    /** A datagram the game sent us. Payloads it decoded become our input. */
    fun onGameDatagram(data: ByteArray, from: RakNetAddress): Result {
        if (devState == DevState.FAILED) return Result(disconnected = true)
        val r = deviceEp.onDatagram(data, from)
        if (r.disconnected) {
            fail("device leg disconnected")
            return Result(disconnected = true)
        }
        beginOutput()
        pendingToGame.addAll(r.sends)
        for (payload in r.payloads) {
            if (!handleDevice(payload)) break
        }
        return result()
    }

    /** A datagram the upstream server sent us. */
    fun onUpstreamDatagram(data: ByteArray, from: RakNetAddress): Result {
        if (upState == UpState.FAILED) return Result(disconnected = true)
        val r = upstreamEp.onDatagram(data, from)
        if (r.disconnected) {
            fail("upstream leg disconnected")
            return Result(disconnected = true)
        }
        beginOutput()
        pendingToUpstream.addAll(r.sends)
        for (payload in r.payloads) {
            if (!handleUpstream(payload)) break
        }
        return result()
    }

    /** Tick both owned endpoints, open the upstream login once, flush what is due. */
    fun onTick(): Result {
        if (devState == DevState.FAILED || upState == UpState.FAILED) {
            return Result(disconnected = true)
        }
        beginOutput()
        pendingToGame.addAll(deviceEp.onTick().sends)
        val up = upstreamEp.onTick()
        if (up.disconnected) {
            fail("upstream leg disconnected")
            return Result(disconnected = true)
        }
        pendingToUpstream.addAll(up.sends)
        if (!maybeStartUpstream()) return Result(disconnected = true)
        return result()
    }

    fun close() {
        fail("session closed")
    }

    private fun beginOutput() {
        pendingToGame.clear()
        pendingToUpstream.clear()
    }

    private fun result(): Result = Result(
        toGame = pendingToGame.toList(),
        toUpstream = pendingToUpstream.toList(),
        disconnected = devState == DevState.FAILED || upState == UpState.FAILED,
    )

    private fun fail(message: String): Boolean {
        lastError = message
        devState = DevState.FAILED
        upState = UpState.FAILED
        return false
    }

    // ----------------------------------------------------------------- device

    private fun handleDevice(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val id = payload[0].toInt() and 0xff
        try {
            when (devState) {
                DevState.WAIT_SETTINGS -> {
                    if (id != BedrockHandshake.PACKET_REQUEST_NETWORK_SETTINGS) {
                        return fail("expected request_network_settings, got 0x${id.toString(16)}")
                    }
                    if (payload.size < 5) return fail("request_network_settings truncated")
                    deviceProto = ByteBuffer.wrap(payload, 1, 4)
                        .order(ByteOrder.BIG_ENDIAN).int
                    sendToDevice(BedrockHandshake.buildNetworkSettings())
                    devState = DevState.WAIT_LOGIN
                }
                DevState.WAIT_LOGIN -> {
                    val login = findPlain(payload, BedrockHandshake.PACKET_LOGIN)
                        ?: return fail("expected login, got 0x${id.toString(16)}")
                    val parsed = BedrockHandshake.parseLogin(login)
                    loginInfo = parsed
                    val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
                    val key = BedrockCrypto.deriveKey(
                        salt,
                        BedrockCrypto.ecdhShared(
                            identity.privateKey.encoded,
                            BedrockHandshake.identityPublicKey(parsed.identityJson),
                        ),
                    )
                    devC2s = BedrockCipher(key)
                    devS2c = BedrockCipher(key)
                    val jwt = BedrockHandshake.signServerHandshake(
                        identity.privateKey, identity.publicSpki, salt,
                    )
                    sendToDevice(preKeyFrame(listOf(tokenPacket(jwt))))
                    devState = DevState.WAIT_C2S
                }
                DevState.WAIT_C2S -> {
                    val packets = BedrockBatch.openFrame(payload, devC2s!!)
                    if (packets.isEmpty()) return true
                    if (packets[0].isEmpty() ||
                        (packets[0][0].toInt() and 0xff) != BedrockHandshake.PACKET_C2S_HANDSHAKE
                    ) {
                        return fail("expected c2s handshake first")
                    }
                    devState = DevState.ONLINE
                    if (!emit(RelayDirection.TO_SERVER, packets.drop(1))) return false
                    flushToGame()
                }
                DevState.ONLINE -> {
                    if (id != BedrockBatch.FRAME_ID) {
                        return fail("bare payload while online, id 0x${id.toString(16)}")
                    }
                    if (!emit(RelayDirection.TO_SERVER, BedrockBatch.openFrame(payload, devC2s!!))) {
                        return false
                    }
                }
                DevState.FAILED -> return false
            }
        } catch (e: BedrockFormatException) {
            return fail(e.message ?: "device payload rejected")
        }
        return true
    }

    // --------------------------------------------------------------- upstream

    /** Both the login and the game key are known and the socket is up: send 0xc1. */
    private fun maybeStartUpstream(): Boolean {
        if (upState != UpState.WAIT_CONNECTED) return true
        val login = loginInfo ?: return true
        if (!upstreamEp.connected) return true
        val proto = deviceProto ?: login.protocolVersion
        sendToUpstream(BedrockHandshake.buildRequestNetworkSettings(proto))
        upState = UpState.WAIT_NS
        return true
    }

    private fun handleUpstream(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val id = payload[0].toInt() and 0xff
        try {
            when (upState) {
                UpState.WAIT_NS -> {
                    if (id != BedrockHandshake.PACKET_NETWORK_SETTINGS) {
                        return fail("upstream: expected network_settings, got 0x${id.toString(16)}")
                    }
                    val login = loginInfo ?: return fail("upstream: no login captured")
                    val rewritten = BedrockHandshake.rewriteLogin(
                        login, identity.publicSpki, identity.privateKey,
                    )
                    sendToUpstream(
                        preKeyFrame(
                            listOf(
                                BedrockHandshake.buildLogin(
                                    rewritten.protocolVersion,
                                    rewritten.identityJson,
                                    rewritten.clientToken,
                                ),
                            ),
                        ),
                    )
                    upState = UpState.WAIT_LOGIN_SENT
                }
                UpState.WAIT_LOGIN_SENT -> {
                    val hs = when (id) {
                        BedrockBatch.FRAME_ID ->
                            findPlain(payload, BedrockHandshake.PACKET_S2C_HANDSHAKE)
                        BedrockHandshake.PACKET_S2C_HANDSHAKE -> payload
                        else -> null
                    } ?: return fail("upstream: expected s2c handshake, got 0x${id.toString(16)}")
                    val jwt = BedrockHandshake.extractVarString(hs, 1)
                        ?: return fail("upstream: handshake token missing")
                    val info = BedrockHandshake.parseServerHandshake(jwt)
                    val key = BedrockCrypto.deriveKey(
                        info.salt,
                        BedrockCrypto.ecdhShared(
                            identity.privateKey.encoded,
                            BedrockHandshake.decodeKeyMaterial(info.x5u),
                        ),
                    )
                    upC2s = BedrockCipher(key)
                    upS2c = BedrockCipher(key)
                    upState = UpState.ONLINE
                    try {
                        onOnline(loginInfo?.protocolVersion ?: 0)
                    } catch (e: Exception) {
                        // A notification failure is not a session failure.
                    }
                    sendToUpstream(
                        BedrockBatch.buildFrame(
                            listOf(BedrockHandshake.buildClientToServerHandshake()),
                            upC2s!!,
                        ),
                    )
                    flushToServer()
                }
                UpState.ONLINE -> {
                    if (id != BedrockBatch.FRAME_ID) {
                        return fail("upstream: bare payload while online, id 0x${id.toString(16)}")
                    }
                    if (!emit(RelayDirection.TO_CLIENT, BedrockBatch.openFrame(payload, upS2c!!))) {
                        return false
                    }
                }
                else -> return false
            }
        } catch (e: BedrockFormatException) {
            return fail(e.message ?: "upstream payload rejected")
        }
        return true
    }

    // ---------------------------------------------------------------- bridging

    /**
     * Seal the transformed packets toward [direction]: straight through when
     * both legs are online, otherwise queue them in plaintext for the flush.
     */
    private fun emit(direction: RelayDirection, packets: List<ByteArray>): Boolean {
        val out = ArrayList<ByteArray>()
        for (packet in packets) out.addAll(listener.transform(direction, packet))
        if (out.isEmpty()) return true
        if (deviceOnline && upstreamOnline) {
            // Seal with the cipher for the leg this traffic is leaving through:
            // upstream c2s toward the server, device s2c toward the game.
            val cipher = if (direction == RelayDirection.TO_SERVER) upC2s!! else devS2c!!
            val frame = BedrockBatch.buildFrame(out, cipher)
            return if (direction == RelayDirection.TO_SERVER) {
                sendToUpstream(frame); true
            } else {
                sendToDevice(frame); true
            }
        }
        val queue = if (direction == RelayDirection.TO_SERVER) toServerQueue else toGameQueue
        if (queue.size + out.size > MAX_QUEUED) return fail("relay queue overflow")
        queue.addAll(out)
        return true
    }

    private fun flushToServer() {
        if (toServerQueue.isEmpty()) return
        val frame = BedrockBatch.buildFrame(toServerQueue.toList(), upC2s!!)
        toServerQueue.clear()
        sendToUpstream(frame)
    }

    private fun flushToGame() {
        if (toGameQueue.isEmpty()) return
        val frame = BedrockBatch.buildFrame(toGameQueue.toList(), devS2c!!)
        toGameQueue.clear()
        sendToDevice(frame)
    }

    private fun sendToDevice(payload: ByteArray) {
        pendingToGame.addAll(deviceEp.send(payload).sends)
    }

    private fun sendToUpstream(payload: ByteArray) {
        pendingToUpstream.addAll(upstreamEp.send(payload).sends)
    }

    /** Unencrypted 0xFE envelope used while a leg has no session key yet. */
    private fun preKeyFrame(packets: List<ByteArray>): ByteArray =
        byteArrayOf(
            BedrockBatch.FRAME_ID.toByte(),
            BedrockBatch.COMPRESSOR_NONE.toByte(),
        ) + BedrockBatch.buildBatch(packets)

    private fun tokenPacket(jwt: String): ByteArray {
        val bytes = jwt.toByteArray(Charsets.UTF_8)
        return byteArrayOf(BedrockHandshake.PACKET_S2C_HANDSHAKE.toByte()) +
            BedrockHandshake.varUInt(bytes.size) + bytes
    }

    /** First packet with [id] among the pre-encryption shapes this payload can take. */
    private fun findPlain(payload: ByteArray, id: Int): ByteArray? {
        if (payload[0].toInt() and 0xff != BedrockBatch.FRAME_ID) {
            return payload.takeIf { it[0].toInt() and 0xff == id }
        }
        val body = payload.copyOfRange(1, payload.size)
        for (group in BedrockBatch.plainCandidates(body)) {
            for (p in group) {
                if (p.isNotEmpty() && (p[0].toInt() and 0xff) == id) return p
            }
        }
        return null
    }

    companion object {
        private const val SALT_BYTES = 16
        private const val MAX_QUEUED = 4096
    }
}