package com.albunyaan.tube.ui.shared

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Single-choice dialog listing the video qualities (heights) available for the
 * currently bound stream. The first item is always "Auto" — selecting it clears
 * any user cap and lets the AdaptiveTrackSelection bandwidth-driven picker run.
 *
 * The hosting fragment listens on [REQUEST_KEY] and reads
 * [RESULT_SELECTED_HEIGHT] (an int — 0 means Auto / no cap, otherwise the
 * pixel-height the user picked).
 */
class QualityPickerDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val heights: IntArray = arguments?.getIntArray(ARG_HEIGHTS) ?: IntArray(0)
        val labels: Array<String> = arguments?.getStringArray(ARG_LABELS) ?: emptyArray()
        val currentHeight = arguments?.getInt(ARG_CURRENT_HEIGHT, AUTO) ?: AUTO

        val autoLabel = "Auto"
        val allLabels: Array<String> = arrayOf(autoLabel) + labels

        // Auto is index 0; specific heights start at index 1
        var heightMatch = -1
        for (i in heights.indices) {
            if (heights[i] == currentHeight) {
                heightMatch = i
                break
            }
        }
        val checkedIndex = if (currentHeight == AUTO || heightMatch < 0) 0 else heightMatch + 1

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Quality")
            .setSingleChoiceItems(allLabels, checkedIndex) { dialog, which ->
                val selectedHeight: Int = if (which == 0) {
                    AUTO
                } else {
                    val idx = which - 1
                    if (idx in heights.indices) heights[idx] else AUTO
                }
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_SELECTED_HEIGHT to selectedHeight)
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "QualityPickerDialog"
        const val REQUEST_KEY = "quality_picker_request"
        const val RESULT_SELECTED_HEIGHT = "selected_height"
        const val AUTO = 0

        private const val ARG_HEIGHTS = "heights"
        private const val ARG_LABELS = "labels"
        private const val ARG_CURRENT_HEIGHT = "current_height"

        /**
         * @param qualities ordered (highest → lowest) list of `(height, label)` pairs.
         *                  e.g. listOf(2160 to "2160p", 1080 to "1080p", 720 to "720p")
         * @param currentHeight currently active cap height, or [AUTO] if no cap.
         */
        fun newInstance(
            qualities: List<Pair<Int, String>>,
            currentHeight: Int = AUTO
        ): QualityPickerDialog {
            val heights = IntArray(qualities.size) { qualities[it].first }
            val labels = qualities.map { it.second }.toTypedArray()
            return QualityPickerDialog().apply {
                arguments = bundleOf(
                    ARG_HEIGHTS to heights,
                    ARG_LABELS to labels,
                    ARG_CURRENT_HEIGHT to currentHeight
                )
            }
        }
    }
}
