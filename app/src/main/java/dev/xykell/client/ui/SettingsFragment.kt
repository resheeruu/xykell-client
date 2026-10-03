package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.NativeSettings
import dev.xykell.client.R
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Real Settings experience (Batch 7) bound to the native settings
 * catalog. Sections derive from catalog entries; every control commits
 * through validated native set (rejections surface as error text, the
 * row keeps its committed value). Sections with no catalog settings
 * show an honest unavailable note — never fake toggles.
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private data class Row(
        val section: String,
        val key: String,
        val type: String,
        val min: Double,
        val max: Double,
        val options: List<String>,
        val description: String,
    )

    private lateinit var container: LinearLayout
    private lateinit var error: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.settings_container)
        error = view.findViewById(R.id.settings_error)
        view.findViewById<Button>(R.id.settings_reset_all).setOnClickListener {
            if (NativeSettings.reset(NativeSettings.root(requireContext()))) {
                reload("")
            } else {
                error.text = "Reset failed (native bridge unavailable?)"
            }
        }
        view.findViewById<SearchView>(R.id.settings_search)
            .setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?): Boolean = true
                override fun onQueryTextChange(q: String?): Boolean {
                    reload(q.orEmpty())
                    return true
                }
            })
        reload("")
    }

    private fun loadRows(): List<Row> {
        val out = mutableListOf<Row>()
        try {
            val catalog = JSONObject(NativeSettings.getCatalog())
            val keys = catalog.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = catalog.optJSONObject(id) ?: continue
                val opts = mutableListOf<String>()
                val arr = o.optJSONArray("options")
                if (arr != null) {
                    for (i in 0 until arr.length()) opts.add(arr.optString(i))
                }
                out.add(
                    Row(
                        section = o.optString("section"),
                        key = o.optString("key"),
                        type = o.optString("type"),
                        min = o.optDouble("min", 0.0),
                        max = o.optDouble("max", 0.0),
                        options = opts,
                        description = o.optString("description"),
                    ),
                )
            }
        } catch (e: Exception) {
            // Catalog unavailable: honest empty state below.
        }
        return out.sortedWith(compareBy({ it.section }, { it.key }))
    }

    private fun currentValues(): JSONObject {
        return try {
            JSONObject(NativeSettings.getValues(NativeSettings.root(requireContext())))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun reload(filter: String) {
        container.removeAllViews()
        error.text = ""
        val rows = loadRows().filter {
            filter.isBlank() ||
                it.key.contains(filter, true) ||
                it.description.contains(filter, true) ||
                it.section.contains(filter, true)
        }
        if (rows.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = "No settings available (native bridge missing?)"
            container.addView(empty)
            return
        }
        val values = currentValues()
        var lastSection = ""
        for (row in rows) {
            val display = SettingRowMapper.displaySection(row.section)
            if (display != lastSection) {
                lastSection = display
                container.addView(sectionHeader(display, row.section))
            }
            container.addView(settingRow(row, values))
        }
        for (missing in listOf("Appearance", "Modules", "Controls", "Performance", "Advanced", "About")) {
            if (rows.none { SettingRowMapper.displaySection(it.section) == missing }) {
                val note = TextView(requireContext())
                note.text = "$missing\nNOT WIRED — no settings declared for this section yet."
                container.addView(note)
            }
        }
    }

    private fun sectionHeader(display: String, native: String): View {
        val context = requireContext()
        val box = LinearLayout(context)
        box.orientation = LinearLayout.HORIZONTAL
        val title = TextView(context)
        title.text = display
        title.textSize = 18f
        val reset = Button(context)
        reset.text = "RESET"
        reset.setOnClickListener {
            if (NativeSettings.reset(NativeSettings.root(context), native)) {
                reload("")
            } else {
                error.text = "Section reset failed"
            }
        }
        box.addView(title)
        box.addView(reset)
        return box
    }

    private fun settingRow(row: Row, values: JSONObject): View {
        val context = requireContext()
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        val label = TextView(context)
        label.text = "${row.key}\n${row.description} [${row.section}]"
        box.addView(label)
        val current = values.optJSONObject(row.section)
        when (SettingRowMapper.rowKind(row.type, row.min, row.max, row.options.size)) {
            SettingRowMapper.RowKind.TOGGLE -> {
                val sw = Switch(context)
                sw.isChecked = current?.optBoolean(row.key, rowDefaultBool(row))
                    ?: rowDefaultBool(row)
                sw.setOnCheckedChangeListener { _, checked ->
                    if (!NativeSettings.set(
                            NativeSettings.root(context), row.section, row.key,
                            if (checked) "true" else "false",
                        )
                    ) {
                        sw.isChecked = !checked
                        error.text = "Rejected: ${row.section}.${row.key}"
                    } else {
                        error.text = ""
                    }
                }
                box.addView(sw)
            }
            SettingRowMapper.RowKind.SLIDER_INT, SettingRowMapper.RowKind.SLIDER_DOUBLE -> {
                val isInt = SettingRowMapper.rowKind(row.type, row.min, row.max, row.options.size) ==
                    SettingRowMapper.RowKind.SLIDER_INT
                val cur = current?.optDouble(row.key, row.min) ?: row.min
                val status = TextView(context)
                val bar = SeekBar(context)
                bar.max = 1000
                bar.progress = (((cur - row.min) / (row.max - row.min)) * 1000)
                    .roundToInt().coerceIn(0, 1000)
                val render = {
                    val v = row.min + (row.max - row.min) * bar.progress / 1000.0
                    status.text = if (isInt) v.roundToInt().toString() else "%.2f".format(v)
                }
                render()
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) = render()
                    override fun onStartTrackingTouch(b: SeekBar?) = Unit
                    override fun onStopTrackingTouch(b: SeekBar?) {
                        val v = row.min + (row.max - row.min) * bar.progress / 1000.0
                        val json = if (isInt) v.roundToInt().toString() else v.toString()
                        if (!NativeSettings.set(
                                NativeSettings.root(context), row.section, row.key, json,
                            )
                        ) {
                            error.text = "Rejected: ${row.section}.${row.key}"
                        } else {
                            error.text = ""
                        }
                    }
                })
                box.addView(status)
                box.addView(bar)
            }
            SettingRowMapper.RowKind.DROPDOWN -> {
                val spinner = Spinner(context)
                spinner.adapter = ArrayAdapter(
                    context, android.R.layout.simple_spinner_item, row.options,
                )
                val cur = current?.optString(row.key, row.options.firstOrNull() ?: "")
                spinner.setSelection(row.options.indexOf(cur).coerceAtLeast(0))
                box.addView(spinner)
                val apply = Button(context)
                apply.text = "APPLY"
                apply.setOnClickListener {
                    val chosen = row.options[spinner.selectedItemPosition]
                    if (!NativeSettings.set(
                            NativeSettings.root(context), row.section, row.key,
                            JSONObject.quote(chosen),
                        )
                    ) {
                        error.text = "Rejected: ${row.section}.${row.key}"
                    } else {
                        error.text = ""
                    }
                }
                box.addView(apply)
            }
            SettingRowMapper.RowKind.TEXT -> {
                val edit = EditText(context)
                edit.setText(current?.optString(row.key, "") ?: "")
                box.addView(edit)
                val apply = Button(context)
                apply.text = "APPLY"
                apply.setOnClickListener {
                    if (!NativeSettings.set(
                            NativeSettings.root(context), row.section, row.key,
                            JSONObject.quote(edit.text.toString()),
                        )
                    ) {
                        error.text = "Rejected: ${row.section}.${row.key}"
                    } else {
                        error.text = ""
                    }
                }
                box.addView(apply)
            }
        }
        val resetOne = Button(context)
        resetOne.text = "RESET THIS"
        resetOne.setOnClickListener {
            if (NativeSettings.reset(NativeSettings.root(context), row.section)) {
                reload("")
            } else {
                error.text = "Reset failed"
            }
        }
        box.addView(resetOne)
        return box
    }

    private fun rowDefaultBool(row: Row): Boolean {
        return try {
            val catalog = JSONObject(NativeSettings.getCatalog())
            val keys = catalog.keys()
            while (keys.hasNext()) {
                val o = catalog.optJSONObject(keys.next()) ?: continue
                if (o.optString("section") == row.section && o.optString("key") == row.key) {
                    return o.optBoolean("default", false)
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
