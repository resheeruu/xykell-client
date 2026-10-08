package dev.xykell.client.runtime.observation

import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loopback JSON-line feed server (app -> game, Phase F). Broadcasts each
 * published line to every connected client and remembers the last line per
 * type key so a late joiner (the game process reconnecting) immediately
 * receives current state.
 *
 * Thread model: registration, replay, store, and broadcast all run under
 * one lock, so a client can never miss a line (accepted-before-publish gets
 * the broadcast; publish-before-accept gets the replay) and never gets a
 * duplicate. Loopback only, no auth — same trust model as the 8765
 * observation server. Send failures drop the client, never throw.
 */
class ObservationFeedServer(private val port: Int = PROD_PORT) : Closeable {

    private val lock = Any()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val lastByType = LinkedHashMap<String, String>()
    private var server: ServerSocket? = null

    @Volatile
    private var closed = false

    val isOpen: Boolean get() = !closed

    /** Ephemeral port the server actually bound (-1 before start()). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        synchronized(lock) {
            if (server != null || closed) return
            val s = ServerSocket(port, 16, InetAddress.getLoopbackAddress())
            server = s
            Thread({ acceptLoop(s) }, "xykell-feed-accept")
                .apply { isDaemon = true; start() }
        }
    }

    /** Store and broadcast one wire line; [type] keys the replay entry. */
    fun publish(type: String, line: String) {
        synchronized(lock) {
            if (closed) return
            lastByType[type] = line
            broadcastLocked(line)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                server?.close()
            } catch (_: Exception) {
            }
            clients.forEach { c -> try { c.close() } catch (_: Exception) {} }
            clients.clear()
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!closed) {
            val client = try {
                s.accept()
            } catch (_: Exception) {
                return
            }
            synchronized(lock) {
                if (closed) {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                client.tcpNoDelay = true
                clients.add(client)
                // Replay under the same lock as publish: exactly-once delivery.
                lastByType.values.forEach { line ->
                    if (!sendLocked(client, line)) {
                        clients.remove(client)
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                        return@synchronized
                    }
                }
            }
        }
    }

    private fun broadcastLocked(line: String) {
        val it = clients.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (!sendLocked(c, line)) {
                clients.remove(c)
                try {
                    c.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendLocked(c: Socket, line: String): Boolean = try {
        val out = BufferedOutputStream(c.getOutputStream())
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val PROD_PORT = 8790
    }
}
