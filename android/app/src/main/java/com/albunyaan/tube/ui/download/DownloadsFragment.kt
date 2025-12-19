package com.albunyaan.tube.ui.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentDownloadsBinding
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * P3-T3: DownloadsFragment with Hilt DI
 */
@AndroidEntryPoint
class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private var binding: FragmentDownloadsBinding? = null
    private val adapter = DownloadsAdapter(::onPauseResumeClicked, ::onCancelClicked, ::onOpenClicked)
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
            val recentlyWatchedItem = view.findViewById<View>(R.id.recentlyWatchedItem)
            val historyItem = view.findViewById<View>(R.id.historyItem)

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

            recentlyWatchedItem?.setOnClickListener {
                Toast.makeText(requireContext(), R.string.library_recently_watched_coming_soon, Toast.LENGTH_SHORT).show()
            }

            historyItem?.setOnClickListener {
                Toast.makeText(requireContext(), R.string.library_history_coming_soon, Toast.LENGTH_SHORT).show()
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

    private fun onOpenClicked(entry: DownloadEntry) {
        android.util.Log.d("DownloadsFragment", "onOpenClicked called for: ${entry.request.videoId}")
        val file = viewModel.fileFor(entry) ?: run {
            android.util.Log.e("DownloadsFragment", "fileFor returned null")
            Toast.makeText(requireContext(), R.string.downloads_no_file, Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.d("DownloadsFragment", "File path: ${file.absolutePath}")
        if (!file.exists()) {
            android.util.Log.e("DownloadsFragment", "File does not exist")
            Toast.makeText(requireContext(), getString(R.string.downloads_file_not_found, file.name), Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.d("DownloadsFragment", "File exists, size: ${file.length()} bytes")

        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.downloads.provider",
            file
        )
        android.util.Log.d("DownloadsFragment", "FileProvider URI: $uri")

        val mimeType = if (entry.request.audioOnly) "audio/*" else "video/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Grant temporary read permission to all apps that can handle this intent
        val resolvedActivities = requireContext().packageManager.queryIntentActivities(intent, 0)
        android.util.Log.d("DownloadsFragment", "Found ${resolvedActivities.size} apps that can handle video")
        for (resolvedInfo in resolvedActivities) {
            val packageName = resolvedInfo.activityInfo.packageName
            android.util.Log.d("DownloadsFragment", "Granting permission to: $packageName")
            requireContext().grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        if (resolvedActivities.isNotEmpty()) {
            android.util.Log.d("DownloadsFragment", "Starting activity with intent")
            try {
                startActivity(intent)
                android.util.Log.d("DownloadsFragment", "Activity started successfully")
            } catch (e: Exception) {
                android.util.Log.e("DownloadsFragment", "Failed to start activity", e)
                val errorMessage = e.localizedMessage ?: getString(R.string.error_unknown)
                Toast.makeText(requireContext(), getString(R.string.downloads_open_error, errorMessage), Toast.LENGTH_LONG).show()
            }
        } else {
            android.util.Log.e("DownloadsFragment", "No apps found to handle video")
            Toast.makeText(requireContext(), R.string.downloads_no_player, Toast.LENGTH_SHORT).show()
        }
    }
}
