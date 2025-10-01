package com.albunyaan.tube.ui.home

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentHomeBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            ServiceLocator.provideFilterManager(),
            ServiceLocator.provideContentRepository()
        )
    }
    private val contentAdapter = ContentAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view).also { binding = it }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = contentAdapter

        binding.listState.retryButton.setOnClickListener { contentAdapter.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.pagingData.collectLatest { pagingData ->
                    contentAdapter.submitData(pagingData)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                contentAdapter.loadStateFlow.collect { loadStates ->
                    val isLoading = loadStates.refresh is androidx.paging.LoadState.Loading
                    val isError = loadStates.refresh is androidx.paging.LoadState.Error
                    val isEmpty = loadStates.refresh is androidx.paging.LoadState.NotLoading && contentAdapter.itemCount == 0
                    binding.listState.skeletonContainer.isVisible = isLoading
                    binding.listState.errorContainer.isVisible = isError
                    binding.listState.emptyContainer.isVisible = isEmpty
                    binding.listFooter.root.isVisible = !isLoading
                    binding.listFooter.paginationStatus.text = getString(
                        R.string.list_footer_status,
                        contentAdapter.itemCount
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.filter_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.filter_clear -> {
                viewModel.clearFilters()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
