package com.albunyaan.tube.ui.player

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack

/**
 * Dialog for selecting video quality
 */
class QualitySelectionDialog : DialogFragment() {

    private var qualities: List<QualityOption> = emptyList()
    private var onQualitySelected: ((VideoTrack?) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val options = qualities.map { it.label }.toTypedArray()
        val selectedIndex = qualities.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: 0

        return AlertDialog.Builder(requireContext())
            .setTitle("Select Quality")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val selected = qualities[which]
                onQualitySelected?.invoke(selected.track)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        fun newInstance(
            resolvedStreams: ResolvedStreams,
            currentQuality: String?,
            onSelected: (VideoTrack?) -> Unit
        ): QualitySelectionDialog {
            return QualitySelectionDialog().apply {
                // Add audio-only option
                val audioOnly = QualityOption(
                    label = "Audio Only",
                    track = null,
                    isSelected = currentQuality == null
                )

                // Add video quality options sorted by quality
                val videoOptions = resolvedStreams.videoTracks
                    .sortedByDescending { it.height ?: 0 }
                    .map { track ->
                        QualityOption(
                            label = track.qualityLabel ?: "${track.height}p",
                            track = track,
                            isSelected = track.qualityLabel == currentQuality
                        )
                    }

                qualities = listOf(audioOnly) + videoOptions
                onQualitySelected = onSelected
            }
        }
    }

    data class QualityOption(
        val label: String,
        val track: VideoTrack?,
        val isSelected: Boolean
    )
}
