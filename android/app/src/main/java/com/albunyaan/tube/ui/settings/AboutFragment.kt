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

        /** Fallback when `share.base.url` is blanked for local development. */
        private const val DEFAULT_WEB_BASE = "https://app.fitrahtube.com"
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

        // Hidden developer options: 7 taps on version text (like Android's developer options).
        //
        // ANDROID-PLAY-03: debug builds only. The dialog exposes which YouTube
        // client the app impersonates plus trip/reset controls for the extraction
        // cooldown — user-reachable rate-limit and impersonation switches are a
        // liability in Play review, and an ordinary user who taps around can break
        // their own playback with them. Leaving the listener unregistered in
        // release is what makes the dialog unreachable; the dialog itself still
        // compiles in every flavor.
        if (BuildConfig.DEBUG) {
            versionText?.setOnClickListener {
                handleDeveloperOptionsTap()
            }
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

    /**
     * ANDROID-ABOUT-URL-01: every link on this screen used to point at
     * `albunyaan.tube`, which has no DNS record at all — five dead links, one
     * of them the privacy policy, which is an automatic Play rejection.
     *
     * The legal pages are served by the backend (`LegalPagesController`) at the
     * same host the app already builds share links from, so the host is derived
     * from [BuildConfig.SHARE_BASE_URL] rather than hardcoded a second time.
     * `share.base.url` can be blanked in local.properties to force the in-app
     * deep-link fallback, hence the empty guard.
     */
    private fun setupLinks(view: View) {
        val webBase = BuildConfig.SHARE_BASE_URL.trimEnd('/').ifEmpty { DEFAULT_WEB_BASE }

        // OWNER DECISION: there is no public website to link to. fitrahtube.com
        // resolves but 404s, and app.fitrahtube.com serves the API only — its
        // root 403s. Hidden rather than pointed at a 404, because Play
        // reviewers do click these. To re-enable once a site exists, delete
        // these two visibility lines.
        view.findViewById<View>(R.id.websiteItem)?.visibility = View.GONE
        view.findViewById<View>(R.id.websiteDivider)?.visibility = View.GONE
        view.findViewById<View>(R.id.websiteItem)?.setOnClickListener {
            openUrl("https://fitrahtube.com")
        }

        view.findViewById<View>(R.id.privacyItem)?.setOnClickListener {
            openUrl("$webBase/privacy")
        }

        view.findViewById<View>(R.id.termsItem)?.setOnClickListener {
            openUrl("$webBase/terms")
        }

        view.findViewById<View>(R.id.licensesItem)?.setOnClickListener {
            openUrl("$webBase/licenses")
        }

        // The old `albunyaan/albunyaan-tube` slug 404s; this repo is public
        // under the org the git remote actually points at.
        view.findViewById<View>(R.id.githubItem)?.setOnClickListener {
            openUrl("https://github.com/talibfitrah/albunyaantube")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
