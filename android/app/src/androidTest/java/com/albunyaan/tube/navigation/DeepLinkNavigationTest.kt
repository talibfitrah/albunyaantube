package com.albunyaan.tube.navigation

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.albunyaan.tube.R
import com.albunyaan.tube.player.PlaybackService
import com.albunyaan.tube.preferences.SettingsPreferences
import com.albunyaan.tube.ui.MainActivity
import com.albunyaan.tube.ui.MainShellFragment
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deep-link navigation instrumentation tests (6 tests total).
 *
 * These tests verify that deep-links (albunyaantube:// scheme) are handled correctly
 * in both cold start and warm start scenarios.
 *
 * Cold start: App not running, deep-link launches the app
 * Warm start: App already running, deep-link arrives via onNewIntent
 *
 * The app uses a nested navigation architecture:
 * MainActivity (nav_graph.xml) → MainShellFragment → Nested NavController (main_tabs_nav.xml)
 *
 * Deep-links are defined in main_tabs_nav.xml and handled by the nested NavController.
 * MainActivity routes deep-links to the nested controller, waiting for MainShellFragment
 * if necessary (during splash/onboarding).
 *
 * Design per AGENTS.md zero-tolerance flakiness policy:
 * - Uses CountDownLatch with NavController.OnDestinationChangedListener for deterministic waits
 * - Listener tracking list is cleared in @After (listeners auto-cleaned when activity destroys)
 * - All main thread operations happen via scenario.onActivity which internally uses runOnMainSync
 * - Marks onboarding as completed before each test to skip onboarding flow
 * - Explicitly stops PlaybackService before closing scenario to prevent foreground service
 *   from blocking activity destruction
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeepLinkNavigationTest {

    companion object {
        /** Maximum time to wait for navigation to complete (seconds) */
        private const val NAVIGATION_TIMEOUT_SECONDS = 15L
    }

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private var scenario: ActivityScenario<MainActivity>? = null

    /** Track registered destination listeners for cleanup */
    private val registeredListeners = mutableListOf<Pair<NavController, NavController.OnDestinationChangedListener>>()

    /** Flag to signal that tearDown is in progress - prevents new listener registrations */
    private val isTearingDown = AtomicBoolean(false)

    @Before
    fun setUp() {
        hiltRule.inject()
        isTearingDown.set(false)

        // Mark onboarding as completed to skip onboarding flow and go directly to main
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settingsPreferences = SettingsPreferences(context)
        runBlocking {
            settingsPreferences.setOnboardingCompleted(true)
        }
    }

    @After
    fun tearDown() {
        // Signal that tearDown is in progress
        isTearingDown.set(true)

        // Clear tracked listeners list - we don't need to remove them since we're
        // finishing the activity anyway and it will destroy everything.
        registeredListeners.clear()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Stop the PlaybackService explicitly BEFORE finishing the activity.
        // This removes the foreground notification and allows the activity to be destroyed.
        // Without this, the foreground service keeps the activity alive and ActivityScenario.close()
        // times out waiting for the activity to be destroyed.
        try {
            val serviceIntent = Intent(context, PlaybackService::class.java)
            context.stopService(serviceIntent)
        } catch (_: Exception) {
            // Service may not be running, that's fine
        }

        // Short sleep to let the service stop message be processed.
        // This is bounded and acceptable in teardown - we're just giving the system
        // a brief moment to process the stopService() before we finish the activity.
        Thread.sleep(50)

        // Reset the activity's intent before finishing.
        // This fixes ActivityScenario.close() timeouts that occur when onNewIntent()
        // changes the intent (via setIntent()), causing ActivityScenario to lose track
        // of lifecycle events due to intent mismatch.
        try {
            scenario?.onActivity { activity ->
                // Reset to a neutral intent that matches the original launch intent structure
                val resetIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.intent = resetIntent
            }
        } catch (_: Exception) {
            // Scenario may already be closed or activity gone
        }

        // Finish the activity
        try {
            scenario?.onActivity { activity ->
                activity.finish()
            }
        } catch (_: Exception) {
            // Scenario may already be closed or activity gone
        }

        // Brief pause to let activity lifecycle process the finish
        Thread.sleep(50)

        // Now close the scenario - activity should be able to destroy cleanly
        try {
            scenario?.close()
        } catch (_: Exception) {
            // If close fails for any reason, just proceed with cleanup
        } finally {
            scenario = null
        }
    }

    // region Cold Start Tests

    /**
     * Test cold start with video deep-link.
     *
     * Launches MainActivity with albunyaantube://video/{id} intent.
     * Verifies navigation reaches playerFragment destination.
     */
    @Test
    fun coldStart_videoDeepLink_navigatesToPlayer() {
        val videoId = "test_video_123"
        val intent = createDeepLinkIntent("albunyaantube://video/$videoId")

        scenario = ActivityScenario.launch(intent)

        waitForDestination(R.id.playerFragment, "playerFragment")
    }

    /**
     * Test cold start with channel deep-link.
     */
    @Test
    fun coldStart_channelDeepLink_navigatesToChannelDetail() {
        val channelId = "test_channel_456"
        val intent = createDeepLinkIntent("albunyaantube://channel/$channelId")

        scenario = ActivityScenario.launch(intent)

        waitForDestination(R.id.channelDetailFragment, "channelDetailFragment")
    }

    /**
     * Test cold start with playlist deep-link.
     */
    @Test
    fun coldStart_playlistDeepLink_navigatesToPlaylistDetail() {
        val playlistId = "test_playlist_789"
        val intent = createDeepLinkIntent("albunyaantube://playlist/$playlistId")

        scenario = ActivityScenario.launch(intent)

        waitForDestination(R.id.playlistDetailFragment, "playlistDetailFragment")
    }

    // endregion

    // region Warm Start Tests

    /**
     * Test warm start with video deep-link simulating onNewIntent.
     *
     * First launches the app normally, then simulates a deep-link via onNewIntent
     * (which is what happens when SINGLE_TOP activity receives a new intent).
     * Verifies navigation reaches playerFragment destination.
     */
    @Test
    fun warmStart_videoDeepLink_navigatesToPlayer() {
        // First, launch app normally
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForMainShellReady()

        // Navigate to a different tab to ensure we're not already on player
        scenario?.onActivity { activity ->
            val nestedNavController = getNestedNavController(activity)
            nestedNavController?.navigate(R.id.channelsFragment)
        }
        // scenario.onActivity uses runOnMainSync internally, so navigation is already dispatched

        // Verify we're on channels first
        waitForDestination(R.id.channelsFragment, "channelsFragment")

        // Simulate receiving a deep-link via onNewIntent (warm start scenario)
        // This is what happens when a SINGLE_TOP activity receives a new intent
        val videoId = "test_video_warm_start"
        val deepLinkIntent = createDeepLinkIntent("albunyaantube://video/$videoId")

        scenario?.onActivity { activity ->
            // Directly call onNewIntent to simulate warm start behavior
            activity.onNewIntent(deepLinkIntent)
        }
        // scenario.onActivity uses runOnMainSync internally, intent handling is dispatched

        // Wait for navigation to playerFragment
        waitForDestination(R.id.playerFragment, "playerFragment")
    }

    /**
     * Test warm start deep-link while on playerFragment.
     *
     * Verifies that receiving a new video deep-link while already on playerFragment
     * still works correctly (should update or re-navigate to player).
     */
    @Test
    fun warmStart_videoDeepLink_whileOnPlayer_handlesCorrectly() {
        // Launch with first video deep-link
        val firstVideoId = "first_video"
        val firstIntent = createDeepLinkIntent("albunyaantube://video/$firstVideoId")

        scenario = ActivityScenario.launch(firstIntent)

        // Wait for player
        waitForDestination(R.id.playerFragment, "playerFragment (first video)")

        // Simulate receiving second video deep-link via onNewIntent
        val secondVideoId = "second_video"
        val secondIntent = createDeepLinkIntent("albunyaantube://video/$secondVideoId")

        scenario?.onActivity { activity ->
            // Directly call onNewIntent to simulate warm start behavior
            activity.onNewIntent(secondIntent)
        }
        // scenario.onActivity uses runOnMainSync internally, intent handling is dispatched

        // Verify we're still on player (navigation handled the new video)
        waitForDestination(R.id.playerFragment, "playerFragment (second video)")
    }

    // endregion

    // region Edge Cases

    /**
     * Test that unrecognized deep-link path doesn't crash the app and doesn't navigate.
     *
     * Sends a deep-link with a valid host (video) but invalid path structure that
     * doesn't match the nav graph's expected pattern (albunyaantube://video/{videoId}).
     *
     * The manifest intent-filter only accepts hosts: video, channel, playlist.
     * Using an invalid host like "unknown" would throw ActivityNotFoundException.
     * Instead, we use a valid host with an extra/invalid path segment that won't
     * match the navigation graph's deepLink pattern.
     *
     * The app should remain stable (not crash) and the destination should remain unchanged.
     */
    @Test
    fun unrecognizedDeepLinkPath_doesNotCrash() {
        // Launch app normally first
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForMainShellReady()

        // Store initial destination to verify it remains unchanged after invalid deep-link
        val initialDestRef = AtomicReference<Int?>()
        scenario?.onActivity { activity ->
            initialDestRef.set(getNestedNavController(activity)?.currentDestination?.id)
        }

        val initialDestination = initialDestRef.get()
        assertNotNull("Initial destination should be set", initialDestination)

        // Send deep-link with valid host but invalid path structure via onNewIntent.
        // Navigation graph expects: albunyaantube://video/{videoId}
        // We send: albunyaantube://video/some_id/extra/invalid/segments
        // This matches the manifest intent-filter (host=video) but won't match
        // the nav graph's deepLink pattern, so handleDeepLink() should gracefully
        // fail without crashing or navigating.
        val invalidPathIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("albunyaantube://video/some_id/extra/invalid/segments")
        )
        invalidPathIntent.setPackage(
            ApplicationProvider.getApplicationContext<android.content.Context>().packageName
        )
        invalidPathIntent.addCategory(Intent.CATEGORY_DEFAULT)
        invalidPathIntent.addCategory(Intent.CATEGORY_BROWSABLE)

        scenario?.onActivity { activity ->
            // Simulate receiving the invalid deep-link via onNewIntent
            activity.onNewIntent(invalidPathIntent)
        }
        // scenario.onActivity uses runOnMainSync internally, intent handling is dispatched

        // App should be running normally without crash - verify activity is still in valid state
        assertTrue(
            "Activity should be in RESUMED or STARTED state after invalid deep-link",
            scenario?.state == Lifecycle.State.RESUMED || scenario?.state == Lifecycle.State.STARTED
        )

        // Verify nested nav controller still exists and destination is unchanged
        // (invalid deep-link should not navigate anywhere)
        val verifyResult = AtomicBoolean(false)
        scenario?.onActivity { activity ->
            val nestedNavController = getNestedNavController(activity)
            if (nestedNavController != null) {
                val currentDestination = nestedNavController.currentDestination?.id
                verifyResult.set(currentDestination == initialDestination)
            }
        }

        assertTrue(
            "Destination should remain unchanged after invalid deep-link (no navigation should occur)",
            verifyResult.get()
        )
    }

    // endregion

    // region Helper Methods

    /**
     * Create an ACTION_VIEW intent with the given deep-link URI.
     */
    private fun createDeepLinkIntent(uriString: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage(ApplicationProvider.getApplicationContext<android.content.Context>().packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * Wait for MainShellFragment to be displayed and its nested nav controller to be ready.
     * Uses CountDownLatch with NavController.OnDestinationChangedListener for deterministic,
     * timeout-bounded waits without polling loops.
     *
     * Strategy:
     * 1. First check if nested nav controller is already available (MainShell already displayed)
     * 2. If not, register a destination listener on the main NavController waiting for mainShellFragment
     * 3. Once mainShellFragment is reached, wait briefly for nested NavHost to initialize
     */
    private fun waitForMainShellReady() {
        if (isTearingDown.get()) {
            return
        }

        scenario?.moveToState(Lifecycle.State.RESUMED)

        val mainShellLatch = CountDownLatch(1)
        val nestedNavReady = AtomicBoolean(false)

        scenario?.onActivity { activity ->
            if (isTearingDown.get()) {
                mainShellLatch.countDown()
                return@onActivity
            }

            // Check if nested nav controller is already available
            if (getNestedNavController(activity) != null) {
                nestedNavReady.set(true)
                mainShellLatch.countDown()
                return@onActivity
            }

            // Get the main nav controller and wait for mainShellFragment destination
            val mainNavHost = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

            if (mainNavHost == null) {
                mainShellLatch.countDown()
                return@onActivity
            }

            val mainNavController = mainNavHost.navController

            // Check if we're already at mainShellFragment
            if (mainNavController.currentDestination?.id == R.id.mainShellFragment) {
                // MainShellFragment is current destination, but nested nav might not be ready yet
                // Register a listener for any destination change in nested nav (once available)
                nestedNavReady.set(true)
                mainShellLatch.countDown()
                return@onActivity
            }

            // Register listener to wait for mainShellFragment
            val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                if (destination.id == R.id.mainShellFragment) {
                    nestedNavReady.set(true)
                    mainShellLatch.countDown()
                }
            }
            mainNavController.addOnDestinationChangedListener(listener)
            registeredListeners.add(mainNavController to listener)

            // Re-check in case navigation happened between check and listener registration
            if (mainNavController.currentDestination?.id == R.id.mainShellFragment) {
                nestedNavReady.set(true)
                mainShellLatch.countDown()
            }
        }

        // Wait for mainShellFragment with timeout
        val reachedMainShell = mainShellLatch.await(NAVIGATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!reachedMainShell) {
            throw AssertionError("MainShellFragment should be reached within timeout")
        }

        if (!nestedNavReady.get()) {
            throw AssertionError("MainShellFragment navigation failed")
        }

        // Now wait for nested nav controller to be available (fragment view creation)
        val nestedNavLatch = CountDownLatch(1)
        val nestedNavFound = AtomicBoolean(false)

        scenario?.onActivity { activity ->
            if (getNestedNavController(activity) != null) {
                nestedNavFound.set(true)
                nestedNavLatch.countDown()
            } else {
                // Post a check after the current frame to allow fragment view creation
                activity.window.decorView.post {
                    if (getNestedNavController(activity) != null) {
                        nestedNavFound.set(true)
                    }
                    nestedNavLatch.countDown()
                }
            }
        }

        nestedNavLatch.await(NAVIGATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Final verification - check once more via scenario.onActivity
        if (!nestedNavFound.get()) {
            scenario?.onActivity { activity ->
                nestedNavFound.set(getNestedNavController(activity) != null)
            }
        }

        assertTrue("Nested NavController should be available after MainShellFragment is displayed", nestedNavFound.get())
    }

    /**
     * Wait for the nested NavController to reach the expected destination.
     * Uses CountDownLatch with NavController.OnDestinationChangedListener for deterministic waits.
     *
     * First waits for the MainShellFragment to be ready (polling), then registers a listener
     * for navigation changes.
     *
     * @param expectedDestinationId The R.id of the expected destination
     * @param destinationName Human-readable name for error messages
     */
    private fun waitForDestination(expectedDestinationId: Int, destinationName: String) {
        if (isTearingDown.get()) {
            return // Exit early if tearDown is in progress
        }

        scenario?.moveToState(Lifecycle.State.RESUMED)

        // First, wait for MainShellFragment to be ready (nav controller available)
        waitForMainShellReady()

        val navigationLatch = CountDownLatch(1)
        val lastSeenDestination = AtomicInteger(-1)
        val setupSuccessful = AtomicBoolean(false)

        // Register listener via scenario.onActivity which handles main thread synchronization
        scenario?.onActivity { activity ->
            if (isTearingDown.get()) {
                return@onActivity
            }

            try {
                val navController = getNestedNavController(activity)
                if (navController == null) {
                    return@onActivity
                }

                // Check current destination first
                val currentDest = navController.currentDestination?.id ?: -1
                lastSeenDestination.set(currentDest)
                if (currentDest == expectedDestinationId) {
                    navigationLatch.countDown()
                    setupSuccessful.set(true)
                    return@onActivity
                }

                // Register listener for future changes
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    lastSeenDestination.set(destination.id)
                    if (destination.id == expectedDestinationId) {
                        navigationLatch.countDown()
                    }
                }
                navController.addOnDestinationChangedListener(listener)
                registeredListeners.add(navController to listener)
                setupSuccessful.set(true)

                // Re-check in case navigation happened between our check and listener registration
                val recheckDest = navController.currentDestination?.id ?: -1
                lastSeenDestination.set(recheckDest)
                if (recheckDest == expectedDestinationId) {
                    navigationLatch.countDown()
                }
            } catch (e: Exception) {
                // Log but don't crash setup - await() will timeout if navigation doesn't happen
                android.util.Log.w("DeepLinkNavigationTest", "Failed to set up destination listener", e)
            }
        }

        // Wait for navigation to complete
        val reachedDestination = navigationLatch.await(NAVIGATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(
            "Expected destination $destinationName (id=$expectedDestinationId) but was ${lastSeenDestination.get()}. Setup successful: ${setupSuccessful.get()}",
            reachedDestination
        )
    }

    /**
     * Get the nested NavController from MainActivity.
     *
     * The app uses a two-level navigation:
     * 1. Main NavController (nav_graph.xml): splash → onboarding → mainShellFragment
     * 2. Nested NavController (main_tabs_nav.xml): tabs and detail screens
     *
     * Deep-links target the nested controller since destinations are defined in main_tabs_nav.xml.
     */
    private fun getNestedNavController(activity: MainActivity): NavController? {
        val mainNavHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return null

        val mainShellFragment = mainNavHost.childFragmentManager
            .primaryNavigationFragment as? MainShellFragment
            ?: return null

        val nestedNavHost = mainShellFragment.childFragmentManager
            .findFragmentById(R.id.main_shell_nav_host) as? NavHostFragment
            ?: return null

        return nestedNavHost.navController
    }

    // endregion
}
