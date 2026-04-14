package com.albunyaan.tube.ui.shorts

import android.view.View
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.shorts.ShortsItem
import com.albunyaan.tube.databinding.ItemShortsPageBinding

/**
 * Binds one [ShortsItem] to the views defined in `item_shorts_page.xml`.
 *
 * The ViewHolder exposes its [PlayerView] so [ShortsPlayerFragment] /
 * [PlayerBinder] can attach the shared ExoPlayer instance on page change.
 */
class ShortsPageViewHolder(
    private val binding: ItemShortsPageBinding
) : RecyclerView.ViewHolder(binding.root) {

    /** The PlayerView for this page; bound by [PlayerBinder.bind]. */
    val playerView: PlayerView get() = binding.shortPlayerView

    /** The scrubber timeline for this page; driven by the fragment. */
    val timeBar: androidx.media3.ui.DefaultTimeBar get() = binding.shortTimeBar

    fun bind(
        item: ShortsItem,
        isLiked: Boolean,
        hasMultipleAudioTracks: Boolean,
        onLike: () -> Unit,
        onShare: () -> Unit,
        onDownload: () -> Unit,
        onChannelTap: () -> Unit,
        onTapVideo: () -> Unit,
        onAudioTrackTap: () -> Unit
    ) {
        binding.shortTitle.text = item.title

        // Hide the channel row when we have no channel info (feed mode often
        // lacks channelName until the item is hydrated). Title and the video
        // itself stay visible. Subscribe UX lives on the channel detail
        // screen — the shorts overlay is read-only channel attribution.
        val hasChannelInfo = item.channelName.isNotBlank()
        binding.shortChannelAvatar.visibility = if (hasChannelInfo) View.VISIBLE else View.GONE
        binding.shortChannelHandle.visibility = if (hasChannelInfo) View.VISIBLE else View.GONE

        if (hasChannelInfo) {
            // Prefix with "@" to match common shorts UI. The string is pulled
            // from resources so RTL locales can inject a bidi control (LRM) to
            // keep the "@" on the visual left of an LTR handle inside an RTL
            // paragraph — see values-ar/strings.xml.
            val ctx = binding.shortChannelHandle.context
            binding.shortChannelHandle.text =
                ctx.getString(R.string.shorts_channel_handle, item.channelName)
        }

        binding.shortLikeBtn.setImageResource(
            if (isLiked) R.drawable.ic_shorts_like_filled else R.drawable.ic_shorts_like
        )

        // Load avatar via Coil with circle crop and a safe placeholder.
        val placeholder = R.drawable.home_channel_avatar_bg
        val avatarUrl = item.channelAvatarUrl
        if (!avatarUrl.isNullOrBlank()) {
            binding.shortChannelAvatar.load(avatarUrl) {
                placeholder(placeholder)
                error(placeholder)
                transformations(CircleCropTransformation())
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                networkCachePolicy(CachePolicy.ENABLED)
            }
        } else {
            binding.shortChannelAvatar.setImageResource(placeholder)
        }

        binding.shortLikeBtn.setOnClickListener { onLike() }
        binding.shortShareBtn.setOnClickListener { onShare() }
        binding.shortDownloadBtn.setOnClickListener { onDownload() }
        binding.shortChannelAvatar.setOnClickListener { onChannelTap() }
        binding.shortChannelHandle.setOnClickListener { onChannelTap() }
        binding.shortTapTarget.setOnClickListener { onTapVideo() }

        // Audio-language rail button: only shown when the current short
        // exposes ≥2 audio languages. Defaults to gone in XML; the adapter
        // flips it visible reactively as streams resolve.
        binding.shortAudioTrackBtn.visibility =
            if (hasMultipleAudioTracks) View.VISIBLE else View.GONE
        binding.shortAudioTrackBtn.setOnClickListener { onAudioTrackTap() }
    }

    /**
     * Update only the audio-language button visibility without rebinding the
     * whole page. Called when stream resolution completes after the initial
     * bind, so the button can appear mid-playback without flicker.
     */
    fun setAudioTrackButtonVisible(visible: Boolean) {
        binding.shortAudioTrackBtn.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Flash the centered play/pause overlay. When [isPlaying] is true the
     * indicator shows the pause glyph for [AUTO_HIDE_MS] and fades out —
     * giving quick visual feedback that a tap resumed playback. When false
     * the play glyph stays visible persistently (paused state should be
     * obvious).
     */
    fun flashPlayPauseIndicator(isPlaying: Boolean) {
        val view = binding.shortPlayPauseIndicator
        view.animate().cancel()
        view.setImageResource(
            if (isPlaying) R.drawable.ic_shorts_pause_indicator
            else R.drawable.ic_shorts_play_indicator
        )
        view.alpha = 1f
        view.visibility = View.VISIBLE
        if (isPlaying) {
            view.animate()
                .alpha(0f)
                .setStartDelay(AUTO_HIDE_MS)
                .setDuration(FADE_MS)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    /** Hide the overlay immediately — useful on page recycle. */
    fun clearPlayPauseIndicator() {
        val view = binding.shortPlayPauseIndicator
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
    }

    companion object {
        private const val AUTO_HIDE_MS = 600L
        private const val FADE_MS = 250L
    }
}
