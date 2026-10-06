package dev.xykell.client.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.observation.ApiResult
import dev.xykell.client.runtime.observation.ChatFilter
import dev.xykell.client.runtime.observation.ChatPolicy
import dev.xykell.client.runtime.observation.ObservationService

/**
 * Observed chat, presented locally.
 *
 * This is the read side the observation pipeline never had: the translator
 * produces PlayerMessage frames and ObservationService already forwards them
 * into [dev.xykell.client.runtime.observation.ObservedState], but nothing
 * consumed that state. Rendering it through the existing [ChatFilter] is what
 * makes timestamps, filtering and nicknames real features rather than models.
 *
 * Read-only by construction: it renders what was observed and stores a local
 * presentation policy. It never contacts the game and never re-sends chat.
 */
class ChatFragment : Fragment(R.layout.fragment_chat) {

    private val policy: ChatPolicy get() = ChatPolicy.shared()

    private var logView: TextView? = null
    private var statusView: TextView? = null
    private var rulesView: TextView? = null
    private var nickView: TextView? = null
    private var feedbackView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!isResumed) return
            render()
            view?.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadPolicy()

        view.findViewById<TextView>(R.id.chat_title).text = getString(R.string.chat_title)
        view.findViewById<TextView>(R.id.chat_body).text = getString(R.string.chat_body)

        logView = view.findViewById(R.id.chat_log)
        statusView = view.findViewById(R.id.chat_status)
        rulesView = view.findViewById(R.id.chat_rules_list)
        nickView = view.findViewById(R.id.chat_nick_list)
        feedbackView = view.findViewById(R.id.chat_feedback)

        val timestamps = view.findViewById<CheckBox>(R.id.chat_timestamps)
        timestamps.isChecked = policy.showTimestamps
        timestamps.setOnCheckedChangeListener { _, checked ->
            policy.showTimestamps = checked
            savePolicy()
            render()
        }

        val pattern = view.findViewById<EditText>(R.id.chat_rule_pattern)
        view.findViewById<Button>(R.id.chat_rule_hide).setOnClickListener {
            addRule(pattern, ChatFilter.Action.HIDE)
        }
        view.findViewById<Button>(R.id.chat_rule_highlight).setOnClickListener {
            addRule(pattern, ChatFilter.Action.HIGHLIGHT)
        }
        view.findViewById<Button>(R.id.chat_rules_clear).setOnClickListener {
            policy.filter.clear()
            savePolicy()
            render()
            feedback(R.string.chat_rules_cleared)
        }

        val sender = view.findViewById<EditText>(R.id.chat_nick_sender)
        val display = view.findViewById<EditText>(R.id.chat_nick_display)
        view.findViewById<Button>(R.id.chat_nick_set).setOnClickListener {
            val result = policy.nicknames.set(
                sender.text.toString().trim(),
                display.text.toString().trim(),
            )
            if (result is ApiResult.Ok) {
                sender.text.clear()
                display.text.clear()
                feedback(R.string.chat_nickname_set)
            } else {
                feedback(R.string.chat_nickname_invalid)
            }
            savePolicy()
            render()
        }

        render()
    }

    private fun addRule(pattern: EditText, action: ChatFilter.Action) {
        val result = policy.filter.add(
            ChatFilter.Rule(
                id = "r" + System.nanoTime(),
                pattern = pattern.text.toString(),
                action = action,
                caseSensitive = false,
            ),
        )
        if (result is ApiResult.Ok) {
            pattern.text.clear()
            feedback(R.string.chat_rule_added)
        } else {
            feedback(R.string.chat_rule_invalid)
        }
        savePolicy()
        render()
    }

    private fun render() {
        val state = ObservationService.observedState()
        val verdicts = policy.filter.visibleLines(
            state.chat, System.currentTimeMillis(), policy.nicknames, policy.config(),
        )
        val accent = ContextCompat.getColor(requireContext(), R.color.xykell_accent)

        val log = logView ?: return
        if (verdicts.isEmpty()) {
            log.text = getString(R.string.chat_empty)
            log.setTypeface(null, Typeface.NORMAL)
        } else {
            val builder = StringBuilder()
            verdicts.forEachIndexed { index, v ->
                val line = buildString {
                    if (v.timestamp != null) append(v.timestamp).append(' ')
                    append('<').append(v.displaySender).append("> ")
                    append(v.line.message)
                }
                val span = SpannableString(line)
                if (v.highlighted) {
                    span.setSpan(
                        ForegroundColorSpan(accent), 0, span.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (index > 0) builder.append('\n')
                builder.append(span)
            }
            log.text = builder
        }

        statusView?.text = getString(
            R.string.chat_status_format,
            ObservationService.state.name,
            state.chatCount,
            verdicts.size,
        )
        rulesView?.text = policy.filter.rules().joinToString("\n").ifEmpty {
            getString(R.string.chat_no_rules)
        }
        nickView?.text = policy.nicknames.entries().entries.joinToString("\n") {
            "${it.key} -> ${it.value}"
        }.ifEmpty { getString(R.string.chat_no_nicknames) }
    }

    private fun feedback(messageRes: Int) {
        feedbackView?.text = getString(messageRes)
    }

    override fun onResume() {
        super.onResume()
        view?.post(ticker)
    }

    override fun onPause() {
        view?.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroyView() {
        view?.removeCallbacks(ticker)
        logView = null
        statusView = null
        rulesView = null
        nickView = null
        feedbackView = null
        super.onDestroyView()
    }

    private fun loadPolicy() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "") ?: ""
        policy.deserialize(json)
    }

    private fun savePolicy() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, policy.serialize()).apply()
    }

    private companion object {
        const val REFRESH_MS = 1000L
        const val PREFS = "xykell_chat_policy"
        const val KEY = "policy"
    }
}
