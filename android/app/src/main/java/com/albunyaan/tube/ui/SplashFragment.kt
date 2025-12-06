package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.preferences.SettingsPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Splash screen placeholder. Checks onboarding completion status and navigates accordingly.
 * If onboarding is completed, goes directly to main. Otherwise, shows onboarding flow.
 */
class SplashFragment : Fragment(R.layout.fragment_splash) {

    private lateinit var settingsPreferences: SettingsPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsPreferences = SettingsPreferences(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            // Give the splash a frame before navigating so animations display cleanly.
            delay(250)

            val onboardingCompleted = settingsPreferences.onboardingCompleted.first()

            if (findNavController().currentDestination?.id == R.id.splashFragment) {
                if (onboardingCompleted) {
                    findNavController().navigate(R.id.action_splash_to_main)
                } else {
                    findNavController().navigate(R.id.action_splash_to_onboarding)
                }
            }
        }
    }
}
