package dev.xykell.client.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
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
import dev.xykell.client.runtime.worlds.NbtReader
import dev.xykell.client.runtime.worlds.WorldEntry
import dev.xykell.client.runtime.worlds.WorldStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** World book: a local, user-owned list with optional level.dat
 *  import via SAF tree picker. No Minecraft data is accessed
 *  without explicit user selection. */
class WorldsFragment : Fragment(R.layout.fragment_worlds) {

    private val importCode = 5101

    private var entries: List<WorldEntry> = emptyList()
    private var query = ""
    private var editingId: String? = null
    private var armedDeleteId: String? = null

    private fun storeFile(): File = File(requireContext().filesDir, "worlds.json")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.worlds_title).text =
            getString(R.string.worlds_title)
        view.findViewById<TextView>(R.id.worlds_body).text =
            getString(R.string.worlds_body)
        view.findViewById<EditText>(R.id.worlds_search).let { field ->
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
        view.findViewById<Button>(R.id.worlds_add).setOnClickListener {
            saveFromForm()
        }
        view.findViewById<Button>(R.id.worlds_cancel_edit).setOnClickListener {
            editingId = null
            clearForm()
            render()
        }
        view.findViewById<Button>(R.id.worlds_import).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                // Optional: filter to show only folders that might contain level.dat
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, importCode)
        }
        view.findViewById<Button>(R.id.worlds_export).setOnClickListener {
            exportList()
        }
        load()
        render()
    }

    private fun load() {
        entries = try {
            WorldStore.fromJson(storeFile().readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        val file = storeFile()
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(WorldStore.toJson(entries))
        if (!tmp.renameTo(file)) {
            file.writeText(WorldStore.toJson(entries))
            tmp.delete()
        }
    }

    private fun saveFromForm() {
        val view = view ?: return
        val name = view.findViewById<EditText>(R.id.worlds_name).text.toString()
        val notes = view.findViewById<EditText>(R.id.worlds_notes).text.toString()
        val gameVersion =
            view.findViewById<EditText>(R.id.worlds_version).text.toString()
        val worldPath =
            view.findViewById<EditText>(R.id.worlds_path).text.toString()
        val error = WorldStore.validate(name)
        if (error != null) {
            status(view, getString(R.string.worlds_invalid, error))
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
                        notes = notes.trim().take(WorldStore.MAX_NOTES),
                        gameVersion = gameVersion.trim(),
                        worldPath = worldPath.trim()
                    ))
                }
                status(view, getString(R.string.worlds_saved, name.trim()))
            }
            editingId = null
        } else {
            val created = System.currentTimeMillis()
            entries = entries + WorldEntry(
                id = WorldStore.newId(),
                name = name.trim(),
                notes = notes.trim().take(WorldStore.MAX_NOTES),
                gameVersion = gameVersion.trim(),
                worldPath = worldPath.trim(),
                createdAt = created
            )
            status(view, getString(R.string.worlds_added, name.trim()))
        }
        save()
        clearForm()
        render()
    }

    private fun clearForm() {
        val view = view ?: return
        view.findViewById<EditText>(R.id.worlds_name).text?.clear()
        view.findViewById<EditText>(R.id.worlds_notes).text?.clear()
        view.findViewById<EditText>(R.id.worlds_version).text?.clear()
        view.findViewById<EditText>(R.id.worlds_path).text?.clear()
        view.findViewById<Button>(R.id.worlds_add).text =
            getString(R.string.worlds_add)
        view.findViewById<Button>(R.id.worlds_cancel_edit).visibility =
            View.GONE
    }

    private fun startEdit(entry: WorldEntry) {
        val view = view ?: return
        editingId = entry.id
        view.findViewById<EditText>(R.id.worlds_name).setText(entry.name)
        view.findViewById<EditText>(R.id.worlds_notes).setText(entry.notes)
        view.findViewById<EditText>(R.id.worlds_version).setText(entry.gameVersion)
        view.findViewById<EditText>(R.id.worlds_path).setText(entry.worldPath)
        view.findViewById<Button>(R.id.worlds_add).text =
            getString(R.string.worlds_save)
        view.findViewById<Button>(R.id.worlds_cancel_edit).visibility =
            View.VISIBLE
        status(view, getString(R.string.worlds_editing, entry.name))
    }

    private fun exportList() {
        val view = view ?: return
        try {
            val json = WorldStore.toJson(entries)
            val dir = File(requireContext().getExternalFilesDir(null), "Xykell")
            if (!dir.exists() && !dir.mkdirs()) {
                status(view, getString(R.string.worlds_export_dir_failed))
                return
            }
            val file = File(dir, "xykell-worlds.json")
            file.writeText(json)
            status(view, getString(R.string.worlds_exported, file.absolutePath))
        } catch (e: Exception) {
            status(view, getString(R.string.worlds_export_failed))
        }
    }

    private fun deleteEntry(entry: WorldEntry) {
        val view = view ?: return
        if (armedDeleteId == entry.id) {
            armedDeleteId = null
            entries = entries.filterNot { it.id == entry.id }
            save()
            status(view, getString(R.string.worlds_deleted, entry.name))
            render()
        } else {
            armedDeleteId = entry.id
            render()
        }
    }

    private fun render() {
        val view = view ?: return
        val rows = view.findViewById<LinearLayout>(R.id.worlds_rows)
        val empty = view.findViewById<TextView>(R.id.worlds_empty)
        rows.removeAllViews()
        val visible = WorldStore.sort(WorldStore.filter(entries, query))
        if (visible.isEmpty()) {
            empty.text = if (query.trim().isEmpty()) {
                getString(R.string.worlds_empty)
            } else {
                getString(R.string.worlds_empty_search, query.trim())
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            for (entry in visible) {
                rows.addView(worldCard(entry))
            }
        }
    }

    private fun worldCard(entry: WorldEntry): View {
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
        val fav = TextView(context)
        fav.text = if (entry.favorite) " ★" else ""
        fav.textSize = 16f
        titleRow.addView(name)
        titleRow.addView(fav)
        card.addView(titleRow)

        if (entry.gameVersion.isNotBlank()) {
            val ver = TextView(context)
            ver.text = getString(R.string.worlds_version_label, entry.gameVersion)
            ver.textSize = 13f
            ver.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context, R.color.xykell_accent
                )
            )
            card.addView(ver)
        }

        if (entry.notes.isNotBlank()) {
            val notes = TextView(context)
            notes.text = entry.notes
            notes.textSize = 13f
            card.addView(notes)
        }

        if (entry.worldPath.isNotBlank()) {
            val path = TextView(context)
            path.text = "Path: " + entry.worldPath.take(80)
            path.textSize = 12f
            path.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context, R.color.xykell_muted
                )
            )
            card.addView(path)
        }

        val meta = TextView(context)
        meta.text = if (entry.lastUsed > 0L) {
            getString(
                R.string.worlds_last_used,
                DateFormat.getDateFormat(context)
                    .format(java.util.Date(entry.lastUsed))
            )
        } else {
            getString(R.string.worlds_never_used)
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

        val favBtn = Button(context)
        favBtn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        favBtn.minimumHeight = (48 * density).toInt()
        favBtn.text = if (entry.favorite) {
            getString(R.string.worlds_unfavorite)
        } else {
            getString(R.string.worlds_favorite)
        }
        favBtn.setOnClickListener {
            val idx = entries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) {
                entries = entries.toMutableList().apply {
                    set(idx, entry.copy(favorite = !entry.favorite))
                }
                save()
                render()
            }
        }
        actions.addView(favBtn)

        val editBtn = Button(context)
        editBtn.layoutParams = favBtn.layoutParams
        editBtn.minimumHeight = (48 * density).toInt()
        editBtn.text = getString(R.string.worlds_edit)
        editBtn.setOnClickListener { startEdit(entry) }
        actions.addView(editBtn)

        val delBtn = Button(context)
        delBtn.layoutParams = favBtn.layoutParams
        delBtn.minimumHeight = (48 * density).toInt()
        delBtn.text = if (armedDeleteId == entry.id) {
            getString(R.string.worlds_delete_confirm, entry.name)
        } else {
            getString(R.string.worlds_delete)
        }
        delBtn.setOnClickListener { deleteEntry(entry) }
        actions.addView(delBtn)

        card.addView(actions)
        return card
    }

    private fun status(view: View, text: String) {
        view.findViewById<TextView>(R.id.worlds_status).text = text
    }

    @Deprecated("Framework picker without new deps; result handled below")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val view = view ?: return
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val treeUri = data.data!!
        if (requestCode == importCode) {
            importWorldTree(treeUri)
        }
    }

    /** Import world from SAF tree URI: find level.dat via DocumentsContract,
     *  parse NBT, pre-fill form with extracted metadata. */
    private fun importWorldTree(treeUri: Uri) {
        val view = view ?: return
        status(view, getString(R.string.worlds_importing))
        Thread {
            try {
                val resolver = requireContext().contentResolver
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val cursor = resolver.query(treeUri, columns, null, null, null)
                var levelDatId: String? = null
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: ""
                        val docId = c.getString(1) ?: ""
                        if (name == "level.dat") {
                            levelDatId = docId
                            break
                        }
                    }
                }
                if (levelDatId == null) {
                    view.post { status(view, getString(R.string.worlds_no_level_dat)) }
                    return@Thread
                }
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, levelDatId)
                val bytes = resolver.openInputStream(childUri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) {
                        buffer.write(buf, 0, len)
                    }
                    buffer.toByteArray()
                }
                if (bytes == null) {
                    view.post { status(view, getString(R.string.worlds_import_failed, "no data")) }
                    return@Thread
                }
                val meta = WorldStore.extractFromLevelDat(bytes)
                val name = meta["LevelName"] as? String ?: ""
                val version = meta["Version"] as? Int
                val worldName = if (name.isNotBlank()) name else "Imported World"
                val gameVer = version?.toString() ?: ""
                val uriString = treeUri.toString()
                view.post {
                    editingId = null
                    view.findViewById<EditText>(R.id.worlds_name).setText(worldName)
                    view.findViewById<EditText>(R.id.worlds_version).setText(gameVer)
                    view.findViewById<EditText>(R.id.worlds_path).setText(uriString)
                    view.findViewById<Button>(R.id.worlds_add).text =
                        getString(R.string.worlds_add)
                    status(view, getString(R.string.worlds_imported, worldName))
                }
            } catch (e: Exception) {
                view.post {
                    status(view, getString(R.string.worlds_import_failed, e.message ?: "unknown"))
                }
            }
        }.start()
    }
}