package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.preferences.SettingsPreferences

/**
 * Dialog for selecting app language.
 * Shows supported languages in their native scripts, plus "System default" option.
 *
 * Uses Fragment Result API for process-death safety instead of callbacks.
 * Register a listener in the parent fragment's onViewCreated:
 *
 * ```
 * childFragmentManager.setFragmentResultListener(
 *     LanguageSelectionDialog.REQUEST_KEY,
 *     viewLifecycleOwner
 * ) { _, result ->
 *     val selection = result.getString(LanguageSelectionDialog.RESULT_LANGUAGE) ?: SettingsPreferences.LOCALE_SYSTEM
 *     // Handle selection - "system", "en", "ar", or "nl"
 * }
 * ```
 */
class LanguageSelectionDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val currentSelection = arguments?.getString(ARG_CURRENT_SELECTION) ?: SettingsPreferences.LOCALE_SYSTEM

        // Use LANGUAGE_SELECTION_KEYS for safe iteration over available language options
        val selections = LocaleManager.LANGUAGE_SELECTION_KEYS
        val ctx = requireContext()
        val displayNames = selections.map { selection ->
            if (selection == SettingsPreferences.LOCALE_SYSTEM) {
                // Show resolved locale for System default, e.g. "الافتراضي (English)"
                LocaleManager.getLanguageDisplayNameWithResolved(ctx, selection)
            } else {
                // Native language names (English, العربية, Nederlands) - use context-aware method for consistency
                LocaleManager.getLanguageDisplayName(ctx, selection)
            }
        }.toTypedArray()
        val currentIndex = selections.indexOf(currentSelection).takeIf { it >= 0 } ?: 0

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_select_title)
            .setSingleChoiceItems(displayNames, currentIndex) { dialogInterface, which ->
                val selectedLanguage = selections[which]
                // Deliver result via Fragment Result API (survives process death)
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_LANGUAGE to selectedLanguage)
                )
                dialogInterface.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "LanguageSelectionDialog"
        const val REQUEST_KEY = "language_selection_request"
        const val RESULT_LANGUAGE = "selected_language"

        private const val ARG_CURRENT_SELECTION = "current_selection"

        /**
         * Create a new dialog instance.
         * @param currentSelection The current locale selection ("system", "en", "ar", "nl")
         */
        fun newInstance(currentSelection: String): LanguageSelectionDialog {
            return LanguageSelectionDialog().apply {
                arguments = bundleOf(ARG_CURRENT_SELECTION to currentSelection)
            }
        }
    }
}
