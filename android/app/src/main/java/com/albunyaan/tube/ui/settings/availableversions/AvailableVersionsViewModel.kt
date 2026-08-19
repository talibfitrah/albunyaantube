package com.albunyaan.tube.ui.settings.availableversions

import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.update.ReleaseCatalogCache
import com.albunyaan.tube.update.ReleaseRow
import com.albunyaan.tube.update.RowState
import com.albunyaan.tube.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    // Test-only override. Production reads the in-app locale at every load()
    // call via [resolveLocale] so the picker reacts to a language change made
    // while the ViewModel survived a config rotation (cubic R6 P2).
    @VisibleForTesting
    internal var locale: String? = null

    /** Resolves the locale tag for summary lookup: test override → in-app override → system. */
    private fun resolveLocale(): String =
        locale ?: AppCompatDelegate.getApplicationLocales()[0]?.language
            ?: Locale.getDefault().language

    /** Secondary constructor used by unit tests only. */
    internal constructor(
        catalog: ReleaseCatalogCache,
        installedVersionName: String,
        locale: String,
    ) : this(catalog) {
        this.installedVersionName = installedVersionName
        // Setting [locale] non-null disables the per-call resolution path so
        // unit tests get deterministic locale behaviour without invoking
        // [AppCompatDelegate].
        this.locale = locale
    }

    private val _rows = MutableStateFlow<List<ReleaseRow>>(emptyList())
    val rows: StateFlow<List<ReleaseRow>> = _rows.asStateFlow()

    // Initialised true so the picker's `combine` collector sees `loading=true`
    // on its first emission and renders the spinner instead of the "no releases"
    // empty state — fixes the flicker before the load() coroutine flips the flag
    // (cubic R1 C3).
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // Coalesces concurrent load() calls. A re-entry while a previous load is
    // still in-flight discards the new call; without this the second coroutine
    // could resolve faster and the first would overwrite it with stale data
    // (cubic R1 C5).
    private var inFlightLoad: Job? = null

    /**
     * Triggers a fetch + render of the picker rows.
     *
     * Thread-safety: MUST be called from the Main thread. The [inFlightLoad]
     * re-entry guard is not atomic; concurrent off-Main invocations could
     * launch duplicate fetches (cubic R6 P2). Fragment / Activity callers
     * default to Main, so the constraint is met in practice.
     */
    fun load() {
        if (inFlightLoad?.isActive == true) return
        // Re-resolve the locale per call so a language change in Settings that
        // does not destroy the ViewModel (config-change survival) is picked up
        // on the next refresh (cubic R6 P2).
        val localeTag = resolveLocale()
        inFlightLoad = viewModelScope.launch {
            _loading.value = true
            try {
                // Two different hosts, cached independently — fetch them together so
                // splitting the cache did not make the picker twice as slow to open.
                val (releases, summaries) = coroutineScope {
                    val r = async { catalog.list(limit = PICKER_PAGE_SIZE) }
                    val s = async { catalog.summaries() }
                    r.await() to s.await()
                }
                _rows.value = releases.map { info ->
                    val remote = info.versionName.trim()
                    ReleaseRow(
                        info = info,
                        localizedSummary = summaries.summaryFor(info.versionName, localeTag),
                        state = computeState(remote, installedVersionName.trim()),
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

    private companion object {
        /** Number of releases the picker renders. Was a default param on
         *  [ReleaseCatalogCache.list]/[UpdateChecker.listReleases]; lifted to a
         *  caller-side constant so the API surface no longer carries a "configurable
         *  setting nobody changes" (initial bloat-audit finding #5). */
        const val PICKER_PAGE_SIZE = 5
    }
}
