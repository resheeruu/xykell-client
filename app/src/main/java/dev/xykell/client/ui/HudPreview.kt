package dev.xykell.client.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure HUD preview builder (Batch 8): layout JSON -> honest text preview
 * lines. No Android dependency: host JVM tests cover it. This is a
 * structural preview (names/positions/visibility), never a render claim.
 */
object HudPreview {

    data class Line(val text: String, val visible: Boolean)

    fun preview(layoutJson: String): List<Line> {
        val out = mutableListOf<Line>()
        try {
            val root = JSONObject(layoutJson)
            val elements: JSONArray = root.optJSONArray("elements") ?: return out
            for (i in 0 until elements.length()) {
                val o = elements.optJSONObject(i) ?: continue
                val type = o.optString("type", "unknown")
                val visible = o.optBoolean("visible", true)
                val x = o.optDouble("x", 0.0)
                val y = o.optDouble("y", 0.0)
                val scale = o.optDouble("scale", 1.0)
                out.add(Line("$type @(${x.toInt()},${y.toInt()}) x$scale", visible))
            }
        } catch (e: Exception) {
            // Malformed layout: empty preview, never a crash or fake rows.
        }
        return out
    }

    fun renderText(lines: List<Line>): String {
        val sb = StringBuilder()
        var shown = 0
        for (l in lines) {
            if (!l.visible) continue
            shown++
            sb.append(l.text).append('\n')
        }
        if (shown == 0) sb.append("(all elements hidden)\n")
        return sb.toString()
    }
}
