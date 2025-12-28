package com.albunyaan.tube.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * About screen showing app version, licenses, and links.
 *
 * Hidden developer options: Tap the version text 7 times rapidly to unlock
 * developer settings dialog (same pattern as Android's developer options).
 */
@AndroidEntryPoint
class AboutFragment : Fragment() {

    companion object {
        private const val KEY_TAP_COUNT = "developer_tap_count"
        private const val KEY_LAST_TAP_TIME = "developer_last_tap_time"
    }

    @Inject
    lateinit var featureFlags: PlaybackFeatureFlags

    // Hidden developer options activation (7 taps like Android dev options)
    private var developerTapCount = 0
    private var lastDeveloperTapTime = 0L
    private val developerTapThreshold = 7
    private val developerTapTimeoutMs = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore tap counter state across configuration changes
        savedInstanceState?.let {
            developerTapCount = it.getInt(KEY_TAP_COUNT, 0)
            lastDeveloperTapTime = it.getLong(KEY_LAST_TAP_TIME, 0L)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Preserve tap counter state across configuration changes
        outState.putInt(KEY_TAP_COUNT, developerTapCount)
        outState.putLong(KEY_LAST_TAP_TIME, lastDeveloperTapTime)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(view)
        setupVersionInfo(view)
        setupLinks(view)
    }

    private fun setupToolbar(view: View) {
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupVersionInfo(view: View) {
        val versionText = view.findViewById<MaterialTextView>(R.id.versionText)
        versionText?.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        // Hidden developer options: 7 taps on version text (like Android's developer options)
        versionText?.setOnClickListener {
            handleDeveloperOptionsTap()
        }
    }

    /**
     * Handle taps on version text for hidden developer options.
     * 7 rapid taps activates developer settings (like Android's developer options).
     * This stays on the About screen - no navigation involved.
     *
     * Uses SystemClock.elapsedRealtime() instead of System.currentTimeMillis() to avoid
     * issues with user time changes resetting the tap counter unexpectedly.
     */
    private fun handleDeveloperOptionsTap() {
        val now = SystemClock.elapsedRealtime()

        // Reset counter if too much time passed
        if (now - lastDeveloperTapTime > developerTapTimeoutMs) {
            developerTapCount = 0
        }
        lastDeveloperTapTime = now
        developerTapCount++

        when {
            developerTapCount >= developerTapThreshold -> {
                // Activate developer options
                developerTapCount = 0
                showDeveloperSettingsDialog()
            }
            developerTapCount >= developerTapThreshold - 3 -> {
                // Show countdown toast (last 3 taps)
                val remaining = developerTapThreshold - developerTapCount
                context?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        resources.getQuantityString(R.plurals.dev_settings_steps_away, remaining, remaining),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            // First taps: no feedback (silent counting)
        }
    }

    private fun showDeveloperSettingsDialog() {
        // Guard against fragment not attached
        if (!isAdded) return

        // Log current state for debugging
        featureFlags.logCurrentState()

        val dialog = DeveloperSettingsDialog.newInstance()
        dialog.show(childFragmentManager, DeveloperSettingsDialog.TAG)
    }

    private fun setupLinks(view: View) {
        view.findViewById<View>(R.id.websiteItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube")
        }

        view.findViewById<View>(R.id.privacyItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube/privacy")
        }

        view.findViewById<View>(R.id.termsItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube/terms")
        }

        view.findViewById<View>(R.id.licensesItem)?.setOnClickListener {
            openUrl("https://albunyaan.tube/licenses")
        }

        view.findViewById<View>(R.id.githubItem)?.setOnClickListener {
            openUrl("https://github.com/albunyaan/albunyaan-tube")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
