package dev.xykell.client.runtime.command

import dev.xykell.client.runtime.observation.ObservedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the built-in command set.
 *
 * The property throughout: a command whose subject was never observed says so.
 * Showing 0.00 for an unobserved position would read as a measurement the user
 * did not make, which is the one thing this project will not do.
 */
class ObservedCommandsTest {

    private fun console(state: ObservedState = ObservedState()) =
        ObservedCommands.create(state)

    private fun observed(
        x: Double? = 1.5, y: Double? = 64.0, z: Double? = -3.25, yaw: Double? = 90.0,
    ) = ObservedState().apply {
        onPlayerTravelled("e1", 1000L, x, y, z, yaw, 10.0, 1)
        onPlayerTravelled("e2", 2000L, x, y, z, yaw, 20.0, 1)
    }

    @Test
    fun `every built-in is registered`() {
        val names = console().names()
        for (expected in listOf("status", "pos", "speed", "chat", "limits", "help")) {
            assertTrue("missing $expected", names.contains(expected))
        }
    }

    @Test
    fun `pos reports unobserved rather than zero`() {
        val r = console().execute("/pos")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("no position observed"))
        // Explicitly not a fabricated 0.00.
        assertTrue("must not invent a coordinate", !r.text.contains("0.00"))
    }

    @Test
    fun `pos reports a real observed coordinate`() {
        val r = console(observed()).execute("/pos")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("1.50"))
        assertTrue(r.text, r.text.contains("-3.25"))
        assertTrue(r.text, r.text.contains("90.0"))
    }

    @Test
    fun `pos says when the coordinate was only partly observed`() {
        val r = console(observed(x = null)).execute("/pos")
        assertTrue(r.text, r.text.contains("incomplete"))
    }

    @Test
    fun `speed reports unknown until two samples exist`() {
        val one = ObservedState().apply {
            onPlayerTravelled("e1", 1000L, 1.0, 2.0, 3.0, 0.0, 10.0, 1)
        }
        val r = console(one).execute("/speed")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("unknown"))
    }

    @Test
    fun `speed reports unknown when both samples land in the same instant`() {
        // Two events in one millisecond give no interval, so there is no rate to
        // report. A zero would be a number with no evidence behind it.
        val frozen = ObservedState(clock = { 5_000L }).apply {
            onPlayerTravelled("e1", 1000L, 1.0, 2.0, 3.0, 0.0, 10.0, 1)
            onPlayerTravelled("e2", 1100L, 1.0, 2.0, 3.0, 0.0, 20.0, 1)
        }
        assertTrue(console(frozen).execute("/speed").text.contains("unknown"))
    }

    @Test
    fun `speed reports a real rate once two samples exist`() {
        // A clock that advances, so the interval between the two samples is
        // real rather than zero.
        var now = 1_000L
        val advancing = ObservedState(clock = { now }).apply {
            onPlayerTravelled("e1", 1000L, 1.0, 2.0, 3.0, 0.0, 10.0, 1)
            now = 2_000L
            onPlayerTravelled("e2", 2000L, 1.0, 2.0, 3.0, 0.0, 30.0, 1)
        }
        val r = console(advancing).execute("/speed")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("m/s"))
        assertTrue(r.text, r.text.contains("2 samples"))
    }

    @Test
    fun `status reports observation and relay honestly`() {
        val r = console().execute("/status")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("no movement observed"))
        assertTrue(r.text, r.text.contains("chat lines: 0"))
        assertTrue(r.text, r.text.contains("relay:"))
    }

    @Test
    fun `status reflects a recorded chat line`() {
        val s = ObservedState()
        s.onPlayerMessage("m1", 1000L, "alex", "hello")
        val r = console(s).execute("/status")
        assertTrue(r.text, r.text.contains("chat lines: 1"))
    }

    @Test
    fun `chat counts held lines`() {
        val s = ObservedState()
        s.onPlayerMessage("m1", 1000L, "alex", "a")
        s.onPlayerMessage("m2", 1100L, "alex", "b")
        assertEquals("2 line(s) held", console(s).execute("/chat").text)
    }

    @Test
    fun `limits reports the real decode policy`() {
        val r = console().execute("/limits")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("list<="))
        assertTrue(r.text, r.text.contains("packet<="))
    }

    @Test
    fun `help lists the commands`() {
        val r = console().execute("/help")
        assertTrue(r.ok)
        for (n in listOf("/status", "/pos", "/speed", "/limits")) {
            assertTrue("missing $n", r.text.contains(n))
        }
    }

    @Test
    fun `no command can reach the game`() {
        // The observation layer is read-only by construction, so the whole
        // read-only claim is enforced by the type rather than by review.
        val s: ObservedState = ObservedState()
        assertEquals(
            emptyList<Any>(),
            ObservedState::class.java.methods.filter { it.name.contains("send") || it.name.contains("write") }
                .map { it.name },
        )
        assertTrue(s.chat is List<*>)
    }
}