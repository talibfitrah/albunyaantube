package com.albunyaan.tube.ui.shorts

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.media3.ui.TimeBar
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.albunyaan.tube.R
import com.albunyaan.tube.data.extractor.AudioLanguageOption
import com.albunyaan.tube.data.extractor.availableAudioLanguages
import com.albunyaan.tube.data.report.ReportTargetType
import com.albunyaan.tube.databinding.FragmentShortsPlayerBinding
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import androidx.media3.datasource.cache.SimpleCache
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.share.ShareLinks
import com.albunyaan.tube.share.ShareMetadataPublisher
import com.albunyaan.tube.ui.player.DownloadQualityDialog
import com.albunyaan.tube.ui.report.ContentReportBottomSheet
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.albunyaan.tube.util.showIcons

/**
 * Hosts the vertical, swipeable custom shorts player.
 *
 * The ExoPlayer instance is owned by [ShortsPlayerViewModel] so it survives
 * configuration changes; the fragment just attaches/detaches the player from
 * PlayerViews via a local [PlayerBinder]. The ViewModel is built through an
 * Assisted factory with the nav args `initialShortId` and `channelId`.
 *
 * Edge-to-edge: hides system bars on resume and restores them in
 * [onDestroyView] / [onStop] (not onPause) to avoid flicker on transient
 * pauses (permission dialogs, bottom sheets). See CLAUDE.md
 * (Edge-to-Edge, Android 15+) for the project contract.
 */
@AndroidEntryPoint
class ShortsPlayerFragment : Fragment(R.layout.fragment_shorts_player) {

    @Inject lateinit var vmFactory: ShortsPlayerViewModel.Factory

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var hlsPoisonRegistry: com.albunyaan.tube.player.HlsPoisonRegistry
    @Inject lateinit var multiRepFactory: com.albunyaan.tube.player.MultiRepSyntheticDashMediaSourceFactory
    @Inject lateinit var coldStartQualityChooser: com.albunyaan.tube.player.ColdStartQualityChooser
    @Inject lateinit var playbackFeatureFlags: com.albunyaan.tube.player.PlaybackFeatureFlags
    @Inject lateinit var mpdRegistry: com.albunyaan.tube.player.SyntheticDashMpdRegistry
    @Inject lateinit var probationChecker: com.albunyaan.tube.player.HlsProbationChecker
    @Inject lateinit var cronetDataSourceFactory: com.albunyaan.tube.player.CronetDataSourceFactory
    @Inject lateinit var simpleCache: SimpleCache

    private val viewModel: ShortsPlayerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val initialShortId = arguments?.getString(ARG_INITIAL_SHORT_ID)
                val channelId = arguments?.getString(ARG_CHANNEL_ID)
                return vmFactory.create(
                    initialShortId = initialShortId,
                    channelId = channelId,
                    initialShortTitle = arguments?.getString(ARG_INITIAL_SHORT_TITLE),
                    initialChannelName = arguments?.getString(ARG_INITIAL_CHANNEL_NAME),
                    initialThumbnailUrl = arguments?.getString(ARG_INITIAL_THUMBNAIL_URL),
                    initialChannelAvatarUrl = arguments?.getString(ARG_INITIAL_CHANNEL_AVATAR_URL),
                    initialDurationSeconds = arguments?.getInt(ARG_INITIAL_DURATION_SECONDS, 0) ?: 0,
                ) as T
            }
        }
    }

    private var binding: FragmentShortsPlayerBinding? = null
    private var binder: PlayerBinder? = null
    private var adapter: ShortsPagerAdapter? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var hasBoundInitialPage = false
    private var didEnterImmersive = false
    /** True if playback was running when the fragment was last stopped — drives auto-resume on onStart. */
    private var wasPlayingBeforeStop = false
    /** Saved activity orientation so we can restore it when leaving the shorts player. */
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    /** Position for which the download picker is waiting on a dialog result. */
    private var pendingDownloadPosition: Int = -1
    /** Video id for which the audio-language picker is awaiting a dialog result. */
    private var pendingAudioLanguageVideoId: String? = null
    /**
     * Per-video StateFlow of the audio-language option list. The adapter
     * observes the `.size` of each video's list to drive the rail button's
     * visibility; the fragment reads the full list to populate the dialog.
     *
     * Populated reactively from [PlayerBinder.resolvedEvents] — never blocks
     * the UI thread: the groupBy+map happens on Main.immediate inside a
     * StateFlow write, which is cheap for the tiny audio-track lists YouTube
     * exposes (typically 1–5 entries).
     */
    private val audioLanguagesByVideoId =
        mutableMapOf<String, MutableStateFlow<List<AudioLanguageOption>>>()
    /** Last-selected language code per video id, drives the dialog's "checked" state. */
    private val activeLanguageByVideoId = mutableMapOf<String, String>()

    private fun audioLanguageFlowFor(videoId: String): MutableStateFlow<List<AudioLanguageOption>> =
        audioLanguagesByVideoId.getOrPut(videoId) { MutableStateFlow(emptyList()) }

    private val subtitlesByVideoId =
        mutableMapOf<String, MutableStateFlow<List<com.albunyaan.tube.data.extractor.SubtitleTrack>>>()
    private fun subtitleFlowFor(videoId: String) =
        subtitlesByVideoId.getOrPut(videoId) { MutableStateFlow(emptyList()) }

    /** Time bar driver: periodically mirrors player.currentPosition into the active page's DefaultTimeBar. */
    private val timeBarHandler = Handler(Looper.getMainLooper())
    private val stallRecoveryRunnable = Runnable {
        // Only trigger if the player is still buffering (state may have changed
        // between scheduling and firing).
        if (viewModel.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
            // Invalidate any cached MPD for the current video before forcing a
            // fresh re-resolve, so the new resolution doesn't hit a stale entry.
            val currentPos = binding?.shortsPager?.currentItem
            if (currentPos != null) {
                val currentVideoId = viewModel.items.value.getOrNull(currentPos)?.id
                if (currentVideoId != null) {
                    mpdRegistry.unregisterBoth(currentVideoId)
                }
            }
            binder?.forceRefreshCurrent()
        }
    }
    private var timeBarTicker: Runnable? = null
    /** True while the user is actively dragging the scrubber; suppresses the ticker overwrite. */
    private var isScrubbing = false

    /**
     * Stall-watchdog listener. Held as a field so [onDestroyView] can call
     * [androidx.media3.common.Player.removeListener] — without that, every
     * Fragment recreation (config change, back-and-forward nav) leaves the
     * prior listener attached to the VM-owned player, pinning the dead
     * Fragment + its view tree + a duplicate handler that fires on every
     * BUFFERING transition. Many hours of shorts use leak many copies.
     */
    private val stallListener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                timeBarHandler.removeCallbacks(stallRecoveryRunnable)
                timeBarHandler.postDelayed(stallRecoveryRunnable, STALL_RECOVERY_MS)
            } else {
                timeBarHandler.removeCallbacks(stallRecoveryRunnable)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bnd = FragmentShortsPlayerBinding.bind(view)
        binding = bnd

        // Build the same adaptive MediaSource factory the main PlayerFragment
        // uses — shorts get DASH/HLS with ABR + auto highest-quality selection
        // instead of a single progressive stream.
        val mediaSourceFactory = com.albunyaan.tube.player.MultiQualityMediaSourceFactory(
            requireContext(),
            hlsPoisonRegistry,
            multiRepFactory,
            coldStartQualityChooser,
            playbackFeatureFlags,
            mpdRegistry,
            probationChecker,
            cronetDataSourceFactory,
            simpleCache = simpleCache
        )
        val localBinder = PlayerBinder(viewModel.player, playerRepository, mediaSourceFactory, mpdRegistry, playbackFeatureFlags)

        // Stall watchdog: if BUFFERING persists past STALL_RECOVERY_MS, force
        // a fresh stream resolve. Common cause is expired progressive URLs
        // returning 403 mid-segment. Mirrors the recovery intent of the main
        // player's PlaybackRecoveryManager without hauling in its full machinery.
        // Listener is held as the [stallListener] field so onDestroyView can
        // remove it — see field-level KDoc for the leak this prevents.
        viewModel.player.addListener(stallListener)
        binder = localBinder

        val pagerAdapter = ShortsPagerAdapter(
            lifecycleOwner = viewLifecycleOwner,
            callbacks = ShortsPagerAdapter.Callbacks(
                onLike = { idx -> viewModel.toggleLike(idx) },
                onShare = { idx -> shareShort(idx) },
                onDownload = { idx -> downloadShort(idx) },
                onChannelTap = { idx -> openChannel(idx) },
                onTapVideo = { idx ->
                    localBinder.togglePlayPause()
                    // Flash the centered indicator on the currently-bound
                    // page. The adapter position is the most reliable source
                    // since the tap originated on that holder's tap target.
                    val holder = findViewHolderAt(idx)
                    holder?.flashPlayPauseIndicator(isPlaying = localBinder.isPlaying())
                },
                onAudioTrackTap = { idx -> openAudioLanguagePicker(idx) },
                onSubtitleTap = { idx -> openSubtitlePicker(idx) },
                onLikedFlow = { id -> viewModel.isLikedFlow(id) },
                onAudioLanguageCountFlow = { id ->
                    audioLanguageFlowFor(id).map { it.size }
                },
                onSubtitleCountFlow = { id ->
                    subtitleFlowFor(id).map { it.size }
                },
            )
        )
        adapter = pagerAdapter
        bnd.shortsPager.adapter = pagerAdapter
        bnd.shortsPager.offscreenPageLimit = 1
        // Disable swipe-to-next: product decision to NOT contribute to
        // doom-scrolling. Users tap a Short to watch, press back to return
        // to the source list, then tap another. Removing the vertical
        // swipe gesture makes this intent explicit. The pager is kept
        // (instead of being replaced with a single fragment) so that
        // future programmatic transitions — e.g. play-next-from-channel —
        // can still use setCurrentItem if we ever opt back in.
        bnd.shortsPager.isUserInputEnabled = false

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val item = viewModel.items.value.getOrNull(position) ?: return
                viewModel.onPageChanged(position)
                bindPageWhenReady(position, item.id, item.channelId)
            }
        }
        pageChangeCallback = callback
        bnd.shortsPager.registerOnPageChangeCallback(callback)

        bnd.shortsBackBtn.setOnClickListener {
            findNavController().popBackStack()
        }

        bnd.shortsMenuBtn.setOnClickListener { anchor ->
            showShortsKebabMenu(anchor)
        }

        // Quality picker result: apply the selected cap (or clear if AUTO/0).
        // Two steps:
        //   1) viewModel.applyQualityCap(height) — sets the trackSelector
        //      cap. Effective for multi-rep DASH (track selector picks a
        //      track ≤ cap), but a no-op for single-rep synthetic DASH
        //      (the manifest only has one video track).
        //   2) binder.switchQuality(videoId, height) — rebuilds the
        //      MediaSource so the synthetic-DASH builder regenerates the
        //      manifest with a track that matches the new cap. Most
        //      shorts hit this path because their progressive video
        //      tracks have inconsistent containers, which makes
        //      SYNTH_ADAPTIVE ineligible and forces single-rep.
        childFragmentManager.setFragmentResultListener(
            com.albunyaan.tube.ui.shared.QualityPickerDialog.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            val height = result.getInt(
                com.albunyaan.tube.ui.shared.QualityPickerDialog.RESULT_SELECTED_HEIGHT,
                com.albunyaan.tube.ui.shared.QualityPickerDialog.AUTO,
            )
            viewModel.applyQualityCap(height)
            val pos = binding?.shortsPager?.currentItem
            val videoId = pos?.let { viewModel.items.value.getOrNull(it)?.id }
            if (videoId != null) {
                // Drop any cached synthetic MPD for this video so the
                // factory regenerates with the new quality. Safe no-op
                // for raw progressive (registry lookup misses).
                mpdRegistry.unregisterBoth(videoId)
                binder?.switchQuality(videoId, height)
            }
        }

        // Listen for the DownloadQualityDialog's selection result and
        // enqueue the download against the pending position. Registration
        // MUST be on childFragmentManager because that is where the dialog
        // is shown — the dialog posts via its parentFragmentManager which
        // equals this fragment's childFragmentManager. (Fragment's
        // setFragmentResultListener extension uses parentFragmentManager and
        // would silently miss the result.)
        childFragmentManager.setFragmentResultListener(
            DownloadQualityDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            val pos = pendingDownloadPosition
            pendingDownloadPosition = -1
            if (pos < 0) return@setFragmentResultListener
            val item = viewModel.items.value.getOrNull(pos) ?: return@setFragmentResultListener
            val targetHeight = result.getInt(DownloadQualityDialog.RESULT_TARGET_HEIGHT, 0)
                .takeIf { it > 0 }
            val audioOnly = result.getBoolean(DownloadQualityDialog.RESULT_IS_AUDIO_ONLY, false)
            val request = DownloadRequest(
                id = item.id + "_" + System.currentTimeMillis(),
                title = item.title,
                videoId = item.id,
                audioOnly = audioOnly,
                targetHeight = targetHeight,
                thumbnailUrl = item.thumbnailUrl
            )
            downloadRepository.enqueue(request)
            Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
        }

        // items -> adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { list ->
                pagerAdapter.submitList(list) {
                    // After the first non-empty commit, bind page 0 once.
                    if (!hasBoundInitialPage && list.isNotEmpty()) {
                        hasBoundInitialPage = true
                        val first = list[0]
                        bindPageWhenReady(0, first.id, first.channelId)
                    }
                }
            }
        }

        // VM events -> UI. Wrapped in repeatOnLifecycle(STARTED) so transient
        // one-shot events (SkipCurrent / LoadError toasts) are only delivered
        // while the fragment is visible, never while stopped.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { evt ->
                    when (evt) {
                        is ShortsPlayerViewModel.LoadEvent.SkipCurrent -> {
                            val b = binding ?: return@collect
                            val cur = b.shortsPager.currentItem
                            val size = viewModel.items.value.size
                            if (cur < size - 1) {
                                b.shortsPager.currentItem = cur + 1
                            } else {
                                showToast(R.string.shorts_error_unavailable)
                            }
                        }
                        is ShortsPlayerViewModel.LoadEvent.LoadError -> {
                            showToast(R.string.shorts_error_feed_empty)
                        }
                    }
                }
            }
        }

        // Player stream failures -> VM (which will emit a SkipCurrent).
        // Also gated on STARTED — we don't want to react to stale failures
        // while the fragment is off-screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                localBinder.failureEvents.collect { failingId ->
                    val idx = viewModel.items.value.indexOfFirst { it.id == failingId }
                    if (idx >= 0) viewModel.onPlaybackError(idx)
                }
            }
        }

        // Stream resolution → audio-language option list for the page. Pushes
        // into a per-videoId StateFlow that the adapter collects to flip the
        // rail button's visibility. Gated on STARTED so stale resolutions
        // arriving after backgrounding don't wake anything.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                localBinder.resolvedEvents.collect { (videoId, resolved) ->
                    val options = resolved.availableAudioLanguages()
                    audioLanguageFlowFor(videoId).value = options
                    subtitleFlowFor(videoId).value = resolved.subtitleTracks
                }
            }
        }

        // Audio-language dialog result — swap the active audio track without
        // tearing the player down. Registered on childFragmentManager for the
        // same reason as the download dialog above.
        childFragmentManager.setFragmentResultListener(
            AudioLanguageDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            val videoId = pendingAudioLanguageVideoId
            pendingAudioLanguageVideoId = null
            val code = result.getString(AudioLanguageDialog.RESULT_SELECTED_LANGUAGE)
                ?: return@setFragmentResultListener
            if (videoId == null) return@setFragmentResultListener
            val options = audioLanguageFlowFor(videoId).value
            val chosen = options.firstOrNull { it.language == code } ?: return@setFragmentResultListener
            activeLanguageByVideoId[videoId] = code

            // Always invalidate + rebuild. We don't branch on
            // `resolved.dashUrl` / `hlsUrl` because those reflect what
            // NewPipe surfaced, not what the factory actually built —
            // HLS is disabled via the Media3 1.9.2 crash workaround, so
            // most YouTube videos end up as SYNTH_ADAPTIVE even when an
            // HLS URL exists. SYNTH_ADAPTIVE bakes a single audio track
            // into a synthetic MPD that's cached by videoId, so a track
            // selector hint cannot help — the manifest advertises only
            // one audio track and no `lang` attribute to match against.
            android.util.Log.d(
                "ShortsPlayerFragment",
                "AudioLanguageDialog result: videoId=$videoId lang=$code"
            )
            mpdRegistry.unregisterBoth(videoId)
            binder?.switchAudioTrack(videoId, chosen.representative)
        }

        // Subtitle dialog result — apply the chosen language (or null = Off)
        // to ExoPlayer's track selection parameters.
        childFragmentManager.setFragmentResultListener(
            com.albunyaan.tube.ui.shared.SubtitlePickerDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            val code = result.getString(com.albunyaan.tube.ui.shared.SubtitlePickerDialog.RESULT_SELECTED_CODE)
            viewModel.player.trackSelectionParameters = viewModel.player.trackSelectionParameters
                .buildUpon()
                .setPreferredTextLanguage(code)
                .build()
        }
    }

    /**
     * Binds the ExoPlayer to the PlayerView of the ViewHolder at [position].
     * Retries via `post` if the ViewHolder is not yet attached (common on the
     * very first page after `submitList`, before layout has completed).
     *
     * [PlayerBinder.bind] is non-suspending and self-serializing, so we call
     * it directly — no `lifecycleScope.launch` wrapper is needed. The binder
     * cancels its own prior in-flight resolve on each new bind.
     */
    private fun bindPageWhenReady(position: Int, videoId: String, channelId: String? = null) {
        val b = binding ?: return
        val b2 = binder ?: return
        pushCachedAudioLanguagesForVideo(videoId)
        val holder = findViewHolderAt(position)
        if (holder != null) {
            b2.bind(holder.playerView, videoId, channelId)
            attachTimeBarToHolder(holder)
            return
        }
        // Not yet attached — retry after the pending layout pass.
        b.shortsPager.post {
            // Guard against stale post() retries: if the user swiped between
            // the initial bindPageWhenReady() call and this post running, the
            // pager's currentItem no longer matches [position]. Binding the
            // stale page would put the wrong videoId on the now-visible page's
            // PlayerView. The new page's OnPageChangeCallback will bind
            // correctly; we just need to abandon this stale retry.
            val bnd = binding ?: return@post
            if (!isAdded) return@post
            if (bnd.shortsPager.currentItem != position) return@post
            // Re-verify the item at this position still has the same id.
            // If the list mutated while the post was queued (e.g. retroactive
            // header decoration replaced the item), the captured videoId
            // could be stale even though currentItem still equals position.
            val currentItem = viewModel.items.value.getOrNull(position) ?: return@post
            if (currentItem.id != videoId) return@post
            val retry = findViewHolderAt(position) ?: return@post
            attachTimeBarToHolder(retry)
            val activeBinder = binder ?: return@post
            activeBinder.bind(retry.playerView, videoId, channelId)
        }
    }

    private fun findViewHolderAt(position: Int): ShortsPageViewHolder? {
        val b = binding ?: return null
        // ViewPager2's internal RecyclerView is child 0.
        val rv = b.shortsPager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(position)
        return holder as? ShortsPageViewHolder
    }

    private fun shareShort(idx: Int) {
        val item = viewModel.items.value.getOrNull(idx) ?: return
        val title = item.title.takeIf { it.isNotBlank() } ?: item.id
        val shareUrl = ShareLinks.video(
            videoId = item.id,
            title = title,
            imageUrl = item.thumbnailUrl,
            description = null,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            ShareMetadataPublisher.publish(
                type = "watch",
                id = item.id,
                title = title,
                imageUrl = item.thumbnailUrl,
                description = null,
            )
            if (!isAdded) return@launch
            val ctx = context ?: return@launch
            val shareMessage = buildString {
                append(title)
                append("\n\n")
                append(getString(R.string.share_watch_in_app))
                append("\n")
                append(shareUrl)
                append("\n\n")
                append(getString(R.string.share_app_promo))
            }
            ShareCompat.IntentBuilder(ctx)
                .setType("text/plain")
                .setSubject(title)
                .setText(shareMessage)
                .setChooserTitle(R.string.shorts_share_cd)
                .startChooser()
        }
    }

    private fun openChannel(idx: Int) {
        val item = viewModel.items.value.getOrNull(idx) ?: return
        if (item.channelId.isBlank()) return
        findNavController().navigate(
            R.id.action_global_channelDetailFragment,
            Bundle().apply {
                putString(com.albunyaan.tube.ui.detail.ChannelDetailFragment.ARG_CHANNEL_ID, item.channelId)
                putString(com.albunyaan.tube.ui.detail.ChannelDetailFragment.ARG_CHANNEL_NAME, item.channelName)
                putString(com.albunyaan.tube.ui.detail.ChannelDetailFragment.ARG_CHANNEL_AVATAR_URL, item.channelAvatarUrl)
            }
        )
    }


    /**
     * Open the same quality picker the main [PlayerFragment][com.albunyaan.tube.ui.player.PlayerFragment]
     * uses. Requires the stream resolution to already be cached from playback —
     * since the current page is always playing, this is true for the currently-
     * visible short. Feeds the selected quality into [DownloadRepository] via
     * the same [DownloadRequest] schema as the main player.
     */
    /**
     * Synchronously populate the audio-language flow from [PlayerBinder]'s
     * resolution cache. Belt-and-suspenders for the SharedFlow replay — if
     * the stream was already resolved (prefetch), pushing the options now
     * guarantees the adapter sees the correct count immediately.
     */
    private fun pushCachedAudioLanguagesForVideo(videoId: String) {
        val cached = binder?.resolvedStreamsFor(videoId) ?: return
        val options = cached.availableAudioLanguages()
        audioLanguageFlowFor(videoId).value = options
    }

    /**
     * Show the audio-language picker for the short at [idx]. No-op if the
     * video has fewer than two languages (the rail button is already hidden
     * in that case but this guards against race conditions).
     */
    private fun openAudioLanguagePicker(idx: Int) {
        val item = viewModel.items.value.getOrNull(idx) ?: return
        val options = audioLanguageFlowFor(item.id).value
        if (options.size < 2) return
        pendingAudioLanguageVideoId = item.id
        val triples = options.map { opt ->
            val label = if (opt.isOriginal) {
                getString(R.string.shorts_audio_track_original_prefix, opt.displayName)
            } else opt.displayName
            Triple(opt.language, label, opt.isOriginal)
        }
        AudioLanguageDialog.newInstance(triples, activeLanguageByVideoId[item.id])
            .show(childFragmentManager, AudioLanguageDialog.TAG)
    }

    /**
     * Show the subtitle picker for the short at [idx]. No-op if the video
     * has no subtitle tracks (the CC button is already hidden in that case
     * but this guards against race conditions).
     */
    private fun openSubtitlePicker(idx: Int) {
        val item = viewModel.items.value.getOrNull(idx) ?: return
        val subtitles = subtitleFlowFor(item.id).value
        if (subtitles.isEmpty()) return
        val tracks = subtitles.map { Triple(it.languageCode, it.languageName, it.isAutoGenerated) }
        val currentCode = viewModel.player.trackSelectionParameters.preferredTextLanguages.firstOrNull()
        com.albunyaan.tube.ui.shared.SubtitlePickerDialog.newInstance(tracks, currentCode)
            .show(childFragmentManager, com.albunyaan.tube.ui.shared.SubtitlePickerDialog.TAG)
    }

    private fun downloadShort(idx: Int) {
        val item = viewModel.items.value.getOrNull(idx) ?: return
        val resolved = binder?.resolvedStreamsFor(item.id)
        if (resolved == null) {
            showToast(R.string.shorts_download_preparing)
            return
        }
        pendingDownloadPosition = idx
        DownloadQualityDialog.newInstance(resolved)
            .show(childFragmentManager, DownloadQualityDialog.TAG)
    }

    private fun showToast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * Kebab popup for the Shorts player. Quality picker for everyone, plus
     * a Report action that scopes the report to the channel parent the
     * Shorts player was launched with (passed via Bundle by the originating
     * channel Shorts tab — no long-press required at the list level).
     */
    private fun showShortsKebabMenu(anchor: android.view.View) {
        val popup = androidx.appcompat.widget.PopupMenu(
            requireContext(),
            anchor,
            android.view.Gravity.END,
        )
        popup.menuInflater.inflate(R.menu.menu_shorts_kebab, popup.menu)
        popup.showIcons()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_quality -> {
                    showQualityPicker()
                    true
                }
                R.id.action_report -> {
                    showReportSheetForCurrentShort()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Open the report bottom sheet for the currently visible Short. Parent
     * defaults to CHANNEL with parentId=channelId from the navigation Bundle
     * (Shorts always live under a channel). contentSubType is forced to SHORT
     * so the resolution path puts exclusions in the channel's shorts bucket.
     */
    private fun showReportSheetForCurrentShort() {
        val bnd = binding ?: return
        val currentIndex = bnd.shortsPager.currentItem
        val videoId = viewModel.items.value.getOrNull(currentIndex)?.id
            ?: arguments?.getString("initialShortId")
        if (videoId.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.player_video_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val channelId = arguments?.getString("channelId")?.takeIf { it.isNotBlank() }
        val parentType = if (channelId != null) {
            com.albunyaan.tube.data.report.ReportTargetType.CHANNEL
        } else null
        com.albunyaan.tube.ui.report.ContentReportBottomSheet
            .newInstance(
                targetType = com.albunyaan.tube.data.report.ReportTargetType.VIDEO,
                targetId = videoId,
                parentType = parentType,
                parentId = channelId,
                contentSubType = com.albunyaan.tube.data.report.ReportContentSubType.SHORT,
            )
            .show(parentFragmentManager, com.albunyaan.tube.ui.report.ContentReportBottomSheet.TAG)
    }

    /**
     * Show the kebab quality picker. Pulls the standard quality ladder from
     * the ViewModel and pre-checks the current user-applied cap (0 = Auto).
     * The dialog posts the selected height back via Fragment Result API; the
     * listener registered in onViewCreated calls viewModel.applyQualityCap.
     */
    private fun showQualityPicker() {
        val qualities = viewModel.getQualityOptions()
        val current = viewModel.getUserQualityCap()
        com.albunyaan.tube.ui.shared.QualityPickerDialog
            .newInstance(qualities, current)
            .show(childFragmentManager, com.albunyaan.tube.ui.shared.QualityPickerDialog.TAG)
    }

    /**
     * Wire the scrubber on [holder] to the shared player. Registers the
     * scrub listener once per holder and starts the shared ticker if it
     * isn't already running. The ticker always targets the pager's current
     * page, so changing pages naturally transfers driving to the new holder.
     */
    private fun attachTimeBarToHolder(holder: ShortsPageViewHolder) {
        val player = viewModel.player
        holder.timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                isScrubbing = true
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                // Preview scrub position. Actual seek happens on stop so we
                // don't thrash the decoder.
            }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                isScrubbing = false
                if (!canceled) player.seekTo(position)
            }
        })
        startTimeBarTicker()
    }

    private fun startTimeBarTicker() {
        if (timeBarTicker != null) return
        val ticker = object : Runnable {
            override fun run() {
                val bnd = binding
                if (bnd != null && !isScrubbing) {
                    val active = findViewHolderAt(bnd.shortsPager.currentItem)
                    if (active != null) {
                        val p = viewModel.player
                        val duration = p.duration.coerceAtLeast(0L)
                        val position = p.currentPosition.coerceIn(0L, duration)
                        val buffered = p.bufferedPosition.coerceIn(0L, duration)
                        active.timeBar.setDuration(duration)
                        active.timeBar.setPosition(position)
                        active.timeBar.setBufferedPosition(buffered)
                    }
                }
                timeBarHandler.postDelayed(this, TIME_BAR_UPDATE_MS)
            }
        }
        timeBarTicker = ticker
        timeBarHandler.post(ticker)
    }

    private fun stopTimeBarTicker() {
        timeBarTicker?.let { timeBarHandler.removeCallbacks(it) }
        timeBarTicker = null
    }

    override fun onResume() {
        super.onResume()
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val view = view ?: return
        WindowCompat.getInsetsController(window, view)
            .hide(WindowInsetsCompat.Type.systemBars())
        didEnterImmersive = true

        // Shorts are a portrait-only format. Lock orientation while the
        // fragment is in the foreground and restore the activity's prior
        // policy when we leave (onPause / onDestroyView).
        val activity = requireActivity()
        if (previousOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            previousOrientation = activity.requestedOrientation
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onPause() {
        super.onPause()
        // Restore the activity's orientation policy immediately on pause so
        // other fragments don't inherit our portrait lock on a fragment swap.
        val activity = activity ?: return
        if (previousOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.requestedOrientation = previousOrientation
            previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't bleed audio/video when the fragment is no longer in the
        // foreground. Record whether playback was running so we can restore
        // it seamlessly when the user returns; if the video was already
        // paused, stay paused.
        val currentlyBinder = binder
        wasPlayingBeforeStop = currentlyBinder?.isPlaying() == true
        currentlyBinder?.pause()

        // Covers "Activity finish skips straight to destroy" — restore here too.
        restoreSystemBarsIfImmersive()
    }

    override fun onStart() {
        super.onStart()
        // Auto-resume if the user left the screen mid-playback so the short
        // keeps playing right where they left off.
        if (wasPlayingBeforeStop) {
            binder?.resume()
            wasPlayingBeforeStop = false
        }
    }

    override fun onDestroyView() {
        // Unregister any cached synthetic DASH MPD for the currently-visible
        // short so stale syntheticdash://<videoId> entries don't accumulate.
        val currentPos = binding?.shortsPager?.currentItem
        if (currentPos != null) {
            val currentVideoId = viewModel.items.value.getOrNull(currentPos)?.id
            if (currentVideoId != null) {
                mpdRegistry.unregisterBoth(currentVideoId)
            }
        }
        // Restore system bars here (not in onPause) to prevent flicker on transient
        // pauses (e.g. permission dialog, bottom sheet) while the shorts UI is still alive.
        restoreSystemBarsIfImmersive()
        stopTimeBarTicker()
        pageChangeCallback?.let { binding?.shortsPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        binding?.shortsPager?.adapter = null
        adapter = null
        // Player is owned by the ViewModel; only detach the PlayerView here.
        // cancelScope() aborts any in-flight bind resolution so a late-arriving
        // stream doesn't mutate the still-alive (VM-owned) player after the
        // fragment view is gone. Do NOT call release() — VM owns the player.
        // CRITICAL: removeListener BEFORE clearing binder — otherwise the
        // VM-owned player retains a strong ref to this Fragment instance
        // (via the listener's captured timeBarHandler / stallRecoveryRunnable
        // / mpdRegistry refs), pinning the entire view tree across Fragment
        // recreations.
        viewModel.player.removeListener(stallListener)
        timeBarHandler.removeCallbacks(stallRecoveryRunnable)
        binder?.detach()
        binder?.cancelScope()
        binder = null
        binding = null
        hasBoundInitialPage = false
        // Per-video flow maps are NOT cleared here. The previous version of
        // this fragment cleared them on the theory that PlayerBinder's
        // [resolvedEvents] SharedFlow would replay-and-repopulate after
        // recreation — but the cache that backs resolvedStreamsFor is
        // fragment-scoped (lost on rotation), and resolvedEvents replay=1
        // only carries the most-recent emission which may be for a
        // different video than the currently-playing one. Clearing here
        // caused an empty audio/subtitle dialog if the user opened it
        // immediately after a rotation. Maps grow one entry per scrolled
        // video — session-bounded (50-100 entries in a long shorts
        // session ≈ ~50KB), well under the cost of the empty-dialog UX
        // bug. [activeLanguageByVideoId] specifically holds the user's
        // language selection memory which must survive recreation so the
        // dialog can preselect correctly.
        super.onDestroyView()
    }

    private fun restoreSystemBarsIfImmersive() {
        if (!didEnterImmersive) return
        val activity = activity ?: return
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val anchor = activity.findViewById<View>(android.R.id.content) ?: return
        WindowCompat.getInsetsController(window, anchor)
            .show(WindowInsetsCompat.Type.systemBars())
        didEnterImmersive = false
    }

    companion object {
        private const val ARG_INITIAL_SHORT_ID = "initialShortId"
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_INITIAL_SHORT_TITLE = "initialShortTitle"
        private const val ARG_INITIAL_CHANNEL_NAME = "initialChannelName"
        private const val ARG_INITIAL_THUMBNAIL_URL = "initialThumbnailUrl"
        private const val ARG_INITIAL_CHANNEL_AVATAR_URL = "initialChannelAvatarUrl"
        private const val ARG_INITIAL_DURATION_SECONDS = "initialDurationSeconds"
        private const val TIME_BAR_UPDATE_MS = 250L
        /** Force a fresh URL resolve after this long stuck in BUFFERING. */
        private const val STALL_RECOVERY_MS = 6_000L
    }
}
