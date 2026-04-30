package com.albunyaan.tube.ui.detail.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.databinding.ItemChannelLiveBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.util.ImageLoading.loadYouTubeThumbnail
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for live streams in the Live tab.
 * Shows LIVE or UPCOMING badge on thumbnails.
 */
class ChannelLiveAdapter(
    private val onStreamClick: (ChannelLiveStream) -> Unit
) : ListAdapter<ChannelLiveStream, ChannelLiveAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelLiveBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onStreamClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemChannelLiveBinding,
        private val onStreamClick: (ChannelLiveStream) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context get() = binding.root.context

        fun bind(stream: ChannelLiveStream) {
            binding.streamTitle.text = stream.title

            // Show LIVE or UPCOMING badge (and hide duration for live/upcoming)
            when {
                stream.isLiveNow -> {
                    binding.liveBadge.isVisible = true
                    binding.liveBadge.setText(R.string.live_badge)
                    binding.liveBadge.setBackgroundResource(R.drawable.bg_live_badge)
                    binding.upcomingBadge.isVisible = false
                    binding.streamDuration.isVisible = false
                }
                stream.isUpcoming -> {
                    binding.liveBadge.isVisible = false
                    binding.upcomingBadge.isVisible = true
                    binding.upcomingBadge.setText(R.string.upcoming_badge)
                    binding.streamDuration.isVisible = false
                }
                else -> {
                    // Past/recorded stream - show duration overlay if available
                    binding.liveBadge.isVisible = false
                    binding.upcomingBadge.isVisible = false
                    val duration = stream.durationSeconds
                    if (duration != null && duration > 0) {
                        binding.streamDuration.isVisible = true
                        binding.streamDuration.text = formatDuration(duration)
                    } else {
                        binding.streamDuration.isVisible = false
                    }
                }
            }

            // Build metadata line based on stream type
            binding.streamMeta.text = buildMetaLine(stream)

            // Load thumbnail with automatic fallback
            binding.streamThumbnail.loadYouTubeThumbnail(
                primaryUrl = stream.thumbnailUrl,
                videoId = stream.id,
                isShort = false,
                placeholder = R.drawable.thumbnail_placeholder
            )

            binding.root.setOnClickListener {
                onStreamClick(stream)
            }
        }

        /**
         * Builds the metadata line based on stream type:
         * - Live: "1.5K watching" (localized plural)
         * - Past: "100K views • Streamed 2 weeks ago" (localized)
         * - Upcoming: formatted scheduled start time if available
         */
        private fun buildMetaLine(stream: ChannelLiveStream): String {
            val appLocale = LocaleManager.getCurrentLocale(context)
            val formattedViewCount = stream.viewCount?.let {
                NumberFormat.getNumberInstance(appLocale).format(it)
            }

            return when {
                stream.isLiveNow -> {
                    // "X watching" - use localized plural
                    stream.viewCount?.let { viewCount ->
                        context.resources.getQuantityString(
                            R.plurals.live_watching_count,
                            safeQuantityForPlural(viewCount),
                            formattedViewCount
                        )
                    } ?: ""
                }
                stream.isUpcoming -> {
                    // For upcoming, show formatted scheduled time if available
                    stream.scheduledStartTime?.let { instant ->
                        // Use toEpochMilli() instead of Date.from() for API < 26 compatibility
                        formatScheduledTime(Date(instant.toEpochMilli()), appLocale)
                    } ?: ""
                }
                else -> {
                    // Past/recorded stream: "X views • Streamed Y ago"
                    val viewsText = stream.viewCount?.let { viewCount ->
                        context.resources.getQuantityString(
                            R.plurals.video_views,
                            safeQuantityForPlural(viewCount),
                            formattedViewCount
                        )
                    }
                    val timeText = stream.publishedTime

                    when {
                        viewsText != null && !timeText.isNullOrBlank() ->
                            context.getString(R.string.live_past_meta, viewsText, timeText)
                        viewsText != null -> viewsText
                        !timeText.isNullOrBlank() -> timeText
                        else -> ""
                    }
                }
            }
        }

    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChannelLiveStream>() {
            override fun areItemsTheSame(oldItem: ChannelLiveStream, newItem: ChannelLiveStream): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelLiveStream, newItem: ChannelLiveStream): Boolean =
                oldItem == newItem
        }

        /**
         * Safely converts a Long count to Int for plural quantity selection.
         * Clamps to Int.MAX_VALUE to prevent overflow for very large counts (e.g., billions of views).
         * The actual formatted display uses the full Long value via NumberFormat.
         *
         * Note on plural category selection:
         * - Android's plural rules use mod-based calculations (e.g., Arabic uses mod 100)
         * - For clamped values (Int.MAX_VALUE = 2,147,483,647), the plural category depends
         *   on the language's rules applied to that specific number
         * - English: "other" (correct for any count > 1)
         * - Arabic: Int.MAX_VALUE mod 100 = 47, which falls into "many" (11-99)
         * - Dutch: "other" (correct for any count > 1)
         * - In practice, videos with billions of views are extremely rare, and the display
         *   text (formatted with NumberFormat) remains accurate regardless of plural category
         *
         * @param count The view/watch count as a Long
         * @return A safe Int value for use with getQuantityString()
         */
        @JvmStatic
        internal fun safeQuantityForPlural(count: Long): Int {
            return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Formats a scheduled start time in a localized, user-friendly format.
         * Uses medium date and short time format for the specified locale.
         *
         * Note: The time is formatted in the device's current timezone. This is intentional
         * as users expect to see times in their local timezone for upcoming streams.
         *
         * @param date The scheduled start time
         * @param locale The locale to use for formatting (should be the app's per-app locale)
         * @return A localized date/time string (e.g., "Dec 25, 2025, 3:00 PM" in en_US)
         */
        @JvmStatic
        internal fun formatScheduledTime(date: Date, locale: Locale = Locale.getDefault()): String {
            val dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                locale
            )
            return dateFormat.format(date)
        }

        /**
         * Formats duration in seconds to "H:MM:SS" or "M:SS" format.
         * Uses US locale for consistent numeric formatting (no locale-specific separators).
         *
         * @param seconds Duration in seconds
         * @return Formatted duration string
         */
        @JvmStatic
        internal fun formatDuration(seconds: Int): String {
            // Normalize negative values to 0
            val normalizedSeconds = if (seconds < 0) 0 else seconds
            val hours = normalizedSeconds / 3600
            val minutes = (normalizedSeconds % 3600) / 60
            val secs = normalizedSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, secs)
            }
        }
    }
}
