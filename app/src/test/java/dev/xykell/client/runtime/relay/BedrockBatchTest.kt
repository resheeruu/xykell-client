package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockBatchTest {

    // Reference vectors generated with Node (bedrock-protocol semantics):
    // P-384 ECDH + SHA256(salt||shared) + raw deflate level 7 + AES-CTR
    // (IV key[0..11]||00000002) + SHA256 checksum trailer.
    private val clientPrivPkcs8 = hex(
            "3081b6020100301006072a8648ce3d020106052b8104002204819e30819b02010104308c91ef" +
            "f49987ec437d7126e2aba315dc32ce9a143a402e2a415b783d3a5b9742830bb55ea74d598ecf" +
            "62a729da0046c8a164036200040886de457de5733d94b4f7666fbd8825979bb19865d94ee205" +
            "dd65644a30fad0f019e5785dcf5581d2fdee0728ee3775ae691393c25c8f06ead594e869424f" +
            "a715600175374bfd92fb076195d549a55658b2464047e9993b6cfd53aab301b9d3"
        )
    private val serverPubSpki = hex(
            "3076301006072a8648ce3d020106052b81040022036200045e601a0ea9210a121fbb2e49e3a7" +
            "8872fa244fa2355d9b676db723da940719da2bd687968bdfe39ca88aa16e992153ecae4f836d" +
            "069392003a648d41b66aaf66e9c80681c73e8455ded1ae62ed9bde5e68570a7b62661bbce180" +
            "e0d019fceb68"
        )
    private val salt = hex("00112233445566778899aabbccddeeff")
    private val expectedKey = hex("ab2d3ec5bbdd048f8cc299b2b8728cde5d3ba199cbbb548a542050ef2474b405")
    private val expectedShared = hex(
            "8b495ab491b86175ea4b5360cc7fc62d059a4879f120456b290549b55bc3e86d3ce1243d4730" +
            "5cf4a30f64d31d17d7f1"
        )

    private val packet1 = hex("01007f")
    private val packet2 = byteArrayOf(0x0a) + ByteArray(299) { 0xab.toByte() }
    private val batchPlain = hex("0301007fac02") + packet2
    private val envelopeCounter0 = hex("38f9c8f3cebfae1de9ec2d2fcd3b9c7721dde9a4b7ca")
    private val envelopeCounter1 = hex("ffd0f3b92066b312ce2a792f8a95402cbfe6ba18886b")

    @Test
    fun `ecdh and key derivation match reference vectors`() {
        val shared = BedrockCrypto.ecdhShared(clientPrivPkcs8, serverPubSpki)
        assertEquals(expectedShared.toHex(), shared.toHex())
        val key = BedrockCrypto.deriveKey(salt, shared)
        assertEquals(expectedKey.toHex(), key.toHex())
    }

    @Test
    fun `split and build batch match reference framing`() {
        val packets = BedrockBatch.splitBatch(batchPlain)
        assertEquals(2, packets.size)
        assertEquals(packet1.toHex(), packets[0].toHex())
        assertEquals(packet2.toHex(), packets[1].toHex())
        assertEquals(batchPlain.toHex(), BedrockBatch.buildBatch(packets).toHex())
    }

    @Test
    fun `opens reference envelopes at counter 0 and 1`() {
        val cipher = BedrockCipher(expectedKey)
        val plain0 = BedrockBatch.openFrame(
            byteArrayOf(BedrockBatch.FRAME_ID.toByte()) + envelopeCounter0,
            cipher,
        )
        assertEquals(2, plain0.size)
        assertEquals(packet1.toHex(), plain0[0].toHex())
        assertEquals(packet2.toHex(), plain0[1].toHex())

        val plain1 = BedrockBatch.openFrame(
            byteArrayOf(BedrockBatch.FRAME_ID.toByte()) + envelopeCounter1,
            cipher,
        )
        assertEquals(2, plain1.size)
        assertEquals(packet1.toHex(), plain1[0].toHex())
    }

    @Test
    fun `buildFrame reproduces reference envelope byte for byte`() {
        val frame = BedrockBatch.buildFrame(listOf(packet1, packet2), BedrockCipher(expectedKey))
        val expected = byteArrayOf(BedrockBatch.FRAME_ID.toByte()) + envelopeCounter0
        assertEquals(expected.toHex(), frame.toHex())
    }

    @Test
    fun `round trip through build then open preserves packets`() {
        val cipherA = BedrockCipher(expectedKey)
        val cipherB = BedrockCipher(expectedKey)
        val frame = BedrockBatch.buildFrame(listOf(packet1, packet2), cipherA)
        val packets = BedrockBatch.openFrame(frame, cipherB)
        assertEquals(2, packets.size)
        assertEquals(packet1.toHex(), packets[0].toHex())
        assertEquals(packet2.toHex(), packets[1].toHex())
    }

    @Test
    fun `tampered ciphertext fails checksum`() {
        val frame = BedrockBatch.buildFrame(listOf(packet1), BedrockCipher(expectedKey))
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x01).toByte()
        try {
            BedrockBatch.openFrame(frame, BedrockCipher(expectedKey))
            throw AssertionError("expected checksum failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }

    @Test
    fun `snappy frame round trip preserves packets`() {
        val cipherA = BedrockCipher(expectedKey)
        val cipherB = BedrockCipher(expectedKey)
        val frame = BedrockBatch.buildFrame(
            listOf(packet1, packet2), cipherA, BedrockBatch.COMPRESSOR_SNAPPY
        )
        val packets = BedrockBatch.openFrame(frame, cipherB)
        assertEquals(2, packets.size)
        assertEquals(packet1.toHex(), packets[0].toHex())
        assertEquals(packet2.toHex(), packets[1].toHex())
    }

    @Test
    fun `snappy decodes handcrafted literal vector`() {
        // preamble 6 | literal len 6 (n=5 -> tag 0x14) | "hello!"
        val stream = byteArrayOf(0x06, 0x14) + "hello!".toByteArray()
        assertEquals("hello!", String(BedrockBatch.snappyUncompress(stream)))
    }

    @Test
    fun `snappy decodes one byte offset back reference`() {
        // preamble 7 | literal "abc" (tag 0x08) | copy1 len 4 offset 3 (0x01 0x03)
        val stream = byteArrayOf(0x07, 0x08, 0x61, 0x62, 0x63, 0x01, 0x03)
        assertEquals("abcabca", String(BedrockBatch.snappyUncompress(stream)))
    }

    @Test
    fun `snappy encoder output decodes back to input`() {
        val src = ByteArray(200) { (it * 7 + 3).toByte() }
        val decoded = BedrockBatch.snappyUncompress(BedrockBatch.snappyCompress(src))
        assertEquals(src.toHex(), decoded.toHex())
    }

    @Test
    fun `snappy rejects invalid back reference offset`() {
        // preamble 1 | literal "a" (tag 0x00) | copy1 len 4 offset 9
        val stream = byteArrayOf(0x01, 0x00, 0x61, 0x01, 0x09)
        try {
            BedrockBatch.snappyUncompress(stream)
            throw AssertionError("expected offset failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("offset"))
        }
    }

    @Test
    fun `snappy rejects declared length mismatch`() {
        // preamble declares 4 but the literal only delivers 1
        val stream = byteArrayOf(0x04, 0x00, 0x61)
        try {
            BedrockBatch.snappyUncompress(stream)
            throw AssertionError("expected mismatch failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("mismatch"))
        }
    }

    @Test
    fun `snappy rejects truncated stream`() {
        try {
            BedrockBatch.snappyUncompress(byteArrayOf()) // missing preamble
            throw AssertionError("expected truncation failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("snappy"))
        }
        try {
            BedrockBatch.snappyUncompress(byteArrayOf(0x06, 0x14, 0x68, 0x65)) // literal cut off
            throw AssertionError("expected truncation failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    @Test
    fun `missing frame id rejected`() {
        assertFalse(BedrockBatch.isBatchFrame(envelopeCounter0))
        try {
            BedrockBatch.openFrame(envelopeCounter0, BedrockCipher(expectedKey))
            throw AssertionError("expected frame id failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("0xFE"))
        }
    }

    @Test
    fun `inflate guards against truncated stream`() {
        val truncated = BedrockBatch.deflate(batchPlain).copyOfRange(0, 3)
        try {
            BedrockBatch.inflate(truncated)
            throw AssertionError("expected truncation failure")
        } catch (e: BedrockFormatException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    private fun hex(s: String): ByteArray {
        val clean = s.filter { it.isLetterOrDigit() }
        require(clean.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
