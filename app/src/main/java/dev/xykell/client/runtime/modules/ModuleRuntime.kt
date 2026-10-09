package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.PlayerListTable
import dev.xykell.client.runtime.relay.RelayDirection
import dev.xykell.client.runtime.relay.RelayListener
import kotlin.random.Random

/**
 * The one seam that makes every module reachable: a [RelayListener] that owns
 * the session [ModuleContext], applies each enabled module's transform to every
 * packet, and answers the tap plans.
 *
 * Without it the category objects are dead code. Each `XxxModules.transform`
 * exists and is host-tested, but nothing called them at runtime:
 * `RelaySession` was constructed with `RelayListener.PASS`, and nothing filled
 * `ModuleContext.settings`, so every id answered from its default. This object
 * is the only place that decides which ids are on.
 *
 * Design constraints, all deliberate:
 *
 *  - **Order is fixed, not set order.** Modules run in a declared sequence
 *    ([ORDER]) so a rewrite is reproducible; `LinkedHashSet` iteration of a
 *    caller's map would make the result depend on insertion order.
 *  - **Enabled ids are a predicate, not a set copy.** The UI writes flags into
 *    the active profile behind a JNI bridge, so the owner re-reads on every
 *    packet instead of caching a snapshot that could go stale mid-session.
 *  - **A transform that drops a packet stops the chain.** An empty list stays
 *    empty; running later modules over nothing would be a no-op with extra cost.
 *  - **A module that throws cannot kill the relay.** Forwarding beats dying:
 *    the session's own contract is that a listener failure must not take the
 *    connection down.
 *
 * Settings are copied into the context once, because the context is per-session
 * and the UI has no reason to mutate them mid-packet.
 */
class ModuleRuntime(
    private val isEnabled: (String) -> Boolean,
    settings: Map<String, String> = emptyMap(),
    private val random: Random = Random.Default,
    /**
     * Where a decoded roster change goes. Defaults to the real observation sink
     * so the relay feeds the HUD without every caller wiring it; host tests pass
     * their own recorder.
     */
    private val onPlayerListChange: (PlayerListTable.Change) -> Unit = {},
) : RelayListener {

    /** Per-session state; dies with the session via [reset]. */
    val ctx = ModuleContext(
        settings = settings.toMutableMap(),
        onPlayerListChange = onPlayerListChange,
    )

    /** Ids this runtime will consider, in application order. */
    private val candidates: List<String> = ORDER.filter { it in ALL_IMPLEMENTED }

    override fun transform(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        // Feed the session's entity table FIRST, before any module reads it.
        // Without this the table is never populated and every derived view (esp,
        // tracers, nametag, block_tracers) silently sees zero entities — the
        // modules would be correct and useless. Self is excluded so the player's
        // own id never counts as a target.
        learnSelf(direction, packet)
        ctx.observePlayerList(direction, packet)
        try {
            ctx.entities.observe(packet, ctx.selfRuntimeId)
        } catch (e: Exception) {
            // A packet this table cannot parse is not an error; forwarding is
            // unaffected either way.
        }
        var out: List<ByteArray> = listOf(packet)
        for (id in candidates) {
            if (out.isEmpty()) return emptyList()
            if (!isEnabled(id)) continue
            val next = try {
                dispatch(id, direction, out, ctx)
            } catch (e: Exception) {
                // A module that cannot parse its packet forwards rather than
                // taking the session down; never silently swallow into a drop.
                out
            }
            if (next.isEmpty()) return emptyList()
            out = next
        }
        return out
    }

    /**
     * Advance the session one tick and return every due tap, in [ORDER].
     *
     * Empty on most ticks, which is the normal answer. The caller replays the
     * steps through the accessibility service — the same surface a finger uses.
     * Plan modules are a subset of the transforms: their packet arms (the item
     * cooldown, the bite announcement) already ran in [transform], so this only
     * has to read the state they recorded.
     */
    fun onTick(): List<MacroStep> {
        ctx.onTick()
        val out = ArrayList<MacroStep>()
        for (id in candidates) {
            if (id !in PLANNED) continue
            if (!isEnabled(id)) continue
            try {
                out.addAll(plan(id, ctx, random))
            } catch (e: Exception) {
                // One plan throwing must not silence the others on this tick.
            }
        }
        return out
    }

    /** Drop all tracked state; call on disconnect so the next session is clean. */
    fun reset() {
        ctx.reset()
    }

    /**
     * Learn the player's own reported position, always, not only while a
     * movement module happens to be enabled.
     *
     * This was a real ordering bug: the position was recorded *inside* the
     * movement transforms, so with every module off the context never learned
     * the self id — which then made the entity table count the player as a
     * target, and left `speed` permanently unprimed. Session bookkeeping
     * belongs here, once, where every packet passes.
     */
    private fun learnSelf(direction: RelayDirection, packet: ByteArray) {
        // Only the player's own outbound report: a server-sent position would
        // let a server place the player anywhere, and every distance check
        // downstream depends on this being the client's own claim.
        if (direction != RelayDirection.TO_SERVER) return
        val move = try {
            MovementModules.parseMove(packet)
        } catch (e: Exception) {
            null
        } ?: return
        ctx.updateSelf(move.runtimeId, move.x, move.y, move.z, move.onGround)
    }

    private fun dispatch(
        id: String,
        direction: RelayDirection,
        packets: List<ByteArray>,
        ctx: ModuleContext,
    ): List<ByteArray> {
        val category = categoryOf(id)
        return packets.flatMap { packet ->
            when (category) {
                "combat" -> CombatModules.transform(id, direction, packet, ctx)
                "movement" -> MovementModules.transform(id, direction, packet, ctx)
                "visual" -> VisualModules.transform(id, direction, packet, ctx)
                "world" -> WorldModules.transform(id, direction, packet, ctx)
                "automation" -> AutomationModules.transform(id, direction, packet, ctx)
                "player" -> PlayerModules.transform(id, direction, packet, ctx)
                "misc" -> MiscModules.transform(id, direction, packet, ctx)
                "network" -> NetworkModules.transform(id, direction, packet, ctx)
                else -> listOf(packet)
            }
        }
    }

    companion object {

        /**
         * Application order. Cheap filters first, then position rewrites, then
         * anything that reads session state a later module may have recorded.
         */
        val ORDER: List<String> = listOf(
            // filters (drop whole packets)
            "xykell.automation.ghost",
            "xykell.misc.disabler",
            "xykell.misc.anti_weather",
            "xykell.visual.no_weather",
            "xykell.visual.no_blindness",
            "xykell.visual.no_nausea",
            "xykell.player.no_blindness",
            "xykell.player.no_nausea",
            "xykell.player.haste",
            // motion filters
            "xykell.combat.velocity",
            "xykell.combat.knockback",
            "xykell.combat.backtrack",
            "xykell.movement.movement_correction",
            // position rewrites
            "xykell.movement.levitate",
            "xykell.movement.speed",
            "xykell.movement.no_fall",
            "xykell.movement.slow_falling",
            "xykell.movement.fly",
            "xykell.movement.motion_fly",
            "xykell.movement.jump_boost",
            "xykell.player.no_fall",
            // input-flag rewrites
            "xykell.misc.toggle_sprint",
            "xykell.misc.toggle_sneak",
            "xykell.automation.no_break_delay",
            "xykell.combat.auto_crit",
            // clock / world views
            "xykell.visual.time_changer",
            "xykell.visual.fullbright",
            "xykell.visual.weather_changer",
            "xykell.visual.particle_controls",
            "xykell.visual.fog_controls",
            // session-state readers / overlays
            "xykell.visual.esp",
            "xykell.visual.player_esp",
            "xykell.visual.entity_esp",
            "xykell.visual.item_esp",
            "xykell.visual.tracers",
            "xykell.visual.block_tracers",
            "xykell.visual.nametag",
            "xykell.visual.waypoints",
            "xykell.visual.chunk_borders",
            "xykell.visual.new_chunks",
            "xykell.world.block_esp",
            "xykell.world.block_tracer",
            "xykell.world.chunk_borders",
            "xykell.world.chunk_finder",
            "xykell.world.new_chunks",
            // state learners feeding the tap plans
            "xykell.automation.auto_eat",
            "xykell.automation.auto_fish",
            "xykell.player.fast_eat",
            "xykell.player.fast_interact",
            // input plans (taps, holds, double taps)
            "xykell.combat.afk_clicker",
            "xykell.combat.double_click",
            "xykell.misc.quick_drop",
            // readers
            "xykell.network.packet_monitor",
            "xykell.network.packet_logger",
        )

        /** Ids with a real transform or a real tap plan, across every category. */
        val ALL_IMPLEMENTED: Set<String> =
            CombatModules.IMPLEMENTED +
                MovementModules.IMPLEMENTED +
                VisualModules.IMPLEMENTED +
                WorldModules.IMPLEMENTED +
                AutomationModules.IMPLEMENTED +
                PlayerModules.IMPLEMENTED +
                MiscModules.IMPLEMENTED +
                NetworkModules.READERS

        /** The subset that answers "tap here this tick" instead of rewriting bytes. */
        val PLANNED: Set<String> = setOf(
            "xykell.player.fast_eat",
            "xykell.player.fast_interact",
            "xykell.automation.auto_eat",
            "xykell.automation.auto_fish",
            "xykell.combat.afk_clicker",
            "xykell.combat.double_click",
            "xykell.misc.quick_drop",
        )

        private val BY_CATEGORY = mapOf(
            "xykell.combat." to "combat",
            "xykell.movement." to "movement",
            "xykell.visual." to "visual",
            "xykell.world." to "world",
            "xykell.automation." to "automation",
            "xykell.player." to "player",
            "xykell.misc." to "misc",
            "xykell.network." to "network",
        )

        /** Category segment of a full id, or null when it is not a module id. */
        fun categoryOf(id: String): String? =
            BY_CATEGORY.entries.firstOrNull { id.startsWith(it.key) }?.value

        /** The tap plan for [id], or empty when it has none. */
        fun plan(id: String, ctx: ModuleContext, random: Random): List<MacroStep> = when (id) {
            "xykell.player.fast_eat" -> PlayerModules.fastEatPlan(ctx, random)
            "xykell.player.fast_interact" -> PlayerModules.fastInteractPlan(ctx, random)
            "xykell.automation.auto_eat" -> AutomationModules.autoEatPlan(ctx, random)
            "xykell.automation.auto_fish" -> AutomationModules.autoFishPlan(ctx, random)
            "xykell.combat.afk_clicker" -> CombatModules.afkClickerPlan(ctx, random)
            "xykell.combat.double_click" -> CombatModules.doubleClickPlan(ctx, random)
            "xykell.misc.quick_drop" -> MiscModules.quickDropPlan(ctx)
            else -> emptyList()
        }
    }
}