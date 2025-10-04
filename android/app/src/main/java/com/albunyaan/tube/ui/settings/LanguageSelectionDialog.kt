package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.albunyaan.tube.locale.LocaleManager

/**
 * Dialog for selecting app language.
 * Shows supported languages in their native scripts.
 */
class LanguageSelectionDialog : DialogFragment() {

    private var currentLanguage: String = "en"
    private var onLanguageSelected: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        currentLanguage = arguments?.getString(ARG_CURRENT_LANGUAGE) ?: "en"

        val languages = LocaleManager.SUPPORTED_LANGUAGES.keys.toList()
        val languageNames = languages.map { LocaleManager.getLanguageDisplayName(it) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage).takeIf { it >= 0 } ?: 0

        return AlertDialog.Builder(requireContext())
            .setTitle("Select Language")
            .setSingleChoiceItems(languageNames, currentIndex) { dialogInterface, which ->
                val selectedLanguage = languages[which]
                onLanguageSelected?.invoke(selectedLanguage)
                dialogInterface.dismiss()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .create()
    }

    fun setOnLanguageSelectedListener(listener: (String) -> Unit) {
        onLanguageSelected = listener
    }

    companion object {
        private const val ARG_CURRENT_LANGUAGE = "current_language"

        fun newInstance(currentLanguage: String): LanguageSelectionDialog {
            return LanguageSelectionDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_LANGUAGE, currentLanguage)
                }
            }
        }
    }
}
