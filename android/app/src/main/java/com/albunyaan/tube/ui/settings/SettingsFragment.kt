package com.albunyaan.tube.ui.settings

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSettingsBinding
import com.albunyaan.tube.download.DownloadStorage
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.preferences.SettingsPreferences
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    @Inject
    lateinit var downloadStorage: DownloadStorage

    private var binding: FragmentSettingsBinding? = null
    private lateinit var preferences: SettingsPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSettingsBinding.bind(view)
        preferences = SettingsPreferences(requireContext())

        setupToolbar()
        setupFragmentResultListeners()
        loadPreferences()
        setupListeners()
        setupStorageManagement()
    }

    /**
     * Set up Fragment Result API listeners for dialog results.
     * This pattern survives process death unlike callback-based approaches.
     */
    private fun setupFragmentResultListeners() {
        // Language selection result
        childFragmentManager.setFragmentResultListener(
            LanguageSelectionDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            // Only process if user made a selection (not cancelled)
            val selectedLanguage = result.getString(LanguageSelectionDialog.RESULT_LANGUAGE)
                ?: return@setFragmentResultListener
            try {
                LocaleManager.saveAndApplyLocale(requireContext(), selectedLanguage)
                // Use Toast instead of Snackbar because locale change triggers Activity recreation,
                // which would destroy the Snackbar before it's visible. Toast survives recreation.
                Toast.makeText(requireContext(), R.string.settings_language_changed, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to change language", e)
                Toast.makeText(requireContext(), R.string.settings_language_change_failed, Toast.LENGTH_LONG).show()
            }
        }

        // Quality selection result
        childFragmentManager.setFragmentResultListener(
            QualitySelectionDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            // Only process if user made a selection (not cancelled)
            val selectedQuality = result.getString(QualitySelectionDialog.RESULT_QUALITY)
                ?: return@setFragmentResultListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    preferences.setDownloadQuality(selectedQuality)
                    binding?.root?.let { view ->
                        val qualityLabel = when (selectedQuality) {
                            "low" -> getString(R.string.settings_quality_low)
                            "high" -> getString(R.string.settings_quality_high)
                            else -> getString(R.string.settings_quality_medium)
                        }
                        Snackbar.make(view, getString(R.string.settings_quality_changed, qualityLabel), Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "Failed to change quality", e)
                    binding?.root?.let { view ->
                        Snackbar.make(view, R.string.settings_quality_change_failed, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
                Snackbar.make(view, R.string.settings_theme_coming_soon, Snackbar.LENGTH_SHORT).show()
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
            dialog.show(childFragmentManager, LanguageSelectionDialog.TAG)
        }
    }

    private fun showQualitySelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentQuality = preferences.downloadQuality.first()
            val dialog = QualitySelectionDialog.newInstance(currentQuality)
            dialog.show(childFragmentManager, QualitySelectionDialog.TAG)
        }
    }

    /**
     * ANDROID-DL-03: Storage Management
     * Shows device storage information (no artificial quota)
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

            updateStorageDisplay()
        }
    }

    private fun updateStorageDisplay() {
        binding?.root?.let { view ->
            val storageQuotaValue = view.findViewById<TextView>(R.id.storageQuotaValue)
            val storageQuotaProgress = view.findViewById<ProgressBar>(R.id.storageQuotaProgress)

            viewLifecycleOwner.lifecycleScope.launch {
                val downloadedBytes = downloadStorage.getCurrentDownloadSize()
                val availableBytes = downloadStorage.getAvailableDeviceStorage()
                val totalBytes = downloadStorage.getTotalDeviceStorage()

                val downloadedStr = Formatter.formatShortFileSize(requireContext(), downloadedBytes)
                val availableStr = Formatter.formatShortFileSize(requireContext(), availableBytes)
                val totalStr = Formatter.formatShortFileSize(requireContext(), totalBytes)

                // Calculate used device storage percentage (not downloads, but total device usage)
                val usedDeviceBytes = totalBytes - availableBytes
                val progress = ((usedDeviceBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)

                storageQuotaValue?.text = getString(R.string.settings_storage_format, downloadedStr, availableStr, totalStr)
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
        val options = arrayOf(
            getString(R.string.settings_storage_internal),
            getString(R.string.settings_storage_external)
        )
        val currentSelection = 0 // Internal storage is default

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_download_location)
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                when (which) {
                    0 -> {
                        binding?.root?.let { view ->
                            Snackbar.make(view, R.string.settings_storage_internal_selected, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        binding?.root?.let { view ->
                            Snackbar.make(view, R.string.settings_storage_external_not_implemented, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showClearDownloadsConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_clear_downloads_title)
            .setMessage(R.string.settings_clear_downloads_message)
            .setPositiveButton(R.string.settings_clear_downloads_confirm) { _, _ ->
                clearAllDownloads()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearAllDownloads() {
        viewLifecycleOwner.lifecycleScope.launch {
            val downloadDir = File(requireContext().filesDir, "downloads")
            val deletedCount = deleteDirectoryContents(downloadDir)

            binding?.root?.let { view ->
                Snackbar.make(view, getString(R.string.settings_files_cleared, deletedCount), Snackbar.LENGTH_SHORT).show()
            }

            updateStorageDisplay()
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
