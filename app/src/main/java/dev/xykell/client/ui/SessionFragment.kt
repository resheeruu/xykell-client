package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.LaunchExecutor
import dev.xykell.client.runtime.Onboarding
import dev.xykell.client.runtime.OnboardingStore
import dev.xykell.client.runtime.ProfileManager
import dev.xykell.client.runtime.XykellInfo
import dev.xykell.client.runtime.observation.ObservationService
import dev.xykell.client.runtime.replay.ReplayPlayback
import dev.xykell.client.runtime.replay.ReplayRecorder
import dev.xykell.client.runtime.replay.ReplayStore

/**
 * Pre-launch session configuration: shows selected profile, theme,
 * HUD, content, world/server target, and launch readiness.
 */
class SessionFragment : Fragment(R.layout.fragment_session) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.session_title).text = getString(R.string.session_title)
        view.findViewById<TextView>(R.id.session_body).text = getString(R.string.session_body)

        refresh(view)

        view.findViewById<Button>(R.id.session_launch).setOnClickListener {
            val status = LaunchExecutor.execute(requireContext()) + "\n\n" +
                LaunchExecutor.launchMinecraft(requireContext())
            view.findViewById<TextView>(R.id.session_status).text = status
        }
    }

    private fun refresh(view: View) {
        // Profile
        val profile = ProfileManager.currentProfile
        view.findViewById<TextView>(R.id.session_profile).text = getString(R.string.session_profile_label, profile)

        // Theme
        val theme = try {
            val values = org.json.JSONObject(dev.xykell.client.NativeSettings.getValues(dev.xykell.client.NativeSettings.root(requireContext())))
            values.optJSONObject("client")?.optString("theme", "") ?: dev.xykell.client.ui.ThemeColors.DEFAULT.name
        } catch (e: Exception) {
            dev.xykell.client.ui.ThemeColors.DEFAULT.name
        }
        view.findViewById<TextView>(R.id.session_theme).text = getString(R.string.session_theme_label, theme)

        // Xykell version
        view.findViewById<TextView>(R.id.session_version).text = getString(
            R.string.session_version_label,
            XykellInfo.XYKELL_VERSION,
            XykellInfo.NATIVE_VERSION
        )

        // Observation state
        val obsState = ObservationService.state.name
        view.findViewById<TextView>(R.id.session_observation).text = getString(R.string.session_observation_label, obsState)

        // Runtime status
        val runtime = dev.xykell.client.runtime.RuntimeStatus.summary()
        val firstLine = runtime.lineSequence().firstOrNull().orEmpty().removePrefix("Runtime:").trim()
        val runtimeDisplay = firstLine.ifEmpty { getString(R.string.session_runtime_unknown) }
        view.findViewById<TextView>(R.id.session_runtime).text = getString(R.string.session_runtime_label, runtimeDisplay)

        // Launch status
        view.findViewById<TextView>(R.id.session_status).text = LaunchExecutor.execute(requireContext()) + "\n\n" +
            LaunchExecutor.launchMinecraft(requireContext())

        // A control that renders but has no listener is worse than no control.
        view.findViewById<Button>(R.id.session_replay_start).setOnClickListener { startReplay() }
        view.findViewById<Button>(R.id.session_replay_stop).setOnClickListener { stopReplay() }
        renderOnboarding(view)
        renderReplay(view)
    }

    // --- replay ------------------------------------------------------------
    // The recorder is fed by polling the observation state, not by a timer of
    // its own: a frame exists only when the observation layer produced one, so
    // a replay can never contain a position nobody observed.

    private var recorder: ReplayRecorder? = null
    private var replayInfo: TextView? = null

    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            ObservationService.observedState().motion?.let { r.onMotion(it) }
            // 10 Hz sits above the movement sample rate and costs nothing:
            // repeated timestamps are not recorded, so a faster poll only spins.
            view?.postDelayed(this, 100L)
        }
    }

    private fun replayDir() = java.io.File(requireContext().filesDir, "replays")

    private fun startReplay() {
        if (recorder != null) return
        recorder = ReplayRecorder("take-" + System.currentTimeMillis())
        view?.post(poll)
        view?.let { renderReplay(it) }
    }

    private fun stopReplay() {
        val r = recorder ?: return
        recorder = null
        view?.removeCallbacks(poll)
        val replay = r.stop()
        var saved = 0
        // The name comes from our own timestamp, but it is still checked:
        // isSafeName is the only thing standing between a name and a path.
        if (replay.frames.isNotEmpty() && ReplayStore.isSafeName(r.name)) {
            val dir = replayDir()
            if (dir.isDirectory || dir.mkdirs()) {
                java.io.File(dir, r.name + ".json").writeText(ReplayStore.toJson(replay))
                saved = replay.frames.size
            }
        }
        val v = view ?: return
        v.findViewById<TextView>(R.id.session_replay_info).text =
            getString(R.string.session_replay_saved, saved)
        renderReplay(v)
    }

    private fun renderReplay(view: View) {
        replayInfo = view.findViewById(R.id.session_replay_info)
        replayInfo?.text = getString(
            R.string.session_replay_saved,
            recorder?.frameCount ?: 0,
        )

        val list = view.findViewById<LinearLayout>(R.id.session_replay_list)
        list.removeAllViews()
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val files = (replayDir().listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

        list.staggerEntrance(requireContext())
        if (files.isEmpty()) {
            list.addView(TextView(context).apply {
                text = getString(R.string.session_replay_none)
                textSize = 13f
            })
            return
        }

        for (file in files.take(MAX_LISTED)) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val label = TextView(context).apply {
                text = file.name.removeSuffix(".json")
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            row.addView(label)
            row.addView(Button(context).apply {
                text = "?"
                minHeight = (48 * density).toInt()
                minimumHeight = (48 * density).toInt()
                contentDescription = file.name
                setOnClickListener { describeReplay(file, label) }
            })
            row.addView(Button(context).apply {
                text = getString(R.string.packs_delete)
                minHeight = (48 * density).toInt()
                minimumHeight = (48 * density).toInt()
                setOnClickListener {
                    if (file.delete()) {
                        replayInfo?.text = getString(
                            R.string.session_replay_deleted,
                            file.name.removeSuffix(".json"),
                        )
                        renderReplay(view)
                    }
                }
            })
            list.addView(row)
        }
    }

    /** Shows a recording's real header, or exactly why it could not be read. */
    private fun describeReplay(file: java.io.File, label: TextView) {
        val decoded = try {
            ReplayStore.fromJson(file.readText())
        } catch (e: Exception) {
            null
        }
        label.text = when (decoded) {
            is ReplayStore.Decoded.Ok -> {
                val pb = ReplayPlayback(decoded.replay)
                val hz = pb.observedSampleHz()
                file.name.removeSuffix(".json") + " \u2014 " +
                    decoded.replay.frames.size + " f, " +
                    pb.durationMs + " ms, " +
                    (if (hz == null) "rate unknown" else String.format("%.1f Hz", hz)) +
                    (if (pb.segmentTotal > 1) ", " + pb.segmentTotal + " takes" else "")
            }
            is ReplayStore.Decoded.Refused ->
                file.name.removeSuffix(".json") + " \u2014 refused: " + decoded.reason.name
            null -> file.name.removeSuffix(".json") + " \u2014 unreadable"
        }
    }

    override fun onDestroyView() {
        // The poll posts to the view, which is about to be gone. Dropping the
        // callbacks and the recorder stops it holding the fragment alive.
        recorder = null
        view?.removeCallbacks(poll)
        replayInfo = null
        super.onDestroyView()
    }

    private companion object {
        /** Bound on how many recordings the screen renders rows for. */
        const val MAX_LISTED = 50
    }

    // --- onboarding --------------------------------------------------------
    // A preference, not a gate: "first run" means show the introduction once,
    // and there is no licence check, redirect or time-limited token anywhere in
    // this path. See docs/LEGAL-LICENSE.md sections 3 and 5.

    private fun renderOnboarding(view: View) {
        val onboarding = OnboardingStore.load(requireContext())
        val context = requireContext()
        val density = context.resources.displayMetrics.density

        view.findViewById<TextView>(R.id.session_onboarding).text = buildString {
            append(
                if (onboarding.firstRun) {
                    getString(R.string.session_onboarding_first)
                } else {
                    getString(R.string.session_onboarding_done)
                },
            )
            append('\n')
            append(
                onboarding.launchMode?.id
                    ?: getString(R.string.session_launch_mode_none),
            )
        }

        val row = view.findViewById<LinearLayout>(R.id.session_launch_modes)
        row.removeAllViews()
        for (mode in Onboarding.LaunchMode.values()) {
            row.addView(Button(context).apply {
                text = mode.id
                isAllCaps = false
                minHeight = (48 * density).toInt()
                minimumHeight = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).also { if (row.childCount > 0) it.marginStart = (8 * density).toInt() }
                setOnClickListener {
                    val current = OnboardingStore.load(context)
                    current.completeOnboarding(mode)
                    OnboardingStore.save(context, current)
                    renderOnboarding(view)
                }
            })
        }
    }
}
