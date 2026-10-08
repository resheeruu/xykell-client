package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the overlay's only real logic: turning the native renderer's
 * line JSON into drawable lines.
 *
 * The rule these pin: a malformed or empty entry is dropped, never drawn at a
 * guessed position. A corrupt frame must show less, not something wrong.
 */
class HudOverlayLinesTest {

    private val good = """[
        {"text":"XYKELL","x":16.0,"y":48.0,"size":20.0,"color":4294967295},
        {"text":"hp: --","x":16.0,"y":80.0,"size":18.0,"color":4278255360}
    ]"""

    @Test
    fun parsesEveryFieldOfEveryLine() {
        val lines = HudOverlayLines.parse(good)
        assertEquals(2, lines.size)
        assertEquals("XYKELL", lines[0].text)
        assertEquals(16f, lines[0].x, 0f)
        assertEquals(48f, lines[0].y, 0f)
        assertEquals(20f, lines[0].size, 0f)
        assertEquals(0xFFFFFFFF.toInt(), lines[0].color)
        // 0xFF00FF00, i.e. opaque green: alpha must survive the double hop.
        assertEquals(0xFF00FF00.toInt(), lines[1].color)
    }

    @Test
    fun unavailableMarkerIsCarriedThroughNotRewritten() {
        // The renderer prints "--" for an unobserved value. The overlay must
        // pass that through verbatim -- turning it into 0 here would fabricate
        // a reading the relay never had.
        val lines = HudOverlayLines.parse("""[{"text":"hp: --","x":1,"y":2,"size":12,"color":1}]""")
        assertEquals(1, lines.size)
        assertEquals("hp: --", lines[0].text)
    }

    @Test
    fun emptyTextIsDropped() {
        val lines = HudOverlayLines.parse("""[{"text":"","x":1,"y":2,"size":12,"color":1},{"text":"a","x":1,"y":2,"size":12,"color":1}]""")
        assertEquals(1, lines.size)
        assertEquals("a", lines[0].text)
    }

    @Test
    fun malformedJsonYieldsNoLinesRatherThanACrash() {
        assertTrue(HudOverlayLines.parse("not json").isEmpty())
        assertTrue(HudOverlayLines.parse("{}").isEmpty())
        assertTrue(HudOverlayLines.parse("").isEmpty())
    }

    @Test
    fun nonFiniteCoordinatesAreDropped() {
        // JSON cannot carry NaN, so a coordinate arrives as null or absent.
        val lines = HudOverlayLines.parse(
            """[{"text":"a","x":null,"y":2,"size":12,"color":1},{"text":"b","y":2,"size":12,"color":1}]""",
        )
        assertTrue(lines.isEmpty())
    }

    @Test
    fun nonPositiveSizeIsClampedNotTrusted() {
        val lines = HudOverlayLines.parse("""[{"text":"a","x":1,"y":2,"size":0,"color":1}]""")
        assertEquals(1, lines.size)
        assertTrue("a zero-size glyph would be invisible or crash the paint", lines[0].size > 0f)
    }

    @Test
    fun missingColorFallsBackToWhite() {
        val lines = HudOverlayLines.parse("""[{"text":"a","x":1,"y":2,"size":12}]""")
        assertEquals(0xFFFFFFFF.toInt(), lines[0].color)
    }

    @Test
    fun aPartlyCorruptArrayKeepsItsGoodLines() {
        val lines = HudOverlayLines.parse(
            """[{"text":"good","x":1,"y":2,"size":12,"color":1}, 7, {"text":"bad","x":null,"y":2,"size":12,"color":1}, {"text":"also good","x":3,"y":4,"size":12,"color":1}]""",
        )
        assertEquals(2, lines.size)
        assertEquals("good", lines[0].text)
        assertEquals("also good", lines[1].text)
    }
}