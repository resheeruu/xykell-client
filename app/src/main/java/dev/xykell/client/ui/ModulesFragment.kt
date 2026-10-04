package dev.xykell.client.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeHud
import dev.xykell.client.NativeProfiles
import dev.xykell.client.R
import org.json.JSONObject

/**
 * Real registry browser (Batch 12): parses the same packaged
 * registry/features.json as native (build-time copy — never a second
 * catalog), groups by category, expands per-entry detail, and searches
 * across id/name/description/category. Profile-preference switches are
 * offered ONLY for SUPPORTED/PARTIAL entries (something exists to gate)
 * and are labeled as in-game profile preferences — this app runs no
 * modules. Statuses render verbatim; nothing is implied beyond them.
 */
class ModulesFragment : Fragment(R.layout.fragment_modules) {

    private class Holder(
        val entry: ModuleEntry,
        val row: LinearLayout,
        val detail: TextView,
        val toggle: Switch?,
    )

    private var entries: List<ModuleEntry> = emptyList()
    private var holders: List<Holder> = emptyList()
    private var headers: List<Pair<TextView, List<Holder>>> = emptyList()
    private var loadError: String? = null
    private var activeProfile: String? = null
    private var modulesJson: JSONObject? = null
    private var prefsAvailable = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRegistry()
        loadProfileState(view)
        build(view)
        view.findViewById<EditText>(R.id.modules_search)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter(view, s?.toString().orEmpty())
                }
            })
    }

    private fun loadRegistry() {
        try {
            val text = requireContext().assets.open("features.json")
                .bufferedReader().use { it.readText() }
            val parsed = ModuleEntry.parseAll(text)
            if (parsed == null) {
                loadError = getString(R.string.modules_unavailable)
            } else {
                entries = parsed
            }
        } catch (e: Exception) {
            loadError = getString(R.string.modules_unavailable) + " (${e.message})"
        }
    }

    /** Snapshot of the active profile's module flags (for switch state).
     *  Missing bridge/store leaves prefs unavailable — honestly. */
    private fun loadProfileState(view: View) {
        try {
            val root = NativeProfiles.root(requireContext())
            // Mirror ProfilesFragment: first open auto-creates Default.
            NativeProfiles.setActive(root, NativeProfiles.getActive(root))
            activeProfile = NativeProfiles.getActive(root).ifEmpty { null }
            val json = activeProfile?.let { NativeProfiles.getProfileJson(root, it) }
            modulesJson = json?.let { JSONObject(it).optJSONObject("modules") }
            prefsAvailable = activeProfile != null && modulesJson != null
            if (!prefsAvailable) {
                view.findViewById<TextView>(R.id.modules_status).text =
                    getString(R.string.modules_pref_unavailable)
            }
        } catch (e: UnsatisfiedLinkError) {
            activeProfile = null
            prefsAvailable = false
            view.findViewById<TextView>(R.id.modules_status).text =
                getString(R.string.modules_pref_unavailable)
        }
    }

    private fun build(view: View) {
        val sections = view.findViewById<LinearLayout>(R.id.modules_sections)
        sections.removeAllViews()
        if (loadError != null) {
            view.findViewById<TextView>(R.id.modules_counts).text = ""
            view.findViewById<TextView>(R.id.modules_status).text = loadError
            return
        }
        view.findViewById<TextView>(R.id.modules_counts).text = getString(
            R.string.modules_count_line,
            entries.size,
            ModuleEntry.groupByCategory(entries).size,
        )
        val density = resources.displayMetrics.density
        val builtHeaders = ArrayList<Pair<TextView, List<Holder>>>()
        val builtHolders = ArrayList<Holder>()
        for ((category, group) in ModuleEntry.groupByCategory(entries)) {
            val header = TextView(requireContext())
            header.text = category
            header.textSize = 15f
            header.setTextColor(resources.getColor(R.color.xykell_accent, null))
            header.setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
            sections.addView(header)
            val groupHolders = ArrayList<Holder>(group.size)
            for (entry in group) {
                val holder = buildRow(entry, density)
                sections.addView(holder.row)
                groupHolders.add(holder)
                builtHolders.add(holder)
            }
            builtHeaders.add(header to groupHolders)
        }
        headers = builtHeaders
        holders = builtHolders
        applyFilter(view, view.findViewById<EditText>(R.id.modules_search).text.toString())
    }

    private fun buildRow(entry: ModuleEntry, density: Float): Holder {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        row.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())

        val line1 = LinearLayout(context)
        line1.orientation = LinearLayout.HORIZONTAL
        val name = TextView(context)
        name.text = entry.name
        name.textSize = 16f
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        line1.addView(name)
        val status = TextView(context)
        status.text = entry.status
        status.textSize = 12f
        status.setTextColor(resources.getColor(statusColor(entry.status), null))
        line1.addView(status)
        row.addView(line1)

        val desc = TextView(context)
        desc.text = entry.description
        desc.textSize = 13f
        desc.setTextColor(resources.getColor(R.color.xykell_muted, null))
        row.addView(desc)

        val detail = TextView(context)
        detail.text = getString(
            R.string.modules_detail_format,
            entry.id,
            entry.implementation.ifEmpty { "—" },
            entry.riskLevel.ifEmpty { "—" },
            entry.capabilities.joinToString().ifEmpty { "—" },
            entry.settingKeys.joinToString().ifEmpty { "—" },
            entry.evidence.ifEmpty { "—" },
        )
        detail.textSize = 12f
        detail.setTextColor(resources.getColor(R.color.xykell_muted, null))
        detail.visibility = View.GONE
        row.addView(detail)

        var toggle: Switch? = null
        if (entry.supportsPreference) {
            val switch = Switch(context)
            switch.text = getString(R.string.modules_hint_switch_label)
            switch.textSize = 12f
            switch.contentDescription = getString(R.string.modules_switch_desc, entry.name)
            switch.isChecked = currentPreference(entry)
            switch.setOnClickListener {
                val on = switch.isChecked
                if (!persistPreference(entry, on)) {
                    switch.isChecked = !on
                }
            }
            row.addView(switch)
            toggle = switch
        }

        row.setOnClickListener {
            detail.visibility = if (detail.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        return Holder(entry, row, detail, toggle)
    }

    private fun statusColor(status: String): Int = when (status) {
        "SUPPORTED" -> R.color.xykell_success
        "PARTIAL" -> R.color.xykell_warning
        else -> R.color.xykell_muted
    }

    private fun currentPreference(entry: ModuleEntry): Boolean =
        modulesJson?.optBoolean(entry.id, false) ?: false

    /** Writes the flag into the ACTIVE profile through the validated HUD
     *  bridge. Returns false (with an honest status message) on failure. */
    private fun persistPreference(entry: ModuleEntry, enabled: Boolean): Boolean {
        val root = try {
            NativeProfiles.root(requireContext())
        } catch (e: Exception) {
            return false
        }
        val profile = activeProfile
        if (profile.isNullOrEmpty() || !prefsAvailable || modulesJson == null) {
            status(R.string.modules_pref_unavailable)
            return false
        }
        val ok = NativeHud.toggleModule(root, profile, entry.id, enabled)
        if (!ok) {
            status(getString(R.string.modules_pref_failed, entry.name))
            return false
        }
        modulesJson?.put(entry.id, enabled)
        status(
            getString(
                R.string.modules_pref_saved,
                entry.name,
                if (enabled) "ON" else "OFF",
            ),
        )
        return true
    }

    private fun status(text: String) {
        view?.findViewById<TextView>(R.id.modules_status)?.text = text
    }

    private fun status(resId: Int) {
        view?.findViewById<TextView>(R.id.modules_status)?.text = getString(resId)
    }

    private fun applyFilter(view: View, query: String) {
        if (loadError != null) return
        val matched = ModuleEntry.search(entries, query)
        val matchedIds = matched.mapTo(HashSet()) { it.id }
        for (holder in holders) {
            val visible = matchedIds.contains(holder.entry.id)
            holder.row.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) holder.detail.visibility = View.GONE
        }
        for ((header, groupHolders) in headers) {
            val any = groupHolders.any { matchedIds.contains(it.entry.id) }
            header.visibility = if (any) View.VISIBLE else View.GONE
        }
        val statusView = view.findViewById<TextView>(R.id.modules_status)
        if (matched.isEmpty() && query.isNotBlank()) {
            statusView.text = getString(R.string.modules_no_match, query)
        } else if (loadError == null && prefsAvailable && query.isBlank()) {
            statusView.text = ""
        }
    }
}
