package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.MeRefreshTelemetry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * ANDROID-PERSONAL-02 / T12: read-only viewer for the in-process telemetry
 * ring buffer (spec §10 P10).
 *
 * Snapshots [MeRefreshTelemetry.snapshot] once on open — does NOT subscribe
 * to the SharedFlow because:
 *  - Dialog dismissal would otherwise have to manage a coroutine scope.
 *  - Operators want a stable list to scroll/copy from, not a moving target.
 * Re-open the dialog to refresh.
 *
 * Format: one [MeRefreshTelemetry.Event.toString] per line, oldest first.
 * No formatting library — `data class.toString()` is sufficient for an
 * operator-only surface.
 */
@AndroidEntryPoint
class MeTelemetryLogDialog : DialogFragment() {

    @Inject
    lateinit var telemetry: MeRefreshTelemetry

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val scrollView = ScrollView(context).apply { isFillViewport = true }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.spacing_md),
                resources.getDimensionPixelSize(R.dimen.spacing_sm),
                resources.getDimensionPixelSize(R.dimen.spacing_md),
                resources.getDimensionPixelSize(R.dimen.spacing_sm),
            )
        }
        scrollView.addView(container)

        val snap = telemetry.snapshot()
        val body = if (snap.isEmpty()) {
            getString(R.string.dev_settings_telemetry_empty)
        } else {
            snap.joinToString(separator = "\n") { it.toString() }
        }
        val textView = TextView(context).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setTextColor(context.getColor(R.color.home_text_primary))
            setTextIsSelectable(true)
        }
        container.addView(textView)

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dev_settings_telemetry_title)
            .setView(scrollView)
            .setPositiveButton(R.string.dev_settings_close, null)
            .create()
    }

    companion object {
        const val TAG = "MeTelemetryLogDialog"

        fun newInstance(): MeTelemetryLogDialog = MeTelemetryLogDialog()
    }
}
