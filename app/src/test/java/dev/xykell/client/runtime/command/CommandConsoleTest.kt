package dev.xykell.client.runtime.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the local command console.
 *
 * The properties that matter: an unknown name reports the name (a typo is the
 * usual problem), a command that throws is contained rather than fatal, and
 * nothing accepts unbounded input.
 */
class CommandConsoleTest {

    private fun console(): CommandConsole {
        val c = CommandConsole()
        c.register(
            CommandConsole.Command("echo", "repeat an argument", "/echo <text>") { args ->
                if (args.isEmpty()) null else args.joinToString(" ")
            },
        )
        c.register(
            CommandConsole.Command("fail", "always throws", "/fail") { _ ->
                throw IllegalStateException("boom")
            },
        )
        return c
    }

    @Test
    fun `a command runs and returns its text`() {
        val r = console().execute("/echo hello world")
        assertTrue(r.ok)
        assertEquals("hello world", r.text)
    }

    @Test
    fun `the leading marker is optional`() {
        assertTrue(console().execute("echo hi").ok)
    }

    @Test
    fun `an unknown command names itself`() {
        // More useful than a generic failure when the typo is the whole problem.
        val r = console().execute("/spped 3")
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("spped"))
    }

    @Test
    fun `a blank line is blank, not an error`() {
        val r = console().execute("   ")
        assertTrue(r.ok)
        assertEquals("", r.text)
    }

    @Test
    fun `a bare prefix lists the commands`() {
        val r = console().execute("/")
        assertTrue(r.ok)
        assertTrue(r.text, r.text.contains("/echo"))
        assertTrue(r.text, r.text.contains("/fail"))
    }

    @Test
    fun `a command called wrongly returns its usage`() {
        val r = console().execute("/echo")
        assertFalse(r.ok)
        assertEquals("/echo <text>", r.usage)
        assertEquals("/echo <text>", r.text)
    }

    @Test
    fun `a throwing command is contained`() {
        val r = console().execute("/fail")
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("boom"))
        // The console still works afterwards.
        assertTrue(console().execute("/echo still here").ok)
    }

    @Test
    fun `names are case-insensitive`() {
        assertTrue(console().execute("/ECHO hi").ok)
    }

    @Test
    fun `a duplicate name is refused rather than overwritten`() {
        val c = console()
        assertFalse(
            c.register(CommandConsole.Command("echo", "different", "/echo") { "clobbered" }),
        )
        assertEquals("mine", c.execute("/echo mine").text)
    }

    @Test
    fun `invalid names are refused`() {
        val c = CommandConsole()
        for (bad in listOf("", "UPPER", "has space", "dot.name", "a".repeat(33))) {
            assertFalse("must refuse '$bad'", c.register(
                CommandConsole.Command(bad, "s", "/$bad") { "x" },
            ))
        }
        assertTrue(c.names().isEmpty())
    }

    @Test
    fun `argument count is capped and reports usage`() {
        val c = CommandConsole(maxArgs = 2)
        c.register(CommandConsole.Command("join", "join args", "/join <a> <b>") {
            it.joinToString("|")
        })
        assertEquals("a|b", c.execute("/join a b").text)
        val over = c.execute("/join a b c d")
        assertFalse(over.ok)
        assertEquals("/join <a> <b>", over.usage)
    }

    @Test
    fun `argument length is capped`() {
        val c = CommandConsole(maxArgLength = 8)
        c.register(CommandConsole.Command("echo", "echo", "/echo") { it.joinToString(" ") })
        assertEquals(8, c.execute("/echo " + "x".repeat(50)).text.length)
    }

    @Test
    fun `the registry is capped`() {
        val c = CommandConsole(maxCommands = 3)
        for (i in 0 until 10) {
            c.register(CommandConsole.Command("c$i", "s", "/c$i") { "ok" })
        }
        assertEquals(3, c.names().size)
    }

    @Test
    fun `an out-of-range bound is rejected at construction`() {
        var threw = false
        try {
            CommandConsole(maxCommands = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `help reports when nothing is registered`() {
        assertTrue(CommandConsole().helpText().contains("no commands registered"))
    }

    @Test
    fun `usage is retrievable by name`() {
        val c = console()
        assertEquals("/echo <text>", c.usageFor("echo"))
        assertEquals("/echo <text>", c.usageFor("ECHO"))
        assertEquals(null, c.usageFor("nope"))
    }

    @Test
    fun `tabs and runs of spaces separate arguments`() {
        assertEquals("a b", console().execute("/echo\ta   b").text)
    }

    @Test
    fun `the marker is a slash, not the game chat dot`() {
        // The distinction is the whole point: nothing here can reach the game.
        assertEquals("/", CommandConsole.PREFIX)
    }
}