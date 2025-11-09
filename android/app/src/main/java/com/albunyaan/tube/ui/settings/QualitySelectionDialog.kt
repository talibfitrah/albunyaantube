package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Dialog for selecting download/playback quality.
 */
class QualitySelectionDialog : DialogFragment() {

    private var currentQuality: String = "medium"
    private var onQualitySelected: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        currentQuality = arguments?.getString(ARG_CURRENT_QUALITY) ?: "medium"

        val qualities = listOf("low", "medium", "high")
        val qualityNames = arrayOf(
            "Low (360p) - Save data",
            "Medium (720p) - Balanced",
            "High (1080p) - Best quality"
        )
        val currentIndex = qualities.indexOf(currentQuality).takeIf { it >= 0 } ?: 1

        return AlertDialog.Builder(requireContext())
            .setTitle("Download Quality")
            .setSingleChoiceItems(qualityNames, currentIndex) { dialogInterface, which ->
                val selectedQuality = qualities[which]
                onQualitySelected?.invoke(selectedQuality)
                dialogInterface.dismiss()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .create()
    }

    fun setOnQualitySelectedListener(listener: (String) -> Unit) {
        onQualitySelected = listener
    }

    companion object {
        private const val ARG_CURRENT_QUALITY = "current_quality"

        fun newInstance(currentQuality: String): QualitySelectionDialog {
            return QualitySelectionDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_QUALITY, currentQuality)
                }
            }
        }
    }
}
