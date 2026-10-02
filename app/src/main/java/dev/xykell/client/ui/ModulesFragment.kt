package dev.xykell.client.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import org.json.JSONArray

/** Real registry browser: reads the same registry/features.json as native
 *  (packaged as a build-time asset — never a second catalog). Toggles are
 *  NOT offered here: research-required entries must not imply operation, and
 *  the native store bridge is not wired yet. Statuses shown verbatim. */
class ModulesFragment : Fragment(R.layout.fragment_modules) {

    private data class Entry(val id: String, val status: String)

    private var entries: List<Entry> = emptyList()
    private var loadError: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRegistry()
        val search = view.findViewById<EditText>(R.id.modules_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                render(view, s?.toString().orEmpty())
            }
        })
        render(view, "")
    }

    private fun loadRegistry() {
        try {
            val text = requireContext().assets.open("features.json")
                .bufferedReader().use { it.readText() }
            val arr: JSONArray = org.json.JSONObject(text).getJSONArray("features")
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.getString("id"), o.getString("status")))
            }
            entries = out
        } catch (e: Exception) {
            loadError = "Registry unavailable: ${e.message}"
        }
    }

    private fun render(view: View, query: String) {
        view.findViewById<TextView>(R.id.modules_title).text =
            "Modules (${entries.size})"
        val body = view.findViewById<TextView>(R.id.modules_body)
        if (loadError != null) {
            body.text = loadError
            return
        }
        val q = query.lowercase()
        val matched = if (q.isEmpty()) entries
            else entries.filter { it.id.lowercase().contains(q) }
        val counts = entries.groupingBy { it.status }.eachCount()
        val sb = StringBuilder()
        counts.toSortedMap().forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
        sb.append('\n')
        matched.take(50).forEach { sb.append(it.id).append(" — ").append(it.status).append('\n') }
        if (matched.size > 50) sb.append("…and ${matched.size - 50} more\n")
        sb.append("\nToggles live in-game once wired; statuses are authoritative.")
        body.text = sb.toString()
    }
}
