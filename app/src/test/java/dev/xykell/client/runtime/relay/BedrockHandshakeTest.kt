package dev.xykell.client.runtime.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class BedrockHandshakeTest {

    private fun keypair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp384r1"))
    }.generateKeyPair()

    private fun b64url(data: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun leafJwt(pub: ByteArray, priv: java.security.PrivateKey): String {
        val header = """{"x5u":"${b64url(pub)}"}"""
        val payload = """{"identityPublicKey":"${Base64.getEncoder().encodeToString(pub)}"}"""
        return BedrockHandshake.signEs384(header, payload, priv)
    }

    @Test
    fun `network settings packet bytes`() {
        assertArrayEquals(byteArrayOf(0x8f.toByte()) + ByteArray(10), BedrockHandshake.buildNetworkSettings())
        assertArrayEquals(
            byteArrayOf(0xc1.toByte(), 0x00, 0x00, 0x03, 0x08),
            BedrockHandshake.buildRequestNetworkSettings(776)
        )
    }

    @Test
    fun `login packet roundtrip`() {
        val kp = keypair()
        val identity = """{"chain":["${leafJwt(kp.public.encoded, kp.private)}"]}"""
        val packet = BedrockHandshake.buildLogin(776, identity, "client-token-jwt")
        val parsed = BedrockHandshake.parseLogin(packet)
        assertEquals(776, parsed.protocolVersion)
        assertEquals(identity, parsed.identityJson)
        assertEquals("client-token-jwt", parsed.clientToken)
    }

    @Test
    fun `identity key extraction handles legacy and certificate shapes`() {
        val kp = keypair()
        val pub = kp.public.encoded
        val leaf = leafJwt(pub, kp.private)
        val legacy = """{"chain":["$leaf"]}"""
        assertArrayEquals(pub, BedrockHandshake.identityPublicKey(legacy))

        val inner = """{"chain":["$leaf"]}"""
        val certShape =
            """{"Certificate":${org.json.JSONObject.quote(inner)},"AuthenticationType":2}"""
        assertArrayEquals(pub, BedrockHandshake.identityPublicKey(certShape))
    }

    @Test
    fun `server handshake sign and parse roundtrip with verifiable signature`() {
        val relay = keypair()
        val salt = ByteArray(16) { it.toByte() }
        val jwt = BedrockHandshake.signServerHandshake(relay.private, relay.public.encoded, salt)

        val info = BedrockHandshake.parseServerHandshake(jwt)
        assertArrayEquals(salt, info.salt)
        assertArrayEquals(relay.public.encoded, BedrockHandshake.decodeKeyMaterial(info.x5u))

        // ES384 signature over "b64(header).b64(payload)" must verify.
        val parts = jwt.split(".")
        val der = joseToDer(Base64.getUrlDecoder().decode(parts[2]), 48)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(relay.public)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        assertTrue(verifier.verify(der))
    }

    @Test
    fun `device leg both sides derive the same key`() {
        val game = keypair()
        val relay = keypair()
        val salt = ByteArray(16) { (0xa0 + it).toByte() }

        val sharedGame = BedrockCrypto.ecdhShared(game.private.encoded, relay.public.encoded)
        val sharedRelay = BedrockCrypto.ecdhShared(relay.private.encoded, game.public.encoded)
        assertArrayEquals(sharedGame, sharedRelay)
        assertArrayEquals(
            BedrockCrypto.deriveKey(salt, sharedGame),
            BedrockCrypto.deriveKey(salt, sharedRelay)
        )
    }

    @Test
    fun `c2s handshake packet is just its id`() {
        assertArrayEquals(byteArrayOf(0x04), BedrockHandshake.buildClientToServerHandshake())
    }

    @Test
    fun `rewriteLogin swaps the identity leaf for the relay key`() {
        val game = keypair()
        val relay = keypair()
        val login = BedrockHandshake.parseLogin(
            BedrockHandshake.buildLogin(776, """{"chain":["${leafJwt(game.public.encoded, game.private)}"]}""", "tok")
        )

        val rewritten = BedrockHandshake.rewriteLogin(login, relay.public.encoded, relay.private)
        assertEquals(776, rewritten.protocolVersion)
        assertEquals("tok", rewritten.clientToken)
        assertArrayEquals(
            relay.public.encoded,
            BedrockHandshake.identityPublicKey(rewritten.identityJson),
        )
        // The relay leaf must verify under the relay key, not the game's.
        val parts = rewritten.identityJson.split("\"")[3].split(".")
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(relay.public)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        assertTrue(verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2]), 48)))
    }

    /** Inverse of BedrockHandshake.derToJose: raw r||s back to DER for JCA verify. */
    private fun joseToDer(jose: ByteArray, half: Int): ByteArray {
        val r = jose.copyOfRange(0, half)
        val s = jose.copyOfRange(half, half * 2)
        val rDer = derInt(r)
        val sDer = derInt(s)
        val body = rDer + sDer
        val out = ArrayList<Byte>()
        out.add(0x30)
        if (body.size >= 128) {
            out.add(0x81.toByte())
            out.add(body.size.toByte())
        } else {
            out.add(body.size.toByte())
        }
        out.addAll(body.toList())
        return out.toByteArray()
    }

    private fun derInt(raw: ByteArray): ByteArray {
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        var value = raw.copyOfRange(start, raw.size)
        if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
        val out = ArrayList<Byte>()
        out.add(0x02)
        out.add(value.size.toByte())
        out.addAll(value.toList())
        return out.toByteArray()
    }
}
