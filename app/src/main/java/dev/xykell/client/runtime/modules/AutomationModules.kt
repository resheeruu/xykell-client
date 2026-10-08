package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.RelayDirection
import kotlin.random.Random
/**
 * Per-packet transforms and tap plans for the AUTOMATION registry ids.
 *
 * Transforms are pure: one packet in, packets out, plus whatever [ModuleContext]
 * records. No sockets, no Android, no clock. Empty list drops the packet.
 *
 * Four ids are implementable:
 *  - `ghost` hides chat from a screen recording;
 *  - `no_break_delay` strips the `block_breaking_delay_enabled` ordinal from the
 *    player's own PlayerAuthInput 0x90, read through the optional-list form (see
 *    ModuleWire) rather than the pre-1.26.40 bitfield;
 *  - `auto_eat` and `auto_fish` are input plans over [TapPlan], gated on state the
 *    relay really observes — the client's own reported item cooldown for eating,
 *    and the server's own bobber animation for the bite.
 *
 * The rest are in [IMPOSSIBLE], and the reasons are no longer "needs a clock" or
 * "needs cross-packet state", because both now exist. They name the one input
 * that is genuinely missing: the block type behind a position, the contents of a
 * container, the text a human must type, or the `Action` ordinals the vendored
 * proto does not list. A tap at a fixed point on a timer cannot substitute for
 * any of those — it would be a macro, not the module.
 */
object AutomationModules {

    /** Text 0x09, the layout BedrockPackets.text() decodes. */
    private const val ID_TEXT = 0x09

    /** EntityEvent 0x1b, which carries the fishing bobber's own events. */
    const val ID_ENTITY_EVENT = 0x1b

    /** Client -> server movement input, the one carrying `input_data`. */
    const val ID_PLAYER_AUTH_INPUT = 0x90

    /** EntityEvent 0x1b event 13, the bobber hook: the bite. */
    const val ENTITY_EVENT_FISH_HOOK_HOOK = 13

    /** Tap point keys for the two input plans. */
    const val AUTO_EAT_POINT = "auto_eat.point"
    const val AUTO_FISH_POINT = "auto_fish.point"

    /** needsTranslation(0) + category(1), so the type byte sits third. */
    private const val TYPE_OFFSET = 2

    private const val TYPE_CHAT = 1
    private const val TYPE_WHISPER = 7

    /** Ids with a real transform or a real tap plan. */
    val IMPLEMENTED: Set<String> = setOf(
        "xykell.automation.ghost",
        "xykell.automation.no_break_delay",
        "xykell.automation.auto_eat",
        "xykell.automation.auto_fish",
    )

    /** Ids a relay genuinely cannot deliver, with the reason. */
    val IMPOSSIBLE: Map<String, String> = mapOf(
        "xykell.automation.auto_tool" to
            "Picking a tool needs the targeted block's type. The relay sees only " +
            "where — PlayerAction 0x24 carries a BlockCoordinates, not a block id — " +
            "and block ids arrive in LevelChunk/UpdateBlock, which this repo does " +
            "not decode. Tapping a slot on a timer would be a blind macro.",
        "xykell.automation.auto_tool_swap" to
            "Same missing input as auto_tool (the block type behind the position), " +
            "and the swap itself would have to be an InventoryTransaction 0x1e hotbar " +
            "write the game did not send.",
        "xykell.automation.auto_mine" to
            "A mining loop is the Action ordinal of PlayerAction 0x24, and the " +
            "vendored proto.yml names those constants as 'the constants above' " +
            "without ever listing them, so the relay cannot even read the action " +
            "byte to know a break started.",
        "xykell.automation.auto_dig" to
            "auto_mine's unreadable Action ordinal plus the block-type filter auto_" +
            "tool already lacks: block ids never arrive in a packet this repo decodes.",
        "xykell.automation.auto_equip" to
            "It must know what is in hand and what is worn. The held item is an " +
            "ItemV4 inside MobEquipment 0x1f, a variable-length network item with " +
            "no fixed offsets, so `selected_slot`/`window_id` cannot be located; " +
            "and equipping is a spawned ItemStackRequest 0x93.",
        "xykell.automation.auto_armor" to
            "Same as auto_equip: the worn set is server-confirmed through " +
            "MobEquipment 0x1f, whose trailing slot/window fields sit behind a " +
            "variable-length item, so the relay cannot read which armour is on.",
        "xykell.automation.auto_refill" to
            "Refilling means deciding what to move from where, which is inventory " +
            "contents the relay never holds, and then originating ItemStackRequest " +
            "0x93 clicks — a packet the client never sent.",
        "xykell.automation.auto_steal" to
            "The chest's contents arrive in a container payload this repo does not " +
            "decode, and taking them is a spawned ItemStackRequest 0x93, so both " +
            "halves — what to take and the act of taking — are unavailable.",
        "xykell.automation.auto_sell" to
            "A server shop's prices and contents live in container/request data the " +
            "relay does not decode, and the sale is a spawned ItemStackRequest 0x93 " +
            "into a window the relay cannot see.",
        "xykell.automation.inventory_cleaner" to
            "Dropping the unwanted stack means originating an InventoryTransaction " +
            "0x1e, and knowing which stacks are protected is inventory state the " +
            "relay does not hold. Cancelling drops is not cleaning them.",
        "xykell.automation.inventory_lock" to
            "Deciding whether a drop touches a locked slot means reading the " +
            "InventoryTransaction 0x1e Transaction payload or the ItemStackRequest " +
            "0x93 list — both nested, varint-prefixed structures with no fixed " +
            "offsets, so the slot fields cannot be located and a partial rewrite " +
            "would desync the client's predicted inventory.",
        "xykell.automation.auto_sign" to
            "The text goes into the game's own sign screen, and the tap surface " +
            "cannot type it; the relay may not originate the input that writes it.",
        "xykell.automation.auto_gg" to
            "The win message is server-specific text (Hive/Zeqa/CubeCraft/" +
            "Lifeboat/Galaxite) that exists in no packet, and sending it means " +
            "either typing it into the game or originating a Text 0x09 the client " +
            "never sent.",
        "xykell.automation.command_hotkey" to
            "The payload is a command string the relay never sees, and the tap " +
            "surface injects taps only — it cannot type into the chat box, and " +
            "sending the Text 0x09 itself would be outbound injection.",
        "xykell.automation.text_hotkey" to
            "Same missing text payload and same typing limit as command_hotkey: a " +
            "tap cannot enter a character, and the relay will not forge the chat.",
    )

    fun transform(
        id: String,
        direction: RelayDirection,
        packet: ByteArray,
        ctx: ModuleContext = ModuleContext(),
    ): List<ByteArray> = when (id) {
        "xykell.automation.ghost" -> hideChat(direction, packet)
        "xykell.automation.no_break_delay" -> dropBreakDelayFlag(direction, packet)
        "xykell.automation.auto_eat" -> PlayerModules.learnItemCooldown(direction, packet, ctx)
        "xykell.automation.auto_fish" -> noteBite(direction, packet, ctx)
        else -> listOf(packet) // not in IMPLEMENTED: forward untouched
    }

    /**
     * Ghost hides chat from a screen recording. Dropping the chat packet at the
     * relay means the game never receives it, so it never draws the overlay.
     *
     * Text (0x09) is the one gameplay packet whose layout this repo verifies:
     * BedrockPackets.text() and BedrockPacketsTest both fix the body prefix as
     * `u8 needsTranslation, u8 category, u8 type`, all at fixed offsets because
     * nothing variable-length precedes them. So the type byte is read at a
     * known offset rather than guessed, and everything after it is ignored.
     * Types 1 and 7 are the chat and whisper classes the observation
     * translator already treats as chat; type 5 system chatter and the command
     * echo stay, because they also carry server notices that are not commands.
     *
     * Scoped to [RelayDirection.TO_CLIENT]: only the copy the server sends to the
     * game is dropped. The player's own outbound chat is the opposite
     * direction and passes through, so the relay never silences someone
     * typing.
     */
    private fun hideChat(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        val body = ModuleWire.bodyStart(packet) ?: return listOf(packet)
        if (ModuleWire.id(packet) != ID_TEXT) return listOf(packet)
        val type = ModuleWire.readByte(packet, body + TYPE_OFFSET) ?: return listOf(packet)
        return if (type.first == TYPE_CHAT || type.first == TYPE_WHISPER) {
            emptyList()
        } else {
            listOf(packet)
        }
    }

    /**
     * no_break_delay: remove `block_breaking_delay_enabled` (ordinal 48) from the
     * player's own PlayerAuthInput 0x90, so the server is never told to hold the
     * player back between block breaks.
     *
     * Scoped to [RelayDirection.TO_SERVER] (0x90 is server-bound). The list is
     * read through ModuleWire's optional-list reader — presence byte, varint
     * count, zigzag32 ordinals, the 1.26.40-and-later form — and a packet that
     * does not parse, or has no such ordinal, is forwarded byte-identical.
     */
    private fun dropBreakDelayFlag(direction: RelayDirection, packet: ByteArray): List<ByteArray> {
        if (direction != RelayDirection.TO_SERVER) return listOf(packet)
        if (ModuleWire.id(packet) != ID_PLAYER_AUTH_INPUT) return listOf(packet)
        val out = ModuleWire.setInputData(packet, emptyList(), listOf(ModuleWire.INPUT_BLOCK_BREAKING_DELAY_ENABLED))
        return if (out.contentEquals(packet)) listOf(packet) else listOf(out)
    }

    /**
     * auto_fish's packet arm: EntityEvent 0x1b event 13 (`fish_hook_hook`) is the
     * bobber biting, announced by the server, and it is the only bite signal that
     * is not a client-local particle. The event id sits at a fixed offset behind
     * the runtime id, whose width is measured rather than decoded, and the tick
     * is recorded so the plan can fire exactly on it.
     *
     * Scoped to [RelayDirection.TO_CLIENT] because that is the leg the server
     * announces on. Other players' bites ride the same packet and are not
     * filtered: their runtime id is a varint64 this repo does not decode.
     */
    private fun noteBite(direction: RelayDirection, packet: ByteArray, ctx: ModuleContext): List<ByteArray> {
        if (direction != RelayDirection.TO_CLIENT) return listOf(packet)
        if (ModuleWire.id(packet) != ID_ENTITY_EVENT) return listOf(packet)
        val body = ModuleWire.bodyStart(packet) ?: return listOf(packet)
        val idWidth = ModuleWire.varUIntSize(packet, body) ?: return listOf(packet)
        val (event, _) = ModuleWire.readByte(packet, body + idWidth) ?: return listOf(packet)
        if (event == ENTITY_EVENT_FISH_HOOK_HOOK) ctx.recordBite(ctx.tick)
        return listOf(packet)
    }

    /**
     * auto_eat's tap plan: the same cadence the PLAYER fast_eat id uses, because
     * it is the same behaviour — one use-item tap every
     * [ModuleContext.itemCooldownTicks] ticks. Two registry entries, one
     * mechanism; a second copy of the cadence would only be able to drift. Only
     * the tap point differs, so each id owns its own.
     */
    fun autoEatPlan(ctx: ModuleContext, random: Random): List<MacroStep> =
        PlayerModules.eatPlan(ctx, random, AUTO_EAT_POINT)

    /**
     * auto_fish's tap plan: one reel tap on the tick the bite was announced, and
     * nothing on any other tick. The cast stays the player's own tap — the relay
     * has no way to know the rod is loaded.
     */
    fun autoFishPlan(ctx: ModuleContext, random: Random): List<MacroStep> {
        val bite = ctx.lastBiteTick ?: return emptyList()
        if (ctx.tick != bite) return emptyList()
        return TapPlan.once(ctx, random, AUTO_FISH_POINT)
    }
}
