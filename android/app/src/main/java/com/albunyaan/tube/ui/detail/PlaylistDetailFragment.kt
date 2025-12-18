package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.playlist.PlaylistHeader
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.databinding.FragmentPlaylistDetailBinding
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.download.PlaylistDownloadItem
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.ui.detail.adapters.PlaylistVideosAdapter
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Playlist Detail screen showing playlist header info and paginated video list.
 * Uses NewPipeExtractor via PlaylistDetailRepository.
 */
@AndroidEntryPoint
class PlaylistDetailFragment : Fragment(R.layout.fragment_playlist_detail) {

    private var binding: FragmentPlaylistDetailBinding? = null

    @Inject
    lateinit var downloadRepository: DownloadRepository

    @Inject
    lateinit var prefetchService: StreamPrefetchService

    // Navigation arguments
    private val playlistId: String by lazy { arguments?.getString(ARG_PLAYLIST_ID).orEmpty() }
    private val playlistTitleArg: String? by lazy { arguments?.getString(ARG_PLAYLIST_TITLE) }
    private val playlistCategoryArg: String? by lazy { arguments?.getString(ARG_PLAYLIST_CATEGORY) }
    private val playlistCount: Int by lazy { arguments?.getInt(ARG_PLAYLIST_COUNT, 0) ?: 0 }
    private val downloadPolicy: DownloadPolicy by lazy {
        val policyStr = arguments?.getString(ARG_DOWNLOAD_POLICY) ?: DownloadPolicy.ENABLED.name
        try {
            DownloadPolicy.valueOf(policyStr)
        } catch (e: Exception) {
            DownloadPolicy.ENABLED
        }
    }
    private val isExcluded: Boolean by lazy { arguments?.getBoolean(ARG_EXCLUDED, false) ?: false }

    private val viewModel: PlaylistDetailViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<PlaylistDetailViewModel.Factory> { factory ->
                factory.create(
                    playlistId = playlistId,
                    initialTitle = playlistTitleArg,
                    initialCategory = playlistCategoryArg,
                    initialCount = playlistCount,
                    downloadPolicy = downloadPolicy,
                    excluded = isExcluded
                )
            }
        }
    )

    private lateinit var videosAdapter: PlaylistVideosAdapter

    // Track download states for items
    private var downloadStates: Map<String, Pair<DownloadStatus, Int>> = emptyMap()
    private var appBarOffsetListener: AppBarLayout.OnOffsetChangedListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPlaylistDetailBinding.bind(view)

        setupToolbar()
        setupRecyclerView()
        setupActionButtons()
        observeViewModel()
        observeDownloads()
    }

    private fun setupToolbar() {
        binding?.apply {
            toolbar.navigationIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }
            // Only show title on tablets; phones show title in the header content area
            val isTablet = resources.getBoolean(R.bool.is_tablet)
            toolbar.title = if (isTablet) (playlistTitleArg ?: getString(R.string.app_name)) else ""

            val listener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
                val collapsed = appBarLayout.totalScrollRange + verticalOffset <= 0
                val expandedColor = ContextCompat.getColor(requireContext(), android.R.color.white)
                val collapsedColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
                toolbar.navigationIcon?.mutate()?.setTint(if (collapsed) collapsedColor else expandedColor)
                if (isTablet) {
                    toolbar.setTitleTextColor(if (collapsed) collapsedColor else expandedColor)
                }
            }
            appBarLayout.addOnOffsetChangedListener(listener)
            appBarOffsetListener = listener
        }
    }

    private fun setupRecyclerView() {
        videosAdapter = PlaylistVideosAdapter { item, position ->
            Log.d(TAG, "Video clicked: ${item.title} at position $position")
            // Trigger prefetch before navigation (hides 2-5s extraction latency)
            // Use lifecycleScope (not viewLifecycleOwner) so prefetch survives navigation
            prefetchService.triggerPrefetch(item.videoId, lifecycleScope)
            navigateToPlayer(item.videoId, position)
        }

        binding?.videosRecyclerView?.apply {
            adapter = videosAdapter
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)

            // Pagination scroll listener
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    val totalCount = layoutManager.itemCount
                    viewModel.onListScrolled(lastVisible, totalCount)
                }
            })
        }
    }

    private fun setupActionButtons() {
        binding?.apply {
            playAllButton.setOnClickListener {
                // Prefetch first video for smoother start
                prefetchFirstPlaylistItem()
                viewModel.onPlayAllClicked()
            }

            shuffleButton.setOnClickListener {
                // Note: Can't prefetch shuffle since we don't know the order until player starts
                viewModel.onShuffleClicked()
            }

            downloadPlaylistButton.setOnClickListener {
                viewModel.onDownloadPlaylistClicked()
            }

            // Configure based on download policy
            configureDownloadButton(downloadPolicy, isExcluded)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Header state
                launch {
                    viewModel.headerState.collect { state ->
                        handleHeaderState(state)
                    }
                }

                // Items (videos) state
                launch {
                    viewModel.itemsState.collect { state ->
                        handleItemsState(state)
                    }
                }

                // Download UI state
                launch {
                    viewModel.downloadUiState.collect { state ->
                        handleDownloadUiState(state)
                    }
                }

                // One-shot UI events (use collect instead of collectLatest to process all events)
                launch {
                    viewModel.uiEvents.collect { event ->
                        handleUiEvent(event)
                    }
                }
            }
        }
    }

    private fun handleHeaderState(state: PlaylistDetailViewModel.HeaderState) {
        binding?.apply {
            when (state) {
                is PlaylistDetailViewModel.HeaderState.Loading -> {
                    Log.d(TAG, "Loading header...")
                    headerSkeleton.isVisible = true
                    headerContent.isVisible = false
                }
                is PlaylistDetailViewModel.HeaderState.Success -> {
                    Log.d(TAG, "Header loaded: ${state.header.title}")
                    headerSkeleton.isVisible = false
                    headerContent.isVisible = true
                    bindHeader(state.header)
                }
                is PlaylistDetailViewModel.HeaderState.Error -> {
                    Log.e(TAG, "Header error: ${state.message}")
                    headerSkeleton.isVisible = false
                    headerContent.isVisible = false
                    // Show error in content area
                    showErrorState(state.message)
                }
            }
        }
    }

    private fun handleItemsState(state: PlaylistDetailViewModel.PaginatedState<PlaylistItem>) {
        binding?.apply {
            when (state) {
                is PlaylistDetailViewModel.PaginatedState.Idle -> {
                    // Initial state, nothing to show
                }
                is PlaylistDetailViewModel.PaginatedState.LoadingInitial -> {
                    Log.d(TAG, "Loading initial items...")
                    listSkeletonContainer.isVisible = true
                    videosRecyclerView.isVisible = false
                    emptyState.root.isVisible = false
                    errorState.root.isVisible = false
                }
                is PlaylistDetailViewModel.PaginatedState.Loaded -> {
                    Log.d(TAG, "Items loaded: ${state.items.size}")
                    listSkeletonContainer.isVisible = false
                    videosRecyclerView.isVisible = true
                    emptyState.root.isVisible = false
                    errorState.root.isVisible = false

                    updateVideosList(state.items)
                }
                is PlaylistDetailViewModel.PaginatedState.Empty -> {
                    Log.d(TAG, "Playlist is empty")
                    listSkeletonContainer.isVisible = false
                    videosRecyclerView.isVisible = false
                    emptyState.root.isVisible = true
                    errorState.root.isVisible = false
                }
                is PlaylistDetailViewModel.PaginatedState.ErrorInitial -> {
                    Log.e(TAG, "Initial load error: ${state.message}")
                    listSkeletonContainer.isVisible = false
                    videosRecyclerView.isVisible = false
                    emptyState.root.isVisible = false
                    showErrorState(state.message)
                }
                is PlaylistDetailViewModel.PaginatedState.ErrorAppend -> {
                    Log.e(TAG, "Append error: ${state.message}")
                    // Keep showing existing items
                    updateVideosList(state.items)
                    context?.let { Toast.makeText(it, state.message, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun handleDownloadUiState(state: PlaylistDetailViewModel.PlaylistDownloadUiState) {
        binding?.apply {
            if (state.isDownloading) {
                downloadPlaylistButton.text = getString(R.string.playlist_detail_downloading)
                downloadPlaylistButton.isEnabled = false
            } else if (state.downloadedCount > 0 && state.downloadedCount == state.totalCount) {
                downloadPlaylistButton.text = getString(R.string.download_status_completed)
                downloadPlaylistButton.isEnabled = false
            } else if (state.downloadedCount > 0) {
                // Partial download
                downloadPlaylistButton.text = "${state.downloadedCount}/${state.totalCount}"
                downloadPlaylistButton.isEnabled = true
            } else {
                // Default state
                configureDownloadButton(downloadPolicy, isExcluded)
            }

            state.errorMessage?.let { msg ->
                context?.let { ctx -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handleUiEvent(event: PlaylistDetailViewModel.PlaylistUiEvent) {
        when (event) {
            is PlaylistDetailViewModel.PlaylistUiEvent.NavigateToPlayer -> {
                navigateToPlayer(startIndex = event.startIndex, shuffled = event.shuffled)
            }
            is PlaylistDetailViewModel.PlaylistUiEvent.ShowDownloadQualitySheet -> {
                showDownloadQualitySheet(event)
            }
            is PlaylistDetailViewModel.PlaylistUiEvent.StartPlaylistDownload -> {
                executePlaylistDownload(event)
            }
            is PlaylistDetailViewModel.PlaylistUiEvent.ShowError -> {
                Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindHeader(header: PlaylistHeader) {
        binding?.apply {
            // Update toolbar title only on tablets; phones show title in content area
            val isTablet = resources.getBoolean(R.bool.is_tablet)
            if (isTablet) {
                toolbar.title = header.title
            }

            // Title
            playlistTitle.text = header.title

            // Hero component (YouTube-style: blurred background + centered thumbnail)
            val thumbnailUrl = header.thumbnailUrl ?: header.bannerUrl
            if (!thumbnailUrl.isNullOrEmpty()) {
                // Load blurred background (scaled down for softening + RenderEffect on API 31+)
                heroBackgroundBlurred.load(thumbnailUrl) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    crossfade(true)
                    size(320, 180) // Scaled down for natural softening
                    listener(onSuccess = { _, _ ->
                        // Apply RenderEffect blur on API 31+
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            heroBackgroundBlurred.setRenderEffect(
                                android.graphics.RenderEffect.createBlurEffect(30f, 30f, android.graphics.Shader.TileMode.CLAMP)
                            )
                        }
                    })
                }

                // Load sharp foreground thumbnail
                (heroThumbnail as? android.widget.ImageView)?.load(thumbnailUrl) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    crossfade(true)
                }
            }

            // Channel name (clickable)
            if (!header.channelName.isNullOrEmpty()) {
                channelName.text = header.channelName
                channelName.isVisible = true
                channelName.setOnClickListener {
                    header.channelId?.let { channelId ->
                        navigateToChannel(channelId, header.channelName)
                    }
                }
            } else {
                channelName.isVisible = false
            }

            // Metadata (video count + duration)
            val count = header.itemCount ?: 0
            val durationText = header.totalDurationSeconds?.let { formatTotalDuration(it) }
            playlistMetadata.text = if (durationText != null) {
                getString(R.string.playlist_metadata_duration_format, count.toInt(), durationText)
            } else {
                getString(R.string.playlist_metadata_format, count.toInt())
            }

            // Category chip
            if (!header.category.isNullOrEmpty()) {
                categoryChipsContainer.isVisible = true
                categoryChipsContainer.removeAllViews()
                val chip = Chip(requireContext()).apply {
                    text = header.category
                    isClickable = false
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                        requireContext().getColor(R.color.surface_variant)
                    )
                    setTextColor(requireContext().getColor(R.color.primary_green))
                }
                categoryChipsContainer.addView(chip)
            } else {
                categoryChipsContainer.isVisible = false
            }

            // Exclusion banner
            exclusionBanner.isVisible = header.excluded
        }
    }

    private fun showErrorState(message: String) {
        binding?.apply {
            errorState.root.isVisible = true
            errorState.errorBody.text = message
            errorState.retryButton.setOnClickListener {
                // Reload both header and items on retry
                viewModel.loadHeader(forceRefresh = true)
                viewModel.retryInitial()
            }
        }
    }

    private fun updateVideosList(items: List<PlaylistItem>) {
        videosAdapter.submitItems(items, downloadStates)
    }

    private fun observeDownloads() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadRepository.downloads.collect { downloads ->
                    // Build map of videoId -> (status, progress) for items in this playlist
                    downloadStates = downloads
                        .filter { entry -> entry.request.id.startsWith("$playlistId|") }
                        .associate { entry ->
                            // Extract videoId from request ID: "playlistId|qualityLabel|videoId"
                            val videoId = entry.request.id.substringAfterLast("|")
                            videoId to (entry.status to entry.progress)
                        }

                    // Refresh the list with updated download states
                    val currentState = viewModel.itemsState.value
                    if (currentState is PlaylistDetailViewModel.PaginatedState.Loaded) {
                        updateVideosList(currentState.items)
                    }
                }
            }
        }
    }

    private fun configureDownloadButton(policy: DownloadPolicy, excluded: Boolean) {
        binding?.downloadPlaylistButton?.apply {
            when (policy) {
                DownloadPolicy.ENABLED -> {
                    text = getString(R.string.playlist_detail_download)
                    isEnabled = !excluded
                }
                DownloadPolicy.QUEUED -> {
                    text = getString(R.string.playlist_detail_downloading)
                    isEnabled = false
                }
                DownloadPolicy.DISABLED -> {
                    text = getString(R.string.playlist_detail_download_disabled)
                    isEnabled = false
                }
            }
        }
    }

    private fun showDownloadQualitySheet(event: PlaylistDetailViewModel.PlaylistUiEvent.ShowDownloadQualitySheet) {
        // Simple quality selection dialog
        val qualities = PlaylistQualityOption.entries.toTypedArray()
        val qualityNames = qualities.map { it.label }.toTypedArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.player_action_quality)
            .setItems(qualityNames) { _, which ->
                viewModel.startPlaylistDownload(qualities[which])
            }
            .show()
    }

    private fun executePlaylistDownload(event: PlaylistDetailViewModel.PlaylistUiEvent.StartPlaylistDownload) {
        val downloadItems = event.items.map { item ->
            PlaylistDownloadItem(
                videoId = item.videoId,
                title = item.title,
                indexInPlaylist = item.position
            )
        }

        val enqueuedCount = downloadRepository.enqueuePlaylist(
            playlistId = event.playlistId,
            playlistTitle = event.playlistTitle,
            qualityLabel = event.quality.label,
            items = downloadItems,
            audioOnly = event.quality.audioOnly,
            targetHeight = event.quality.targetHeight
        )

        Toast.makeText(
            context,
            getString(R.string.playlist_download_started, enqueuedCount, event.playlistTitle),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Prefetch the first video in the playlist for smoother "Play All" start.
     */
    private fun prefetchFirstPlaylistItem() {
        val state = viewModel.itemsState.value
        if (state is PlaylistDetailViewModel.PaginatedState.Loaded && state.items.isNotEmpty()) {
            val firstItem = state.items.first()
            // Use lifecycleScope (not viewLifecycleOwner) so prefetch survives navigation
            prefetchService.triggerPrefetch(firstItem.videoId, lifecycleScope)
        }
    }

    private fun navigateToPlayer(targetVideoId: String? = null, startIndex: Int = 0, shuffled: Boolean = false) {
        val bundle = Bundle().apply {
            // PR6.6: Pass targetVideoId as authoritative identifier, startIndex as optimization hint
            targetVideoId?.let { putString("targetVideoId", it) }
            putString("playlistId", playlistId)
            putInt("startIndex", startIndex)
            putBoolean("shuffled", shuffled)
        }
        findNavController().navigate(R.id.action_global_playerFragment, bundle)
    }

    private fun navigateToChannel(channelId: String, channelName: String?) {
        val bundle = Bundle().apply {
            putString("channelId", channelId)
            putString("channelName", channelName)
        }
        findNavController().navigate(R.id.action_global_channelDetailFragment, bundle)
    }

    private fun formatTotalDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }

    override fun onDestroyView() {
        binding?.appBarLayout?.removeOnOffsetChangedListener(appBarOffsetListener)
        appBarOffsetListener = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PlaylistDetailFragment"
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_PLAYLIST_TITLE = "playlistTitle"
        const val ARG_PLAYLIST_CATEGORY = "playlistCategory"
        const val ARG_PLAYLIST_COUNT = "playlistCount"
        const val ARG_DOWNLOAD_POLICY = "downloadPolicy"
        const val ARG_EXCLUDED = "excluded"
    }
}
