package com.albunyaan.tube.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.FragmentSearchBinding
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P3-T4: SearchFragment with Hilt DI
 */
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {

    private var binding: FragmentSearchBinding? = null

    private lateinit var searchHistoryAdapter: SearchHistoryAdapter
    private lateinit var searchResultsAdapter: SearchResultsAdapter

    @Inject
    lateinit var imageLoader: ImageLoader

    private val viewModel: SearchViewModel by viewModels()

    private var searchJob: Job? = null
    private val searchHistory = mutableListOf<String>()

    private val PREFS_NAME = "search_prefs"
    private val KEY_SEARCH_HISTORY = "search_history"
    private val MAX_HISTORY_SIZE = 10

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSearchBinding.bind(view)

        setupToolbar()
        setupSearchView()
        setupSearchHistory()
        setupResultsList()
        loadSearchHistory()
        observeSearchResults()
    }

    private fun setupToolbar() {
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupSearchView() {
        binding?.searchView?.apply {
            // Auto-focus the search view
            requestFocus()
            isIconified = false

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    query?.let {
                        if (it.isNotBlank()) {
                            performSearch(it)
                            saveToHistory(it)
                        }
                    }
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    // Debounce search as user types
                    searchJob?.cancel()

                    if (newText.isNullOrBlank()) {
                        showSearchHistory()
                    } else {
                        searchJob = lifecycleScope.launch {
                            delay(500) // 500ms debounce
                            if (newText.length >= 2) {
                                performSearch(newText)
                            }
                        }
                    }
                    return true
                }
            })
        }

        // Show search history initially
        showSearchHistory()
    }

    private fun setupSearchHistory() {
        searchHistoryAdapter = SearchHistoryAdapter(
            onItemClick = { query ->
                binding?.searchView?.setQuery(query, true)
            },
            onDeleteClick = { query ->
                removeFromHistory(query)
            }
        )

        binding?.searchHistoryList?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchHistoryAdapter
            addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
        }

        binding?.clearHistoryButton?.setOnClickListener {
            clearHistory()
        }
    }

    private fun setupResultsList() {
        searchResultsAdapter = SearchResultsAdapter(
            imageLoader = imageLoader,
            enableImages = true, // Always enable images with Hilt DI
            onItemClick = { item -> handleItemClick(item) }
        )

        binding?.searchResultsList?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchResultsAdapter
            // No divider decoration - item layouts have their own spacing
        }
    }

    private fun performSearch(query: String) {
        viewModel.search(query)
    }

    private fun observeSearchResults() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchResults.collect { state ->
                when (state) {
                    is SearchViewModel.SearchState.Empty -> {
                        binding?.apply {
                            loadingState.isVisible = false
                            emptyState.isVisible = false
                            searchHistorySection.isVisible = searchHistory.isNotEmpty()
                            searchResultsList.isVisible = false
                        }
                    }
                    is SearchViewModel.SearchState.Loading -> {
                        binding?.apply {
                            loadingState.isVisible = true
                            emptyState.isVisible = false
                            searchHistorySection.isVisible = false
                            searchResultsList.isVisible = false
                        }
                    }
                    is SearchViewModel.SearchState.Success -> {
                        binding?.apply {
                            loadingState.isVisible = false
                            emptyState.isVisible = false
                            searchHistorySection.isVisible = false
                            searchResultsList.isVisible = true
                        }
                        searchResultsAdapter.submitList(state.results)
                    }
                    is SearchViewModel.SearchState.NoResults -> {
                        binding?.apply {
                            loadingState.isVisible = false
                            emptyState.isVisible = true
                            emptyStateTitle.text = getString(R.string.search_no_results)
                            emptyStateMessage.text = getString(R.string.search_try_different, state.query)
                            searchHistorySection.isVisible = false
                            searchResultsList.isVisible = false
                        }
                    }
                    is SearchViewModel.SearchState.Error -> {
                        // Log the actual error for debugging, show generic message to user
                        android.util.Log.e("SearchFragment", "Search error: ${state.message}")
                        showError(getString(R.string.search_error_generic))
                    }
                }
            }
        }
    }

    private fun showSearchHistory() {
        viewModel.clearResults()
    }

    private fun loadSearchHistory() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyString = prefs.getString(KEY_SEARCH_HISTORY, "") ?: ""

        searchHistory.clear()
        if (historyString.isNotBlank()) {
            searchHistory.addAll(historyString.split("|").filter { it.isNotBlank() })
        }

        searchHistoryAdapter.submitList(searchHistory.toList())
        showSearchHistory()
    }

    private fun saveToHistory(query: String) {
        // Remove if already exists
        searchHistory.remove(query)

        // Add to beginning
        searchHistory.add(0, query)

        // Trim to max size
        while (searchHistory.size > MAX_HISTORY_SIZE) {
            searchHistory.removeLast()
        }

        // Save to SharedPreferences
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEARCH_HISTORY, searchHistory.joinToString("|")).apply()

        searchHistoryAdapter.submitList(searchHistory.toList())
    }

    private fun removeFromHistory(query: String) {
        searchHistory.remove(query)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEARCH_HISTORY, searchHistory.joinToString("|")).apply()

        searchHistoryAdapter.submitList(searchHistory.toList())
        showSearchHistory()
    }

    private fun clearHistory() {
        searchHistory.clear()

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()

        searchHistoryAdapter.submitList(searchHistory.toList())
        showSearchHistory()
    }

    private fun showError(message: String) {
        binding?.apply {
            loadingState.isVisible = false
            emptyState.isVisible = true
            emptyStateTitle.text = getString(R.string.error_title)
            emptyStateMessage.text = message
            searchHistorySection.isVisible = false
            searchResultsList.isVisible = false
        }
    }

    private fun handleItemClick(item: ContentItem) {
        when (item) {
            is ContentItem.Video -> {
                findNavController().navigate(
                    R.id.action_global_playerFragment,
                    android.os.Bundle().apply {
                        putString("videoId", item.id)
                        putString("title", item.title)
                        putString("channelName", item.category)
                        putString("thumbnailUrl", item.thumbnailUrl)
                        putString("description", item.description)
                        putInt("durationSeconds", item.durationSeconds)
                        putLong("viewCount", item.viewCount ?: -1L)
                    }
                )
            }
            is ContentItem.Channel -> {
                findNavController().navigate(
                    R.id.action_global_channelDetailFragment,
                    android.os.Bundle().apply {
                        putString("channelId", item.id)
                        putString("channelName", item.name)
                    }
                )
            }
            is ContentItem.Playlist -> {
                findNavController().navigate(
                    R.id.action_global_playlistDetailFragment,
                    android.os.Bundle().apply {
                        putString("playlistId", item.id)
                        putString("playlistTitle", item.title)
                        putString("playlistCategory", item.category)
                        putInt("playlistCount", item.itemCount)
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding = null
        super.onDestroyView()
    }
}
