package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.databinding.FragmentChannelDetailBinding
import com.albunyaan.tube.ui.detail.tabs.ChannelAboutTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelLiveTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelPlaylistsTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelShortsTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelVideosTabFragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Fragment for displaying channel details with tabs for Videos, Live, Shorts, Playlists, and About.
 * Uses NewPipeExtractor directly via ChannelDetailRepository (no backend API calls).
 *
 * Note: Posts/Community tab is not supported because NewPipeExtractor doesn't support
 * YouTube Community Posts extraction.
 */
@AndroidEntryPoint
class ChannelDetailFragment : Fragment(R.layout.fragment_channel_detail) {

    private var binding: FragmentChannelDetailBinding? = null

    private val channelId: String by lazy { arguments?.getString(ARG_CHANNEL_ID).orEmpty() }
    private val channelName: String? by lazy { arguments?.getString(ARG_CHANNEL_NAME) }
    private val isExcluded: Boolean by lazy { arguments?.getBoolean(ARG_EXCLUDED, false) ?: false }

    private val viewModel: ChannelDetailViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<ChannelDetailViewModel.Factory> { factory ->
                factory.create(channelId)
            }
        }
    )

    private var tabLayoutMediator: TabLayoutMediator? = null
    private var pageChangeCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var appBarOffsetListener: AppBarLayout.OnOffsetChangedListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelDetailBinding.bind(view)

        setupToolbar()
        setupTabs()
        observeHeaderState()

        // Restore selected tab
        savedInstanceState?.getInt(STATE_SELECTED_TAB)?.let { position ->
            binding?.viewPager?.setCurrentItem(position, false)
        }
    }

    private fun setupToolbar() {
        binding?.apply {
            toolbar.navigationIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            toolbar.title = channelName ?: channelId
            toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }

            val listener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
                val collapsed = appBarLayout.totalScrollRange + verticalOffset <= 0
                // Use colorOnPrimary for expanded state (white in both light/dark themes) for visibility over banner
                val expandedColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnPrimary)
                val collapsedColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
                toolbar.navigationIcon?.mutate()?.setTint(if (collapsed) collapsedColor else expandedColor)
                toolbar.setTitleTextColor(if (collapsed) collapsedColor else expandedColor)
            }
            appBarLayout.addOnOffsetChangedListener(listener)
            appBarOffsetListener = listener

            // Show exclusion banner if needed
            exclusionBanner.isVisible = isExcluded
        }
    }

    private fun setupTabs() {
        binding?.apply {
            val tabs = ChannelTab.entries.toTypedArray()

            viewPager.adapter = ChannelDetailPagerAdapter(
                fragment = this@ChannelDetailFragment,
                tabs = tabs
            )

            // Disable swipe between tabs if needed for nested scrolling
            viewPager.isUserInputEnabled = true

            tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.setText(getTabTitle(tabs[position]))
            }.also { it.attach() }

            // Track selected tab for state restoration
            pageChangeCallback = object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.setSelectedTab(position)
                }
            }
            viewPager.registerOnPageChangeCallback(pageChangeCallback!!)
        }
    }

    private fun getTabTitle(tab: ChannelTab): Int {
        return when (tab) {
            ChannelTab.VIDEOS -> R.string.channel_tab_videos
            ChannelTab.LIVE -> R.string.channel_tab_live
            ChannelTab.SHORTS -> R.string.channel_tab_shorts
            ChannelTab.PLAYLISTS -> R.string.channel_tab_playlists
            ChannelTab.ABOUT -> R.string.channel_tab_about
        }
    }

    private fun observeHeaderState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.headerState.collect { state ->
                updateHeaderUI(state)
            }
        }
    }

    private fun updateHeaderUI(state: ChannelDetailViewModel.HeaderState) {
        binding?.apply {
            // AppBarLayout always visible to keep toolbar (back button) accessible
            appBarLayout.isVisible = true

            when (state) {
                is ChannelDetailViewModel.HeaderState.Loading -> {
                    Log.d(TAG, "Loading channel header...")
                    // Show header skeleton (below toolbar) and content skeleton
                    headerSkeleton.isVisible = true
                    headerContent.isVisible = false
                    tabLayout.isVisible = false
                    viewPager.isVisible = false
                    contentSkeleton.isVisible = true
                    contentErrorState.root.isVisible = false
                }
                is ChannelDetailViewModel.HeaderState.Success -> {
                    Log.d(TAG, "Channel header loaded: ${state.header.title}")
                    headerSkeleton.isVisible = false
                    headerContent.isVisible = true
                    tabLayout.isVisible = true
                    viewPager.isVisible = true
                    contentSkeleton.isVisible = false
                    contentErrorState.root.isVisible = false
                    bindHeader(state.header)
                }
                is ChannelDetailViewModel.HeaderState.Error -> {
                    Log.e(TAG, "Error loading channel header: ${state.message}")
                    // Show error in content area (below AppBar so toolbar remains accessible)
                    headerSkeleton.isVisible = false
                    headerContent.isVisible = false
                    tabLayout.isVisible = false
                    viewPager.isVisible = false
                    contentSkeleton.isVisible = false
                    contentErrorState.root.isVisible = true
                    contentErrorState.errorBody.text = state.message
                    contentErrorState.retryButton.setOnClickListener {
                        viewModel.loadHeader(forceRefresh = true)
                    }
                }
            }
        }
    }

    private fun bindHeader(header: ChannelHeader) {
        binding?.apply {
            // Update toolbar title
            toolbar.title = header.title

            // Load banner image
            if (!header.bannerUrl.isNullOrBlank()) {
                channelBanner.load(header.bannerUrl) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    crossfade(true)
                }
                bannerGradient.isVisible = true
            } else {
                channelBanner.setImageResource(R.drawable.thumbnail_placeholder)
                bannerGradient.isVisible = false
            }

            // Load avatar
            if (!header.avatarUrl.isNullOrBlank()) {
                channelAvatar.load(header.avatarUrl) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    crossfade(true)
                }
            }

            // Channel name
            channelNameText.text = header.title

            // Verified badge (localized via content description)
            verifiedBadge.isVisible = header.isVerified

            // Subscriber count
            if (header.subscriberCount != null && header.subscriberCount > 0) {
                val formattedCount = NumberFormat.getInstance().format(header.subscriberCount)
                subscriberCountText.text = getString(R.string.channel_subscribers_format, formattedCount)
                subscriberCountText.isVisible = true
            } else {
                subscriberCountText.text = getString(R.string.channel_subscribers_unknown)
                subscriberCountText.isVisible = true
            }

            // Summary / short description
            val summary = header.summaryLine ?: header.shortDescription
            if (!summary.isNullOrBlank()) {
                channelSummaryText.text = summary
                channelSummaryText.isVisible = true
            } else {
                channelSummaryText.isVisible = false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding?.viewPager?.currentItem?.let { position ->
            outState.putInt(STATE_SELECTED_TAB, position)
        }
    }

    override fun onDestroyView() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        pageChangeCallback?.let { binding?.viewPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        binding?.appBarLayout?.removeOnOffsetChangedListener(appBarOffsetListener)
        appBarOffsetListener = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ChannelDetailFragment"
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_CHANNEL_NAME = "channelName"
        const val ARG_EXCLUDED = "excluded"
        private const val STATE_SELECTED_TAB = "selectedTab"
    }
}

/**
 * ViewPager adapter for channel detail tabs.
 * Creates the appropriate fragment for each tab.
 */
private class ChannelDetailPagerAdapter(
    fragment: Fragment,
    private val tabs: Array<ChannelTab>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        return when (tabs[position]) {
            ChannelTab.VIDEOS -> ChannelVideosTabFragment()
            ChannelTab.LIVE -> ChannelLiveTabFragment()
            ChannelTab.SHORTS -> ChannelShortsTabFragment()
            ChannelTab.PLAYLISTS -> ChannelPlaylistsTabFragment()
            ChannelTab.ABOUT -> ChannelAboutTabFragment()
        }
    }
}
