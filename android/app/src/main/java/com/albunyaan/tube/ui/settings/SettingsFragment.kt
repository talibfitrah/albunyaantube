package com.albunyaan.tube.ui.settings

import android.content.Context
import android.os.Bundle
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
import androidx.appcompat.app.AppCompatDelegate
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
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = context ?: return@launch  // Guard against detached fragment
                try {
                    // Use suspend version to ensure atomic persist + apply
                    LocaleManager.saveAndApplyLocale(ctx, selectedLanguage)
                    // Use Toast instead of Snackbar because locale change triggers Activity recreation,
                    // which would destroy the Snackbar before it's visible. Toast survives recreation.
                    Toast.makeText(ctx, R.string.settings_language_changed, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "Failed to change language", e)
                    context?.let { Toast.makeText(it, R.string.settings_language_change_failed, Toast.LENGTH_LONG).show() }
                }
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
            val ctx = context ?: return@setFragmentResultListener  // Guard against detached fragment
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    preferences.setDownloadQuality(selectedQuality)
                    binding?.root?.let { view ->
                        val qualityLabel = getQualityDisplayName(ctx, selectedQuality)
                        // Update the displayed value
                        view.findViewById<TextView>(R.id.downloadQualityValue)?.text = qualityLabel
                        Snackbar.make(view, ctx.getString(R.string.settings_quality_changed, qualityLabel), Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "Failed to change quality", e)
                    binding?.root?.let { view ->
                        Snackbar.make(view, R.string.settings_quality_change_failed, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Theme selection result
        childFragmentManager.setFragmentResultListener(
            ThemeSelectionDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            // Only process if user made a selection (not cancelled)
            val selectedTheme = result.getString(ThemeSelectionDialog.RESULT_THEME)
                ?: return@setFragmentResultListener
            val ctx = context ?: return@setFragmentResultListener  // Guard against detached fragment
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Persist first
                    preferences.setTheme(selectedTheme)

                    // Apply theme BEFORE updating subtitle so getSystemTheme() reflects the new mode.
                    // Note: setDefaultNightMode() may trigger activity recreation, in which case
                    // loadPreferences() will show the correct value on recreation anyway.
                    applyTheme(selectedTheme)

                    // Update the displayed value (after apply, so resolved theme is correct)
                    binding?.root?.findViewById<TextView>(R.id.themeValue)?.text = getThemeDisplayName(ctx, selectedTheme)

                    // Use Toast because activity may recreate
                    Toast.makeText(ctx, R.string.settings_theme_changed, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "Failed to change theme", e)
                    context?.let { Toast.makeText(it, R.string.settings_theme_change_failed, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    /**
     * Apply the selected theme using AppCompatDelegate.
     * @param themeSelection THEME_SYSTEM, THEME_LIGHT, or THEME_DARK
     */
    private fun applyTheme(themeSelection: String) {
        val mode = when (themeSelection) {
            SettingsPreferences.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            SettingsPreferences.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
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

            // Get value TextViews for language, theme, and download quality
            val languageValue = view.findViewById<TextView>(R.id.languageValue)
            val themeValue = view.findViewById<TextView>(R.id.themeValue)
            val downloadQualityValue = view.findViewById<TextView>(R.id.downloadQualityValue)

            // Capture context before coroutine to avoid requireContext() in suspended context
            val ctx = context ?: return

            // Load saved preferences
            viewLifecycleOwner.lifecycleScope.launch {
                // Load switch states
                audioOnlySwitch?.isChecked = preferences.audioOnly.first()
                backgroundPlaySwitch?.isChecked = preferences.backgroundPlay.first()
                wifiOnlySwitch?.isChecked = preferences.wifiOnly.first()
                safeModeSwitch?.isChecked = preferences.safeMode.first()

                // Load language selection and display
                val localeSelection = preferences.localeSelection.first()
                languageValue?.text = LocaleManager.getLanguageDisplayNameWithResolved(ctx, localeSelection)

                // Load theme and display
                val currentTheme = preferences.theme.first()
                themeValue?.text = getThemeDisplayName(ctx, currentTheme)

                // Load download quality and display
                val currentQuality = preferences.downloadQuality.first()
                downloadQualityValue?.text = getQualityDisplayName(ctx, currentQuality)
            }
        }
    }

    /**
     * Get localized display name for theme.
     * For THEME_SYSTEM selection, shows the resolved theme (e.g., "System default (Light)").
     *
     * @param ctx Context to use for string resources (captured before coroutine to avoid crash)
     */
    private fun getThemeDisplayName(ctx: Context, theme: String): String {
        return when (theme) {
            SettingsPreferences.THEME_SYSTEM -> {
                val resolvedTheme = SettingsPreferences.getSystemTheme(ctx)
                val resolvedName = when (resolvedTheme) {
                    SettingsPreferences.THEME_DARK -> ctx.getString(R.string.settings_theme_dark)
                    else -> ctx.getString(R.string.settings_theme_light)
                }
                ctx.getString(R.string.settings_theme_system_resolved, resolvedName)
            }
            SettingsPreferences.THEME_LIGHT -> ctx.getString(R.string.settings_theme_light)
            SettingsPreferences.THEME_DARK -> ctx.getString(R.string.settings_theme_dark)
            else -> ctx.getString(R.string.settings_theme_system)
        }
    }

    /**
     * Get localized display name for download quality.
     *
     * @param ctx Context to use for string resources (captured before coroutine to avoid crash)
     */
    private fun getQualityDisplayName(ctx: Context, quality: String): String {
        return when (quality) {
            "low" -> ctx.getString(R.string.settings_quality_low)
            "high" -> ctx.getString(R.string.settings_quality_high)
            else -> ctx.getString(R.string.settings_quality_medium)
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
                showThemeSelectionDialog()
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
            // Use localeSelection to get the user's choice ("system", "en", "ar", "nl")
            val currentSelection = preferences.localeSelection.first()
            val dialog = LanguageSelectionDialog.newInstance(currentSelection)
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

    private fun showThemeSelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentTheme = preferences.theme.first()
            if (!isAdded) return@launch
            val dialog = ThemeSelectionDialog.newInstance(currentTheme)
            dialog.show(childFragmentManager, ThemeSelectionDialog.TAG)
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

            // Capture context before coroutine to avoid requireContext() in suspended context
            val ctx = context ?: return

            viewLifecycleOwner.lifecycleScope.launch {
                val downloadedBytes = downloadStorage.getCurrentDownloadSize()
                val availableBytes = downloadStorage.getAvailableDeviceStorage()
                val totalBytes = downloadStorage.getTotalDeviceStorage()

                val downloadedStr = Formatter.formatShortFileSize(ctx, downloadedBytes)
                val availableStr = Formatter.formatShortFileSize(ctx, availableBytes)
                val totalStr = Formatter.formatShortFileSize(ctx, totalBytes)

                // Calculate used device storage percentage (not downloads, but total device usage)
                val usedDeviceBytes = totalBytes - availableBytes
                val progress = ((usedDeviceBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)

                storageQuotaValue?.text = ctx.getString(R.string.settings_storage_format, downloadedStr, availableStr, totalStr)
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
        val ctx = context ?: return  // Guard against detached fragment
        val options = arrayOf(
            ctx.getString(R.string.settings_storage_internal),
            ctx.getString(R.string.settings_storage_external)
        )
        val currentSelection = 0 // Internal storage is default

        MaterialAlertDialogBuilder(ctx)
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
        val ctx = context ?: return  // Guard against detached fragment
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_clear_downloads_title)
            .setMessage(R.string.settings_clear_downloads_message)
            .setPositiveButton(R.string.settings_clear_downloads_confirm) { _, _ ->
                clearAllDownloads()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearAllDownloads() {
        // Capture context before coroutine to avoid requireContext() in suspended context
        val ctx = context ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val downloadDir = File(ctx.filesDir, "downloads")
            val deletedCount = deleteDirectoryContents(downloadDir)

            binding?.root?.let { view ->
                Snackbar.make(view, ctx.getString(R.string.settings_files_cleared, deletedCount), Snackbar.LENGTH_SHORT).show()
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
