package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.ClickSchedule
import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.EntityTable
import dev.xykell.client.runtime.relay.PlayerListTable
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
    /**
     * How a decoded roster change reaches the HUD.
     *
     * Injected rather than called directly: the production sink crosses JNI into
     * the native observation consumer, which a host JVM test has no way to load.
     * Tests pass a recorder and assert on what the relay observed, which is the
     * part that actually has logic in it.
     */
    val onPlayerListChange: (PlayerListTable.Change) -> Unit = {},
    /**
     * Monotonic millisecond clock, injected so tests can drive time exactly
     * and no module reaches for a global.
     *
     * `ctx.tick` counts what the relay has processed, which is not elapsed
     * time: a session that sits still still has packets arriving, and a busy
     * one does not tick in real time. Anything that must reason about *seconds*
     * (a session timer, a double-tap gap, a delayed release) needs this, and
     * `System.nanoTime` is the right source because it is monotonic on both
     * the phone and the host JVM, unlike a wall clock that can jump.
     */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** When this session started, on the same clock as [nowMs]. */
    var sessionStartMs: Long = clock()
        private set

    /** Monotonic milliseconds right now. */
    fun nowMs(): Long = clock()

    /** Milliseconds since this session started; never negative. */
    fun elapsedMs(): Long = (nowMs() - sessionStartMs).coerceAtLeast(0L)
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

    /** The online roster, decoded from clientbound PlayerList 0x3f. */
    val playerList = PlayerListTable()

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

    /**
     * When the relay last authored a chat packet, or null if it never has.
     *
     * Wall clock, not a tick: a chat cadence has to hold in real seconds while
     * the session is quiet, and `tick` would drift with packet rate.
     */
    var lastChatAtMs: Long? = null
        private set

    /** Record that a chat packet was authored at [nowMs]. */
    fun markChatSent(nowMs: Long) {
        lastChatAtMs = nowMs
    }

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
     * Decode a clientbound PlayerList entry and pass on whatever it changed.
     *
     * Clientbound only: the server is the only side that sends this, so an
     * outbound packet claiming to be one is not a roster entry and is ignored.
     */
    fun observePlayerList(direction: RelayDirection, packet: ByteArray) {
        if (direction != RelayDirection.TO_CLIENT) return
        val change = try {
            playerList.observe(packet)
        } catch (e: Exception) {
            null
        } ?: return
        onPlayerListChange(change)
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
        playerList.clear()
        settings.clear()
        hasGround = false
        tick = 0
        itemCooldownTicks = 0
        lastBiteTick = null
        lastChatAtMs = null
        sessionStartMs = clock()
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

    /**
     * How long a hold lasts, in milliseconds.
     *
     * A tap is a ~60 ms stroke; a long press on a hotbar slot is ~600 ms. The
     * distinction is real on a touch layout — dropping a stack is a long press,
     * not a tap — so the plan vocabulary carries it rather than pretending every
     * input is the same gesture.
     */
    const val HOLD_MS = 600L

    /** How long a double tap waits for its second tap. */
    const val DOUBLE_TAP_GAP_MS = 120L

    /** Bounds on a hold and on a double-tap gap, in milliseconds. */
    const val MIN_HOLD_MS = 50
    const val MAX_HOLD_MS = 5_000

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
     * One LONG PRESS at [key] — the gesture a touch layout uses for "drop this
     * stack", which a tap cannot express. Empty until the point is configured,
     * so nothing is ever pressed at a guessed location.
     */
    fun hold(ctx: ModuleContext, key: String): List<MacroStep> {
        val (nx, ny) = point(ctx, key) ?: return emptyList()
        val hold = ctx.int("holdMs", HOLD_MS.toInt()).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        return listOf(MacroStep(0L, nx, ny, hold.toLong()))
    }

    /**
     * Two taps at [key] — the touch-layout equivalent of a double click.
     *
     * Two ordinary steps, so the service replays them in order exactly as it
     * replays a recorded macro: one mechanism, no special case.
     */
    fun doubleTap(ctx: ModuleContext, random: Random, key: String): List<MacroStep> {
        val (nx, ny) = point(ctx, key) ?: return emptyList()
        val gap = ctx.int("doubleTapGapMs", DOUBLE_TAP_GAP_MS.toInt())
            .coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        val first = ClickSchedule.nextDelayMs(ctx.int("cps", DEFAULT_CPS), ctx.int("jitterPct", 0), random)
        return listOf(MacroStep(first, nx, ny), MacroStep(gap.toLong(), nx, ny))
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