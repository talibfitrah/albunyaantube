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
                // Find the index of the installed version in the list. Releases are returned
                // newest-first by the GitHub API, so everything before the installed row is
                // Newer and everything after is Older. isNewerVersion is used as a secondary
                // heuristic for versions that don't appear in the list (shouldn't happen in
                // practice but guards the Current-not-found case).
                val installedIdx = releases.indexOfFirst { it.versionName == installedVersionName }
                _rows.value = releases.mapIndexed { idx, info ->
                    ReleaseRow(
                        info = info,
                        publishedAt = null, // Task 7 will thread publishedAt through UpdateInfo
                        localizedSummary = summaries.summaryFor(info.versionName, locale),
                        state = computeState(info.versionName, idx, installedIdx),
                    )
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Assigns row state based on list position (primary) and semver comparison (fallback).
     *
     * List position is authoritative because GitHub returns releases newest-first.
     * Items appearing before the installed entry are Newer; items after are Older.
     * When the installed version is absent from the list, we fall back to
     * [UpdateChecker.isNewerVersion] — this covers the edge case where the running
     * build pre-dates all listed releases.
     */
    private fun computeState(remote: String, idx: Int, installedIdx: Int): RowState = when {
        remote == installedVersionName -> RowState.Current
        installedIdx >= 0 -> if (idx < installedIdx) RowState.Newer else RowState.Older
        UpdateChecker.isNewerVersion(remote, installedVersionName) -> RowState.Newer
        else -> RowState.Older
    }
}
