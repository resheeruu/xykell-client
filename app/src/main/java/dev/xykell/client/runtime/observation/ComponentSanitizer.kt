package dev.xykell.client.runtime.observation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders a Bedrock text component as inert plain text for local display.
 *
 * **Why this exists.** The relay is read-only: it forwards packets and mirrors
 * observed events, and the HUD only ever *shows* what it observed. That makes
 * the one thing a text component can carry which we have no use for — an
 * interactive payload — into a liability rather than a feature. A click event's
 * value is a URL chosen by whoever sent the message. Rendering that into the
 * HUD hands a display-only surface a link it was never supposed to follow, and
 * the hover payload is free-form structured data with the same problem.
 *
 * So the rule is simple and total: **interactive payloads are dropped, never
 * rendered and never executed.** Plain text, including from the interactive
 * component, is kept — dropping a whole message because someone made part of
 * it clickable would lose the player's actual words, which is the only reason
 * this data is on screen at all.
 *
 * **Why it is bounded.** These components arrive from a server we do not
 * control. `extra` is a nested list, and nothing in the protocol caps its depth
 * or width, so an unbounded recursive renderer is a stack-overflow and a
 * quadratic-output vector reachable by anyone who can send chat. Every walk here
 * is depth-, width- and length-capped, and hitting a cap degrades to truncated
 * text rather than throwing or looping.
 *
 * Pure Kotlin: no Android imports, host-testable.
 */
object ComponentSanitizer {

    /** Max nesting depth walked before the tail is dropped. */
    const val MAX_DEPTH = 16

    /** Max components visited across the whole tree, not per level. */
    const val MAX_NODES = 512

    /** Max `extra` siblings accepted at any one level. */
    const val MAX_SIBLINGS = 128

    /** Max characters of rendered output. */
    const val MAX_OUTPUT = 4096

    /** Keys carrying an interactive payload. Dropped wherever they appear. */
    private val INTERACTIVE_KEYS = setOf(
        "click_event", "clickEvent",
        "hover_event", "hoverEvent",
    )

    /** Keys that contribute literal text. First match wins, per component. */
    private val TEXT_KEYS = arrayOf("text", "raw", "message")

    /**
     * Sanitizes a component supplied as a JSON string.
     *
     * Returns the empty string for input we cannot parse: a display-only
     * surface that shows a raw parse error is worse than one that shows
     * nothing, and the caller has no way to distinguish a malformed component
     * from an empty one, which is fine — both render as nothing.
     */
    fun sanitize(json: String): String {
        if (json.isBlank() || json.length > MAX_RAW_JSON) return ""
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return ""
        }
        val budget = Budget()
        val out = StringBuilder()
        walk(root, out, budget, 0)
        if (budget.truncated) out.append(TRUNCATION_MARK)
        return out.toString().take(MAX_OUTPUT)
    }

    /**
     * Sanitizes an already-parsed component. A non-object is rendered as its
     * own string, which is how Bedrock represents a bare leaf.
     */
    fun sanitize(component: JSONObject): String {
        val budget = Budget()
        val out = StringBuilder()
        walk(component, out, budget, 0)
        if (budget.truncated) out.append(TRUNCATION_MARK)
        return out.toString().take(MAX_OUTPUT)
    }

    /**
     * Sanitizes plain text that is already inert.
     *
     * Control characters are stripped because this value is handed to a HUD
     * renderer: an unescaped control byte can reposition the cursor or clear a
     * line in a terminal-backed surface. Everything else is preserved.
     */
    fun sanitizePlain(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(minOf(text.length, MAX_OUTPUT))
        for (c in text) {
            if (out.length >= MAX_OUTPUT) {
                out.append(TRUNCATION_MARK)
                break
            }
            if (c.code == 0x7f || c.code < 0x20) continue
            out.append(c)
        }
        return out.toString()
    }

    /** True when the component tree carries an interactive payload anywhere. */
    fun hasInteractive(component: JSONObject): Boolean {
        val budget = Budget()
        return hasInteractive(component, budget, 0)
    }

    private fun walk(node: JSONObject, out: StringBuilder, budget: Budget, depth: Int) {
        if (budget.nodes >= MAX_NODES || depth > MAX_DEPTH) {
            budget.truncated = true
            return
        }
        budget.nodes++

        var emitted = false
        for (key in TEXT_KEYS) {
            val v = node.optString(key, "")
            if (v.isNotEmpty()) {
                out.append(sanitizePlain(v))
                emitted = true
                break
            }
        }
        // A component with no text key but with children is a wrapper; one with
        // neither is empty. Either way nothing is emitted for it here.

        if (depth == MAX_DEPTH) {
            // Depth cap reached with children still pending.
            if (node.has("extra")) budget.truncated = true
            return
        }

        val extra = node.optJSONArray("extra")
        if (extra != null && extra.length() > 0) {
            val n = minOf(extra.length(), MAX_SIBLINGS)
            if (extra.length() > n) budget.truncated = true
            for (i in 0 until n) {
                if (budget.nodes >= MAX_NODES) {
                    budget.truncated = true
                    return
                }
                val child = extra.optJSONObject(i)
                if (child != null) {
                    walk(child, out, budget, depth + 1)
                } else {
                    // Bedrock allows bare strings in `extra`.
                    val leaf = extra.optString(i, "")
                    if (leaf.isNotEmpty()) out.append(sanitizePlain(leaf))
                }
            }
        }
        if (!emitted && out.isEmpty() && node.length() == 0) return
    }

    private fun hasInteractive(node: JSONObject, budget: Budget, depth: Int): Boolean {
        if (budget.nodes >= MAX_NODES || depth > MAX_DEPTH) return false
        budget.nodes++
        for (key in INTERACTIVE_KEYS) {
            if (node.has(key)) return true
        }
        val extra: JSONArray? = node.optJSONArray("extra")
        if (extra != null) {
            val n = minOf(extra.length(), MAX_SIBLINGS)
            for (i in 0 until n) {
                val child = extra.optJSONObject(i) ?: continue
                if (hasInteractive(child, budget, depth + 1)) return true
            }
        }
        return false
    }

    private class Budget {
        var nodes = 0
        var truncated = false
    }

    /** Refuse to even parse an implausibly large raw document. */
    private const val MAX_RAW_JSON = 512 * 1024

    private const val TRUNCATION_MARK = "…"
}