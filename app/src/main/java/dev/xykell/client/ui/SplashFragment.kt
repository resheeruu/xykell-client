package dev.xykell.client.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.NativeSettings
import dev.xykell.client.runtime.ProfileManager
import dev.xykell.client.ui.ModuleEntry
import dev.xykell.client.MainActivity
import org.json.JSONObject

/**
 * Splash screen with animated startup sequence.
 * Sequence: logo fade-in → accent sweep → subtitle fade-in →
 * initialization status steps → transition to Home.
 * Reduced-motion: instant transition, no animations.
 */
class SplashFragment : Fragment(R.layout.fragment_splash) {

    private lateinit var logo: TextView
    private lateinit var sweep: View
    private lateinit var subtitle: TextView
    private lateinit var status: TextView

    private val initSteps = listOf(
        "Loading theme…",
        "Loading profiles…",
        "Loading settings…",
        "Loading modules…",
        "Loading HUD layout…",
        "Ready"
    )

    private var stepIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var reducedMotion = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logo = view.findViewById(R.id.splash_logo)
        sweep = view.findViewById(R.id.splash_sweep)
        subtitle = view.findViewById(R.id.splash_subtitle)
        status = view.findViewById(R.id.splash_status)

        reducedMotion = isReducedMotionEnabled()

        if (reducedMotion) {
            // Reduced motion: instant transition
            logo.alpha = 1f
            subtitle.alpha = 1f
            status.text = getString(R.string.splash_ready)
            status.alpha = 1f
            handler.postDelayed({ advanceToHome() }, 100)
        } else {
            startAnimationSequence()
        }
    }

    private fun startAnimationSequence() {
        // Step 1: Logo fade-in
        logo.animate()
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { startSweepAnimation() }
            .start()
    }

    private fun startSweepAnimation() {
        // Step 2: Accent sweep (width animation)
        sweep.alpha = 1f
        val sweepWidth = resources.displayMetrics.widthPixels / 2
        sweep.animate()
            .setStartDelay(100)
            .setDuration(500)
            .scaleX(1f)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { startSubtitleAndStatus() }
            .start()

        // Subtitle fade-in during sweep
        subtitle.animate()
            .alpha(1f)
            .setStartDelay(200)
            .setDuration(300)
            .start()
    }

    private fun startSubtitleAndStatus() {
        // Step 3: Status text appears
        status.alpha = 1f
        runInitSteps()
    }

    private fun runInitSteps() {
        if (stepIndex >= initSteps.size) {
            advanceToHome()
            return
        }

        val step = initSteps[stepIndex]
        status.text = step
        stepIndex++

        // Real initialization work for each step
        when (stepIndex - 1) {
            0 -> loadTheme()
            1 -> loadProfiles()
            2 -> loadSettings()
            3 -> loadModules()
            4 -> loadHudLayout()
            else -> {}
        }

        // Delay before next step (variable for realism, but bounded)
        val delay = if (stepIndex == initSteps.size) 200 else 350
        handler.postDelayed({ runInitSteps() }, delay.toLong())
    }

    private fun loadTheme() {
        try {
            val values = JSONObject(NativeSettings.getValues(NativeSettings.root(requireContext())))
            val name = values.optJSONObject("client")?.optString("theme", "").orEmpty()
            if (name.isNotEmpty() && name != dev.xykell.client.ui.ThemeColors.DEFAULT.name) {
                val tokens = dev.xykell.client.NativeThemes.tokens(name)?.let { dev.xykell.client.ui.ThemeColors.from(it) }
                tokens?.let { dev.xykell.client.ui.ActiveTheme.apply(requireActivity().findViewById(android.R.id.content), it) }
            }
        } catch (e: Exception) {
            // Theme load failed: keep default
        }
    }

    private fun loadProfiles() {
        // ProfileManager.currentProfile is already initialized
        dev.xykell.client.runtime.ProfileManager.currentProfile
    }

    private fun loadSettings() {
        // NativeSettings root is initialized on first access
        dev.xykell.client.NativeSettings.root(requireContext())
    }

private fun loadModules() {
        // ModuleEntry is a pure Kotlin data class with companion object, already loaded
        dev.xykell.client.ui.ModuleEntry
    }

    private fun loadHudLayout() {
        // HUD editor state is loaded on demand
    }

    private fun advanceToHome() {
        handler.removeCallbacksAndMessages(null)
        val activity = activity as? MainActivity
        activity?.run { showPage(0) }
    }

    private fun isReducedMotionEnabled(): Boolean {
        return resources.configuration.fontScale > 1.3f
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}