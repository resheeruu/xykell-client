package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.RelayDirection
import kotlin.random.Random
/**
 * Per-packet transforms for the PLAYER registry ids.
 *
 * Every transform is pure: one packet in, packets out, plus whatever it records
 * in [ModuleContext] for the ids that need more than one packet. No sockets, no
 * Android, no clock. Empty list drops the packet.
 *
 * Three shapes are implemented, and each is honest about its own reach:
 *
 *  - **Status-effect filters** (`no_blindness`, `no_nausea`, `haste`) drop
 *    MobEffect 0x1c for one effect id. The packet is client-bound and its
 *    `effect_id` sits at a fixed offset, so this strips exactly that effect and
 *    leaves every other one alone — which is what an earlier "dropping the whole
 *    packet" note called impossible. The server still thinks the effect is on;
 *    the player just stops seeing it.
 *  - **`no_fall`** rewrites the player's own outbound height to the last
 *    on-ground y, which [ModuleContext] now carries across packets.
 *  - **`fast_eat` / `fast_interact`** are input plans, not packet rewrites:
 *    see [TapPlan]. They read the client's own reported item cooldown
 *    (ClientStartItemCooldown 0xb0) for the cadence and answer "tap here now",
 *    which the accessibility service executes. No UseItem or Interact packet is
 *    ever forged.
 *
 * What is *not* here matters as much: `haste` strips MiningFatigue and never
 * grants Haste, because granting it would mean fabricating a MobEffect the
 * server never sent. Every remaining id is in [IMPOSSIBLE] with the specific
 * input that is missing, not with "needs a timer" — the timer and the
 * cross-packet state both exist now.
 */
object PlayerModules {

    /** MobEffect 0x1c, the client-bound status-effect packet. */
    const val ID_MOB_EFFECT = 0x1c

    /** MovePlayer 0x13, the client's own position report. */
    const val ID_MOVE_PLAYER = 0x13

    /** PlayerAuthInput 0x90, the server-authoritative movement input. */
    const val ID_PLAYER_AUTH_INPUT = 0x90

    /** ClientStartItemCooldown 0xb0, the client reporting its own item lock. */
    const val ID_START_ITEM_COOLDOWN = 0xb0

    /**
     * Vanilla Bedrock `Effect` ordinals.
     *
     * registry/bedrock-packets.json gives MobEffect 0x1c the `effect_id` field
     * but not the value it carries, and the vendored proto.yml defines the field
     * without listing the Effect table, so nothing in this repo verifies these
     * numbers. They are the vanilla ordinals and they are overridable per module
     * with the `effect_id` setting for a server that remaps them.
     */
    const val EFFECT_MINING_FATIGUE = 4
    const val EFFECT_NAUSEA = 9
    const val EFFECT_BLINDNESS = 15

    /** MobEffect 0x1c `event_id`: add, update, remove. */
    private const val EVENT_ID_MIN = 1
    private const val EVENT_ID_MAX = 3

    /** Tap point and cadence keys for the two input plans. */
    const val FAST_EAT_POINT = "fast_eat.point"
    const val FAST_INTERACT_POINT = "fast_interact.point"

    /** Fallback eat cadence in ticks, used until the client reports a cooldown. */
    private const val DEFAULT_EAT_TICKS = 20

    /** Fallback interact cadence in ticks. */
    private const val DEFAULT_INTERACT_TICKS = 4

    /** Ids with a real transform or a real tap plan. */
    val IMPLEMENTED: Set<String> = setOf(
        "xykell.player.no_blindness",
        "xykell.player.no_nausea",
        "xykell.player.haste",
        "xykell.player.no_fall",
        "xykell.player.fast_eat",
        "xykell.player.fast_interact",
        "xykell.player.spam",
    )

    /** The text spam sends, and the minimum gap between two sends. */
    const val SETTING_SPAM_TEXT = "spam_text"

    /** Never faster than this: a sub-second chat loop is a ban magnet, not a feature. */
    const val MIN_SPAM_GAP_MS = 1_000L
    const val DEFAULT_SPAM_GAP_MS = 3_000L

    /**
     * spam: send a configured line on a wall-clock cadence.
     *
     * This is the first id the relay *authors* rather than rewrites, and the
     * scope is deliberate (see [BedrockText]): the user's own account says text
     * the user configured, on a cadence that has a hard floor.
     *
     * It fires on the outbound leg rather than on a timer thread, so a message
     * can only go out while the client is actually talking to the server. That
     * is both safer and simpler than owning a scheduler: when the session is
     * idle, nothing is sent, which is exactly when a chat loop would be
     * detectable anyway.
     */
    fun spam(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        val text = ctx.settings[SETTING_SPAM_TEXT] ?: return listOf(packet)
        val now = ctx.nowMs()
        val last = ctx.lastChatAtMs
        val gap = ctx.number("spam_gap_ms", DEFAULT_SPAM_GAP_MS.toFloat())
            .toLong().coerceAtLeast(MIN_SPAM_GAP_MS)
        if (last != null && now - last < gap) return listOf(packet)
        val chat = BedrockText.chat(text) ?: return listOf(packet)
        ctx.markChatSent(now)
        // The client's own packet still goes out first: dropping it would look
        // like a broken session, and the injected line must not replace it.
        return listOf(packet, chat)
    }

    /** Ids a relay genuinely cannot deliver, with the reason. */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.player.no_fire" to
            "The fire overlay is drawn from the actor's flame flag, which arrives as " +
            "an entry inside SetActorData 0x27's MetadataDictionary — a nested " +
            "varint-prefixed key/value blob with no fixed offsets, so there is no " +
            "verified byte to rewrite. MobEffect 0x1c cannot stand in for it: " +
            "Bedrock's Effect table has fire_resistance but no fire effect, because " +
            "the flame is an entity flag rather than a status effect.",
        "xykell.player.slow_mine" to
            "Slowing a break is a longer hold, and the only wire trace of a break is " +
            "PlayerAction 0x24 — whose Action ordinals the vendored proto.yml names " +
            "as 'the constants above' and never lists, so the relay cannot even read " +
            "which action a packet carries, let alone extend one. The break " +
            "progress itself is server-accumulated and validated there.",
        "xykell.player.anti_immobile" to
            "The 1.26.45 InputData list in the vendored proto has 66 ordinals (0..65) " +
            "and none of them is an input-lock or immobilise flag, so PlayerAuthInput " +
            "0x90 carries nothing to clear; the server enforces immobilising by " +
            "rejecting movement, which no packet edit can talk the server out of.",
        "xykell.player.no_hurt_cam" to
            "The hurt-camera shake is the renderer reacting to a health drop, so no " +
            "packet carries it. CameraShake 0x9f is the server's own scripted shake, " +
            "not the damage reaction, and dropping it would not remove the hurt cam.",
        "xykell.player.fake_stats" to
            "Registry: \"local display only\". The HUD renders values the server " +
            "broadcasts; there is no client-bound packet holding a display-only " +
            "counter to rewrite, and the relay has no render surface.",
    )

    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = when (id) {
        "xykell.player.no_blindness" -> dropEffect(direction, packet, ctx.int("effect_id", EFFECT_BLINDNESS))
        "xykell.player.no_nausea" -> dropEffect(direction, packet, ctx.int("effect_id", EFFECT_NAUSEA))
        "xykell.player.haste" -> dropEffect(direction, packet, ctx.int("effect_id", EFFECT_MINING_FATIGUE))
        "xykell.player.no_fall" -> holdGroundHeight(direction, packet, ctx)
        "xykell.player.fast_eat" -> learnItemCooldown(direction, packet, ctx)
        "xykell.player.spam" -> spam(direction, packet, ctx)
        else -> listOf(packet) // not in IMPLEMENTED: forward untouched
    }

    /**
     * Drop MobEffect 0x1c when it carries [effectId], keeping every other status
     * effect byte-identical.
     *
     * Scoped to [RelayDirection.TO_CLIENT] because 0x1c is client-bound
     * (BedrockPacketIds.infoOf("MobEffect").toClient). The filter is not scoped to
     * the local player's runtime id: that id is a varint64 which ModuleWire's
     * reader deliberately does not decode past 28 bits, so comparing it would
     * silently disable the module on real traffic. Stripping another entity's
     * copy of the same effect is harmless — those are not rendered anyway.
     */
    private fun dropEffect(direction: RelayDirection, packet: ByteArray, effectId: Int): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        return if (mobEffectId(packet) == effectId) emptyList() else listOf(packet)
    }

    /**
     * The `effect_id` of a well-formed MobEffect 0x1c, or null when [raw] is not
     * one. The runtime id's width is measured rather than assumed (it is a
     * varint64 and is never decoded here), and an `event_id` outside add/update/
     * remove means the bytes are not the packet this expects.
     */
    private fun mobEffectId(raw: ByteArray): Int? {
        if (ModuleWire.id(raw) != ID_MOB_EFFECT) return null
        val body = ModuleWire.bodyStart(raw) ?: return null
        val idWidth = ModuleWire.varUIntSize(raw, body) ?: return null
        val (event, afterEvent) = ModuleWire.readByte(raw, body + idWidth) ?: return null
        if (event < EVENT_ID_MIN || event > EVENT_ID_MAX) return null
        return ModuleWire.readVarInt(raw, afterEvent)?.first
    }

    /**
     * no_fall: while the player is airborne, report the last on-ground y instead
     * of the descending one, so the server never sees a fall and never computes
     * fall damage from it.
     *
     * Two carriers, because Bedrock has two. MovePlayer 0x13 is the legacy
     * report and is handled by the one rule `xykell.movement.no_fall` already
     * uses — this delegates to it rather than repeating the arithmetic.
     * PlayerAuthInput 0x90 is what the client actually sends when the server set
     * ServerAuthoritativeMovementMode, and no other module touched it: its
     * `position` is at a fixed offset ahead of the input list, ahead of every
     * variable-length field, so the y is read and written with no guessing.
     *
     * Scoped to [RelayDirection.TO_SERVER]: the server's own view of the player
     * is the other leg and stays authoritative, which is what makes this work at
     * all. A packet too short to parse, a session that has never seen ground, and
     * a player who is on the ground all forward untouched.
     */
    private fun holdGroundHeight(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        if (ModuleWire.id(packet) == ID_MOVE_PLAYER) return MovementModules.noFall(direction, packet, ctx)
        if (ctx.selfOnGround || !ctx.hasGround()) return listOf(packet)
        if (ModuleWire.id(packet) != ID_PLAYER_AUTH_INPUT) return listOf(packet)
        val at = ModuleWire.bodyStart(packet)?.plus(ModuleWire.PLAYER_AUTH_INPUT_POSITION_Y_OFFSET)
            ?: return listOf(packet)
        val reported = ModuleWire.readF32LE(packet, at)?.first ?: return listOf(packet)
        if (reported <= ctx.lastGroundY) return listOf(packet)
        return listOf(ModuleWire.put(packet, at, ModuleWire.writeF32LE(ctx.lastGroundY)))
    }

    /**
     * fast_eat's packet arm: ClientStartItemCooldown 0xb0 is the client telling
     * the server how long it just locked the item it used. That is the cadence a
     * repeat use may honestly be sent at, so the plan reads it instead of
     * guessing. Only a cooldown whose category names eating is learned.
     *
     * Scoped to [RelayDirection.TO_SERVER] (0xb0 is server-bound) and forwarded
     * unchanged: this observes, it never rewrites a value the server owns.
     */
    internal fun learnItemCooldown(
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext,
    ): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        if (ModuleWire.id(packet) != ID_START_ITEM_COOLDOWN) return listOf(packet)
        val body = ModuleWire.bodyStart(packet) ?: return listOf(packet)
        val (category, next) = ModuleWire.readVarString(packet, body) ?: return listOf(packet)
        if (!category.endsWith(EAT_CATEGORY_SUFFIX, ignoreCase = true)) return listOf(packet)
        val (duration, _) = ModuleWire.readVarInt(packet, next) ?: return listOf(packet)
        ctx.recordItemCooldown(duration)
        return listOf(packet)
    }

    /**
     * The eat cadence: one use-item tap every [ModuleContext.itemCooldownTicks]
     * ticks, or every `intervalTicks` when that setting is positive. Empty on
     * the ticks that are not due, and empty until [pointKey] is configured.
     *
     * This is input, not a packet: it does not bypass the server's own cooldown,
     * it just asks for the use again the moment the client's own cooldown is up.
     * Shared by `xykell.player.fast_eat` and `xykell.automation.auto_eat`, which
     * are the same behaviour under two registry names.
     */
    internal fun eatPlan(ctx: ModuleContext, random: Random, pointKey: String): List<MacroStep> {
        val override = ctx.int("intervalTicks", 0)
        val ticks = if (override > 0) override else maxOf(DEFAULT_EAT_TICKS, ctx.itemCooldownTicks)
        return TapPlan.everyTicks(ctx, random, pointKey, ticks.toLong())
    }

    /** [eatPlan] at this category's own tap point. */
    fun fastEatPlan(ctx: ModuleContext, random: Random): List<MacroStep> =
        eatPlan(ctx, random, FAST_EAT_POINT)

    /**
     * fast_interact's tap plan: one tap at the configured point — the crosshair,
     * on the layout where it sits at the screen's centre — every
     * `intervalTicks` ticks.
     */
    fun fastInteractPlan(ctx: ModuleContext, random: Random): List<MacroStep> =
        TapPlan.everyTicks(
            ctx,
            random,
            FAST_INTERACT_POINT,
            ctx.int("intervalTicks", DEFAULT_INTERACT_TICKS).toLong(),
        )

    private const val EAT_CATEGORY_SUFFIX = "eat"
}
