package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentChannelDetailBinding
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import java.text.NumberFormat

class ChannelDetailFragment : Fragment(R.layout.fragment_channel_detail) {

    private var binding: FragmentChannelDetailBinding? = null

    private val channelId: String by lazy { arguments?.getString("channelId").orEmpty() }
    private val channelName: String? by lazy { arguments?.getString("channelName") }
    private val isExcluded: Boolean by lazy { arguments?.getBoolean("excluded", false) ?: false }

    private val viewModel: ChannelDetailViewModel by viewModels {
        ChannelDetailViewModel.Factory(
            ServiceLocator.provideContentService(),
            channelId
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelDetailBinding.bind(view)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding?.apply {
            // Set toolbar title and back button
            toolbar.title = channelName ?: channelId
            toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }

            // Show exclusion banner if needed
            exclusionBanner.isVisible = isExcluded

            // Setup tabs
            val tabs = ChannelTab.values()
            viewPager.adapter = ChannelDetailPagerAdapter(
                this@ChannelDetailFragment,
                channelId,
                channelName ?: channelId,
                tabs,
                viewModel
            )
            
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.setText(tabs[position].titleRes)
            }.attach()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.channelState.collect { state ->
                when (state) {
                    is ChannelDetailViewModel.ChannelState.Loading -> {
                        Log.d(TAG, "Loading channel details...")
                        binding?.apply {
                            progressBar.isVisible = true
                            contentContainer.isVisible = false
                            errorText.isVisible = false
                        }
                    }
                    is ChannelDetailViewModel.ChannelState.Success -> {
                        Log.d(TAG, "Channel loaded: ${state.channel.name}")
                        binding?.apply {
                            progressBar.isVisible = false
                            contentContainer.isVisible = true
                            errorText.isVisible = false

                            // Update toolbar title with actual channel name
                            toolbar.title = state.channel.name

                            // Update channel info
                            channelNameText.text = state.channel.name
                            
                            val formattedSubs = NumberFormat.getInstance().format(state.channel.subscribers)
                            subscriberCountText.text = getString(R.string.channel_subscribers_format, formattedSubs)
                            
                            if (!state.channel.description.isNullOrBlank()) {
                                channelDescriptionText.text = state.channel.description
                                channelDescriptionText.isVisible = true
                            } else {
                                channelDescriptionText.isVisible = false
                            }

                            // Update category chips
                            categoryChipsContainer.removeAllViews()
                            val categories = state.channel.categories ?: listOf(state.channel.category)
                            if (categories.isNotEmpty()) {
                                categories.forEach { category ->
                                    val chip = Chip(requireContext()).apply {
                                        text = category
                                        isClickable = false
                                        chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                                            requireContext().getColor(R.color.surface_variant)
                                        )
                                        setTextColor(requireContext().getColor(R.color.primary_green))
                                    }
                                    categoryChipsContainer.addView(chip)
                                }
                                categoryChipsContainer.isVisible = true
                            } else {
                                categoryChipsContainer.isVisible = false
                            }
                        }
                    }
                    is ChannelDetailViewModel.ChannelState.Error -> {
                        Log.e(TAG, "Error loading channel: ${state.message}")
                        binding?.apply {
                            progressBar.isVisible = false
                            contentContainer.isVisible = false
                            errorText.isVisible = true
                            errorText.text = state.message
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ChannelDetailFragment"
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_CHANNEL_NAME = "channelName"
        const val ARG_EXCLUDED = "excluded"
    }
}

private class ChannelDetailPagerAdapter(
    fragment: Fragment,
    private val channelId: String,
    private val channelName: String,
    private val tabs: Array<ChannelTab>,
    private val viewModel: ChannelDetailViewModel
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        val tab = tabs[position]
        return ChannelDetailTabFragment.newInstance(tab, channelId, channelName)
    }
}

enum class ChannelTab(val titleRes: Int) {
    VIDEOS(R.string.channel_tab_videos),
    LIVE(R.string.channel_tab_live),
    SHORTS(R.string.channel_tab_shorts),
    PLAYLISTS(R.string.channel_tab_playlists),
    POSTS(R.string.channel_tab_posts),
    ABOUT(R.string.channel_tab_about)
}
