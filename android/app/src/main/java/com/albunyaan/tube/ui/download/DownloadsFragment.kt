package com.albunyaan.tube.ui.download

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentDownloadsBinding
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadStorage
import com.albunyaan.tube.ui.player.PlayerFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P3-T3: DownloadsFragment with Hilt DI
 */
@AndroidEntryPoint
class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private var binding: FragmentDownloadsBinding? = null
    private val adapter = DownloadsAdapter(
        onPauseResume = ::onPauseResumeClicked,
        onCancel = ::onCancelClicked,
        onOpen = ::onOpenClicked,
        onRetry = ::onRetryClicked,
        onRemove = ::onRemoveClicked,
        onDelete = ::onDeleteClicked
    )
    private val viewModel: DownloadViewModel by viewModels()

    @Inject
    lateinit var downloadStorage: DownloadStorage

    @Inject
    lateinit var favoritesRepository: FavoritesRepository

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentDownloadsBinding.bind(view).also { binding = it }

        setupToolbar()
        setupDownloadsList()
        setupLibraryItems()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloads.collectLatest { entries ->
                    adapter.submitList(entries)
                    binding.emptyDownloads.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    binding.downloadsRecyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

                    // Update storage info with real data from DownloadStorage
                    val downloadCount = entries.size
                    val usedBytes = downloadStorage.getCurrentDownloadSize()
                    val availableBytes = downloadStorage.getAvailableDeviceStorage()
                    val totalBytes = downloadStorage.getTotalDeviceStorage()

                    val usedStr = Formatter.formatShortFileSize(requireContext(), usedBytes)
                    val availableStr = Formatter.formatShortFileSize(requireContext(), availableBytes)

                    binding.storageText.text = getString(R.string.downloads_storage_format, downloadCount, usedStr, availableStr)

                    // Progress shows download usage as percentage of total device storage.
                    // Using total storage (not available) provides a stable baseline that
                    // doesn't fluctuate as other apps consume device storage.
                    val progress = if (totalBytes > 0) {
                        ((usedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    binding.storageProgress.progress = progress
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshPersistedDownloads()
    }

    private fun setupToolbar() {
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupDownloadsList() {
        binding?.downloadsRecyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
            adapter = this@DownloadsFragment.adapter
        }
    }

    private fun setupLibraryItems() {
        binding?.root?.let { view ->
            val favoritesItem = view.findViewById<View>(R.id.favoritesItem)
            val favoritesCount = view.findViewById<TextView>(R.id.favoritesCount)

            // Navigate to Favorites
            favoritesItem?.setOnClickListener {
                findNavController().navigate(R.id.action_downloadsFragment_to_favoritesFragment)
            }

            // Observe favorites count and update the count text
            favoritesCount?.let { countView ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        favoritesRepository.getFavoriteCount().collectLatest { count ->
                            countView.text = resources.getQuantityString(R.plurals.video_count, count, count)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun onPauseResumeClicked(entry: DownloadEntry) {
        when (entry.status) {
            com.albunyaan.tube.download.DownloadStatus.RUNNING -> viewModel.pause(entry.request.id)
            com.albunyaan.tube.download.DownloadStatus.PAUSED,
            com.albunyaan.tube.download.DownloadStatus.QUEUED -> viewModel.resume(entry.request.id)
            else -> Unit
        }
    }

    private fun onCancelClicked(entry: DownloadEntry) {
        viewModel.cancel(entry.request.id)
    }

    private fun onRetryClicked(entry: DownloadEntry) {
        android.util.Log.d("DownloadsFragment", "Retry clicked for: ${entry.request.videoId}")
        viewModel.retry(entry.request.id)
    }

    private fun onRemoveClicked(entry: DownloadEntry) {
        android.util.Log.d("DownloadsFragment", "Remove clicked for: ${entry.request.videoId}")
        viewModel.remove(entry.request.id)
    }

    private fun onDeleteClicked(entry: DownloadEntry) {
        android.util.Log.d("DownloadsFragment", "Delete clicked for: ${entry.request.videoId}")
        val success = viewModel.delete(entry.request.id)
        val messageRes = if (success) R.string.download_deleted else R.string.download_delete_failed
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    /** Plays the download in FitrahTube's own player, from its local file (1.0.3). */
    private fun onOpenClicked(entry: DownloadEntry) {
        findNavController().navigate(
            R.id.action_global_playerFragment,
            Bundle().apply { putString(PlayerFragment.ARG_DOWNLOAD_ID, entry.request.id) },
        )
    }
}
