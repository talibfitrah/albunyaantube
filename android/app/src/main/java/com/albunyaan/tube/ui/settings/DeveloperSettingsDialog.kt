package com.albunyaan.tube.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import android.widget.Toast
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hidden developer settings dialog for runtime feature flag control.
 *
 * Access: Tap the "Version" text 7 times on the About screen to reveal developer options
 * (same pattern as Android's developer options accessed via Build Number).
 *
 * This dialog provides operational kill-switches for playback features:
 * - Synthetic Adaptive DASH: Multi-representation DASH from progressive streams
 * - MPD Prefetch: Pre-generate DASH MPD on video tap
 * - Degradation Manager: Per-video refresh budgets and quality step-downs
 * - iOS Client Fetch: Use iOS client for HLS manifest extraction
 *
 * Each toggle shows:
 * - Build default (from BuildConfig)
 * - Current effective value
 * - Runtime override status
 *
 * Changes take effect immediately and persist across app restarts.
 */
@AndroidEntryPoint
class DeveloperSettingsDialog : DialogFragment() {

    companion object {
        const val TAG = "DeveloperSettingsDialog"

        fun newInstance(): DeveloperSettingsDialog {
            return DeveloperSettingsDialog()
        }
    }

    @Inject
    lateinit var featureFlags: PlaybackFeatureFlags

    @Inject
    lateinit var extractorClient: NewPipeExtractorClient

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        // Wrap content in ScrollView for accessibility with large fonts/small screens
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }

        // Create the content view
        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.spacing_md),
                resources.getDimensionPixelSize(R.dimen.spacing_sm),
                resources.getDimensionPixelSize(R.dimen.spacing_md),
                resources.getDimensionPixelSize(R.dimen.spacing_sm)
            )
        }
        scrollView.addView(contentView)

        // Add header with diagnostics
        val headerView = TextView(context).apply {
            text = getString(R.string.dev_settings_header)
            setTextColor(context.getColor(R.color.home_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.spacing_md))
        }
        contentView.addView(headerView)

        // Get current diagnostics
        val diagnostics = featureFlags.getDiagnostics()

        // Create toggle for each feature flag
        addFeatureToggle(
            contentView,
            getString(R.string.dev_settings_synth_adaptive_title),
            getString(R.string.dev_settings_synth_adaptive_desc),
            featureFlags.isSynthAdaptiveEnabled,
            BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            diagnostics["synth_adaptive"]?.runtimeOverride
        ) { enabled ->
            featureFlags.setSynthAdaptiveEnabled(if (enabled == BuildConfig.ENABLE_SYNTH_ADAPTIVE) null else enabled)
        }

        addFeatureToggle(
            contentView,
            getString(R.string.dev_settings_mpd_prefetch_title),
            getString(R.string.dev_settings_mpd_prefetch_desc),
            featureFlags.isMpdPrefetchEnabled,
            BuildConfig.ENABLE_MPD_PREFETCH,
            diagnostics["mpd_prefetch"]?.runtimeOverride
        ) { enabled ->
            featureFlags.setMpdPrefetchEnabled(if (enabled == BuildConfig.ENABLE_MPD_PREFETCH) null else enabled)
        }

        addFeatureToggle(
            contentView,
            getString(R.string.dev_settings_degradation_title),
            getString(R.string.dev_settings_degradation_desc),
            featureFlags.isDegradationManagerEnabled,
            BuildConfig.ENABLE_DEGRADATION_MANAGER,
            diagnostics["degradation_manager"]?.runtimeOverride
        ) { enabled ->
            featureFlags.setDegradationManagerEnabled(if (enabled == BuildConfig.ENABLE_DEGRADATION_MANAGER) null else enabled)
        }

        addFeatureToggle(
            contentView,
            getString(R.string.dev_settings_ios_fetch_title),
            getString(R.string.dev_settings_ios_fetch_desc),
            featureFlags.isIosFetchEnabled,
            BuildConfig.ENABLE_NPE_IOS_FETCH,
            diagnostics["ios_fetch"]?.runtimeOverride
        ) { enabled ->
            featureFlags.setIosFetchEnabled(if (enabled == BuildConfig.ENABLE_NPE_IOS_FETCH) null else enabled)
        }

        // Add clear cache button (useful when toggling IOS_FETCH to force new extractions)
        val clearCacheButton = TextView(context).apply {
            text = getString(R.string.dev_settings_clear_cache)
            setTextColor(context.getColor(R.color.primary_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setPadding(
                0,
                resources.getDimensionPixelSize(R.dimen.spacing_md),
                0,
                resources.getDimensionPixelSize(R.dimen.spacing_sm)
            )
            setOnClickListener {
                try {
                    val cleared = extractorClient.clearStreamCache()
                    Toast.makeText(
                        context,
                        getString(R.string.dev_settings_cache_cleared, cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        getString(R.string.dev_settings_cache_clear_failed, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        contentView.addView(clearCacheButton)

        // Add reset button
        val resetButton = TextView(context).apply {
            text = getString(R.string.dev_settings_reset_all)
            setTextColor(context.getColor(R.color.accent_red))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
            setPadding(
                0,
                resources.getDimensionPixelSize(R.dimen.spacing_lg),
                0,
                resources.getDimensionPixelSize(R.dimen.spacing_sm)
            )
            setOnClickListener {
                featureFlags.clearAllOverrides()
                dismiss()
            }
        }
        contentView.addView(resetButton)

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dev_settings_title)
            .setView(scrollView)
            .setPositiveButton(R.string.dev_settings_done, null)
            .create()
    }

    private fun addFeatureToggle(
        parent: LinearLayout,
        title: String,
        description: String,
        currentValue: Boolean,
        buildDefault: Boolean,
        runtimeOverride: Boolean?,
        onToggle: (Boolean) -> Unit
    ) {
        val context = requireContext()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                resources.getDimensionPixelSize(R.dimen.spacing_sm),
                0,
                resources.getDimensionPixelSize(R.dimen.spacing_sm)
            )
        }

        // Title row with switch
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_subtitle))
            setTextColor(context.getColor(R.color.home_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleView)

        val toggle = SwitchMaterial(context).apply {
            isChecked = currentValue
            // OnCheckedChangeListener set below after statusView is created
        }
        titleRow.addView(toggle)

        container.addView(titleRow)

        // Description
        val descView = TextView(context).apply {
            text = description
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setTextColor(context.getColor(R.color.home_text_secondary))
        }
        container.addView(descView)

        // Status line showing build default and override status
        val buildDefaultStr = if (buildDefault) {
            getString(R.string.dev_settings_build_default_on)
        } else {
            getString(R.string.dev_settings_build_default_off)
        }
        val initialOverrideStatus = when {
            runtimeOverride == null -> getString(R.string.dev_settings_using_default)
            runtimeOverride -> getString(R.string.dev_settings_overridden_on)
            else -> getString(R.string.dev_settings_overridden_off)
        }
        val statusView = TextView(context).apply {
            text = getString(R.string.dev_settings_status_format, buildDefaultStr, initialOverrideStatus)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_duration))
            setTextColor(context.getColor(R.color.home_text_muted))
        }
        container.addView(statusView)

        // Update status text dynamically when toggle changes
        toggle.setOnCheckedChangeListener { _, isChecked ->
            onToggle(isChecked)
            // Calculate new override status based on toggle position vs build default
            val newOverride = if (isChecked == buildDefault) null else isChecked
            val newOverrideStatus = when {
                newOverride == null -> getString(R.string.dev_settings_using_default)
                newOverride -> getString(R.string.dev_settings_overridden_on)
                else -> getString(R.string.dev_settings_overridden_off)
            }
            statusView.text = getString(R.string.dev_settings_status_format, buildDefaultStr, newOverrideStatus)
        }

        parent.addView(container)
    }
}
