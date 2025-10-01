package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentPlaylistDetailBinding

/**
 * Placeholder playlist detail screen providing hero + download CTA scaffolding until real data arrives.
 */
class PlaylistDetailFragment : Fragment(R.layout.fragment_playlist_detail) {

    private var binding: FragmentPlaylistDetailBinding? = null

    private val playlistId: String by lazy { requireArguments().getString(ARG_PLAYLIST_ID).orEmpty() }
    private val playlistTitleArg: String by lazy { requireArguments().getString(ARG_PLAYLIST_TITLE).orEmpty() }
    private val playlistCategoryArg: String by lazy { requireArguments().getString(ARG_PLAYLIST_CATEGORY).orEmpty() }
    private val playlistCount: Int by lazy { requireArguments().getInt(ARG_PLAYLIST_COUNT, 0) }
    private val downloadPolicy: DownloadPolicy by lazy {
        DownloadPolicy.valueOf(requireArguments().getString(ARG_DOWNLOAD_POLICY) ?: DownloadPolicy.ENABLED.name)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPlaylistDetailBinding.bind(view).apply {
            val resolvedTitle = playlistTitleArg.takeIf { it.isNotBlank() } ?: playlistId
            playlistTitle.text = getString(R.string.playlist_detail_title_placeholder, resolvedTitle)
            val resolvedCategory = playlistCategoryArg
            playlistCategory.text = if (resolvedCategory.isNotBlank()) {
                getString(R.string.playlist_detail_category, resolvedCategory)
            } else {
                ""
            }
            playlistItemCount.text = getString(R.string.playlist_detail_item_count, playlistCount)
            playlistDescription.text = getString(R.string.playlist_detail_description_placeholder)
            heroInitial.text = resolvedTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            configureDownloadButton(downloadPolicy)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun configureDownloadButton(policy: DownloadPolicy) {
        val button = binding?.downloadButton ?: return
        when (policy) {
            DownloadPolicy.ENABLED -> {
                button.text = getString(R.string.playlist_detail_download)
                button.isEnabled = true
            }
            DownloadPolicy.QUEUED -> {
                button.text = getString(R.string.playlist_detail_downloading)
                button.isEnabled = false
            }
            DownloadPolicy.DISABLED -> {
                button.text = getString(R.string.playlist_detail_download_disabled)
                button.isEnabled = false
            }
        }
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_PLAYLIST_TITLE = "playlistTitle"
        const val ARG_PLAYLIST_CATEGORY = "playlistCategory"
        const val ARG_PLAYLIST_COUNT = "playlistCount"
        const val ARG_DOWNLOAD_POLICY = "downloadPolicy"
    }
}

enum class DownloadPolicy { ENABLED, QUEUED, DISABLED }
