package com.albunyaan.tube.ui.shorts

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentShortsPlayerBinding
import com.albunyaan.tube.player.PlayerRepository
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
                onSubscribe = { idx -> viewModel.toggleFollow(idx) },
                onChannelTap = { idx -> openChannel(idx) },
                onTapVideo = { _ -> localBinder.togglePlayPause() },
                onLikedFlow = { id -> viewModel.isLikedFlow(id) },
                onFollowedFlow = { id -> viewModel.isFollowedFlow(id) }
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
            return
        }
        // Not yet attached — retry after the pending layout pass.
        b.shortsPager.post {
            val retry = findViewHolderAt(position) ?: return@post
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
        ShareCompat.IntentBuilder(requireContext())
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

    private fun showToast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val view = view ?: return
        WindowCompat.getInsetsController(window, view)
            .hide(WindowInsetsCompat.Type.systemBars())
        didEnterImmersive = true
    }

    override fun onStop() {
        super.onStop()
        // Covers "Activity finish skips straight to destroy" — restore here too.
        restoreSystemBarsIfImmersive()
    }

    override fun onDestroyView() {
        // Restore system bars here (not in onPause) to prevent flicker on transient
        // pauses (e.g. permission dialog, bottom sheet) while the shorts UI is still alive.
        restoreSystemBarsIfImmersive()
        pageChangeCallback?.let { binding?.shortsPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        binding?.shortsPager?.adapter = null
        adapter = null
        // Player is owned by the ViewModel; only detach the PlayerView here.
        binder?.detach()
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
    }
}
