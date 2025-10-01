package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentChannelDetailBinding
import com.google.android.material.tabs.TabLayoutMediator

class ChannelDetailFragment : Fragment(R.layout.fragment_channel_detail) {

    private var binding: FragmentChannelDetailBinding? = null

    private val channelId: String by lazy { requireArguments().getString(ARG_CHANNEL_ID).orEmpty() }
    private val channelName: String by lazy { requireArguments().getString(ARG_CHANNEL_NAME).orEmpty() }
    private val isExcluded: Boolean by lazy { requireArguments().getBoolean(ARG_EXCLUDED, false) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelDetailBinding.bind(view).apply {
            channelTitle.text = channelName.ifBlank { channelId }
            exclusionBanner.visibility = if (isExcluded) View.VISIBLE else View.GONE
            val tabs = ChannelTab.values()
            viewPager.adapter = ChannelDetailPagerAdapter(this@ChannelDetailFragment, channelId, channelName, tabs)
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.setText(tabs[position].titleRes)
            }.attach()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_CHANNEL_NAME = "channelName"
        const val ARG_EXCLUDED = "excluded"
    }
}

private class ChannelDetailPagerAdapter(
    fragment: Fragment,
    private val channelId: String,
    private val channelName: String,
    private val tabs: Array<ChannelTab>
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
    POSTS(R.string.channel_tab_posts)
}
