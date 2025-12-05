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
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val navController: NavController by lazy {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        host.navController
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply stored locale before super.onCreate to ensure proper locale is applied
        LocaleManager.applyStoredLocale(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        navController // trigger lazy init if toolbar needed later

        // Delay handling intent until after navigation is ready
        if (intent?.action == PlaybackService.ACTION_OPEN_PLAYER) {
            // Wait for the nav graph to be ready, then navigate
            binding.root.post {
                handleIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == PlaybackService.ACTION_OPEN_PLAYER) {
            // Only navigate if we're not on splash screen
            try {
                val currentDest = navController.currentDestination?.id
                if (currentDest == R.id.mainShellFragment || currentDest == R.id.playerFragment) {
                    // We're already in the app, safe to navigate to player
                    navController.navigate(R.id.playerFragment)
                } else {
                    // We're on splash, wait for it to finish then navigate
                    // The splash screen will auto-navigate to mainShell, then we navigate to player
                    navController.addOnDestinationChangedListener { _, destination, _ ->
                        if (destination.id == R.id.mainShellFragment) {
                            // Now we can safely navigate to player
                            binding.root.postDelayed({
                                try {
                                    navController.navigate(R.id.playerFragment)
                                } catch (e: Exception) {
                                    // Ignore navigation errors
                                }
                            }, 100)
                        }
                    }
                }
            } catch (e: Exception) {
                // If navigation fails, just ignore - user can manually open player
                android.util.Log.e("MainActivity", "Failed to navigate to player", e)
            }
        }
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
