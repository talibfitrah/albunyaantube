package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentOnboardingBinding
import com.albunyaan.tube.onboarding.OnboardingPagerAdapter
import com.albunyaan.tube.onboarding.onboardingPages
import com.albunyaan.tube.preferences.SettingsPreferences
import kotlinx.coroutines.launch

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    private var binding: FragmentOnboardingBinding? = null
    private lateinit var settingsPreferences: SettingsPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsPreferences = SettingsPreferences(requireContext())
        binding = FragmentOnboardingBinding.bind(view).apply {
            // Set up ViewPager2
            viewPager.adapter = OnboardingPagerAdapter(onboardingPages)
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateIndicators(position)
                    updateButton(position)
                }
            })

            // Initial state
            updateIndicators(0)
            updateButton(0)

            // Button click handlers
            primaryCta.setOnClickListener {
                val currentPosition = viewPager.currentItem
                if (currentPosition < onboardingPages.size - 1) {
                    viewPager.currentItem = currentPosition + 1
                } else {
                    navigateToMain()
                }
            }

            skipButton.setOnClickListener { navigateToMain() }
        }
    }

    private fun updateIndicators(position: Int) {
        binding?.let { b ->
            val indicators = listOf(b.indicator1, b.indicator2, b.indicator3)
            Log.d("OnboardingFragment", "updateIndicators called with position: $position, indicators count: ${indicators.size}")
            indicators.forEachIndexed { index, indicator ->
                val background = if (index == position) {
                    R.drawable.onboarding_indicator_active
                } else {
                    R.drawable.onboarding_indicator_inactive
                }
                Log.d("OnboardingFragment", "Setting indicator $index to ${if (index == position) "active" else "inactive"}")
                indicator.setBackgroundResource(background)
            }
        }
    }

    private fun updateButton(position: Int) {
        binding?.primaryCta?.text = if (position == onboardingPages.size - 1) {
            getString(R.string.onboarding_get_started)
        } else {
            getString(R.string.onboarding_continue)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun navigateToMain() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsPreferences.setOnboardingCompleted(true)
        }
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.onboardingFragment) {
            navController.navigate(R.id.action_onboarding_to_main)
        }
    }
}
