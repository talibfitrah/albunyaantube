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
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.databinding.FragmentMeBinding
import com.albunyaan.tube.util.DeviceConfig
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MeFragment : Fragment(R.layout.fragment_me) {

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

        b.meSwipeRefresh.setOnRefreshListener { viewModel.refreshFeed(force = true) }

        b.meEmpty.meEmptyCta.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.meFragment) {
                findNavController().navigate(R.id.channelsFragment)
            }
        }

        b.meRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !rv.canScrollVertically(1)) viewModel.refreshFeed(force = false)
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MeFeedState) {
        val b = binding ?: return
        when (state) {
            is MeFeedState.Loading -> {
                b.meSwipeRefresh.isRefreshing = true
                b.meEmpty.root.visibility = View.GONE
                b.meRecycler.visibility = View.VISIBLE
            }
            is MeFeedState.Empty -> {
                b.meSwipeRefresh.isRefreshing = false
                b.meEmpty.root.visibility = View.VISIBLE
                b.meRecycler.visibility = View.GONE
            }
            is MeFeedState.Content -> {
                b.meEmpty.root.visibility = View.GONE
                b.meRecycler.visibility = View.VISIBLE
                b.meSwipeRefresh.isRefreshing = state.refreshing

                chipsAdapter.selectedId = state.filterChannelId
                chipsAdapter.submit(state.chips)
                shortsAdapter.submit(state.shorts)
                videosAdapter.submit(state.videos)

                b.meRecycler.post {
                    if (view == null) return@post
                    if (!b.meRecycler.canScrollVertically(1) &&
                        (state.videos.isNotEmpty() || state.shorts.isNotEmpty())
                    ) {
                        viewModel.refreshFeed(force = false)
                    }
                }
            }
            is MeFeedState.Error -> {
                b.meSwipeRefresh.isRefreshing = false
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
