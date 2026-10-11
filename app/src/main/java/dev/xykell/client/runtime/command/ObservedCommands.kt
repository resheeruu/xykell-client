package dev.xykell.client.runtime.command

import dev.xykell.client.runtime.observation.ObservedState
import dev.xykell.client.runtime.relay.CodecDiagnostics
import dev.xykell.client.runtime.relay.DecodeLimits

/**
 * The built-in command set, bound to observed state.
 *
 * Every command here is a **reader**. There is no command that writes, because
 * [ObservedState] has no way to send anything — the observation layer is
 * read-only by construction, not by convention, so a write command cannot be
 * added here without first changing that type.
 *
 * The values are formatted from whatever was actually observed. A command whose
 * subject has not been observed reports that, rather than showing a zero or a
 * placeholder that would read as a real measurement.
 */
object ObservedCommands {

    /**
     * Builds a console with the built-ins registered.
     *
     * Relay facts arrive as parameters rather than being read from
     * [dev.xykell.client.runtime.relay.RelayService], because that type is an
     * Android `Service` and importing it would drag the framework into a class
     * that is meant to stay host-testable. Defaults are the "nothing observed"
     * case, so a caller with no relay still gets an honest console.
     */
    fun create(
        state: ObservedState = ObservedState(),
        relayRunning: Boolean = false,
        negotiatedProtocol: Int? = null,
    ): CommandConsole {
        val c = CommandConsole()

        c.register(
            CommandConsole.Command(
                "status", "observation and relay state", "/status",
            ) { _ ->
                val motion = state.motion
                buildString {
                    append("observation: ")
                    append(if (motion == null) "no movement observed" else "active")
                    append('\n')
                    append("chat lines: ").append(state.chatCount)
                    append('\n')
                    append("relay: ")
                        .append(if (relayRunning) "running" else "stopped")
                    if (negotiatedProtocol != null) {
                        append('\n').append(
                            CodecDiagnostics.summary(
                                negotiatedProtocol,
                                negotiatedProtocol,
                            ),
                        )
                    }
                }
            },
        )

        c.register(
            CommandConsole.Command(
                "pos", "last observed position", "/pos",
            ) { _ ->
                val m = state.motion
                when {
                    m == null -> "no position observed yet"
                    m.x == null || m.y == null || m.z == null ->
                        "position incomplete: only part was observed"
                    else -> "x %.2f  y %.2f  z %.2f".format(m.x, m.y, m.z) +
                        (m.yawDegrees?.let { "\nyaw %.1f".format(it) } ?: "")
                }
            },
        )

        c.register(
            CommandConsole.Command(
                "speed", "speed derived from observed travel", "/speed",
            ) { _ ->
                val s = state.speed
                when {
                    s == null -> "speed unknown: two travel samples are needed"
                    else -> "%.2f m/s (from %d samples at %d)".format(
                        s.metresPerSecond, s.samples, s.sampledAtMs,
                    )
                }
            },
        )

        c.register(
            CommandConsole.Command(
                "chat", "how many chat lines are held", "/chat",
            ) { _ -> "${state.chatCount} line(s) held" },
        )

        c.register(
            CommandConsole.Command(
                "limits", "active decode limits", "/limits",
            ) { _ -> DecodeLimits.DEFAULT.describe() },
        )

        c.register(
            CommandConsole.Command(
                "help", "list commands", "/help",
            ) { _ -> c.helpText() },
        )

        return c
    }
}
