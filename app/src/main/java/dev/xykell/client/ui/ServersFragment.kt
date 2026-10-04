package dev.xykell.client.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.servers.ServerEntry
import dev.xykell.client.runtime.servers.ServerStore
import org.json.JSONObject
import java.io.File
import java.util.Date

/** Server book: a local, user-owned list with safe read-only
 *  TCP reachability checks, favorites, search, sort, notes,
 *  and SAF import/export. No packets are sent, no auth is
 *  touched, nothing is fetched from the network. */
class ServersFragment : Fragment(R.layout.fragment_servers) {

    private val importCode = 4102
    private val exportCode = 4103

    private var entries: List<ServerEntry> = emptyList()
    private var query = ""
    private var editingId: String? = null
    private var armedDeleteId: String? = null
    private var probing = false
    private var statusMap: Map<String, ServerStore.ProbeResult> = emptyMap()

    private fun storeFile(): File = File(requireContext().filesDir, "servers.json")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.servers_title).text =
            getString(R.string.servers_title)
        view.findViewById<TextView>(R.id.servers_body).text =
            getString(R.string.servers_body)
        view.findViewById<EditText>(R.id.servers_search).let { field ->
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString().orEmpty()
                    armedDeleteId = null
                    render()
                }
                override fun beforeTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int
                ) = Unit
                override fun onTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int
                ) = Unit
            })
        }
        view.findViewById<Button>(R.id.servers_add).setOnClickListener {
            saveFromForm()
        }
        view.findViewById<Button>(R.id.servers_cancel_edit).setOnClickListener {
            editingId = null
            clearForm()
            render()
        }
        view.findViewById<Button>(R.id.servers_probe_all).setOnClickListener {
            probeAll()
        }
        view.findViewById<Button>(R.id.servers_import).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, importCode)
        }
        view.findViewById<Button>(R.id.servers_export).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "xykell-servers.json")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, exportCode)
        }
        load()
        render()
    }

    private fun load() {
        entries = try {
            ServerStore.fromJson(storeFile().readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Atomic write: temp file then rename. */
    private fun save() {
        val file = storeFile()
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(ServerStore.toJson(entries))
        if (!tmp.renameTo(file)) {
            file.writeText(ServerStore.toJson(entries))
            tmp.delete()
        }
    }

    private fun saveFromForm() {
        val view = view ?: return
        val name = view.findViewById<EditText>(R.id.servers_name).text.toString()
        val address =
            view.findViewById<EditText>(R.id.servers_address).text.toString()
        val portText =
            view.findViewById<EditText>(R.id.servers_port).text.toString()
        val notes = view.findViewById<EditText>(R.id.servers_notes).text.toString()
        val port = portText.trim().ifEmpty {
            ServerStore.DEFAULT_PORT.toString()
        }.toIntOrNull() ?: -1
        val error = ServerStore.validate(name, address, port)
        if (error != null) {
            status(view, getString(R.string.servers_invalid, error))
            return
        }
        val editing = editingId
        if (editing != null) {
            val idx = entries.indexOfFirst { it.id == editing }
            if (idx >= 0) {
                val old = entries[idx]
                entries = entries.toMutableList().apply {
                    set(idx, old.copy(
                        name = name.trim(),
                        address = address.trim(),
                        port = port,
                        notes = notes.trim().take(ServerStore.MAX_NOTES)
                    ))
                }
                status(view, getString(R.string.servers_saved, name.trim()))
            }
            editingId = null
        } else {
            val created = System.currentTimeMillis()
            entries = entries + ServerEntry(
                id = ServerStore.newId(),
                name = name.trim(),
                address = address.trim(),
                port = port,
                notes = notes.trim().take(ServerStore.MAX_NOTES),
                createdAt = created
            )
            status(view, getString(R.string.servers_added, name.trim()))
        }
        save()
        clearForm()
        render()
    }

    private fun clearForm() {
        val view = view ?: return
        view.findViewById<EditText>(R.id.servers_name).text?.clear()
        view.findViewById<EditText>(R.id.servers_address).text?.clear()
        view.findViewById<EditText>(R.id.servers_port).text?.clear()
        view.findViewById<EditText>(R.id.servers_notes).text?.clear()
        view.findViewById<Button>(R.id.servers_add).text =
            getString(R.string.servers_add)
        view.findViewById<Button>(R.id.servers_cancel_edit).visibility =
            View.GONE
    }

    private fun startEdit(entry: ServerEntry) {
        val view = view ?: return
        editingId = entry.id
        view.findViewById<EditText>(R.id.servers_name).setText(entry.name)
        view.findViewById<EditText>(R.id.servers_address).setText(entry.address)
        view.findViewById<EditText>(R.id.servers_port).setText(entry.port.toString())
        view.findViewById<EditText>(R.id.servers_notes).setText(entry.notes)
        view.findViewById<Button>(R.id.servers_add).text =
            getString(R.string.servers_save)
        view.findViewById<Button>(R.id.servers_cancel_edit).visibility =
            View.VISIBLE
        status(view, getString(R.string.servers_editing, entry.name))
    }

    /** Read-only TCP reachability sweep on a background thread;
     *  results posted back per server id. */
    private fun probeAll() {
        val view = view ?: return
        if (probing) return
        probing = true
        armedDeleteId = null
        status(view, getString(R.string.servers_probe_checking))
        val snapshot = entries
        Thread {
            val results = mutableMapOf<String, ServerStore.ProbeResult>()
            for (entry in snapshot) {
                results[entry.id] = ServerStore.probe(
                    entry.address, entry.port, 3000
                )
            }
            view.post {
                statusMap = results
                probing = false
                render()
            }
        }.start()
    }

    private fun toggleFavorite(entry: ServerEntry) {
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx < 0) return
        val updated = entries.toMutableList().apply {
            set(idx, entry.copy(favorite = !entry.favorite))
        }
        entries = updated
        save()
        render()
    }

    private fun deleteEntry(entry: ServerEntry) {
        val view = view ?: return
        if (armedDeleteId == entry.id) {
            armedDeleteId = null
            entries = entries.filterNot { it.id == entry.id }
            statusMap = statusMap - entry.id
            save()
            status(view, getString(R.string.servers_deleted, entry.name))
            render()
        } else {
            armedDeleteId = entry.id
            render()
        }
    }

    private fun copyAddress(entry: ServerEntry) {
        val view = view ?: return
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            status(view, getString(R.string.servers_copy_unavailable))
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("server address", entry.displayAddress))
        val now = System.currentTimeMillis()
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            val updated = entries.toMutableList().apply {
                set(idx, entry.copy(lastUsed = now))
            }
            entries = updated
            save()
        }
        status(view, getString(R.string.servers_copied, entry.displayAddress))
        render()
    }

    private fun render() {
        val view = view ?: return
        val rows = view.findViewById<LinearLayout>(R.id.servers_rows)
        val empty = view.findViewById<TextView>(R.id.servers_empty)
        rows.removeAllViews()
        val visible = ServerStore.sort(ServerStore.filter(entries, query))
        if (visible.isEmpty()) {
            empty.text = if (query.trim().isEmpty()) {
                getString(R.string.servers_empty)
            } else {
                getString(R.string.servers_empty_search, query.trim())
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            for (entry in visible) {
                rows.addView(serverCard(entry))
            }
        }
    }

    private fun serverCard(entry: ServerEntry): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.setPadding(
            (12 * density).toInt(), (10 * density).toInt(),
            (12 * density).toInt(), (10 * density).toInt()
        )
        card.contentDescription = entry.name + " — " + entry.displayAddress

        val titleRow = LinearLayout(context)
        titleRow.orientation = LinearLayout.HORIZONTAL
        val name = TextView(context)
        name.text = entry.name
        name.textSize = 16f
        name.setTypeface(null, android.graphics.Typeface.BOLD)
        name.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        val statusText = TextView(context)
        statusText.text = probeLabel(statusMap[entry.id])
        statusText.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context, R.color.xykell_muted
            )
        )
        statusText.textSize = 12f
        titleRow.addView(name)
        titleRow.addView(statusText)
        card.addView(titleRow)

        val address = TextView(context)
        address.text = entry.displayAddress +
            if (entry.favorite) "  ★" else ""
        address.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context, R.color.xykell_accent
            )
        )
        address.textSize = 14f
        card.addView(address)

        if (entry.notes.isNotBlank()) {
            val notes = TextView(context)
            notes.text = entry.notes
            notes.textSize = 13f
            card.addView(notes)
        }

        val meta = TextView(context)
        meta.text = if (entry.lastUsed > 0L) {
            getString(
                R.string.servers_last_used,
                DateFormat.getDateFormat(context)
                    .format(Date(entry.lastUsed))
            )
        } else {
            getString(R.string.servers_never_used)
        }
        meta.textSize = 12f
        meta.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context, R.color.xykell_muted
            )
        )
        card.addView(meta)

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        val pad = (8 * density).toInt()
        actions.setPadding(0, pad, 0, 0)

        val fav = Button(context)
        fav.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        fav.minimumHeight = (48 * density).toInt()
        fav.text = if (entry.favorite) {
            getString(R.string.servers_unfavorite)
        } else {
            getString(R.string.servers_favorite)
        }
        fav.setOnClickListener { toggleFavorite(entry) }
        actions.addView(fav)

        val edit = Button(context)
        edit.layoutParams = fav.layoutParams
        edit.minimumHeight = (48 * density).toInt()
        edit.text = getString(R.string.servers_edit)
        edit.setOnClickListener { startEdit(entry) }
        actions.addView(edit)

        val copy = Button(context)
        copy.layoutParams = fav.layoutParams
        copy.minimumHeight = (48 * density).toInt()
        copy.text = getString(R.string.servers_copy)
        copy.setOnClickListener { copyAddress(entry) }
        actions.addView(copy)

        val del = Button(context)
        del.layoutParams = fav.layoutParams
        del.minimumHeight = (48 * density).toInt()
        del.text = if (armedDeleteId == entry.id) {
            getString(R.string.servers_delete_confirm, entry.name)
        } else {
            getString(R.string.servers_delete)
        }
        del.setOnClickListener { deleteEntry(entry) }
        actions.addView(del)

        card.addView(actions)
        return card
    }

    private fun probeLabel(result: ServerStore.ProbeResult?): String =
        when (result) {
            ServerStore.ProbeResult.OPEN -> getString(R.string.servers_status_open)
            ServerStore.ProbeResult.CLOSED -> getString(R.string.servers_status_closed)
            ServerStore.ProbeResult.TIMEOUT -> getString(R.string.servers_status_timeout)
            ServerStore.ProbeResult.INVALID -> getString(R.string.servers_status_invalid)
            null -> getString(R.string.servers_status_unknown)
        }

    private fun status(view: View, text: String) {
        view.findViewById<TextView>(R.id.servers_status).text = text
    }

    @Deprecated("Framework picker without new deps; result handled below")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val view = view ?: return
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == importCode) {
            try {
                val text = requireContext().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("empty file")
                val validShape = try {
                    JSONObject(text).optJSONArray("servers") != null
                } catch (e: Exception) {
                    false
                }
                val decoded = ServerStore.fromJson(text)
                if (!validShape || decoded.isEmpty()) {
                    status(view, getString(R.string.servers_import_rejected))
                    return
                }
                entries = decoded
                statusMap = emptyMap()
                save()
                render()
                status(view, getString(R.string.servers_imported, decoded.size))
            } catch (e: Exception) {
                status(view, getString(R.string.servers_import_rejected))
            }
        } else if (requestCode == exportCode) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(ServerStore.toJson(entries).toByteArray())
                }
                status(view, getString(R.string.servers_exported, uri.toString()))
            } catch (e: Exception) {
                status(view, getString(R.string.servers_export_failed))
            }
        }
    }
}
