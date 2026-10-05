package dev.xykell.client.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.scripting.ScriptEngine
import dev.xykell.client.runtime.scripting.ScriptManager
import dev.xykell.client.runtime.scripting.ScriptRuntime
import java.io.ByteArrayOutputStream

/**
 * Script Manager.
 *
 * Xykell scripts are declarative local rules — a trigger, optional conditions,
 * and actions on Xykell's own state. There is no code editor here on purpose:
 * the engine has no code path to edit, so a text editor would be a lie about
 * what the product can do.
 */
class ScriptsFragment : Fragment() {

    private companion object {
        const val IMPORT_CODE = 7301
        const val EXPORT_CODE = 7302
        const val MAX_IMPORT_BYTES = 256 * 1024
    }

    private var runtime: ScriptRuntime? = null
    private lateinit var list: LinearLayout
    private lateinit var statusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_scripts, container, false)
        list = v.findViewById(R.id.scripts_list)
        statusText = v.findViewById(R.id.scripts_status)

        v.findViewById<Button>(R.id.scripts_start).setOnClickListener {
            ScriptManager.start(requireContext())
            render()
        }
        v.findViewById<Button>(R.id.scripts_stop).setOnClickListener {
            ScriptManager.stop()
            render()
        }
        v.findViewById<Button>(R.id.scripts_import).setOnClickListener { pickFile(IMPORT_CODE) }
        v.findViewById<Button>(R.id.scripts_export).setOnClickListener { export() }
        v.findViewById<Button>(R.id.scripts_reset).setOnClickListener { confirmReset() }
        return v
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun rt(): ScriptRuntime = runtime
        ?: ScriptManager.runtime(requireContext()).also { runtime = it }

    private fun render() {
        val r = rt()
        statusText.text = getString(
            R.string.scripts_status_fmt,
            if (r.isRunning) getString(R.string.scripts_running) else getString(R.string.scripts_stopped),
            r.rules().size,
            r.rules().count { it.enabled },
            r.diagnostics().budgetRemaining,
            r.diagnostics().lastError ?: "-",
        )
        list.removeAllViews()
        val statuses = r.status()
        if (statuses.isEmpty()) {
            list.addView(
                TextView(requireContext()).apply {
                    setText(R.string.scripts_empty)
                },
            )
            return
        }
        for (s in statuses) {
            list.addView(row(s))
        }
    }

    private fun row(s: ScriptRuntime.RuleStatus): View {
        val ctx = requireContext()
        val box = LayoutInflater.from(ctx).inflate(R.layout.item_script, list, false)
        box.findViewById<TextView>(R.id.script_name).text = s.rule.name
        box.findViewById<TextView>(R.id.script_id).text = s.rule.id
        box.findViewById<TextView>(R.id.script_triggers).text = getString(
            R.string.scripts_triggers_fmt,
            s.rule.triggers.joinToString(", ") { it.type },
        )
        box.findViewById<TextView>(R.id.script_conditions).text = getString(
            R.string.scripts_conditions_fmt,
            if (s.rule.conditions.isEmpty()) {
                getString(R.string.scripts_none)
            } else {
                s.rule.conditions.joinToString(", ") { it.type }
            },
        )
        box.findViewById<TextView>(R.id.script_actions).text = getString(
            R.string.scripts_actions_fmt,
            s.rule.actions.joinToString(", ") { it.type },
        )
        val err = box.findViewById<TextView>(R.id.script_error)
        if (s.violations.isEmpty()) {
            err.setText(R.string.scripts_valid)
            err.setTextColor(0xFF4FD8C7.toInt())
        } else {
            err.text = s.violations.joinToString("\n") { it.toString() }
            err.setTextColor(0xFFE8B34B.toInt())
        }
        val last = box.findViewById<TextView>(R.id.script_runs)
        last.text = if (s.runCount == 0) {
            getString(R.string.scripts_never_run)
        } else {
            getString(R.string.scripts_runs_fmt, s.runCount, s.lastError ?: "-")
        }

        box.findViewById<Switch>(R.id.script_enabled).apply {
            isChecked = s.rule.enabled
            setOnCheckedChangeListener { _, on ->
                rt().setEnabled(s.rule.id, on)
                render()
            }
        }
        box.findViewById<Button>(R.id.script_duplicate).setOnClickListener {
            val id = "${s.rule.id}_copy"
            when (val res = rt().duplicate(s.rule.id, id, "${s.rule.name} (copy)")) {
                is dev.xykell.client.runtime.scripting.ApiResult.Ok -> render()
                is dev.xykell.client.runtime.scripting.ApiResult.Invalid ->
                    toast(res.errorText() ?: getString(R.string.scripts_failed))
                else -> toast(getString(R.string.scripts_failed))
            }
        }
        box.findViewById<Button>(R.id.script_delete).setOnClickListener {
            rt().delete(s.rule.id)
            render()
        }
        return box
    }

    private fun confirmReset() {
        rt().reset()
        render()
        toast(getString(R.string.scripts_reset_done))
    }

    private fun export() {
        val json = rt().exportDocument()
        if (json.length <= 2) {
            toast(getString(R.string.scripts_nothing_to_export))
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "xykell-scripts.json")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, EXPORT_CODE)
    }

    private fun pickFile(code: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, code)
    }

    @Deprecated("Framework picker; result handled in onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == IMPORT_CODE) {
            importDocument(uri)
        } else if (requestCode == EXPORT_CODE) {
            writeDocument(uri)
        }
    }

    private fun importDocument(uri: Uri) {
        Thread {
            val text = try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_IMPORT_BYTES) return@use null
                        out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                }
            } catch (e: Exception) {
                null
            }
            activity?.runOnUiThread {
                if (text == null) {
                    toast(getString(R.string.scripts_import_failed))
                    return@runOnUiThread
                }
                when (val res = rt().importDocument(text)) {
                    is dev.xykell.client.runtime.scripting.ApiResult.Ok -> {
                        toast(getString(R.string.scripts_imported_fmt, res.value.size))
                        render()
                    }
                    is dev.xykell.client.runtime.scripting.ApiResult.Invalid ->
                        toast(res.errorText() ?: getString(R.string.scripts_import_failed))
                    else -> toast(getString(R.string.scripts_import_failed))
                }
            }
        }.start()
    }

    private fun writeDocument(uri: Uri) {
        val json = rt().exportDocument()
        Thread {
            val ok = try {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                    true
                } ?: false
            } catch (e: Exception) {
                false
            }
            activity?.runOnUiThread {
                toast(getString(if (ok) R.string.scripts_exported else R.string.scripts_export_failed))
            }
        }.start()
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /** Exposed so the host can show a rule summary without a view. */
    fun describe(rule: ScriptEngine.Rule): String = buildString {
        append(rule.name)
        append(" · ")
        append(rule.triggers.joinToString(", ") { it.type })
        append(" · ")
        append(rule.actions.size)
        append(" action(s)")
    }
}
