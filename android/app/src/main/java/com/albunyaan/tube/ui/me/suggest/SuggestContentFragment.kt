package com.albunyaan.tube.ui.me.suggest

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import com.albunyaan.tube.databinding.FragmentSuggestContentBinding
import com.albunyaan.tube.ui.me.submissions.SubmitContentBottomSheet
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SuggestContentFragment : Fragment(R.layout.fragment_suggest_content) {

    private var _binding: FragmentSuggestContentBinding? = null
    private val binding get() = _binding!!
    private val vm: SuggestContentViewModel by viewModels()

    // Tracks consecutive auto-load attempts when chip filter hides all visible items,
    // so we page forward to find matching content without looping indefinitely.
    private var emptyFilterAutofillCount = 0
    // Deduplicates transient Snackbars across repeatOnLifecycle re-subscriptions.
    private var shownSnackbarKey: String? = null

    private val adapter by lazy {
        SuggestResultsAdapter { hit ->
            if (hit.alreadyKnown && hit.knownStatus in setOf("APPROVED", "PENDING")) {
                Snackbar.make(binding.root, R.string.suggest_already_in_registry, Snackbar.LENGTH_SHORT).show()
            } else {
                SubmitContentBottomSheet.newInstance(
                    prefillUrl = hit.url,
                    prefillName = hit.name,
                    prefillThumbnailUrl = hit.thumbnailUrl,
                ).show(childFragmentManager, SubmitContentBottomSheet.TAG)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSuggestContentBinding.bind(view)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val cols = resources.getInteger(R.integer.suggest_grid_columns)
        binding.results.layoutManager =
            if (cols > 1) GridLayoutManager(requireContext(), cols)
            else LinearLayoutManager(requireContext())
        binding.results.adapter = adapter

        binding.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= lm.itemCount - 10) {
                    vm.loadMore()
                }
            }
        })

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                vm.onQueryChange(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.typeChips.setOnCheckedStateChangeListener { _, ids ->
            val type = when (ids.firstOrNull()) {
                R.id.chipAll      -> YouTubeContentTypeDto.ALL
                R.id.chipChannel  -> YouTubeContentTypeDto.CHANNEL
                R.id.chipPlaylist -> YouTubeContentTypeDto.PLAYLIST
                R.id.chipVideo    -> YouTubeContentTypeDto.VIDEO
                else              -> YouTubeContentTypeDto.ALL
            }
            vm.onTypeChange(type)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: SuggestUiState) {
        binding.loading.visibility = if (state is SuggestUiState.Loading) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (state is SuggestUiState.Empty) View.VISIBLE else View.GONE
        when (state) {
            is SuggestUiState.Idle, is SuggestUiState.Loading -> {
                emptyFilterAutofillCount = 0
                shownSnackbarKey = null
                adapter.submitList(emptyList())
            }
            is SuggestUiState.Empty -> {
                binding.emptyState.text = getString(
                    R.string.suggest_empty_results,
                    binding.searchInput.text?.toString().orEmpty()
                )
                adapter.submitList(emptyList())
            }
            is SuggestUiState.Results -> {
                adapter.submitList(state.items)
                if (state.items.isEmpty() && state.nextPageToken != null && !state.loadingMore) {
                    // Filter hides all visible items but more pages exist — auto-page forward
                    // to find matching content, capped at 3 consecutive attempts per query.
                    if (emptyFilterAutofillCount < 3) {
                        emptyFilterAutofillCount++
                        vm.loadMore()
                    }
                } else {
                    if (state.items.isNotEmpty()) emptyFilterAutofillCount = 0
                    // Standard scroll-based autofill for large screens where all items fit without scrolling.
                    binding.results.post {
                        if (_binding == null) return@post
                        if (!binding.results.canScrollVertically(1)
                            && state.nextPageToken != null
                            && !state.loadingMore
                        ) vm.loadMore()
                    }
                }
            }
            is SuggestUiState.Error -> {
                val msg = if (state.formatArg != null) getString(state.messageRes, state.formatArg)
                          else getString(state.messageRes)
                val key = "error:$msg"
                if (key != shownSnackbarKey) {
                    shownSnackbarKey = key
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                }
            }
            is SuggestUiState.RateLimited -> {
                val key = "rate_limited:${state.retryAfterSec}"
                if (key != shownSnackbarKey) {
                    shownSnackbarKey = key
                    Snackbar.make(binding.root, R.string.suggest_rate_limited, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
