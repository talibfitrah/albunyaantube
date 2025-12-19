package com.albunyaan.tube.ui.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R
import com.albunyaan.tube.data.extractor.ResolvedStreams

/**
 * Dialog for selecting download quality.
 *
 * Presents available video qualities from resolved streams plus an audio-only option.
 * When a quality is selected, the result is delivered via Fragment Result API.
 *
 * Note: The actual stream URL selection happens server-side based on targetHeight.
 * The server picks the best available stream that doesn't exceed the target height.
 *
 * Uses Fragment Result API for process-death safety instead of callbacks.
 */
class DownloadQualityDialog : DialogFragment() {

    // For legacy callback support (won't survive process death, but maintains API compat)
    private var onDismissCallback: ((Int?, Boolean) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Restore from arguments (survives process death)
        val heights = arguments?.getIntegerArrayList(ARG_HEIGHTS) ?: arrayListOf()
        val audioOnlyFlags = arguments?.getBooleanArray(ARG_AUDIO_ONLY) ?: booleanArrayOf()

        // Generate localized labels from heights using context
        val labels = heights.mapIndexed { index, height ->
            val isAudioOnly = audioOnlyFlags.getOrElse(index) { false }
            when {
                isAudioOnly -> getString(R.string.download_quality_audio_only)
                height >= 2160 -> getString(R.string.download_quality_4k, height)
                height >= 1440 -> getString(R.string.download_quality_2k, height)
                else -> getString(R.string.download_quality_video, height)
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download_quality_title)
            .setItems(labels.toTypedArray()) { dialog, which ->
                val targetHeight = heights.getOrNull(which)?.takeIf { it != NO_HEIGHT }
                val isAudioOnly = audioOnlyFlags.getOrElse(which) { false }

                // Deliver result via Fragment Result API
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        RESULT_TARGET_HEIGHT to (targetHeight ?: NO_HEIGHT),
                        RESULT_IS_AUDIO_ONLY to isAudioOnly
                    )
                )

                // Also invoke legacy callback if set
                onDismissCallback?.invoke(targetHeight, isAudioOnly)

                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "DownloadQualityDialog"
        const val REQUEST_KEY = "download_quality_request"
        const val RESULT_TARGET_HEIGHT = "target_height"
        const val RESULT_IS_AUDIO_ONLY = "is_audio_only"

        private const val ARG_HEIGHTS = "heights"
        private const val ARG_AUDIO_ONLY = "audio_only_flags"

        /** Sentinel value for null targetHeight in Bundle (Bundle doesn't support null Integers) */
        const val NO_HEIGHT = -1

        /**
         * Creates a new DownloadQualityDialog with options derived from resolved streams.
         *
         * Use Fragment Result API to listen for results:
         * ```
         * parentFragmentManager.setFragmentResultListener(
         *     DownloadQualityDialog.REQUEST_KEY,
         *     viewLifecycleOwner
         * ) { _, result ->
         *     val height = result.getInt(RESULT_TARGET_HEIGHT).takeIf { it != NO_HEIGHT }
         *     val audioOnly = result.getBoolean(RESULT_IS_AUDIO_ONLY)
         *     // Handle selection
         * }
         * ```
         *
         * @param resolvedStreams The resolved video/audio streams from extraction
         */
        fun newInstance(resolvedStreams: ResolvedStreams): DownloadQualityDialog {
            val heights = arrayListOf<Int>()
            val audioOnlyFlags = mutableListOf<Boolean>()

            // Audio-only option (always first) - M4A is the actual format
            heights.add(NO_HEIGHT)
            audioOnlyFlags.add(true)

            // Collect unique heights from video tracks
            val availableHeights = resolvedStreams.videoTracks
                .mapNotNull { it.height }
                .distinct()
                .sortedDescending()

            // Add video options for each unique height
            for (height in availableHeights) {
                heights.add(height)
                audioOnlyFlags.add(false)
            }

            return DownloadQualityDialog().apply {
                arguments = bundleOf(
                    ARG_HEIGHTS to heights,
                    ARG_AUDIO_ONLY to audioOnlyFlags.toBooleanArray()
                )
            }
        }

        /**
         * Legacy factory method for backwards compatibility.
         * The callback is invoked immediately when selection is made.
         *
         * Note: This approach doesn't survive process death.
         * Prefer using newInstance(resolvedStreams) + Fragment Result API.
         */
        fun newInstance(
            resolvedStreams: ResolvedStreams,
            onSelected: (targetHeight: Int?, isAudioOnly: Boolean) -> Unit
        ): DownloadQualityDialog {
            return newInstance(resolvedStreams).apply {
                onDismissCallback = onSelected
            }
        }
    }
}
