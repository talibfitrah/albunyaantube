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
import com.albunyaan.tube.databinding.FragmentShortsPlayerBinding
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.ui.player.DownloadQualityDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private val viewModel: ShortsPlayerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val initialShortId = arguments?.getString(ARG_INITIAL_SHORT_ID)
                val channelId = arguments?.getString(ARG_CHANNEL_ID)
                return vmFactory.create(initialShortId, channelId) as T
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
    /** Time bar driver: periodically mirrors player.currentPosition into the active page's DefaultTimeBar. */
    private val timeBarHandler = Handler(Looper.getMainLooper())
    private var timeBarTicker: Runnable? = null
    /** True while the user is actively dragging the scrubber; suppresses the ticker overwrite. */
    private var isScrubbing = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bnd = FragmentShortsPlayerBinding.bind(view)
        binding = bnd

        val localBinder = PlayerBinder(viewModel.player, playerRepository)
        binder = localBinder

        val pagerAdapter = ShortsPagerAdapter(
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
                onLikedFlow = { id -> viewModel.isLikedFlow(id) },
            )
        )
        adapter = pagerAdapter
        bnd.shortsPager.adapter = pagerAdapter
        bnd.shortsPager.offscreenPageLimit = 1

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val item = viewModel.items.value.getOrNull(position) ?: return
                viewModel.onPageChanged(position)
                bindPageWhenReady(position, item.id)
            }
        }
        pageChangeCallback = callback
        bnd.shortsPager.registerOnPageChangeCallback(callback)

        bnd.shortsBackBtn.setOnClickListener {
            findNavController().popBackStack()
        }

        // Listen for the DownloadQualityDialog's selection result and
        // enqueue the download against the pending position.
        setFragmentResultListener(DownloadQualityDialog.REQUEST_KEY) { _, result ->
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
                        bindPageWhenReady(0, first.id)
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
    private fun bindPageWhenReady(position: Int, videoId: String) {
        val b = binding ?: return
        val b2 = binder ?: return
        val holder = findViewHolderAt(position)
        if (holder != null) {
            b2.bind(holder.playerView, videoId)
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
            activeBinder.bind(retry.playerView, videoId)
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
        // Use null-safe context — fragment may be detached if the share tap
        // races with destruction (e.g. system back during pending toast).
        val ctx = context ?: return
        ShareCompat.IntentBuilder(ctx)
            .setType("text/plain")
            .setText(item.canonicalShareUrl)
            .setChooserTitle(R.string.shorts_share_cd)
            .startChooser()
    }

    private fun openChannel(idx: Int) {
        val item = viewModel.items.value.getOrNull(idx) ?: return
        if (item.channelId.isBlank()) return
        findNavController().navigate(
            R.id.action_global_channelDetailFragment,
            Bundle().apply {
                putString("channelId", item.channelId)
                putString("channelName", item.channelName)
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
        binder?.detach()
        binder?.cancelScope()
        binder = null
        binding = null
        hasBoundInitialPage = false
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
        private const val TIME_BAR_UPDATE_MS = 250L
    }
}
