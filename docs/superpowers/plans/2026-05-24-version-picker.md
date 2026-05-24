# Available Updates (Version Picker) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings child screen ("Available updates") that lists up to 5 newest GitHub releases with localized one-line summaries and lets users jump-install any release newer than the one they have.

**Architecture:** New `AvailableVersionsFragment` reached from `SettingsFragment`. Data path: `AvailableVersionsViewModel` → `ReleaseCatalogCache` (new `@Singleton` that lifts the existing 5-min probe cache out of `UpdatePromptFlow`) → either `UpdateChecker.listReleases(limit)` (new method, GitHub Releases API) or `ReleaseSummaryFetcher` (new, `releases-meta.json` from repo root). Row "Install" reuses the existing `UpdatePromptFlow.showUpdateDialogAndAwait()` install path. Play Store installs hide the row entirely via an extracted `InstallSource` helper. Row state (current / newer / older) is computed using the existing `UpdateChecker.isNewerVersion()` semver comparator — `UpdateInfo` has only `versionName`, no `versionCode`.

**Tech Stack:** Kotlin, Android (SDK 35), Hilt, Coroutines + Flow, Moshi (JSON), OkHttp, Material Components, AndroidX Navigation. JUnit 4 + Mockito-Kotlin for unit tests.

**Spec:** `docs/superpowers/specs/2026-05-24-version-picker-design.md`. Read it first.

**Ticket prefix for commits:** `[FEAT]` for new code, `[REFACTOR]` for the cache extraction, `[DOCS]` for the JSON backfill and spec. The body of each commit message should mention `ANDROID-VERSIONS-01` for tracing.

---

## File Structure

**New files:**
- `android/app/src/main/java/com/albunyaan/tube/update/InstallSource.kt` — Play Store install detection helper.
- `android/app/src/main/java/com/albunyaan/tube/update/ReleaseSummaryFetcher.kt` — fetches `releases-meta.json` from the repo's `main` branch.
- `android/app/src/main/java/com/albunyaan/tube/update/ReleaseCatalogCache.kt` — `@Singleton` cache that owns the 5-minute snapshot for both splash and the picker.
- `android/app/src/main/java/com/albunyaan/tube/update/ReleaseRow.kt` — data class + `RowState` sealed enum used by the picker.
- `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsFragment.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModel.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsAdapter.kt`
- `android/app/src/main/res/layout/fragment_available_versions.xml`
- `android/app/src/main/res/layout/item_available_version.xml`
- `android/app/src/main/res/layout/settings_item_available_versions.xml` — Settings row (mirrors `settings_item_update_check.xml`).
- `releases-meta.json` — at repo root.
- `android/app/src/test/java/com/albunyaan/tube/update/InstallSourceTest.kt`
- `android/app/src/test/java/com/albunyaan/tube/update/ReleaseSummaryFetcherTest.kt`
- `android/app/src/test/java/com/albunyaan/tube/update/ReleaseCatalogCacheTest.kt`
- `android/app/src/test/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModelTest.kt`

**Modified files:**
- `android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt` — add `listReleases(limit: Int): Result<List<UpdateInfo>>`, replace inlined `isInstalledFromPlayStore()` with the `InstallSource` helper.
- `android/app/src/main/java/com/albunyaan/tube/update/UpdatePromptFlow.kt` — delegate `checkForUpdate()` to `ReleaseCatalogCache.latest()`; remove `cachedProbe` field and `PROBE_CACHE_TTL_MS` (now owned by the cache).
- `android/app/src/test/java/com/albunyaan/tube/update/UpdatePromptFlowTest.kt` — add coverage that `showUpdateDialogAndAwait` works with a synthesized non-latest `UpdateInfo`.
- `android/app/src/main/java/com/albunyaan/tube/ui/settings/SettingsFragment.kt` — wire new row click; hide it when `InstallSource.isPlayStore()` is true.
- `android/app/src/main/res/layout/fragment_settings.xml` — `<include layout="@layout/settings_item_available_versions"/>` between Support and Update Check.
- `android/app/src/main/res/navigation/main_tabs_nav.xml` — new `availableVersionsFragment` destination + action from `settingsFragment`.
- `android/app/src/main/res/values/strings.xml` + `values-ar/strings.xml` + `values-nl/strings.xml` — new strings.
- `CLAUDE.md` — add the `releases-meta.json` step to the release checklist.

---

## Task 1: Extract `InstallSource` helper

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/update/InstallSource.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt` (lines 47–73 of current file — replace inlined `isInstalledFromPlayStore`)
- Test: `android/app/src/test/java/com/albunyaan/tube/update/InstallSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/albunyaan/tube/update/InstallSourceTest.kt
package com.albunyaan.tube.update

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class InstallSourceTest {

    @Test
    fun `isPlayStore returns true when installer is com_android_vending`() {
        val pm = mock<PackageManager>()
        val info = mock<InstallSourceInfo> {
            whenever(it.installingPackageName).thenReturn("com.android.vending")
        }
        whenever(pm.getInstallSourceInfo("pkg")).thenReturn(info)
        val ctx = mock<Context> {
            whenever(it.packageName).thenReturn("pkg")
            whenever(it.packageManager).thenReturn(pm)
        }

        assertTrue(InstallSource(ctx).isPlayStore())
    }

    @Test
    fun `isPlayStore returns false for sideloaded install`() {
        val pm = mock<PackageManager>()
        val info = mock<InstallSourceInfo> {
            whenever(it.installingPackageName).thenReturn(null)
        }
        whenever(pm.getInstallSourceInfo("pkg")).thenReturn(info)
        val ctx = mock<Context> {
            whenever(it.packageName).thenReturn("pkg")
            whenever(it.packageManager).thenReturn(pm)
        }

        assertFalse(InstallSource(ctx).isPlayStore())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.InstallSourceTest'`
Expected: FAIL with "Unresolved reference: InstallSource".

- [ ] **Step 3: Implement `InstallSource`**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/update/InstallSource.kt
package com.albunyaan.tube.update

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports how the app was installed. Used to suppress GitHub-based update affordances
 * for Play Store installs (Play manages its own updates; sideloading from GitHub on top
 * of a Play install would break auto-updates).
 *
 * Extracted from UpdateChecker so non-update callers (e.g. SettingsFragment hiding the
 * "Available updates" row) don't need to pull in the whole update package.
 */
@Singleton
class InstallSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isPlayStore(): Boolean {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        return installer == PLAY_STORE_INSTALLER
    }

    private companion object {
        const val PLAY_STORE_INSTALLER = "com.android.vending"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.InstallSourceTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Replace inlined check in `UpdateChecker`**

In `android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt`:

Replace the constructor:
```kotlin
class UpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
```
with:
```kotlin
class UpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val installSource: InstallSource
) {
```

Remove the existing `isInstalledFromPlayStore()` private function (currently lines 62–73). Replace its single call site:
```kotlin
        if (isInstalledFromPlayStore()) {
```
with:
```kotlin
        if (installSource.isPlayStore()) {
```

Remove the now-unused imports: `import android.content.Context`, `import android.os.Build`, `import dagger.hilt.android.qualifiers.ApplicationContext`.

- [ ] **Step 6: Run the whole update test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.*'`
Expected: PASS for `InstallSourceTest`, `UpdateCheckerTest`, `UpdatePromptFlowTest`. If `UpdateCheckerTest` breaks due to the constructor change, update its mocks to inject a fake `InstallSource` whose `isPlayStore()` returns `false`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/update/InstallSource.kt \
        android/app/src/test/java/com/albunyaan/tube/update/InstallSourceTest.kt \
        android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt \
        android/app/src/test/java/com/albunyaan/tube/update/UpdateCheckerTest.kt
git commit -m "[REFACTOR]: extract InstallSource helper from UpdateChecker

ANDROID-VERSIONS-01 prep: SettingsFragment needs the Play Store check
to hide the new Available Updates row without pulling in UpdateChecker.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Add `UpdateChecker.listReleases(limit)`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt`
- Test: `android/app/src/test/java/com/albunyaan/tube/update/UpdateCheckerTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `UpdateCheckerTest.kt`:
```kotlin
@Test
fun `listReleases returns up to limit releases sorted newest-first with APK assets only`() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody("""
        [
          {"tag_name":"v1.0.0-beta.14","name":"beta-14","body":"","prerelease":true,
           "assets":[{"name":"app.apk","browser_download_url":"https://example/14.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]},
          {"tag_name":"v1.0.0-beta.13","name":"beta-13","body":"","prerelease":true,
           "assets":[{"name":"app.apk","browser_download_url":"https://example/13.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]},
          {"tag_name":"v1.0.0-beta.12","name":"beta-12","body":"","prerelease":true,
           "assets":[]},
          {"tag_name":"v1.0.0-beta.11","name":"beta-11","body":"","prerelease":true,
           "assets":[{"name":"app.apk","browser_download_url":"https://example/11.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]}
        ]
    """.trimIndent()))
    server.start()

    val checker = UpdateChecker(
        okHttpClient = OkHttpClient(),
        installSource = mock { on { isPlayStore() } doReturn false }
    )
    // Inject the mock server URL via a test-only constant override OR add a
    // constructor param for the base URL — implementer's choice; see Step 3.
    val result = checker.listReleases(limit = 5, baseUrlOverride = server.url("/").toString())

    val info = result.getOrThrow()
    assertEquals(3, info.size)  // beta-12 dropped (no APK asset)
    assertEquals("1.0.0-beta.14", info[0].versionName)
    assertEquals("1.0.0-beta.13", info[1].versionName)
    assertEquals("1.0.0-beta.11", info[2].versionName)
    server.shutdown()
}

@Test
fun `listReleases returns empty on Play Store install`() = runTest {
    val checker = UpdateChecker(
        okHttpClient = OkHttpClient(),
        installSource = mock { on { isPlayStore() } doReturn true }
    )
    val result = checker.listReleases(limit = 5)
    assertTrue(result.getOrThrow().isEmpty())
}
```

(If `MockWebServer` is not already on the test classpath, add `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` to `android/app/build.gradle.kts` in this step.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.UpdateCheckerTest.listReleases*'`
Expected: FAIL with "Unresolved reference: listReleases".

- [ ] **Step 3: Implement `listReleases`**

Add to `UpdateChecker.kt`:

```kotlin
@JsonClass(generateAdapter = true)
internal data class GithubReleaseListItemDto(
    val tag_name: String,
    val name: String?,
    val body: String?,
    val prerelease: Boolean,
    val assets: List<GithubAssetDto>
)

// Add to the class body:
private val listAdapter = moshi.adapter<List<GithubReleaseListItemDto>>(
    com.squareup.moshi.Types.newParameterizedType(List::class.java, GithubReleaseListItemDto::class.java)
)

/**
 * Returns up to [limit] most-recent releases (newest first) that ship an APK asset.
 * Filters out Play Store installs, releases without an APK, and (when the running
 * build is stable) pre-releases — matching the rules in [checkForUpdate]. Failure
 * to reach GitHub returns Result.failure; an empty list is a successful state.
 *
 * [baseUrlOverride] exists for tests — production callers must not pass it.
 */
suspend fun listReleases(
    limit: Int = 5,
    baseUrlOverride: String? = null
): Result<List<UpdateInfo>> = withContext(Dispatchers.IO) {
    if (installSource.isPlayStore()) return@withContext Result.success(emptyList())
    runCatching {
        val base = baseUrlOverride ?: "https://api.github.com/"
        val url = "${base.trimEnd('/')}/repos/$GITHUB_REPO/releases?per_page=$limit"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub releases API returned HTTP ${response.code}")
                return@use emptyList<UpdateInfo>()
            }
            val body = response.body?.string() ?: return@use emptyList<UpdateInfo>()
            val list = listAdapter.fromJson(body) ?: return@use emptyList<UpdateInfo>()
            val currentIsPrerelease = BuildConfig.VERSION_NAME.contains('-')
            list.asSequence()
                .filterNot { it.prerelease && !currentIsPrerelease }
                .mapNotNull { release ->
                    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                        ?: return@mapNotNull null
                    UpdateInfo(
                        versionName = release.tag_name.removePrefix("v").removePrefix("V"),
                        releaseName = release.name ?: release.tag_name,
                        releaseNotes = release.body.orEmpty(),
                        apkUrl = apk.browser_download_url,
                        apkSizeBytes = apk.size
                    )
                }
                .take(limit)
                .toList()
        }
    }
}

// Add to companion object:
private const val GITHUB_REPO = "talibfitrah/albunyaantube"
```

Note: `GITHUB_REPO` is already declared elsewhere (currently inlined in `RELEASES_URL`). Hoist it into the companion object as a single named constant and update `RELEASES_URL` to use it:
```kotlin
private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.UpdateCheckerTest'`
Expected: PASS (existing tests + 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt \
        android/app/src/test/java/com/albunyaan/tube/update/UpdateCheckerTest.kt \
        android/app/build.gradle.kts
git commit -m "[FEAT]: UpdateChecker.listReleases for version picker

ANDROID-VERSIONS-01: returns up to N most-recent releases that ship an
APK asset, honouring the same stable-vs-prerelease channel rule as the
single-release check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `ReleaseSummaryFetcher`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/update/ReleaseSummaryFetcher.kt`
- Test: `android/app/src/test/java/com/albunyaan/tube/update/ReleaseSummaryFetcherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/albunyaan/tube/update/ReleaseSummaryFetcherTest.kt
package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSummaryFetcherTest {

    @Test
    fun `parses well-formed JSON and exposes per-locale summary`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {
              "1.0.0-beta.14": {
                "en": "English summary.",
                "ar": "الملخص العربي.",
                "nl": "Nederlandse samenvatting."
              }
            }
        """.trimIndent()))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient(), urlOverride = server.url("/meta").toString())
        val summaries = fetcher.load()

        assertEquals("English summary.", summaries.summaryFor("1.0.0-beta.14", "en"))
        assertEquals("الملخص العربي.", summaries.summaryFor("1.0.0-beta.14", "ar"))
        server.shutdown()
    }

    @Test
    fun `missing locale falls back to en`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {"1.0.0-beta.14": {"en": "Only English."}}
        """.trimIndent()))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient(), urlOverride = server.url("/meta").toString())
        val summaries = fetcher.load()

        assertEquals("Only English.", summaries.summaryFor("1.0.0-beta.14", "nl"))
        server.shutdown()
    }

    @Test
    fun `missing version returns null`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"1.0.0-beta.99": {"en": "x"}}"""))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient(), urlOverride = server.url("/meta").toString())
        val summaries = fetcher.load()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }

    @Test
    fun `404 returns empty map without throwing`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient(), urlOverride = server.url("/meta").toString())
        val summaries = fetcher.load()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }

    @Test
    fun `malformed JSON returns empty map`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not json at all"))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient(), urlOverride = server.url("/meta").toString())
        val summaries = fetcher.load()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.ReleaseSummaryFetcherTest'`
Expected: FAIL with "Unresolved reference: ReleaseSummaryFetcher".

- [ ] **Step 3: Implement `ReleaseSummaryFetcher`**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/update/ReleaseSummaryFetcher.kt
package com.albunyaan.tube.update

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches releases-meta.json from the repo's main branch and exposes localized
 * one-line summaries per release tag. Authoring lives in the repo so the release
 * process is a single git operation; the app reads via raw.githubusercontent.com
 * without depending on a backend.
 *
 * Failure modes (404, 5xx, timeout, parse error) all degrade to an empty map.
 * Missing locale falls back to "en"; missing version returns null. The picker
 * renders rows with no summary when the lookup misses.
 */
@Singleton
class ReleaseSummaryFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val urlOverride: String? = null
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapType = Types.newParameterizedType(
        Map::class.java, String::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )
    private val adapter = moshi.adapter<Map<String, Map<String, String>>>(mapType)

    suspend fun load(): ReleaseSummaries = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(urlOverride ?: META_URL).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "releases-meta.json returned HTTP ${response.code}")
                    return@use ReleaseSummaries(emptyMap())
                }
                val body = response.body?.string() ?: return@use ReleaseSummaries(emptyMap())
                ReleaseSummaries(adapter.fromJson(body) ?: emptyMap())
            }
        }.getOrElse {
            Log.w(TAG, "releases-meta.json fetch failed: ${it.message}")
            ReleaseSummaries(emptyMap())
        }
    }

    private companion object {
        const val TAG = "ReleaseSummaryFetcher"
        const val META_URL =
            "https://raw.githubusercontent.com/talibfitrah/albunyaantube/main/releases-meta.json"
    }
}

/**
 * Immutable view over the per-version, per-locale summary map. Resolution rules:
 *  - exact (version, locale) match → that string
 *  - exact version match but missing locale → fall back to "en"
 *  - missing version → null (caller renders empty subtitle)
 */
data class ReleaseSummaries(
    private val byVersion: Map<String, Map<String, String>>
) {
    fun summaryFor(versionName: String, locale: String): String? {
        val perLocale = byVersion[versionName] ?: return null
        return perLocale[locale] ?: perLocale["en"]
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.ReleaseSummaryFetcherTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/update/ReleaseSummaryFetcher.kt \
        android/app/src/test/java/com/albunyaan/tube/update/ReleaseSummaryFetcherTest.kt
git commit -m "[FEAT]: ReleaseSummaryFetcher for localized release notes

ANDROID-VERSIONS-01: pulls releases-meta.json from raw.githubusercontent.com.
All failure modes degrade silently to empty map so the picker always renders.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `ReleaseRow` data model

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/update/ReleaseRow.kt`

(No standalone test — the type is exercised by `AvailableVersionsViewModelTest` in Task 6.)

- [ ] **Step 1: Create the data file**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/update/ReleaseRow.kt
package com.albunyaan.tube.update

import java.time.Instant

/**
 * One row in the Available Updates screen. Carries the full UpdateInfo so the
 * future downgrade flow (out of scope today) can drive uninstall + reinstall
 * without a re-fetch.
 */
data class ReleaseRow(
    val info: UpdateInfo,
    val publishedAt: Instant?,
    val localizedSummary: String?,
    val state: RowState
)

sealed class RowState {
    /** This release matches the running build — show "Installed" chip, no action. */
    object Current : RowState()

    /** This release is strictly newer than the running build — show "Install" button. */
    object Newer : RowState()

    /**
     * This release is older than the running build OR the comparator could not
     * decide. Shown disabled with "Downgrade not available" subtitle. Future
     * backwards-compat work will give this state an active CTA.
     */
    object Older : RowState()
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/update/ReleaseRow.kt
git commit -m "[FEAT]: ReleaseRow + RowState model for version picker

ANDROID-VERSIONS-01: covered by AvailableVersionsViewModelTest in a
follow-up commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `ReleaseCatalogCache` + `UpdatePromptFlow` migration

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/update/ReleaseCatalogCache.kt`
- Test: `android/app/src/test/java/com/albunyaan/tube/update/ReleaseCatalogCacheTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/update/UpdatePromptFlow.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/albunyaan/tube/update/ReleaseCatalogCacheTest.kt
package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class ReleaseCatalogCacheTest {

    private val release14 = UpdateInfo("1.0.0-beta.14", "beta-14", "", "https://x/14.apk", 1)
    private val release13 = UpdateInfo("1.0.0-beta.13", "beta-13", "", "https://x/13.apk", 1)

    @Test
    fun `first call hits network second call within TTL serves from cache`() = runTest {
        var now = 1_000L
        val clock = { now }
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any(), anyOrNull()) } doReturn Result.success(listOf(release14, release13))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries, clock)

        cache.list(limit = 5)
        cache.list(limit = 5)

        verify(checker, times(1)).listReleases(any(), anyOrNull())
        verify(summaries, times(1)).load()
    }

    @Test
    fun `call after TTL expiry refetches`() = runTest {
        var now = 0L
        val clock = { now }
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any(), anyOrNull()) } doReturn Result.success(listOf(release14))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries, clock)

        cache.list()
        now += ReleaseCatalogCache.TTL_MS + 1
        cache.list()

        verify(checker, times(2)).listReleases(any(), anyOrNull())
    }

    @Test
    fun `latest returns first newer-than-installed entry from cached snapshot`() = runTest {
        // Test assumes BuildConfig.VERSION_NAME is the installed build, so use a
        // version definitely older than every conceivable BuildConfig value to make
        // the test deterministic: feed an obviously-newer fake.
        val veryNew = UpdateInfo("99.0.0", "future", "", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any(), anyOrNull()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries) { 0L }

        val latest = cache.latest()
        assertEquals("99.0.0", latest?.versionName)
    }

    @Test
    fun `list and latest share the same snapshot - no double fetch`() = runTest {
        val veryNew = UpdateInfo("99.0.0", "future", "", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any(), anyOrNull()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries) { 0L }

        cache.list()
        cache.latest()

        verify(checker, times(1)).listReleases(any(), anyOrNull())
        verify(summaries, times(1)).load()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.ReleaseCatalogCacheTest'`
Expected: FAIL with "Unresolved reference: ReleaseCatalogCache".

- [ ] **Step 3: Implement `ReleaseCatalogCache`**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/update/ReleaseCatalogCache.kt
package com.albunyaan.tube.update

import android.os.SystemClock
import com.albunyaan.tube.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-source-of-truth for "what releases exist on GitHub right now". Owned
 * by the update package and consumed by both [UpdatePromptFlow] (latest only)
 * and AvailableVersionsViewModel (full list).
 *
 * The cache holds one [Snapshot] for [TTL_MS] from the moment it was loaded.
 * Concurrent calls during a load coalesce via [loadMutex] so we never issue
 * two parallel GitHub fetches; subsequent callers within the TTL read the
 * AtomicReference without taking the lock.
 *
 * Rationale: GitHub anonymous limit is 60 req/h per IP. The splash check, a
 * settings deep-link, and any rotation that re-enters the splash MUST share
 * one network call within a sane window. 5 minutes matches the typical edit
 * cadence on a release page.
 */
@Singleton
class ReleaseCatalogCache @Inject constructor(
    private val checker: UpdateChecker,
    private val summaries: ReleaseSummaryFetcher,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private data class Snapshot(
        val capturedAtMs: Long,
        val releases: List<UpdateInfo>,
        val summaries: ReleaseSummaries
    )

    private val snapshot = AtomicReference<Snapshot?>(null)
    private val loadMutex = Mutex()

    /** Returns up to [limit] releases (newest first), refreshing if stale. */
    suspend fun list(limit: Int = 5): List<UpdateInfo> {
        val snap = current() ?: return emptyList()
        return snap.releases.take(limit)
    }

    /** Returns the first cached release strictly newer than the running build, or null. */
    suspend fun latest(): UpdateInfo? {
        val snap = current() ?: return null
        return snap.releases.firstOrNull {
            UpdateChecker.isNewerVersion(it.versionName, BuildConfig.VERSION_NAME)
        }
    }

    /** Exposes summaries to callers (the picker). */
    suspend fun summaries(): ReleaseSummaries =
        current()?.summaries ?: ReleaseSummaries(emptyMap())

    private suspend fun current(): Snapshot? {
        snapshot.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return it }
        return loadMutex.withLock {
            // Double-check: another coroutine may have just refreshed.
            snapshot.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return@withLock it }
            val releases = checker.listReleases(limit = 5).getOrNull() ?: emptyList()
            val sums = summaries.load()
            val fresh = Snapshot(clock(), releases, sums)
            snapshot.set(fresh)
            fresh
        }
    }

    companion object {
        const val TTL_MS: Long = 5 * 60_000L
    }
}
```

Note: `UpdateChecker.isNewerVersion` is currently `internal` to the file's companion. Change its visibility from `internal` to `internal` *at the public Kotlin level* (which it already is) — i.e. it must be accessible from the `com.albunyaan.tube.update` package. It already is. No change needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.ReleaseCatalogCacheTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Migrate `UpdatePromptFlow.checkForUpdate` to the cache**

In `android/app/src/main/java/com/albunyaan/tube/update/UpdatePromptFlow.kt`:

1. Add `private val catalog: ReleaseCatalogCache` to the constructor.
2. Replace the body of `checkForUpdate()`:
```kotlin
suspend fun checkForUpdate(): UpdateInfo? = catalog.latest()
```
3. Remove the `cachedProbe: AtomicReference<CachedProbe?>` field, the `CachedProbe` inner data class, and the `PROBE_CACHE_TTL_MS` companion constant — all three are now owned by `ReleaseCatalogCache`.
4. Remove imports that become unused: `import java.util.concurrent.atomic.AtomicReference`, `import android.os.SystemClock` (if not used elsewhere in the file).

- [ ] **Step 6: Run the update suite to confirm the migration**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.*'`
Expected: PASS. Existing `UpdatePromptFlowTest` may need its constructor mock list updated to include a fake `ReleaseCatalogCache`. If a splash-gate idempotency test asserts on the old `cachedProbe` field directly (it shouldn't — the existing test uses reflection on flow internals, not the cache), redirect it to assert on `ReleaseCatalogCache` calls instead.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/update/ReleaseCatalogCache.kt \
        android/app/src/test/java/com/albunyaan/tube/update/ReleaseCatalogCacheTest.kt \
        android/app/src/main/java/com/albunyaan/tube/update/UpdatePromptFlow.kt \
        android/app/src/test/java/com/albunyaan/tube/update/UpdatePromptFlowTest.kt
git commit -m "[REFACTOR]: lift probe cache into ReleaseCatalogCache singleton

ANDROID-VERSIONS-01: splash gate + Available Updates screen now share one
in-memory snapshot under a 5-min TTL. Removes UpdatePromptFlow.cachedProbe.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `AvailableVersionsViewModel`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModel.kt`
- Test: `android/app/src/test/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModelTest.kt
package com.albunyaan.tube.ui.settings.availableversions

import com.albunyaan.tube.update.ReleaseCatalogCache
import com.albunyaan.tube.update.ReleaseRow
import com.albunyaan.tube.update.ReleaseSummaries
import com.albunyaan.tube.update.RowState
import com.albunyaan.tube.update.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AvailableVersionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After  fun tearDown() = Dispatchers.resetMain()

    private fun info(v: String) = UpdateInfo(v, v, "", "https://x/$v.apk", 1)

    @Test
    fun `merges releases with summaries and assigns row state by installed version`() = runTest {
        val newer = info("99.0.0-beta.1")
        val current = info("INSTALLED_VERSION")  // see note below
        val older = info("0.0.1")
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn listOf(newer, current, older)
            onBlocking { summaries() } doReturn ReleaseSummaries(
                mapOf("99.0.0-beta.1" to mapOf("en" to "Future release."))
            )
        }
        // ViewModel takes the installed version as an injected constant so tests
        // don't depend on BuildConfig.
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "INSTALLED_VERSION", locale = "en")
        vm.load()
        advanceUntilIdle()

        val rows = vm.rows.value
        assertEquals(3, rows.size)
        assertEquals(RowState.Newer, rows[0].state)
        assertEquals("Future release.", rows[0].localizedSummary)
        assertEquals(RowState.Current, rows[1].state)
        assertEquals(RowState.Older, rows[2].state)
        assertNull(rows[2].localizedSummary)
    }

    @Test
    fun `missing summary entry leaves localizedSummary null`() = runTest {
        val release = info("1.0.0-beta.14")
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn listOf(release)
            onBlocking { summaries() } doReturn ReleaseSummaries(emptyMap())
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "en")
        vm.load()
        advanceUntilIdle()

        assertNull(vm.rows.value.single().localizedSummary)
    }

    @Test
    fun `empty release list produces empty rows - not error state`() = runTest {
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn emptyList()
            onBlocking { summaries() } doReturn ReleaseSummaries(emptyMap())
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "en")
        vm.load()
        advanceUntilIdle()

        assertEquals(0, vm.rows.value.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.ui.settings.availableversions.AvailableVersionsViewModelTest'`
Expected: FAIL with "Unresolved reference: AvailableVersionsViewModel".

- [ ] **Step 3: Implement the ViewModel**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModel.kt
package com.albunyaan.tube.ui.settings.availableversions

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

@HiltViewModel
class AvailableVersionsViewModel @Inject constructor(
    private val catalog: ReleaseCatalogCache,
    private val installedVersionName: String = BuildConfig.VERSION_NAME,
    private val locale: String = Locale.getDefault().language
) : ViewModel() {

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
                        publishedAt = null, // populated in Task 7 — needs GitHub published_at field
                        localizedSummary = summaries.summaryFor(info.versionName, locale),
                        state = computeState(info.versionName, installedVersionName)
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
```

(`publishedAt` is wired through in Task 7; this step leaves it null so the ViewModel test stays green.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.ui.settings.availableversions.AvailableVersionsViewModelTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModel.kt \
        android/app/src/test/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModelTest.kt
git commit -m "[FEAT]: AvailableVersionsViewModel computes row states

ANDROID-VERSIONS-01: maps cached releases + summaries into ReleaseRow with
Current/Newer/Older state via UpdateChecker.isNewerVersion semver compare.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Wire `published_at` through `UpdateInfo`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt` (extend `UpdateInfo` and parser)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModel.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/update/UpdateCheckerTest.kt` (existing listReleases test) + the AvailableVersionsViewModelTest (assert `publishedAt`)

- [ ] **Step 1: Add `publishedAt` field**

In `UpdateChecker.kt`:
```kotlin
data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: java.time.Instant? = null   // null when GitHub omits it (legacy callers stay green)
)
```

In the `GithubReleaseDto` and `GithubReleaseListItemDto`:
```kotlin
val published_at: String?
```

In both `checkForUpdate` and `listReleases`, populate the new field:
```kotlin
UpdateInfo(
    ...,
    publishedAt = release.published_at?.let { runCatching { Instant.parse(it) }.getOrNull() }
)
```

- [ ] **Step 2: Extend the existing `listReleases` test**

Add a `"published_at":"2026-05-24T10:00:00Z"` field to the mock response in `UpdateCheckerTest.listReleases*`, and assert:
```kotlin
assertEquals(Instant.parse("2026-05-24T10:00:00Z"), info[0].publishedAt)
```

- [ ] **Step 3: Wire it into the ViewModel**

In `AvailableVersionsViewModel.kt`, replace `publishedAt = null` with `publishedAt = info.publishedAt`.

- [ ] **Step 4: Run the affected tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.UpdateCheckerTest' --tests 'com.albunyaan.tube.ui.settings.availableversions.AvailableVersionsViewModelTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/update/UpdateChecker.kt \
        android/app/src/test/java/com/albunyaan/tube/update/UpdateCheckerTest.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsViewModel.kt
git commit -m "[FEAT]: thread published_at through UpdateInfo for version picker

ANDROID-VERSIONS-01: row subtitle needs the release date; populated from
GitHub published_at. Nullable for the legacy code path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Layouts

**Files:**
- Create: `android/app/src/main/res/layout/fragment_available_versions.xml`
- Create: `android/app/src/main/res/layout/item_available_version.xml`
- Create: `android/app/src/main/res/layout/settings_item_available_versions.xml`

- [ ] **Step 1: Settings row layout (mirror of `settings_item_update_check.xml`)**

```xml
<!-- android/app/src/main/res/layout/settings_item_available_versions.xml -->
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/availableVersionsItem"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="@dimen/spacing_md"
    android:background="?attr/selectableItemBackground"
    android:clickable="true"
    android:focusable="true">
    <ImageView
        android:id="@+id/icon"
        android:layout_width="@dimen/library_icon_size"
        android:layout_height="@dimen/library_icon_size"
        android:padding="@dimen/spacing_sm"
        android:src="@drawable/ic_history"
        android:background="@drawable/onboarding_icon_bg"
        android:contentDescription="@null"
        app:tint="?attr/colorPrimary"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
    <TextView
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/spacing_md"
        android:text="@string/settings_available_updates"
        android:textSize="@dimen/text_subtitle"
        android:textColor="?attr/colorOnSurface"
        android:textAlignment="viewStart"
        app:layout_constraintStart_toEndOf="@id/icon"
        app:layout_constraintEnd_toStartOf="@id/chevron"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
    <ImageView
        android:id="@+id/chevron"
        android:layout_width="@dimen/icon_small"
        android:layout_height="@dimen/icon_small"
        android:src="@drawable/ic_chevron_right"
        android:contentDescription="@null"
        app:tint="?attr/colorOnSurfaceVariant"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

Note: if `@drawable/ic_history` doesn't exist, substitute `@drawable/ic_refresh` (already used by the update-check row) — confirm by `ls android/app/src/main/res/drawable/ic_history.xml` first.

- [ ] **Step 2: Fragment shell layout**

```xml
<!-- android/app/src/main/res/layout/fragment_available_versions.xml -->
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:liftOnScroll="true">
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:navigationIcon="@drawable/ic_arrow_back"
            app:title="@string/settings_available_updates" />
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/versionsList"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:paddingHorizontal="@dimen/spacing_md"
        android:paddingTop="@dimen/spacing_md"
        android:paddingBottom="@dimen/bottom_nav_height"
        app:layout_behavior="@string/appbar_scrolling_view_behavior" />

    <ProgressBar
        android:id="@+id/loading"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:visibility="gone" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 3: Row layout**

```xml
<!-- android/app/src/main/res/layout/item_available_version.xml -->
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="@dimen/spacing_sm"
    app:cardBackgroundColor="?attr/colorSurface"
    app:cardCornerRadius="@dimen/corner_radius_medium"
    app:cardElevation="0dp">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="@dimen/spacing_md">

        <TextView
            android:id="@+id/version"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:textStyle="bold"
            android:textSize="@dimen/text_subtitle"
            android:textColor="?attr/colorOnSurface"
            android:textAlignment="viewStart"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toStartOf="@id/action"
            app:layout_constraintTop_toTopOf="parent"
            tools:text="v1.0.0-beta.14" />

        <TextView
            android:id="@+id/dateLine"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/spacing_xs"
            android:textSize="@dimen/text_caption"
            android:textColor="?attr/colorOnSurfaceVariant"
            android:textAlignment="viewStart"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toStartOf="@id/action"
            app:layout_constraintTop_toBottomOf="@id/version"
            tools:text="May 24, 2026" />

        <TextView
            android:id="@+id/summary"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/spacing_sm"
            android:maxLines="2"
            android:ellipsize="end"
            android:textSize="@dimen/text_body"
            android:textColor="?attr/colorOnSurface"
            android:textAlignment="viewStart"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toBottomOf="@id/dateLine"
            tools:text="Splash-gate update prompt + Samsung post-install self-kill." />

        <!-- Right-side affordance: Install button (Newer), Installed chip (Current),
             or "Downgrade unavailable" text (Older). Adapter toggles visibility. -->
        <com.google.android.material.button.MaterialButton
            android:id="@+id/action"
            style="@style/Widget.Material3.Button.TonalButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/available_versions_install"
            android:visibility="gone"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="@id/version"
            app:layout_constraintBottom_toBottomOf="@id/dateLine" />

        <com.google.android.material.chip.Chip
            android:id="@+id/installedChip"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/available_versions_installed"
            android:clickable="false"
            android:focusable="false"
            android:visibility="gone"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="@id/version"
            app:layout_constraintBottom_toBottomOf="@id/dateLine" />
    </androidx.constraintlayout.widget.ConstraintLayout>
</com.google.android.material.card.MaterialCardView>
```

(Add `xmlns:tools="http://schemas.android.com/tools"` to the root if `tools:text` previews are wanted — optional.)

- [ ] **Step 4: Verify all three layouts compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no resource resolution errors).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/layout/fragment_available_versions.xml \
        android/app/src/main/res/layout/item_available_version.xml \
        android/app/src/main/res/layout/settings_item_available_versions.xml
git commit -m "[FEAT]: layouts for Available Updates screen

ANDROID-VERSIONS-01: fragment shell with toolbar+RecyclerView, per-version
card row, and the Settings entry row mirroring settings_item_update_check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Strings (en / ar / nl)

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-ar/strings.xml`
- Modify: `android/app/src/main/res/values-nl/strings.xml`

- [ ] **Step 1: Append new strings to each file**

Append to `values/strings.xml`:
```xml
    <!-- ANDROID-VERSIONS-01: Available Updates screen -->
    <string name="settings_available_updates">Available updates</string>
    <string name="available_versions_install">Install</string>
    <string name="available_versions_installed">Installed</string>
    <string name="available_versions_downgrade_deferred">Downgrade not available</string>
    <string name="available_versions_downgrade_snackbar">Downgrading older versions isn\'t supported yet. Coming in a future update.</string>
    <string name="available_versions_empty">No releases available right now. Try again later.</string>
```

Append to `values-ar/strings.xml`:
```xml
    <!-- ANDROID-VERSIONS-01 -->
    <string name="settings_available_updates">التحديثات المتاحة</string>
    <string name="available_versions_install">تثبيت</string>
    <string name="available_versions_installed">مثبت</string>
    <string name="available_versions_downgrade_deferred">الرجوع لإصدار أقدم غير متاح</string>
    <string name="available_versions_downgrade_snackbar">الرجوع إلى الإصدارات القديمة غير مدعوم بعد. سيأتي في تحديث لاحق.</string>
    <string name="available_versions_empty">لا توجد إصدارات متاحة حالياً. حاول مرة أخرى لاحقاً.</string>
```

Append to `values-nl/strings.xml`:
```xml
    <!-- ANDROID-VERSIONS-01 -->
    <string name="settings_available_updates">Beschikbare updates</string>
    <string name="available_versions_install">Installeren</string>
    <string name="available_versions_installed">Geïnstalleerd</string>
    <string name="available_versions_downgrade_deferred">Downgrade niet beschikbaar</string>
    <string name="available_versions_downgrade_snackbar">Downgraden naar oudere versies wordt nog niet ondersteund. Komt in een toekomstige update.</string>
    <string name="available_versions_empty">Geen releases beschikbaar op dit moment. Probeer het later opnieuw.</string>
```

- [ ] **Step 2: Build to confirm no XML errors**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: i18n strings for Available Updates

ANDROID-VERSIONS-01: en + ar + nl coverage for screen title, buttons,
empty state, and downgrade-deferred snackbar.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: `AvailableVersionsAdapter`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsAdapter.kt`

- [ ] **Step 1: Create the adapter**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsAdapter.kt
package com.albunyaan.tube.ui.settings.availableversions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.update.ReleaseRow
import com.albunyaan.tube.update.RowState
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import android.widget.TextView
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class AvailableVersionsAdapter(
    private val onInstallClick: (ReleaseRow) -> Unit,
    private val onOlderClick: (ReleaseRow) -> Unit
) : ListAdapter<ReleaseRow, AvailableVersionsAdapter.RowVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_available_version, parent, false)
        return RowVH(view)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val version: TextView = view.findViewById(R.id.version)
        private val dateLine: TextView = view.findViewById(R.id.dateLine)
        private val summary: TextView = view.findViewById(R.id.summary)
        private val action: MaterialButton = view.findViewById(R.id.action)
        private val installedChip: Chip = view.findViewById(R.id.installedChip)

        fun bind(row: ReleaseRow) {
            version.text = "v${row.info.versionName}"

            // Date: locale-aware short form, falls back to empty if publishedAt missing.
            val locale = Locale.getDefault()
            dateLine.text = row.publishedAt?.let {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                    .withLocale(locale)
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(it)
            }.orEmpty()

            summary.text = row.localizedSummary.orEmpty()
            summary.visibility = if (row.localizedSummary.isNullOrBlank()) View.GONE else View.VISIBLE

            when (row.state) {
                RowState.Newer -> {
                    action.visibility = View.VISIBLE
                    installedChip.visibility = View.GONE
                    action.text = itemView.context.getString(R.string.available_versions_install)
                    action.isEnabled = true
                    action.setOnClickListener { onInstallClick(row) }
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                }
                RowState.Current -> {
                    action.visibility = View.GONE
                    installedChip.visibility = View.VISIBLE
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                }
                RowState.Older -> {
                    action.visibility = View.GONE
                    installedChip.visibility = View.GONE
                    itemView.isClickable = true
                    itemView.setOnClickListener { onOlderClick(row) }
                }
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ReleaseRow>() {
            override fun areItemsTheSame(o: ReleaseRow, n: ReleaseRow) =
                o.info.versionName == n.info.versionName
            override fun areContentsTheSame(o: ReleaseRow, n: ReleaseRow) = o == n
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsAdapter.kt
git commit -m "[FEAT]: AvailableVersionsAdapter renders Newer/Current/Older states

ANDROID-VERSIONS-01: per-row action affordance switches on RowState.
Date rendered with locale-aware DateTimeFormatter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: `AvailableVersionsFragment` + navigation wiring

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsFragment.kt`
- Modify: `android/app/src/main/res/navigation/main_tabs_nav.xml`

- [ ] **Step 1: Create the fragment**

```kotlin
// android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsFragment.kt
package com.albunyaan.tube.ui.settings.availableversions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.update.ApkInstaller   // for the install call-out path
import com.albunyaan.tube.update.UpdatePromptFlow
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AvailableVersionsFragment : Fragment(R.layout.fragment_available_versions) {

    private val viewModel: AvailableVersionsViewModel by viewModels()

    @Inject lateinit var updatePromptFlow: UpdatePromptFlow

    private lateinit var adapter: AvailableVersionsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        adapter = AvailableVersionsAdapter(
            onInstallClick = { row ->
                val activity = activity ?: return@AvailableVersionsAdapter
                lifecycleScope.launch {
                    updatePromptFlow.showUpdateDialogAndAwait(activity, viewLifecycleOwner, row.info)
                }
            },
            onOlderClick = {
                Snackbar.make(
                    view,
                    R.string.available_versions_downgrade_snackbar,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        )
        val recycler = view.findViewById<RecyclerView>(R.id.versionsList)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val progress = view.findViewById<ProgressBar>(R.id.loading)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rows.collect { adapter.submitList(it) }
                }
                launch {
                    viewModel.loading.collect {
                        progress.visibility = if (it) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        viewModel.load()
    }
}
```

- [ ] **Step 2: Verify `UpdatePromptFlow.showUpdateDialogAndAwait` is callable with an arbitrary `UpdateInfo`**

Inspect the existing signature in `UpdatePromptFlow.kt`. It currently takes `(activity, lifecycleOwner, info: UpdateInfo)`. If yes, no change needed — proceed. If the signature is different (e.g. it fetches latest internally), refactor it to accept an `UpdateInfo` parameter and have the existing splash caller pass `catalog.latest()!!`.

- [ ] **Step 3: Add the navigation destination**

In `android/app/src/main/res/navigation/main_tabs_nav.xml`, add inside the `settingsFragment` element (alongside the existing `action_settingsFragment_to_aboutFragment`):
```xml
        <action
            android:id="@+id/action_settingsFragment_to_availableVersionsFragment"
            app:destination="@id/availableVersionsFragment"
            app:launchSingleTop="true"
            app:popUpTo="@id/settingsFragment" />
```

And register the destination next to the existing `aboutFragment`:
```xml
    <fragment
        android:id="@+id/availableVersionsFragment"
        android:name="com.albunyaan.tube.ui.settings.availableversions.AvailableVersionsFragment"
        android:label="@string/settings_available_updates" />
```

- [ ] **Step 4: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/settings/availableversions/AvailableVersionsFragment.kt \
        android/app/src/main/res/navigation/main_tabs_nav.xml
git commit -m "[FEAT]: AvailableVersionsFragment + nav destination

ANDROID-VERSIONS-01: collects ViewModel rows into adapter, routes install
clicks through UpdatePromptFlow.showUpdateDialogAndAwait, shows snackbar
on disabled older-version rows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: `SettingsFragment` row + Play Store gate

**Files:**
- Modify: `android/app/src/main/res/layout/fragment_settings.xml`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/settings/SettingsFragment.kt`

- [ ] **Step 1: Add the row to the Settings layout**

In `fragment_settings.xml`, locate the `<!-- About & Support Section -->` `MaterialCardView` containing the support + update-check rows. Add a divider and the new include between the support row and the update-check row:

```xml
                    <!-- Support Center -->
                    <include layout="@layout/settings_item_support"/>

                    <View
                        android:layout_width="match_parent"
                        android:layout_height="@dimen/divider_thickness"
                        android:background="?attr/colorOutlineVariant"
                        android:layout_marginStart="@dimen/spacing_lg"/>

                    <!-- ANDROID-VERSIONS-01: Available updates -->
                    <include layout="@layout/settings_item_available_versions"/>

                    <View
                        android:layout_width="match_parent"
                        android:layout_height="@dimen/divider_thickness"
                        android:background="?attr/colorOutlineVariant"
                        android:layout_marginStart="@dimen/spacing_lg"/>

                    <!-- ANDROID-MULTI-01 Issue 3: Check for updates -->
                    <include layout="@layout/settings_item_update_check"/>
```

- [ ] **Step 2: Wire click + Play Store gate in `SettingsFragment`**

Add to `SettingsFragment.kt` constructor injections:
```kotlin
@Inject lateinit var installSource: com.albunyaan.tube.update.InstallSource
```

In `onViewCreated` (or wherever the existing `view.findViewById<View>(R.id.updateCheckItem)?.setOnClickListener` block lives), add:

```kotlin
val availableVersionsRow = view.findViewById<View>(R.id.availableVersionsItem)
if (installSource.isPlayStore()) {
    availableVersionsRow?.visibility = View.GONE
    // Also hide the leading divider so we don't end up with a stranded line.
    // Find the divider by walking the parent — or give the divider an id and hide it explicitly.
    (availableVersionsRow?.parent as? ViewGroup)?.let { parent ->
        val idx = parent.indexOfChild(availableVersionsRow)
        if (idx > 0) parent.getChildAt(idx - 1).visibility = View.GONE
    }
} else {
    availableVersionsRow?.setOnClickListener {
        if (findNavController().currentDestination?.id == R.id.settingsFragment) {
            findNavController().navigate(R.id.action_settingsFragment_to_availableVersionsFragment)
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/layout/fragment_settings.xml \
        android/app/src/main/java/com/albunyaan/tube/ui/settings/SettingsFragment.kt
git commit -m "[FEAT]: Settings row 'Available updates' + Play Store gate

ANDROID-VERSIONS-01: hidden entirely on Play Store installs (and the
leading divider too) since GitHub APKs would break Play auto-updates.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: `releases-meta.json` backfill

**Files:**
- Create: `releases-meta.json` (repo root)
- Modify: `CLAUDE.md` (release checklist)

- [ ] **Step 1: Inspect existing CHANGELOG to draft summaries**

Run: `head -60 /home/farouq/Development/albunyaantube/CHANGELOG.md`
Expected output: changelog entries to draft from.

- [ ] **Step 2: Create the JSON**

```json
// releases-meta.json (repo root)
{
  "1.0.0-beta.14": {
    "en": "Splash-gate update prompt + Samsung post-install self-kill.",
    "ar": "موجه التحديث على شاشة البداية + إنهاء العملية على سامسونج بعد التثبيت.",
    "nl": "Update-prompt op het splashscherm + zelf-afsluiten na installatie op Samsung."
  },
  "1.0.0-beta.13": {
    "en": "Firebase auth + admin user management.",
    "ar": "مصادقة Firebase + إدارة مستخدمي الإدارة.",
    "nl": "Firebase-authenticatie + admin-gebruikersbeheer."
  }
}
```

(Add entries for beta-10 through beta-12 by reading their CHANGELOG entries and drafting en/ar/nl translations. If user wants to review translations before publishing, leave the file with only beta-13 and beta-14, push, and request review — the picker degrades silently for missing entries.)

Note: JSON does not support `//` comments. The line above is a markdown comment in this plan, not in the file.

- [ ] **Step 3: Add the release checklist entry to `CLAUDE.md`**

Find the existing release section (search for `release` near version-bumping guidance). Append a sub-item:

```markdown
### Per-release checklist (additions)
- Add an entry to `releases-meta.json` (repo root) under the new `versionName` with `en`, `ar`, `nl` one-liners. Missing locales are silently OK but degrade UX for that locale.
```

If no release section exists, insert under a new `## Release process` heading at the end of the `## Git conventions` section.

- [ ] **Step 4: Commit**

```bash
git add releases-meta.json CLAUDE.md
git commit -m "[DOCS]: seed releases-meta.json + add to release checklist

ANDROID-VERSIONS-01: localized one-line summaries for beta-10..14
consumed by the Available Updates screen.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: `UpdatePromptFlow` non-latest install coverage

**Files:**
- Modify: `android/app/src/test/java/com/albunyaan/tube/update/UpdatePromptFlowTest.kt`

- [ ] **Step 1: Add the test**

Append to the test file:

```kotlin
@Test
fun `showUpdateDialogAndAwait accepts a non-latest UpdateInfo and drives the install path`() = runTest {
    // The flow does not inspect "is this the latest" anywhere — it simply downloads
    // the apkUrl on the passed UpdateInfo and hands off to ApkInstaller. We exercise
    // that path with a synthesized older-than-latest UpdateInfo to lock the contract.
    val nonLatest = UpdateInfo(
        versionName = "1.0.0-beta.13",   // current head is beta-14 — this is one behind
        releaseName = "beta-13",
        releaseNotes = "",
        apkUrl = "https://example/test.apk",
        apkSizeBytes = 1024L,
        publishedAt = null
    )
    // ...wire mocks for installer + downloader the same way the existing splash-gate
    // test does, but invoke showUpdateDialogAndAwait directly with nonLatest. Assert
    // that ApkInstaller.launchInstaller is called with the downloaded file path.
}
```

(The implementer fills in the mock wiring by mirroring the existing splash-gate test's setup.)

- [ ] **Step 2: Run the test**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'com.albunyaan.tube.update.UpdatePromptFlowTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/test/java/com/albunyaan/tube/update/UpdatePromptFlowTest.kt
git commit -m "[TEST]: UpdatePromptFlow accepts non-latest UpdateInfo

ANDROID-VERSIONS-01: locks the widened contract so future refactors cannot
silently regress the picker's install path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: Full suite + manual smoke test

- [ ] **Step 1: Run the entire app unit test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS. If anything is red, fix before moving on — do not push broken green.

- [ ] **Step 2: Build a debug APK and install on the emulator**

The user's emulator launch convention is captured in memory `project_android_emulator_launch.md` — NVIDIA Prime offload + `-no-snapshot`. Ask the user to run the emulator if it is not already up.

Run: `cd android && ./gradlew :app:installDebug`

- [ ] **Step 3: Manual smoke test on a sideloaded debug build**

Open the app → sign in → Settings → tap **Available updates**. Expected behaviour:

1. Screen opens with the toolbar back-arrow visible.
2. Loading spinner shows briefly, then a list of up to 5 cards.
3. The card matching the running build (`v1.0.0-beta.14` today) shows an "Installed" chip — no Install button.
4. Cards for newer versions (none today unless you publish a fake one) would show an "Install" button.
5. Cards for older versions show no button and tapping the row produces the "Downgrade not available" snackbar.
6. Localized summary appears on each card; date is rendered in the device locale.
7. Switch device language to Arabic, repeat — strings and summaries render in Arabic with RTL layout intact.
8. Switch device language to Dutch, repeat.
9. Back-arrow returns to Settings without crashing.

Document any issues in a follow-up task list — do **not** ship until smoke test passes.

- [ ] **Step 4: Run the project's mandatory 7-stage review pipeline**

Per memory `feedback_review_pipeline.md`, after any non-trivial coding change run:

1. `git status` baseline.
2. Dispatch `code-reviewer` subagent in background on the cumulative diff.
3. Run `/cso` for security review.
4. Run `/codex` challenge mode (fallback to Agent if codex unavailable).
5. Consolidate findings.
6. Patch + re-review until clean.
7. `gstack /review` then `cubic` review.

This is project policy and is **not optional** before merge. Address P0+P1 findings before opening the PR.

- [ ] **Step 5: Final commit (if anything was patched in Step 4)**

If the review pipeline produced patches, commit them with `[FIX]:` prefix and reference `ANDROID-VERSIONS-01`.

---

## Closing

- The spec lives at `docs/superpowers/specs/2026-05-24-version-picker-design.md`. Commit it alongside Task 13's `[DOCS]` commit (add `git add docs/superpowers/specs/2026-05-24-version-picker-design.md` to the staging line) if it has not been committed yet.
- Per project workflow, after every completed task update `docs/TRUE_PROJECT_STATUS.md` and `docs/PROJECT_STATUS.md` (`.claude/rules/workflow.md`). The plan does not enumerate that per task to keep noise down — do it as you go.
- Do **not** push to `main`. Per memory `feedback_branching_policy.md`, all work merges to `develop` until the user confirms a stable release.
