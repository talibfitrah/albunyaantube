package com.albunyaan.tube.ui.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentDownloadsBinding
import com.albunyaan.tube.download.DownloadEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * P3-T3: DownloadsFragment with Hilt DI
 */
@AndroidEntryPoint
class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private var binding: FragmentDownloadsBinding? = null
    private val adapter = DownloadsAdapter(::onPauseResumeClicked, ::onCancelClicked, ::onOpenClicked)
    private val viewModel: DownloadViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentDownloadsBinding.bind(view).also { binding = it }

        setupToolbar()
        setupDownloadsList()
        setupLibraryItems()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloads.collectLatest { entries ->
                adapter.submitList(entries)
                binding.emptyDownloads.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                binding.downloadsRecyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

                // Update storage info (simplified for now)
                val downloadCount = entries.size
                binding.storageText.text = "$downloadCount downloads • 0 MB of 500 MB"
                binding.storageProgress.progress = 0
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
            val savedItem = view.findViewById<View>(R.id.savedItem)
            val recentlyWatchedItem = view.findViewById<View>(R.id.recentlyWatchedItem)
            val historyItem = view.findViewById<View>(R.id.historyItem)

            savedItem?.setOnClickListener {
                // TODO: Navigate to saved videos
                Toast.makeText(requireContext(), "Saved videos", Toast.LENGTH_SHORT).show()
            }

            recentlyWatchedItem?.setOnClickListener {
                // TODO: Navigate to recently watched
                Toast.makeText(requireContext(), "Recently watched", Toast.LENGTH_SHORT).show()
            }

            historyItem?.setOnClickListener {
                // TODO: Navigate to history
                Toast.makeText(requireContext(), "History", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "No file found", Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.d("DownloadsFragment", "File path: ${file.absolutePath}")
        if (!file.exists()) {
            android.util.Log.e("DownloadsFragment", "File does not exist")
            Toast.makeText(requireContext(), "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            android.util.Log.e("DownloadsFragment", "No apps found to handle video")
            Toast.makeText(requireContext(), "No video player found", Toast.LENGTH_SHORT).show()
        }
    }
}
