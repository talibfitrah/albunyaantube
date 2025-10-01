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
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentDownloadsBinding
import com.albunyaan.tube.download.DownloadEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private var binding: FragmentDownloadsBinding? = null
    private val adapter = DownloadsAdapter(::onPauseResumeClicked, ::onCancelClicked, ::onOpenClicked)
    private val viewModel: DownloadViewModel by viewModels {
        DownloadViewModel.Factory(
            ServiceLocator.provideDownloadRepository()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentDownloadsBinding.bind(view).also { binding = it }
        binding.downloadList.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadList.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.downloadList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloads.collectLatest { entries ->
                adapter.submitList(entries)
                binding.downloadsEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
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
        val file = viewModel.fileFor(entry) ?: run {
            Toast.makeText(requireContext(), R.string.download_toast_no_viewer, Toast.LENGTH_SHORT).show()
            return
        }
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.download_toast_no_viewer, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.downloads.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = if (entry.request.audioOnly) "audio/*" else "video/*"
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.download_toast_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }
}
