package dev.xykell.client.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.capture.CaptureResult
import dev.xykell.client.runtime.capture.CaptureService

/**
 * Screenshot controls: two taps that both go through Android's own consent
 * dialog, and nothing that ever records without it.
 *
 * Consent is consumed once per capture (the service performs exactly one
 * getMediaProjection + one createVirtualDisplay, which Android 14+ enforces).
 * The share sheet is opened here, from the foreground, because starting an
 * activity from the capture service would be blocked as a background start.
 */
class ScreenshotFragment : Fragment(R.layout.fragment_screenshot) {

    private var statusView: TextView? = null
    private var saveButton: Button? = null
    private var shareButton: Button? = null
    private var capturing = false
    private var wantShare = false

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                text(R.string.screenshot_denied)
                busy(false)
                return@registerForActivityResult
            }
            text(R.string.screenshot_capturing)
            startCapture(result.resultCode, result.data!!)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.screenshot_title).text = getString(R.string.screenshot_title)
        view.findViewById<TextView>(R.id.screenshot_body).text = getString(R.string.screenshot_body)

        statusView = view.findViewById(R.id.screenshot_status)
        saveButton = view.findViewById<Button>(R.id.screenshot_save).also { button ->
            button.setOnClickListener { askConsent(share = false) }
        }
        shareButton = view.findViewById<Button>(R.id.screenshot_share).also { button ->
            button.setOnClickListener { askConsent(share = true) }
        }
        text(R.string.screenshot_idle)
    }

    override fun onDestroyView() {
        CaptureResult.onFinished = null
        statusView = null
        saveButton = null
        shareButton = null
        super.onDestroyView()
    }

    private fun askConsent(share: Boolean) {
        if (capturing) return
        wantShare = share
        busy(true)
        text(R.string.screenshot_waiting)
        val manager =
            requireContext().getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consent.launch(manager.createScreenCaptureIntent())
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val (width, height, dpi) = captureMetrics()
        val intent = Intent(requireContext(), CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_WIDTH, width)
            putExtra(CaptureService.EXTRA_HEIGHT, height)
            putExtra(CaptureService.EXTRA_DPI, dpi)
            putExtra(CaptureService.EXTRA_SHARE, wantShare)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        CaptureResult.onFinished = { uri, error ->
            // Already on the main thread (the service posts this).
            onCaptureDone(uri, error)
        }
    }

    private fun onCaptureDone(uri: android.net.Uri?, detail: String?) {
        if (!isAdded) return
        busy(false)
        when {
            uri == null -> text(R.string.screenshot_error, detail ?: "unknown")
            detail == "api28-app-folder" -> text(R.string.screenshot_saved_app_folder)
            wantShare -> openShareSheet(uri)
            else -> text(R.string.screenshot_saved)
        }
    }

    private fun openShareSheet(uri: android.net.Uri) {
        text(R.string.screenshot_shared)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.screenshot_share))) }
            .onFailure { text(R.string.screenshot_error, "share-unavailable") }
    }

    private fun captureMetrics(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = requireActivity().windowManager.currentWindowMetrics.bounds
            return Triple(bounds.width(), bounds.height(), densityDpi)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        requireActivity().windowManager.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun busy(value: Boolean) {
        capturing = value
        saveButton?.isEnabled = !value
        shareButton?.isEnabled = !value
    }

    private fun text(resId: Int, arg: String? = null) {
        statusView?.text =
            if (arg == null) getString(resId) else getString(resId, arg)
    }
}
