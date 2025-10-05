package com.albunyaan.tube.ui.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import java.io.File

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
        setupStorageManagement()
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

    /**
     * ANDROID-DL-03: Storage Management
     */
    private fun setupStorageManagement() {
        binding?.root?.let { view ->
            val storageLocationItem = view.findViewById<View>(R.id.storageLocationItem)
            val clearDownloadsItem = view.findViewById<View>(R.id.clearDownloadsItem)

            storageLocationItem?.setOnClickListener {
                showStorageLocationDialog()
            }

            clearDownloadsItem?.setOnClickListener {
                showClearDownloadsConfirmation()
            }

            updateStorageQuota()
        }
    }

    private fun updateStorageQuota() {
        binding?.root?.let { view ->
            val storageQuotaValue = view.findViewById<TextView>(R.id.storageQuotaValue)
            val storageQuotaProgress = view.findViewById<ProgressBar>(R.id.storageQuotaProgress)

            viewLifecycleOwner.lifecycleScope.launch {
                val downloadDir = File(requireContext().filesDir, "downloads")
                val usedBytes = calculateDirectorySize(downloadDir)
                val quotaBytes = 500_000_000L // 500 MB default quota

                val usedStr = Formatter.formatShortFileSize(requireContext(), usedBytes)
                val quotaStr = Formatter.formatShortFileSize(requireContext(), quotaBytes)
                val progress = ((usedBytes.toDouble() / quotaBytes) * 100).toInt()

                storageQuotaValue?.text = "$usedStr of $quotaStr"
                storageQuotaProgress?.progress = progress
            }
        }
    }

    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists()) return 0
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    private fun showStorageLocationDialog() {
        val options = arrayOf("Internal Storage", "External SD Card (if available)")
        val currentSelection = 0 // Internal storage is default

        AlertDialog.Builder(requireContext())
            .setTitle("Download Location")
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                when (which) {
                    0 -> {
                        binding?.root?.let { view ->
                            Snackbar.make(view, "Using internal storage", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        binding?.root?.let { view ->
                            Snackbar.make(view, "External storage not yet implemented", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearDownloadsConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Downloads?")
            .setMessage("This will delete all downloaded videos and audio files. This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllDownloads()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllDownloads() {
        viewLifecycleOwner.lifecycleScope.launch {
            val downloadDir = File(requireContext().filesDir, "downloads")
            val deletedCount = deleteDirectoryContents(downloadDir)

            binding?.root?.let { view ->
                Snackbar.make(view, "Cleared $deletedCount files", Snackbar.LENGTH_SHORT).show()
            }

            updateStorageQuota()
        }
    }

    private fun deleteDirectoryContents(directory: File): Int {
        if (!directory.exists()) return 0
        var count = 0
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                count += deleteDirectoryContents(file)
                file.delete()
            } else {
                if (file.delete()) count++
            }
        }
        return count
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
