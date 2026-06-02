package com.albunyaan.tube.ui.me

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AuthState
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.me.WeekContent
import com.albunyaan.tube.data.me.work.RefreshScheduler
import com.albunyaan.tube.databinding.FragmentMeBinding
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import com.albunyaan.tube.ui.settings.NavHostIds
import com.albunyaan.tube.util.DeviceConfig
import com.albunyaan.tube.util.showIcons
import com.albunyaan.tube.util.tintIcons
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.albunyaan.tube.preferences.SettingsPreferences
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MeFragment : Fragment(R.layout.fragment_me) {

    @Inject lateinit var refreshScheduler: RefreshScheduler

    // T10: injected so the long-press snackbar action can call removeFavorite
    // directly. The MeViewModel observes the same repository, so a removal
    // here flows back through state.favorites and the row updates without
    // an extra round-trip.
    @Inject lateinit var favoritesRepository: FavoritesRepository

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var prefetchService: com.albunyaan.tube.player.StreamPrefetchService
    @Inject lateinit var playbackFeatureFlags: com.albunyaan.tube.player.PlaybackFeatureFlags

    // B13: created lazily so requireContext() is safe at first use (onResume or later).
    private val settingsPreferences: SettingsPreferences by lazy { SettingsPreferences(requireContext()) }

    private val viewModel: MeViewModel by viewModels()
    private var binding: FragmentMeBinding? = null

    private lateinit var chipsAdapter: MeChipsAdapter
    private lateinit var favoritesAdapter: MeFavoritesAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var prefetchController: com.albunyaan.tube.player.PredictivePrefetchController? = null

    // ANDROID-PERSONAL-03 / T6: per-week sub-adapter cache keyed by
    // weekIndex. Looking up here lets us call submit() on existing weeks
    // when their underlying cache changes (instead of recreating). Order
    // mirrors viewModel.weeks; weeks are only ever appended.
    private val weekAdapters = mutableMapOf<Int, MeWeekSectionAdapter>()

    // ANDROID-PERSONAL-02 round 4 (UX feedback): the video card now shows
    // each subscribed channel's avatar. The chip list already carries
    // imageUrl + label per channel, so we mirror it into a lookup map and
    // hand it to the videos paging adapter through getChannelAvatar /
    // onChannelClick lambdas. Refreshed every render(Content); reads on
    // bind happen on the main thread alongside writes, so a plain Map is
    // safe (no concurrent access).
    private var channelMap: Map<String, ChipItem.Channel> = emptyMap()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentMeBinding.bind(view).also { binding = it }

        chipsAdapter = MeChipsAdapter(
            onClick = ::onChipClicked,
        )
        favoritesAdapter = MeFavoritesAdapter(
            onClick = ::playFavorite,
            onLongPress = ::confirmRemoveFavorite,
            onSeeAll = ::navigateToFavoritesScreen,
        )

        // ANDROID-PERSONAL-03 / T6: dynamic ConcatAdapter. Initially holds
        // chips + favorites; per-week sub-adapters are appended as the
        // viewModel.weeks flow emits. Isolation is disabled so the
        // spanSizeLookup can compare raw inner view types — every
        // adapter participating in this ConcatAdapter must use a unique
        // view type constant (chips=101, favorites=401-403,
        // weeks=501-503; see each adapter's companion object).
        concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                .setIsolateViewTypes(false)
                .build(),
            chipsAdapter.rowAdapter,
            // T10: favorites row sits between chips and per-week content.
            favoritesAdapter.sectionAdapter,
        )

        val isTablet = DeviceConfig.isTablet(requireContext()) || DeviceConfig.isTV(requireContext())
        val isLarge = resources.configuration.smallestScreenWidthDp >= 720
        b.meRecycler.layoutManager = if (isTablet) {
            val spanCount = if (isLarge) 3 else 2
            GridLayoutManager(requireContext(), spanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        // ConcatAdapter is configured with isolation OFF
                        // so getItemViewType returns the raw inner view
                        // type. Only video tiles span 1 column; chips,
                        // favorites, week headers, and shorts rows span
                        // full width.
                        val viewType = concatAdapter.getItemViewType(position)
                        return if (viewType == MeWeekSectionAdapter.WEEK_VIDEO_VIEW_TYPE) 1
                        else spanCount
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

        // ANDROID-PERSONAL-03 / T6: weeks collector. Builds / updates the
        // per-week sub-adapters appended to the outer ConcatAdapter.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.weeks.collect { renderWeeks(it) }
            }
        }

        // ANDROID-PERSONAL-03 / T6: load-more sentinel. When the user
        // scrolls within PREFETCH_DISTANCE of the bottom, ask the
        // ViewModel for the next non-empty week. The ViewModel's
        // [isLoadingMoreWeeks] / [reachedEnd] flags coalesce this so we
        // don't fire mid-load or after we've already exhausted history.
        b.meRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = rv.layoutManager ?: return
                val total = layoutManager.itemCount
                val lastVisible = when (layoutManager) {
                    is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    else -> RecyclerView.NO_POSITION
                }
                if (lastVisible == RecyclerView.NO_POSITION) return
                if (total - lastVisible <= PREFETCH_DISTANCE) {
                    viewModel.loadNextWeek()
                }
            }
        })

        setupKebab(b)
    }

    // K2: wire the kebab menu on the fragment-owned MaterialToolbar.
    // app:menu="@menu/menu_me_kebab" is declared in XML so inflation is free;
    // we only need to set visibility of action_suggest_content and handle clicks.
    private fun setupKebab(b: FragmentMeBinding) {
        val toolbar = b.meToolbar ?: return
        toolbar.menu.showIcons()
        // ic_nav_me / ic_add / ic_logout have white fillColor (designed for nav-bar
        // itemIconTint). The overflow popup never applies itemIconTint, so tint them
        // explicitly to colorControlNormal to match every other kebab in the app.
        toolbar.menu.tintIcons(
            MaterialColors.getColor(toolbar, android.R.attr.colorControlNormal, android.graphics.Color.BLACK)
        )
        val role = viewModel.snapshotRole()
        val isModerator = role.equals("moderator", ignoreCase = true) ||
            role.equals("admin", ignoreCase = true)
        toolbar.menu.findItem(R.id.action_my_submissions)?.isVisible = isModerator
        toolbar.menu.findItem(R.id.action_suggest_content)?.isVisible = isModerator

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_profile -> {
                    if (findNavController().currentDestination?.id == R.id.meFragment) {
                        findNavController().navigate(R.id.action_me_to_profile)
                    }
                    true
                }
                R.id.action_my_submissions -> {
                    if (findNavController().currentDestination?.id == R.id.meFragment) {
                        findNavController().navigate(R.id.action_me_to_mySubmissions)
                    }
                    true
                }
                R.id.action_suggest_content -> {
                    if (findNavController().currentDestination?.id == R.id.meFragment) {
                        findNavController().navigate(R.id.action_me_to_suggestContent)
                    }
                    true
                }
                R.id.action_import_youtube -> {
                    // B12: visible to all signed-in users; no role gate.
                    if (findNavController().currentDestination?.id == R.id.meFragment) {
                        findNavController().navigate(R.id.action_me_to_importFromYouTube)
                    }
                    true
                }
                R.id.action_sign_out -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_account_sign_out_confirm_title)
                        .setMessage(R.string.settings_account_sign_out_confirm_body)
                        .setPositiveButton(R.string.settings_account_sign_out_confirm_action) { _, _ ->
                            signOutAndNavigateToSignIn()
                        }
                        .setNegativeButton(R.string.settings_account_sign_out_cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }
    }

    // Full sign-out: Firebase + local state. MeFragment lives in the inner nav
    // (main_tabs_nav), so we grab the outer NavController to reach signInFragment.
    private fun signOutAndNavigateToSignIn() {
        viewLifecycleOwner.lifecycleScope.launch {
            authRepository.signOut()
            // isActive guards the window between signOut() completing and the
            // AuthStateListener firing, which can cancel this scope if the fragment
            // is torn down concurrently.
            if (!isActive) return@launch
            val act = activity ?: return@launch
            if (act.isFinishing || act.isDestroyed) return@launch
            val outerNav = try {
                Navigation.findNavController(act, NavHostIds.ROOT)
            } catch (e: IllegalStateException) {
                android.util.Log.w("MeFragment", "outer NavController unavailable during sign-out", e)
                return@launch
            }
            if (outerNav.currentDestination?.id == R.id.signInFragment) return@launch
            try {
                outerNav.navigate(
                    R.id.signInFragment,
                    null,
                    navOptions {
                        popUpTo(R.id.app_nav_graph) { inclusive = true }
                    },
                )
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("MeFragment", "destination changed before navigate() — already navigating", e)
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
        // B13: one-time import offer for signed-in users.
        viewLifecycleOwner.lifecycleScope.launch {
            maybeShowImportOffer()
        }
    }

    /**
     * B13: Shows a one-time "Import from YouTube" offer dialog the first time a
     * signed-in user lands on the Me tab. Sets [SettingsPreferences.IMPORT_OFFER_SHOWN_KEY]
     * true on any choice (Import or Not now) so it never appears again.
     */
    private suspend fun maybeShowImportOffer() {
        val isSignedIn = authRepository.authState.value is AuthState.SignedIn
        if (!isSignedIn) return
        if (!settingsPreferences.shouldShowImportOffer()) return
        // Mark shown immediately to prevent a second launch before the dialog closes.
        settingsPreferences.setImportOfferShown()
        if (!isAdded || activity == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_offer_title)
            .setMessage(R.string.import_offer_message)
            .setPositiveButton(R.string.import_offer_positive) { _, _ ->
                if (findNavController().currentDestination?.id == R.id.meFragment) {
                    findNavController().navigate(R.id.action_me_to_importFromYouTube)
                }
            }
            .setNegativeButton(R.string.import_offer_negative, null)
            .show()
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

                // ANDROID-PERSONAL-02 round 4: refresh the avatar lookup
                // from the chip list before submitting chips. The per-week
                // adapter calls getChannelAvatar(channelId) at bind time,
                // so the map needs to be current by the time any video row
                // binds. Same render() runs on the main thread that does
                // the bind, so a plain Map (no concurrent reads) is fine.
                channelMap = state.chips
                    .filterIsInstance<ChipItem.Channel>()
                    .associateBy { it.id }

                chipsAdapter.selectedId = state.filterChannelId
                chipsAdapter.submit(state.chips)
                favoritesAdapter.submit(state.favorites)
                // ANDROID-PERSONAL-03 / T6: shorts + videos are now driven
                // by per-week sub-adapters fed by the [renderWeeks]
                // collector. state.shorts / state.videos are unused in the
                // new architecture but kept on MeFeedState.Content for
                // legacy parity.
            }
            is MeFeedState.Error -> {
                Snackbar.make(b.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * ANDROID-PERSONAL-03 / T6: synchronise the per-week sub-adapters
     * against [viewModel.weeks]. New weeks are appended; existing weeks
     * are submit()'d in case their underlying cache changed (e.g. a deep
     * page just landed).
     *
     * Bug 1/Bug 2: with the live-derived weeks flow, the incoming list
     * can also SHRINK (filter switch leaves some weeks empty for the
     * scoped channel; an unsubscribe drops a channel's content out of a
     * week entirely). Any cached week adapter whose weekIndex is not in
     * the incoming list must be removed from the outer ConcatAdapter and
     * its entry dropped from [weekAdapters], otherwise stale rows linger
     * on screen.
     */
    private fun renderWeeks(weeks: List<WeekContent>) {
        val incomingIndices = weeks.asSequence().map { it.weekIndex }.toSet()
        // Step 1: tear down adapters that are no longer in the incoming
        // list. Iterate over a snapshot of the current keys so we can
        // mutate the map inside the loop.
        weekAdapters.keys.toList().forEach { idx ->
            if (idx !in incomingIndices) {
                weekAdapters.remove(idx)?.let { adapter ->
                    concatAdapter.removeAdapter(adapter.sectionAdapter)
                }
            }
        }
        // Step 2: add new adapters / submit fresh content to existing ones.
        for (week in weeks) {
            val existing = weekAdapters[week.weekIndex]
            if (existing != null) {
                existing.submit(week)
            } else {
                val adapter = MeWeekSectionAdapter(
                    initial = week,
                    onClick = ::playVideo,
                    getChannelAvatar = { channelId -> channelMap[channelId]?.imageUrl },
                    onChannelClick = ::navigateToChannel,
                    onItemBind = ::warmPrefetchFor,
                )
                weekAdapters[week.weekIndex] = adapter
                concatAdapter.addAdapter(adapter.sectionAdapter)
            }
        }

        // ANDROID-PERSONAL-03 round 8 [field-bug, filter-too-thin]: when the
        // user filters by a channel whose recent activity is one-shape-only
        // (e.g. Mufti Menk's last 12 days are all shorts; his videos start at
        // week 5), the first rendered week shows just a shorts row + a videos
        // header with no body. The result fits the viewport, the scroll
        // listener never fires, and the user thinks "no videos". Same gotcha
        // CLAUDE.md flags for Pagination on Large Screens — scroll-listener
        // alone is insufficient when content fits on screen.
        // Post a layout-time check: if the recycler can't scroll DOWN,
        // auto-fire [loadNextWeek] until content overflows or [reachedEnd]
        // flips. The ViewModel's loadJob debounce keeps this from looping
        // uselessly during a single in-flight load.
        binding?.meRecycler?.post {
            val rv = binding?.meRecycler ?: return@post
            if (!rv.canScrollVertically(1) &&
                !viewModel.reachedEnd.value &&
                !viewModel.isLoadingMoreWeeks.value
            ) {
                viewModel.loadNextWeek()
            }
        }
    }

    /**
     * Trigger a background prefetch for [videoId] when a feed cell binds.
     * Cheap call — StreamPrefetchService dedupes already-running and already-
     * resolved fetches and uses BACKGROUND_REFRESH priority so it never
     * starves channel/Me UI loads. Gated by the predictive-prefetch feature
     * flag so kill-switching it requires only a flag flip.
     */
    private fun warmPrefetchFor(videoId: String) {
        if (!playbackFeatureFlags.isPredictivePrefetchEnabled) return
        prefetchService.triggerPrefetch(videoId, viewLifecycleOwner.lifecycleScope)
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
        // ANDROID-PERSONAL-03 round 8 [field-bug]: pass the same metadata
        // bundle that HomeFragment passes so PlayerFragment can render the
        // title, channel, thumbnail, etc. immediately while NewPipe
        // extraction runs in the background. Without these args the player
        // shows placeholder text until extraction completes (or forever
        // if extraction fails). MeFeedVideo doesn't carry a description
        // (ATOM doesn't expose it; NewPipe deep-paging returns it but we
        // don't currently capture it) so we pass empty string.
        // Round 8 fixes:
        //   - PlayerFragment.kt:374 reads `getInt("durationSeconds")`. We
        //     were calling `putLong` — Bundle silently returns 0 on a
        //     type mismatch. Use `putInt` and convert.
        //   - "description" was passed as "" — non-null, so the player's
        //     `description ?: getString(R.string.player_no_description)`
        //     showed an empty block. Don't pass at all → player gets null
        //     → falls back to placeholder until NewPipe extraction lands.
        //   - "thumbnailUrl" similarly: don't pass empty strings, let null
        //     drive the placeholder.
        if (findNavController().currentDestination?.id == R.id.meFragment) {
            if (video.isShort) {
                val args = Bundle().apply {
                    putString("initialShortId", video.videoId)
                    putString("channelId", video.channelId)
                    putString("initialShortTitle", video.title)
                    putString("initialChannelName", video.channelName)
                    video.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                        putString("initialThumbnailUrl", it)
                    }
                    channelMap[video.channelId]?.imageUrl?.takeIf { it.isNotBlank() }?.let {
                        putString("initialChannelAvatarUrl", it)
                    }
                    putInt("initialDurationSeconds", (video.durationSeconds ?: 0L).toInt())
                }
                findNavController().navigate(R.id.action_global_shortsPlayerFragment, args)
            } else {
                val args = Bundle().apply {
                    putString("videoId", video.videoId)
                    putString("title", video.title)
                    putString("channelName", video.channelName)
                    video.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { putString("thumbnailUrl", it) }
                    putInt("durationSeconds", (video.durationSeconds ?: 0L).toInt())
                    putLong("viewCount", video.viewCount ?: -1L)
                }
                findNavController().navigate(R.id.playerFragment, args)
            }
        }
    }

    /** T10: tile click → open the player with the favorite's videoId. */
    private fun playFavorite(item: FavoriteVideo) {
        if (item.videoId.isBlank()) return
        // Same fixes as playVideo: durationSeconds as Int, omit empty
        // thumbnailUrl/description so the player falls back to placeholders
        // and lets NewPipe extraction fill the real values.
        val args = Bundle().apply {
            putString("videoId", item.videoId)
            putString("title", item.title)
            putString("channelName", item.channelName)
            item.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { putString("thumbnailUrl", it) }
            putInt("durationSeconds", item.durationSeconds)
            putLong("viewCount", -1L)
        }
        if (findNavController().currentDestination?.id == R.id.meFragment) {
            findNavController().navigate(R.id.playerFragment, args)
        }
    }

    /**
     * T10: long-press on a tile → show a snackbar with a "Remove" action.
     * The remove call is fired from viewLifecycleOwner.lifecycleScope so it
     * is cancelled if the view is torn down before the user taps the action.
     * The repository emission flows back through MeViewModel and the row
     * updates without an explicit refresh.
     */
    private fun confirmRemoveFavorite(item: FavoriteVideo) {
        val b = binding ?: return
        Snackbar.make(b.root, item.title, Snackbar.LENGTH_LONG)
            .setAction(R.string.me_remove_from_favorites) {
                viewLifecycleOwner.lifecycleScope.launch {
                    favoritesRepository.removeFavorite(item.videoId)
                }
            }
            .show()
    }

    /**
     * T10: "See all" tile → open the existing FavoritesFragment. There is no
     * meFragment-to-favoritesFragment action defined in main_tabs_nav, so
     * we navigate by destination id (matches how playerFragment is opened
     * from the same fragment).
     */
    private fun navigateToFavoritesScreen() {
        if (findNavController().currentDestination?.id == R.id.meFragment) {
            findNavController().navigate(R.id.favoritesFragment)
        }
    }

    /**
     * ANDROID-PERSONAL-02 round 4 (UX feedback): tapping the channel
     * avatar on a video card opens that channel's detail page. Uses the
     * global action `action_global_channelDetailFragment` (defined in
     * main_tabs_nav.xml) and matches the args bundle that
     * ChannelsFragmentNew passes (channelId / channelName / excluded=false).
     *
     * The lookup may legitimately fail — chip list refreshed mid-paging,
     * channel removed from subscriptions while a paging row was in
     * flight, etc. In that case we just no-op rather than crash.
     */
    private fun navigateToChannel(channelId: String) {
        if (channelId.isBlank()) return
        val chip = channelMap[channelId] ?: return
        if (findNavController().currentDestination?.id != R.id.meFragment) return
        val args = bundleOf(
            ChannelDetailFragment.ARG_CHANNEL_ID to channelId,
            ChannelDetailFragment.ARG_CHANNEL_NAME to chip.label,
            ChannelDetailFragment.ARG_CHANNEL_AVATAR_URL to chip.imageUrl,
            ChannelDetailFragment.ARG_EXCLUDED to false,
        )
        findNavController().navigate(R.id.action_global_channelDetailFragment, args)
    }

    override fun onDestroyView() {
        binding?.meRecycler?.adapter = null
        weekAdapters.clear()
        binding = null
        super.onDestroyView()
    }

    companion object {
        /**
         * ANDROID-PERSONAL-03 / T6: number of items from the bottom that
         * triggers [MeViewModel.loadNextWeek]. Roughly one screenful of
         * tiles on a phone (~10 items at 1 column). Tablet/TV grids see
         * the same 10 because layout-manager itemCount is per-cell.
         */
        private const val PREFETCH_DISTANCE = 10
    }
}
