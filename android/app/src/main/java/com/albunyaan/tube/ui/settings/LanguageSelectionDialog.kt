package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R
import com.albunyaan.tube.locale.LocaleManager

/**
 * Dialog for selecting app language.
 * Shows supported languages in their native scripts.
 *
 * Uses Fragment Result API for process-death safety instead of callbacks.
 * Register a listener in the parent fragment's onViewCreated:
 *
 * ```
 * childFragmentManager.setFragmentResultListener(
 *     LanguageSelectionDialog.REQUEST_KEY,
 *     viewLifecycleOwner
 * ) { _, result ->
 *     val language = result.getString(LanguageSelectionDialog.RESULT_LANGUAGE) ?: "en"
 *     // Handle selection
 * }
 * ```
 */
class LanguageSelectionDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val currentLanguage = arguments?.getString(ARG_CURRENT_LANGUAGE) ?: "en"

        val languages = LocaleManager.SUPPORTED_LANGUAGES.keys.toList()
        val languageNames = languages.map { LocaleManager.getLanguageDisplayName(it) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage).takeIf { it >= 0 } ?: 0

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_select_title)
            .setSingleChoiceItems(languageNames, currentIndex) { dialogInterface, which ->
                val selectedLanguage = languages[which]
                // Deliver result via Fragment Result API (survives process death)
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_LANGUAGE to selectedLanguage)
                )
                dialogInterface.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "LanguageSelectionDialog"
        const val REQUEST_KEY = "language_selection_request"
        const val RESULT_LANGUAGE = "selected_language"

        private const val ARG_CURRENT_LANGUAGE = "current_language"

        fun newInstance(currentLanguage: String): LanguageSelectionDialog {
            return LanguageSelectionDialog().apply {
                arguments = bundleOf(ARG_CURRENT_LANGUAGE to currentLanguage)
            }
        }
    }
}
