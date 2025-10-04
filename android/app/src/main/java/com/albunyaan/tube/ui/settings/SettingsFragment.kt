package com.albunyaan.tube.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSettingsBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.preferences.SettingsPreferences
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var binding: FragmentSettingsBinding? = null
    private lateinit var preferences: SettingsPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSettingsBinding.bind(view)
        preferences = SettingsPreferences(requireContext())

        setupToolbar()
        loadPreferences()
        setupListeners()
    }

    private fun setupToolbar() {
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun loadPreferences() {
        binding?.root?.let { view ->
            // Get switches
            val audioOnlySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.audioOnlySwitch)
            val backgroundPlaySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.backgroundPlaySwitch)
            val wifiOnlySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.wifiOnlySwitch)
            val safeModeSwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.safeModeSwitch)

            // Load saved preferences
            viewLifecycleOwner.lifecycleScope.launch {
                audioOnlySwitch?.isChecked = preferences.audioOnly.first()
                backgroundPlaySwitch?.isChecked = preferences.backgroundPlay.first()
                wifiOnlySwitch?.isChecked = preferences.wifiOnly.first()
                safeModeSwitch?.isChecked = preferences.safeMode.first()
            }
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

            // Language setting - show language selection dialog
            languageItem?.setOnClickListener {
                showLanguageSelectionDialog()
            }

            // Theme setting - show theme selection dialog
            themeItem?.setOnClickListener {
                Snackbar.make(view, "Theme selection coming soon", Snackbar.LENGTH_SHORT).show()
            }

            // Download quality - show quality selection dialog
            downloadQualityItem?.setOnClickListener {
                showQualitySelectionDialog()
            }

            // Support Center - navigate to About screen
            supportItem?.setOnClickListener {
                findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
            }

            // Toggle switches - handle state changes
            audioOnlySwitch?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences.setAudioOnly(isChecked)
                }
            }

            backgroundPlaySwitch?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences.setBackgroundPlay(isChecked)
                }
            }

            wifiOnlySwitch?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences.setWifiOnly(isChecked)
                }
            }

            safeModeSwitch?.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences.setSafeMode(isChecked)
                }
            }
        }
    }

    private fun showLanguageSelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentLanguage = preferences.locale.first()
            val dialog = LanguageSelectionDialog.newInstance(currentLanguage)
            dialog.setOnLanguageSelectedListener { selectedLanguage ->
                LocaleManager.saveAndApplyLocale(requireContext(), selectedLanguage)
                binding?.root?.let { view ->
                    Snackbar.make(view, "Language changed. Restart may be required.", Snackbar.LENGTH_LONG).show()
                }
            }
            dialog.show(childFragmentManager, "language_selection")
        }
    }

    private fun showQualitySelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentQuality = preferences.downloadQuality.first()
            val dialog = QualitySelectionDialog.newInstance(currentQuality)
            dialog.setOnQualitySelectedListener { selectedQuality ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences.setDownloadQuality(selectedQuality)
                    binding?.root?.let { view ->
                        val qualityLabel = when (selectedQuality) {
                            "low" -> "Low (360p)"
                            "high" -> "High (1080p)"
                            else -> "Medium (720p)"
                        }
                        Snackbar.make(view, "Download quality set to $qualityLabel", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.show(childFragmentManager, "quality_selection")
        }
    }


    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
