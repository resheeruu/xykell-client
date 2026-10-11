package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Host JVM tests for theme token parsing and role resolution (Batch 10).
 * Pure logic: no Android framework. Malformed input must degrade to the
 * documented fallbacks, never throw and never invent colors.
 */
class ThemeColorsTest {

    private val dark = ThemeColors.DEFAULT

    private fun aurora(): ThemeColors = ThemeColors.from(
        """
        {"name":"Xykell Aurora","background":"#071210","surface":"#0D1F1C",
         "elevated":"#14322C","accent":"#3FE0A8","text":"#E2F5EC",
         "muted":"#7FA698","border":"#1B3A33","hudAccent":"#7C6CF0",
         "warning":"#E8B34B","error":"#E05D5D","success":"#5DD39E",
         "supported":"#5DD39E","partial":"#E8B34B","unavailable":"#8A97AD",
         "opacity":1.0,"radius":8.0}
        """.trimIndent(),
    ) ?: error("parse failed")

    @Test
    fun parseHex() {
        assertEquals(0xFF0D1526.toInt(), ThemeColors.parseHex("#0D1526"))
        assertEquals(0xFF0D1526.toInt(), ThemeColors.parseHex("0D1526"))
        assertEquals(0x804FD8C7.toInt(), ThemeColors.parseHex("#804FD8C7"))
        assertNull(ThemeColors.parseHex("#GGHHII"))
        assertNull(ThemeColors.parseHex("#12345"))
        assertNull(ThemeColors.parseHex(""))
        assertNull(ThemeColors.parseHex("#1234567"))
    }

    @Test
    fun parseCompleteTokens() {
        val a = aurora()
        assertEquals("Xykell Aurora", a.name)
        assertEquals("#3FE0A8", a.accent)
        assertEquals("#7C6CF0", a.hudAccent)
        assertEquals("#071210", a.background)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(ThemeColors.from("not json"))
        assertNull(ThemeColors.from("[1,2,3]"))
        assertNull(ThemeColors.from("{}")) // no name → not a theme payload
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        val legacy = ThemeColors.from("""{"name":"Xykell Dark"}""")
        assertNotNull(legacy)
        assertEquals(dark.background, legacy!!.background)
        assertEquals(dark.elevated, legacy.elevated)
        assertEquals(dark.hudAccent, legacy.hudAccent)
        assertEquals(dark.name, legacy.name)
    }

    @Test
    fun outOfRangeNumericTokensAreClamped() {
        val weird = ThemeColors.from("""{"name":"X","opacity":9.5,"radius":-3}""")
        assertNotNull(weird)
        assertEquals(1.0, weird!!.opacity, 0.0)
        assertEquals(0.0, weird.radius, 0.0)
    }

    @Test
    fun resolveRoleMovesOnlyMatchingColors() {
        val a = aurora()
        val currentBackground = ThemeColors.parseHex(dark.background)!!
        val next = ThemeColors.resolveRole(
            ThemeColors.Role.BACKGROUND, dark, a, currentBackground,
        )
        assertEquals(ThemeColors.parseHex(a.background), next)

        // A color that is not this token's baseline must not move.
        val userColor = 0xFFFFFFFF.toInt()
        assertNull(ThemeColors.resolveRole(ThemeColors.Role.BACKGROUND, dark, a, userColor))

        // Same palette → identity mapping (idempotent re-apply).
        assertEquals(
            ThemeColors.parseHex(dark.accent),
            ThemeColors.resolveRole(ThemeColors.Role.ACCENT, dark, dark,
                ThemeColors.parseHex(dark.accent)!!),
        )
    }

    @Test
    fun defaultMirrorsNativeDark() {
        // Guard against drift between the Kotlin fallback and the native
        // Xykell Dark builtin: the app's compiled resources equal these.
        assertEquals("#0D1526", dark.background)
        assertEquals("#4FD8C7", dark.accent)
        assertEquals("#E8EEF7", dark.text)
        assertEquals("#1E2A45", dark.elevated)
        assertEquals("#2A3A58", dark.border)
        assertNotEquals("", dark.name)
    }

    @Test
    fun tokenArgbNeverThrowsOnMalformedColor() {
        val broken = dark.copy(accent = "#zzz")
        // Falls back to parsed text color (valid), not a crash.
        assertEquals(ThemeColors.parseHex(dark.text), broken.tokenArgb(ThemeColors.Role.ACCENT))
        val allBroken = dark.copy(accent = "x", text = "y")
        assertEquals(0xFF000000.toInt(), allBroken.tokenArgb(ThemeColors.Role.ACCENT))
    }

    // --- HSV re-tint ------------------------------------------------------
    // Themes are authored as hue/sat/value triples, so the conversion is on the
    // critical path for every custom theme. The primary anchors below are the
    // ones a wrong conversion cannot produce by accident.

    @Test
    fun hsvPrimaryAnchors() {
        assertEquals(0xFFFF0000.toInt(), ThemeColors.hsvToArgb(0.0, 1.0, 1.0))
        assertEquals(0xFF00FF00.toInt(), ThemeColors.hsvToArgb(120.0, 1.0, 1.0))
        assertEquals(0xFF0000FF.toInt(), ThemeColors.hsvToArgb(240.0, 1.0, 1.0))
    }

    @Test
    fun hsvSecondaryAndNeutralAnchors() {
        assertEquals(0xFFFFFF00.toInt(), ThemeColors.hsvToArgb(60.0, 1.0, 1.0))
        assertEquals(0xFF00FFFF.toInt(), ThemeColors.hsvToArgb(180.0, 1.0, 1.0))
        assertEquals(0xFFFF00FF.toInt(), ThemeColors.hsvToArgb(300.0, 1.0, 1.0))
        // Zero saturation is a grey regardless of hue; zero value is black.
        assertEquals(0xFF808080.toInt(), ThemeColors.hsvToArgb(0.0, 0.0, 0.5019608))
        assertEquals(0xFF000000.toInt(), ThemeColors.hsvToArgb(0.0, 1.0, 0.0))
        assertEquals(0xFFFFFFFF.toInt(), ThemeColors.hsvToArgb(0.0, 0.0, 1.0))
    }

    @Test
    fun hsvAlphaIsHonoured() {
        assertEquals(0x80FF0000.toInt(), ThemeColors.hsvToArgb(0.0, 1.0, 1.0, 0.5019608))
        assertEquals(0x00FF0000.toInt(), ThemeColors.hsvToArgb(0.0, 1.0, 1.0, 0.0))
    }

    @Test
    fun hsvCoercesOutOfRangeInsteadOfThrowing() {
        // A theme file with an out-of-range hue must still render something.
        // 360 wraps to 0; 400 wraps to 40 (a yellow-orange), NOT to 0 -- an
        // implementation that clamped rather than wrapped would pass the first
        // and fail the second, which is exactly the distinction worth pinning.
        assertEquals(
            ThemeColors.hsvToArgb(0.0, 1.0, 1.0),
            ThemeColors.hsvToArgb(360.0, 1.0, 1.0),
        )
        assertEquals(
            ThemeColors.hsvToArgb(40.0, 1.0, 1.0),
            ThemeColors.hsvToArgb(400.0, 1.0, 1.0),
        )
        assertEquals(
            ThemeColors.hsvToArgb(350.0, 1.0, 1.0),
            ThemeColors.hsvToArgb(-10.0, 1.0, 1.0),
        )
        // Saturation, value and alpha clamp rather than wrapping. A negative value
        // clamps to opaque black, not to transparent black -- alpha is its own
        // axis and is not carried along.
        assertEquals(0xFFFF0000.toInt(), ThemeColors.hsvToArgb(0.0, 5.0, 5.0))
        assertEquals(0xFFFF0000.toInt(), ThemeColors.hsvToArgb(0.0, 1.0, 1.0, 9.0))
        assertEquals(0xFF000000.toInt(), ThemeColors.hsvToArgb(0.0, 1.0, -1.0))
    }

    @Test
    fun hsvToHexMatchesTheTokenFormat() {
        val hex = ThemeColors.hsvToHex(0.0, 1.0, 1.0)
        assertEquals("#FF0000", hex)
        assertEquals(7, hex.length)
        assertEquals("#00FF00", ThemeColors.hsvToHex(120.0, 1.0, 1.0))
    }

    @Test
    fun hsvRoundTripsThroughHexParse() {
        val argb = ThemeColors.hsvToArgb(210.0, 0.6, 0.9)
        val hex = ThemeColors.hsvToHex(210.0, 0.6, 0.9)
        assertEquals(argb, ThemeColors.parseHex(hex))
    }

    @Test
    fun withHsvReplacesTheRoleAndTheAccentPairTogether() {
        val retinted = dark.withHsv(ThemeColors.Role.ACCENT, 0.0, 1.0, 1.0)
        assertEquals("#FF0000", retinted.accent)
        // Accent drives the HUD accent too; leaving them out of step would show
        // a red accent next to a teal HUD.
        assertEquals("#FF0000", retinted.hudAccent)
        // Unrelated tokens are untouched.
        assertEquals(dark.background, retinted.background)
        assertEquals(dark.text, retinted.text)
    }

    @Test
    fun withHsvCoversEveryRole() {
        for (role in ThemeColors.Role.values()) {
            val t = dark.withHsv(role, 120.0, 1.0, 1.0)
            assertEquals("#00FF00", t.token(role))
        }
    }
}
