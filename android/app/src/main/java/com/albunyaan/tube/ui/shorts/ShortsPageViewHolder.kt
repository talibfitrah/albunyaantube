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

    fun bind(
        item: ShortsItem,
        isLiked: Boolean,
        isFollowed: Boolean,
        onLike: () -> Unit,
        onShare: () -> Unit,
        onSubscribe: () -> Unit,
        onChannelTap: () -> Unit,
        onTapVideo: () -> Unit
    ) {
        binding.shortTitle.text = item.title

        // Hide the entire channel+subscribe row when we have no channel info
        // (feed mode often lacks channelName until the item is hydrated). Title
        // and the video itself stay visible.
        val hasChannelInfo = item.channelName.isNotBlank()
        binding.shortChannelAvatar.visibility = if (hasChannelInfo) View.VISIBLE else View.GONE
        binding.shortChannelHandle.visibility = if (hasChannelInfo) View.VISIBLE else View.GONE
        binding.shortSubscribeBtn.visibility = if (hasChannelInfo) View.VISIBLE else View.GONE

        if (hasChannelInfo) {
            // Prefix with "@" to match common shorts UI; localised prefixing not
            // required since "@" is universal for social handles.
            binding.shortChannelHandle.text = "@" + item.channelName
        }

        binding.shortLikeBtn.setImageResource(
            if (isLiked) R.drawable.ic_shorts_like_filled else R.drawable.ic_shorts_like
        )
        binding.shortSubscribeBtn.setText(
            if (isFollowed) R.string.shorts_subscribed else R.string.shorts_subscribe
        )
        binding.shortSubscribeBtn.isSelected = isFollowed

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
        binding.shortSubscribeBtn.setOnClickListener { onSubscribe() }
        binding.shortChannelAvatar.setOnClickListener { onChannelTap() }
        binding.shortChannelHandle.setOnClickListener { onChannelTap() }
        binding.shortTapTarget.setOnClickListener { onTapVideo() }
    }
}
