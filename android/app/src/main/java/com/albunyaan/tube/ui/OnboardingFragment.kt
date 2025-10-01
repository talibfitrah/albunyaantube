package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentOnboardingBinding

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    private var binding: FragmentOnboardingBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentOnboardingBinding.bind(view).apply {
            primaryCta.setOnClickListener { navigateToMain() }
            skipButton.setOnClickListener { navigateToMain() }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun navigateToMain() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.onboardingFragment) {
            navController.navigate(R.id.action_onboarding_to_main)
        }
    }
}
