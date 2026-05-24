package com.albunyaan.tube.ui.settings.availableversions

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.update.ReleaseCatalogCache
import com.albunyaan.tube.update.ReleaseRow
import com.albunyaan.tube.update.RowState
import com.albunyaan.tube.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Drives the Available Updates picker screen. Merges [ReleaseCatalogCache.list] with
 * [ReleaseCatalogCache.summaries] and assigns a [RowState] to each release relative to
 * the currently installed build.
 *
 * Row state is determined by semver comparison via [UpdateChecker.isNewerVersion]:
 * a release whose version string is newer than the installed build is [RowState.Newer];
 * an exact match is [RowState.Current]; anything older is [RowState.Older].
 *
 * The Hilt-injected constructor takes only [ReleaseCatalogCache]. The secondary
 * constructor is for unit tests, which supply the installed version and locale
 * directly instead of relying on BuildConfig / Locale.getDefault().
 */
@HiltViewModel
class AvailableVersionsViewModel @Inject constructor(
    private val catalog: ReleaseCatalogCache,
) : ViewModel() {

    @VisibleForTesting
    internal var installedVersionName: String = BuildConfig.VERSION_NAME

    @VisibleForTesting
    internal var locale: String = Locale.getDefault().language

    /** Secondary constructor used by unit tests only. */
    internal constructor(
        catalog: ReleaseCatalogCache,
        installedVersionName: String,
        locale: String,
    ) : this(catalog) {
        this.installedVersionName = installedVersionName
        this.locale = locale
    }

    private val _rows = MutableStateFlow<List<ReleaseRow>>(emptyList())
    val rows: StateFlow<List<ReleaseRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val releases = catalog.list(limit = 5)
                val summaries = catalog.summaries()
                _rows.value = releases.map { info ->
                    ReleaseRow(
                        info = info,
                        localizedSummary = summaries.summaryFor(info.versionName, locale),
                        state = computeState(info.versionName, installedVersionName),
                    )
                }
            } finally {
                _loading.value = false
            }
        }
    }

    private fun computeState(remote: String, installed: String): RowState = when {
        remote == installed -> RowState.Current
        UpdateChecker.isNewerVersion(remote, installed) -> RowState.Newer
        else -> RowState.Older
    }
}
