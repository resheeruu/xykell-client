package dev.xykell.client.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.packs.PackEntry
import dev.xykell.client.runtime.packs.PackStore
import dev.xykell.client.runtime.packs.PackType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Pack book: a local, user-owned list with optional manifest.json
 *  import via SAF file picker. No Minecraft data is modified. */
class PacksFragment : Fragment(R.layout.fragment_packs) {

    private val importCode = 6101

    private var entries: List<PackEntry> = emptyList()
    private var query = ""
    private var editingId: String? = null
    private var armedDeleteId: String? = null

    private fun storeFile(): File = File(requireContext().filesDir, "packs.json")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.packs_title).text =
            getString(R.string.packs_title)
        view.findViewById<TextView>(R.id.packs_body).text =
            getString(R.string.packs_body)
        view.findViewById<EditText>(R.id.packs_search).let { field ->
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
        view.findViewById<Button>(R.id.packs_add).setOnClickListener {
            saveFromForm()
        }
        view.findViewById<Button>(R.id.packs_cancel_edit).setOnClickListener {
            editingId = null
            clearForm()
            render()
        }
        view.findViewById<Button>(R.id.packs_import).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/zip", "application/octet-stream"))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, importCode)
        }
        view.findViewById<Button>(R.id.packs_export).setOnClickListener {
            exportList()
        }
        load()
        render()
    }

    private fun load() {
        entries = try {
            PackStore.fromJson(storeFile().readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        val file = storeFile()
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(PackStore.toJson(entries))
        if (!tmp.renameTo(file)) {
            file.writeText(PackStore.toJson(entries))
            tmp.delete()
        }
    }

    private fun saveFromForm() {
        val view = view ?: return
        val name = view.findViewById<EditText>(R.id.packs_name).text.toString()
        val typeStr =
            view.findViewById<EditText>(R.id.packs_type).text.toString().uppercase()
        val version =
            view.findViewById<EditText>(R.id.packs_version).text.toString()
        val author =
            view.findViewById<EditText>(R.id.packs_author).text.toString()
        val notes = view.findViewById<EditText>(R.id.packs_notes).text.toString()
        val path = view.findViewById<EditText>(R.id.packs_path).text.toString()
        val type = try {
            PackType.valueOf(typeStr)
        } catch (e: Exception) {
            PackType.UNKNOWN
        }
        val error = PackStore.validate(name, type)
        if (error != null) {
            status(view, getString(R.string.packs_invalid, error))
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
                        type = type,
                        version = version.trim(),
                        author = author.trim().take(PackStore.MAX_AUTHOR),
                        notes = notes.trim().take(PackStore.MAX_NOTES),
                        path = path.trim()
                    ))
                }
                status(view, getString(R.string.packs_saved, name.trim()))
            }
            editingId = null
        } else {
            val created = System.currentTimeMillis()
            entries = entries + PackEntry(
                id = PackStore.newId(),
                name = name.trim(),
                type = type,
                version = version.trim(),
                author = author.trim().take(PackStore.MAX_AUTHOR),
                notes = notes.trim().take(PackStore.MAX_NOTES),
                path = path.trim(),
                createdAt = created
            )
            status(view, getString(R.string.packs_added, name.trim()))
        }
        save()
        clearForm()
        render()
    }

    private fun clearForm() {
        val view = view ?: return
        view.findViewById<EditText>(R.id.packs_name).text?.clear()
        view.findViewById<EditText>(R.id.packs_type).text?.clear()
        view.findViewById<EditText>(R.id.packs_version).text?.clear()
        view.findViewById<EditText>(R.id.packs_author).text?.clear()
        view.findViewById<EditText>(R.id.packs_notes).text?.clear()
        view.findViewById<EditText>(R.id.packs_path).text?.clear()
        view.findViewById<Button>(R.id.packs_add).text =
            getString(R.string.packs_add)
        view.findViewById<Button>(R.id.packs_cancel_edit).visibility =
            View.GONE
    }

    private fun startEdit(entry: PackEntry) {
        val view = view ?: return
        editingId = entry.id
        view.findViewById<EditText>(R.id.packs_name).setText(entry.name)
        view.findViewById<EditText>(R.id.packs_type).setText(entry.type.name)
        view.findViewById<EditText>(R.id.packs_version).setText(entry.version)
        view.findViewById<EditText>(R.id.packs_author).setText(entry.author)
        view.findViewById<EditText>(R.id.packs_notes).setText(entry.notes)
        view.findViewById<EditText>(R.id.packs_path).setText(entry.path)
        view.findViewById<Button>(R.id.packs_add).text =
            getString(R.string.packs_save)
        view.findViewById<Button>(R.id.packs_cancel_edit).visibility =
            View.VISIBLE
        status(view, getString(R.string.packs_editing, entry.name))
    }

    private fun exportList() {
        val view = view ?: return
        try {
            val json = dev.xykell.client.runtime.packs.PackStore.toJson(entries)
            val dir = File(requireContext().getExternalFilesDir(null), "Xykell")
            if (!dir.exists() && !dir.mkdirs()) {
                status(view, getString(R.string.packs_export_dir_failed))
                return
            }
            val file = File(dir, "xykell-packs.json")
            file.writeText(json)
            status(view, getString(R.string.packs_exported, file.absolutePath))
        } catch (e: Exception) {
            status(view, getString(R.string.packs_export_failed))
        }
    }

    private fun deleteEntry(entry: PackEntry) {
        val view = view ?: return
        if (armedDeleteId == entry.id) {
            armedDeleteId = null
            entries = entries.filterNot { it.id == entry.id }
            save()
            status(view, getString(R.string.packs_deleted, entry.name))
            render()
        } else {
            armedDeleteId = entry.id
            render()
        }
    }

    private fun render() {
        val view = view ?: return
        val rows = view.findViewById<LinearLayout>(R.id.packs_rows)
        val empty = view.findViewById<TextView>(R.id.packs_empty)
        rows.removeAllViews()
        val visible = dev.xykell.client.runtime.packs.PackStore.sort(
            dev.xykell.client.runtime.packs.PackStore.filter(entries, query)
        )
        if (visible.isEmpty()) {
            empty.text = if (query.trim().isEmpty()) {
                getString(R.string.packs_empty)
            } else {
                getString(R.string.packs_empty_search, query.trim())
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            for (entry in visible) {
                rows.addView(packCard(entry))
            }
        }
    }

    private fun packCard(entry: PackEntry): View {
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
        card.contentDescription = entry.name

        val titleRow = LinearLayout(context)
        titleRow.orientation = LinearLayout.HORIZONTAL
        val name = TextView(context)
        name.text = entry.name
        name.textSize = 16f
        name.setTypeface(null, android.graphics.Typeface.BOLD)
        name.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        val typeBadge = TextView(context)
        typeBadge.text = getString(R.string.packs_type_badge, entry.type.name)
        typeBadge.textSize = 14f
        typeBadge.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context, when (entry.type) {
                    dev.xykell.client.runtime.packs.PackType.RESOURCE -> R.color.xykell_accent
                    dev.xykell.client.runtime.packs.PackType.BEHAVIOR -> R.color.xykell_warning
                    else -> R.color.xykell_muted
                }
            )
        )
        titleRow.addView(name)
        titleRow.addView(typeBadge)
        card.addView(titleRow)

        if (entry.version.isNotBlank()) {
            val ver = TextView(context)
            ver.text = getString(R.string.packs_version_label, entry.version)
            ver.textSize = 13f
            ver.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context, R.color.xykell_accent
                )
            )
            card.addView(ver)
        }

        if (entry.author.isNotBlank()) {
            val auth = TextView(context)
            auth.text = getString(R.string.packs_author_label, entry.author)
            auth.textSize = 13f
            card.addView(auth)
        }

        if (entry.description.isNotBlank()) {
            val desc = TextView(context)
            desc.text = entry.description
            desc.textSize = 13f
            card.addView(desc)
        }

        if (entry.notes.isNotBlank()) {
            val notes = TextView(context)
            notes.text = entry.notes
            notes.textSize = 13f
            card.addView(notes)
        }

        if (entry.path.isNotBlank()) {
            val path = TextView(context)
            path.text = getString(R.string.path_label, entry.path.take(80))
            path.textSize = 12f
            path.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context, R.color.xykell_muted
                )
            )
            card.addView(path)
        }

        val meta = TextView(context)
        meta.text = if (entry.createdAt > 0L) {
            getString(
                R.string.packs_created,
                DateFormat.getDateFormat(context)
                    .format(java.util.Date(entry.createdAt))
            )
        } else {
            getString(R.string.packs_never_used)
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

        val toggleBtn = Button(context)
        toggleBtn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        toggleBtn.minimumHeight = (48 * density).toInt()
        toggleBtn.text = if (entry.enabled) {
            getString(R.string.packs_disable)
        } else {
            getString(R.string.packs_enable)
        }
        toggleBtn.setOnClickListener {
            val idx = entries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) {
                entries = entries.toMutableList().apply {
                    set(idx, entry.copy(enabled = !entry.enabled))
                }
                save()
                render()
            }
        }
        actions.addView(toggleBtn)

        val importBtn = Button(context)
        importBtn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        importBtn.minimumHeight = (48 * density).toInt()
        importBtn.text = getString(R.string.packs_import_manifest)
        importBtn.setOnClickListener {
            importManifestFor(entry)
        }
        actions.addView(importBtn)

        val editBtn = Button(context)
        editBtn.layoutParams = toggleBtn.layoutParams
        editBtn.minimumHeight = (48 * density).toInt()
        editBtn.text = getString(R.string.packs_edit)
        editBtn.setOnClickListener { startEdit(entry) }
        actions.addView(editBtn)

        val delBtn = Button(context)
        delBtn.layoutParams = toggleBtn.layoutParams
        delBtn.minimumHeight = (48 * density).toInt()
        delBtn.text = if (armedDeleteId == entry.id) {
            getString(R.string.packs_delete_confirm, entry.name)
        } else {
            getString(R.string.packs_delete)
        }
        delBtn.setOnClickListener { deleteEntry(entry) }
        actions.addView(delBtn)

        card.addView(actions)
        return card
    }

    private fun status(view: View, text: String) {
        view.findViewById<TextView>(R.id.packs_status).text = text
    }

    private fun importManifestFor(entry: PackEntry) {
        val view = view ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.packs_choose_manifest)),
            6102
        )
        // Store entry ID for callback
        view.tag = entry.id
    }

    @Deprecated("Framework picker without new deps; result handled below")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val view = view ?: return
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == importCode) {
            importPackFile(uri)
        } else if (requestCode == 6102) {
            importManifestForExisting(uri, view.tag as? String ?: return)
        }
    }

    private fun importPackFile(uri: Uri) {
        val view = view ?: return
        status(view, getString(R.string.packs_importing))
        Thread {
            try {
                val resolver = requireContext().contentResolver
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) {
                        buffer.write(buf, 0, len)
                    }
                    buffer.toByteArray()
                }
                if (bytes == null) {
                    view.post { status(view, getString(R.string.packs_import_failed, "no data")) }
                    return@Thread
                }
                // Try manifest.json first (for .mcpack/zip we'd need zip parsing;
                // here we assume user picks manifest.json directly)
                val meta = dev.xykell.client.runtime.packs.PackStore.extractFromManifest(bytes)
                val name = meta["name"] as? String ?: ""
                val version = meta["version"] as? String ?: ""
                val author = meta["author"] as? String ?: ""
                val desc = meta["description"] as? String ?: ""
                val type = meta["packType"] as? dev.xykell.client.runtime.packs.PackType
                    ?: dev.xykell.client.runtime.packs.PackType.UNKNOWN
                val packName = if (name.isNotBlank()) name else "Imported Pack"
                view.post {
                    editingId = null
                    view.findViewById<EditText>(R.id.packs_name).setText(packName)
                    view.findViewById<EditText>(R.id.packs_type).setText(type.name)
                    view.findViewById<EditText>(R.id.packs_version).setText(version)
                    view.findViewById<EditText>(R.id.packs_author).setText(author)
                    view.findViewById<EditText>(R.id.packs_notes).setText(desc)
                    view.findViewById<EditText>(R.id.packs_path).setText(uri.toString())
                    view.findViewById<Button>(R.id.packs_add).text =
                        getString(R.string.packs_add)
                    status(view, getString(R.string.packs_imported, packName))
                }
            } catch (e: Exception) {
                view.post {
                    status(view, getString(R.string.packs_import_failed, e.message ?: "unknown"))
                }
            }
        }.start()
    }

    private fun importManifestForExisting(uri: Uri, entryId: String) {
        val view = view ?: return
        val idx = entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return
        Thread {
            try {
                val resolver = requireContext().contentResolver
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) {
                        buffer.write(buf, 0, len)
                    }
                    buffer.toByteArray()
                }
                if (bytes == null) {
                    view.post { status(view, getString(R.string.packs_import_failed, "no data")) }
                    return@Thread
                }
                val meta = dev.xykell.client.runtime.packs.PackStore.extractFromManifest(bytes)
                val version = meta["version"] as? String ?: ""
                val author = meta["author"] as? String ?: ""
                val desc = meta["description"] as? String ?: ""
                val type = meta["packType"] as? dev.xykell.client.runtime.packs.PackType
                    ?: dev.xykell.client.runtime.packs.PackType.UNKNOWN
                val updated = entries.toMutableList().apply {
                    val old = this[idx]
                    set(idx, old.copy(
                        version = version,
                        author = author,
                        description = desc,
                        type = type
                    ))
                }
                entries = updated
                save()
                view.post {
                    render()
                    status(view, getString(R.string.packs_manifest_updated, entryId))
                }
            } catch (e: Exception) {
                view.post {
                    status(view, getString(R.string.packs_import_failed, e.message ?: "unknown"))
                }
}
            }.start()
    }
}