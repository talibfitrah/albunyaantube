package com.albunyaan.tube.ui.list

import android.content.Context
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.albunyaan.tube.R
import com.albunyaan.tube.analytics.ErrorCategory
import com.albunyaan.tube.analytics.ListMetricsReporter
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.PublishedDate
import com.albunyaan.tube.data.filters.SortOption
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.databinding.FragmentHomeBinding
import com.albunyaan.tube.databinding.DialogFilterListBinding
import com.google.android.material.chip.Chip
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.LinkedHashSet
import kotlin.math.max
import retrofit2.HttpException
import javax.inject.Inject

/**
 * P3-T2: Abstract base fragment for content lists with Hilt DI
 */
@AndroidEntryPoint
abstract class ContentListFragment : Fragment(R.layout.fragment_home) {

    protected abstract val contentType: ContentType

    @Inject
    lateinit var viewModelFactory: ContentListViewModel.Factory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var metricsReporter: ListMetricsReporter

    private var binding: FragmentHomeBinding? = null
    private lateinit var adapter: ContentAdapter
    private var currentFilterState: FilterState = FilterState()
    private var categoryChip: Chip? = null
    private var lengthChip: Chip? = null
    private var dateChip: Chip? = null
    private var sortChip: Chip? = null
    private var clearChip: Chip? = null
    private var lastMetricsSnapshot: MetricsSnapshot? = null
    private lateinit var thumbnailPrefetcher: ThumbnailPrefetcher
    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.provideFactory(viewModelFactory, contentType)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Always enable images - configuration via BuildConfig removed in Hilt migration
        val enableImages = true
        adapter = ContentAdapter(imageLoader, enableImages)
        val prefetchDistance = if (enableImages) PREFETCH_ITEM_COUNT else 0
        thumbnailPrefetcher = ThumbnailPrefetcher(context.applicationContext, imageLoader, prefetchDistance, PREFETCH_TRACK_LIMIT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view).also { binding = it }
        setupFilterRow(binding)
        adapter.setOnItemClickListener(::onContentClicked)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                initialPrefetchItemCount = PREFETCH_ITEM_COUNT
            }
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            setHasFixedSize(true)
            adapter = this@ContentListFragment.adapter
        }
        thumbnailPrefetcher.attach(binding.recyclerView, adapter)

        binding.listState.retryButton.setOnClickListener {
            metricsReporter.onRetry(contentType)
            adapter.retry()
        }
        binding.listState.clearFiltersButton.setOnClickListener { clearAllFilters() }

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
                    val uiState = when {
                        refreshState is LoadState.Loading -> UiState.Loading
                        refreshState is LoadState.Error -> UiState.Error(categorizeError(refreshState.error))
                        refreshState is LoadState.NotLoading && adapter.itemCount == 0 -> UiState.Empty(currentFilterState.hasActiveFilters)
                        refreshState is LoadState.NotLoading -> UiState.Data(adapter.itemCount)
                        else -> UiState.Loading
                    }
                    renderUi(binding, uiState)
                    reportMetrics(uiState)
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
        binding?.recyclerView?.removeOnScrollListener(thumbnailPrefetcher)
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
                clearAllFilters()
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
        clearChip = createFilterChip(getString(R.string.filter_clear)) { clearAllFilters() }
            .also(chipGroup::addView)
        clearChip?.isVisible = false

        updateFilterChips(currentFilterState)
    }

    private fun updateFilterChips(state: FilterState) {
        categoryChip?.text = formatChipText(
            base = getString(R.string.filter_category),
            value = state.category ?: getString(R.string.filter_category_all),
            isDefault = state.category == null
        )
        applyChipStyle(categoryChip, state.category == null)
        lengthChip?.text = formatChipText(
            base = getString(R.string.filter_length),
            value = videoLengthLabel(state.videoLength),
            isDefault = state.videoLength == VideoLength.ANY
        )
        applyChipStyle(lengthChip, state.videoLength == VideoLength.ANY)
        dateChip?.text = formatChipText(
            base = getString(R.string.filter_date),
            value = publishedDateLabel(state.publishedDate),
            isDefault = state.publishedDate == PublishedDate.ANY
        )
        applyChipStyle(dateChip, state.publishedDate == PublishedDate.ANY)
        sortChip?.text = formatChipText(
            base = getString(R.string.filter_sort),
            value = sortOptionLabel(state.sortOption),
            isDefault = state.sortOption == SortOption.DEFAULT
        )
        applyChipStyle(sortChip, state.sortOption == SortOption.DEFAULT)
        clearChip?.isVisible = state.hasActiveFilters
        clearChip?.let { chip ->
            val context = chip.context
            chip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.filter_chip_default_bg)
            )
            chip.setTextColor(ContextCompat.getColor(context, R.color.filter_chip_default_text))
            chip.chipIcon = null
            chip.isChipIconVisible = false
        }
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
            minimumHeight = resources.getDimensionPixelSize(R.dimen.filter_chip_height)
            chipCornerRadius = resources.getDimension(R.dimen.filter_chip_radius)
            chipStrokeWidth = 2f
            chipStrokeColor = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.primary_green, null)
            )
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                resources.getColor(android.R.color.white, null)
            )
            setTextColor(resources.getColor(R.color.primary_green, null))
            isChipIconVisible = false
            checkedIcon = null
        }
    }

    private fun showCategoryDialog() {
        val entries = resources.getStringArray(R.array.filter_category_entries)
        val values = resources.getStringArray(R.array.filter_category_values)
        val selectedIndex = values.indexOf(currentFilterState.category ?: "").takeIf { it >= 0 } ?: 0
        showSingleChoiceBottomSheet(
            titleRes = R.string.filter_category,
            entries = entries,
            selectedIndex = selectedIndex
        ) { which ->
            val value = values.getOrNull(which).orEmpty().ifBlank { null }
            viewModel.setCategory(value)
        }
    }

    private fun showLengthDialog() {
        val options = VideoLength.values()
        val labels = options.map { videoLengthLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(currentFilterState.videoLength)
        showSingleChoiceBottomSheet(
            titleRes = R.string.filter_length,
            entries = labels,
            selectedIndex = selectedIndex
        ) { which ->
            viewModel.setVideoLength(options[which])
        }
    }

    private fun showPublishedDateDialog() {
        val options = PublishedDate.values()
        val labels = options.map { publishedDateLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(currentFilterState.publishedDate)
        showSingleChoiceBottomSheet(
            titleRes = R.string.filter_date,
            entries = labels,
            selectedIndex = selectedIndex
        ) { which ->
            viewModel.setPublishedDate(options[which])
        }
    }

    private fun showSortDialog() {
        val options = SortOption.values()
        val labels = options.map { sortOptionLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(currentFilterState.sortOption)
        showSingleChoiceBottomSheet(
            titleRes = R.string.filter_sort,
            entries = labels,
            selectedIndex = selectedIndex
        ) { which ->
            viewModel.setSortOption(options[which])
        }
    }

    private fun renderUi(binding: FragmentHomeBinding, state: UiState) {
        val stateBinding = binding.listState
        stateBinding.skeletonContainer.isVisible = state is UiState.Loading
        stateBinding.errorContainer.isVisible = state is UiState.Error
        stateBinding.emptyContainer.isVisible = state is UiState.Empty

        when (state) {
            UiState.Loading -> {
                binding.listFooter.root.isVisible = false
                stateBinding.clearFiltersButton.isVisible = false
            }
            is UiState.Error -> {
                val titleRes = when (state.category) {
                    ErrorCategory.OFFLINE -> R.string.list_error_offline_title
                    else -> R.string.list_error_title
                }
                val bodyRes = when (state.category) {
                    ErrorCategory.OFFLINE -> R.string.list_error_offline_body
                    ErrorCategory.SERVER -> R.string.list_error_server_body
                    ErrorCategory.UNKNOWN -> R.string.list_error_description
                }
                stateBinding.errorTitle.setText(titleRes)
                stateBinding.errorDescription.setText(bodyRes)
                binding.listFooter.root.isVisible = false
                stateBinding.clearFiltersButton.isVisible = false
            }
            is UiState.Empty -> {
                stateBinding.clearFiltersButton.isVisible = state.filtersActive
                binding.listFooter.root.isVisible = true
                binding.listFooter.paginationStatus.text =
                    getString(R.string.list_footer_status, 0)
            }
            is UiState.Data -> {
                stateBinding.clearFiltersButton.isVisible = false
                binding.listFooter.root.isVisible = true
                binding.listFooter.paginationStatus.text =
                    getString(R.string.list_footer_status, state.count)
            }
        }
    }

    private fun reportMetrics(state: UiState) {
        when (state) {
            UiState.Loading -> {
                lastMetricsSnapshot = null
            }
            is UiState.Data -> {
                val snapshot = MetricsSnapshot.Success(state.count)
                if (snapshot != lastMetricsSnapshot) {
                    metricsReporter.onLoadSuccess(contentType, state.count)
                    lastMetricsSnapshot = snapshot
                }
            }
            is UiState.Empty -> {
                val snapshot = MetricsSnapshot.Empty
                if (snapshot != lastMetricsSnapshot) {
                    metricsReporter.onLoadEmpty(contentType)
                    lastMetricsSnapshot = snapshot
                }
            }
            is UiState.Error -> {
                val snapshot = MetricsSnapshot.Error(state.category)
                if (snapshot != lastMetricsSnapshot) {
                    metricsReporter.onLoadError(contentType, state.category)
                    lastMetricsSnapshot = snapshot
                }
            }
        }
    }

    private fun categorizeError(throwable: Throwable): ErrorCategory = when (throwable) {
        is IOException -> ErrorCategory.OFFLINE
        is HttpException -> if (throwable.code() >= 500) ErrorCategory.SERVER else ErrorCategory.UNKNOWN
        else -> ErrorCategory.UNKNOWN
    }

    private fun clearAllFilters() {
        metricsReporter.onClearFilters(contentType)
        viewModel.clearFilters()
    }

    private fun showSingleChoiceBottomSheet(
        titleRes: Int,
        entries: Array<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = DialogFilterListBinding.inflate(layoutInflater)
        sheetBinding.sheetTitle.setText(titleRes)
        val adapter = ArrayAdapter(requireContext(), R.layout.item_filter_option, entries)
        sheetBinding.optionList.adapter = adapter
        if (selectedIndex in entries.indices) {
            sheetBinding.optionList.setItemChecked(selectedIndex, true)
            sheetBinding.optionList.setSelection(selectedIndex)
        }
        sheetBinding.optionList.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onSelect(position)
        }
        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun applyChipStyle(chip: Chip?, isDefault: Boolean) {
        val chipContext = chip?.context ?: return
        val backgroundColor = if (isDefault) R.color.filter_chip_default_bg else R.color.filter_chip_selected_bg
        val textColor = if (isDefault) R.color.filter_chip_default_text else R.color.filter_chip_selected_text
        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(chipContext, backgroundColor))
        chip.setTextColor(ContextCompat.getColor(chipContext, textColor))
        if (isDefault) {
            chip.chipIcon = null
            chip.isChipIconVisible = false
        } else {
            chip.chipIcon = ContextCompat.getDrawable(chipContext, R.drawable.ic_chip_check)
            chip.isChipIconVisible = true
        }
    }

    protected open fun onContentClicked(item: ContentItem) {
        when (item) {
            is ContentItem.Video -> {
                findNavController().navigate(
                    R.id.action_global_playerFragment,
                    android.os.Bundle().apply {
                        putString("videoId", item.id)
                    }
                )
            }
            is ContentItem.Channel -> {
                findNavController().navigate(
                    R.id.action_global_channelDetailFragment,
                    android.os.Bundle().apply {
                        putString(com.albunyaan.tube.ui.detail.ChannelDetailFragment.ARG_CHANNEL_ID, item.id)
                        putString(com.albunyaan.tube.ui.detail.ChannelDetailFragment.ARG_CHANNEL_NAME, item.name)
                    }
                )
            }
            is ContentItem.Playlist -> {
                findNavController().navigate(
                    R.id.action_global_playlistDetailFragment,
                    android.os.Bundle().apply {
                        putString(com.albunyaan.tube.ui.detail.PlaylistDetailFragment.ARG_PLAYLIST_ID, item.id)
                        putString(com.albunyaan.tube.ui.detail.PlaylistDetailFragment.ARG_PLAYLIST_TITLE, item.title)
                    }
                )
            }
        }
    }

    private sealed interface UiState {
        object Loading : UiState
        data class Data(val count: Int) : UiState
        data class Empty(val filtersActive: Boolean) : UiState
        data class Error(val category: ErrorCategory) : UiState
    }

    private sealed interface MetricsSnapshot {
        data class Success(val count: Int) : MetricsSnapshot
        object Empty : MetricsSnapshot
        data class Error(val category: ErrorCategory) : MetricsSnapshot
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

    companion object {
        private const val PREFETCH_ITEM_COUNT = 6
        private const val PREFETCH_TRACK_LIMIT = 120
    }
}

private class ThumbnailPrefetcher(
    context: Context,
    private val imageLoader: ImageLoader,
    private val prefetchDistance: Int,
    private val trackLimit: Int
) : RecyclerView.OnScrollListener() {

    private val appContext = context.applicationContext
    private var adapter: ContentAdapter? = null
    private val prefetchedUrls = LinkedHashSet<String>()

    fun attach(recyclerView: RecyclerView, adapter: ContentAdapter) {
        this.adapter = adapter
        if (prefetchDistance <= 0) return
        recyclerView.addOnScrollListener(this)
        recyclerView.post { prefetchRange(0, prefetchDistance) }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return
        prefetchRange(lastVisible + 1, lastVisible + prefetchDistance)
    }

    private fun prefetchRange(start: Int, endInclusive: Int) {
        val adapter = adapter ?: return
        if (prefetchDistance <= 0) return
        if (start > endInclusive) return
        if (adapter.itemCount == 0) return
        val boundedStart = max(start, 0)
        for (index in boundedStart..endInclusive) {
            val item = adapter.peek(index) ?: continue
            val url = item.thumbnailUrl() ?: continue
            if (!prefetchedUrls.add(url)) continue
            pruneIfNeeded()
            val request = ImageRequest.Builder(appContext)
                .data(url)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun pruneIfNeeded() {
        if (prefetchedUrls.size <= trackLimit) return
        val iterator = prefetchedUrls.iterator()
        while (prefetchedUrls.size > trackLimit && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

private fun ContentItem.thumbnailUrl(): String? = when (this) {
    is ContentItem.Video -> thumbnailUrl
    is ContentItem.Channel -> thumbnailUrl
    is ContentItem.Playlist -> thumbnailUrl
}
