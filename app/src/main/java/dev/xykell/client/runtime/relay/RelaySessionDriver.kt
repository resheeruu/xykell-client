package dev.xykell.client.runtime.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import dev.xykell.client.runtime.network.RelayTuning

/**
 * Socket driver for [RelaySession]: two loopback sockets, one receive loop, one
 * tick timer.
 *
 * [RelaySession] is pure — it takes datagrams, returns datagrams, and never
 * touches a socket — so it could be host-tested and still unreachable from the
 * app. This is the class that makes it real: it owns the game-facing socket
 * (bound on loopback, the port the user puts in the game) and the upstream
 * socket (connected to the real server), feeds both into the session, and
 * writes whatever comes back.
 *
 * Shape follows [RelayPipe] deliberately: one receive thread per socket would
 * be two threads for one job, so both sockets are polled from one loop with a
 * short timeout, and the session tick rides the same loop. Single-threaded by
 * construction — the session documents that the caller serializes its calls.
 *
 * Two rules, both from the session's own contract:
 *  - a listener that throws must not kill the relay, so every session call is
 *    wrapped and a failure closes the session instead of the thread throwing;
 *  - `disconnected` is terminal, so the loop stops and [close] releases both
 *    sockets rather than spinning on a dead session.
 */
class RelaySessionDriver(
    private val upstreamHost: String,
    upstreamPort: Int,
    listenPort: Int = 0,
    listener: RelayListener = RelayListener.PASS,
    private val onStopped: (String?) -> Unit = {},
    /** Forwarded to the session; see its onOnline for the shape. */
    private val onOnline: (protocolVersion: Int) -> Unit = {},
) : AutoCloseable {

    /**
     * Upstream address, resolved on the relay loop rather than in this
     * constructor. The caller is the service's main thread, and a cold DNS
     * lookup there is a multi-second main-thread stall behind a black screen.
     * Resolution is cached per host, so a reconnect costs nothing.
     */
    private val upstreamPortValue = upstreamPort

    private val upstreamResolver = RelayTuning.UpstreamResolver(
        lookup = { host: String -> InetAddress.getByName(host) },
    )

    private val upstreamAddr: InetSocketAddress by lazy {
        val addr = upstreamResolver.resolve(upstreamHost)
            ?: throw IllegalStateException("upstream host unresolved: $upstreamHost")
        InetSocketAddress(addr, upstreamPortValue)
    }

    private val gameSocket = DatagramSocket(listenPort, InetAddress.getLoopbackAddress()).apply {
        // Short poll timeout, never infinite: one loop drives both sockets and
        // the session tick, so a blocking receive on one would starve the other.
        soTimeout = POLL_TIMEOUT_MS
        // A Bedrock relay carries RakNet datagrams both ways on one loop. The
        // platform default buffer is small enough that a real server's burst
        // overruns it, and a dropped datagram presents as a stalled connection.
        // Request more; the kernel clamps, so ask for the granted value.
        runCatching { receiveBufferSize = RelayTuning.bufferRequest() }
        runCatching { sendBufferSize = RelayTuning.bufferRequest() }
    }

    /** What the kernel actually granted, for the relay screen. Null = unknown. */
    val bufferSummary: String by lazy {
        RelayTuning.describeBuffers(
            RelayTuning.REQUESTED_BUFFER_BYTES,
            runCatching { gameSocket.receiveBufferSize }.getOrDefault(0),
            runCatching { gameSocket.sendBufferSize }.getOrDefault(0),
        )
    }

    @Volatile
    private var clientAddr: InetSocketAddress? = null

    @Volatile
    private var running = true

    private var thread: Thread? = null

    /** The port to enter in the game. */
    val port: Int get() = gameSocket.localPort

    /**
     * True only once BOTH legs completed their handshake.
     *
     * This is the difference between a bound relay and a working one: the game
     * socket answers a ping long before the upstream login has been rewritten and
     * sealed, and reporting that as "online" would claim a connection that drops
     * every packet.
     */
    fun bothLegsOnline(): Boolean = running && session.deviceOnline && session.upstreamOnline

    /** The session's own error string once it has stopped, else null. */
    @Volatile
    var lastError: String? = null
        private set

    /** Why this session was built: SERVER toward the game, CLIENT upstream. */
    private val session = RelaySession(
        deviceEp = RakNetEndpoint(
            mode = RakNetEndpoint.Mode.SERVER,
            guid = DEVICE_GUID,
            sessionTimeoutMs = SESSION_TIMEOUT_MS,
        ),
        upstreamEp = RakNetEndpoint(
            mode = RakNetEndpoint.Mode.CLIENT,
            guid = UPSTREAM_GUID,
            serverAddress = RakNetAddress(upstreamHost, upstreamPort),
            sessionTimeoutMs = SESSION_TIMEOUT_MS,
        ),
        listener = listener,
        onOnline = onOnline,
    )

    fun start() {
        check(thread == null) { "already started" }
        thread = Thread(::loop, "relay-session").apply {
            isDaemon = true
            // Latency-sensitive loop, but the HUD and ClickGUI render on the
            // main thread. One step above default is enough to win a busy-core
            // slice without costing frame time.
            priority = RelayTuning.threadPriority()
            start()
        }
    }

    private fun loop() {
        val gameBuf = ByteArray(MAX_DATAGRAM)
        val upBuf = ByteArray(MAX_DATAGRAM)
        while (running) {
            try {
                val acted = pollGame(gameBuf) || pollUpstream(upBuf)
                val r = session.onTick()
                if (!route(r)) return
                // Idle loop: keep the RakNet retry ladder and session timeout alive
                // without burning the battery.
                if (!acted) Thread.sleep(TICK_IDLE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                stop("relay loop interrupted")
                return
            } catch (e: Exception) {
                // The class contract says a failure closes the session rather
                // than the thread throwing. Socket errors and a dead upstream
                // DNS entry both arrive here, and an uncaught throw would kill
                // the loop silently while the service kept reporting "running".
                stop("relay loop failed: ${e.message}")
                return
            }
        }
    }

    private fun pollGame(buf: ByteArray): Boolean {
        val pkt = DatagramPacket(buf, buf.size)
        try {
            gameSocket.receive(pkt)
        } catch (e: java.net.SocketException) {
            return false // closed under us: running is already false
        } catch (e: java.net.SocketTimeoutException) {
            return false
        }
        val from = pkt.socketAddress as? InetSocketAddress ?: return false
        if (from.address == upstreamAddr.address && from.port == upstreamAddr.port) return false
        if (clientAddr == null) clientAddr = from
        val r = try {
            session.onGameDatagram(pkt.data.copyOf(pkt.length), RakNetAddress(
                from.address.hostAddress ?: "127.0.0.1",
                from.port,
            ))
        } catch (e: Exception) {
            stop("device datagram rejected: ${e.message}")
            return true
        }
        if (!route(r)) return true
        return true
    }

    private fun pollUpstream(buf: ByteArray): Boolean {
        val pkt = DatagramPacket(buf, buf.size)
        try {
            // The upstream socket is unconnected on purpose: RakNet replies come
            // from the address we sent to, but a redirect or a second server
            // address must still be readable.
            upstreamSocket().receive(pkt)
        } catch (e: java.net.SocketTimeoutException) {
            return false
        }
        val r = try {
            session.onUpstreamDatagram(pkt.data.copyOf(pkt.length), RakNetAddress(
                upstreamAddr.address.hostAddress ?: "127.0.0.1",
                upstreamAddr.port,
            ))
        } catch (e: Exception) {
            stop("upstream datagram rejected: ${e.message}")
            return true
        }
        if (!route(r)) return true
        return true
    }

    @Volatile
    private var upstream: DatagramSocket? = null

    private fun upstreamSocket(): DatagramSocket {
        val existing = upstream
        if (existing != null) return existing
        val s = DatagramSocket().apply {
            soTimeout = POLL_TIMEOUT_MS
            connect(upstreamAddr)
        }
        upstream = s
        return s
    }

    /** Send both results and report whether the session is still alive. */
    private fun route(r: RelaySession.Result): Boolean {
        for (d in r.toGame) sendGame(d)
        for (d in r.toUpstream) sendUpstream(d)
        if (!r.disconnected) return true
        stop(session.lastError ?: "session disconnected")
        return false
    }

    private fun sendGame(data: ByteArray) {
        val dest = clientAddr ?: return
        try {
            gameSocket.send(DatagramPacket(data, data.size, dest))
        } catch (e: Exception) {
            // The game vanished mid-session; the session's own close path ends it.
        }
    }

    private fun sendUpstream(data: ByteArray) {
        try {
            upstreamSocket().send(DatagramPacket(data, data.size, upstreamAddr))
        } catch (e: Exception) {
        }
    }

    private fun stop(reason: String?) {
        if (!running) return
        running = false
        lastError = reason
        onStopped(reason)
    }

    override fun close() {
        running = false
        runCatching { session.close() }
        runCatching { gameSocket.close() }
        runCatching { upstream?.close() }
        thread?.join(1000)
        thread = null
    }

    private companion object {
        /** RakNet GUIDs are opaque session ids; distinct per leg. */
        const val DEVICE_GUID = 0x58494B45L // "XIKE"
        const val UPSTREAM_GUID = 0x58494B55L // "XIKU"
        const val SESSION_TIMEOUT_MS = 60_000L
        const val MAX_DATAGRAM = 65536
        const val TICK_IDLE_MS = 10L
        const val POLL_TIMEOUT_MS = 5
    }
}