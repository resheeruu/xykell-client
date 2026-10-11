package dev.xykell.client.runtime.relay

/**
 * Protocol-version diagnostics for the relay's two legs.
 *
 * **What this deliberately does not do.** It does not claim that a given Bedrock
 * protocol number is supported. This repo vendors one protocol schema
 * (`scripts/protocol/vendor/bedrock-1.26.45-proto.yml`, upstream minecraft-data,
 * MIT) and derives [BedrockPacketIds] from it. That schema is version-tagged
 * with a *game* version, not a protocol number, and we do not ship an
 * authoritative game-version -> protocol-number mapping to check it against. So
 * any table here claiming "protocol N maps to codec M" would be a guess wearing
 * a constant's clothing, and a module filtered on a guessed id fails silently
 * -- the exact failure mode the generator in `scripts/protocol/` exists to
 * prevent. Unknown stays unknown.
 *
 * **What it does do**, all of it sourced from what the relay actually observes:
 *
 *  1. [legComparison] -- the device leg and the upstream leg negotiate
 *     independently, so they can end up on different protocol numbers. That is
 *     a real, observable divergence and a real bug: every packet the device
 *     sends is then encoded for a version the upstream is not speaking. It is
 *     worth naming loudly.
 *  2. [sanity] -- range checks that catch a truncated or misparsed varint, which
 *     is how a bad read presents. A sanity failure is "this number is not
 *     trustworthy", not "this version is unsupported".
 *  3. [summary] -- one line for the relay screen and for crash/observation
 *     context. No secrets: protocol numbers are public wire facts.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
object CodecDiagnostics {

    /**
     * The game version of the vendored protocol schema. A provenance fact from
     * the repo, not a compatibility claim: it says which schema the packet-id
     * table came from, nothing about which builds the relay can serve.
     */
    const val VENDORED_SCHEMA_VERSION = "1.26.45"

    /** Source of that fact, for anyone auditing the claim. */
    val VENDORED_SCHEMA_SOURCE: String =
        "scripts/protocol/vendor/bedrock-$VENDORED_SCHEMA_VERSION-proto.yml"

    /** Bedrock protocol numbers are well inside this band in practice. */
    const val PLAUSIBLE_MIN = 1
    const val PLAUSIBLE_MAX = 100_000

    /** Which leg a protocol version was observed on. */
    enum class Leg { DEVICE, UPSTREAM }

    /** Result of a sanity check. Never claims compatibility. */
    enum class Verdict {
        /** Read as a plausible protocol number. Says nothing about support. */
        PLAUSIBLE,

        /** Zero or negative: a misread varint or a truncated packet. */
        NOT_A_VERSION,

        /** Far outside any observed Bedrock range: a misread, not a new build. */
        IMPLAUSIBLE,
    }

    data class Observation(val leg: Leg, val protocolVersion: Int?)

    /**
     * Compares what each leg negotiated.
     *
     * Returns null when there is nothing to compare (one leg has not reported
     * yet), which is the normal case before the upstream login completes.
     */
    fun legComparison(
        device: Int?,
        upstream: Int?,
    ): ComparisonResult? {
        if (device == null || upstream == null) return null
        val result = ComparisonResult(
            device = device,
            upstream = upstream,
            agree = device == upstream,
            deviceVerdict = sanity(device),
            upstreamVerdict = sanity(upstream),
        )
        // Agreement on two impossible numbers is still not agreement; report the
        // mismatch so the screen cannot render "matched" for garbage.
        return if (result.deviceVerdict == Verdict.PLAUSIBLE &&
            result.upstreamVerdict == Verdict.PLAUSIBLE
        ) {
            result
        } else {
            result.copy(agree = false, reason = "a leg reported an implausible protocol number")
        }
    }

    data class ComparisonResult(
        val device: Int,
        val upstream: Int,
        val agree: Boolean,
        val deviceVerdict: Verdict,
        val upstreamVerdict: Verdict,
        val reason: String? = null,
    )

    /**
     * Range check only. A [Verdict.PLAUSIBLE] result means "this number is
     * shaped like a protocol number", never "this version is supported".
     */
    fun sanity(protocolVersion: Int): Verdict = when {
        protocolVersion <= 0 -> Verdict.NOT_A_VERSION
        protocolVersion > PLAUSIBLE_MAX -> Verdict.IMPLAUSIBLE
        else -> Verdict.PLAUSIBLE
    }

    /**
     * One line for the relay screen. Reports only what was observed, and says
     * "unknown" rather than omitting a leg, so a missing value is visible
     * instead of looking like agreement.
     */
    fun summary(
        device: Int?,
        upstream: Int?,
        tablePackets: Int = BedrockPacketIds.allNames.size,
    ): String {
        fun leg(v: Int?): String = when (v) {
            null -> "unknown"
            else -> when (sanity(v)) {
                Verdict.PLAUSIBLE -> v.toString()
                Verdict.NOT_A_VERSION -> "$v (invalid)"
                Verdict.IMPLAUSIBLE -> "$v (implausible)"
            }
        }
        val verdict = legComparison(device, upstream)
        val state = when {
            verdict == null -> "device only"
            verdict.agree -> "matched"
            else -> "MISMATCH"
        }
        return "protocol device=${leg(device)} upstream=${leg(upstream)} [$state]; " +
            "table $tablePackets packets from schema $VENDORED_SCHEMA_VERSION"
    }
}