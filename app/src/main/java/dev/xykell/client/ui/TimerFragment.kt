package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.privacy.ApiResult
import dev.xykell.client.runtime.privacy.CountdownTimer

/**
 * A countdown with an honest state machine.
 *
 * [CountdownTimer] only reports RUNNING when it really has a deadline, and
 * resolves FINISHED exactly once, so the label can never drift from reality.
 * The screen is a thin view over it: no second clock, no local arithmetic.
 */
class TimerFragment : Fragment(R.layout.fragment_timer) {

    private val timer = CountdownTimer()

    private var display: TextView? = null
    private var stateView: TextView? = null
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
        view.findViewById<TextView>(R.id.timer_title).text = getString(R.string.timer_title)
        view.findViewById<TextView>(R.id.timer_body).text = getString(R.string.timer_body)

        display = view.findViewById(R.id.timer_display)
        stateView = view.findViewById(R.id.timer_state)
        feedbackView = view.findViewById(R.id.timer_feedback)

        val minutes = view.findViewById<EditText>(R.id.timer_minutes)

        view.findViewById<Button>(R.id.timer_start).setOnClickListener {
            val value = minutes.text.toString().toLongOrNull()
            if (value == null || value < 1L) {
                feedback(R.string.timer_invalid_duration)
                render()
                return@setOnClickListener
            }
            when (timer.start(value * 60_000L)) {
                is ApiResult.Ok -> feedback(R.string.timer_started)
                is ApiResult.Invalid -> feedback(R.string.timer_invalid_duration)
                else -> feedback(R.string.timer_invalid_duration)
            }
            render()
        }
        view.findViewById<Button>(R.id.timer_stop).setOnClickListener {
            timer.stop()
            feedback(R.string.timer_stopped)
            render()
        }
        view.findViewById<Button>(R.id.timer_restart).setOnClickListener {
            when (timer.restart()) {
                is ApiResult.Ok -> feedback(R.string.timer_started)
                is ApiResult.Invalid -> feedback(R.string.timer_no_previous)
                else -> feedback(R.string.timer_no_previous)
            }
            render()
        }

        render()
    }

    private fun render() {
        val snapshot = timer.snapshot()
        display?.text = timer.format(snapshot)
        stateView?.text = getString(
            when (snapshot.state) {
                CountdownTimer.Phase.IDLE -> R.string.timer_state_idle
                CountdownTimer.Phase.RUNNING -> R.string.timer_state_running
                CountdownTimer.Phase.FINISHED -> R.string.timer_state_finished
            },
        )
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
        display = null
        stateView = null
        feedbackView = null
        super.onDestroyView()
    }

    private companion object {
        const val REFRESH_MS = 250L
    }
}
