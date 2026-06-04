package com.albunyaan.tube.ui.me.importflow

import android.os.Bundle
import android.view.View
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.importflow.ImportProgress
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.databinding.FragmentImportYoutubeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * B11: Screen for the YouTube-import flow.
 *
 * Renders each [ImportUiState] in place — all states share the same fragment
 * instance; only the relevant container is made visible.
 *
 * Consent launch: uses [ActivityResultContracts.StartIntentSenderForResult]
 * registered at fragment creation time (before onStart) so it is always
 * ready when NeedsConsent arrives.
 */
@AndroidEntryPoint
class ImportFromYouTubeFragment : Fragment(R.layout.fragment_import_youtube) {

    private val viewModel: ImportViewModel by viewModels()
    private var _binding: FragmentImportYoutubeBinding? = null
    private val binding get() = _binding!!

    private val adapter by lazy {
        ImportReviewAdapter(
            onToggleItem = { id -> viewModel.toggleSelection(id) },
            onGroupSelectAll = { type, selected -> viewModel.setGroupSelected(type, selected) },
        )
    }

    /** Registered before onStart so it survives configuration changes safely. */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(result.data)
    }

    // Track whether start() has been called so we auto-start only once.
    private var startedOnce = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentImportYoutubeBinding.bind(view)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.importButton.setOnClickListener { showImportCautionDialog() }
        binding.doneButton.setOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { viewModel.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    // ── State rendering ───────────────────────────────────────────────────────

    private fun render(state: ImportUiState) {
        // Auto-start on first Idle observation (entry point).
        if (state is ImportUiState.Idle && !startedOnce) {
            startedOnce = true
            viewModel.start()
            return
        }

        // Launch consent UI immediately — do not wait for next render cycle.
        if (state is ImportUiState.NeedsConsent) {
            val request = IntentSenderRequest.Builder(state.pendingIntent.intentSender).build()
            consentLauncher.launch(request)
            // cubic-P3: leave NeedsConsent so a config-change re-render doesn't relaunch consent.
            viewModel.onConsentLaunched()
            return
        }

        showOnly(state)
    }

    /** Toggle visibility of the four container groups based on [state]. */
    private fun showOnly(state: ImportUiState) {
        binding.loadingContainer.isVisible = state is ImportUiState.Authorizing
                || state is ImportUiState.Fetching

        binding.reviewContainer.isVisible = state is ImportUiState.Review
        binding.importingContainer.isVisible = state is ImportUiState.Importing
        binding.doneContainer.isVisible = state is ImportUiState.Done
        binding.errorContainer.isVisible = state is ImportUiState.Error

        when (state) {
            ImportUiState.Authorizing -> {
                binding.loadingLabel.setText(R.string.import_youtube_loading_authorizing)
            }
            ImportUiState.Fetching -> {
                binding.loadingLabel.setText(R.string.import_youtube_loading_fetching)
            }
            is ImportUiState.Review -> {
                adapter.submitReview(state.candidates, state.selected)

                val count = state.selected.size
                binding.importButton.isEnabled = count > 0
                binding.importButton.text =
                    resources.getQuantityString(R.plurals.import_youtube_button_import, count, count)

                val failedText = buildPartialFailureText(state.partialFailureTypes)
                binding.partialFailureNotice.isVisible = failedText != null
                binding.partialFailureNotice.text = failedText

                // Large-screen auto-scroll: post after submitReview to let the
                // RecyclerView lay out, then trigger loadMore if nothing scrollable.
                binding.recycler.post {
                    if (_binding == null) return@post
                    if (!binding.recycler.canScrollVertically(1)
                        && viewModel.uiState.value is ImportUiState.Review
                    ) {
                        // All items fit without scrolling — nothing to page, but we
                        // force a re-layout so items fill the available space properly.
                        binding.recycler.requestLayout()
                    }
                }
            }
            is ImportUiState.Importing -> {
                renderProgress(state.progress)
            }
            is ImportUiState.Done -> {
                val s = state.summary
                val summaryText = getString(
                    R.string.import_youtube_done_summary,
                    s.added,
                    s.sentForReview,
                    s.skipped,
                )
                // F10: if the daily budget cut the run short, tell the user the rest
                // weren't imported (already-imported items are saved; retry later).
                binding.doneSummary.text = if (s.rateLimited) {
                    "$summaryText\n${getString(R.string.import_youtube_done_rate_limited)}"
                } else {
                    summaryText
                }
            }
            is ImportUiState.Error -> {
                binding.errorMessage.text = state.message
                binding.retryButton.isVisible = state.retryable
            }
            // Idle / NeedsConsent handled before showOnly()
            else -> Unit
        }
    }

    private fun renderProgress(progress: ImportProgress) {
        val determinate = progress.total > 0
        binding.importProgress.isIndeterminate = !determinate
        if (determinate) {
            binding.importProgress.max = progress.total
            binding.importProgress.progress = progress.processed
        }
        binding.importProgressLabel.text = when (progress.phase) {
            ImportProgress.Phase.RESOLVING -> getString(R.string.import_youtube_importing_resolving)
            ImportProgress.Phase.WRITING   -> getString(R.string.import_youtube_importing_writing)
            ImportProgress.Phase.DONE      -> getString(R.string.import_youtube_importing_done)
        }
    }

    private fun buildPartialFailureText(failedTypes: Set<CandidateType>): String? {
        if (failedTypes.isEmpty()) return null
        val names = failedTypes.joinToString(", ") { type ->
            when (type) {
                CandidateType.CHANNEL  -> getString(R.string.import_youtube_group_channels_short)
                CandidateType.PLAYLIST -> getString(R.string.import_youtube_group_playlists_short)
                CandidateType.VIDEO    -> getString(R.string.import_youtube_group_videos_short)
            }
        }
        return getString(R.string.import_youtube_partial_failure, names)
    }

    /**
     * Finding 4: show a Sharī'ah-compliance caution before the import runs. Confirming
     * proceeds to [ImportViewModel.confirmImport]; cancelling leaves the user on the
     * review screen with their selection intact.
     */
    private fun showImportCautionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_caution_title)
            .setMessage(R.string.import_caution_message)
            .setPositiveButton(R.string.import_caution_continue) { _, _ -> viewModel.confirmImport() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
