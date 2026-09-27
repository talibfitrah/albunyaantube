# iOS Phase 1 — Catalog UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship every guest-usable catalog screen of the Android app on iPhone and iPad: splash, onboarding, the five-tab shell, Home, Channels/Playlists/Videos, Search, Categories/Subcategories, Featured, local Favorites, Settings (with the five real settings), About, plus en/ar/nl localisation, RTL, and iPad layouts — all reading the existing backend through the Phase 0 `FitrahAPI` client and `AppContainer`.

**Architecture:** Phase 0's `AppContainer` grows one protocol per dependency (`CatalogClient` extended; new `SettingsStore`, `FilterStore`, `FavoritesStore`, `CategoriesCache`, `NetworkMonitor`); each has a live and a fake. Screens are SwiftUI views that create an `@Observable` ViewModel from the container. Navigation is a typed `Route` enum per tab stack under a `sidebarAdaptable` `TabView`. Strings come from a generated `Localizable.xcstrings` that keeps Android keys verbatim. Player, channel detail, playlist detail and Shorts are **phase 2**: phase 1 routes to them and shows a labelled placeholder carrying the exact navigation arguments.

**Tech Stack:** Swift 6.2 / Xcode 26.3 / iOS 18 SDK, SwiftUI, SwiftData, Swift Testing, XcodeGen, swift-openapi-generator (existing), Python 3 (strings converter, no third-party modules).

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` (§5 DI + isolation rule, §6 navigation, §7 tokens/components/grids, §8 API, §14 i18n/a11y/iPad, §15 phase 1 row). **Requirements corpus:** `docs/superpowers/plans/2026-08-23-ios-phase1-research/` — `INDEX.md` (map + shared behaviours), `RULINGS.md` (every open question decided), and the six briefs; a task says "contract: `<file>:<lines>`" to mean "match that behaviour exactly".

## Global Constraints

- iOS 18.0, iPhone + iPad (`TARGETED_DEVICE_FAMILY 1,2`); Swift 6 language mode, app target `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`; engine-side types `nonisolated … Sendable`, UI stores `@MainActor` (spec §5 isolation rule).
- No third-party dependencies in phase 1 (spec D8). Images load with `AsyncImage`-equivalent backed by `URLSession` + `URLCache` (see Task 6 `RemoteImage`).
- Android keys verbatim for strings and persisted settings: `app_locale, theme, audio_only, background_play (default true), download_quality (medium), wifi_only_downloads (false), safe_mode (default true), onboarding_completed, import_offer_shown`, filter keys `filter_category, filter_category_name, filter_length, filter_date, filter_sort`, search history key `search_history` (max 10).
- Tab order Home → Channels → Me → Playlists → Videos; Me in phase 1 = guest card + local favorites (spec D11).
- Tokens and component names from spec §7 and Phase 0 `Tokens.swift`; never white text on the dark-mode mint (`onBrand`); brand green for text/tint.
- Grids: Channels/Playlists **1/3/4** columns by `WidthClass` (RULINGS 14); Videos `max(2, min(8, floor(width/180)))`; auto-`loadMore()` when content fits (CLAUDE.md rule) with the six guards in `content-lists.md:219-237`.
- Every screen verified on iPhone 17, iPad mini (A17 Pro), iPad Pro 13" (M5) × en + ar (RTL) × light + dark before the phase gate; screenshots saved under the SDD workspace, never committed.
- Tests: Swift Testing, `@Suite(.perTest)`; gate = `ios/scripts/test.sh` (both simulators + package).
- Commits `[PREFIX]: Description` (≤50-char description). Branch `feature/ios-app`.
- **Deviation from the writing-plans "code for every step" rule, deliberate:** SwiftUI view bodies are specified by contract (brief + line range + component list + acceptance checks) rather than pasted verbatim; every non-view unit (models, stores, ViewModels, parsers, scripts, tests) is given in full. A view task is done when its acceptance checks and screenshots pass. Likewise, Tasks 2–13 list test cases by name; implementers write them in the `@Suite(.perTest)` Swift Testing style shown in Task 1, one `@Test` per listed case, before the implementation (TDD).

---

## File structure

| Path | Responsibility |
|---|---|
| `ios/scripts/convert-strings.py` | Android `strings*.xml` (en/ar/nl) → `Localizable.xcstrings`; rules R1–R9 of `strings-assets.md:217-327` |
| `ios/FitrahTube/Resources/Localizable.xcstrings` | generated, committed |
| `ios/FitrahTube/Resources/Assets.xcassets` | AppIcon (1024 px), `logo` (800 px PNG), colors none (code-defined) |
| `ios/FitrahTube/Catalog/Models.swift` | `ContentItem`, `ContentType`, `CursorPage`, `HomeSection`, `Category` (extended), `FilterState`, `SearchType` |
| `ios/FitrahTube/Catalog/CatalogClient.swift` (extend) | `home/content/search/categories/headChannel/headPlaylist/headVideo` |
| `ios/FitrahTube/Catalog/LiveCatalogClient.swift` (extend), `FakeCatalogClient.swift` (extend) | mapping + fixtures |
| `ios/FitrahTube/Catalog/Formatting.swift` | duration, compact counts + plural clamp, time-ago ladder, category display name |
| `ios/FitrahTube/Persistence/SettingsStore.swift` | UserDefaults-backed `@Observable` store, keys verbatim, locale/theme resolution |
| `ios/FitrahTube/Persistence/FilterStore.swift` | `FilterState` persistence + re-derived label |
| `ios/FitrahTube/Persistence/SearchHistoryStore.swift` | ≤10 entries |
| `ios/FitrahTube/Persistence/FavoriteVideo.swift`, `FavoritesStore.swift` | SwiftData model (Room v11 columns) + store |
| `ios/FitrahTube/Persistence/CategoriesCache.swift` | one fetch per launch; tree derivation; sort |
| `ios/FitrahTube/App/NetworkMonitor.swift` | `NWPathMonitor` → `isOnline` |
| `ios/FitrahTube/App/Route.swift`, `Router.swift`, `DeepLinkParser.swift` | typed routes, per-tab stacks, pending deep link |
| `ios/FitrahTube/App/SplashRouter.swift` | pure routing function (guest variant) |
| `ios/FitrahTube/App/AppContainer.swift` (extend) | new dependencies + `fake()` |
| `ios/FitrahTube/App/RootView.swift` (replace) | splash → onboarding → shell |
| `ios/FitrahTube/DesignSystem/Components.swift` | `MediaCard`, `VideoRow`, `VideoGridCell`, `ChannelRow`, `HomeChannelItem`, `PlaylistRow`, `SectionHeader`, `CategoryPill`, `CategoryChip`, `Badge`, `DurationChip`, `TransientBanner`, `RemoteImage`, skeleton variants |
| `ios/FitrahTube/DesignSystem/Layout.swift` | `GridRules`, `CarouselRules`, `widthClass` plumbing, `onContentFits` |
| `ios/FitrahTube/Features/Splash/SplashView.swift`, `Onboarding/OnboardingView.swift` | |
| `ios/FitrahTube/Features/Shell/MainShellView.swift`, `OfflineBanner.swift` | |
| `ios/FitrahTube/Features/Home/HomeView.swift`, `HomeViewModel.swift` | |
| `ios/FitrahTube/Features/Lists/ContentListView.swift`, `ContentListViewModel.swift`, `PaginationGuard.swift` | shared by Channels/Playlists/Videos |
| `ios/FitrahTube/Features/Featured/FeaturedView.swift`, `FeaturedViewModel.swift` | |
| `ios/FitrahTube/Features/Search/SearchView.swift`, `SearchViewModel.swift` | |
| `ios/FitrahTube/Features/Categories/CategoriesView.swift`, `SubcategoriesView.swift`, `CategoriesViewModel.swift` | |
| `ios/FitrahTube/Features/Favorites/FavoritesView.swift`, `FavoritesViewModel.swift` | |
| `ios/FitrahTube/Features/Me/MeGuestView.swift` | phase-1 Me tab |
| `ios/FitrahTube/Features/Settings/SettingsView.swift`, `AboutView.swift`, `DeveloperDialog.swift` | |
| `ios/FitrahTube/Features/Placeholders/PhaseTwoPlaceholderView.swift` | player / channel / playlist / shorts stand-ins |
| `ios/FitrahTubeTests/…` | one test file per unit listed below |
| `docs/architecture/api-specification.yaml` (modify) | `CategoryDto` + optional `displayOrder`, `localizedNames`, `icon` |

---

### Task 1: Strings converter → `Localizable.xcstrings`

**Files:**
- Create: `ios/scripts/convert-strings.py`
- Create: `ios/FitrahTube/Resources/Localizable.xcstrings` (generated)
- Modify: `ios/project.yml` (`CFBundleLocalizations` already en/ar/nl; add `Resources` to sources if not globbed)
- Test: `ios/FitrahTubeTests/LocalizationTests.swift`

**Interfaces:**
- Produces: every non-dead Android key as an xcstrings key (verbatim), en as source language, ar/nl entries only when translated (`strings-assets.md:303-313` R7), plurals as xcstrings plural variations, the two decoupled-quantity plurals via substitutions (`strings-assets.md:147-182`, RULINGS 37), specifier rewrite `%N$s→%N$@`, `%N$d→%N$lld`, `%%` first (R3), positional mapping for bare `%s/%d` (R4), Android unescaping (R5), the dead-key list skipped (`strings-assets.md:328-365`), refusal list reported (R8, §7).
- Consumes: `android/app/src/main/res/values{,-ar,-nl}/strings*.xml`.

- [ ] **Step 1: Write the failing test** — `ios/FitrahTubeTests/LocalizationTests.swift`

```swift
import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct LocalizationTests {
    private func string(_ key: String, locale: String, _ args: CVarArg...) -> String {
        let bundle = Bundle.main.path(forResource: locale, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: Locale(identifier: locale), arguments: args)
    }

    @Test func englishKeysResolve() {
        #expect(string("app_name", locale: "en") == "FitrahTube")
        #expect(string("home_see_all", locale: "en") == "See all")
    }

    @Test func arabicAppNameIsTranslated() {
        #expect(string("app_name", locale: "ar") == "فطرة تيوب")
    }

    @Test func untranslatedKeyFallsBackToEnglish() {
        // `about_version_format` is English-only on Android (strings-assets.md §2)
        #expect(string("about_version_format", locale: "nl", "1.0.0", "7").contains("1.0.0"))
    }

    @Test func pluralSelectsCategory() {
        #expect(string("video_count", locale: "en", 1) == "1 video")
        #expect(string("video_count", locale: "en", 3) == "3 videos")
    }
}
```
If a key named above does not exist in Android (check `strings-assets.md:429-717` for the exact phase-1 key list), substitute the nearest real key and say so in the report.

- [ ] **Step 2: Run it to verify it fails** — `cd ios && ./scripts/test.sh 2>&1 | tail -3` → compile passes, tests fail on missing strings (or fail to find keys).

- [ ] **Step 3: Write the converter** — `ios/scripts/convert-strings.py`

```python
#!/usr/bin/env python3
"""Android strings*.xml (en, ar, nl) -> ios/FitrahTube/Resources/Localizable.xcstrings.
Rules R1-R9 from docs/superpowers/plans/2026-08-23-ios-phase1-research/strings-assets.md.
Usage: python3 ios/scripts/convert-strings.py [--check]"""
import glob, html, json, os, re, sys, xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "android/app/src/main/res")
OUT = os.path.join(ROOT, "ios/FitrahTube/Resources/Localizable.xcstrings")
LOCALES = {"en": "values", "ar": "values-ar", "nl": "values-nl"}
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")
# R8 / §7 of strings-assets.md: keys that cannot convert mechanically are listed here and skipped with a warning.
REFUSE = set()
# §6 dead keys: regenerated from the brief's list at run time would be brittle; keep the explicit prefixes it names.
DEAD_PREFIXES = ("filter_length_", "filter_date_", "filter_sort_", "list_", "locale_settings_", "error_")

def unescape(s):  # R5
    s = html.unescape(s)
    s = re.sub(r"\\(['\"@?])", r"\1", s)
    s = s.replace("\\n", "\n").replace("\\t", "\t")
    return s

def rewrite_specifiers(s):  # R3 + R4
    s = s.replace("%%", "\u0000PCT\u0000")
    n = [0]
    def bare(m):
        n[0] += 1
        conv = {"s": "@", "d": "lld", "f": "f"}[m.group(2)]
        return f"%{n[0]}${conv}"
    s = re.sub(r"%((?!\d)\.?\d*)([sdf])", bare, s)
    s = re.sub(r"%(\d+)\$s", r"%\1$@", s)
    s = re.sub(r"%(\d+)\$d", r"%\1$lld", s)
    return s.replace("\u0000PCT\u0000", "%%")

def load(locale_dir):
    strings, plurals = {}, {}
    for path in sorted(glob.glob(os.path.join(RES, locale_dir, "strings*.xml"))):
        root = ET.parse(path).getroot()
        for el in root:
            name = el.get("name")
            if el.get("translatable") == "false" or name is None:
                continue
            if el.tag == "string":
                text = "".join(el.itertext())
                if name in strings:  # R2: later file wins, report collision
                    print(f"warn: duplicate key {name} in {path}", file=sys.stderr)
                strings[name] = rewrite_specifiers(unescape(text))
            elif el.tag == "plurals":
                plurals[name] = {item.get("quantity"): rewrite_specifiers(unescape("".join(item.itertext())))
                                 for item in el.findall("item")}
    return strings, plurals

def main(check=False):
    data = {loc: load(d) for loc, d in LOCALES.items()}
    en_strings, en_plurals = data["en"]
    out = {"sourceLanguage": "en", "version": "1.0", "strings": {}}
    skipped = []
    for key, en in en_strings.items():
        if key in REFUSE or key.startswith(DEAD_PREFIXES):
            skipped.append(key); continue
        locs = {"en": {"stringUnit": {"state": "translated", "value": en}}}
        for loc in ("ar", "nl"):
            v = data[loc][0].get(key)
            if v is not None and v != en:  # R7: never copy English as a translation
                locs[loc] = {"stringUnit": {"state": "translated", "value": v}}
        out["strings"][key] = {"localizations": locs}
    for key, forms in en_plurals.items():
        if key in REFUSE:
            skipped.append(key); continue
        locs = {}
        for loc in ("en", "ar", "nl"):
            f = data[loc][1].get(key)
            if not f: continue
            plural = {cat: {"stringUnit": {"state": "translated", "value": f[cat]}} for cat in PLURAL_CATEGORIES if cat in f}
            locs[loc] = {"variations": {"plural": plural}}
        out["strings"][key] = {"localizations": locs}
    if check:
        print(f"{len(out['strings'])} keys, {len(skipped)} skipped"); return 0
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2, sort_keys=True)
    print(f"✅ wrote {OUT}: {len(out['strings'])} keys; skipped {len(skipped)} dead/refused")
    return 0

if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv))
```
Then apply the two substitution-form plurals by hand-coding them in `REFUSE` → emit them explicitly per `strings-assets.md:147-182` (add a small `SUBSTITUTIONS = {...}` block that writes the `substitutions` object for `video_views` and `live_watching_count`). If Xcode rejects the substitutions form at build time, fall back per RULINGS 37 (`video_views_one`/`video_views_other` keys chosen in Swift) and document it.

- [ ] **Step 4: Generate, wire, and run** — `python3 ios/scripts/convert-strings.py && cd ios && ~/.local/bin/xcodegen generate -q && ./scripts/test.sh | tail -4`. Expected: all phase-0 tests + 4 localisation tests pass on both simulators. Inspect `Localizable.xcstrings` in Xcode once for warnings (Product → Build shows "string catalog" diagnostics); every diagnostic is a finding to fix in the script, not by hand-editing the catalog.

- [ ] **Step 5: Commit** — `git add ios/scripts/convert-strings.py ios/FitrahTube/Resources/Localizable.xcstrings ios/FitrahTubeTests/LocalizationTests.swift ios/project.yml && git commit -m "[FEAT]: iOS string catalog generated from Android"`

---

### Task 2: Domain models, `CategoryDto` spec fix, `CatalogClient` extended

**Files:**
- Modify: `docs/architecture/api-specification.yaml` (`CategoryDto`: add optional `displayOrder: integer`, `localizedNames: object<string,string>`, `icon: string` — match `backend/src/main/java/.../dto/CategoryDto.java` field names exactly)
- Regenerate: `ios/scripts/generate-swift-dtos.sh` (+ `./scripts/generate-openapi-dtos.sh` for TS/Kotlin; zero-drift expected beyond the additive fields)
- Create: `ios/FitrahTube/Catalog/Models.swift`
- Modify: `ios/FitrahTube/Catalog/CatalogClient.swift`, `LiveCatalogClient.swift`, `FakeCatalogClient.swift`
- Test: `ios/FitrahTubeTests/LiveCatalogClientTests.swift` (extend), `ios/FitrahTubeTests/ModelsTests.swift`

**Interfaces:**
```swift
nonisolated enum ContentType: String, Sendable { case video = "VIDEO", channel = "CHANNEL", playlist = "PLAYLIST" }
nonisolated struct ContentItem: Identifiable, Hashable, Sendable {
    let id: String; let type: ContentType; let title: String
    let category: String?; let description: String?; let thumbnailURL: URL?
    let durationSeconds: Int?; let uploadedDaysAgo: Int?; let viewCount: Int64?
    let channelTitle: String?; let subscribers: Int64?; let videoCount: Int?; let itemCount: Int?
}
nonisolated struct CursorPage<Item: Sendable & Hashable>: Sendable, Hashable { let items: [Item]; let nextCursor: String?; var hasMore: Bool { nextCursor != nil } }
nonisolated struct HomeSection: Identifiable, Hashable, Sendable { let id: String /* categoryId */; let name: String; let localizedNames: [String: String]?; let icon: String?; let items: [ContentItem] }
nonisolated struct Category: Identifiable, Hashable, Sendable { let id, name, slug: String; let parentId: String?; let displayOrder: Int?; let localizedNames: [String: String]?; let icon: String? }
nonisolated struct FilterState: Hashable, Sendable { var categoryId: String?; var categoryName: String?; var length: String?; var date: String?; var sort: String? }
nonisolated enum ListType: String, Sendable { case videos = "VIDEOS", channels = "CHANNELS", playlists = "PLAYLISTS", all = "ALL" }
nonisolated protocol CatalogClient: Sendable {
    func categories() async throws -> [Category]
    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection>
    func content(type: ListType, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem>
    func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem]
}
```
`Category.displayName(for locale: Locale) -> String` lives in `Formatting.swift` (Task 3). `FakeCatalogClient` gains configurable fixtures: `init(categories:homePages:contentPages:searchResults:)` with defaults producing 2 sections × 10 items and 3 content pages of 20.

- [ ] **Step 1: Fix the spec and regenerate** — add the three optional properties; run `./ios/scripts/generate-swift-dtos.sh`; run `./scripts/generate-openapi-dtos.sh` (Swift step + TS; Kotlin needs `export JAVA_HOME="$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home"`). `git status` must show only additive generated changes.
- [ ] **Step 2: Write failing tests** — `ModelsTests.swift`: `CursorPage.hasMore` true/false; `LiveCatalogClientTests`: `home` maps `HomeCategoryDto` → `HomeSection` (fixture JSON from `shell-home.md:435-470`), `content` sends `type=VIDEOS&limit=20&category=c1` and omits nil filters (assert `transport.lastRequest?.path` and query via `HTTPRequest.path`), `search` sends `q` and returns items in server order.
- [ ] **Step 3: Implement** models, protocol, live mapping (`ContentItemDto` → `ContentItem`: `title ?? name`), fake.
- [ ] **Step 4: Run** `ios/scripts/test.sh` → all pass. **Step 5: Commit** `[FEAT]: Catalog models and client endpoints`.

---

### Task 3: Formatting helpers

**Files:** Create `ios/FitrahTube/Catalog/Formatting.swift`; Test `ios/FitrahTubeTests/FormattingTests.swift`.

**Interfaces** (contracts `content-lists.md:462-491`, `strings-assets.md:147-182`, RULINGS contradictions 3–4):
```swift
nonisolated enum Format {
    static func duration(_ seconds: Int) -> String          // "h:mm:ss" / "m:ss", Western digits (Locale(identifier: "en_US_POSIX"))
    static func compactCount(_ n: Int64, locale: Locale) -> String   // 999 → "999", 1200 → "1.2K", 1_500_000 → "1.5M", 2_000_000_000 → "2B"
    static func pluralQuantity(_ n: Int64) -> Int            // clamp ≥1000 → 1000 so the plural category is `other`
    static func timeAgo(days: Int, locale: Locale) -> String // today / N days / N weeks / N months / N years via keys time_ago_* (names from strings-assets §8a)
    static func categoryDisplayName(_ c: Category, locale: Locale) -> String  // localizedNames[lang] ?? name
}
```
- [ ] Write failing tests for each (incl. `viewCount == nil → nil` handled by callers; `timeAgo(days: 0) == "Today"` in en; Arabic locale `ar_EG` produces Eastern digits for counts via `formatted()`), run (fail), implement with `Measurement`-free integer math and `NumberFormatter(locale:)`, run (pass), commit `[FEAT]: Catalog formatting helpers`.

---

### Task 4: Settings, filter, search-history stores + network monitor

**Files:** Create `Persistence/SettingsStore.swift`, `Persistence/FilterStore.swift`, `Persistence/SearchHistoryStore.swift`, `App/NetworkMonitor.swift`; Tests `SettingsStoreTests.swift`, `FilterStoreTests.swift`, `SearchHistoryStoreTests.swift`.

**Interfaces** (contract `favorites-settings-about.md:322-350`, `content-lists.md:510-599`, `search-categories.md:63-84`):
```swift
@MainActor protocol SettingsStore: AnyObject, Observable {
    var appLocale: String { get set }           // "system" | "en" | "ar" | "nl"
    var theme: String { get set }               // "system" | "light" | "dark"
    var audioOnly: Bool { get set }; var backgroundPlay: Bool { get set }; var safeMode: Bool { get set }
    var downloadQuality: String { get set }; var wifiOnlyDownloads: Bool { get set }
    var onboardingCompleted: Bool { get set }; var importOfferShown: Bool { get set }
    var resolvedLocale: Locale { get }          // system → preferredLanguages ∩ {en,ar,nl} else en
    var colorScheme: ColorScheme? { get }       // nil for system
}
@MainActor final class UserDefaultsSettingsStore: SettingsStore { init(defaults: UserDefaults = .standard) }
@MainActor protocol FilterStore: AnyObject, Observable { var state: FilterState { get }; func setCategory(id: String?, name: String?); func clearCategory() }
@MainActor protocol SearchHistoryStore: AnyObject, Observable { var entries: [String] { get }; func add(_ q: String); func remove(_ q: String); func clear() }
@MainActor @Observable final class NetworkMonitor { private(set) var isOnline = true; init(start: Bool = true) }
```
Persist with the verbatim keys; `UserDefaults` defaults registered (`background_play` true, `safe_mode` true, `download_quality` "medium"). Tests use a fresh `UserDefaults(suiteName:)` with `defer removePersistentDomain` (same pattern as Phase 0).

- [ ] Tests (fail) → implement → tests (pass) → commit `[FEAT]: Settings, filter and history stores`.

---

### Task 5: Favorites (SwiftData) + categories cache

**Files:** Create `Persistence/FavoriteVideo.swift`, `Persistence/FavoritesStore.swift`, `Persistence/CategoriesCache.swift`; Tests `FavoritesStoreTests.swift` (in-memory `ModelConfiguration(isStoredInMemoryOnly: true)`), `CategoriesCacheTests.swift`.

**Interfaces** (contract `favorites-settings-about.md:27-63`, Room columns from spec §2E/synthesis; RULINGS 26–27, 30):
```swift
@Model final class FavoriteVideo {
    @Attribute(.unique) var videoId: String
    var title: String; var channelName: String; var thumbnailUrl: String?; var durationSeconds: Int
    var addedAt: Date; var userId: String; var updatedAt: Date; var deleted: Bool; var dirty: Bool
    var approvalStatus: String; var source: String?; var importedAt: Date?
}
@MainActor protocol FavoritesStore: AnyObject, Observable {
    var items: [FavoriteVideo] { get }                  // userId == current, deleted == false, approvalStatus == "APPROVED", addedAt desc
    func isFavorite(_ videoId: String) -> Bool
    func toggle(_ item: ContentItem) throws             // insert or soft-delete (deleted=true, dirty=true)
    func clearAll() throws                              // soft-delete all (RULINGS 30)
}
@MainActor protocol CategoriesCache: AnyObject, Observable {
    var all: [Category] { get }; var isLoading: Bool { get }; var error: Error? { get }
    func loadIfNeeded() async; func reload() async
    func topLevel() -> [Category]; func children(of id: String) -> [Category]   // sorted displayOrder then localized name
    func displayName(for id: String, locale: Locale) -> String?
}
```
Anonymous user id sentinel `""` as Android. `AppContainer` gets a `ModelContainer` (`FavoriteVideo.self`), in-memory in `fake()`.

- [ ] Tests → implement → pass → commit `[FEAT]: Favorites store and categories cache`.

---

### Task 6: Design-system components and layout rules

**Files:** Create `DesignSystem/Components.swift`, `DesignSystem/Layout.swift`; Test `ios/FitrahTubeTests/LayoutRulesTests.swift`. Contracts: spec §7 component list; metrics in `content-lists.md:380-460` (rows/cells), `shell-home.md:215-290` (cards/carousel/header/pill), `strings-assets.md:771-812` (shapes), `splash-onboarding.md:284-310` (dots).

**Interfaces:**
```swift
nonisolated enum GridRules {
    static func listColumns(_ w: WidthClass) -> Int            // 1 / 3 / 4  (channels, playlists)
    static func videoColumns(width: CGFloat) -> Int             // max(2, min(8, Int(width / 180)))
    static func carouselVisible(_ type: ContentType, _ w: WidthClass) -> Int  // vid 2/3/5, ch 2/4/6, pl 2/3/5
    static func carouselCardWidth(container: CGFloat, margin: CGFloat, gap: CGFloat, visible: Int) -> CGFloat // ((w − 2m − (n−1)g)/n)·0.98
}
struct RemoteImage: View { init(url: URL?, contentMode: ContentMode = .fill) }   // URLSession + URLCache(memory 50 MB, disk 200 MB), placeholder = skeleton colour
struct MediaCard: View { init(item: ContentItem, width: CGFloat, onTap: () -> Void) }
struct VideoRow: View, VideoGridCell: View, ChannelRow: View, HomeChannelItem: View, PlaylistRow: View { init(item: ContentItem, onTap: () -> Void) }
struct SectionHeader: View { init(emoji: String?, title: String, onSeeAll: (() -> Void)?) }
struct CategoryPill: View { init(label: String, isActive: Bool, onTap: () -> Void, onClear: () -> Void) }
struct CategoryChip: View { init(text: String) }; struct Badge: View { enum Kind { case live, upcoming }; init(_ kind: Kind) }
struct DurationChip: View { init(seconds: Int) }
struct TransientBanner: ViewModifier { init(message: Binding<BannerMessage?>) } // BannerMessage { text, actionTitle?, action? }; 2.5 s auto-dismiss; AccessibilityNotification.Announcement
struct SkeletonGrid: View { init(columns: Int, rows: Int) }; struct SkeletonCarousel: View { init(cards: Int) }
extension View { func onContentFits(_ fits: @escaping (Bool) -> Void) -> some View } // onScrollGeometryChange: contentSize.height <= containerSize.height
```
- [ ] Step 1 tests for `GridRules` (all three width classes, the 0.98 factor, the video clamp at 359/360/1440/1441 pt) → fail → implement → pass.
- [ ] Step 2 build every component with a `#Preview` in light and dark, compact and regular, LTR and `.environment(\.layoutDirection, .rightToLeft)`; take one screenshot per preview group from the simulator (Xcode previews aren't scriptable: put all components on a temporary `ComponentsGallery` view reachable from the Developer dialog in Task 14) — gallery screenshots are the acceptance artefact.
- [ ] Commit `[FEAT]: iOS design-system components`.

---

### Task 7: Routes, router, deep links, splash routing, shell

**Files:** Create `App/Route.swift`, `App/Router.swift`, `App/DeepLinkParser.swift`, `App/SplashRouter.swift`, `Features/Shell/MainShellView.swift`, `Features/Shell/OfflineBanner.swift`, `Features/Placeholders/PhaseTwoPlaceholderView.swift`; Modify `App/AppContainer.swift`, `App/RootView.swift`, `App/FitrahTubeApp.swift`; Tests `DeepLinkParserTests.swift`, `SplashRouterTests.swift`, `RouterTests.swift`. Contracts: `shell-home.md:31-105`, `splash-onboarding.md:120-184`, spec §6, RULINGS 4, 7, 9.

**Interfaces:**
```swift
nonisolated enum Tab: Int, CaseIterable, Sendable { case home, channels, me, playlists, videos }
nonisolated enum Route: Hashable, Sendable {
    case player(PlayerArgs), shorts(id: String), channel(id: String, name: String?, avatarURL: URL?), playlist(id: String, title: String?, category: String?, count: Int?)
    case search, categories, subcategories(parentId: String, parentName: String), featured(categoryId: String?, categoryName: String?)
    case favorites, settings, about
}
nonisolated struct PlayerArgs: Hashable, Sendable { let videoId: String; var playlistId: String? = nil; var title: String? = nil; var channelName: String? = nil; var thumbnailURL: URL? = nil; var description: String? = nil; var durationSeconds: Int? = nil; var viewCount: Int64? = nil; var channelId: String? = nil }
@MainActor @Observable final class Router {
    var selectedTab: Tab = .home; var paths: [Tab: [Route]]; var pendingRoute: Route?
    func push(_ r: Route); func popToRoot(_ t: Tab); func reselect(_ t: Tab) -> ReselectAction /* .popToRoot | .scrollToTop */
    func open(_ url: URL)                                  // parses; if shell not ready → pendingRoute
}
nonisolated enum DeepLinkParser { static func route(for url: URL) -> Route? }
// albunyaantube://video|channel|playlist|shorts/{id}; https://app.fitrahtube.com/watch|channel|playlist/{id} and /api/watch|channel|playlist/{id}
nonisolated enum SplashDestination: Equatable { case onboarding, main }
nonisolated enum SplashRouter { static func destination(onboardingCompleted: Bool) -> SplashDestination }  // phase 4 adds the account branches
```
`MainShellView`: `TabView(selection:)` + `.tabViewStyle(.sidebarAdaptable)`, each tab a `NavigationStack(path:)` with `.navigationDestination(for: Route.self)`; `OfflineBanner` overlay at top when `!monitor.isOnline` (`errorBackground`/`errorText`, text key per `shell-home.md:71-105`); tab bar hidden when `router.isFullscreen` (phase 2 sets it). Tab reselect: `onChange(of: selectedTab)` with the previous value equal → `reselect`. `PhaseTwoPlaceholderView(route:)` shows the route's case name and all args as a labelled list (this is how phase-1 navigation is verified).

- [ ] Tests: parser (all 10 URL shapes + 2 invalid), `SplashRouter` both branches, `Router.reselect` semantics and `pendingRoute` applied once on `shellDidAppear()`. Implement. `RootView`: `SplashView` (Task 8) → `OnboardingView` or `MainShellView`. Run gate. Commit `[FEAT]: iOS shell, router and deep links`.

---

### Task 8: Splash and onboarding

**Files:** Create `Features/Splash/SplashView.swift`, `Features/Onboarding/OnboardingView.swift`, `Resources/Assets.xcassets/logo.imageset` (copy `android/app/src/main/res/drawable/albunyaantube_logo.png`), `AppIcon.appiconset` (1024 px generated: `sips -z 1024 1024` of the logo on a `#275E4B` plate via a 10-line Python/PIL-free `sips` + `ImageMagick`-free approach — if no tool can composite, use the logo on white and note RULINGS 1). Contracts: `splash-onboarding.md:65-118` (timeline), `:190-335` (onboarding pages, metrics, strings), RULINGS 2–5.

- [ ] `SplashView`: logo at t=0; name fade+slide (400 ms, 30 pt) at 600 ms; tagline at 1150 ms; spinner at 1550 ms; completion at `max(2750 ms, work)` where work = `container.categories.loadIfNeeded()` warm-up + (phase 2) remote config, capped at 3250 ms; Reduce Motion → no slide. Deep link present → skip straight to routing.
- [ ] `OnboardingView`: `TabView(.page)` with 3 pages (strings `onboarding_*`, SF Symbols per RULINGS contradiction 6), dots (`brand` / `textMuted`), `Skip` and `Next`/`Get started`; `interactiveDismissDisabled`; persist `onboardingCompleted = true` **before** dismissing.
- [ ] Manual check on 3 simulators × en/ar; screenshots. Commit `[FEAT]: iOS splash and onboarding`.

---

### Task 9: Home

**Files:** Create `Features/Home/HomeViewModel.swift`, `HomeView.swift`; Test `HomeViewModelTests.swift`. Contracts: `shell-home.md:107-496` (header, pill, state matrix, sections, carousel, `/home` params, paging, fix-list), RULINGS 10–13, 16–17, contradictions 2–4.

**Interfaces:**
```swift
@MainActor @Observable final class HomeViewModel {
    enum State: Equatable { case loading, content(sections: [HomeSection], hasMore: Bool, isLoadingMore: Bool), error, empty }
    private(set) var state: State = .loading
    init(catalog: CatalogClient, filter: FilterStore, widthClass: () -> WidthClass)
    func load() async            // reads persisted filter first (no double fetch); categoryLimit 5, contentLimit 10 compact / 20 otherwise
    func refresh() async         // keeps content visible while refreshing (RULINGS 12)
    func loadMore() async        // dedupe by section id; silent on failure; in-flight flag set before await
    func clearFilter()
    func playerArgs(for item: ContentItem) -> PlayerArgs   // channelName = channelTitle ?? category (RULINGS 17)
}
```
- [ ] Tests with `FakeCatalogClient`: initial load uses persisted category; `loadMore` dedupes and stops when `hasMore == false`; failure on `loadMore` leaves `.content`; `refresh` never shows `.loading` when content exists; `playerArgs` mapping.
- [ ] `HomeView`: header (24 pt bold title, search + overflow menu with Favorites/Settings — Downloads arrives in phase 3), `CategoryPill` bound to `FilterStore`, per-state body (skeleton = `SkeletonCarousel(cards: 4)`, error card with Retry, empty card with Clear filter when a filter is active), sections as `LazyVStack` of `SectionHeader` + horizontal `ScrollView`/`LazyHStack` of `MediaCard`/`HomeChannelItem` with `GridRules.carouselCardWidth`, footer spinner, `.refreshable`, `onContentFits` → `loadMore`, scroll threshold ≈ 200 pt from the end. `scrollToTop` on tab reselect via `ScrollViewReader`.
- [ ] Manual matrix + screenshots; commit `[FEAT]: iOS Home`.

---

### Task 10: Channels / Playlists / Videos tabs

**Files:** Create `Features/Lists/ContentListViewModel.swift`, `PaginationGuard.swift`, `ContentListView.swift`; Test `ContentListViewModelTests.swift`, `PaginationGuardTests.swift`. Contracts: `content-lists.md:24-400` (state, loading types, inline search, columns, guards, matrices, chips, snackbar), RULINGS 13–16, 22.

**Interfaces:**
```swift
nonisolated enum LoadKind: Sendable { case initial, refresh, pagination }
@MainActor @Observable final class ContentListViewModel {
    enum State: Equatable { case loading(LoadKind), content(items: [ContentItem], hasMore: Bool, paginationError: Bool, isSearchActive: Bool), error }
    init(type: ListType, catalog: CatalogClient, filter: FilterStore, pageSize: Int = 20)
    var query: String { didSet }           // 300 ms debounce; <2 chars → no q; one Task per load kind
    func load() async; func refresh() async; func loadMore() async; func retryPagination() async
}
nonisolated struct PaginationGuard {         // content-lists.md:219-237
    var attempts = 0; let maxAttempts = 5; var lastCount = 0
    mutating func shouldAutoLoad(widthClass: WidthClass, hasMore: Bool, paginationError: Bool, contentFits: Bool, itemCount: Int) -> Bool
}
```
- [ ] Tests: every guard (≥600 pt gate, attempts cap, progress invariant, error gate), debounce timing (use a `ContinuousClock` injection), cursor/hasMore, the snackbar flag.
- [ ] `ContentListView(type:)` used by three tab roots: inline search field in the header, `CategoryChip` "Category: %1$@" when active, Categories toolbar button on Channels only, list (1 column rows) vs grid per `GridRules`, skeleton mirroring the layout, empty (inside `.refreshable` scroll, Clear filter when filtered), error, `TransientBanner` on pagination error with Retry. Taps: channel → `.channel(...)`, playlist → `.playlist(...)`, video → `.player(args)`.
- [ ] Manual matrix; commit `[FEAT]: iOS content list tabs`.

---

### Task 11: Featured, Search, Categories

**Files:** Create `Features/Featured/FeaturedViewModel.swift` + `FeaturedView.swift`, `Features/Search/SearchViewModel.swift` + `SearchView.swift`, `Features/Categories/CategoriesViewModel.swift` + `CategoriesView.swift` + `SubcategoriesView.swift`; Tests `FeaturedViewModelTests.swift`, `SearchViewModelTests.swift`, `CategoriesViewModelTests.swift`. Contracts: `content-lists.md:603-701`, `search-categories.md:40-149, 259-281, 336-412, 471-581`, RULINGS 19–20, 22–29.

**Interfaces:**
```swift
@MainActor @Observable final class FeaturedViewModel {  // probe /home → sections mode else flat /content?type=ALL&limit=50
    enum Mode: Equatable { case sections([HomeSection]), flat([ContentItem]) }
    enum State: Equatable { case loading, content(Mode, hasMore: Bool), error(String), empty }
    static let featuredCategoryId = "itirf9pGpAvoBT5VSkEc"
    init(categoryId: String?, categoryName: String?, catalog: CatalogClient); func load() async; func refresh() async; func loadMore() async
}
@MainActor @Observable final class SearchViewModel {
    enum State: Equatable { case zero(history: [String]), loading, results([ContentItem]), noResults, error }
    init(catalog: CatalogClient, history: SearchHistoryStore); var query: String { didSet /* 500 ms, ≥2 chars */ }
    func submit() async /* bypasses debounce+min, writes history */; func selectHistory(_ q: String) async; func removeHistory(_ q: String); func clearHistory(); func retry() async
}
@MainActor @Observable final class CategoriesViewModel { init(cache: CategoriesCache, filter: FilterStore); func select(_ c: Category) -> CategorySelection /* .drillDown | .applied(label) */ }
```
`select` applies `filter.setCategory(id:name:)` with the localized name and the "Parent › Sub" format key for subcategories; the view pops to the origin and shows the banner "Filtering by: X".
- [ ] Tests, implement, views (Featured has `.refreshable` + empty state; Search zero-state with history rows or an `EmptyStateView` prompt, error with Retry; Categories/Subcategories with skeleton/error/empty), manual matrix, commit `[FEAT]: iOS Featured, Search and Categories`.

---

### Task 12: Favorites screen and guest Me tab

**Files:** Create `Features/Favorites/FavoritesViewModel.swift`, `FavoritesView.swift`, `Features/Me/MeGuestView.swift`; Test `FavoritesViewModelTests.swift`. Contracts: `favorites-settings-about.md:65-125`, spec D11, RULINGS 30–31.
- [ ] `FavoritesView`: rows (`VideoRow`) → `.player(PlayerArgs(videoId:title:channelName:thumbnailURL:durationSeconds:))`, swipe/trailing "Remove", toolbar "Clear all" with confirmation, `EmptyStateView`. `MeGuestView`: sign-in card (copy from `strings` `me_*`/`auth_*` keys if present, else new keys `me_guest_title`/`me_guest_body`/`me_guest_sign_in` added to the catalog by hand via the converter's `EXTRA` table — keep the Android-key convention) + favorites section with "See all" → `.favorites`. Phase 4 replaces the card with the real Me tab.
- [ ] Tests, implement, manual matrix, commit `[FEAT]: iOS Favorites and guest Me tab`.

---

### Task 13: Settings, About, Developer dialog

**Files:** Create `Features/Settings/SettingsView.swift`, `AboutView.swift`, `DeveloperDialog.swift`; Test `SettingsViewModelTests.swift` (if a VM is needed; otherwise the store tests from Task 4 cover logic). Contracts: `favorites-settings-about.md:139-246, 279-320` (row order, dialogs, switches, sign-out hidden for guests, About rows/URLs, 7-tap gate), RULINGS 32–35.
- [ ] Settings rows in Android order minus Downloads/Storage/Updates (phases 3/none): Language (resolved value; tap → `UIApplication.openSettingsURLString`), Theme picker, Playback switches (audio only, background play), Safe Mode switch, Download quality picker (value only; downloads arrive in phase 3), Wi-Fi-only switch, Library → Favorites, About & Support. Theme applies via `.preferredColorScheme(settings.colorScheme)` at the root.
- [ ] About: rows with the Android URLs verbatim, version "Version %1$@ (%2$@)", 7 taps in 3 s → `DeveloperDialog` (version/build, API base URL, device id, Components gallery link from Task 6).
- [ ] Manual matrix, commit `[FEAT]: iOS Settings and About`.

---

### Task 14: iPad, RTL and accessibility pass

**Files:** touch only what the checks require.
- [ ] Run every screen on iPad mini + iPad Pro 13" in portrait and landscape, Split View 1/3 width (compact), and Stage Manager; in Arabic with RTL; with Dynamic Type `.accessibility3`; with VoiceOver rotor on each list row (label + value). Fix per spec §14. Record screenshots per screen/config under the SDD workspace.
- [ ] Commit `[FIX]: iPad, RTL and accessibility fixes`.

---

### Task 15: Phase gate

- [ ] `ios/scripts/test.sh` clean from `rm -rf ios/DerivedData`; `git status` clean; no untracked artefacts.
- [ ] Mandatory 9-stage pipeline (AGENTS.md) over `git merge-base develop HEAD`..HEAD; Cubic if logged in (else state the block and run `/code-review high ios` rounds until two consecutive rounds show no P0/P1).
- [ ] `CHANGELOG.md` Unreleased: `- iOS: catalog UI — splash, onboarding, shell, Home, Channels/Playlists/Videos, Search, Categories, Featured, Favorites, Settings, About; en/ar/nl (Phase 1).` Commit `[DOCS]: Changelog for iOS phase 1`.
- [ ] Phase 2 plan is written after this gate passes.
