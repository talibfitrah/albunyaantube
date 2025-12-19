package com.albunyaan.tube.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.ActivityMainBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.player.PlaybackService
import dagger.hilt.android.AndroidEntryPoint

/**
 * P3-T2: Main activity with Hilt DI support
 *
 * Handles deep links via albunyaantube:// scheme:
 * - albunyaantube://video/{videoId} → PlayerFragment
 * - albunyaantube://channel/{channelId} → ChannelDetailFragment
 * - albunyaantube://playlist/{playlistId} → PlaylistDetailFragment
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val navController: NavController by lazy {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        host.navController
    }

    // Track pending navigation listener to prevent memory leaks
    private var pendingNavigationListener: NavController.OnDestinationChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply stored locale before super.onCreate to ensure proper locale is applied
        LocaleManager.applyStoredLocale(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        navController // trigger lazy init if toolbar needed later

        // Handle intent (both deep links and service intents)
        intent?.let { pendingIntent ->
            binding.root.post {
                handleIntent(pendingIntent)
            }
        }

        // Handle back press for nested navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                    ?.childFragmentManager?.primaryNavigationFragment

                // If we're in MainShellFragment, check the nested navigation
                if (currentFragment is MainShellFragment) {
                    val mainShellNavHost = currentFragment.childFragmentManager
                        .findFragmentById(R.id.main_shell_nav_host) as? NavHostFragment
                    val nestedNavController = mainShellNavHost?.navController

                    if (nestedNavController != null) {
                        // Try to pop the nested back stack first
                        if (nestedNavController.popBackStack()) {
                            return
                        }
                        // If we're on a root tab and can't pop, finish the activity
                        // (user can use bottom nav to switch tabs)
                    }
                }

                // Default behavior: try to navigate up in main nav controller, or finish activity
                if (!navController.navigateUp()) {
                    finish()
                }
            }
        })
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update intent for future reference
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            PlaybackService.ACTION_OPEN_PLAYER -> handleOpenPlayerIntent()
            Intent.ACTION_VIEW -> handleDeepLink(intent)
        }
    }

    /**
     * Handle ACTION_OPEN_PLAYER from PlaybackService notification.
     */
    private fun handleOpenPlayerIntent() {
        try {
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.mainShellFragment) {
                // We're in the app, navigate to player via nested nav controller
                val nestedNav = getNestedNavController()
                if (nestedNav != null) {
                    nestedNav.navigate(R.id.playerFragment)
                } else {
                    // Nested nav not ready yet, wait for it
                    android.util.Log.d("MainActivity", "Nested nav not ready, waiting for controller")
                    waitForNestedNavControllerThenExecute(
                        action = { controller -> controller.navigate(R.id.playerFragment) }
                    )
                }
            } else {
                // Not on mainShell (splash/onboarding/transition), wait for mainShell then navigate
                waitForMainShellThenNavigate { nestedNavController ->
                    nestedNavController.navigate(R.id.playerFragment)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to navigate to player", e)
        }
    }

    /**
     * Handle ACTION_VIEW deep links (albunyaantube:// scheme).
     * Deep links are defined in main_tabs_nav.xml which is hosted in MainShellFragment.
     */
    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data ?: return
        android.util.Log.d("MainActivity", "Handling deep link: $uri")

        try {
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.mainShellFragment) {
                // We're already in the app, route to nested nav controller
                val nestedNav = getNestedNavController()
                if (nestedNav != null) {
                    nestedNav.handleDeepLink(intent)
                } else {
                    // Nested nav not ready yet, wait for it
                    android.util.Log.d("MainActivity", "Nested nav not ready for deep link, waiting")
                    waitForNestedNavControllerThenExecute(
                        action = { controller -> controller.handleDeepLink(intent) }
                    )
                }
            } else if (currentDest == R.id.splashFragment || currentDest == R.id.onboardingFragment) {
                // We're on splash/onboarding, wait for mainShell then handle deep link
                waitForMainShellThenNavigate { nestedNavController ->
                    nestedNavController.handleDeepLink(intent)
                }
            } else {
                // Fallback: wait for nested controller to be ready then handle
                android.util.Log.d("MainActivity", "Deep link in transition state, waiting for nested nav")
                waitForNestedNavControllerThenExecute(
                    action = { nestedNavController -> nestedNavController.handleDeepLink(intent) }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to handle deep link: $uri", e)
        }
    }

    /**
     * Get the nested NavController from MainShellFragment.
     */
    private fun getNestedNavController(): NavController? {
        val mainShellFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            ?.childFragmentManager?.primaryNavigationFragment as? MainShellFragment

        val mainShellNavHost = mainShellFragment?.childFragmentManager
            ?.findFragmentById(R.id.main_shell_nav_host) as? NavHostFragment

        return mainShellNavHost?.navController
    }

    /**
     * Wait for MainShellFragment to be the current destination, then execute the navigation action.
     * Uses event-driven approach: posts to decorView to wait for fragment view creation,
     * then polls briefly for nested nav controller availability.
     *
     * Note: If multiple navigation requests arrive quickly, only the latest one is honored.
     * This is intentional - when rapid intents arrive (e.g., from notifications), the user
     * typically wants the most recent action, not a queue of old ones. Executing stale
     * navigation actions could lead to confusing UX.
     */
    private fun waitForMainShellThenNavigate(action: (NavController) -> Unit) {
        // Remove any existing pending listener to avoid leaks and prevent stale actions
        pendingNavigationListener?.let { navController.removeOnDestinationChangedListener(it) }

        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(controller: NavController, destination: androidx.navigation.NavDestination, arguments: Bundle?) {
                if (destination.id == R.id.mainShellFragment) {
                    // Remove listener to prevent repeated calls
                    controller.removeOnDestinationChangedListener(this)
                    pendingNavigationListener = null
                    // Wait for fragment view creation via decorView.post(), then check for nested nav
                    waitForNestedNavControllerThenExecute(action)
                }
            }
        }
        pendingNavigationListener = listener
        navController.addOnDestinationChangedListener(listener)
    }

    /**
     * Wait for nested nav controller to become available, then execute the action.
     * Uses decorView.post() to wait for fragment view creation, with retry logic
     * for cases where the nested NavHost isn't immediately available.
     */
    private fun waitForNestedNavControllerThenExecute(
        action: (NavController) -> Unit,
        retryCount: Int = 0
    ) {
        val maxRetries = 5

        // Guard against activity being destroyed during async wait
        if (isFinishing || isDestroyed) {
            android.util.Log.d("MainActivity", "Activity finishing/destroyed, skipping nested nav wait")
            return
        }

        // Post to decorView to wait for current frame's layout/view creation
        window.decorView.post {
            // Re-check lifecycle state inside posted runnable
            if (isFinishing || isDestroyed) {
                android.util.Log.d("MainActivity", "Activity finishing/destroyed during wait, aborting")
                return@post
            }

            try {
                val nestedNavController = getNestedNavController()
                if (nestedNavController != null) {
                    action(nestedNavController)
                } else if (retryCount < maxRetries) {
                    // Nested nav not ready yet, retry after next frame
                    android.util.Log.d("MainActivity", "Nested nav not ready, retry ${retryCount + 1}/$maxRetries")
                    waitForNestedNavControllerThenExecute(action, retryCount + 1)
                } else {
                    android.util.Log.e("MainActivity", "Nested nav controller not available after $maxRetries retries")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Navigation failed after waiting for mainShell", e)
            }
        }
    }

    override fun onDestroy() {
        // Clean up any pending navigation listener to prevent leaks
        pendingNavigationListener?.let { navController.removeOnDestinationChangedListener(it) }
        pendingNavigationListener = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    /**
     * Hide or show the bottom navigation bar (for fullscreen video playback).
     * This finds the MainShellFragment and hides its bottom nav.
     */
    fun setBottomNavVisibility(visible: Boolean) {
        val mainShellFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            ?.childFragmentManager?.primaryNavigationFragment as? MainShellFragment

        mainShellFragment?.setBottomNavVisibility(visible)
    }
}
