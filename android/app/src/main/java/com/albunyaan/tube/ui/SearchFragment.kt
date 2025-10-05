package com.albunyaan.tube.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSearchBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {

    private var binding: FragmentSearchBinding? = null

    private lateinit var searchHistoryAdapter: SearchHistoryAdapter

    private var searchJob: Job? = null
    private val searchHistory = mutableListOf<String>()

    private val PREFS_NAME = "search_prefs"
    private val KEY_SEARCH_HISTORY = "search_history"
    private val MAX_HISTORY_SIZE = 10

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSearchBinding.bind(view)

        setupSearchView()
        setupSearchHistory()
        setupResultsList()
        loadSearchHistory()
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
        // TODO: Implement results list adapter once we have real data
        binding?.searchResultsList?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()

        binding?.apply {
            loadingState.isVisible = true
            emptyState.isVisible = false
            searchHistorySection.isVisible = false
            searchResultsList.isVisible = false
        }

        searchJob = lifecycleScope.launch {
            try {
                // TODO: Call backend search API when connected
                delay(500) // Simulate network call
                displayResults(query)
            } catch (e: Exception) {
                showError("Search failed: ${e.message}")
            }
        }
    }

    private fun displayResults(query: String) {
        binding?.loadingState?.isVisible = false

        // TODO: Replace with real results
        // For now, show empty state
        binding?.apply {
            emptyState.isVisible = true
            emptyStateTitle.text = "Search ready"
            emptyStateMessage.text = "Backend integration coming soon. Searched for: \"$query\""
            searchResultsList.isVisible = false
        }
    }

    private fun showSearchHistory() {
        binding?.apply {
            searchHistorySection.isVisible = searchHistory.isNotEmpty()
            searchResultsList.isVisible = false
            loadingState.isVisible = false
            emptyState.isVisible = false
        }
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
            emptyStateTitle.text = "Error"
            emptyStateMessage.text = message
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        binding = null
        super.onDestroyView()
    }
}
