package com.albunyaan.tube.ui.shorts

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Single-choice dialog listing the audio languages NewPipe surfaced for the
 * currently-bound short. Selection is posted back via Fragment Result API —
 * the hosting fragment listens on [REQUEST_KEY] and calls
 * [PlayerBinder.switchAudioTrack] with the chosen language's representative
 * track.
 *
 * The dialog itself is stateless; all labels and the currently-selected
 * language code are passed via arguments so the dialog survives process
 * death (Bundle-safe).
 */
class AudioLanguageDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val codes = arguments?.getStringArray(ARG_CODES).orEmpty()
        val names = arguments?.getStringArray(ARG_NAMES).orEmpty()
        val originals = arguments?.getBooleanArray(ARG_ORIGINALS) ?: BooleanArray(codes.size)
        val currentCode = arguments?.getString(ARG_CURRENT_CODE)

        val labels = Array(codes.size) { i ->
            val base = names.getOrNull(i).orEmpty()
            if (originals.getOrNull(i) == true) {
                getString(R.string.shorts_audio_track_original_prefix, base)
            } else {
                base
            }
        }

        val checkedIndex = codes.indexOfFirst { it == currentCode }
            .let { if (it >= 0) it else -1 }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shorts_audio_track_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val code = codes.getOrNull(which) ?: return@setSingleChoiceItems
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_SELECTED_LANGUAGE to code)
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "AudioLanguageDialog"
        const val REQUEST_KEY = "audio_language_request"
        const val RESULT_SELECTED_LANGUAGE = "selected_language"

        private const val ARG_CODES = "codes"
        private const val ARG_NAMES = "names"
        private const val ARG_ORIGINALS = "originals"
        private const val ARG_CURRENT_CODE = "current_code"

        /**
         * @param languages ordered list of `(code, displayName, isOriginal)`
         * @param currentCode language code currently active, or null for "none selected yet"
         */
        fun newInstance(
            languages: List<Triple<String, String, Boolean>>,
            currentCode: String?
        ): AudioLanguageDialog {
            val codes = languages.map { it.first }.toTypedArray()
            val names = languages.map { it.second }.toTypedArray()
            val originals = BooleanArray(languages.size) { languages[it].third }
            return AudioLanguageDialog().apply {
                arguments = bundleOf(
                    ARG_CODES to codes,
                    ARG_NAMES to names,
                    ARG_ORIGINALS to originals,
                    ARG_CURRENT_CODE to currentCode
                )
            }
        }
    }
}
