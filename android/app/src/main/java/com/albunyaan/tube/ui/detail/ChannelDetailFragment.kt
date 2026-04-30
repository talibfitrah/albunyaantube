package com.albunyaan.tube.ui.detail

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import coil.load
import coil.request.CachePolicy
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscribeResult
import com.albunyaan.tube.data.subscriptions.SubscriptionLimitGuard
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import com.albunyaan.tube.databinding.FragmentChannelDetailBinding
import com.albunyaan.tube.data.report.ReportTargetType
import com.albunyaan.tube.share.ShareLinks
import com.albunyaan.tube.share.ShareMetadataPublisher
import com.albunyaan.tube.ui.report.ContentReportBottomSheet
import com.albunyaan.tube.ui.detail.tabs.ChannelAboutTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelLiveTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelPlaylistsTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelShortsTabFragment
import com.albunyaan.tube.ui.detail.tabs.ChannelVideosTabFragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import com.albunyaan.tube.locale.LocaleManager
import dagger.hilt.android.lifecycle.withCreationCallback
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @Inject
    lateinit var subscriptions: SubscriptionRepository

    @Inject
    lateinit var limitGuard: SubscriptionLimitGuard

    private var binding: FragmentChannelDetailBinding? = null
    private var latestHeader: ChannelHeader? = null
    private var isSubscribedNow: Boolean = false

    private val channelId: String by lazy { arguments?.getString(ARG_CHANNEL_ID).orEmpty() }
    private val channelName: String? by lazy { arguments?.getString(ARG_CHANNEL_NAME) }
    private val initialChannelAvatarUrl: String? by lazy {
        arguments?.getString(ARG_CHANNEL_AVATAR_URL)?.takeIf { it.isNotBlank() }
    }
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
    private var currentHeader: ChannelHeader? = null
    private val searchHandler = Handler(Looper.getMainLooper())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelDetailBinding.bind(view)

        setupToolbar()
        setupTabs()
        setupSearch()
        observeHeaderState()
        observeSubscriptionState()

        // Restore selected tab
        savedInstanceState?.getInt(STATE_SELECTED_TAB)?.let { position ->
            binding?.viewPager?.setCurrentItem(position, false)
        }
    }

    private fun setupSearch() {
        val editText = binding?.searchEditText ?: return
        val clearBtn = binding?.searchClearButton ?: return

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                clearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    viewModel.setSearchQuery(query)
                }, SEARCH_DEBOUNCE_MS)
            }
        })

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchHandler.removeCallbacksAndMessages(null)
                viewModel.setSearchQuery(editText.text?.toString() ?: "")
                true
            } else false
        }

        clearBtn.setOnClickListener {
            searchHandler.removeCallbacksAndMessages(null)
            editText.text?.clear()
            viewModel.setSearchQuery("")
        }
    }

    private fun setupToolbar() {
        binding?.apply {
            toolbar.navigationIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            toolbar.title = channelName ?: channelId
            toolbar.inflateMenu(R.menu.menu_detail_kebab)
            toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_share -> {
                        shareChannel()
                        true
                    }
                    R.id.action_report -> {
                        openReportSheet()
                        true
                    }
                    else -> false
                }
            }

            val listener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
                val collapsed = appBarLayout.totalScrollRange + verticalOffset <= 0
                // Use colorOnPrimary for expanded state (white in both light/dark themes) for visibility over banner
                val expandedColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnPrimary)
                val collapsedColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
                val toolbarColor = if (collapsed) collapsedColor else expandedColor
                toolbar.navigationIcon?.mutate()?.setTint(toolbarColor)
                toolbar.setTitleTextColor(toolbarColor)
                tintToolbarActions(toolbarColor)
            }
            appBarLayout.addOnOffsetChangedListener(listener)
            appBarOffsetListener = listener
            tintToolbarActions(MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnPrimary))

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

    private fun observeSubscriptionState() {
        subscriptions.isChannelSubscribed(channelId)
            .distinctUntilChanged()
            .onEach { subscribed ->
                isSubscribedNow = subscribed
                binding?.subscribeButton?.apply {
                    setText(if (subscribed) R.string.channel_unsubscribe else R.string.channel_subscribe)
                    isSelected = subscribed
                    setOnClickListener { toggleSubscription() }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun toggleSubscription() {
        val header = latestHeader ?: return
        // F11: guard against a malformed channel id slipping through. YouTube
        // channel ids are opaque alphanumeric + `_-`. Refuse to build a URL
        // if the extractor handed us something with a slash / query / space
        // that would break the later ChannelInfo.getInfo(url) lookup.
        if (!CHANNEL_ID_REGEX.matches(header.id)) {
            Log.w(TAG, "Refusing subscribe: malformed channelId='${header.id}'")
            return
        }
        // F-CR7 (CodeRabbit): capture the toggle direction at click time
        // so a fast double-tap doesn't observe a stale isSubscribedNow that
        // an in-flight emission has not flipped yet, and disable the button
        // for the duration of the IO call so a second tap can't queue a
        // second mutation. Wrap the IO in try/catch so a transient Room
        // failure surfaces in logs instead of silently leaving the UI in a
        // wrong state.
        val shouldUnsubscribe = isSubscribedNow
        binding?.subscribeButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (shouldUnsubscribe) {
                        subscriptions.unsubscribe(header.id)
                        SubscribeResult.Success
                    } else {
                        // Route subscribe through the guard so the 30-channel
                        // cap (§4.2) is enforced on every entry. Playlists do
                        // NOT go through the guard — they are unlimited.
                        limitGuard.trySubscribe(
                            SubscribedChannel(
                                channelId = header.id,
                                channelUrl = "https://www.youtube.com/channel/${header.id}",
                                name = header.title,
                                avatarUrl = header.avatarUrl,
                            )
                        )
                    }
                }
                if (result is SubscribeResult.LimitReached) {
                    binding?.root?.let { rootView ->
                        Snackbar.make(
                            rootView,
                            R.string.me_subscription_cap_reached,
                            Snackbar.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to toggle subscription for ${header.id}", t)
            } finally {
                binding?.subscribeButton?.isEnabled = true
            }
        }
    }

    private fun bindHeader(header: ChannelHeader) {
        currentHeader = header
        latestHeader = header
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
            loadChannelAvatar(header.avatarUrl, initialChannelAvatarUrl)

            // Channel name
            channelNameText.text = header.title

            // Verified badge (localized via content description)
            verifiedBadge.isVisible = header.isVerified

            // Subscriber count - use app's per-app locale for number formatting
            if (header.subscriberCount != null && header.subscriberCount > 0) {
                val appLocale = LocaleManager.getCurrentLocale(requireContext())
                val formattedCount = NumberFormat.getNumberInstance(appLocale).format(header.subscriberCount)
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

    private fun shareChannel() {
        if (channelId.isBlank()) return

        val header = currentHeader
        val title = header?.title?.takeIf { it.isNotBlank() }
            ?: channelName?.takeIf { it.isNotBlank() }
            ?: channelId
        val imageUrl = header?.avatarUrl ?: header?.bannerUrl
        val description = header?.summaryLine ?: header?.shortDescription ?: header?.fullDescription
        val shareUrl = ShareLinks.channel(
            channelId = channelId,
            title = title,
            imageUrl = imageUrl,
            description = description
        )
        viewLifecycleOwner.lifecycleScope.launch {
            ShareMetadataPublisher.publish("channel", channelId, title, imageUrl, description)
            if (!isAdded) return@launch

            val shareMessage = buildString {
                append(title)
                append("\n\n")
                append(getString(R.string.share_channel_in_app))
                append("\n")
                append(shareUrl)
                append("\n\n")
                append(getString(R.string.share_app_promo))
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TITLE, title)
                putExtra(Intent.EXTRA_TEXT, shareMessage)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_channel_chooser)))
        }
    }

    private fun openReportSheet() {
        if (channelId.isBlank()) return
        ContentReportBottomSheet.newInstance(ReportTargetType.CHANNEL, channelId)
            .show(childFragmentManager, ContentReportBottomSheet.TAG)
    }

    private fun FragmentChannelDetailBinding.loadChannelAvatar(
        primaryUrl: String?,
        fallbackUrl: String?,
    ) {
        val urls = listOfNotNull(
            primaryUrl?.takeIf { it.isNotBlank() },
            fallbackUrl?.takeIf { it.isNotBlank() && it != primaryUrl },
        )
        if (urls.isEmpty()) {
            channelAvatar.setImageResource(R.drawable.thumbnail_placeholder)
            return
        }
        channelAvatar.load(urls.first()) {
            placeholder(R.drawable.thumbnail_placeholder)
            error(R.drawable.thumbnail_placeholder)
            crossfade(true)
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
            networkCachePolicy(CachePolicy.ENABLED)
            listener(
                onError = { _, _ ->
                    val fallback = urls.getOrNull(1) ?: return@listener
                    channelAvatar.load(fallback) {
                        placeholder(R.drawable.thumbnail_placeholder)
                        error(R.drawable.thumbnail_placeholder)
                        crossfade(true)
                        memoryCachePolicy(CachePolicy.ENABLED)
                        diskCachePolicy(CachePolicy.ENABLED)
                        networkCachePolicy(CachePolicy.ENABLED)
                    }
                }
            )
        }
    }

    private fun tintToolbarActions(color: Int) {
        binding?.toolbar?.overflowIcon?.mutate()?.setTint(color)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding?.viewPager?.currentItem?.let { position ->
            outState.putInt(STATE_SELECTED_TAB, position)
        }
    }

    override fun onDestroyView() {
        searchHandler.removeCallbacksAndMessages(null)
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        pageChangeCallback?.let { binding?.viewPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        binding?.appBarLayout?.removeOnOffsetChangedListener(appBarOffsetListener)
        appBarOffsetListener = null
        currentHeader = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ChannelDetailFragment"
        private val CHANNEL_ID_REGEX = Regex("^[A-Za-z0-9_-]{3,64}$")
        const val ARG_CHANNEL_ID = "channelId"
        const val ARG_CHANNEL_NAME = "channelName"
        const val ARG_CHANNEL_AVATAR_URL = "channelAvatarUrl"
        const val ARG_EXCLUDED = "excluded"
        private const val STATE_SELECTED_TAB = "selectedTab"
        private const val SEARCH_DEBOUNCE_MS = 300L
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
