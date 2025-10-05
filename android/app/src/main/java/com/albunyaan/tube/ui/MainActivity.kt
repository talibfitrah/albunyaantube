package com.albunyaan.tube.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.ActivityMainBinding
import com.albunyaan.tube.locale.LocaleManager

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

                    if (nestedNavController != null && nestedNavController.currentDestination?.id != R.id.homeFragment) {
                        // If not on home tab, navigate to home tab
                        nestedNavController.navigate(R.id.homeFragment)
                        return
                    }
                }

                // Default behavior: try to navigate up in main nav controller, or finish activity
                if (!navController.navigateUp()) {
                    finish()
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
