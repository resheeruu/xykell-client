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
import dev.xykell.client.runtime.social.FriendStore

/**
 * Local friends book: add/remove with color and notes, persisted per concern
 * in its own preferences file (same pattern as WaypointsFragment).
 *
 * App-level only — entries are typed by the user, never read out of the game
 * and never synced anywhere. Presence/online state is backend-gated and not
 * claimed here.
 */
class FriendsFragment : Fragment(R.layout.fragment_friends) {

    private val store = FriendStore()

    private var listView: LinearLayout? = null
    private var emptyView: TextView? = null
    private var feedbackView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()

        view.findViewById<TextView>(R.id.friends_title).text =
            getString(R.string.friends_title)
        view.findViewById<TextView>(R.id.friends_body).text =
            getString(R.string.friends_body)

        listView = view.findViewById(R.id.friends_list)
        emptyView = view.findViewById(R.id.friends_empty)
        feedbackView = view.findViewById(R.id.friends_feedback)

        val name = view.findViewById<EditText>(R.id.friends_name)
        val color = view.findViewById<EditText>(R.id.friends_color)
        val notes = view.findViewById<EditText>(R.id.friends_notes)
        if (color.text.isEmpty()) {
            color.setText(FriendStore.DEFAULT_COLOR)
        }

        view.findViewById<Button>(R.id.friends_add).setOnClickListener {
            val result = store.add(
                FriendStore.Friend(
                    name = name.text.toString().trim(),
                    color = color.text.toString().trim()
                        .ifEmpty { FriendStore.DEFAULT_COLOR },
                    notes = notes.text.toString().trim()
                        .take(FriendStore.MAX_NOTES),
                    server = "",
                ),
            )
            if (result is FriendStore.Result.Ok) {
                save()
                name.text.clear()
                notes.text.clear()
                feedback(getString(R.string.friends_added))
                renderList()
            } else {
                feedback(getString(R.string.friends_invalid))
            }
        }

        renderList()
    }

    private fun renderList() {
        val list = listView ?: return
        list.removeAllViews()
        val entries = store.sorted()
        emptyView?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        emptyView?.text = getString(R.string.friends_empty)

        val pad = (16 * resources.displayMetrics.density).toInt()
        for (f in entries) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val label = TextView(requireContext()).apply {
                text = if (f.notes.isEmpty()) f.name else getString(
                    R.string.friend_row_format, f.name, f.notes,
                )
                textSize = 13f
                setTextColor(parseColorOr(f.color, R.color.xykell_text))
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val remove = Button(requireContext()).apply {
                text = getString(R.string.friend_remove)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    store.remove(f.name)
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

    private fun parseColorOr(hex: String, fallbackRes: Int): Int {
        if (!FriendStore.isValidColor(hex)) {
            return androidx.core.content.ContextCompat.getColor(requireContext(), fallbackRes)
        }
        return try {
            android.graphics.Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            androidx.core.content.ContextCompat.getColor(requireContext(), fallbackRes)
        }
    }

    private fun load() {
        store.deserialize(prefs().getString(KEY, "") ?: "")
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

    private fun feedback(message: String) {
        feedbackView?.text = message
    }

    private companion object {
        const val PREFS = "xykell_friends"
        const val KEY = "book"
    }
}
