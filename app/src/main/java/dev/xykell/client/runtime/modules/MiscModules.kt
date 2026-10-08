package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.RelayDirection
/**
 * MISC batch: transforms for the ten `xykell.misc.*` ids.
 *
 * Every transform is pure: one packet in, packets out. No state between calls,
 * no sockets, no Android. Empty list drops the packet.
 *
 * Four ids are implementable now that the packet ids are known and the input
 * flags have a verified layout. [disabler] has two arms, [anti_weather] reads a
 * fixed field of LevelEvent 0x19, and [toggle_sprint] / [toggle_sneak] rewrite
 * the player's own PlayerAuthInput 0x90 `input_data` list.
 *
 * That last pair is where the 1.26.40 change bites. Upstream says `input_data`
 * is "an optional list of InputData values instead of a bitfield", and
 * 1.26.45 defines it as `input_data?: InputData[]varint` — a presence byte, a
 * varint count, then zigzag32 ordinals. ModuleWire reads exactly that form and
 * returns null on anything it cannot parse, so a caller forwards the packet
 * rather than applying a bitfield rewrite to a list. There is no bitfield
 * fallback anywhere, which is the whole point: a mask rewrite over these bytes
 * would silently corrupt the packet instead of doing nothing.
 *
 * The other six are in [IMPOSSIBLE] with the concrete blocker, which for three of
 * them is the one thing a relay genuinely cannot touch: server-granted state.
 */
object MiscModules {

    /** Server -> client health/damage value. Layout verified in BedrockPackets. */
    const val ID_SET_HEALTH = 0x2A

    /** Server -> client level events, including the weather ones. */
    const val ID_LEVEL_EVENT = 0x19

    /** Server -> client status effects; the disabler's second arm. */
    const val ID_MOB_EFFECT = 0x1c

    /** Client -> server movement input, the one carrying `input_data`. */
    const val ID_PLAYER_AUTH_INPUT = 0x90

    /** LevelEvent 0x19 weather events. */
    const val EVENT_START_RAIN = 3001
    const val EVENT_START_THUNDER = 3002

    /** Ids with a real transform. */
    val IMPLEMENTED: Set<String> = setOf(
        "xykell.misc.disabler",
        "xykell.misc.anti_weather",
        "xykell.misc.toggle_sprint",
        "xykell.misc.toggle_sneak",
    )

    /** Ids a relay genuinely cannot deliver, with the reason. */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.misc.quick_perspective" to
            "camera path: the third-person flag is renderer state and rides on no " +
            "packet. Camera 0x49 is the Education Edition camera bind (two entity " +
            "ids) and is client-bound, so it is not a perspective toggle either; " +
            "nothing the relay forwards decides which shoulder the client draws.",
        "xykell.misc.quick_drop" to
            "A drop is PlayerAction 0x24 with the DROP_ITEM action, which the " +
            "relay must not originate — forging a client action is the forgery " +
            "this client does not do. On a touch layout it is also a long press " +
            "on a hotbar slot, and the accessibility surface injects fixed taps, " +
            "not holds.",
        "xykell.misc.fake_op" to
            "server-authoritative: operator status is granted by the server, a " +
            "relayed packet edit cannot claim it, and the op level the HUD shows " +
            "comes from server messages the relay has no way to satisfy.",
        "xykell.misc.skin_stealer" to
            "server-authoritative: the worn skin is server-side inventory state, " +
            "no client-bound packet decides what the server renders, and taking " +
            "another player's skin means account data the client must never touch.",
        "xykell.misc.java_mode" to
            "unreachable on this surface by construction: the protocol version is " +
            "carried in LoginPacket 0x01, and RelaySession documents that the " +
            "handshake packets (0xc1, 0x8f, 0x01, 0x03, 0x04) never reach the " +
            "module hook at all. Post-login no packet repeats the version, so " +
            "there is nothing left to read or rewrite.",
        "xykell.misc.fast_throw" to
            "The charge is client-local elapsed time: no packet field carries a " +
            "charge level, and PlayerAuthInput 0x90's only use-item ordinals (34 " +
            "item_interact, 53 start_using_item) mark press and release, not how " +
            "long the item was held. The release is the game's own transaction, " +
            "so the relay cannot move it earlier without forging it.",
    )

    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = when (id) {
        "xykell.misc.disabler" -> disabler(direction, packet, ctx)
        "xykell.misc.anti_weather" -> antiWeather(direction, packet)
        "xykell.misc.toggle_sprint" -> toggleSprint(direction, packet, ctx)
        "xykell.misc.toggle_sneak" -> toggleSneak(direction, packet, ctx)
        else -> listOf(packet) // not in IMPLEMENTED: forward untouched
    }

    /**
     * Disabler, two honest arms:
     *  - always drop the server's SetHealth 0x2A, so the client stops applying
     *    health and damage updates;
     *  - when the `dropEffects` setting is on, drop the server's MobEffect 0x1c
     *    so no status effect the server grants ever reaches the game.
     *
     * Both are scoped to [RelayDirection.TO_CLIENT]. Neither is a movement or
     * combat arm: those would need field layouts this repo does not decode, so
     * they stay unclaimed rather than guessed at.
     */
    private fun disabler(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val id = ModuleWire.id(packet)
        if (id == ID_SET_HEALTH) return emptyList()
        if (id == ID_MOB_EFFECT && ctx.flag("dropEffects")) return emptyList()
        return listOf(packet)
    }

    /**
     * anti_weather: drop the LevelEvent 0x19 that starts rain or thunder, so the
     * game never switches its weather renderer on and never draws the rain
     * overlay. The event id is the packet's first field, so this needs no guess
     * work: 3001 starts rain and 3002 starts thunder, while 3003/3004 stop them
     * and pass through.
     *
     * Scoped to [RelayDirection.TO_CLIENT] (0x19 is client-bound), so the
     * server's own weather continues — this hides it locally, which is what the
     * module claims.
     */
    private fun antiWeather(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        if (ModuleWire.id(packet) != ID_LEVEL_EVENT) return listOf(packet)
        val body = ModuleWire.bodyStart(packet) ?: return listOf(packet)
        val (event, _) = ModuleWire.readVarInt(packet, body) ?: return listOf(packet)
        return if (event == EVENT_START_RAIN || event == EVENT_START_THUNDER) emptyList() else listOf(packet)
    }

    /**
     * toggle_sprint: report the sprint ordinals in the player's own outbound
     * PlayerAuthInput 0x90 while the `sprint` setting is on, and clear every
     * sprint ordinal while it is off.
     *
     * It is a function of the packet's own list, so the transition is where the
     * work happens: a sprint press that is not yet in the list gains both the
     * held flag and the one-shot press, and a release clears them. No call
     * history is needed and no state desyncs.
     */
    private fun toggleSprint(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        val on = ctx.flag("sprint")
        return setInputFlags(
            direction,
            packet,
            on = on,
            onFlags = listOf(
                ModuleWire.INPUT_SPRINTING,
                ModuleWire.INPUT_START_SPRINTING,
            ),
            offFlags = listOf(
                ModuleWire.INPUT_SPRINT_DOWN,
                ModuleWire.INPUT_SPRINTING,
                ModuleWire.INPUT_START_SPRINTING,
                ModuleWire.INPUT_STOP_SPRINTING,
            ),
        )
    }

    /** toggle_sneak: the same shape over the sneak ordinals. */
    private fun toggleSneak(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        val on = ctx.flag("sneak")
        return setInputFlags(
            direction,
            packet,
            on = on,
            onFlags = listOf(
                ModuleWire.INPUT_SNEAKING,
                ModuleWire.INPUT_START_SNEAKING,
            ),
            offFlags = listOf(
                ModuleWire.INPUT_SNEAKING,
                ModuleWire.INPUT_SNEAK_DOWN,
                ModuleWire.INPUT_PERSIST_SNEAK,
                ModuleWire.INPUT_START_SNEAKING,
                ModuleWire.INPUT_STOP_SNEAKING,
            ),
        )
    }

    /**
     * Rewrite PlayerAuthInput 0x90's `input_data` list with [onFlags] added or
     * [offFlags] removed.
     *
     * Scoped to [RelayDirection.TO_SERVER] (0x90 is server-bound). The list is
     * read through ModuleWire's optional-list reader, which returns null on a
     * malformed or pre-1.26.40 packet, and a null forwards the packet untouched
     * — this never falls back to treating those bytes as a bitfield.
     */
    private fun setInputFlags(
        direction: RelayDirection,
        packet: ByteArray,
        on: Boolean,
        onFlags: List<Int>,
        offFlags: List<Int>,
    ): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        if (ModuleWire.id(packet) != ID_PLAYER_AUTH_INPUT) return listOf(packet)
        val keep = if (on) onFlags else emptyList()
        val drop = if (on) emptyList() else offFlags
        val out = ModuleWire.setInputData(packet, keep, drop)
        return if (out.contentEquals(packet)) listOf(packet) else listOf(out)
    }
}
