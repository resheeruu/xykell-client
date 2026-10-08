package dev.xykell.client.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * One rendered HUD line, exactly as the native renderer produced it.
 *
 * The renderer is the tested C++ one (`test_motion_hud` pins its text); this
 * only carries the result across JNI, so nothing here re-decides a value.
 */
data class HudLine(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    /** ARGB, ready for a Paint colour. */
    val color: Int,
)

/**
 * Parses the renderer's line JSON.
 *
 * Pure and Android-free so the overlay's only real logic is host-testable. A
 * malformed entry is dropped instead of drawn at a fabricated position: a
 * corrupt frame shows less, never something wrong.
 */
object HudOverlayLines {

    fun parse(json: String): List<HudLine> {
        val out = ArrayList<HudLine>()
        val arr: JSONArray = try {
            JSONArray(json)
        } catch (e: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text", "")
            if (text.isEmpty()) continue  // an empty line has nothing to draw
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            val size = o.optDouble("size", Double.NaN)
            if (!finite(x) || !finite(y) || !finite(size)) continue
            val color = o.optDouble("color", Double.NaN)
            out.add(
                HudLine(
                    text = text,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    // A non-positive size would be invisible or crash the paint;
                    // the renderer never emits one, so clamp rather than trust.
                    size = if (size > 0.0) size.toFloat() else 1f,
                    color = if (finite(color)) color.toLong().toInt() else 0xFFFFFFFF.toInt(),
                ),
            )
        }
        return out
    }

    private fun finite(v: Double): Boolean =
        v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY
}