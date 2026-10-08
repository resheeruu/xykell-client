package dev.xykell.client.runtime.relay

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BedrockFormatException(message: String) : IllegalArgumentException(message)

/**
 * Key derivation for the Bedrock login handshake — pure Kotlin/JCE.
 * key = SHA256(salt || ECDH(localPriv, remotePub)), EC P-384.
 * Matches CloudburstMC/Protocol EncryptionUtils.getSecretKey (Apache-2.0,
 * observed wire facts) and PrismarineJS bedrock-protocol keyExchange.js (MIT).
 */
object BedrockCrypto {
    fun ecdhShared(privPkcs8: ByteArray, pubSpki: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("EC")
        val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privPkcs8))
        val pub = factory.generatePublic(X509EncodedKeySpec(pubSpki))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(priv)
        agreement.doPhase(pub, true)
        return agreement.generateSecret()
    }

    fun deriveKey(salt: ByteArray, sharedSecret: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(sharedSecret)
        return digest.digest()
    }
}

/**
 * Session cipher for one direction of a Bedrock connection (protocol >= 428):
 * AES-256-CTR, IV = key[0..11] || 00 00 00 02, with a SHA-256 checksum
 * trailer over LE64(counter) || payload || key, counter++ per batch.
 * Observed from CloudburstMC/Protocol EncryptionUtils.createCipher +
 * BedrockEncryptionEncoder (Apache-2.0) — clean-room reimplementation.
 *
 * ponytail: JCE CTR advances the whole 128-bit block while GCM (the wire
 * equivalent) advances only the low 32 bits — the two diverge after 2^32
 * cipher blocks (64 GiB) in one session. Per-block wrap matters only then.
 */
class BedrockCipher(key: ByteArray) {
    private val keyBytes: ByteArray = key.copyOf()
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        val iv = ByteArray(16)
        System.arraycopy(key, 0, iv, 0, 12)
        iv[15] = 2
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }
    private var counter = 0L

    /** Append checksum then encrypt. */
    fun seal(payload: ByteArray): ByteArray {
        val trailer = checksum(payload, counter++)
        val plain = payload + trailer
        return cipher.update(plain) ?: throw BedrockFormatException("cipher update failed")
    }

    /** Decrypt then verify checksum. Returns the payload without trailer. */
    fun open(payload: ByteArray): ByteArray {
        if (payload.size < 8) throw BedrockFormatException("encrypted batch too short")
        val plain = cipher.update(payload) ?: throw BedrockFormatException("cipher update failed")
        val body = plain.copyOfRange(0, plain.size - 8)
        val trailer = plain.copyOfRange(plain.size - 8, plain.size)
        val expected = checksum(body, counter++)
        if (!trailer.contentEquals(expected)) throw BedrockFormatException("checksum mismatch")
        return body
    }

    private fun checksum(payload: ByteArray, counterValue: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (shift in 0 until 8) digest.update(((counterValue ushr (8 * shift)) and 0xff).toByte())
        digest.update(payload)
        digest.update(keyBytes)
        return digest.digest().copyOf(8)
    }
}

/**
 * Bedrock batch envelope: `0xFE || cipher(compressorByte || deflateRaw(batch))`,
 * batch = repeated varuint(len) || packet. Frame id lives outside the cipher;
 * compressor byte (0x00 deflate / 0x01 snappy / 0xFF none) lives inside.
 * Wire facts from CloudburstMC/Protocol batch codecs (Apache-2.0), clean-room.
 */
object BedrockBatch {
    const val FRAME_ID = 0xFE
    const val COMPRESSOR_DEFLATE = 0x00
    const val COMPRESSOR_SNAPPY = 0x01
    const val COMPRESSOR_NONE = 0xFF
    const val MAX_INFLATED = 10 * 1024 * 1024

    fun isBatchFrame(frame: ByteArray): Boolean =
        frame.isNotEmpty() && (frame[0].toInt() and 0xff) == FRAME_ID

    fun buildBatch(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (packet in packets) {
            writeVarUInt(out, packet.size)
            out.write(packet)
        }
        return out.toByteArray()
    }

    fun splitBatch(batch: ByteArray): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var i = 0
        while (i < batch.size) {
            var value = 0
            var shift = 0
            var lengthBytes = 0
            while (true) {
                if (i >= batch.size) throw BedrockFormatException("truncated varuint in batch")
                val b = batch[i].toInt() and 0xff
                i++
                lengthBytes++
                value = value or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28 || lengthBytes > 5) throw BedrockFormatException("varuint too long")
            }
            if (value < 0 || value > MAX_INFLATED || i + value > batch.size) {
                throw BedrockFormatException("batch packet length out of range: $value")
            }
            packets.add(batch.copyOfRange(i, i + value))
            i += value
        }
        return packets
    }

    fun deflate(src: ByteArray): ByteArray {
        // Level 7 = Cloudburst ZlibCompression default (parity with reference vectors).
        val deflater = Deflater(7, true)
        return try {
            deflater.setInput(src)
            deflater.finish()
            val out = ByteArrayOutputStream(src.size / 2 + 16)
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun inflate(src: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(src)
            val out = ByteArrayOutputStream(src.size * 4 + 64)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput()) throw BedrockFormatException("truncated deflate stream")
                    if (inflater.needsDictionary()) throw BedrockFormatException("deflate dictionary unsupported")
                    break
                }
                if (out.size() + n > MAX_INFLATED) throw BedrockFormatException("batch inflates past $MAX_INFLATED")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw BedrockFormatException("bad deflate stream: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    /**
     * Snappy-raw decode, clean-room from the published wire format:
     * varint uncompressed-length preamble, then tags — 00 literal (upper 6
     * bits n: n<60 -> len n+1, else len = 1 + (n-59) LE bytes), 01 copy with
     * 1-byte offset (len 4+((tag>>2)&7)), 10 copy 2-byte offset (len
     * (tag>>2)+1), 11 copy 4-byte offset. Every read is bounds-checked and
     * the total must match the preamble exactly; overlapping back-references
     * copy byte-by-byte forward as the format requires.
     */
    fun snappyUncompress(src: ByteArray): ByteArray {
        var i = 0
        var expected = 0
        var shift = 0
        while (true) {
            if (i >= src.size) throw BedrockFormatException("snappy: truncated preamble")
            val b = src[i].toInt() and 0xff
            i++
            expected = expected or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw BedrockFormatException("snappy: preamble varint too long")
        }
        if (expected < 0 || expected > MAX_INFLATED) {
            throw BedrockFormatException("snappy: declared length out of range")
        }
        val out = ByteArray(expected)
        var o = 0
        while (i < src.size) {
            val tag = src[i].toInt() and 0xff
            i++
            when (tag and 0x03) {
                0 -> { // literal
                    var len: Int
                    val n = tag shr 2
                    if (n < 60) {
                        len = n + 1
                    } else {
                        val extra = n - 59 // 1..4 LE bytes
                        if (i + extra > src.size) {
                            throw BedrockFormatException("snappy: truncated literal length")
                        }
                        var base = 1
                        for (k in 0 until extra) {
                            base += (src[i + k].toInt() and 0xff) shl (8 * k)
                        }
                        i += extra
                        len = base
                    }
                    if (o + len > expected) {
                        throw BedrockFormatException("snappy: literal past declared length")
                    }
                    if (i + len > src.size) {
                        throw BedrockFormatException("snappy: truncated literal")
                    }
                    System.arraycopy(src, i, out, o, len)
                    i += len
                    o += len
                }
                1 -> { // copy, 1-byte offset, len 4..11
                    if (i >= src.size) throw BedrockFormatException("snappy: truncated copy")
                    val len = 4 + ((tag shr 2) and 0x07)
                    val off = ((tag and 0xe0) shl 3) or (src[i].toInt() and 0xff)
                    i++
                    snappyCopy(out, off, o, len, expected)
                    o += len
                }
                2 -> { // copy, 2-byte offset, len 1..64
                    if (i + 2 > src.size) throw BedrockFormatException("snappy: truncated copy")
                    val len = (tag shr 2) + 1
                    val off = (src[i].toInt() and 0xff) or
                        ((src[i + 1].toInt() and 0xff) shl 8)
                    i += 2
                    snappyCopy(out, off, o, len, expected)
                    o += len
                }
                else -> { // copy, 4-byte offset, len 1..64
                    if (i + 4 > src.size) throw BedrockFormatException("snappy: truncated copy")
                    val len = (tag shr 2) + 1
                    var off = 0
                    for (k in 0 until 4) {
                        off = off or ((src[i + k].toInt() and 0xff) shl (8 * k))
                    }
                    i += 4
                    snappyCopy(out, off, o, len, expected)
                    o += len
                }
            }
        }
        if (o != expected) {
            throw BedrockFormatException(
                "snappy: length mismatch (decoded $o, preamble $expected)"
            )
        }
        return out
    }

    /**
     * Snappy-raw encode. Literal-only framing: every snappy decoder accepts
     * it, the format imposes no ratio requirement, and outgoing frames are
     * rare control traffic (the handshake pins deflate for data anyway).
     */
    fun snappyCompress(src: ByteArray): ByteArray {
        if (src.size > MAX_INFLATED) throw BedrockFormatException("snappy: input too large")
        val out = ByteArrayOutputStream(src.size + src.size / 60 + 8)
        var v = src.size
        while (v >= 0x80) {
            out.write(v and 0x7f or 0x80)
            v = v ushr 7
        }
        out.write(v)
        var i = 0
        while (i < src.size) {
            val len = minOf(60, src.size - i)
            out.write(((len - 1) shl 2) or 0x00) // literal, n = len-1 < 60
            out.write(src, i, len)
            i += len
        }
        return out.toByteArray()
    }

    /** Back-reference copy; forward byte order supports overlapping windows. */
    private fun snappyCopy(out: ByteArray, off: Int, o: Int, len: Int, expected: Int) {
        if (off <= 0 || off > o) {
            throw BedrockFormatException("snappy: invalid back-reference offset $off")
        }
        if (o + len > expected) {
            throw BedrockFormatException("snappy: copy past declared length")
        }
        var k = 0
        while (k < len) {
            out[o + k] = out[o - off + k]
            k++
        }
    }

    /** Full outgoing frame: 0xFE + encrypted(compressor byte + deflate + checksum). */
    fun buildFrame(
        packets: List<ByteArray>,
        cipher: BedrockCipher,
        compressor: Int = COMPRESSOR_DEFLATE,
    ): ByteArray {
        val batch = buildBatch(packets)
        val body = when (compressor) {
            COMPRESSOR_DEFLATE -> deflate(batch)
            COMPRESSOR_NONE -> batch
            COMPRESSOR_SNAPPY -> snappyCompress(batch)
            else -> throw BedrockFormatException("unknown compressor: $compressor")
        }
        val payload = byteArrayOf(compressor.toByte()) + body
        return byteArrayOf(FRAME_ID.toByte()) + cipher.seal(payload)
    }

    /** Parse a full incoming frame back to packets. */
    fun openFrame(frame: ByteArray, cipher: BedrockCipher): List<ByteArray> {
        if (!isBatchFrame(frame)) throw BedrockFormatException("missing batch frame id 0xFE")
        val payload = cipher.open(frame.copyOfRange(1, frame.size))
        if (payload.isEmpty()) throw BedrockFormatException("empty batch payload")
        val compressor = payload[0].toInt() and 0xff
        val body = payload.copyOfRange(1, payload.size)
        return when (compressor) {
            COMPRESSOR_DEFLATE -> splitBatch(inflate(body))
            COMPRESSOR_NONE -> splitBatch(body)
            COMPRESSOR_SNAPPY -> splitBatch(snappyUncompress(body))
            else -> throw BedrockFormatException("unknown compressor: $compressor")
        }
    }

    /**
     * Every batch shape a pre-encryption frame body can take: raw batch, a
     * compressor-byte prefixed batch, or a whole-body deflate stream. Returns
     * the shapes that parse, in that order; callers pick the candidate whose
     * packets decode (the reference framer behaves the same way). Mirrors the
     * reference framer fallback so the relay never has to guess one shape.
     */
    fun plainCandidates(body: ByteArray): List<List<ByteArray>> {
        val out = ArrayList<List<ByteArray>>(3)
        tryParse(out) { splitBatch(body) }
        if (body.isNotEmpty()) {
            when (body[0].toInt() and 0xff) {
                COMPRESSOR_NONE ->
                    tryParse(out) { splitBatch(body.copyOfRange(1, body.size)) }
                COMPRESSOR_DEFLATE ->
                    tryParse(out) { splitBatch(inflate(body.copyOfRange(1, body.size))) }
                COMPRESSOR_SNAPPY ->
                    tryParse(out) { splitBatch(snappyUncompress(body.copyOfRange(1, body.size))) }
            }
        }
        tryParse(out) { splitBatch(inflate(body)) }
        return out
    }

    private inline fun tryParse(out: MutableList<List<ByteArray>>, block: () -> List<ByteArray>) {
        try {
            out.add(block())
        } catch (e: RuntimeException) {
            // candidate shape did not parse; try the next one
        }
    }

    private fun writeVarUInt(out: ByteArrayOutputStream, value: Int) {
        require(value >= 0) { "varuint negative: $value" }
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
    }
}
