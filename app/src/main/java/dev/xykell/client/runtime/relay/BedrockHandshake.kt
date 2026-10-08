package dev.xykell.client.runtime.relay

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

data class LoginInfo(
    val protocolVersion: Int,
    val identityJson: String,
    val clientToken: String,
)

data class HandshakeInfo(
    val salt: ByteArray,
    val x5u: String,
)

/**
 * Login handshake wire facts (bedrock protocol, latest minecraft-data):
 * request_network_settings 0xc1, network_settings 0x8f, login 0x01
 * (i32 BE protocol + varint blob of two li32 strings), s2c handshake 0x03
 * (varint string JWT, unencrypted), c2s handshake 0x04 (no fields,
 * first encrypted packet, counter 0).
 */
object BedrockHandshake {

    const val PACKET_LOGIN = 0x01
    const val PACKET_S2C_HANDSHAKE = 0x03
    const val PACKET_C2S_HANDSHAKE = 0x04
    const val PACKET_REQUEST_NETWORK_SETTINGS = 0xc1
    const val PACKET_NETWORK_SETTINGS = 0x8f

    fun parseLogin(packet: ByteArray): LoginInfo {
        if (packet.isEmpty() || (packet[0].toInt() and 0xff) != PACKET_LOGIN) {
            throw BedrockFormatException("not a login packet")
        }
        if (packet.size < 5) throw BedrockFormatException("login truncated")
        val protocol = ByteBuffer.wrap(packet, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        var off = 5
        val blobLen = readVarUInt(packet, off) ?: throw BedrockFormatException("login tokens length missing")
        off += varUIntSize(packet, off) ?: throw BedrockFormatException("login tokens length missing")
        if (blobLen <= 0 || off + blobLen > packet.size) throw BedrockFormatException("login tokens out of bounds")
        val blob = packet.copyOfRange(off, off + blobLen)
        var bo = 0
        val identity = readLittleString(blob, bo) ?: throw BedrockFormatException("login identity missing")
        bo = identity.second
        val client = readLittleString(blob, bo) ?: throw BedrockFormatException("login client token missing")
        return LoginInfo(protocol, identity.first, client.first)
    }

    /** Build a login packet (relay upstream leg). */
    fun buildLogin(protocolVersion: Int, identityJson: String, clientToken: String): ByteArray {
        val idBytes = identityJson.toByteArray(Charsets.UTF_8)
        val clientBytes = clientToken.toByteArray(Charsets.UTF_8)
        val blob = ByteArrayOutputStream()
        writeLi32(blob, idBytes.size); blob.write(idBytes)
        writeLi32(blob, clientBytes.size); blob.write(clientBytes)
        val out = ByteArrayOutputStream()
        out.write(PACKET_LOGIN)
        val proto = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protocolVersion).array()
        out.write(proto)
        val blobBytes = blob.toByteArray()
        out.write(varUInt(blobBytes.size))
        out.write(blobBytes)
        return out.toByteArray()
    }

    /**
     * Client ECDH public key (SPKI) from the login identity chain. Handles
     * both shapes: legacy {"chain":[...]} and 1.21.90+ {"Certificate":"<json>"}.
     */
    fun identityPublicKey(identityJson: String): ByteArray {
        val outer = JSONObject(identityJson)
        val chainJson = when {
            outer.has("Certificate") -> outer.getString("Certificate")
            outer.has("chain") -> identityJson
            else -> throw BedrockFormatException("login identity has no chain")
        }
        val chain = JSONObject(chainJson).getJSONArray("chain")
        if (chain.length() == 0) throw BedrockFormatException("empty identity chain")
        val leaf = chain.getString(chain.length() - 1)
        val payload = jwtPayloadJson(leaf) ?: throw BedrockFormatException("identity leaf not a JWT")
        val claim = JSONObject(payload).optString("identityPublicKey", "")
        if (claim.isNotEmpty()) return decodeKeyMaterial(claim)
        val header = jwtHeaderJson(leaf) ?: throw BedrockFormatException("identity leaf header missing")
        val x5u = JSONObject(header).optString("x5u", "")
        if (x5u.isEmpty()) throw BedrockFormatException("identity leaf has no key")
        return decodeKeyMaterial(x5u)
    }

    /** ES384-signed server handshake JWT toward the game (relay = server). */
    fun signServerHandshake(relayPriv: PrivateKey, relayPubSpki: ByteArray, salt: ByteArray): String {
        require(salt.size in 1..64) { "salt must be 1..64 bytes" }
        val header = JSONObject().put("alg", "ES384").put("typ", "JWT")
            .put("x5u", Base64.getUrlEncoder().withoutPadding().encodeToString(relayPubSpki)).toString()
        val payload = JSONObject().put("salt", Base64.getEncoder().encodeToString(salt)).toString()
        return signEs384(header, payload, relayPriv)
    }

    fun parseServerHandshake(token: String): HandshakeInfo {
        val header = jwtHeaderJson(token) ?: throw BedrockFormatException("handshake JWT header missing")
        val x5u = JSONObject(header).optString("x5u", "")
        if (x5u.isEmpty()) throw BedrockFormatException("handshake JWT has no x5u")
        val payload = jwtPayloadJson(token) ?: throw BedrockFormatException("handshake JWT payload missing")
        val saltB64 = JSONObject(payload).optString("salt", "")
        if (saltB64.isEmpty()) throw BedrockFormatException("handshake JWT has no salt")
        val salt = decodeSalt(saltB64) ?: throw BedrockFormatException("handshake salt invalid")
        return HandshakeInfo(salt, x5u)
    }

    fun buildNetworkSettings(): ByteArray {
        val w = ByteArrayOutputStream()
        w.write(PACKET_NETWORK_SETTINGS)
        writeLu16(w, 0)      // compression_threshold: 0 = never compress (device leg MVP)
        writeLu16(w, 0)      // compression_algorithm: deflate
        w.write(0)           // client_throttle off
        w.write(0)           // client_throttle_threshold
        w.write(ByteArray(4)) // client_throttle_scalar f32 0.0 LE
        return w.toByteArray()
    }

    fun buildRequestNetworkSettings(protocolVersion: Int): ByteArray {
        val w = ByteArrayOutputStream()
        w.write(PACKET_REQUEST_NETWORK_SETTINGS)
        w.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protocolVersion).array())
        return w.toByteArray()
    }

    /** c2s handshake has no fields; caller seals it as the counter-0 envelope. */
    fun buildClientToServerHandshake(): ByteArray = byteArrayOf(PACKET_C2S_HANDSHAKE.toByte())

    /**
     * Replace the client's identity leaf with one signed by the relay keypair
     * (see PROXY-DESIGN §6 key swap) and return the login to send upstream.
     * Non-leaf chain entries and both scalar fields are preserved verbatim, so
     * the server sees the same protocol version and client token.
     */
    fun rewriteLogin(
        login: LoginInfo,
        relayPubSpki: ByteArray,
        relayPriv: PrivateKey,
    ): LoginInfo {
        val outer = JSONObject(login.identityJson)
        val chainJson = when {
            outer.has("Certificate") -> outer.getString("Certificate")
            outer.has("chain") -> login.identityJson
            else -> throw BedrockFormatException("login identity has no chain")
        }
        val chain = JSONObject(chainJson).getJSONArray("chain")
        if (chain.length() == 0) throw BedrockFormatException("empty identity chain")
        val entries = ArrayList<String>(chain.length())
        for (i in 0 until chain.length() - 1) entries.add(chain.getString(i))
        val header = JSONObject().put("alg", "ES384").put("typ", "JWT")
            .put("x5u", b64Url(relayPubSpki)).toString()
        val payload = JSONObject()
            .put("identityPublicKey", Base64.getEncoder().encodeToString(relayPubSpki))
            .toString()
        entries.add(signEs384(header, payload, relayPriv))
        return LoginInfo(
            login.protocolVersion,
            JSONObject().put("chain", JSONArray(entries)).toString(),
            login.clientToken,
        )
    }

    /** varuint-length-prefixed UTF-8 string at [start], or null when malformed. */
    fun extractVarString(buf: ByteArray, start: Int): String? {
        val len = readVarUInt(buf, start) ?: return null
        val size = varUIntSize(buf, start) ?: return null
        val off = start + size
        if (len <= 0 || off + len > buf.size) return null
        return String(buf, off, len, Charsets.UTF_8)
    }

    fun signEs384(headerJson: String, payloadJson: String, priv: PrivateKey): String {
        val h = b64Url(headerJson.toByteArray(Charsets.UTF_8))
        val p = b64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(priv)
        signer.update("$h.$p".toByteArray(Charsets.US_ASCII))
        val der = signer.sign()
        val jose = derToJose(der, 48)
        return "$h.$p.${b64Url(jose)}"
    }

    // --- helpers ---

    private fun readLittleString(buf: ByteArray, start: Int): Pair<String, Int>? {
        if (start + 4 > buf.size) return null
        val len = ByteBuffer.wrap(buf, start, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || start + 4 + len > buf.size) return null
        return String(buf, start + 4, len, Charsets.UTF_8) to (start + 4 + len)
    }

    private fun writeLi32(out: ByteArrayOutputStream, v: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write(b)
    }

    private fun writeLu16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v ushr 8) and 0xff)
    }

    fun varUInt(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x80.inv() == 0) {
                out.write(x)
                return out.toByteArray()
            }
            out.write((x and 0x7f) or 0x80)
            x = x ushr 7
        }
    }

    fun readVarUInt(buf: ByteArray, start: Int): Int? {
        var result = 0
        var shift = 0
        var i = start
        while (i < buf.size && shift < 28) {
            val b = buf[i].toInt() and 0xff
            result = result or ((b and 0x7f) shl shift)
            i++
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    fun varUIntSize(buf: ByteArray, start: Int): Int? {
        var i = start
        while (i < buf.size) {
            if (buf[i].toInt() and 0x80 == 0) return i - start + 1
            i++
        }
        return null
    }

    fun b64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun decodeKeyMaterial(value: String): ByteArray {
        return try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(value)
        }
    }

    fun decodeSalt(value: String): ByteArray? {
        val raw = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(value)
            } catch (e2: IllegalArgumentException) {
                return null
            }
        }
        return if (raw.isEmpty() || raw.size > 64) null else raw
    }

    fun jwtHeaderJson(jwt: String): String? = jwtPart(jwt, 0)
    fun jwtPayloadJson(jwt: String): String? = jwtPart(jwt, 1)

    private fun jwtPart(jwt: String, index: Int): String? {
        val parts = jwt.split(".")
        if (parts.size <= index) return null
        return try {
            String(Base64.getUrlDecoder().decode(parts[index]), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** JWS signatures are raw r||s; JCA emits DER. Convert for P-384 (48-byte halves). */
    private fun derToJose(der: ByteArray, half: Int): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) idx = 2 + (der[1].toInt() and 0x7f)
        if (der[idx] != 0x02.toByte()) throw BedrockFormatException("bad ECDSA DER: r tag")
        val rLen = der[idx + 1].toInt() and 0xff
        val r = der.copyOfRange(idx + 2, idx + 2 + rLen)
        val sTag = idx + 2 + rLen
        if (der[sTag] != 0x02.toByte()) throw BedrockFormatException("bad ECDSA DER: s tag")
        val sLen = der[sTag + 1].toInt() and 0xff
        val s = der.copyOfRange(sTag + 2, sTag + 2 + sLen)
        val out = ByteArray(half * 2)
        writeRightAligned(r, out, 0, half)
        writeRightAligned(s, out, half, half)
        return out
    }

    private fun writeRightAligned(src: ByteArray, dst: ByteArray, dstOff: Int, width: Int) {
        var start = 0
        while (start < src.size - width && src[start] == 0.toByte()) start++
        val len = src.size - start
        if (len > width) throw BedrockFormatException("ECDSA component larger than field")
        System.arraycopy(src, start, dst, dstOff + width - len, len)
    }
}
