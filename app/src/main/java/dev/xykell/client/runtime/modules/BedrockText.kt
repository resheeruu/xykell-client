package dev.xykell.client.runtime.modules

/**
 * Encoder for serverbound `Text 0x09`, the packet the client uses to say
 * something in chat.
 *
 * This is the one packet in the codebase the relay is allowed to *author*
 * rather than rewrite. That is a deliberate decision and it is scoped tightly:
 *
 *  - it is the user's own account, sending text the user configured in the
 *    module settings. Nothing is said on the user's behalf that they did not
 *    type into this app;
 *  - a chat message is the lowest-risk thing a client can send: it carries no
 *    attack, no interaction and no inventory claim, so unlike a forged
 *    InventoryTransaction it cannot desync a world or claim an action the
 *    server never granted;
 *  - the layout is not guessed. It mirrors `BedrockPackets.text()`, the decoder
 *    this repo already verifies, and the round-trip test pins the two against
 *    each other so a change on either side fails loudly.
 *
 * Everything else the relay still rewrites or drops. Forging a UseItem, an
 * Interact or a placement action stays out of scope; that is a different risk
 * class and the reasons in each category's IMPOSSIBLE map still say so.
 */
object BedrockText {

    /**
     * Chat category: a plain say that carries a source field.
     *
     * Chosen to match the shape this repo's own `BedrockPackets` decoder test
     * proves round-trips, rather than the category-0 variant. The encoder is
     * pinned to the decoder, so the two cannot drift apart silently.
     */
    private const val CATEGORY_SAY = 1

    /** Message type: ordinary chat, not whisper or announcement. */
    private const val TYPE_CHAT = 1

    /** Chat type used for commands, which are chat messages starting with "/". */
    private const val TYPE_COMMAND = 2

    const val MAX_MESSAGE_BYTES = 256

    /**
     * Build one chat packet.
     *
     * Returns null when the text is empty, over the length bound, or contains a
     * NUL -- a null byte would truncate the packet at the server's decoder and
     * desync the batch, so it is rejected rather than written.
     */
    fun chat(message: String): ByteArray? {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_MESSAGE_BYTES) return null
        if (trimmed.any { it.code == 0 }) return null
        val type = if (trimmed.startsWith("/")) TYPE_COMMAND else TYPE_CHAT
        return ModuleWire.build(
            ID_TEXT,
            // needsTranslation = 0: this text is literal, not a translation key.
            ModuleWire.byte(0),
            ModuleWire.byte(CATEGORY_SAY),
            ModuleWire.byte(type),
            // The source field, present because the category above asks for it.
            ModuleWire.writeVarString(""),
            ModuleWire.writeVarString(trimmed),
            // xboxUserId and platformChatId are empty for a first-party client;
            // the decoder reads both unconditionally, so they must be present.
            ModuleWire.writeVarString(""),
            ModuleWire.writeVarString(""),
            // Trailing optional-filtered flag. It must be written even though it
            // is false: the decoder reads this byte unconditionally, and a
            // truncated tail is what makes a packet get rejected wholesale.
            ModuleWire.byte(0),
        )
    }

    /** Text 0x09, the layout BedrockPackets.text() decodes. */
    const val ID_TEXT = 0x09
}