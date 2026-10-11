package dev.xykell.client.runtime.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for relay protocol diagnostics.
 *
 * The property under test throughout: these diagnostics report what was
 * observed and never upgrade "I did not check" into "supported". A false
 * compatibility claim here would be worse than no claim, because a module
 * filtered on a believed-wrong id fails silently.
 */
class CodecDiagnosticsTest {

    @Test
    fun `sanity separates a real number from a misread`() {
        assertEquals(CodecDiagnostics.Verdict.PLAUSIBLE, CodecDiagnostics.sanity(800))
        assertEquals(CodecDiagnostics.Verdict.PLAUSIBLE, CodecDiagnostics.sanity(1))
        assertEquals(CodecDiagnostics.Verdict.NOT_A_VERSION, CodecDiagnostics.sanity(0))
        assertEquals(CodecDiagnostics.Verdict.NOT_A_VERSION, CodecDiagnostics.sanity(-3))
        assertEquals(
            CodecDiagnostics.Verdict.IMPLAUSIBLE,
            CodecDiagnostics.sanity(CodecDiagnostics.PLAUSIBLE_MAX + 1),
        )
    }

    @Test
    fun `no comparison before both legs report`() {
        assertNull(CodecDiagnostics.legComparison(null, null))
        assertNull(CodecDiagnostics.legComparison(800, null))
        assertNull(CodecDiagnostics.legComparison(null, 800))
    }

    @Test
    fun `matching legs compare equal`() {
        val c = CodecDiagnostics.legComparison(800, 800)!!
        assertTrue(c.agree)
        assertNull(c.reason)
        assertEquals(CodecDiagnostics.Verdict.PLAUSIBLE, c.deviceVerdict)
    }

    @Test
    fun `divergent legs are a mismatch, which is the whole point`() {
        val c = CodecDiagnostics.legComparison(800, 827)!!
        assertFalse(c.agree)
        assertEquals(800, c.device)
        assertEquals(827, c.upstream)
    }

    @Test
    fun `agreement on two implausible numbers is not agreement`() {
        // Otherwise the relay screen can render "matched" over garbage.
        val c = CodecDiagnostics.legComparison(0, 0)!!
        assertFalse(c.agree)
        assertTrue(c.reason!!.contains("implausible"))
    }

    @Test
    fun `summary names an unreported leg instead of hiding it`() {
        val s = CodecDiagnostics.summary(800, null)
        assertTrue(s, s.contains("device=800"))
        assertTrue(s, s.contains("upstream=unknown"))
        assertTrue(s, s.contains("device only"))
    }

    @Test
    fun `summary flags a mismatch loudly`() {
        val s = CodecDiagnostics.summary(800, 827)
        assertTrue(s, s.contains("MISMATCH"))
    }

    @Test
    fun `summary marks an invalid leg rather than printing it bare`() {
        val s = CodecDiagnostics.summary(0, 0)
        assertTrue(s, s.contains("0 (invalid)"))
        assertTrue(s, s.contains("MISMATCH"))
    }

    @Test
    fun `summary cites the vendored schema it came from`() {
        val s = CodecDiagnostics.summary(800, 800)
        assertTrue(s, s.contains(CodecDiagnostics.VENDORED_SCHEMA_VERSION))
        assertTrue(s, s.contains("matched"))
    }

    @Test
    fun `the packet table is not empty, so the summary's count means something`() {
        assertTrue(BedrockPacketIds.allNames.size > 100)
        assertTrue(
            CodecDiagnostics.summary(800, 800).contains("${BedrockPacketIds.allNames.size} packets"),
        )
    }

    @Test
    fun `the provenance string points at a file that exists in the repo`() {
        assertTrue(
            CodecDiagnostics.VENDORED_SCHEMA_SOURCE,
            CodecDiagnostics.VENDORED_SCHEMA_SOURCE.startsWith("scripts/protocol/vendor/"),
        )
        assertTrue(
            CodecDiagnostics.VENDORED_SCHEMA_SOURCE.endsWith("-proto.yml"),
        )
    }
}