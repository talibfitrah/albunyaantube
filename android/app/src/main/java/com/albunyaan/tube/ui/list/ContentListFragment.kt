package com.albunyaan.tube.ui.list

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
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.databinding.FragmentHomeBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class ContentListFragment : Fragment(R.layout.fragment_home) {

    protected abstract val contentType: ContentType

    private var binding: FragmentHomeBinding? = null
    private val adapter = ContentAdapter()
    private var currentFilterState: FilterState = FilterState()
    private var categoryChip: Chip? = null
    private var lengthChip: Chip? = null
    private var dateChip: Chip? = null
    private var sortChip: Chip? = null
    private var clearChip: Chip? = null
    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            ServiceLocator.provideFilterManager(),
            ServiceLocator.provideContentRepository(),
            contentType
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view).also { binding = it }
        setupFilterRow(binding)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ContentListFragment.adapter
        }

        binding.listState.retryButton.setOnClickListener { adapter.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.pagingData.collectLatest(adapter::submitData)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.filterState.collect { state ->
                    currentFilterState = state
                    updateFilterChips(state)
                    activity?.invalidateOptionsMenu()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                adapter.loadStateFlow.collect { loadStates ->
                    val binding = binding ?: return@collect
                    val refreshState = loadStates.refresh
                    val isLoading = refreshState is LoadState.Loading
                    val isError = refreshState is LoadState.Error
                    val isEmpty = refreshState is LoadState.NotLoading && adapter.itemCount == 0

                    binding.listState.skeletonContainer.isVisible = isLoading
                    binding.listState.errorContainer.isVisible = isError
                    binding.listState.emptyContainer.isVisible = isEmpty
                    if (isError) {
                        val error = (refreshState as? LoadState.Error)?.error
                        binding.listState.errorDescription.text = error?.localizedMessage
                            ?: getString(R.string.list_error_description)
                    }
                    binding.listFooter.root.isVisible = !isLoading
                    binding.listFooter.paginationStatus.text =
                        getString(R.string.list_footer_status, adapter.itemCount)
                }
            }
        }
    }

    override fun onDestroyView() {
        categoryChip = null
        lengthChip = null
        dateChip = null
        sortChip = null
        clearChip = null
        binding = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.filter_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.filter_clear)?.isVisible = currentFilterState.hasActiveFilters
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.filter_category -> {
                showCategoryDialog()
                true
            }
            R.id.filter_length -> {
                showLengthDialog()
                true
            }
            R.id.filter_date -> {
                showPublishedDateDialog()
                true
            }
            R.id.filter_sort -> {
                showSortDialog()
                true
            }
            R.id.filter_clear -> {
                viewModel.clearFilters()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupFilterRow(binding: FragmentHomeBinding) {
        val chipGroup = binding.filterRow.filterChipGroup
        chipGroup.removeAllViews()

        categoryChip = createFilterChip(getString(R.string.filter_category)) { showCategoryDialog() }
            .also(chipGroup::addView)
        lengthChip = createFilterChip(getString(R.string.filter_length)) { showLengthDialog() }
            .also(chipGroup::addView)
        dateChip = createFilterChip(getString(R.string.filter_date)) { showPublishedDateDialog() }
            .also(chipGroup::addView)
        sortChip = createFilterChip(getString(R.string.filter_sort)) { showSortDialog() }
            .also(chipGroup::addView)
        clearChip = createFilterChip(getString(R.string.filter_clear)) { viewModel.clearFilters() }
            .also(chipGroup::addView)
        clearChip?.isVisible = false
    }

    private fun updateFilterChips(state: FilterState) {
        categoryChip?.text = formatChipText(
            base = getString(R.string.filter_category),
            value = state.category ?: getString(R.string.filter_category_all),
            isDefault = state.category == null
        )
        lengthChip?.text = formatChipText(
            base = getString(R.string.filter_length),
            value = videoLengthLabel(state.videoLength),
            isDefault = state.videoLength == VideoLength.ANY
        )
        dateChip?.text = formatChipText(
            base = getString(R.string.filter_date),
            value = publishedDateLabel(state.publishedDate),
            isDefault = state.publishedDate == PublishedDate.ANY
        )
        sortChip?.text = formatChipText(
            base = getString(R.string.filter_sort),
            value = sortOptionLabel(state.sortOption),
            isDefault = state.sortOption == SortOption.DEFAULT
        )
        clearChip?.isVisible = state.hasActiveFilters
    }

    private fun formatChipText(base: String, value: String, isDefault: Boolean): String {
        return if (isDefault) base else getString(R.string.filter_chip_value, base, value)
    }

    private fun createFilterChip(label: String, onClick: () -> Unit): Chip {
        return Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
            text = label
            isClickable = true
            isCheckable = false
            setOnClickListener { onClick() }
        }
    }

    private fun showCategoryDialog() {
        val entries = resources.getStringArray(R.array.filter_category_entries)
        val values = resources.getStringArray(R.array.filter_category_values)
        val selectedIndex = values.indexOf(currentFilterState.category ?: "").takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_category)
            .setSingleChoiceItems(entries, selectedIndex) { dialog, which ->
                val value = values.getOrNull(which).orEmpty().ifBlank { null }
                viewModel.setCategory(value)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLengthDialog() {
        val options = VideoLength.values()
        val labels = options.map { videoLengthLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(currentFilterState.videoLength)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_length)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                viewModel.setVideoLength(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPublishedDateDialog() {
        val options = PublishedDate.values()
        val labels = options.map { publishedDateLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(currentFilterState.publishedDate)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_date)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                viewModel.setPublishedDate(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSortDialog() {
        val options = SortOption.values()
        val labels = options.map { sortOptionLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(currentFilterState.sortOption)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_sort)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                viewModel.setSortOption(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun videoLengthLabel(option: VideoLength): String = when (option) {
        VideoLength.ANY -> getString(R.string.filter_length_any)
        VideoLength.UNDER_FOUR_MIN -> getString(R.string.filter_length_short)
        VideoLength.FOUR_TO_TWENTY_MIN -> getString(R.string.filter_length_medium)
        VideoLength.OVER_TWENTY_MIN -> getString(R.string.filter_length_long)
    }

    private fun publishedDateLabel(option: PublishedDate): String = when (option) {
        PublishedDate.ANY -> getString(R.string.filter_date_any)
        PublishedDate.LAST_24_HOURS -> getString(R.string.filter_date_last_24_hours)
        PublishedDate.LAST_7_DAYS -> getString(R.string.filter_date_last_7_days)
        PublishedDate.LAST_30_DAYS -> getString(R.string.filter_date_last_30_days)
    }

    private fun sortOptionLabel(option: SortOption): String = when (option) {
        SortOption.DEFAULT -> getString(R.string.filter_sort_default)
        SortOption.MOST_POPULAR -> getString(R.string.filter_sort_popular)
        SortOption.NEWEST -> getString(R.string.filter_sort_newest)
    }
}
