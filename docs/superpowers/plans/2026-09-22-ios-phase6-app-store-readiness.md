# App Store Readiness Implementation Plan (iOS Phase 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.
>
> **Provenance:** drafted 2026-09-21 outside the repo; reconciled 2026-09-22 against HEAD `7abce4f2` on `feature/ios-app` after Tasks 37 and 38 of the Phase 4 plan shipped four of its nine tasks early. Every "verified" below cites a file:line read on 2026-09-22 or a live probe run that day. Anything not re-verified is marked **UNVERIFIED**.

**Goal:** Close spec §15 row 6 — "App Store readiness: manifests, labels, questionnaire, notes, TestFlight" (`docs/superpowers/specs/2026-08-23-ios-app-design.md:299`) — by making a signed Release build of FitrahTube that passes App Store validation, offers every sign-in it advertises, links only to pages that resolve, and can be archived and exported with one script. Everything the engineering side can do **without the owner** is a numbered task; everything only the owner can do is in the OWNER-ONLY section at the end, written for a non-technical reader.

**Architecture:** No new package, no new dependency, no fastlane. Small edits to files that already exist, two shell scripts that follow `copy-firebase-plist.sh`'s shape, and one `ExportOptions.plist`. Build-time facts keep travelling the one way this project already uses: xcconfig/build setting → Info.plist key → one pure reader (the `FITRAH_APPLE_SIGNIN_REGISTERED` pattern, `ios/FitrahTube/Features/Auth/OAuthSignInProvider.swift:104-118`).

**Tech Stack:** Swift 6, SwiftUI, Swift Testing (`@Test`/`#expect`), XcodeGen (`~/.local/bin/xcodegen`), Xcode 26.3 (`ios/project.yml:6`), `/usr/libexec/PlistBuddy`, bash 3.2. One backend task: Spring (`backend/`, `./gradlew test`).

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` — §12 (backend additions: AASA), §14 last bullet (privacy manifest, labels, export compliance flag, age-rating answers, reviewer notes), §15 row 6, §17 (risks), D3 (update-required screen, no in-app updater), D11 (guest mode). Compliance source: `docs/architecture/ios-app-plan.md` §8 (`:304`, backend/infra rows) and §9 (`:318`, guideline text, App Store Connect requirements, checklist). Listing text, review notes, age-rating answers and the privacy-label worksheet are the companion file `docs/superpowers/plans/2026-09-22-ios-phase6-app-store-metadata.md` — a DRAFT for owner review; the owner/agent pastes from it into App Store Connect.

**Predecessor:** `docs/superpowers/plans/2026-09-02-ios-phase4-accounts.md` — its Task 37 (`a21d6b1a`) and Task 38 (`f5a54084`) records, and carry-forwards CF-A-28, CF-A-57, CF-A-58.

---

## What was verified on 2026-09-22

| # | Fact | State at HEAD | Evidence |
|---|---|---|---|
| 1 | Associated-domains entitlement | **DONE** `a21d6b1a` | `ios/FitrahTube/FitrahTube.entitlements:29-32` carries `applinks:app.fitrahtube.com` beside `com.apple.developer.applesignin` |
| 2 | Production AASA | **LIVE, 200** since the 2026-09-22 14:16Z deploy (was 403) | `curl -s -o /dev/null -w "%{http_code}" https://app.fitrahtube.com/.well-known/apple-app-site-association` → `200` (re-probed 2026-09-22 after the deploy). `WellKnownController.java:57-58` lists six paths incl. the `/api/` forms; `WellKnownControllerTest.java:68-69` pins them |
| 3 | Privacy manifest disk-space reason | **DONE** `a21d6b1a` | `PrivacyInfo.xcprivacy:146-153` declares `NSPrivacyAccessedAPICategoryDiskSpace` / `E174.1`; the API is read at `OfflineStorage.swift:56-57`; `FirebaseSeamTests.thePrivacyManifestRequiredReasonTableIsPinned` pins the whole table |
| 4 | About links | **DONE** `a21d6b1a`; GitHub row REMOVED in `7dd85af5` (OQ-1 ruled) | `AboutView.swift:65-71`: `AboutLinks` holds only `legal` — three links on `app.fitrahtube.com` (each 200 on 2026-09-22); no website row, no GitHub row, no Links section (`:91` renders `about_legal` only). `SettingsRowsTests.everyAboutLinkIsOnFitrahtube` (`:125`) pins the three URLs and allows no off-domain host. See Task 2 |
| 5 | Stale "NOT registered" comments | **DONE** `a21d6b1a` | `Debug.xcconfig:6-11`, `Release.xcconfig:4-9`, `FitrahTube.entitlements:8-13`, `SignInCapabilitiesTests.swift:21-27` all now say the owner's team has the capability since 2026-09-20 and the tracked default stays empty on purpose |
| 6 | `Local.xcconfig` | **DONE** `a21d6b1a` (+`7abce4f2`) | `ios/scripts/write-local-xcconfig.sh` exists (61 lines); `ios/Config/Local.xcconfig` is present on this Mac and gitignored (`.gitignore:212`, `git check-ignore -v` confirms). Never read for this plan |
| 7 | Remote config on `main`; `embed` rung | **`embed` PUBLISHED** `7dd85af5` (order pinned); `main` still lacks the file until the merge | raw.githubusercontent `…/main/ios-remote-config.json` → 404 (re-probed 2026-09-22; the file is on this branch only); `ios-remote-config.json:4` is `["visionosHLS", "androidItag18", "embed"]`; `"embed"` is a known strategy (`RemoteConfig.swift:89`); `RemoteConfigTests.swift:225` pins that exact order (embed last) |
| 8 | Update-required screen has no button | **DONE** `7dd85af5` (hidden until the App Store ID is set) | `FitrahTubeApp.swift:485-501` `UpdateRequiredView` passes `EmptyStateView`'s `action:` (`StateViews.swift:11`) only when `AppStoreLink.url(appStoreID:)` (`:505`) returns non-nil; `project.yml:33` `FITRAH_APP_STORE_ID: ""` → `:175` Info.plist key; `UpdateGateTests.swift:52` pins the numeric-only rule |
| 9 | No ExportOptions / archive script / fastlane | **DONE** `7dd85af5`; dry run produced a signed `.ipa` | `ios/ExportOptions.plist` (`plutil -lint` OK) and `ios/scripts/archive.sh` exist; `BUILD_NUMBER=1 bash ios/scripts/archive.sh` on 2026-09-22 → `ARCHIVE SUCCEEDED`, `preflight OK`, `EXPORT SUCCEEDED`, `FitrahTube.ipa` under `ios/DerivedData-Release/Archive/export/`. Still no fastlane |
| 10 | Privacy policy names Sign in with Apple | **DONE** `bd7a038a` (+ Cubic round-2 wording), not yet deployed | `LegalPagesController.java:207-210` names Sign in with Apple (email only, no name); `:179` covers "the Android and iOS apps"; `LAST_UPDATED = "22 September 2026"` (`:38`); `LegalPagesControllerTest.java:113-114` pins "Sign in with Apple" and "iOS" |
| 11 | Verification mail 503 | **DONE** `f5a54084` | `AccountController.java:129-130` answers 503 `{"code":"MAIL_UNAVAILABLE"}`; iOS falls back to Firebase on 500 and 503 (`EmailVerificationViewModelTests.swift:173-182`). Effective on deploy |
| 12 | Support page | **NONE** | `/support` 403, `/` 403, `fitrahtube.com` 404 (2026-09-22). `/terms` and `/privacy` publish `info@albunyaan.tv` (`LegalPagesController.java:35`). Support URL = `/terms` in the metadata file; a `/support` page is not planned until a reviewer objects |
| 13 | Developer dialog ships in Release | TRUE, no action | `DeveloperDialog.swift:20-24` (version, API host, device id); only the Components Gallery is `#if DEBUG` (`:31-35`). Disclosed in the review notes (guideline 2.3.1) |
| 14 | Cast framework needs no Bluetooth string; icon is valid | TRUE, no action | `strings` on `Vendor/GoogleCast.xcframework/ios-arm64/GoogleCast.framework/GoogleCast` → 0 `CoreBluetooth`/`CBCentralManager` hits; it ships its own `PrivacyInfo.xcprivacy`; `icon-1024.png` is 1024×1024, `hasAlpha: no` (`sips`) |
| 15 | Export-compliance flag, Mac/Vision opt-out, version | already true | `ITSAppUsesNonExemptEncryption: false` (`project.yml:134`); `SUPPORTS_MACCATALYST/…_MAC_DESIGNED…/…_XR_DESIGNED…: NO` (`:22-24`); `MARKETING_VERSION "1.0.0"`, `CURRENT_PROJECT_VERSION "1"` (`:28-29`) |
| 16 | Simulator build is unsigned | TRUE | `project.yml:26` `"CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]": NO`. Consequence in Task 11 |

---

## Assumptions (state them; stop and ask if one is false)

1. The App ID `com.albunyaan.tube` has Sign in with Apple enabled in the portal since 2026-09-20 (owner statement, recorded in `FitrahTube.entitlements:10-11`; not verifiable from the repo). **Associated Domains on the same App ID is CONFIRMED** — the 2026-09-22 archive signed with that entitlement without a provisioning error (OWNER-ONLY item 3 is done).
2. The bundled `GoogleService-Info.plist` exists (gitignored, `.gitignore:210`) and carries `CLIENT_ID` and `BUNDLE_ID`. Its values were not read; `write-local-xcconfig.sh` checks them at run time and prints no value.
3. This Mac's Xcode is signed in to team `72PF8SBQR6` with a role that can create a cloud-managed distribution certificate. If `-allowProvisioningUpdates` cannot mint one, Task 8's archive still succeeds and only the export step moves to the owner section.
4. Adding the embed rung to the **published** config before review is already decided (`ios-app-plan.md` §9 checklist). The **bundled** default stays two rungs — not re-decided here.
5. The PR target is `develop` (spec `:301`); the remote config only goes live on the later merge to `main` (`AppContainer.swift:36`).
6. `.onOpenURL` receives Universal Links in the SwiftUI lifecycle (`FitrahTubeApp.swift:81` routes https URLs through `DeepLinkParser`, which strips a leading `api` segment — `DeepLinkParser.swift:28-32`, `DeepLinkParserTests.swift:61-76`). First real proof is on a device after the owner deploys — OWNER-ONLY item 9, never claimed from a simulator.

---

## Gate section — the first real sign-in (2026-09-22)

Phase 4's Tier-3 gate (spec §15 row 4, "end-to-end once Firebase plist exists") is **partially met**, recorded as CF-A-58 in the Phase 4 plan:

| Provider | Status | What blocks it |
|---|---|---|
| **Google** | **PASSED** on the live server, 2026-09-21/22: sign in → favourites + playlists → sign out → sign in → preferences retained | — |
| **Email + password** | **BLOCKED until deploy** | The server answered 200 from a mailer that sent nothing, so neither the server's mail nor Firebase's fallback arrived. Task 38 (`f5a54084`) makes it 503 `MAIL_UNAVAILABLE`; after deploy the app falls back to Firebase's mailer, or the server mails itself once OWNER-ONLY item 1's env vars are set |
| **Sign in with Apple** | **DEVICE-ONLY, not yet run** | Needs a signed device build (entitlement + the portal capability); a simulator cannot prove it |

Two facts learned the hard way, now rules (Task 11): the gate's simulator build is unsigned, so Firebase's keychain write fails (`errSecMissingEntitlement`, -34018) and every provider says "Something went wrong"; and the gate's `xcodebuild test` installs its test host over the app on `iPhone 17` mid-run.

---

## Global Constraints

- **Gate for every iOS task:** `KEEP_RESULTS=1 bash ios/scripts/test.sh` from the repo root; add `RELEASE=1` for any task touching `ios/project.yml`, an xcconfig, entitlements or Info.plist keys. 300 s wall-clock per watchdog window, 60 s per test. One implementer at a time on the iOS build slot — never two `xcodebuild`s at once. **Hold the gate while a manual simulator test is in progress** (Task 11).
- **Never delete `ios/DerivedData/SourcePackages`, `ios/DerivedData-Release/SourcePackages` or `ios/DerivedData-Signed/SourcePackages`.**
- **`ios/FitrahTube/Info.plist` is generated and git-ignored** (`.gitignore:200`). Info.plist keys are edited in `ios/project.yml` → `targets.FitrahTube.info.properties` only.
- **Secrets:** never print, `cat`, log or commit `GoogleService-Info.plist` values, `ios/Config/Local.xcconfig`, or anything under `$HOME/.config/albunyaan/`. `firebase-service-account.json` is never touched. No script in this plan uses `set -x`.
- **Copy rules, absolute:** never "Download", never "ad-free", in any locale, in any string, script message, listing field or review note. Enforced for catalog keys by `LocalizationTests.noKeyWithASwiftCallerCarriesABannedStemInAnyLocale` (`bannedStems`, `LocalizationTests.swift:95-102`). No YouTube hand-off anywhere (owner directive 2026-08-27). User-facing name: FitrahTube. Only `fitrahtube.com` URLs in anything user-facing (owner directive 2026-09-21).
- **Strings go through `ios/scripts/convert-strings.py`** (`EXTRA_KEYS` for iOS-only keys). Never hand-edit `Localizable.xcstrings`.
- **No music-video ids** anywhere. Approved: video `xc7keR2piUM`, channel `UCmMcOjsVehVlEOteyrhjI2Q`, playlist `PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc`.
- **No new `.md` files in the repo** beyond this plan and its metadata companion (owner-approved 2026-09-22).
- **Commits:** `[PREFIX]: Description` (≤50 chars), explicit `git add <path>` only, never push, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Simulator vs device honesty:** a simulator build is unsigned (`project.yml:26`), so entitlements, Sign in with Apple, Google's callback and Universal Links are **not** proven by the gate. Google's callback IS proven by the ad-hoc signed simulator build (Task 11); Apple and Universal Links only on a signed device build (OWNER-ONLY).

## File map

| File | Task | Change |
|---|---|---|
| `ios/FitrahTube/Resources/PrivacyInfo.xcprivacy`, `ios/FitrahTubeTests/FirebaseSeamTests.swift` | 1 | DONE `a21d6b1a` |
| `ios/FitrahTube/Features/Settings/AboutView.swift`, `ios/FitrahTubeTests/SettingsRowsTests.swift` | 2 | DONE `a21d6b1a`; GitHub-row question open |
| `ios/FitrahTube/FitrahTube.entitlements`, `ios/Config/{Debug,Release}.xcconfig`, `ios/FitrahTubeTests/SignInCapabilitiesTests.swift` | 3 | DONE `a21d6b1a` |
| `ios/scripts/write-local-xcconfig.sh`, `ios/Config/Local.xcconfig.example` | 4 | DONE `a21d6b1a`, `7abce4f2` |
| `ios/project.yml` | 5 | + `FITRAH_APP_STORE_ID` setting and Info.plist key |
| `ios/FitrahTube/App/FitrahTubeApp.swift` | 5 | `AppStoreLink` + button on `UpdateRequiredView` |
| `ios/scripts/convert-strings.py` | 5 | + `app_update_required_button` in `EXTRA_KEYS` |
| `ios/FitrahTubeTests/UpdateGateTests.swift` | 5 | + one `@Test` |
| `ios-remote-config.json` (repo root) | 6 | + `"embed"` |
| `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift` | 6 | `count == 3` |
| `backend/.../controller/WellKnownController.java` + test | 7a | DONE `a21d6b1a` |
| `backend/.../controller/LegalPagesController.java` + test | 7b | Apple sentence, `LAST_UPDATED` |
| `ios/scripts/archive.sh`, `ios/ExportOptions.plist` | 8 | new |
| — (no repo file) | 9 | App Store screenshots |
| `backend/.../controller/AccountController.java`, `service/MailService.java` + tests, `ios/FitrahTubeTests/EmailVerificationViewModelTests.swift` | 10 | DONE `f5a54084` |
| — (no repo file; `.gitignore:205` already ignores `/ios/DerivedData-Signed/`) | 11 | manual sign-in build recipe |

---

## Task 1: Declare the disk-space required-reason API — DONE `a21d6b1a`

`PrivacyInfo.xcprivacy:146-153` declares `NSPrivacyAccessedAPICategoryDiskSpace` / `E174.1`; the header comment (`:32-33`) names `OfflineStorage`'s `volumeAvailableCapacityForImportantUsage` read (`OfflineStorage.swift:56-57`). `FirebaseSeamTests.thePrivacyManifestRequiredReasonTableIsPinned` reads the BUILT bundle and pins the full three-row table (UserDefaults CA92.1, SystemBootTime 35F9.1, DiskSpace E174.1), failing on a duplicate category rather than trapping.

- [x] Gate green at `a21d6b1a` (`RELEASE: built`, 1569 tests / 0 failures per the commit body).
- [ ] **Verification at phase gate:** `plutil -lint ios/FitrahTube/Resources/PrivacyInfo.xcprivacy` → `OK`; the pinned test still passes.

---

## Task 2: About screen links that resolve — DONE `a21d6b1a`, one OWNER question open

**Record (2026-09-22, `7dd85af5`).** OQ-1 ruled by the coordinator after the owner did not answer: the GitHub row and its Links section are REMOVED; `everyAboutLinkIsOnFitrahtube` has no exception. The README was ALSO reworded (`bd7a038a`), so both options were taken. `about_github`/`about_links` stay in the catalog as Android-sourced keys (converter keeps them by design).

What shipped (`AboutView.swift:65-74`): the three legal rows point at `https://app.fitrahtube.com/{privacy,terms,licenses}` (each 200 on 2026-09-22); the **website row is removed** (no site answers; Android hides it too); the **GitHub row is KEPT**, repointed from the dead `github.com/albunyaan/albunyaan-tube` (404) to `https://github.com/talibfitrah/albunyaantube` (200). `SettingsRowsTests.everyAboutLinkIsOnFitrahtubeExceptTheSourceRepository` pins the three legal URLs exactly and allows exactly one off-domain URL — that repository — with `https` required on all.

The 2026-09-21 draft had proposed removing the GitHub row too. The implementer kept it (Android has the same row; the owner's 2026-09-21 directive was "only fitrahtube.com exists", read as being about *dead* hosts). This plan records reality and leaves the decision to the owner:

**OPEN OWNER QUESTION (OQ-1).** The GitHub row is one tap from the About screen, and the repository's public `README.md` (read 2026-09-22, `README.md:3`, `:54`, `:79`) describes the project as "an ad-free, admin-curated halal YouTube client", lists "Offline downloads with 30-day expiry" and "background downloads". That is the exact wording the project's own copy rules forbid in the app, and the listing profile `ios-app-plan.md` §9 says draws the 5.2.2/5.2.3 complaint. A reviewer can reach it from inside the app. Options:
- **(a) Remove the row** — `AboutLinks.links` becomes empty and the `about_links` section goes; the test's one named exception is deleted. Two lines, one test edit. Cost: one row Android has and iOS does not.
- **(b) Keep the row and reword three README lines** (`:3`, `:54`, `:79`) so the public page says what the app says ("curated Islamic video library", "Save for offline"). Cost: a README edit on `main`, which is what the link shows.
Default if the owner does not answer before submission: **(a)** — it is the cheaper reversal. Recorded, not decided.

- [x] Gate green at `a21d6b1a`.
- [ ] **Verification at phase gate (network, not a test):** `for p in privacy terms licenses; do curl -s -o /dev/null -w "$p %{http_code}\n" https://app.fitrahtube.com/$p; done` → three `200`s.
- [ ] **OQ-1 answered** and, if (a), the two-line removal committed as `[FIX]: Drop the About GitHub row`.

---

## Task 3: Associated Domains entitlement + retire the stale comments — DONE `a21d6b1a`

`FitrahTube.entitlements:29-32` claims `applinks:app.fitrahtube.com`; its header (`:3-21`) explains both capabilities and that neither is exercised by the unsigned gate. The four stale "NOT registered" comments are rewritten (`Debug.xcconfig:6-11`, `Release.xcconfig:4-9`, `:19-20`, `SignInCapabilitiesTests.swift:21-27`); the tracked `FITRAH_APPLE_SIGNIN_REGISTERED` default stays **empty** so a fork signing with another team does not render a button that cannot finish (ruling F11).

- [x] Release gate green at `a21d6b1a`.
- [ ] **Verification at phase gate:** `plutil -lint ios/FitrahTube/FitrahTube.entitlements` → `OK`.

**Known cost:** the next **device** build needs the App ID to carry Associated Domains. If a device build fails with "provisioning profile doesn't include the com.apple.developer.associated-domains entitlement", that is OWNER-ONLY item 3, not a code bug.

---

## Task 4: `write-local-xcconfig.sh` — DONE `a21d6b1a` + `7abce4f2`

`ios/scripts/write-local-xcconfig.sh` derives `GID_REVERSED_CLIENT_ID` from the bundled plist's `CLIENT_ID` by the SDK's own rule (dot-components reversed, lower-cased — what `SignInCapabilities.googleCallbackSchemeMatches` checks, `OAuthSignInProvider.swift:90`) and writes `FITRAH_APPLE_SIGNIN_REGISTERED = 1`, into `ios/Config/Local.xcconfig`. It prints key names and lengths only (`:11`, `:62`), refuses a `BUNDLE_ID` other than `com.albunyaan.tube` (`:31`), refuses any output path git would track (`:39-42`), is idempotent over other lines (`:51-58`), and `APPLE_SIGNIN_REGISTERED=0|false|no` switches the Apple flag off (`:22`, added in `7abce4f2`). Overrides for a synthetic-plist self-check are `FITRAH_PLIST` / `FITRAH_OUT` (`:19-20`).

- [x] Ran for real on 2026-09-21; the Google button rendered and the Google sign-in completed (Gate section).
- [ ] **Verification at phase gate:** `git check-ignore -v ios/Config/Local.xcconfig` → `.gitignore:212:…`; `git status --short ios/Config/` shows nothing tracked besides `Local.xcconfig.example`; `bash -n ios/scripts/write-local-xcconfig.sh` → exit 0. Never `cat` the result.
- [ ] **Self-check against a synthetic plist** (no real value involved; run from the repo root; `T` must be inside the repo so the gitignore check has a work tree, e.g. under `ios/DerivedData-Signed/` which `.gitignore:205` ignores):

```bash
T="$(mktemp -d "$PWD/ios/DerivedData-Signed/selfcheck.XXXXXX")"
/usr/libexec/PlistBuddy -c 'Add :BUNDLE_ID string com.albunyaan.tube' -c 'Add :CLIENT_ID string 123-AbC.apps.googleusercontent.com' "$T/g.plist" >/dev/null
printf 'API_BASE_URL = keep-me\nGID_REVERSED_CLIENT_ID = stale' > "$T/Local.xcconfig"
FITRAH_PLIST="$T/g.plist" FITRAH_OUT="$T/Local.xcconfig" bash ios/scripts/write-local-xcconfig.sh
diff "$T/Local.xcconfig" - <<'EOF'
API_BASE_URL = keep-me
GID_REVERSED_CLIENT_ID = com.googleusercontent.apps.123-abc
FITRAH_APPLE_SIGNIN_REGISTERED = 1
EOF
echo "self-check: $?"          # expect 0
rm -rf "$T"
```

---

## Task 5: "Update" button on the update-required screen

**Record (2026-09-22, `7dd85af5`).** Done as written; `EmptyStateView.action` had the plan's tuple shape. `FITRAH_APP_STORE_ID` stays `""` until OWNER-ONLY item 5. +1 `@Test`; the button is hidden for anything that is not all-ASCII digits (Arabic-Indic digits, whitespace → nil).

**Goal:** Spec D3's blocking screen gains its one missing control (`FitrahTubeApp.swift:482` TODO). The numeric App Store ID does not exist until the owner creates the app record (OWNER-ONLY item 5), so the button is **hidden while the ID is empty** and the code ships now.

**Files:**
- Modify: `ios/project.yml` — under `settings.base` add `FITRAH_APP_STORE_ID: ""`; under `targets.FitrahTube.info.properties` add `FITRAH_APP_STORE_ID: $(FITRAH_APP_STORE_ID)`. One place, not two xcconfigs: the ID is public and identical for Debug and Release.
- Modify: `ios/FitrahTube/App/FitrahTubeApp.swift:479-492`
- Modify: `ios/scripts/convert-strings.py` (`EXTRA_KEYS`, directly after `app_update_required_message`, `:528-532`)
- Modify: `ios/FitrahTubeTests/UpdateGateTests.swift`

**Interfaces produced:**
```swift
nonisolated enum AppStoreLink {
    /// nil unless `id` is all ASCII digits -- the unset build setting arrives as "".
    static func url(appStoreID: String?) -> URL?
}
```

- [ ] **Step 1: Failing test** in `UpdateGateTests.swift`:

```swift
    @Test func theUpdateButtonExistsOnlyForANumericAppStoreID() {
        #expect(AppStoreLink.url(appStoreID: "1234567890")?.absoluteString == "https://apps.apple.com/app/id1234567890")
        #expect(AppStoreLink.url(appStoreID: "") == nil)          // the tracked default today
        #expect(AppStoreLink.url(appStoreID: nil) == nil)
        #expect(AppStoreLink.url(appStoreID: "id123") == nil)
        #expect(AppStoreLink.url(appStoreID: "12 3") == nil)
    }
```

- [ ] **Step 2: Gate → compile failure** (`AppStoreLink` missing).
- [ ] **Step 3: String.** In `convert-strings.py` `EXTRA_KEYS`:

```python
    "app_update_required_button": {
        "en": "Update",
        "ar": "تحديث",
        "nl": "Bijwerken",
    },
```

  then `python3 ios/scripts/convert-strings.py` (regenerates the catalog; `--check` is what the gate runs).
- [ ] **Step 4: Implement.** Replace the `// TODO:` line and the view:

```swift
nonisolated enum AppStoreLink {
    static func url(appStoreID: String?) -> URL? {
        guard let appStoreID, !appStoreID.isEmpty, appStoreID.allSatisfy(\.isASCII),
              appStoreID.allSatisfy(\.isNumber) else { return nil }
        return URL(string: "https://apps.apple.com/app/id\(appStoreID)")
    }
}

struct UpdateRequiredView: View {
    @Environment(\.openURL) private var openURL
    private let storeURL = AppStoreLink.url(
        appStoreID: Bundle.main.object(forInfoDictionaryKey: "FITRAH_APP_STORE_ID") as? String)

    var body: some View {
        EmptyStateView(
            systemImage: "arrow.down.circle.fill",
            title: String(localized: "app_update_required_title"),
            message: String(localized: "app_update_required_message"),
            action: storeURL.map { url in (title: String(localized: "app_update_required_button"), run: { openURL(url) }) }
        )
        .background(Color.background.ignoresSafeArea())
    }
}
```

- [ ] **Step 5: Gate with Release** → green, +1 `@Test`, `RELEASE: built`.
- [ ] **Step 6: Commit** — `[FEAT]: Update button on the update-required screen`.

**Follow-up after OWNER-ONLY item 5:** put the real number in `project.yml`'s `FITRAH_APP_STORE_ID`, re-run the Release gate, commit `[CHORE]: Set the App Store ID`.

---

## Task 6: Publish the embed rung

**Record (2026-09-22, `7dd85af5`).** Done; the review turned the count pin into an ORDER pin (`embed` last — a count check passed with YouTube's player first). Post-approval decision recorded below.

**Goal:** `ios-app-plan.md` §9 (reviewer-notes bullet): the embed rung is added to the **published** `resolverOrder` before submission, so a review network that blocks the native path still sees a working player.

**Files:**
- Modify: `ios-remote-config.json:4` → `"resolverOrder": ["visionosHLS", "androidItag18", "embed"],`
- Modify: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift:223` → `#expect(config.resolverOrder.count == 3)   // published for App Review (plan §9); the BUNDLED default stays two rungs`

**Not changed:** `Packages/InnerTubeKit/Sources/InnerTubeKit/Resources/remote-config-default.json` and the tests at `RemoteConfigTests.swift:10` that pin it.

- [ ] **Step 1:** change the test first; gate → FAIL (`2 ≠ 3`). The test reads the repo-root file through `IOS_REMOTE_CONFIG_PATH` (`RemoteConfigTests.swift:284-286`, set by `test.sh`) and skips if the file is absent.
- [ ] **Step 2:** edit the JSON; `python3 -m json.tool ios-remote-config.json >/dev/null` → exit 0.
- [ ] **Step 3:** gate → PASS, +0 tests.
- [ ] **Step 4:** commit — `[CHORE]: Publish embed rung in iOS remote config`.

**Consequence to carry into the metadata:** with this rung live, YouTube's own player — and whatever it shows — can appear as the last fallback. The age-rating "Advertising" answer and the review notes in the metadata file already account for it. **Goes live only on the merge to `main`** (OWNER-ONLY item 7). **Post-approval decision (review of Task 6, P2):** nothing scopes the rung to the review window — once `main` carries it, every user whose native rungs fail sees YouTube's own player as the last fallback. After App Review approves, either remove `embed` from `ios-remote-config.json` again (`[CHORE]`, one line + the order test) or decide to keep it; record which.

---

## Task 7: Backend — (a) AASA covers the `/api/…` share links — DONE `a21d6b1a`; (b) privacy policy names Sign in with Apple — OPEN

**Record (2026-09-22).** (a) went LIVE at 14:16Z: the branch backend was deployed to production on the owner's instruction (jar swap, `backend-prod-deploy` memory / DEPLOYMENT_GUIDE "Updating"); AASA answers 200 through Cloudflare with all six paths. (b) `bd7a038a`: `/privacy` names Sign in with Apple; the Cubic round corrected the clause (the app requests only the email scope, see the round-2 record) and the scope sentence (Android AND iOS). Same commit: "ad-free" removed from the legal pages, Android `share_app_promo`, README and `releases-meta.json` — owner rule, all locales.

**(a) DONE.** `WellKnownController.java:56-58` lists `/watch/*`, `/channel/*`, `/playlist/*` and the three `/api/` forms; the Javadoc (`:35-55`) records why (`ShareLinks.swift:14` emits `/api/…`; Apple's legacy `paths` `*` SPANS slashes; `DeepLinkParser` rejects deeper URLs). `WellKnownControllerTest.java:67-69` pins all six. Backend 997 tests / 0 failures at `a21d6b1a`. Live only after OWNER-ONLY item 1.

**(b) OPEN.** `LegalPagesController.java:204-206` names Google sign-in only. One sentence, same deploy.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/LegalPagesController.java:38` (`LAST_UPDATED`), `:204-206`
- Modify: `backend/src/test/java/com/albunyaan/tube/controller/LegalPagesControllerTest.java` (one assertion, the file's MockMvc style at `:73-84`)

- [ ] **Step 1: Failing test.** Add a `/privacy` assertion: `.andExpect(content().string(containsString("Sign in with Apple")))`.
- [ ] **Step 2:** `cd backend && ./gradlew test --tests '*LegalPagesControllerTest'` → 1 failure.
- [ ] **Step 3: Implement.** After the Google `<li>` (`:204-206`):
  ```html
                  <li>If you sign in with Apple, Firebase gives us a user identifier and the
                      email address you choose to share — either your own or Apple's private
                      relay address — and, the first time only, the name you choose to share.
                      We do not receive your Apple ID password.</li>
  ```
  Set `LAST_UPDATED` to the deploy date.
- [ ] **Step 4:** `cd backend && ./gradlew test` → green.
- [ ] **Step 5:** commit — `[FIX]: Privacy policy names Sign in with Apple`.

---

## Task 8: `archive.sh` + `ExportOptions.plist`

**Record (2026-09-22, `7dd85af5`).** Done. **Dry run succeeded end to end**: `** ARCHIVE SUCCEEDED **`, `preflight OK`, `** EXPORT SUCCEEDED **`, `FitrahTube.ipa` (9.2 MB) in `ios/DerivedData-Release/Archive/export/` — so assumption 3 holds (cloud-managed distribution signing works from this Mac) and the App ID ALREADY carries Associated Domains (OWNER-ONLY item 3 is done). Not uploaded (`UPLOAD` off). Review made the preflight fail closed and `BUILD_NUMBER` integer-only; `FITRAH_APP=<dir>` runs the preflight alone.

**Goal:** One command from a clean checkout to an `.ipa` (or an upload), with a preflight that refuses a build carrying the placeholder Google scheme or missing an entitlement. No fastlane.

**Files:**
- Create: `ios/ExportOptions.plist`
- Create: `ios/scripts/archive.sh`
- No `.gitignore` change: output goes under `ios/DerivedData-Release/` (ignored at `.gitignore:206`).

- [ ] **Step 1: `ios/ExportOptions.plist`:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>
    <key>destination</key>
    <string>export</string>
    <key>teamID</key>
    <string>72PF8SBQR6</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>manageAppVersionAndBuildNumber</key>
    <false/>
    <key>uploadSymbols</key>
    <true/>
</dict>
</plist>
```

- [ ] **Step 2: `ios/scripts/archive.sh`** (`chmod +x`):

```bash
#!/usr/bin/env bash
# Signed Release archive -> App Store export. NOT part of the gate (needs signing + network).
#   BUILD_NUMBER=7 bash ios/scripts/archive.sh            # -> DerivedData-Release/Archive/export/*.ipa
#   BUILD_NUMBER=7 UPLOAD=1 bash ios/scripts/archive.sh   # exports AND uploads to App Store Connect
# BUILD_NUMBER is REQUIRED and must be higher than every build already uploaded for this version.
# Needs Xcode signed in to team 72PF8SBQR6 (Xcode > Settings > Accounts).
set -euo pipefail
PATH="$HOME/.local/bin:$PATH"
: "${BUILD_NUMBER:?set BUILD_NUMBER to an integer above the last uploaded build}"

cd "$(dirname "$0")/.."
OUT="DerivedData-Release/Archive"
ARCHIVE="$OUT/FitrahTube.xcarchive"
APP="$ARCHIVE/Products/Applications/FitrahTube.app"
PB=/usr/libexec/PlistBuddy

bash scripts/copy-firebase-plist.sh
bash scripts/write-local-xcconfig.sh
[ -d Vendor/GoogleCast.xcframework ] || ./scripts/fetch-cast-sdk.sh
xcodegen generate

rm -rf "${OUT:?}"
xcodebuild archive \
    -project FitrahTube.xcodeproj -scheme FitrahTube -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
    -derivedDataPath DerivedData-Release -onlyUsePackageVersionsFromResolvedFile \
    -allowProvisioningUpdates CURRENT_PROJECT_VERSION="$BUILD_NUMBER"

# Preflight: facts only, never values.
fail() { echo "archive.sh: PREFLIGHT FAILED -- $1" >&2; exit 1; }
[ -f "$APP/GoogleService-Info.plist" ] || fail "no GoogleService-Info.plist in the app (every sign-in would be hidden)"
"$PB" -c 'Print :CFBundleURLTypes' "$APP/Info.plist" | grep -q 'no-client-id' && fail "placeholder Google callback scheme shipped"
[ "$("$PB" -c 'Print :API_BASE_URL' "$APP/Info.plist")" = "https://app.fitrahtube.com/" ] || fail "API_BASE_URL is not production"
[ "$("$PB" -c 'Print :CFBundleVersion' "$APP/Info.plist")" = "$BUILD_NUMBER" ] || fail "CFBundleVersion is not $BUILD_NUMBER"
ENT="$(codesign -d --entitlements - --xml "$APP" 2>/dev/null)"
echo "$ENT" | grep -q 'com.apple.developer.applesignin' || fail "Sign in with Apple entitlement missing from the signature"
echo "$ENT" | grep -q 'applinks:app.fitrahtube.com' || fail "Associated Domains entitlement missing from the signature"
[ -f "$APP/PrivacyInfo.xcprivacy" ] || fail "privacy manifest missing"
echo "archive.sh: preflight OK"

OPTS="$OUT/ExportOptions.plist"
cp ExportOptions.plist "$OPTS"
[ "${UPLOAD:-0}" = "1" ] && "$PB" -c 'Set :destination upload' "$OPTS"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTS" \
    -exportPath "$OUT/export" -allowProvisioningUpdates
echo "archive.sh: done -> ios/$OUT/export"
```

  `API_BASE_URL` is an Info.plist key (`project.yml:160`), so `PlistBuddy` can read it from the archived app.
- [ ] **Step 3: Static checks** — `bash -n ios/scripts/archive.sh` and `plutil -lint ios/ExportOptions.plist` → `OK`.
- [ ] **Step 4: Dry run to the preflight** — `BUILD_NUMBER=1 bash ios/scripts/archive.sh`. **Run outside the gate and outside the 300 s watchdog** (a cold Release archive takes longer). Expected: `archive.sh: preflight OK`, then either `done` (assumption 3 holds) or an export error naming the distribution certificate/profile — in which case record the exact message and hand the export to OWNER-ONLY item 6; the task is still complete (archive + preflight are the deliverable).
  If the ARCHIVE step itself fails on "doesn't include the com.apple.developer.associated-domains entitlement" → OWNER-ONLY item 3.
- [ ] **Step 5: Commit** — `git add ios/ExportOptions.plist ios/scripts/archive.sh` / `[CHORE]: Archive and export script for App Store`.

Skipped: upload via API key, build-number auto-increment, CI. Add when there is a second person cutting builds.

---

## Task 9: App Store screenshots (no repo change)

**Goal:** The required screenshot sets, in en / ar / nl, from the real catalog. Required sizes today: iPhone 6.9" (1320×2868 — `iPhone 17 Pro Max`, present on this Mac per `xcrun simctl list`) and iPad 13" (2064×2752 — `iPad Pro 13-inch (M5)`, present). **UNVERIFIED:** the exact pixel sizes App Store Connect accepts for these two classes were not re-checked against Apple's current specification page; verify on the upload form before shooting.

**Rules (`ios-app-plan.md` §9 checklist):** native player only — never the embed rung (it shows YouTube's logo); no YouTube marks; never the word "kids"; nothing with the two banned terms; the Saved screen is titled "Saved"/"Save for offline". Lecture content only (approved video `xc7keR2piUM`).

**No new rig.** `screenshots.sh` is an assertion harness with its own output tree; for ~6 shots × 3 locales × 2 devices, `simctl` by hand is smaller than extending it.

- [ ] **Step 1:** build the production-pointing simulator app: `RELEASE=1 KEEP_RESULTS=1 bash ios/scripts/test.sh` → `ios/DerivedData-Release/Build/Products/Release-iphonesimulator/FitrahTube.app`. (Unsigned is fine here: no sign-in screen is shot.)
- [ ] **Step 2:** per device and locale (`en`, `ar`, `nl`):

```bash
DEV="iPhone 17 Pro Max"; LANG_=ar; OUT="$HOME/Desktop/fitrahtube-appstore/$LANG_/iphone-6.9"; mkdir -p "$OUT"
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl status_bar "$DEV" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3
xcrun simctl install "$DEV" ios/DerivedData-Release/Build/Products/Release-iphonesimulator/FitrahTube.app
xcrun simctl launch "$DEV" com.albunyaan.tube -AppleLanguages "($LANG_)" -AppleLocale "$LANG_"
# navigate by hand, then for each screen:
xcrun simctl io "$DEV" screenshot "$OUT/01-home.png"
```

  Screens, in order: 1 Home · 2 Categories · 3 Channel detail · 4 Player (native, portrait, a lecture) · 5 Search results · 6 Settings showing Safe Mode and language.
- [ ] **Step 3:** verify sizes — `sips -g pixelWidth -g pixelHeight "$OUT"/*.png`.
- [ ] **Step 4:** eyeball every Arabic shot for RTL mirroring and every shot for a stray YouTube mark. Hand the folder to the owner (OWNER-ONLY item 8).

Output lives outside the repo; nothing to commit.

---

## Task 10: Verification mail the server never sent is 503 — DONE `f5a54084`

Found on the owner's first real sign-in. `POST /api/account/send-verification-email` answered 200 "sent" when `mail.enabled=false` (a silent no-op) or Graph threw (swallowed), so neither app fell back to Firebase's mailer and the account sat unverified. Now `MailService.sendViaGraph` reports whether a message was handed to Graph, and `AccountController.java:129-130` answers `503 {"code":"MAIL_UNAVAILABLE"}` when it was not; the per-uid cooldown is recorded either way (`:120`). iOS maps 503 to `.unknown` and falls back to Firebase, pinned over 500 and 503 (`EmailVerificationViewModelTests.swift:173-182`). The password-reset path is `@Async void` and unchanged — CF-A-57.

- [x] Backend 997 tests / 0 failures; iOS gate green at `f5a54084`.
- [ ] **Takes effect on deploy** (OWNER-ONLY item 1). After deploy, either the server sends mail itself — only with `MAIL_ENABLED=true` and `AZURE_TENANT_ID` / `AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET` set (`application.yml:183-190`; optional `MAIL_FROM_ADDRESS`, default `noreply@fitrahtube.com`, `:184`) — or it answers 503 and the app uses Firebase's mailer. Both are acceptable for review; the owner picks (item 1).
- [ ] **Verification (owner, item 10):** create an email account on a TestFlight build and receive the verification mail.

---

## Task 11: Manual sign-in build (recipe; no script)

**Why this exists.** The gate builds the simulator app with `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*] = NO` (`project.yml:26`). Unsigned, the app has no entitlements section, Firebase's keychain write fails with `errSecMissingEntitlement` (-34018), and every provider ends in "Something went wrong". A manual sign-in test needs an **ad-hoc signed** simulator build. `ios/DerivedData-Signed/` (gitignored, `.gitignore:205`) holds one from 2026-09-21; its binary carries a `(__TEXT,__entitlements)` section (checked with `otool` on 2026-09-22).

**Two traps the recipe avoids:** the gate's `xcodebuild test` installs its test host onto `iPhone 17` and `iPad Pro 13-inch (M5)` mid-run, replacing whatever is installed there — so test on **`iPhone 17 Pro`** and **hold gates while a manual test is running** (one build slot either way). And Debug's tracked `API_BASE_URL` is `http://localhost:8080/` (`Debug.xcconfig:3`); to hit the live server either set it in `Local.xcconfig` (the example says Debug may) or pass it on the command line. **UNVERIFIED:** the command-line `API_BASE_URL=…` override was not re-run for this plan; the 2026-09-21 run reached the live server (Gate section), by one of the two routes.

- [ ] **Recipe** (from the repo root; needs `copy-firebase-plist.sh` + `write-local-xcconfig.sh` to have run):

```bash
cd ios && PATH="$HOME/.local/bin:$PATH" xcodegen generate
xcodebuild build -project FitrahTube.xcodeproj -scheme FitrahTube -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath DerivedData-Signed -onlyUsePackageVersionsFromResolvedFile \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO \
  API_BASE_URL='https://app.fitrahtube.com/' 2>&1 | grep -E 'error:|BUILD (SUCCEEDED|FAILED)'
APP=DerivedData-Signed/Build/Products/Debug-iphonesimulator/FitrahTube.app
otool -s __TEXT __entitlements "$APP/FitrahTube" | head -1     # expect: Contents of (__TEXT,__entitlements) section
xcrun simctl boot 'iPhone 17 Pro' 2>/dev/null || true
xcrun simctl install 'iPhone 17 Pro' "$APP" && xcrun simctl launch 'iPhone 17 Pro' com.albunyaan.tube
```

- [ ] **What it proves:** Google sign-in end to end (the callback scheme, the keychain, the backend bootstrap); email sign-up once Task 10 is deployed. **What it cannot prove:** Sign in with Apple and Universal Links — device only.

**Decision on `ios/scripts/run-sim.sh`: not worth a task.** The recipe is five commands with one tester and one Mac; a script would need the same `API_BASE_URL` and simulator-name choices as arguments and would be the third `xcodebuild` wrapper in `ios/scripts/`. Add it the day a second person needs to reproduce a sign-in bug, or the day the recipe drifts from what works — whichever first.

---

## Task 12: Phase gate

- [ ] `RELEASE=1 KEEP_RESULTS=1 bash ios/scripts/test.sh` green at the final sha; `cd backend && ./gradlew test` green.
- [ ] The mandatory 9-stage review pipeline (`AGENTS.md:74` → "Mandatory Post-Coding Review Pipeline"). Name any stage that cannot run and why.
- [ ] Record the phase outcome in this file (a "Record" paragraph per task, the Phase 4 plan's convention). **UNVERIFIED / CORRECTED:** the draft said to update `docs/status/PROJECT_STATUS.md`; that file does not exist at HEAD (`docs/status/` holds `DEPLOYMENT_GUIDE.md`, `DEVELOPMENT_GUIDE.md`, `TESTING_GUIDE.md`, `YOUTUBE_RATE_LIMIT_PLAN.md`), nor do `docs/PROJECT_STATUS.md` / `docs/TRUE_PROJECT_STATUS.md`. No new status file is created.
- [ ] One PR into `develop` (spec `:301`).
- [ ] Report Tier-3 honestly: signed-build behaviours (Apple sign-in completing, Universal Links opening the app, upload accepted) are **unproven** until the OWNER-ONLY section is done. Google sign-in is proven (Gate section).

---

## Spec coverage (self-review)

| Spec / plan §9 requirement | Where |
|---|---|
| Privacy manifest complete | Task 1 (DONE) |
| Privacy/support URLs linked in-app (5.1.1(i)) | Task 2 (DONE; OQ-1 open) |
| AASA + Universal Links | Tasks 3 (DONE), 7a (DONE); owner 1, 3, 9 |
| Sign in with Apple offered beside Google (4.8) | Tasks 3, 4 (DONE); owner 2, 4 |
| Email verification actually arrives | Task 10 (DONE); owner 1 |
| Update-required screen (D3) | Task 5; owner 5 |
| Embed rung published before review | Task 6; owner 7 |
| Export-compliance flag | already shipped — `ITSAppUsesNonExemptEncryption: false`, `project.yml:134` |
| Xcode 26 build, deployment target 18 | already true — `project.yml:6` (Xcode 26.3), `:4-5` (iOS 18.0) |
| Labels, questionnaire, review notes, listing | metadata file; owner 8 |
| TestFlight | Task 8; owner 6 |
| Opt out of Mac / Vision Pro availability | owner 8 (`project.yml:22-24` already sets the build-side flags to NO) |
| IPv6-only / NAT64 test (§9) | **GAP, deliberately not a task:** needs a Mac sharing a NAT64 hotspot to a physical phone — owner 9, optional |
| DSA trader status (EU) | owner 8 |

---

# OWNER-ONLY — what only you can do

Written for you, not for an engineer. Nothing here needs code. Do them in this order; each says what to click and how you will know it worked. Where it says "tell the agent", just paste the value into the chat.

**1. Deploy the backend.** *(unblocks: email sign-up, shared links opening the app)*
Deploy the current backend the way you normally do (the deployment guide is `docs/status/DEPLOYMENT_GUIDE.md`). Two things ride on this deploy:
- Shared links: afterwards open `https://app.fitrahtube.com/.well-known/apple-app-site-association` in a browser — you should see a short block of text containing `72PF8SBQR6.com.albunyaan.tube`. Today it shows an error (403).
- Verification e-mails: the server now tells the app honestly when it cannot send mail, and the app then uses Google Firebase's own mailer instead — so e-mail sign-up works either way. If you want the mails to come from **noreply@fitrahtube.com** rather than from Firebase, set these on the server before starting it (values come from your Microsoft Azure app registration; never paste them into chat):
  ```
  MAIL_ENABLED=true
  AZURE_TENANT_ID=…
  AZURE_CLIENT_ID=…
  AZURE_CLIENT_SECRET=…
  ```
  Optional: `MAIL_FROM_ADDRESS` (default `noreply@fitrahtube.com`) and `MAIL_FROM_DISPLAY_NAME` (default `FitrahTube`). If you leave `MAIL_ENABLED` unset, nothing breaks — Firebase sends the mail.
Do this **after** the agent has finished Task 7b so you only deploy once.

**2. Firebase console — switch on Apple sign-in.** *(unblocks: the "Sign in with Apple" button actually working)*
console.firebase.google.com → project **albunyaan-tube** → Build → Authentication → Sign-in method → Add new provider → **Apple** → Enable → Save. Leave the optional fields empty (they are for websites). While there, confirm **Google** and **Email/Password** both say "Enabled". *(UNVERIFIED: the Firebase project's display name; use whichever project holds the iOS app `com.albunyaan.tube`.)*

**3. Apple Developer portal — confirm two switches on the App ID.**
developer.apple.com/account → Certificates, IDs & Profiles → Identifiers → **com.albunyaan.tube**. Make sure both are ticked: **Sign in with Apple** (you did this on 20 September) and **Associated Domains** (new — this is what lets a shared link open the app). Save. If the agent reports a signing error mentioning "associated-domains", this is the fix.

**4. Sign in with Apple on your own iPhone.**
This is the one sign-in that cannot be tested on the Mac's simulator. Ask the agent to build to your phone (Xcode → your iPhone plugged in, or via TestFlight after item 6), then: Me → Sign in → **Sign in with Apple** → choose "Hide My Email" or share it → you should land on the profile setup screen. If it fails, tell the agent the exact message.

**5. App Store Connect — create the app record.**
appstoreconnect.apple.com → Apps → "+" → New App. Platform **iOS**; Name **FitrahTube: Islamic Videos** (if taken, try plain **FitrahTube**); Primary language **English (U.S.)**; Bundle ID **com.albunyaan.tube**; SKU `fitrahtube-ios`; User Access **Full**.
Then open App Information, find **Apple ID** (a number of about ten digits) and **tell the agent that number** — it switches on the "Update" button inside the app (Task 5).

**6. Let the agent build and upload → TestFlight.**
Make sure Xcode on the Mac is signed in (Xcode → Settings → Accounts shows your Apple ID under team 72PF8SBQR6). Tell the agent "run the archive with upload". If Apple asks you to accept a new agreement or a two-factor code appears on your phone, approve it. About 15–30 minutes later the build appears under **TestFlight** in App Store Connect. Internal testers (people you add under Users and Access) can install it straight away with no Apple review.

**7. Approve the merge to `main`.**
The app reads its live settings file from the `main` branch. Until the iOS work is merged there, the app quietly uses the copy built into it, and the safety-net video player for Apple's reviewers is not switched on. Approve the pull request(s) when the agent presents them. **This must happen before you press "Submit for Review".**

**8. Fill in the App Store page.** Open `docs/superpowers/plans/2026-09-22-ios-phase6-app-store-metadata.md` (it is a draft — read it once, change anything you dislike, then paste) and copy each block into the matching field in App Store Connect:
- *App Information:* name, subtitle, category **Education** (secondary **Lifestyle**), content rights → "Yes, it contains third-party content, and I have the rights / permission" only if you are comfortable stating that — read the note in the draft first.
- *Pricing and Availability:* **Free**. Under availability, **untick "Make this app available on Mac"** and **untick Apple Vision Pro**.
- *App Privacy:* Privacy Policy URL, then answer the data questions exactly as the worksheet table says.
- *Age rating:* answer the questionnaire exactly as the draft's table says.
- *Version page (for English, Arabic and Dutch — add the two extra languages with the "+" next to the language menu):* promotional text, description, keywords, support URL, what's new, and the screenshots folder the agent gives you (Task 9).
- *Business → Compliance:* because the app is offered in the Netherlands/EU, complete the **Digital Services Act trader status** form (you will need a contact address, phone and e-mail that Apple will show publicly in the EU).
- *Agreements:* make sure the **Free Apps** agreement shows "Active".
- *The GitHub link inside the app (OQ-1 in Task 2):* decide whether the About screen keeps its "GitHub" row. If it stays, the public README on GitHub should be reworded (three lines) so it does not describe the app in words the App Store page avoids. Tell the agent "remove the row" or "reword the README".

**9. Try it on your phone before submitting** (10 minutes; tell the agent anything that fails):
- Sign in with Apple → works (item 4). Sign out. Sign in with Google → works (already proven on the simulator).
- Create an e-mail account → the verification mail arrives (after item 1).
- Me → ⋯ → Profile → Delete account → the account is deleted and you are back as a guest. (Use a throw-away account, not the reviewer's.)
- Send yourself a shared video link in Messages and tap it → FitrahTube opens on that video (only after items 1 and 3).
- About → tap Privacy Policy, Terms, Licences → each opens a real page.
- Optional but recommended by Apple: on a Mac, System Settings → Sharing → Internet Sharing, hold Option and tick **Create NAT64 Network**, join that Wi-Fi from the phone, and check that videos still play.

**10. Create a demo account for Apple's reviewer.**
On your own phone (TestFlight build), create a fresh e-mail account with an address you control, verify the e-mail, and complete the profile with an adult date of birth. Put that e-mail and password **only** into App Store Connect → the version page → App Review Information → "Sign-in required" fields. Never paste them into chat, a document or the repo. Then paste the review notes from the draft into the Notes box.

**11. One thing to check with Google (may take days — start early).**
"Import from YouTube" asks Google for read-only access to a user's YouTube account. In Google Cloud Console → APIs & Services → OAuth consent screen, check whether the app is **"In production" and verified** for the `youtube.readonly` scope. If it says "Testing", only test users you list can use the import, and everyone else sees a warning. If it is not verified, tell the agent — the review notes should then say the import is limited, or the menu item should be hidden for 1.0.

**12. Press "Add for Review" → "Submit".** Typical wait is 1–3 days. If Apple replies with a question about where the videos come from or about saving for offline, **do not answer alone** — forward the message to the agent; `docs/architecture/ios-app-plan.md` §9 has the prepared position and a same-day settings change that needs no new build.
