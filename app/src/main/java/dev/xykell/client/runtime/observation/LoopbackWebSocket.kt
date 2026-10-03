package dev.xykell.client.runtime.observation

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Narrowly scoped loopback observation transport (Stage 20, Phase 1).
 *
 * Responsibilities ONLY: connect to the fixed endpoint, offer the
 * documented wsencrypt subprotocol, send the ONE documented
 * enableencryption handshake, receive key material, derive the frozen
 * session, decrypt inbound frames, parse plaintext JSON, translate via
 * ObservationTranslator, and hand Translated items to LiveProducer.
 *
 * It must NOT execute commands, send anything other than the handshake
 * and the two proven subscribes, inject/rewrite packets, touch auth or
 * credentials, or contact any other endpoint. No reconnect: any close or
 * failure ends the session and reports the reason once.
 */
class LoopbackWebSocket(
    private val events: Events,
    private val clientFactory: () -> OkHttpClient = { defaultClient() },
) {

    interface Events {
        fun onHandshakeSent()
        fun onEstablished()
        fun onTranslated(item: Translated)
        fun onDroppedMalformed()
        fun onFailed(reason: String)
        fun onClosed()
    }

    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var crypto: SessionCrypto? = null
    private var handshakeRequestId = ""
    private var failed = false

    fun start() {
        val request = try {
            buildRequest()
        } catch (e: IllegalArgumentException) {
            fail("endpoint-rejected: ${e.message}")
            return
        }
        val c = clientFactory()
        client = c
        socket = c.newWebSocket(request, Listener())
    }

    fun close() {
        try {
            socket?.close(1000, "stop")
        } catch (_: Exception) {
        }
        try {
            socket?.cancel()
        } catch (_: Exception) {
        }
        socket = null
        shutdownClient()
    }

    private fun shutdownClient() {
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {
        }
        try {
            client?.connectionPool?.evictAll()
        } catch (_: Exception) {
        }
        client = null
    }

    private fun fail(reason: String) {
        if (failed) return
        failed = true
        try {
            socket?.cancel()
        } catch (_: Exception) {
        }
        socket = null
        shutdownClient()
        events.onFailed(reason)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            try {
                val pair = ObservationCrypto.generateEphemeralKeyPair()
                val salt = ObservationCrypto.randomSalt()
                val cmd = ObservationCrypto.enableEncryptionCommand(
                    ObservationCrypto.spkiDer(pair), salt,
                )
                handshakeRequestId = HANDSHAKE_REQUEST_ID
                ws.send(frame(cmd, handshakeRequestId))
                crypto = SessionCrypto(pair, salt)
                events.onHandshakeSent()
            } catch (e: Exception) {
                fail("handshake-failed")
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleInbound(text.toByteArray(Charsets.UTF_8), isBinary = false, ws)
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            handleInbound(bytes.toByteArray(), isBinary = true, ws)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            events.onClosed()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            // onFailure also fires after our own cancel(); report once.
            if (!failed) events.onClosed()
        }
    }

    private fun handleInbound(data: ByteArray, isBinary: Boolean, ws: WebSocket) {
        val session = crypto
        if (session == null || !session.established) {
            handlePreSession(data, isBinary)
            return
        }
        val plain = session.decrypt(data)
        val text = try {
            plain.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            events.onDroppedMalformed()
            return
        }
        val envelope = try {
            JSONObject(text)
        } catch (e: Exception) {
            events.onDroppedMalformed() // non-JSON plaintext (e.g. U1 class)
            return
        }
        val item = ObservationTranslator.translate(
            envelope, session.nextId(), nowMs(), data.size,
        ) ?: run {
            events.onDroppedMalformed()
            return
        }
        events.onTranslated(item)
    }

    private fun handlePreSession(data: ByteArray, isBinary: Boolean) {
        if (isBinary) return // pre-establishment binary (U0 class): not gameplay
        val envelope = try {
            JSONObject(data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val session = crypto ?: return
        val header = envelope.optJSONObject("header") ?: return
        if (header.optString("requestId", "") != handshakeRequestId) return
        val keyB64 = envelope.optJSONObject("body")?.optString("publicKey", "") ?: ""
        if (keyB64.isEmpty()) return // error frames carry no key: stay awaiting
        try {
            session.establish(ObservationCrypto.b64d(keyB64))
            events.onEstablished()
            sendSubscribes()
        } catch (e: Exception) {
            fail("establish-failed")
        }
    }

    private fun sendSubscribes() {
        val ws = socket ?: run {
            fail("socket-gone")
            return
        }
        val session = crypto ?: return
        for (event in SUBSCRIBED_EVENTS) {
            val frame = subscribeFrame(event)
            val wire = session.encrypt(frame.toByteArray(Charsets.UTF_8))
            if (!ws.send(wire.toByteString())) {
                fail("subscribe-send-failed")
                return
            }
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        /** Fixed production endpoint. No setting, preference, or override. */
        const val ENDPOINT = "ws://127.0.0.1:8765"
        const val SUBPROTOCOL = "com.microsoft.minecraft.wsencrypt"
        const val HANDSHAKE_REQUEST_ID = "hs-xykell-1"
        val SUBSCRIBED_EVENTS = listOf("PlayerMessage", "PlayerTravelled")

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        /** Fails immediately unless the target is exactly 127.0.0.1:8765. */
        fun buildRequest(url: String = ENDPOINT): Request {
            require(url == ENDPOINT) { "only $ENDPOINT is permitted" }
            val http = url.replaceFirst("ws://", "http://").toHttpUrl()
            require(http.host == "127.0.0.1") { "host must be 127.0.0.1" }
            require(http.port == 8765) { "port must be 8765" }
            require(http.scheme == "http") { "plaintext loopback only" }
            return Request.Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
                .build()
        }

        fun frame(commandLine: String, requestId: String): String =
            JSONObject()
                .put("header", JSONObject()
                    .put("version", 1)
                    .put("requestId", requestId)
                    .put("messageType", "commandRequest")
                    .put("messagePurpose", "commandRequest"))
                .put("body", JSONObject()
                    .put("version", 1)
                    .put("commandLine", commandLine))
                .toString()

        fun subscribeFrame(eventName: String): String {
            require(eventName == "PlayerMessage" || eventName == "PlayerTravelled") {
                "only the two proven subscribes"
            }
            return JSONObject()
                .put("header", JSONObject()
                    .put("version", 1)
                    .put("requestId", UUID.randomUUID().toString())
                    .put("messageType", "commandRequest")
                    .put("messagePurpose", "subscribe"))
                .put("body", JSONObject().put("eventName", eventName))
                .toString()
        }
    }

    /** Frozen per-session crypto state (mirrors the proven lab session). */
    private class SessionCrypto(pair: java.security.KeyPair, salt: ByteArray) {
        private val privateKey = pair.private
        private val salt = salt.copyOf()
        private var encipher: ObservationCrypto.Cfb8Stream? = null
        private var decipher: ObservationCrypto.Cfb8Stream? = null
        private var seq = 0L
        var established = false
            private set

        fun establish(peerDer: ByteArray) {
            if (established) return // exactly-once (lab guard semantics)
            val peer = ObservationCrypto.importPeerSpki(peerDer)
            val secret = ObservationCrypto.agreeX384(privateKey, peer)
            val key = ObservationCrypto.deriveKey(salt, secret)
            encipher = ObservationCrypto.Cfb8Stream(key, decrypt = false)
            decipher = ObservationCrypto.Cfb8Stream(key, decrypt = true)
            established = true
        }

        fun encrypt(bytes: ByteArray): ByteArray =
            (encipher ?: throw IllegalStateException("not established")).update(bytes)

        fun decrypt(bytes: ByteArray): ByteArray =
            (decipher ?: throw IllegalStateException("not established")).update(bytes)

        fun nextId(): String = "xykell-live-${seq++}"
    }
}
