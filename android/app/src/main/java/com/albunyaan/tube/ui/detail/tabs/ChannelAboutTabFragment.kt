package com.albunyaan.tube.ui.detail.tabs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.databinding.FragmentChannelAboutTabBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Fragment for the About tab in Channel Detail.
 * Displays channel description, links, and additional info.
 */
@AndroidEntryPoint
class ChannelAboutTabFragment : Fragment(R.layout.fragment_channel_about_tab) {

    private var binding: FragmentChannelAboutTabBinding? = null

    private val channelId: String by lazy {
        requireParentFragment().arguments?.getString("channelId").orEmpty()
    }

    private val viewModel: ChannelDetailViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        extrasProducer = {
            requireParentFragment().defaultViewModelCreationExtras.withCreationCallback<ChannelDetailViewModel.Factory> { factory ->
                factory.create(channelId)
            }
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelAboutTabBinding.bind(view)

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aboutState.collect { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: ChannelDetailViewModel.HeaderState) {
        binding?.apply {
            when (state) {
                is ChannelDetailViewModel.HeaderState.Loading -> {
                    aboutSkeleton.isVisible = true
                    aboutScrollView.isVisible = false
                    aboutErrorState.root.isVisible = false
                }
                is ChannelDetailViewModel.HeaderState.Success -> {
                    aboutSkeleton.isVisible = false
                    aboutScrollView.isVisible = true
                    aboutErrorState.root.isVisible = false
                    bindHeader(state.header)
                }
                is ChannelDetailViewModel.HeaderState.Error -> {
                    aboutSkeleton.isVisible = false
                    aboutScrollView.isVisible = false
                    aboutErrorState.root.isVisible = true
                    // Set up retry button to reload header
                    aboutErrorState.retryButton.setOnClickListener {
                        viewModel.loadHeader(forceRefresh = true)
                    }
                }
            }
        }
    }

    private fun bindHeader(header: ChannelHeader) {
        binding?.apply {
            // Description section
            if (!header.fullDescription.isNullOrBlank()) {
                descriptionText.text = header.fullDescription
                descriptionText.isVisible = true
                noDescriptionText.isVisible = false
            } else if (!header.shortDescription.isNullOrBlank()) {
                descriptionText.text = header.shortDescription
                descriptionText.isVisible = true
                noDescriptionText.isVisible = false
            } else {
                descriptionText.isVisible = false
                noDescriptionText.isVisible = true
            }

            // Links section
            if (header.links.isNotEmpty()) {
                linksContainer.removeAllViews()
                header.links.forEach { link ->
                    val linkView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_channel_link, linksContainer, false)
                    linkView.findViewById<TextView>(R.id.linkName).text = link.name
                    linkView.findViewById<TextView>(R.id.linkUrl).text = link.url
                    linkView.setOnClickListener {
                        openUrl(link.url)
                    }
                    linksContainer.addView(linkView)
                }
                linksSectionTitle.isVisible = true
                linksContainer.isVisible = true
                linksDivider.isVisible = true
            } else {
                linksSectionTitle.isVisible = false
                linksContainer.isVisible = false
                linksDivider.isVisible = false
            }

            // More info section
            var hasAnyInfo = false

            // Subscriber count
            if (header.subscriberCount != null && header.subscriberCount > 0) {
                subscribersRow.isVisible = true
                subscribersLabel.text = formatSubscriberCount(header.subscriberCount)
                hasAnyInfo = true
            } else {
                subscribersRow.isVisible = false
            }

            // Total views
            if (header.totalViews != null && header.totalViews > 0) {
                viewsRow.isVisible = true
                viewsLabel.text = getString(R.string.channel_total_views, formatNumber(header.totalViews))
                hasAnyInfo = true
            } else {
                viewsRow.isVisible = false
            }

            // Location
            if (!header.location.isNullOrBlank()) {
                locationRow.isVisible = true
                locationLabel.text = header.location
                hasAnyInfo = true
            } else {
                locationRow.isVisible = false
            }

            // Joined date - use app's per-app locale for date formatting
            if (header.joinedDate != null) {
                joinedRow.isVisible = true
                val appLocale = LocaleManager.getCurrentLocale(requireContext())
                val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(appLocale)
                    .withZone(ZoneId.systemDefault())
                joinedLabel.text = getString(R.string.channel_joined_date, dateFormatter.format(header.joinedDate))
                hasAnyInfo = true
            } else {
                joinedRow.isVisible = false
            }

            // Verified badge
            if (header.isVerified) {
                verifiedRow.isVisible = true
                hasAnyInfo = true
            } else {
                verifiedRow.isVisible = false
            }

            moreInfoSectionTitle.isVisible = hasAnyInfo
            statsContainer.isVisible = hasAnyInfo
        }
    }

    private fun formatSubscriberCount(count: Long): String {
        return getString(R.string.channel_subscribers_format, formatNumber(count))
    }

    private fun formatNumber(number: Long): String {
        val appLocale = LocaleManager.getCurrentLocale(requireContext())
        return NumberFormat.getNumberInstance(appLocale).format(number)
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            // URL couldn't be opened
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
