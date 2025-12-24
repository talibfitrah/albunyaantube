package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R
import com.albunyaan.tube.preferences.SettingsPreferences

/**
 * Dialog for selecting app theme (System/Light/Dark).
 *
 * Uses Fragment Result API for process-death safety instead of callbacks.
 * Register a listener in the parent fragment's onViewCreated:
 *
 * ```
 * childFragmentManager.setFragmentResultListener(
 *     ThemeSelectionDialog.REQUEST_KEY,
 *     viewLifecycleOwner
 * ) { _, result ->
 *     val theme = result.getString(ThemeSelectionDialog.RESULT_THEME)
 *         ?: SettingsPreferences.THEME_SYSTEM
 *     // Handle selection - THEME_SYSTEM, THEME_LIGHT, or THEME_DARK
 * }
 * ```
 */
class ThemeSelectionDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val currentTheme = arguments?.getString(ARG_CURRENT_THEME)
            ?: SettingsPreferences.THEME_SYSTEM

        val themes = listOf(
            SettingsPreferences.THEME_SYSTEM,
            SettingsPreferences.THEME_LIGHT,
            SettingsPreferences.THEME_DARK
        )
        val themeNames = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val currentIndex = themes.indexOf(currentTheme).takeIf { it >= 0 } ?: 0

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme_select_title)
            .setSingleChoiceItems(themeNames, currentIndex) { dialogInterface, which ->
                val selectedTheme = themes[which]
                // Deliver result via Fragment Result API (survives process death)
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_THEME to selectedTheme)
                )
                dialogInterface.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "ThemeSelectionDialog"
        const val REQUEST_KEY = "theme_selection_request"
        const val RESULT_THEME = "selected_theme"

        private const val ARG_CURRENT_THEME = "current_theme"

        /**
         * Create a new dialog instance.
         * @param currentTheme The current theme (THEME_SYSTEM, THEME_LIGHT, or THEME_DARK)
         */
        fun newInstance(currentTheme: String): ThemeSelectionDialog {
            return ThemeSelectionDialog().apply {
                arguments = bundleOf(ARG_CURRENT_THEME to currentTheme)
            }
        }
    }
}
