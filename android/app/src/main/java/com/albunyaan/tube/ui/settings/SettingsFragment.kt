package com.albunyaan.tube.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var binding: FragmentSettingsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSettingsBinding.bind(view)

        setupToolbar()
        setupListeners()
    }

    private fun setupToolbar() {
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupListeners() {
        binding?.root?.let { view ->
            // Get references to included layout items
            val languageItem = view.findViewById<View>(R.id.languageItem)
            val themeItem = view.findViewById<View>(R.id.themeItem)
            val downloadQualityItem = view.findViewById<View>(R.id.downloadQualityItem)
            val supportItem = view.findViewById<View>(R.id.supportItem)

            // Get switches
            val audioOnlySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.audioOnlySwitch)
            val backgroundPlaySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.backgroundPlaySwitch)
            val wifiOnlySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.wifiOnlySwitch)
            val safeModeSwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.safeModeSwitch)

            // Language setting - navigate to language selection
            languageItem?.setOnClickListener {
                // TODO: Navigate to language selection screen
            }

            // Theme setting - navigate to theme selection
            themeItem?.setOnClickListener {
                // TODO: Navigate to theme selection screen
            }

            // Download quality - navigate to quality selection
            downloadQualityItem?.setOnClickListener {
                // TODO: Navigate to quality selection screen
            }

            // Support Center - navigate to support
            supportItem?.setOnClickListener {
                // TODO: Navigate to support center or open URL
            }

            // Toggle switches - handle state changes
            audioOnlySwitch?.setOnCheckedChangeListener { _, isChecked ->
                // TODO: Save audio-only preference
            }

            backgroundPlaySwitch?.setOnCheckedChangeListener { _, isChecked ->
                // TODO: Save background play preference
            }

            wifiOnlySwitch?.setOnCheckedChangeListener { _, isChecked ->
                // TODO: Save WiFi-only preference
            }

            safeModeSwitch?.setOnCheckedChangeListener { _, isChecked ->
                // TODO: Save safe mode preference
            }

            // Set default values
            safeModeSwitch?.isChecked = true // Safe mode ON by default
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
