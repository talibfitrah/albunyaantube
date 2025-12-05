package com.albunyaan.tube.ui.components

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import com.albunyaan.tube.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

/**
 * Helper class for binding the PlaylistHero component with blurred background.
 *
 * YouTube-style layout:
 * - Background: Blurred/softened version of thumbnail filling width
 * - Foreground: Centered 16:9 thumbnail card with correct aspect ratio
 * - Top/bottom gradient scrims for text/icon readability
 *
 * Usage:
 * ```
 * val helper = PlaylistHeroHelper(
 *     backgroundBlurred = binding.heroBackgroundBlurred,
 *     thumbnail = binding.heroThumbnail
 * )
 * helper.bind(thumbnailUrl)
 * ```
 */
private const val BLUR_RADIUS = 30f

class PlaylistHeroHelper(
    private val backgroundBlurred: ImageView,
    private val thumbnail: ShapeableImageView,
    private val thumbnailCard: MaterialCardView? = null
) {

    /**
     * Bind the hero component with the given thumbnail URL.
     * Loads both the blurred background and the sharp foreground thumbnail.
     */
    fun bind(thumbnailUrl: String?) {
        if (thumbnailUrl.isNullOrEmpty()) {
            // Use placeholder for both
            backgroundBlurred.setImageResource(R.drawable.thumbnail_placeholder)
            thumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            return
        }

        // Load background image with blur effect applied via RenderEffect (API 31+)
        // or scaled down for a softened effect on older devices
        backgroundBlurred.load(thumbnailUrl) {
            placeholder(R.drawable.thumbnail_placeholder)
            error(R.drawable.thumbnail_placeholder)
            crossfade(true)
            memoryCachePolicy(CachePolicy.ENABLED)
            // Use small size for faster loading and natural softening
            size(320, 180)
            listener(onSuccess = { _, _ ->
                applyBlurEffect(backgroundBlurred)
            })
        }

        // Load sharp foreground thumbnail
        thumbnail.load(thumbnailUrl) {
            placeholder(R.drawable.thumbnail_placeholder)
            error(R.drawable.thumbnail_placeholder)
            crossfade(true)
            memoryCachePolicy(CachePolicy.ENABLED)
        }
    }

    /**
     * Bind with a banner URL (higher resolution) for background and thumbnail URL for foreground.
     * Use this when you have separate banner and thumbnail URLs.
     */
    fun bind(bannerUrl: String?, thumbnailUrl: String?) {
        // Normalize inputs: treat empty strings as null for consistent fallback behavior
        val normalizedBanner = bannerUrl?.takeIf { it.isNotEmpty() }
        val normalizedThumbnail = thumbnailUrl?.takeIf { it.isNotEmpty() }
        val bgUrl = normalizedBanner ?: normalizedThumbnail
        val fgUrl = normalizedThumbnail ?: normalizedBanner

        if (bgUrl.isNullOrEmpty() && fgUrl.isNullOrEmpty()) {
            backgroundBlurred.setImageResource(R.drawable.thumbnail_placeholder)
            thumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            return
        }

        // Load blurred background
        if (!bgUrl.isNullOrEmpty()) {
            backgroundBlurred.load(bgUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
                memoryCachePolicy(CachePolicy.ENABLED)
                size(320, 180)
                listener(onSuccess = { _, _ ->
                    applyBlurEffect(backgroundBlurred)
                })
            }
        }

        // Load sharp foreground thumbnail
        if (!fgUrl.isNullOrEmpty()) {
            thumbnail.load(fgUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
                memoryCachePolicy(CachePolicy.ENABLED)
            }
        }
    }

    /**
     * Apply blur effect to the ImageView.
     * Uses RenderEffect on API 31+ for hardware-accelerated blur.
     * On older devices, the scaled-down image (320x180) provides natural softening.
     */
    private fun applyBlurEffect(imageView: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Use RenderEffect for hardware-accelerated blur on API 31+
            val blurEffect = RenderEffect.createBlurEffect(
                BLUR_RADIUS,
                BLUR_RADIUS,
                Shader.TileMode.CLAMP
            )
            imageView.setRenderEffect(blurEffect)
        }
        // On older devices (< API 31), the scaled-down image provides natural softening
    }

    /**
     * Set visibility of the thumbnail card.
     */
    fun setThumbnailCardVisible(visible: Boolean) {
        thumbnailCard?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        /**
         * Create a PlaylistHeroHelper from view references.
         * Expects views with standard IDs from view_playlist_hero.xml.
         * @throws IllegalStateException if required views (heroBackgroundBlurred, heroThumbnail) are not found
         */
        fun from(container: View): PlaylistHeroHelper {
            val backgroundBlurred = container.findViewById<ImageView>(R.id.heroBackgroundBlurred)
                ?: throw IllegalStateException("Required view heroBackgroundBlurred not found in container")
            val thumbnail = container.findViewById<ShapeableImageView>(R.id.heroThumbnail)
                ?: throw IllegalStateException("Required view heroThumbnail not found in container")
            return PlaylistHeroHelper(
                backgroundBlurred = backgroundBlurred,
                thumbnail = thumbnail,
                thumbnailCard = container.findViewById(R.id.heroThumbnailCard)
            )
        }
    }
}
