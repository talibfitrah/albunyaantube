package com.albunyaan.tube.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentChannelsNewBinding
import com.albunyaan.tube.ui.adapters.ChannelAdapter
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import com.albunyaan.tube.ui.utils.AutofillPaginationHelper
import com.albunyaan.tube.ui.utils.isTablet
import com.albunyaan.tube.ui.utils.updateCategoryFilter
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class ChannelsFragmentNew : Fragment(R.layout.fragment_channels_new) {

    private var binding: FragmentChannelsNewBinding? = null
    private lateinit var adapter: ChannelAdapter

    @Inject
    @Named("real")
    lateinit var contentService: ContentService

    @Inject
    lateinit var filterManager: FilterManager

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            contentService,
            ContentType.CHANNELS
        )
    }

    private val autofillHelper = AutofillPaginationHelper(TAG)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelsNewBinding.bind(view)

        setupRecyclerView()
        setupSwipeRefresh()
        setupSearch()
        observeFilters()
        observeViewModel()
        setupCategoriesFab()
    }

    private fun setupSearch() {
        val editText = binding?.searchEditText ?: return
        val clearBtn = binding?.searchClearButton ?: return

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                clearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                autofillHelper.reset()
                viewModel.setSearchQuery(query, ContentListViewModel.TYPING_DEBOUNCE_MS) // the ViewModel debounces: this keystroke drops the old load at once
            }
        })

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = editText.text?.toString() ?: ""
                autofillHelper.reset()
                viewModel.setSearchQuery(query)
                true
            } else false
        }

        clearBtn.setOnClickListener { editText.text?.clear() } // the watcher above brings the full list back, once
    }

    private fun observeFilters() {
        viewLifecycleOwner.lifecycleScope.launch {
            filterManager.state.collectLatest { filterState ->
                Log.d(TAG, "Filter state changed: category=${filterState.category}")
                autofillHelper.reset()
                viewModel.setFilters(filterState)
                binding?.filterChip?.updateCategoryFilter(filterState.category, filterState.categoryName) {
                    Log.d(TAG, "Clearing category filter")
                    filterManager.setCategory(null)
                }
            }
        }
    }

    private fun setupCategoriesFab() {
        binding?.categoriesFab?.setOnClickListener {
            findNavController().navigate(R.id.categoriesFragment)
        }
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setOnRefreshListener {
            autofillHelper.reset()
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter { channel ->
            val args = bundleOf(
                ChannelDetailFragment.ARG_CHANNEL_ID to channel.id,
                ChannelDetailFragment.ARG_CHANNEL_NAME to channel.name,
                ChannelDetailFragment.ARG_CHANNEL_AVATAR_URL to channel.thumbnailUrl,
                ChannelDetailFragment.ARG_EXCLUDED to false
            )
            findNavController().navigate(R.id.action_channelsFragment_to_channelDetailFragment, args)
        }

        binding?.recyclerView?.apply {
            // Use grid layout on tablets for better use of screen real estate
            // Phone: 1 column (linear), Tablet: 3 columns, TV: 4 columns (from resources)
            layoutManager = if (requireContext().isTablet()) {
                val spanCount = resources.getInteger(R.integer.grid_span_count_default).coerceIn(2, 4)
                GridLayoutManager(requireContext(), spanCount)
            } else {
                LinearLayoutManager(requireContext())
            }
            adapter = this@ChannelsFragmentNew.adapter

            // Infinite scroll listener with Fragment-side guards
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Only trigger on scroll down
                    if (dy <= 0) return

                    // Fragment-side guard: early exit if cannot load more
                    if (!viewModel.canLoadMore) return

                    val lm = recyclerView.layoutManager
                    val totalItems = lm?.itemCount ?: return
                    val lastVisible = when (lm) {
                        is GridLayoutManager -> lm.findLastVisibleItemPosition()
                        is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                        else -> return
                    }

                    // Load more when 5 items from bottom (threshold for smooth UX)
                    if (lastVisible >= totalItems - LOAD_MORE_THRESHOLD) {
                        viewModel.loadMore()
                    }
                }
            })
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { state ->
                // A recreated view starts with an empty adapter: put back the rows a load or a failure keeps up.
                if (state !is ContentListViewModel.ContentState.Success && adapter.currentList.isEmpty() && state.items.isNotEmpty()) {
                    adapter.submitList(state.items.filterIsInstance<ContentItem.Channel>())
                }
                when (state) {
                    is ContentListViewModel.ContentState.Loading -> {
                        Log.d(TAG, "Loading channels (type=${state.type})...")
                        when (state.type) {
                            ContentListViewModel.LoadingType.INITIAL -> {
                                binding?.swipeRefresh?.isRefreshing = false
                                binding?.swipeRefresh?.visibility = View.GONE
                                binding?.listSkeleton?.root?.visibility = View.VISIBLE
                                binding?.loadingMore?.visibility = View.GONE
                                binding?.emptyState?.visibility = View.GONE
                            }
                            ContentListViewModel.LoadingType.REFRESH -> {
                                binding?.swipeRefresh?.isRefreshing = true
                                binding?.loadingMore?.visibility = View.GONE
                                binding?.emptyState?.visibility = View.GONE
                            }
                            ContentListViewModel.LoadingType.PAGINATION -> {
                                binding?.swipeRefresh?.isRefreshing = false
                                binding?.loadingMore?.visibility = View.VISIBLE
                            }
                        }
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        val channels = state.items.filterIsInstance<ContentItem.Channel>()
                        Log.d(TAG, "Channels loaded: ${channels.size} items, hasMore=${state.hasMoreData}, search=${state.isSearchActive}")
                        binding?.let { binding ->
                            binding.listSkeleton.root.visibility = View.GONE
                            binding.swipeRefresh.visibility = View.VISIBLE
                            binding.swipeRefresh.isRefreshing = false
                            binding.swipeRefresh.isEnabled = !state.isSearchActive
                            binding.loadingMore.visibility = View.GONE

                            state.paginationError?.let { errorMessage ->
                                val message = if (errorMessage.isBlank()) getString(R.string.list_error_title) else errorMessage
                                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                            }

                            val screenWidthDp = resources.configuration.smallestScreenWidthDp
                            val rv = binding.recyclerView
                            // A new list (another query) opens at its best match; a page appended below, or the same list, keeps its place.
                            val replaced = adapter.currentList.let { it.isNotEmpty() && channels.take(it.size) != it }
                            adapter.submitList(channels) {
                                if (replaced) rv.scrollToPosition(0)
                                autofillHelper.check(
                                    itemCount = channels.size,
                                    hasMoreData = state.hasMoreData,
                                    hasPaginationError = state.paginationError != null,
                                    smallestScreenWidthDp = screenWidthDp,
                                    recyclerView = rv,
                                    isViewActive = { this@ChannelsFragmentNew.binding != null && isAdded },
                                    canLoadMore = { viewModel.canLoadMore },
                                    loadMore = { viewModel.loadMore() }
                                )
                            }

                            if (channels.isEmpty()) {
                                binding.emptyState.visibility = View.VISIBLE
                                binding.recyclerView.visibility = View.GONE
                                if (state.isSearchActive) {
                                    binding.emptyIcon.setImageResource(R.drawable.ic_search)
                                    binding.emptyTitle.text = getString(R.string.search_no_results)
                                    binding.emptySubtitle.text = getString(R.string.search_try_different_hint)
                                } else {
                                    binding.emptyIcon.setImageResource(R.drawable.ic_channels)
                                    binding.emptyTitle.text = getString(R.string.channels_empty_title)
                                    binding.emptySubtitle.text = getString(R.string.channels_empty_subtitle)
                                }
                            } else {
                                binding.emptyState.visibility = View.GONE
                                binding.recyclerView.visibility = View.VISIBLE
                            }
                        }
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        binding?.loadingMore?.visibility = View.GONE
                        binding?.emptyState?.visibility = View.GONE
                        if (adapter.currentList.isEmpty()) {
                            binding?.listSkeleton?.root?.visibility = View.VISIBLE
                            binding?.swipeRefresh?.visibility = View.GONE
                        } else {
                            binding?.listSkeleton?.root?.visibility = View.GONE
                            binding?.swipeRefresh?.visibility = View.VISIBLE
                        }
                        Log.e(TAG, "Error loading channels: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChannelsFragmentNew"
        private const val LOAD_MORE_THRESHOLD = 5
    }

    override fun onDestroyView() {
        autofillHelper.reset()
        binding = null
        super.onDestroyView()
    }
}
