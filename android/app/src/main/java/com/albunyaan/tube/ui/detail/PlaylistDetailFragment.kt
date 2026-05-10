package com.albunyaan.tube.ui.detail

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
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
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.albunyaan.tube.player.PredictivePrefetchController
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.data.report.ReportTargetType
import com.albunyaan.tube.share.ShareLinks
import com.albunyaan.tube.share.ShareMetadataPublisher
import com.albunyaan.tube.ui.report.ContentReportBottomSheet
import com.albunyaan.tube.ui.detail.adapters.PlaylistVideosAdapter
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.albunyaan.tube.util.showIcons

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

    @Inject
    lateinit var subscriptions: com.albunyaan.tube.data.subscriptions.SubscriptionRepository

    @Inject
    lateinit var featureFlags: PlaybackFeatureFlags

    private var latestPlaylistHeader: com.albunyaan.tube.data.playlist.PlaylistHeader? = null
    private var isPlaylistSavedNow: Boolean = false
    private var prefetchController: PredictivePrefetchController? = null

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
    private var currentHeader: PlaylistHeader? = null
    private val searchHandler = Handler(Looper.getMainLooper())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPlaylistDetailBinding.bind(view)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        if (featureFlags.isPredictivePrefetchEnabled) {
            prefetchController = PredictivePrefetchController(
                prefetchService,
                viewLifecycleOwner.lifecycleScope,
                videoIdResolver = { pos -> videosAdapter.currentList.getOrNull(pos)?.item?.videoId }
            )
            binding?.videosRecyclerView?.let { prefetchController?.attach(it) }
        }
        setupActionButtons()
        observeViewModel()
        observeDownloads()
        observeSavedState()
    }

    private fun observeSavedState() {
        subscriptions.isPlaylistSaved(playlistId)
            .distinctUntilChanged()
            .onEach { saved ->
                isPlaylistSavedNow = saved
                binding?.apply {
                    savePlaylistLabel.setText(if (saved) R.string.playlist_unsave else R.string.playlist_save)
                    savePlaylistIcon.setImageResource(
                        if (saved) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                    )
                    savePlaylistButton.contentDescription = getString(
                        if (saved) R.string.playlist_unsave else R.string.cd_save_playlist_button
                    )
                    savePlaylistButton.isSelected = saved
                    savePlaylistButton.setOnClickListener { togglePlaylistSaved() }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun togglePlaylistSaved() {
        val header = latestPlaylistHeader ?: return
        // F11: reject malformed ids that would break the later NewPipe lookup.
        if (!PLAYLIST_ID_REGEX.matches(header.id)) {
            android.util.Log.w("PlaylistDetailFragment", "Refusing save: malformed playlistId='${header.id}'")
            return
        }
        // F-CR7 (CodeRabbit): capture the toggle direction at click time so
        // a fast double-tap can't read a stale isPlaylistSavedNow, disable
        // the button while the IO is in flight, and catch failures so the
        // UI doesn't sit in a half-flipped state on a transient Room error.
        val shouldUnsave = isPlaylistSavedNow
        binding?.savePlaylistButton?.setActionEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (shouldUnsave) {
                        subscriptions.unsavePlaylist(header.id)
                    } else {
                        subscriptions.savePlaylist(
                            com.albunyaan.tube.data.local.SavedPlaylist(
                                playlistId = header.id,
                                playlistUrl = "https://www.youtube.com/playlist?list=${header.id}",
                                name = header.title,
                                thumbnailUrl = header.thumbnailUrl,
                                uploaderName = header.channelName,
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("PlaylistDetailFragment", "Failed to toggle save for ${header.id}", t)
            } finally {
                binding?.savePlaylistButton?.isEnabled = true
            }
        }
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
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    viewModel.setSearchQuery(query)
                }, SEARCH_DEBOUNCE_MS)
            }
        })

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchHandler.removeCallbacksAndMessages(null)
                viewModel.setSearchQuery(editText.text?.toString() ?: "")
                true
            } else false
        }

        clearBtn.setOnClickListener {
            searchHandler.removeCallbacksAndMessages(null)
            editText.text?.clear()
            viewModel.setSearchQuery("")
        }
    }

    private fun setupToolbar() {
        binding?.apply {
            toolbar.navigationIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            toolbar.inflateMenu(R.menu.menu_detail_kebab)
            toolbar.menu.showIcons()
            toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_share -> {
                        sharePlaylist()
                        true
                    }
                    R.id.action_report -> {
                        openReportSheet()
                        true
                    }
                    else -> false
                }
            }
            toolbar.title = playlistTitleArg ?: getString(R.string.app_name)

            val listener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
                val collapsed = appBarLayout.totalScrollRange + verticalOffset <= 0
                val expandedColor = ContextCompat.getColor(requireContext(), android.R.color.white)
                val collapsedColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
                val toolbarColor = if (collapsed) collapsedColor else expandedColor
                toolbar.navigationIcon?.mutate()?.setTint(toolbarColor)
                toolbar.setTitleTextColor(toolbarColor)
                tintToolbarActions(toolbarColor)
            }
            appBarLayout.addOnOffsetChangedListener(listener)
            appBarOffsetListener = listener
            tintToolbarActions(ContextCompat.getColor(requireContext(), android.R.color.white))
        }
    }

    private fun setupRecyclerView() {
        videosAdapter = PlaylistVideosAdapter { item, position ->
            Log.d(TAG, "Video clicked: ${item.title} at position $position")
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

                // Items (videos) state, filtered by search query
                launch {
                    combine(viewModel.itemsState, viewModel.searchQuery) { state, query ->
                        val lowerQuery = query.trim().lowercase(java.util.Locale.ROOT)
                        if (lowerQuery.isEmpty()) state
                        else when (state) {
                            is PlaylistDetailViewModel.PaginatedState.Loaded -> {
                                val filtered = state.items.filter {
                                    it.title.lowercase(java.util.Locale.ROOT).contains(lowerQuery) ||
                                    it.channelName?.lowercase(java.util.Locale.ROOT)?.contains(lowerQuery) == true
                                }
                                if (filtered.isEmpty()) PlaylistDetailViewModel.PaginatedState.Empty
                                else state.copy(items = filtered, nextPage = null)
                            }
                            is PlaylistDetailViewModel.PaginatedState.ErrorAppend -> {
                                val filtered = state.items.filter {
                                    it.title.lowercase(java.util.Locale.ROOT).contains(lowerQuery) ||
                                    it.channelName?.lowercase(java.util.Locale.ROOT)?.contains(lowerQuery) == true
                                }
                                state.copy(items = filtered)
                            }
                            else -> state
                        }
                    }.collect { state ->
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
                is PlaylistDetailViewModel.HeaderState.ContentUnavailable -> {
                    Log.i(TAG, "Playlist is unavailable per backend")
                    headerSkeleton.isVisible = false
                    headerContent.isVisible = false
                    errorState.root.isVisible = true
                    errorState.errorHeadline.text =
                        getString(R.string.content_unavailable_title)
                    errorState.errorBody.text =
                        getString(R.string.content_unavailable_message)
                    errorState.retryButton.isVisible = false
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
                downloadPlaylistLabel.text = getString(R.string.playlist_detail_downloading)
                downloadPlaylistButton.setActionEnabled(false)
            } else if (state.downloadedCount > 0 && state.downloadedCount == state.totalCount) {
                downloadPlaylistLabel.text = getString(R.string.download_status_completed)
                downloadPlaylistButton.setActionEnabled(false)
            } else if (state.downloadedCount > 0) {
                // Partial download
                downloadPlaylistLabel.text = "${state.downloadedCount}/${state.totalCount}"
                downloadPlaylistButton.setActionEnabled(true)
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
        currentHeader = header
        latestPlaylistHeader = header
        binding?.apply {
            // Keep the collapsed toolbar populated on all devices.
            toolbar.title = header.title

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

    private fun sharePlaylist() {
        if (playlistId.isBlank()) return

        val header = currentHeader
        val title = header?.title?.takeIf { it.isNotBlank() }
            ?: playlistTitleArg?.takeIf { it.isNotBlank() }
            ?: playlistId
        val imageUrl = header?.thumbnailUrl ?: header?.bannerUrl
        val description = header?.description
        val shareUrl = ShareLinks.playlist(
            playlistId = playlistId,
            title = title,
            imageUrl = imageUrl,
            description = description
        )
        viewLifecycleOwner.lifecycleScope.launch {
            ShareMetadataPublisher.publish("playlist", playlistId, title, imageUrl, description)
            if (!isAdded) return@launch

            val shareMessage = buildString {
                append(title)
                append("\n\n")
                append(getString(R.string.share_playlist_in_app))
                append("\n")
                append(shareUrl)
                append("\n\n")
                append(getString(R.string.share_app_promo))
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TITLE, title)
                putExtra(Intent.EXTRA_TEXT, shareMessage)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_playlist_chooser)))
        }
    }

    private fun openReportSheet() {
        if (playlistId.isBlank()) return
        ContentReportBottomSheet.newInstance(ReportTargetType.PLAYLIST, playlistId)
            .show(childFragmentManager, ContentReportBottomSheet.TAG)
    }

    private fun tintToolbarActions(color: Int) {
        binding?.toolbar?.overflowIcon?.mutate()?.setTint(color)
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
        val b = binding ?: return
        when (policy) {
            DownloadPolicy.ENABLED -> {
                b.downloadPlaylistLabel.text = getString(R.string.playlist_detail_download)
                b.downloadPlaylistButton.setActionEnabled(!excluded)
            }
            DownloadPolicy.QUEUED -> {
                b.downloadPlaylistLabel.text = getString(R.string.playlist_detail_downloading)
                b.downloadPlaylistButton.setActionEnabled(false)
            }
            DownloadPolicy.DISABLED -> {
                b.downloadPlaylistLabel.text = getString(R.string.playlist_detail_download_disabled)
                b.downloadPlaylistButton.setActionEnabled(false)
            }
        }
    }

    private fun View.setActionEnabled(enabled: Boolean) {
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        alpha = if (enabled) 1f else 0.4f
    }

    private fun showDownloadQualitySheet(
        @Suppress("UNUSED_PARAMETER") event: PlaylistDetailViewModel.PlaylistUiEvent.ShowDownloadQualitySheet
    ) {
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
                indexInPlaylist = item.position,
                thumbnailUrl = item.thumbnailUrl
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
        searchHandler.removeCallbacksAndMessages(null)
        prefetchController?.detach()
        prefetchController = null
        binding?.appBarLayout?.removeOnOffsetChangedListener(appBarOffsetListener)
        appBarOffsetListener = null
        currentHeader = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PlaylistDetailFragment"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private val PLAYLIST_ID_REGEX = Regex("^[A-Za-z0-9_-]{3,128}$")
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_PLAYLIST_TITLE = "playlistTitle"
        const val ARG_PLAYLIST_CATEGORY = "playlistCategory"
        const val ARG_PLAYLIST_COUNT = "playlistCount"
        const val ARG_DOWNLOAD_POLICY = "downloadPolicy"
        const val ARG_EXCLUDED = "excluded"
    }
}
