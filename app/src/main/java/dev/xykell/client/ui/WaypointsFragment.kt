package dev.xykell.client.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.observation.ObservationService
import dev.xykell.client.runtime.world.WaypointStore

/**
 * Local waypoint book.
 *
 * Coordinates come from the user or from an observed PlayerTravelled sample —
 * never read out of the game. This is also the first consumer of the motion
 * half of [dev.xykell.client.runtime.observation.ObservedState], which the
 * translator always produced but nothing previously read.
 *
 * The store owns its own preferences file, matching how AccountStore and
 * ScriptManager persist: one file per concern, no shared mega-preference.
 */
class WaypointsFragment : Fragment(R.layout.fragment_waypoints) {

    private val store = WaypointStore()

    private var listView: LinearLayout? = null
    private var emptyView: TextView? = null
    private var feedbackView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()

        view.findViewById<TextView>(R.id.waypoints_title).text =
            getString(R.string.waypoints_title)
        view.findViewById<TextView>(R.id.waypoints_body).text =
            getString(R.string.waypoints_body)

        listView = view.findViewById(R.id.waypoints_list)
        emptyView = view.findViewById(R.id.waypoints_empty)
        feedbackView = view.findViewById(R.id.waypoints_feedback)

        val name = view.findViewById<EditText>(R.id.waypoints_name)
        val dimension = view.findViewById<EditText>(R.id.waypoints_dimension)
        val x = view.findViewById<EditText>(R.id.waypoints_x)
        val y = view.findViewById<EditText>(R.id.waypoints_y)
        val z = view.findViewById<EditText>(R.id.waypoints_z)

        view.findViewById<Button>(R.id.waypoints_use_position).setOnClickListener {
            val motion = ObservationService.observedState().motion
            if (motion == null || !motion.anyKnown) {
                feedback(R.string.waypoints_no_position)
                return@setOnClickListener
            }
            motion.x?.let { x.setText(formatCoord(it)) }
            motion.y?.let { y.setText(formatCoord(it)) }
            motion.z?.let { z.setText(formatCoord(it)) }
            feedback(R.string.waypoints_position_filled)
        }

        view.findViewById<Button>(R.id.waypoints_add).setOnClickListener {
            val point = WaypointStore.Waypoint(
                id = "wp" + System.nanoTime(),
                name = name.text.toString().trim(),
                dimension = dimension.text.toString().trim()
                    .ifEmpty { WaypointStore.DIM_OVERWORLD },
                x = x.text.toString().toDoubleOrNull() ?: Double.NaN,
                y = y.text.toString().toDoubleOrNull() ?: Double.NaN,
                z = z.text.toString().toDoubleOrNull() ?: Double.NaN,
                colorArgb = WaypointStore.DEFAULT_COLOR,
                showOnMap = true,
            )
            when (val result = store.put(point)) {
                is WaypointStore.Result.Ok -> {
                    save()
                    name.text.clear()
                    feedback(R.string.waypoints_added)
                    renderList()
                }
                is WaypointStore.Result.Invalid -> feedback(R.string.waypoints_invalid)
                is WaypointStore.Result.Failed -> feedback(R.string.waypoints_failed)
            }
        }

        view.findViewById<Button>(R.id.waypoints_clear_all).setOnClickListener {
            store.clear()
            save()
            feedback(R.string.waypoints_cleared)
            renderList()
        }

        renderList()
    }

    private fun renderList() {
        val list = listView ?: return
        list.removeAllViews()
        val points = store.sorted()
        emptyView?.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        emptyView?.text = getString(R.string.waypoints_empty)

        val pad = (16 * resources.displayMetrics.density).toInt()
        for (p in points) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val label = TextView(requireContext()).apply {
                text = getString(
                    R.string.waypoint_row_format,
                    p.name, p.dimension, p.x, p.y, p.z,
                )
                textSize = 13f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.xykell_text))
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val remove = Button(requireContext()).apply {
                text = getString(R.string.waypoint_remove)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    store.remove(p.id)
                    save()
                    renderList()
                }
            }
            row.addView(remove)
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun formatCoord(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else "%.1f".format(value)

    private fun load() {
        val prefs = prefs()
        store.deserialize(prefs.getString(KEY, "") ?: "")
    }

    private fun save() {
        prefs().edit().putString(KEY, store.serialize()).apply()
    }

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onDestroyView() {
        listView = null
        emptyView = null
        feedbackView = null
        super.onDestroyView()
    }

    private fun feedback(messageRes: Int) {
        feedbackView?.text = getString(messageRes)
    }

    private companion object {
        const val PREFS = "xykell_waypoints"
        const val KEY = "book"
    }
}
