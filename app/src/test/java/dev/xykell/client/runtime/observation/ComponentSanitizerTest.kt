package dev.xykell.client.runtime.observation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for Bedrock text-component sanitising.
 *
 * The properties that matter: an interactive payload never reaches the rendered
 * string, the words a player actually typed survive, and a hostile nesting
 * shape terminates instead of overflowing the stack.
 */
class ComponentSanitizerTest {

    @Test
    fun `plain text passes through`() {
        assertEquals("hello", ComponentSanitizer.sanitize("""{"text":"hello"}"""))
    }

    @Test
    fun `a click event's url never reaches the rendered text`() {
        val json = """
            {"text":"click me","click_event":{"action":"open_url","value":"https://evil.example"}}
        """.trimIndent()
        val out = ComponentSanitizer.sanitize(json)
        assertEquals("click me", out)
        assertFalse(out.contains("evil.example"))
        assertFalse(out.contains("open_url"))
    }

    @Test
    fun `a hover event payload never reaches the rendered text`() {
        val json = """
            {"text":"hover","hover_event":{"action":"show_item",
             "contents":{"id":"minecraft:diamond","count":64}}}
        """.trimIndent()
        val out = ComponentSanitizer.sanitize(json)
        assertEquals("hover", out)
        assertFalse(out.contains("diamond"))
    }

    @Test
    fun `interactive payloads are dropped from children too`() {
        val json = """
            {"text":"a","extra":[{"text":"b","click_event":{"action":"open_url","value":"x"}}]}
        """.trimIndent()
        val out = ComponentSanitizer.sanitize(json)
        assertEquals("ab", out)
        assertFalse(out.contains("open_url"))
    }

    @Test
    fun `extra children concatenate in order`() {
        val json = """
            {"text":"one ","extra":[{"text":"two "},{"text":"three"}]}
        """.trimIndent()
        assertEquals("one two three", ComponentSanitizer.sanitize(json))
    }

    @Test
    fun `bare string leaves in extra are kept`() {
        val json = """{"text":"a","extra":["b","c"]}"""
        assertEquals("abc", ComponentSanitizer.sanitize(json))
    }

    @Test
    fun `raw and message are accepted as text keys`() {
        assertEquals("r", ComponentSanitizer.sanitize("""{"raw":"r"}"""))
        assertEquals("m", ComponentSanitizer.sanitize("""{"message":"m"}"""))
    }

    @Test
    fun `malformed json renders as empty, never as an error`() {
        assertEquals("", ComponentSanitizer.sanitize("not json"))
        assertEquals("", ComponentSanitizer.sanitize("{"))
        assertEquals("", ComponentSanitizer.sanitize("[1,2,3]"))
        assertEquals("", ComponentSanitizer.sanitize(""))
        assertEquals("", ComponentSanitizer.sanitize("   "))
    }

    @Test
    fun `control characters are stripped from plain text`() {
        // An unescaped control byte can reposition a cursor in a terminal-backed
        // HUD surface, so it must not survive rendering.
        val out = ComponentSanitizer.sanitizePlain("a\u0007bc\u007Fd")
        assertEquals("abcd", out)
    }

    // Built recursively because objects and arrays interleave. A flat
    // open-then-close sequence closes every array before any object, which is
    // not valid JSON -- and an early parse failure also renders empty, so the
    // assertion would have passed for the wrong reason.
    private fun nest(depth: Int): String =
        if (depth <= 0) {
            """{"text":"x"}"""
        } else {
            """{"text":".","extra":[${nest(depth - 1)}]}"""
        }

    @Test
    fun `nesting at exactly the depth cap still renders whole`() {
        val out = ComponentSanitizer.sanitize(nest(ComponentSanitizer.MAX_DEPTH))
        assertFalse("the cap itself must not truncate", out.endsWith("\u2026"))
        assertTrue(out.endsWith("x"))
    }

    @Test
    fun `nesting one past the depth cap is marked truncated`() {
        val out = ComponentSanitizer.sanitize(nest(ComponentSanitizer.MAX_DEPTH + 1))
        assertTrue("one level over must truncate", out.endsWith("\u2026"))
    }

    @Test
    fun `deep nesting terminates and stays bounded`() {
        // 200 levels: well past the cap. This must return, not overflow.
        val out = ComponentSanitizer.sanitize(nest(200))
        assertTrue("expected a truncation mark", out.endsWith("\u2026"))
        assertTrue("must stay bounded", out.length < ComponentSanitizer.MAX_OUTPUT)
    }
}
