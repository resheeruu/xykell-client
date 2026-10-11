package dev.xykell.client.runtime.command

/**
 * A local command console over the read-only observation stream.
 *
 * **What this is.** A parser and dispatcher for typed commands that answer
 * questions about what has been observed. `/status`, `/speed`, `/pos`. It runs
 * against [dev.xykell.client.runtime.observation.ObservedState] and returns
 * text for this device's screen.
 *
 * **What this is not.** The reference client in this category parses commands
 * out of *in-game chat* and replies *into* the game. That is a write path in
 * both directions, and this project's observation layer is deliberately
 * read-only, so that form is not implemented here — not as a policy decision
 * anyone has to take on trust, but because [dev.xykell.client.runtime.observation.ObservedState]
 * exposes no way to send anything, by construction.
 *
 * So the syntax is borrowed (a leading marker, name, arguments) and the effect
 * is not: every command here is a reader. A future automation command would
 * have to be added explicitly, would go through the same Advanced/Experimental
 * gate as every other automation, and would still not be able to reuse this
 * dispatcher for the write half without new plumbing.
 *
 * **Bounded.** Command names and arguments are length- and count-capped, the
 * registry is capped, and unknown commands return the name they did not
 * recognise rather than a generic failure — a typo is a normal thing to make.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
class CommandConsole(
    private val maxCommands: Int = MAX_COMMANDS,
    private val maxArgLength: Int = MAX_ARG_LENGTH,
    private val maxArgs: Int = MAX_ARGS,
) {

    init {
        require(maxCommands in 1..MAX_COMMANDS) { "maxCommands out of range" }
    }

    /** What a command produced. */
    data class Reply(
        val text: String,
        /** True when the command was recognised and ran. */
        val ok: Boolean,
        /** Set when the command exists but was called wrongly. */
        val usage: String? = null,
    )

    /** A registered command. [handler] receives already-trimmed arguments. */
    class Command(
        val name: String,
        val summary: String,
        val usage: String,
        private val handler: (List<String>) -> String?,
    ) {
        /** Runs the command. A null result means "called wrongly". */
        fun invoke(args: List<String>): String? = handler(args)
    }

    private val commands = LinkedHashMap<String, Command>()

    /** Registered names, in registration order. */
    fun names(): List<String> = commands.keys.toList()

    fun has(name: String): Boolean = commands.containsKey(name.lowercase())

    /** Registers a command. Duplicate names are refused, not overwritten. */
    fun register(command: Command): Boolean {
        // Validated as written: lowercasing first would quietly accept a
        // mis-cased name, so a command declared "UPPER" would register under a
        // different spelling than the one in the source.
        if (!isValidName(command.name)) return false
        val key = command.name
        synchronized(this) {
            if (commands.size >= maxCommands && !commands.containsKey(key)) return false
            if (commands.containsKey(key)) return false
            commands[key] = command
        }
        return true
    }

    /**
     * Runs [line] and returns what to display.
     *
     * A blank line returns blank rather than an error. An unrecognised name
     * returns that name, because "unknown command: /spped" is more useful than
     * a generic failure when the typo is the whole problem.
     */
    fun execute(line: String): Reply {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return Reply("", true)
        val body = trimmed.removePrefix(PREFIX).trimStart()
        if (body.isEmpty()) return helpReply()

        // maxArgs + 2 because the first part is the command name: taking only
        // maxArgs + 1 would silently discard the overflow and the cap could
        // never fire.
        val parts = body.split(' ', '\t')
            .filter { it.isNotEmpty() }
            .take(maxArgs + 2)
        if (parts.isEmpty()) return helpReply()
        val name = parts[0].lowercase()
        val args = parts.drop(1).map { it.take(maxArgLength) }

        val command = synchronized(this) { commands[name] }
            ?: return Reply("unknown command: ${parts[0]}", false)
        if (args.size > maxArgs) {
            return Reply(command.usage, false, command.usage)
        }
        return try {
            val result = command.invoke(args)
            if (result == null) Reply(command.usage, false, command.usage) else Reply(result, true)
        } catch (e: Exception) {
            // A command that throws is a bug in the command, not a reason to
            // take the console down.
            Reply("${command.name} failed: ${e.message ?: "unknown"}", false)
        }
    }

    /** Command list, one per line, for `help`. */
    fun helpText(): String = synchronized(this) {
        commands.values.joinToString("\n") { "${PREFIX}${it.name} — ${it.summary}" }
            .ifEmpty { "no commands registered" }
    }

    private fun helpReply(): Reply = Reply("Commands:\n${helpText()}", true)

    /** Formats an argument count the way a usage line should read. */
    fun usageFor(name: String): String? =
        synchronized(this) { commands[name.lowercase()]?.usage }

    companion object {
        /** Leading marker. Deliberately not the game's `.` chat prefix. */
        const val PREFIX = "/"

        const val MAX_COMMANDS = 64
        const val MAX_ARG_LENGTH = 256
        const val MAX_ARGS = 8

        /** Names stay lowercase alphanumerics plus `-` and `_`. */
        fun isValidName(name: String): Boolean {
            if (name.isEmpty() || name.length > 32) return false
            return name.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '_' }
        }
    }
}
