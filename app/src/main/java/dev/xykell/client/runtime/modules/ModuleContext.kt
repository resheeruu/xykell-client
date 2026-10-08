package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.ClickSchedule
import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.EntityTable
import dev.xykell.client.runtime.relay.RelayDirection
import kotlin.random.Random

/**
 * Per-session state handed to every module.
 *
 * This exists because an earlier design forced each module to be a stateless
 * `ByteArray -> ByteArray`, and that constraint — not the protocol — is what
 * made ~30 ids look impossible. Modules like no_fall need the last known
 * on-ground height, bunny_hop needs the last on-ground flag, speed needs the
 * previous position, and auras need the entity table. None of that is
 * reachable from one packet's own bytes.
 *
 * The context is owned by the session and dies with it: no cross-session
 * state, no globals, nothing that survives a disconnect. [settings] is a plain
 * map so a module never has to know how the UI persists its toggle.
 */
class ModuleContext(
    val entities: EntityTable = EntityTable(),
    val settings: MutableMap<String, String> = HashMap(),
) {
    /** The local player's last position as reported by its own outbound packets. */
    var selfX: Float = 0f
        private set
    var selfY: Float = 0f
        private set
    var selfZ: Float = 0f
        private set
    var selfRuntimeId: Long = Long.MIN_VALUE
        private set
    var selfOnGround: Boolean = false
        private set

    /** The last known on-ground height, for the fall-distance modules. */
    var lastGroundY: Float = 0f
        private set
    private var hasGround = false

    var tick: Long = 0
        private set

    /**
     * Duration of the last item cooldown the client itself reported, in ticks;
     * 0 until one has been seen.
     *
     * ClientStartItemCooldown 0xb0 is the client telling the server how long it
     * just locked an item. That is the only honest source for how fast a repeat
     * use may be sent: the value is the client's own, not the relay's guess.
     */
    var itemCooldownTicks: Int = 0
        private set

    /** Tick of the last fishing bobber bite the server announced; null if none. */
    var lastBiteTick: Long? = null
        private set

    fun recordItemCooldown(ticks: Int) {
        if (ticks > 0) itemCooldownTicks = ticks
    }

    fun recordBite(tick: Long) {
        lastBiteTick = tick
    }

    fun onTick() {
        tick++
        entities.onTick()
    }

    /**
     * Record the local player's own reported position. Only ever called with
     * the client's outbound MovePlayer — trusting a server-supplied position
     * for "where am I" would let a server place the player anywhere, which is
     * how a distance check gets defeated.
     */
    fun updateSelf(runtimeId: Long, x: Float, y: Float, z: Float, onGround: Boolean) {
        selfRuntimeId = runtimeId
        selfX = x
        selfY = y
        selfZ = z
        selfOnGround = onGround
        if (onGround) {
            lastGroundY = y
            hasGround = true
        }
    }

    fun hasGround(): Boolean = hasGround

    /** Drop the tracked state; call on disconnect so a new session starts clean. */
    fun reset() {
        entities.clear()
        settings.clear()
        hasGround = false
        tick = 0
        itemCooldownTicks = 0
        lastBiteTick = null
    }

    fun flag(name: String, fallback: Boolean = false): Boolean = when (settings[name]?.lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> fallback
    }

    fun number(name: String, fallback: Float): Float =
        settings[name]?.toFloatOrNull() ?: fallback

    fun int(name: String, fallback: Int): Int =
        settings[name]?.toIntOrNull() ?: fallback
}

/**
 * A module that may hold state across packets within one session.
 *
 * The stateless `transform(id, direction, packet)` entry point on each category
 * object still works and is still the fast path; this is for the ids that need
 * more than one packet to decide.
 */
interface StatefulModule {
    /** Called once per tick with the session context. */
    fun onTick(ctx: ModuleContext) {}

    /** Reset on disconnect. */
    fun reset() {}
}

/** Direction-aware helper so modules do not each re-derive leg semantics. */
object Legs {
    /** True when the packet is travelling toward the upstream server. */
    fun isToServer(direction: RelayDirection): Boolean =
        direction == RelayDirection.TO_SERVER

    /** True when the packet is travelling toward the game. */
    fun isToClient(direction: RelayDirection): Boolean =
        direction == RelayDirection.TO_CLIENT
}

/**
 * Scheduled input: the one honest way this client drives the game.
 *
 * For an id whose whole job is "press this, on this cadence" the relay has
 * nothing to rewrite — the game is the one sending every packet, and forging a
 * UseItem or Interact would be both a protocol forgery and a ban magnet. So
 * those ids answer a different question: *given the session so far, where should
 * a finger go this tick?* The answer is a list of [MacroStep]s, which is exactly
 * what `TouchAutomationService` already replays through the Android gesture API
 * — the same surface a human finger uses.
 *
 * A plan is pure: session context plus a seeded [Random], no clock, no Android,
 * no sockets. Empty means "nothing is due", which is the answer on most ticks.
 * Nothing here invents a game packet, and nothing defaults a tap point: a plan
 * with no configured point taps nothing rather than guessing at the screen.
 */
object TapPlan {

    /** Cadence keys shared with the existing click/replay modes of the service. */
    const val DEFAULT_CPS = 10

    /** The normalised point in setting [key] as `"nx,ny"`, or null when unset. */
    fun point(ctx: ModuleContext, key: String): Pair<Double, Double>? {
        val raw = ctx.settings[key] ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val nx = parts[0].trim().toDoubleOrNull() ?: return null
        val ny = parts[1].trim().toDoubleOrNull() ?: return null
        if (nx.isNaN() || ny.isNaN()) return null
        return nx.coerceIn(0.0, 1.0) to ny.coerceIn(0.0, 1.0)
    }

    /** One tap at the point named by [key]; empty when [key] is unset. */
    fun once(ctx: ModuleContext, random: Random, key: String): List<MacroStep> {
        val (nx, ny) = point(ctx, key) ?: return emptyList()
        return listOf(
            MacroStep(
                ClickSchedule.nextDelayMs(ctx.int("cps", DEFAULT_CPS), ctx.int("jitterPct", 0), random),
                nx,
                ny,
            ),
        )
    }

    /**
     * [once], but only on the ticks where `tick % intervalTicks == 0`, and never
     * at all when [intervalTicks] is not positive. The tick is a counter, not a
     * clock: it counts what the relay has processed, which is all a pure
     * function may look at.
     */
    fun everyTicks(
        ctx: ModuleContext,
        random: Random,
        key: String,
        intervalTicks: Long,
    ): List<MacroStep> {
        if (intervalTicks <= 0L) return emptyList()
        if (ctx.tick % intervalTicks != 0L) return emptyList()
        return once(ctx, random, key)
    }
}