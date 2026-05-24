# Settings → Available Updates (Version Picker) Design

**Status:** Approved (brainstorming complete, 2026-05-24)
**Author:** Claude (via brainstorming skill) + Farouq
**Ticket:** `ANDROID-VERSIONS-01`

## Problem

Users on FitrahTube can only react to the latest release prompt that the splash gate (`UpdatePromptFlow`) fronts at cold start. There is no way for a user to:

1. See which releases exist beyond the current latest.
2. Jump forward across multiple betas (e.g. skip a known-broken beta and install the one after it).
3. Read what each release actually changed in their own language.

We want a Settings child screen that exposes the last 5 releases and lets the user install any release *newer than* their installed version. Downgrade is intentionally out of scope for this ticket and is tracked as a future "backwards-compatibility" topic that the user will open separately.

## Non-goals

- **Downgrading to an older version.** Android's `PackageInstaller` rejects same-key installs whose `versionCode` is lower than the installed one (`INSTALL_FAILED_VERSION_DOWNGRADE`). The only workarounds (uninstall + reinstall with data loss, or `adb install -d`) require either UI we are not building yet or USB debugging that end users do not have. A "Downgrade not available" affordance is shown but does nothing — see Future Work.
- **Pinning to a version** (suppressing the auto-prompt past version X).
- **Toggling pre-releases on/off.** All current releases are pre-releases; the toggle would have no effect today. Revisit when a stable line ships.
- **Pull-to-refresh / manual refresh button.** Single refresh-on-entry, reusing the splash cache.

## Architecture

### Screen placement

A new fragment, `AvailableVersionsFragment`, registered as a Settings child destination (`available_versions_dest`) in the Settings navigation graph. Reached from a new row in `SettingsFragment` labelled **"Available updates"** (`التحديثات المتاحة` / `Beschikbare updates`). The fragment hosts a standard `Toolbar` + `RecyclerView` and matches the pattern used by existing Settings children.

### Data flow

```
AvailableVersionsFragment
  └── AvailableVersionsViewModel
        ├── UpdateChecker.listReleases(limit = 5)          [NEW method]
        │     → GET https://api.github.com/repos/talibfitrah/albunyaantube/releases?per_page=5
        └── ReleaseSummaryFetcher.load()                    [NEW class]
              → GET https://raw.githubusercontent.com/talibfitrah/albunyaantube/main/releases-meta.json
  ↓
List<ReleaseRow> (merge of release list + summary map, keyed by versionName)
```

Both HTTP calls are gated through a new singleton `ReleaseCatalogCache` (DI scope: `@Singleton`). The cache holds the merged `List<ReleaseRow>` for 5 minutes (`PROBE_CACHE_TTL_MS`, lifted from `UpdatePromptFlow` and shared). The existing splash probe is migrated to read from this cache, so that splash → settings within a 5-minute window costs zero extra GitHub API calls.

### `releases-meta.json` format

Lives at the **repo root** of `talibfitrah/albunyaantube`, on the `main` branch. Format:

```json
{
  "1.0.0-beta.14": {
    "en": "Splash-gate update prompt + Samsung post-install self-kill.",
    "ar": "موجه التحديث على شاشة البداية + إنهاء العملية على سامسونج بعد التثبيت.",
    "nl": "Update-prompt op het splashscherm + zelf-afsluiten na installatie op Samsung."
  },
  "1.0.0-beta.13": { ... },
  ...
}
```

Resolution rules in `ReleaseSummaryFetcher`:

- Locale match → use it.
- Missing locale → fall back to `en`.
- Missing version entry → row renders with empty summary (no error UI, no crash).
- Network failure (4xx, 5xx, timeout, parse error) → return empty map; rows render version + date only.

### Row state machine

`UpdateInfo` carries `versionName` (string) only — there is no `versionCode` on the GitHub side. We reuse the existing `UpdateChecker.isNewerVersion(remote, current)` semver-2.0.0 comparator (already battle-tested for the splash gate, handles `1.0.0-beta.14` vs `1.0.0-beta.15` correctly and tolerates malformed input). Each row in the list is one of three states, computed in the ViewModel by comparing `release.versionName` against `BuildConfig.VERSION_NAME`:

| State    | Trigger                                                                            | Right-side affordance                                                                                |
| -------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Current  | `release.versionName == BuildConfig.VERSION_NAME` (string equality, after `v` strip) | Chip "Installed". No button.                                                                         |
| Newer    | `isNewerVersion(release.versionName, BuildConfig.VERSION_NAME) == true`            | Button "Install" → calls `UpdatePromptFlow.showUpdateDialogAndAwait(updateInfo)` with the chosen release. |
| Older    | neither of the above                                                               | Disabled row, subtitle "Downgrade not available". Tap → snackbar with the deferred-feature explanation. |

The "Older" branch deliberately catches both genuinely-older versions and any unparseable garbage — both are equally non-installable from this screen.

### Play Store install short-circuit

`UpdateChecker.checkForUpdate()` already returns `null` when the app was installed from Play Store (Play manages its own updates, sideloading from GitHub over a Play install would brick auto-updates). The new screen must respect the same rule: when `isInstalledFromPlayStore()` is true, the Settings row for "Available updates" is hidden entirely. Cleaner than showing the row and then explaining-away an empty screen. Extract `isInstalledFromPlayStore` from `UpdateChecker` into a small `InstallSource` helper so `SettingsFragment` can call it for the visibility check without taking a dependency on `UpdateChecker` itself.

### `UpdatePromptFlow` API change

`showUpdateDialogAndAwait(activity, lifecycleOwner, info)` currently always receives the "latest from GitHub" `UpdateInfo`. We extend the contract so callers can pass any `UpdateInfo` they constructed themselves. No code change beyond:

- Document in the KDoc that callers may pass a non-latest `UpdateInfo`.
- Verify the existing flow still works (download + install + self-kill) when the `versionName` is not the absolute latest — the install machinery does not inspect "is this the latest" anywhere, so this is a documentation-only change in practice.

Add a unit test that exercises the path with a synthesized non-latest `UpdateInfo`.

### Cache sharing

Today `UpdatePromptFlow.cachedProbe: AtomicReference<CachedProbe?>` lives inside `UpdatePromptFlow`. We extract it:

```kotlin
@Singleton
class ReleaseCatalogCache @Inject constructor(
    private val checker: UpdateChecker,
    private val summaries: ReleaseSummaryFetcher,
    private val clock: Clock,
) {
    suspend fun latest(): UpdateInfo?           // serves UpdatePromptFlow
    suspend fun list(limit: Int = 5): List<ReleaseRow>  // serves AvailableVersionsViewModel
}
```

Internally one in-memory `CachedSnapshot(timestamp, releases, summaries)` field with the 5-minute TTL. `latest()` returns the first newer-than-installed entry; `list()` returns up to `limit` releases merged with summaries.

`UpdatePromptFlow.checkForUpdate()` becomes a thin wrapper around `cache.latest()`.

## Tests

Unit tests (JVM, no Robolectric):

- `AvailableVersionsViewModelTest`
  - merges releases with summaries by `versionName`
  - missing locale → falls back to `en`
  - missing entry in summary map → row has empty summary
  - row state assignment: `current` / `newer` / `older` using representative `versionName` strings against a fake `BuildConfig.VERSION_NAME` (e.g. installed `1.0.0-beta.14`, list contains `1.0.0-beta.15`/`1.0.0-beta.14`/`1.0.0-beta.13` → `newer`/`current`/`older`)
  - empty release list → empty state (not error)
- `ReleaseSummaryFetcherTest`
  - parses well-formed JSON
  - locale fallback path
  - 404 / 5xx / timeout / malformed JSON → returns empty map without throwing
- `ReleaseCatalogCacheTest`
  - first call hits network; second call within TTL serves from cache
  - call after TTL expiry re-fetches
  - both `latest()` and `list()` share the same snapshot (no double-fetch)
- `UpdatePromptFlowTest` (extend existing)
  - existing splash-gate idempotency test stays as-is
  - new test: `showUpdateDialogAndAwait` accepts a synthesized non-latest `UpdateInfo` and routes through the existing install path

Instrumented / fragment tests deliberately omitted — Espresso coverage on Settings children is sparse in this codebase and the screen is thin.

## Release process changes

Add to the release checklist (currently informal — gets added to `CLAUDE.md` "Release workflow" section as part of this ticket):

1. Bump `versionCode` + `versionName` in `android/app/build.gradle.kts`.
2. **NEW:** Add an entry to `releases-meta.json` (repo root) under the new `versionName` with `en`, `ar`, `nl` one-liners. Missing locales are silently OK but degrade the UX for that locale.
3. Update `CHANGELOG.md` Unreleased section.
4. Tag and push as today.

Existing 5 betas (10..14) get backfilled in the same PR that introduces the file. Initial drafts come from CHANGELOG.md text and the user reviews translations.

## Future work (deferred, do not implement now)

1. **Backwards-compatibility downgrade.** Per-release manifest declaring "data format X" and which older versions can read it; on downgrade-tap, run a guarded uninstall + reinstall flow with explicit data-loss consent. User will open this topic separately.
2. **Pre-release toggle** for when a stable line ships.
3. **Pin to version** (suppress auto-prompt past version X).

## Open hooks for the future-work ticket

To minimise rework when (1) is taken up:

- Disabled "older" row's snackbar text uses a string resource named `available_versions_downgrade_deferred` so it is trivial to swap for an active CTA later.
- `ReleaseRow` carries the full `UpdateInfo` (the GitHub-derived shape, with `apkUrl` and `apkSizeBytes`) so the future downgrade flow has everything it needs to drive an uninstall + reinstall without a re-fetch.

## Risks

- **GitHub API rate limit (60/h anonymous, per IP).** Shared cache mitigates within-window collisions, but a user manually re-opening the screen across many 5-minute windows will eventually hit the limit. Acceptable today; the screen handles a 403/429 by showing the stale cache (if any) plus a one-line "Couldn't refresh" subtitle.
- **`releases-meta.json` drift.** A release with no entry renders rows without summaries, which is a visible UX degradation. Mitigation: add the file to the release checklist; CI lint (future) could fail a tag push if the version is missing from the file.
- **`UpdatePromptFlow.showUpdateDialogAndAwait` contract widening.** Callers currently only pass the latest release. Widening to "any release" is documentation-only but creates a path where buggy callers could pass an `UpdateInfo` for a *non-existent* APK URL. Mitigation: `UpdateChecker.listReleases` returns the same `UpdateInfo` shape the existing latest-fetch returns, with the same APK URL provenance — no synthesized URLs from caller code.
