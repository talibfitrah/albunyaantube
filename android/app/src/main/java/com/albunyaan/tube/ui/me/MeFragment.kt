package com.albunyaan.tube.ui.me

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.me.work.RefreshScheduler
import com.albunyaan.tube.databinding.FragmentMeBinding
import com.albunyaan.tube.util.DeviceConfig
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MeFragment : Fragment(R.layout.fragment_me) {

    @Inject lateinit var refreshScheduler: RefreshScheduler

    private val viewModel: MeViewModel by viewModels()
    private var binding: FragmentMeBinding? = null

    private lateinit var chipsAdapter: MeChipsAdapter
    private lateinit var shortsAdapter: MeShortsAdapter
    private lateinit var videosAdapter: MeVideosAdapter
    private lateinit var concatAdapter: ConcatAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentMeBinding.bind(view).also { binding = it }

        chipsAdapter = MeChipsAdapter(
            onClick = ::onChipClicked,
        )
        shortsAdapter = MeShortsAdapter(onClick = ::playVideo)
        videosAdapter = MeVideosAdapter(onClick = ::playVideo)

        concatAdapter = ConcatAdapter(
            chipsAdapter.rowAdapter,
            shortsAdapter.sectionAdapter,
            videosAdapter.sectionAdapter,
        )

        val isTablet = DeviceConfig.isTablet(requireContext()) || DeviceConfig.isTV(requireContext())
        val isLarge = resources.configuration.smallestScreenWidthDp >= 720
        b.meRecycler.layoutManager = if (isTablet) {
            val spanCount = if (isLarge) 3 else 2
            GridLayoutManager(requireContext(), spanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val viewType = concatAdapter.getItemViewType(position)
                        return if (viewType == MeVideosAdapter.VIDEO_VIEW_TYPE) 1 else spanCount
                    }
                }
            }
        } else {
            LinearLayoutManager(requireContext())
        }
        b.meRecycler.adapter = concatAdapter
        b.meRecycler.itemAnimator?.changeDuration = 0L

        // T9: pull-to-refresh enqueues a force=true one-shot via the
        // RefreshScheduler. The SwipeRefreshLayout spinner is dismissed by
        // the WorkInfo observation below — not by the listener.
        b.meSwipeRefresh.setOnRefreshListener { refreshScheduler.enqueuePullToRefresh() }

        b.meEmpty.meEmptyCta.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.meFragment) {
                findNavController().navigate(R.id.channelsFragment)
            }
        }

        // T9: SwipeRefreshLayout spinner driven by the unique-one-shot
        // worker's WorkInfo. While any of the worker's WorkInfo states is
        // not finished (ENQUEUED / RUNNING / BLOCKED), the spinner stays;
        // once SUCCEEDED / FAILED / CANCELLED we dismiss it. observe() is
        // bound to viewLifecycleOwner so it auto-detaches on view destroy.
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(RefreshScheduler.UNIQUE_ONESHOT_NAME)
            .observe(viewLifecycleOwner) { infos ->
                val running = infos?.any { !it.state.isFinished } == true
                binding?.meSwipeRefresh?.isRefreshing = running
            }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // T9: foreground burst. Only fires a one-shot when the newest
        // cached fetch is older than RefreshScheduler.DEFAULT_STALE_THRESHOLD_MS,
        // so tab-switching doesn't hammer YouTube.
        viewLifecycleOwner.lifecycleScope.launch {
            refreshScheduler.enqueueForegroundBurstIfStale()
        }
    }

    private fun render(state: MeFeedState) {
        val b = binding ?: return
        when (state) {
            is MeFeedState.Loading -> {
                // T9: SwipeRefreshLayout spinner is owned by the WorkInfo
                // observer; don't toggle it from render() or it'll fight the
                // observer.
                b.meEmpty.root.visibility = View.GONE
                b.meRecycler.visibility = View.VISIBLE
            }
            is MeFeedState.Empty -> {
                b.meEmpty.root.visibility = View.VISIBLE
                b.meRecycler.visibility = View.GONE
            }
            is MeFeedState.Content -> {
                b.meEmpty.root.visibility = View.GONE
                b.meRecycler.visibility = View.VISIBLE

                chipsAdapter.selectedId = state.filterChannelId
                chipsAdapter.submit(state.chips)
                shortsAdapter.submit(state.shorts)
                videosAdapter.submit(state.videos)
                // T9: removed auto-load post-submitList check — there is no
                // page-2 anymore. The cache is what it is; the worker
                // mutates it on its own cadence (hourly periodic + onResume
                // burst when stale).
            }
            is MeFeedState.Error -> {
                Snackbar.make(b.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun onChipClicked(chip: ChipItem) {
        when (chip) {
            is ChipItem.Channel -> {
                val currentFilter = (viewModel.state.value as? MeFeedState.Content)?.filterChannelId
                viewModel.setFilter(if (currentFilter == chip.id) null else chip.id)
            }
            is ChipItem.Playlist -> {
                val args = Bundle().apply {
                    putString("playlistId", chip.id)
                    putString("playlistTitle", chip.label)
                }
                if (findNavController().currentDestination?.id == R.id.meFragment) {
                    findNavController().navigate(R.id.playlistDetailFragment, args)
                }
            }
        }
    }

    private fun playVideo(video: MeFeedVideo) {
        // F9: guard against an empty videoId making it this far. The fetcher
        // already filters at source (F5) but belt-and-braces — an empty id
        // passed to PlayerFragment would blow up in NewPipe extraction.
        if (video.videoId.isBlank()) return
        val args = Bundle().apply { putString("videoId", video.videoId) }
        if (findNavController().currentDestination?.id == R.id.meFragment) {
            findNavController().navigate(R.id.playerFragment, args)
        }
    }

    override fun onDestroyView() {
        binding?.meRecycler?.adapter = null
        binding = null
        super.onDestroyView()
    }
}
