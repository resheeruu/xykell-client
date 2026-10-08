package dev.xykell.client.runtime.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * Transparent RakNet UDP relay: one socket bound on loopback, forwards raw
 * datagrams between the game client and one upstream server.
 *
 * Single socket for both legs so every reply leaves from the port the peer
 * sent to (client sent to listenPort, server replies to listenPort). Demux
 * by source address: first non-upstream sender becomes the client.
 *
 * Byte-transparent: datagram seq spaces of client and server align through
 * the pipe, so ACK/NACK/retransmit/split/ordering need no rewriting.
 * Observation and injection (re-wrap with our own indices) come later.
 */
class RelayPipe(
    upstreamHost: String,
    upstreamPort: Int,
    listenPort: Int = 0,
    private val observer: ((data: ByteArray, fromClient: Boolean) -> Unit)? = null,
) : AutoCloseable {

    private val socket = DatagramSocket(listenPort, InetAddress.getLoopbackAddress())
    private val upstreamAddr = InetSocketAddress(InetAddress.getByName(upstreamHost), upstreamPort)

    val port: Int get() = socket.localPort

    @Volatile
    private var clientAddr: SocketAddress? = null

    @Volatile
    private var running = true

    private var thread: Thread? = null

    fun start() {
        check(thread == null) { "already started" }
        thread = Thread(::loop, "relay-pipe").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        val buf = ByteArray(65536)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: java.net.SocketException) {
                if (running) throw IllegalStateException("relay socket error", e)
                break
            }
            val fromUpstream = pkt.address == upstreamAddr.address && pkt.port == upstreamAddr.port
            val data = pkt.data.copyOf(pkt.length)
            // Observer must never kill the relay thread (one guard covers
            // every observer; forwarding continues even if it throws).
            try {
                observer?.invoke(data, !fromUpstream)
            } catch (e: Exception) {
                // Swallowed deliberately: observation is best-effort.
            }
            if (fromUpstream) {
                clientAddr?.let { send(data, it) }
            } else {
                if (clientAddr == null) {
                    clientAddr = InetSocketAddress(pkt.address, pkt.port)
                }
                send(data, upstreamAddr)
            }
        }
    }

    private fun send(data: ByteArray, dest: SocketAddress) {
        try {
            socket.send(DatagramPacket(data, data.size, dest))
        } catch (e: java.net.SocketException) {
            if (running) throw IllegalStateException("relay send failed", e)
        }
    }

    override fun close() {
        running = false
        socket.close()
        thread?.join(1000)
        thread = null
    }
}
