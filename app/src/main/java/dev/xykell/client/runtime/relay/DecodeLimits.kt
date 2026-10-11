package dev.xykell.client.runtime.relay

/**
 * Explicit, bounded decode limits for every untrusted length on the relay path.
 *
 * **Why this file exists.** Bedrock length-prefixed fields -- list lengths, byte
 * array lengths, string lengths, NBT sizes -- are read from a peer. A decoder
 * that trusts them will preallocate whatever the peer claims. That is the
 * cheapest denial of service there is: no malformed-packet handling to defeat,
 * no anomaly to detect, just a length header and a phone with a few gigabytes of
 * heap.
 *
 * The failure mode is quiet enough to be worth stating plainly: setting a decode
 * limit to "whatever fits in memory" does not look like an omission in review,
 * it looks like a decision. Anyone reading it has to already know that the bound
 * was deliberately removed, and they will not, because nothing in the code says
 * so. So here the bounds are named, given defensible values, and explained. If
 * one needs to change, its comment says what is being traded.
 *
 * **Where the values come from.** They are sized for a phone. They are *not*
 * claimed to be the exact limits a real server uses -- Bedrock's encoding
 * settings are negotiated per connection and this relay does not implement that
 * negotiation. What they are is the ceiling *we* honour regardless of what was
 * negotiated, so a peer cannot raise our limit by asking. That is a different
 * thing from the protocol's own limits, and conflating the two is how a decoder
 * ends up trusting a number it should have treated as hostile.
 *
 * Every limit is a ceiling on *allocation*, not on validity: a real value above
 * the cap is refused, never truncated into a wrong answer. Silently dropping
 * list entries is worse than refusing the packet.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
object DecodeLimits {

    /** Max elements in a decoded list. Mobile entity/block lists are far below. */
    const val MAX_LIST_SIZE = 65_536

    /** Max bytes in a single decoded byte array (chunk payloads, skin data). */
    const val MAX_BYTE_ARRAY_BYTES = 4 * 1024 * 1024

    /** Max bytes of NBT in one network field. A full chunk block-entity set. */
    const val MAX_NETWORK_NBT_BYTES = 2 * 1024 * 1024

    /** Max bytes of NBT inside a single item stack. */
    const val MAX_ITEM_NBT_BYTES = 64 * 1024

    /** Max characters in a decoded string, after decoding. */
    const val MAX_STRING_CHARS = 8_192

    /**
     * Max total bytes decoded for one packet, across every field. This is the
     * one that actually bounds the attack: a peer can send many legal-looking
     * fields each under its own per-field cap, and only a per-packet ceiling
     * stops the sum.
     */
    const val MAX_PACKET_BYTES = 16 * 1024 * 1024

    /** Max container nesting (NBT compounds/lists, text-component trees). */
    const val MAX_DEPTH = 32

    /**
     * Max bytes one compressed batch may inflate to.
     *
     * Separate from [MAX_PACKET_BYTES] on purpose: a batch is a *container* of
     * packets, so it decompresses to more than one packet's worth. It is also
     * the number a hostile upstream attacks first, since compression is exactly
     * how a small payload becomes a large allocation.
     *
     * This value used to live only in `BedrockBatch.MAX_INFLATED` while the
     * diagnostics screen showed the [MAX_PACKET_BYTES] figure instead — so the
     * app reported a policy the wire path was not enforcing. It is owned here
     * so that what is displayed is what is applied.
     */
    const val MAX_INFLATED_BATCH_BYTES = 10 * 1024 * 1024

    /** The policy applied to relay packets. Every field explicitly bounded. */
    val DEFAULT = Limits(
        maxListSize = MAX_LIST_SIZE,
        maxByteArrayBytes = MAX_BYTE_ARRAY_BYTES,
        maxNetworkNbtBytes = MAX_NETWORK_NBT_BYTES,
        maxItemNbtBytes = MAX_ITEM_NBT_BYTES,
        maxStringChars = MAX_STRING_CHARS,
        maxPacketBytes = MAX_PACKET_BYTES,
        maxDepth = MAX_DEPTH,
        maxInflatedBatchBytes = MAX_INFLATED_BATCH_BYTES,
    )

    /**
     * A resolved limit set. Constructed through [of], which validates, so an
     * out-of-range limit cannot reach a decoder by accident.
     */
    class Limits internal constructor(
        val maxListSize: Int,
        val maxByteArrayBytes: Int,
        val maxNetworkNbtBytes: Int,
        val maxItemNbtBytes: Int,
        val maxStringChars: Int,
        val maxPacketBytes: Int,
        val maxDepth: Int,
        /** See [MAX_INFLATED_BATCH_BYTES]. */
        val maxInflatedBatchBytes: Int = MAX_INFLATED_BATCH_BYTES,
    ) {
        init {
            require(maxListSize > 0 && maxByteArrayBytes > 0 && maxNetworkNbtBytes > 0) {
                "decode limits must be positive"
            }
            require(maxItemNbtBytes > 0 && maxStringChars > 0) {
                "decode limits must be positive"
            }
            require(maxPacketBytes > 0 && maxDepth > 0) { "decode limits must be positive" }
            require(maxInflatedBatchBytes > 0) { "decode limits must be positive" }
            require(maxPacketBytes >= maxByteArrayBytes) {
                "per-packet ceiling below per-field ceiling is unreachable policy"
            }
        }

        /** One-line summary for the relay screen. Contains no user data. */
        fun describe(): String =
            "list<=$maxListSize bytes<=$maxByteArrayBytes nbt<=$maxNetworkNbtBytes " +
                "itemNbt<=$maxItemNbtBytes str<=$maxStringChars " +
                "packet<=$maxPacketBytes depth<=$maxDepth batch<=$maxInflatedBatchBytes"
    }

    /** Builds a validated [Limits]. Throws on an incoherent set, by design. */
    fun of(
        maxListSize: Int = MAX_LIST_SIZE,
        maxByteArrayBytes: Int = MAX_BYTE_ARRAY_BYTES,
        maxNetworkNbtBytes: Int = MAX_NETWORK_NBT_BYTES,
        maxItemNbtBytes: Int = MAX_ITEM_NBT_BYTES,
        maxStringChars: Int = MAX_STRING_CHARS,
        maxPacketBytes: Int = MAX_PACKET_BYTES,
        maxDepth: Int = MAX_DEPTH,
        maxInflatedBatchBytes: Int = MAX_INFLATED_BATCH_BYTES,
    ): Limits = Limits(
        maxListSize, maxByteArrayBytes, maxNetworkNbtBytes,
        maxItemNbtBytes, maxStringChars, maxPacketBytes, maxDepth,
        maxInflatedBatchBytes,
    )

    /** Why a decode was refused. Distinct causes are distinct bugs. */
    enum class Refusal {
        LIST_TOO_LARGE,
        BYTE_ARRAY_TOO_LARGE,
        NBT_TOO_LARGE,
        ITEM_NBT_TOO_LARGE,
        STRING_TOO_LARGE,
        PACKET_TOO_LARGE,
        DEPTH_EXCEEDED,
    }

    /** Either a decoded value or the refusal that replaced it. */
    sealed class Checked<out T> {
        data class Ok<T>(val value: T) : Checked<T>()
        data class Refused(val reason: Refusal, val claimed: Long) : Checked<Nothing>()

        val valueOrNull: T? get() = (this as? Ok)?.value
        val refusalOrNull: Refusal? get() = (this as? Refused)?.reason
        val ok: Boolean get() = this is Ok
    }

    fun checkListSize(size: Int, limits: Limits = DEFAULT): Checked<Int> =
        if (size > limits.maxListSize) {
            Checked.Refused(Refusal.LIST_TOO_LARGE, size.toLong())
        } else {
            Checked.Ok(size)
        }

    fun checkByteArray(size: Int, limits: Limits = DEFAULT): Checked<Int> =
        if (size > limits.maxByteArrayBytes) {
            Checked.Refused(Refusal.BYTE_ARRAY_TOO_LARGE, size.toLong())
        } else {
            Checked.Ok(size)
        }

    fun checkNetworkNbt(size: Int, limits: Limits = DEFAULT): Checked<Int> =
        if (size > limits.maxNetworkNbtBytes) {
            Checked.Refused(Refusal.NBT_TOO_LARGE, size.toLong())
        } else {
            Checked.Ok(size)
        }

    fun checkItemNbt(size: Int, limits: Limits = DEFAULT): Checked<Int> =
        if (size > limits.maxItemNbtBytes) {
            Checked.Refused(Refusal.ITEM_NBT_TOO_LARGE, size.toLong())
        } else {
            Checked.Ok(size)
        }

    fun checkStringLength(chars: Int, limits: Limits = DEFAULT): Checked<Int> =
        if (chars > limits.maxStringChars) {
            Checked.Refused(Refusal.STRING_TOO_LARGE, chars.toLong())
        } else {
            Checked.Ok(chars)
        }

    fun checkDepth(depth: Int, limits: Limits = DEFAULT): Checked<Int> =
        if (depth > limits.maxDepth) {
            Checked.Refused(Refusal.DEPTH_EXCEEDED, depth.toLong())
        } else {
            Checked.Ok(depth)
        }

    /**
     * Per-packet byte budget. A decoder charges every field it reads; once the
     * total would exceed the ceiling the budget is spent, so no later field can
     * resurrect the packet.
     */
    class Budget(val limits: Limits = DEFAULT) {
        private var spentBytes = 0L

        val consumed: Long get() = spentBytes
        val exhausted: Boolean get() = spentBytes > limits.maxPacketBytes

        /** Reserves [bytes]; false once the packet ceiling is reached. */
        fun charge(bytes: Long): Boolean {
            if (bytes < 0 || exhausted) return false
            spentBytes += bytes
            return !exhausted
        }

        fun reset() {
            spentBytes = 0
        }

        /** Charges [bytes], or reports the refusal when the ceiling is hit. */
        fun chargeChecked(bytes: Long): Checked<Long> =
            if (charge(bytes)) Checked.Ok(bytes)
            else Checked.Refused(Refusal.PACKET_TOO_LARGE, spentBytes)
    }
}