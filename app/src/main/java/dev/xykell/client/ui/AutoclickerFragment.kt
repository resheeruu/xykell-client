package dev.xykell.client.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.cheat.ClickSchedule
import dev.xykell.client.runtime.cheat.MacroStore
import dev.xykell.client.runtime.cheat.TouchAutomationService

/**
 * Autoclicker + tap-macro control screen. Real input automation: the
 * accessibility service taps the game screen; this fragment owns settings
 * (target point, CPS, jitter), macro controls, and service state.
 */
class AutoclickerFragment : Fragment(R.layout.fragment_autoclicker) {

    private var status: TextView? = null
    private var macroStatus: TextView? = null
    private var serviceStatus: TextView? = null
    private var recordBtn: Button? = null
    private var replayBtn: Button? = null
    private var clickerBtn: Button? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sp = TouchAutomationService.settings(requireContext())

        view.findViewById<TextView>(R.id.ac_title).text = getString(R.string.ac_title)
        view.findViewById<TextView>(R.id.ac_body).text = getString(R.string.ac_body)
        view.findViewById<TextView>(R.id.ac_service_header).text =
            getString(R.string.ac_service_header)
        view.findViewById<TextView>(R.id.ac_rate_header).text = getString(R.string.ac_rate_header)
        view.findViewById<TextView>(R.id.ac_target_header).text =
            getString(R.string.ac_target_header)
        view.findViewById<TextView>(R.id.ac_target_hint).text = getString(R.string.ac_target_hint)
        view.findViewById<TextView>(R.id.ac_macro_header).text =
            getString(R.string.ac_macro_header)

        serviceStatus = view.findViewById(R.id.ac_service_status)
        status = view.findViewById(R.id.ac_status)
        macroStatus = view.findViewById(R.id.ac_macro_status)
        recordBtn = view.findViewById(R.id.ac_record)
        replayBtn = view.findViewById(R.id.ac_replay)
        clickerBtn = view.findViewById(R.id.ac_clicker)

        view.findViewById<Button>(R.id.ac_open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val cpsLabel = view.findViewById<TextView>(R.id.ac_cps_value)
        val cpsSeek = view.findViewById<SeekBar>(R.id.ac_cps_seek)
        cpsSeek.max = ClickSchedule.MAX_CPS - ClickSchedule.MIN_CPS
        cpsSeek.progress = sp.getInt("cps", 10) - ClickSchedule.MIN_CPS
        cpsLabel.text = getString(
            R.string.ac_cps_value,
            sp.getInt("cps", 10),
        )
        cpsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val cps = progress + ClickSchedule.MIN_CPS
                sp.edit().putInt("cps", cps).apply()
                cpsLabel.text = getString(R.string.ac_cps_value, cps)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val jitterLabel = view.findViewById<TextView>(R.id.ac_jitter_value)
        val jitterSeek = view.findViewById<SeekBar>(R.id.ac_jitter_seek)
        jitterSeek.max = ClickSchedule.MAX_JITTER_PCT
        jitterSeek.progress = sp.getInt("jitterPct", 10)
        jitterLabel.text = getString(R.string.ac_jitter_value, sp.getInt("jitterPct", 10))
        jitterSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                sp.edit().putInt("jitterPct", progress).apply()
                jitterLabel.text = getString(R.string.ac_jitter_value, progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val pad = view.findViewById<TargetPadView>(R.id.ac_target_pad)
        pad.setPoint(sp.getFloat("nx", 0.5f), sp.getFloat("ny", 0.75f))
        pad.onPoint = { x, y ->
            sp.edit().putFloat("nx", x).putFloat("ny", y).apply()
        }

        recordBtn?.setOnClickListener { withService { toggleRecord() } }
        replayBtn?.setOnClickListener { withService { toggleReplay() } }
        clickerBtn?.setOnClickListener { withService { toggleClick() } }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        status = null
        macroStatus = null
        serviceStatus = null
        recordBtn = null
        replayBtn = null
        clickerBtn = null
        super.onDestroyView()
    }

    private fun withService(action: TouchAutomationService.() -> Unit) {
        val svc = TouchAutomationService.instance
        if (svc == null) {
            status?.text = getString(R.string.ac_service_off)
            return
        }
        svc.action()
        render()
    }

    private fun render() {
        val svc = TouchAutomationService.instance
        serviceStatus?.text = getString(
            if (svc == null) R.string.ac_service_disabled else R.string.ac_service_enabled,
        )
        val sp = TouchAutomationService.settings(requireContext())
        val steps = MacroStore.decode(sp.getString("macro", "")).size
        macroStatus?.text = getString(R.string.ac_macro_status, steps, MacroStore.MAX_STEPS)
        recordBtn?.text = getString(
            if (svc?.isRecording() == true) R.string.ac_stop_record else R.string.ac_record,
        )
        replayBtn?.text = getString(
            if (svc?.isReplaying() == true) R.string.ac_stop_replay else R.string.ac_replay,
        )
        clickerBtn?.text = getString(
            if (svc?.isClicking() == true) R.string.ac_stop_clicker else R.string.ac_start_clicker,
        )
        if (svc == null) status?.text = getString(R.string.ac_service_disabled)
    }
}
