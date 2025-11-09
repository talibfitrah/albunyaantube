package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentChannelDetailTabBinding

class ChannelDetailTabFragment : Fragment(R.layout.fragment_channel_detail_tab) {

    private var binding: FragmentChannelDetailTabBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val titleRes = args.getInt(ARG_TITLE_RES)
        val channelName = args.getString(ARG_CHANNEL_NAME).orEmpty()
        binding = FragmentChannelDetailTabBinding.bind(view).apply {
            val tabLabel = if (titleRes != 0) getString(titleRes) else channelName
            placeholderText.text = getString(R.string.channel_detail_placeholder, tabLabel, channelName)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TITLE_RES = "tabTitle"
        private const val ARG_CHANNEL_NAME = "channelName"

        fun newInstance(tab: ChannelTab, channelId: String, channelName: String): ChannelDetailTabFragment {
            return ChannelDetailTabFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE_RES, tab.titleRes)
                    putString(ARG_CHANNEL_NAME, if (channelName.isNotBlank()) channelName else channelId)
                }
            }
        }
    }
}

