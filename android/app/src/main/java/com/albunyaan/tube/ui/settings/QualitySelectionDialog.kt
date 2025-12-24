package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R

/**
 * Dialog for selecting download/playback quality.
 *
 * Uses Fragment Result API for process-death safety instead of callbacks.
 * Register a listener in the parent fragment's onViewCreated:
 *
 * ```
 * childFragmentManager.setFragmentResultListener(
 *     QualitySelectionDialog.REQUEST_KEY,
 *     viewLifecycleOwner
 * ) { _, result ->
 *     val quality = result.getString(QualitySelectionDialog.RESULT_QUALITY) ?: "medium"
 *     // Handle selection
 * }
 * ```
 */
class QualitySelectionDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val currentQuality = arguments?.getString(ARG_CURRENT_QUALITY) ?: "medium"

        val qualities = listOf("low", "medium", "high")
        val qualityNames = arrayOf(
            getString(R.string.settings_quality_low_desc),
            getString(R.string.settings_quality_medium_desc),
            getString(R.string.settings_quality_high_desc)
        )
        val currentIndex = qualities.indexOf(currentQuality).takeIf { it >= 0 } ?: 1

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_download_quality_title)
            .setSingleChoiceItems(qualityNames, currentIndex) { dialogInterface, which ->
                val selectedQuality = qualities[which]
                // Deliver result via Fragment Result API (survives process death)
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_QUALITY to selectedQuality)
                )
                dialogInterface.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "QualitySelectionDialog"
        const val REQUEST_KEY = "quality_selection_request"
        const val RESULT_QUALITY = "selected_quality"

        private const val ARG_CURRENT_QUALITY = "current_quality"

        fun newInstance(currentQuality: String): QualitySelectionDialog {
            return QualitySelectionDialog().apply {
                arguments = bundleOf(ARG_CURRENT_QUALITY to currentQuality)
            }
        }
    }
}
