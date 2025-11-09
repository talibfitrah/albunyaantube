package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash screen placeholder. Immediately transitions to onboarding once the view is ready
 * so the UI doesn't get stuck showing an indefinite spinner.
 */
class SplashFragment : Fragment(R.layout.fragment_splash) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            // Give the splash a frame before navigating so animations display cleanly.
            delay(250)
            if (findNavController().currentDestination?.id == R.id.splashFragment) {
                findNavController().navigate(R.id.action_splash_to_onboarding)
            }
        }
    }
}
