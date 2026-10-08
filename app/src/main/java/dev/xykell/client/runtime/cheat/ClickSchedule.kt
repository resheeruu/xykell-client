package dev.xykell.client.runtime.cheat

import kotlin.random.Random

/**
 * One recorded gesture: delay since the previous one, normalized (0..1) point,
 * and how long the stroke itself lasts.
 *
 * [holdMs] defaults to 0, which the service reads as a normal tap. It is an
 * explicit field rather than an overload of [delayMs] because "wait 600 ms,
 * then tap" and "press for 600 ms" are different instructions, and a signed
 * delay would make them the same number.
 */
data class MacroStep(
    val delayMs: Long,
    val nx: Double,
    val ny: Double,
    val holdMs: Long = 0L,
)

/**
 * Click cadence for the accessibility autoclicker. Pure logic: host tests
 * drive it with a seeded Random; the service only reads the result.
 */
object ClickSchedule {
    const val MIN_CPS = 1
    const val MAX_CPS = 20
    const val MAX_JITTER_PCT = 50
    const val MIN_DELAY_MS = 16L

    fun nextDelayMs(cps: Int, jitterPct: Int, random: Random = Random.Default): Long {
        val base = 1000.0 / cps.coerceIn(MIN_CPS, MAX_CPS)
        val jitter = jitterPct.coerceIn(0, MAX_JITTER_PCT)
        if (jitter == 0) return base.toLong().coerceAtLeast(MIN_DELAY_MS)
        val offset = random.nextDouble(-jitter / 100.0, jitter / 100.0)
        return (base * (1.0 + offset)).toLong().coerceAtLeast(MIN_DELAY_MS)
    }
}

/** Persistence-safe tap macro: cap, clamp, encode/decode, screen mapping. */
object MacroStore {
    const val MAX_STEPS = 200
    const val MAX_STEP_DELAY_MS = 10_000L
    const val MAX_HOLD_MS = 5_000L

    fun append(steps: List<MacroStep>, step: MacroStep): List<MacroStep> {
        if (steps.size >= MAX_STEPS) return steps
        return steps + MacroStep(
            step.delayMs.coerceIn(0L, MAX_STEP_DELAY_MS),
            step.nx.coerceIn(0.0, 1.0),
            step.ny.coerceIn(0.0, 1.0),
            step.holdMs.coerceIn(0L, MAX_HOLD_MS),
        )
    }

    /** Four fields, the last being the hold; a 3-field step decodes as a tap. */
    fun encode(steps: List<MacroStep>): String =
        steps.joinToString(";") { "${it.delayMs},${it.nx},${it.ny},${it.holdMs}" }

    fun decode(raw: String?): List<MacroStep> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<MacroStep>()
        for (entry in raw.split(';')) {
            if (out.size >= MAX_STEPS) break
            val parts = entry.split(',')
            if (parts.size < 3) continue
            val delay = parts[0].toLongOrNull() ?: continue
            val nx = parts[1].toDoubleOrNull() ?: continue
            val ny = parts[2].toDoubleOrNull() ?: continue
            if (nx.isNaN() || ny.isNaN()) continue
            // A 3-field step predates holds and replays as a plain tap.
            val hold = if (parts.size >= 4) parts[3].toLongOrNull() ?: continue else 0L
            out.add(
                MacroStep(
                    delay.coerceIn(0L, MAX_STEP_DELAY_MS),
                    nx.coerceIn(0.0, 1.0),
                    ny.coerceIn(0.0, 1.0),
                    hold.coerceIn(0L, MAX_HOLD_MS),
                ),
            )
        }
        return out
    }

    fun scaleToScreen(nx: Double, ny: Double, width: Int, height: Int): Pair<Float, Float> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return (nx.coerceIn(0.0, 1.0) * w).toFloat() to (ny.coerceIn(0.0, 1.0) * h).toFloat()
    }
}
