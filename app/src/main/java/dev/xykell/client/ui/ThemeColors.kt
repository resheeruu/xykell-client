package dev.xykell.client.ui

import org.json.JSONObject

/**
 * Parsed semantic theme tokens (Batch 10). Pure Kotlin: no Android
 * framework, fully JVM-testable. Hex colors are kept as their original
 * `#RRGGBB` strings for display and parsed to ARGB ints only when a view
 * needs them. Malformed input never throws — callers fall back to
 * [DEFAULT], which mirrors the native Xykell Dark builtin and the app's
 * compiled resource colors.
 */
data class ThemeColors(
    val name: String,
    val background: String,
    val surface: String,
    val elevated: String,
    val accent: String,
    val text: String,
    val muted: String,
    val border: String,
    val hudAccent: String,
    val warning: String,
    val error: String,
    val success: String,
    val supported: String,
    val partial: String,
    val unavailable: String,
    val opacity: Double,
    val radius: Double,
) {
    /** Which semantic token a view color maps to while re-theming. */
    enum class Role {
        BACKGROUND, SURFACE, ELEVATED, ACCENT, TEXT, MUTED, BORDER, HUD_ACCENT
    }

    fun token(role: Role): String = when (role) {
        Role.BACKGROUND -> background
        Role.SURFACE -> surface
        Role.ELEVATED -> elevated
        Role.ACCENT -> accent
        Role.TEXT -> text
        Role.MUTED -> muted
        Role.BORDER -> border
        Role.HUD_ACCENT -> hudAccent
    }

    /** ARGB for a role; a malformed token resolves to [Role.TEXT]'s color
     *  only when it is itself valid, else opaque black — never a crash. */
    fun tokenArgb(role: Role): Int =
        parseHex(token(role)) ?: parseHex(text) ?: 0xFF000000.toInt()

    companion object {
        /** Mirrors native ui::Theme defaults (Xykell Dark == resource colors). */
        val DEFAULT = ThemeColors(
            name = "Xykell Dark",
            background = "#0D1526",
            surface = "#16213A",
            elevated = "#1E2A45",
            accent = "#4FD8C7",
            text = "#E8EEF7",
            muted = "#8A97AD",
            border = "#2A3A58",
            hudAccent = "#4FD8C7",
            warning = "#E8B34B",
            error = "#E05D5D",
            success = "#5DD39E",
            supported = "#5DD39E",
            partial = "#E8B34B",
            unavailable = "#8A97AD",
            opacity = 1.0,
            radius = 8.0,
        )

        /** `#RRGGBB` or `#AARRGGBB` → ARGB int. Null on anything else. */
        fun parseHex(hex: String): Int? {
            val h = hex.trim().removePrefix("#")
            if (h.length != 6 && h.length != 8) return null
            var value = 0L
            for (c in h) {
                val d = c.digitToIntOrNull(16) ?: return null
                value = (value shl 4) or d.toLong()
            }
            return if (h.length == 6) {
                (0xFF000000L or value).toInt()
            } else {
                value.toInt()
            }
        }

        /**
         * Parse serialized native theme tokens. Missing keys fall back to
         * the [DEFAULT] values (old JSON compatibility); malformed JSON or
         * a non-object returns null so callers keep their current palette.
         */
        fun from(json: String): ThemeColors? {
            val o = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            if (!o.has("name")) return null
            fun str(key: String, fallback: String): String {
                val v = o.optString(key, "")
                return if (v.isEmpty()) fallback else v
            }
            val opacity = o.optDouble("opacity", DEFAULT.opacity)
            val radius = o.optDouble("radius", DEFAULT.radius)
            return ThemeColors(
                name = str("name", DEFAULT.name),
                background = str("background", DEFAULT.background),
                surface = str("surface", DEFAULT.surface),
                elevated = str("elevated", DEFAULT.elevated),
                accent = str("accent", DEFAULT.accent),
                text = str("text", DEFAULT.text),
                muted = str("muted", DEFAULT.muted),
                border = str("border", DEFAULT.border),
                hudAccent = str("hudAccent", DEFAULT.hudAccent),
                warning = str("warning", DEFAULT.warning),
                error = str("error", DEFAULT.error),
                success = str("success", DEFAULT.success),
                supported = str("supported", DEFAULT.supported),
                partial = str("partial", DEFAULT.partial),
                unavailable = str("unavailable", DEFAULT.unavailable),
                opacity = opacity.coerceIn(0.0, 1.0),
                radius = radius.coerceIn(0.0, 32.0),
            )
        }

        /**
         * HSV -> ARGB, on the usual 0..1 / 0..360 / 0..360 / 0..1 scales.
         *
         * Themes are authored as hue/saturation/value triples because that is
         * how a user reasons about "the same accent, a bit dimmer" — shifting
         * one number instead of re-picking a hex. This is the standard
         * conversion, implemented from the published definition, and it is pure
         * arithmetic: no allocation, no Android, no native bridge, so it cannot
         * fail in a way the caller has to handle.
         *
         * Out-of-range inputs are coerced rather than rejected, because a theme
         * file with hue 400 should still render something sane. NaN coerces to
         * 0 through the comparison chain, which is the documented behaviour.
         */
        fun hsvToArgb(
            hueDegrees: Double,
            saturation: Double,
            value: Double,
            alpha: Double = 1.0,
        ): Int {
            val h = ((hueDegrees % 360.0) + 360.0) % 360.0
            val s = saturation.coerceIn(0.0, 1.0)
            val v = value.coerceIn(0.0, 1.0)
            val a = alpha.coerceIn(0.0, 1.0)

            val c = v * s
            val hp = h / 60.0
            val x = c * (1.0 - Math.abs(hp % 2.0 - 1.0))
            val m = v - c

            val (r, g, b) = when {
                hp < 1.0 -> Triple(c, x, 0.0)
                hp < 2.0 -> Triple(x, c, 0.0)
                hp < 3.0 -> Triple(0.0, c, x)
                hp < 4.0 -> Triple(0.0, x, c)
                hp < 5.0 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            val ri = ((r + m) * 255.0).toInt().coerceIn(0, 255)
            val gi = ((g + m) * 255.0).toInt().coerceIn(0, 255)
            val bi = ((b + m) * 255.0).toInt().coerceIn(0, 255)
            val ai = (a * 255.0).toInt().coerceIn(0, 255)
            return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        /** [hsvToArgb] rendered as `#RRGGBB`, matching the token format. */
        fun hsvToHex(
            hueDegrees: Double,
            saturation: Double,
            value: Double,
        ): String = "#%06X".format(hsvToArgb(hueDegrees, saturation, value) and 0xFFFFFF)

        /**
         * Role resolution for re-theming an already-rendered view tree:
         * a color that matched a token under [from] moves to the same token
         * under [to]; anything else (user-applied, system, unknown) is left
         * untouched. Returns null when the view should not change.
         */
        fun resolveRole(role: Role, from: ThemeColors, to: ThemeColors, current: Int): Int? {
            val fromArgb = parseHex(from.token(role)) ?: return null
            return if (current == fromArgb) parseHex(to.token(role)) else null
        }
    }
}

/**
 * This theme with [role] re-tinted from an HSV triple.
 *
 * Top level rather than a companion member so a caller in any package can write
 * `theme.withHsv(...)` without importing a companion. Accent moves the HUD
 * accent with it: a red accent beside a teal HUD is a bug that reads as a
 * rendering fault and is actually a missing line of code.
 */
fun ThemeColors.withHsv(
    role: ThemeColors.Role,
    hue: Double,
    saturation: Double,
    brightness: Double,
): ThemeColors {
    val hex = ThemeColors.hsvToHex(hue, saturation, brightness)
    return when (role) {
        ThemeColors.Role.BACKGROUND -> copy(background = hex)
        ThemeColors.Role.SURFACE -> copy(surface = hex)
        ThemeColors.Role.ELEVATED -> copy(elevated = hex)
        ThemeColors.Role.ACCENT -> copy(accent = hex, hudAccent = hex)
        ThemeColors.Role.TEXT -> copy(text = hex)
        ThemeColors.Role.MUTED -> copy(muted = hex)
        ThemeColors.Role.BORDER -> copy(border = hex)
        ThemeColors.Role.HUD_ACCENT -> copy(hudAccent = hex)
    }
}
