package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.BedrockPacketIds
import dev.xykell.client.runtime.relay.RelayDirection
import kotlin.random.Random
/**
 * Combat module transforms for the relay hook: one packet in, packets out.
 *
 * Every transform is pure except for [ctx], the per-session state the session
 * owns: no sockets, no Android, no clock. An empty list drops the packet, one
 * packet rewrites it, several spawn extras.
 *
 * Packet ids come from the generated [BedrockPacketIds] table rather than being
 * written down here, because a hand-written id is a silent no-op: the old table
 * claimed the knockback vector was "SetActorMotion 0x1B", and 0x1B is
 * EntityEvent — the jump / hurt *animation*. Dropping it cancelled a hurt
 * flash, not a push. The vector is `SetEntityMotion`, 0x28.
 *
 * The two direction-scoping facts this batch relies on, both from that table:
 * `SetEntityMotion` is `bound: both`, so knockback scaling is restricted to
 * [RelayDirection.TO_CLIENT] — the player reporting its *own* motion (boats,
 * pistons) is the other leg and must survive. `PlayerAuthInput` is
 * `bound: server`, so the crit pitch rewrite only ever runs outbound.
 *
 * Ids a relay cannot deliver are in [IMPOSSIBLE] with the concrete reason. Note
 * that two of the old reasons were themselves wrong once [ctx] existed — "the
 * relay never decodes actors" is false, [ctx.entities] tracks every entity's
 * runtime id and position — so those entries now name the real blocker.
 */
object CombatModules {

    /**
     * Ids with a real transform.
     *
     * velocity drops the knockback vector outright and knockback scales it, so
     * knockback at 0.0 is velocity and anything above trades push for control.
     */
    val IMPLEMENTED: Set<String> = setOf(
        "xykell.combat.velocity",
        "xykell.combat.knockback",
        "xykell.combat.auto_crit",
        "xykell.combat.afk_clicker",
        "xykell.combat.double_click",
    )

    /** Tap points for the two input plans. */
    const val AFK_CLICKER_POINT = "afk_clicker.point"
    const val DOUBLE_CLICK_POINT = "double_click.point"

    /**
     * afk_clicker's plan: one tap at the configured point every
     * `intervalTicks` ticks.
     *
     * This was IMPOSSIBLE with the reason "a packet hook cannot synthesise
     * touch input", which is no longer true — [ModuleTapRunner] drives the same
     * gesture surface a finger uses. Nothing is forged: the game still sends
     * every attack, it is just asked to send it on a cadence.
     */
    fun afkClickerPlan(ctx: ModuleContext, random: Random): List<MacroStep> =
        TapPlan.everyTicks(
            ctx,
            random,
            AFK_CLICKER_POINT,
            ctx.int("intervalTicks", DEFAULT_AFK_INTERVAL_TICKS).toLong(),
        )

    /**
     * double_click's plan: two taps at the configured point. On a touch layout
     * that IS the double click; the packet-level objection (re-sending an
     * InventoryTransaction attack) does not apply because the game sends it.
     */
    fun doubleClickPlan(ctx: ModuleContext, random: Random): List<MacroStep> {
        // Only on the due ticks, so it is a cadence and not a machine-gun.
        val interval = ctx.int("intervalTicks", DEFAULT_DOUBLE_CLICK_TICKS).toLong()
        if (interval <= 0L || ctx.tick % interval != 0L) return emptyList()
        return TapPlan.doubleTap(ctx, random, DOUBLE_CLICK_POINT)
    }

    private const val DEFAULT_AFK_INTERVAL_TICKS = 10
    private const val DEFAULT_DOUBLE_CLICK_TICKS = 4

    /**
     * Ids a relay genuinely cannot deliver, with the reason.
     *
     * backtrack and mace_damage are the near misses: both are real packet
     * rewrites blocked by a specific missing fact rather than by the old "a
     * transform has no state" argument.
     *
     * afk_clicker and double_click used to sit here too, blocked on "a packet
     * hook cannot synthesise touch input". That is no longer true: the tap
     * surface exists, so both are input plans over [TapPlan] rather than
     * forbidden rewrites.
     */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.combat.aim_assist" to
            "the aim vector is the client's own rendered crosshair; ctx.entities does supply every " +
            "entity's position, but the view matrix and where the camera points exist only in the " +
            "renderer, so the angle to correct cannot be derived.",
        "xykell.combat.trigger_bot" to
            "needs to know when the crosshair is over a target; that raycast runs locally in the " +
            "client before any attack packet is emitted, so there is nothing on the wire to react to.",
        "xykell.combat.kill_aura" to
            "target selection is available from ctx.entities.nearest, but the attack itself lives in " +
            "InventoryTransaction 0x1e's Transaction body, and Transaction/TransactionUseItem/" +
            "UseItemOnEntityAction are not expanded anywhere in the vendored proto, so the relay " +
            "cannot read the action id it would have to rebuild.",
        "xykell.combat.tp_aura" to
            "the outbound leg is rewritable and ctx.entities gives positions, but 'the target' is the " +
            "entity under the client's crosshair, chosen in the renderer; ctx holds no view direction, " +
            "so which entity to teleport to cannot be derived from the wire.",
        "xykell.combat.reach" to
            "reach is a client-side attack-distance check that runs locally before any packet is " +
            "emitted; no packet field carries it and moving the reported position only changes where " +
            "the server thinks the player is, not what the client allows it to hit.",
        "xykell.combat.hitbox" to
            "needs a widened AABB inside the client's own raycast; entity bounds never leave the game " +
            "and the local raycast runs before anything reaches the wire.",
        "xykell.combat.knockback_delay" to
            "needs a timed queue between the two legs; a transform returns immediately and ctx has no " +
            "queue, only the last position and a logical tick.",
        "xykell.combat.backtrack" to
            "needs a per-target position *history* window: ctx.entities holds each entity's latest " +
            "position and overwrites it on every move, so an old position cannot be replayed, and " +
            "ModuleContext exposes no field in which a lookback ring could be kept.",
        "xykell.combat.auto_totem" to
            "needs the offhand totem read from the inventory; no container packet is decoded",
        "xykell.combat.auto_potion" to
            "needs the hotbar potion slot plus its use timing; no inventory state is visible",
        "xykell.combat.auto_crystal" to
            "needs end-crystal entities and a place-block packet built from world geometry",
        "xykell.combat.anti_crystal" to
            "needs crystal positions and the block/sound burst of an explosion; neither is decoded",
        "xykell.combat.auto_cart" to
            "needs beacon entities plus placement geometry, none of it on the wire",
        "xykell.combat.auto_web" to
            "needs a cobweb placement against a tracked target: ctx.entities gives the position, but " +
            "the placement needs the block id from inventory state and the unexpanded " +
            "InventoryTransaction action layout.",
        "xykell.combat.auto_switch" to
            "needs the hotbar slot index holding the wanted item; container state is never decoded",
        "xykell.combat.mace_swap" to
            "needs to swap to a mace slot at attack time, which requires inventory slot contents",
        "xykell.combat.mace_damage" to
            "mace damage is computed server-side from the fall distance the server tracks itself, so " +
            "no packet carries a multiplier; inflating it would mean falsifying the whole reported " +
            "fall, which needs the swing's timing and knowledge that a mace is held — inventory state " +
            "this repo never decodes.",
        "xykell.combat.shield_disabler" to
            "needs an axe swap timed on the target's block state; neither is visible to the relay",
        "xykell.combat.anchor_aura" to
            "needs anchor entities, block states and placement timing from the world",
        "xykell.combat.target_hud" to
            "client-side HUD overlay rendering; a packet hook has no render surface",
        "xykell.combat.target_selector" to
            "UI target picker; the choice is made in the client and never travels on the wire",
        "xykell.combat.friend_filter" to
            "filters against the client's friend store, which a pure transform cannot read",
        "xykell.combat.combat_settings" to
            "a settings aggregate, not a packet transform; there are no bytes to rewrite",
        "xykell.combat.auto_log" to
            "needs a timer plus a session-level logout decision the transform cannot make; ctx.tick " +
            "is a logical counter with no wall-clock mapping.",
        "xykell.combat.mob_aura" to
            "the mob scan is available from ctx.entities, but attacking a chosen mob needs the same " +
            "unexpanded InventoryTransaction action layout that blocks kill_aura.",
    )

    /**
     * The knockback / motion vector. `SetActorMotion` was the old name;
     * `EntityEvent` is what 0x1B actually is, and this table is generated so
     * the id cannot silently drift again.
     */
    val SET_ENTITY_MOTION: Int = BedrockPacketIds.infoOf("SetEntityMotion")!!.id

    /** PlayerAuthInput, `bound: server` — the client's own per-tick input report. */
    val PLAYER_AUTH_INPUT: Int = BedrockPacketIds.infoOf("PlayerAuthInput")!!.id

    /** Pitch every auto_crit swing reports: straight down. */
    const val CRIT_PITCH = -90f

    /** Fraction of the knockback vector that reaches the game by default. */
    const val DEFAULT_KNOCKBACK_SCALE = 0.5f

    const val SETTING_KNOCKBACK_SCALE = "knockback_scale"
    const val SETTING_CRIT_PITCH = "crit_pitch"

    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = when (id) {
        "xykell.combat.velocity" -> velocity(direction, packet)
        "xykell.combat.knockback" -> knockback(direction, packet, ctx)
        "xykell.combat.auto_crit" -> autoCrit(direction, packet, ctx)
        else -> listOf(packet) // not in IMPLEMENTED: forward untouched
    }

    /**
     * velocity: cancel knockback by dropping [SET_ENTITY_MOTION] on the
     * clientbound leg, so the server's push vector never reaches the game.
     *
     * Scoped to [RelayDirection.TO_CLIENT], so only the server's push is
     * cancelled. The player's own outbound SetEntityMotion (boats, pistons) is
     * the other direction and survives.
     */
    private fun velocity(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        if (ModuleWire.id(packet) != SET_ENTITY_MOTION) return listOf(packet)
        return emptyList()
    }

    /**
     * knockback: scale the velocity inside [SET_ENTITY_MOTION] by
     * `knockback_scale`, so 0.5 leaves the player half as far to recover.
     * This is the graded version of what `velocity` does outright.
     *
     * The vector is three little-endian floats behind a varint64 runtime id,
     * whose width has to be measured rather than assumed. Scoped to
     * [RelayDirection.TO_CLIENT] because the packet is `bound: both` and the
     * serverbound leg is the player's own motion, not something to shrink.
     */
    private fun knockback(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val velocity = velocityOffset(packet) ?: return listOf(packet)
        val (x, afterX) = ModuleWire.readF32LE(packet, velocity) ?: return listOf(packet)
        val (y, afterY) = ModuleWire.readF32LE(packet, afterX) ?: return listOf(packet)
        val (z, _) = ModuleWire.readF32LE(packet, afterY) ?: return listOf(packet)
        val scale = ctx.number(SETTING_KNOCKBACK_SCALE, DEFAULT_KNOCKBACK_SCALE)
        var out = ModuleWire.put(packet, velocity, ModuleWire.writeF32LE(x * scale))
        out = ModuleWire.put(out, velocity + 4, ModuleWire.writeF32LE(y * scale))
        return listOf(ModuleWire.put(out, velocity + 8, ModuleWire.writeF32LE(z * scale)))
    }

    /**
     * auto_crit: report the attack pitch as straight down, which is what the
     * server checks for a crit. `pitch` is the first field of the
     * [PLAYER_AUTH_INPUT] body, so this is a four-byte overwrite at a fixed
     * offset — it never has to walk past `input_data`, whose optional-list
     * framing upstream leaves undefined.
     *
     * Scoped to [RelayDirection.TO_SERVER]; the table has PlayerAuthInput as
     * `bound: server`, so a clientbound call is a caller bug and is forwarded.
     */
    private fun autoCrit(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        if (ModuleWire.id(packet) != PLAYER_AUTH_INPUT) return listOf(packet)
        val body = ModuleWire.bodyStart(packet) ?: return listOf(packet)
        if (ModuleWire.readF32LE(packet, body) == null) return listOf(packet)
        return listOf(ModuleWire.put(packet, body, ModuleWire.writeF32LE(ctx.number(SETTING_CRIT_PITCH, CRIT_PITCH))))
    }

    /**
     * Offset of the velocity vec3f inside a [SET_ENTITY_MOTION] body, or null
     * when [raw] is another packet, has no readable header, or is truncated
     * before the runtime id ends.
     */
    private fun velocityOffset(raw: ByteArray): Int? {
        if (ModuleWire.id(raw) != SET_ENTITY_MOTION) return null
        val body = ModuleWire.bodyStart(raw) ?: return null
        val width = ModuleWire.varUIntSize(raw, body) ?: return null
        return body + width
    }
}