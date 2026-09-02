# Phase 4 research — dependencies, what compiles without secrets, and what is USER-BLOCKED

**Honesty note.** This session is offline and read-only: no network, no builds, no `swift package
resolve`. Every SPM URL below is stated from knowledge, not verified live, and **no version is
pinned here** — the plan must pin exact versions at authoring time by resolving them once. Anything
that could not be checked in-repo is marked *unverified*.

---

## 1. What Phase 4 needs to add

| Dependency | Kind | Repository URL | Products the app needs |
|---|---|---|---|
| Firebase iOS SDK | SPM | `https://github.com/firebase/firebase-ios-sdk` | `FirebaseAuth` only (**not** Analytics, Firestore, Messaging) |
| Google Sign-In for iOS | SPM | `https://github.com/google/GoogleSignIn-iOS` | `GoogleSignIn` (and `GoogleSignInSwift` only if the branded button view is used) |
| Sign in with Apple | system | — | `AuthenticationServices` framework + the **Sign in with Apple capability/entitlement** |

Spec D8 already authorises exactly these two third-party additions ("Firebase Auth (SPM), Google
Sign-In (SPM), Google Cast SDK"). Nothing else.

Notes the plan should carry:
- `firebase-ios-sdk` is a large repository; pulling only the `FirebaseAuth` product still resolves
  the whole package graph (GTMSessionFetcher, GoogleUtilities, abseil/gRPC only if Firestore is
  pulled — **do not pull Firestore**). First `xcodegen generate` + resolve is a multi-minute,
  network-dependent step; `ios/scripts/test.sh` has a 300 s wall-clock watchdog, so **the first
  resolve must happen outside the gate** (same shape as the Cast SDK pre-stage at
  `screenshots.sh:27-29` / `test.sh`). *Unverified: exact resolve time on this machine.*
- `GoogleSignIn-iOS` depends on `AppAuth`, `GTMAppAuth`, `GTMSessionFetcher`. It requires
  **iOS 12+**; the project targets iOS 18 (`project.yml:4-6`), so no floor problem.
- Both ship their own `PrivacyInfo.xcprivacy`; the app's own manifest still needs the new collected
  types (`ios-seams.md` §8).

`ios/project.yml:33-37` gains two `packages:` entries and two `dependencies:` lines on the
`FitrahTube` target; the `FitrahTubeTests` target needs the same products on its search path only if
tests `@testable import` code whose *interface* names Firebase types — the `CastController` +
`Vendor/GoogleCast.xcframework` pair at `project.yml:122-126` is the precedent for how that was
handled. **Prefer keeping Firebase types out of every test-visible interface** so the test target
needs nothing.

---

## 2. Runtime prerequisites, one by one

### 2.1 `GoogleService-Info.plist` — needed by Firebase Auth at runtime, not at compile time

- `FirebaseApp.configure()` reads it from the bundle. Without it, `configure()` raises a fatal error
  at launch (it asserts on a missing options file).
- **Mitigation that keeps the phase gate green:** spec D6 already decided the shape — the real file
  lives at `~/.config/albunyaan/GoogleService-Info.plist` (git-ignored) and **a placeholder is
  committed so simulator builds work**, copied in by `ios/scripts/copy-firebase-plist.sh` as a build
  phase. Neither the script nor either plist exists at `cac46c11` (`ios-seams.md` §7) — Phase 4
  builds them.
- Additional safety the plan should specify: call `FirebaseApp.configure()` behind a check for the
  options file, and have `AuthClient` degrade to a `SignedOut`-forever stub when it is absent, so a
  developer without the real plist still gets a running app (the guest experience) rather than a
  launch crash. That is also what makes the placeholder honest.

### 2.2 Google Sign-In — three pieces of configuration

1. **`GIDClientID`** in `Info.plist` (the iOS OAuth client id), or read from
   `GoogleService-Info.plist`'s `CLIENT_ID` at startup via
   `GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID:)`.
2. **A URL scheme** — a second `CFBundleURLTypes` entry with the **reversed client id**
   (`com.googleusercontent.apps.<id>`). Today `project.yml:104-106` declares only `albunyaantube`.
3. **`GIDSignIn.sharedInstance.handle(url)`** must run in `.onOpenURL` **before**
   `router.open(url)` (`FitrahTubeApp.swift:68`), and return early when it handled the URL.

All three come from the same Firebase iOS app registration, so all three are blocked on the same
thing (§3).

Android's equivalent lookup is `default_web_client_id`, and `SignInFragment.kt:220-226` **already
handles its absence gracefully** — surfacing `GOOGLE_SIGN_IN_FAILED` instead of crashing. Port that
behaviour: a missing client id hides or disables the Google button rather than trapping.

### 2.3 Sign in with Apple — the one that hard-requires the Team ID

- Framework: `AuthenticationServices`, no dependency to add.
- **Capability**: "Sign in with Apple" must be enabled on the App ID and present in a
  `FitrahTube.entitlements` (`com.apple.developer.applesignin = ["Default"]`). Adding the
  entitlement file requires code signing to resolve it, and `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)`
  is **empty** (`ios/Config/Debug.xcconfig:3`, `Release.xcconfig:1`).
- On the **simulator with `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*] = NO`**
  (`project.yml:26`) an entitlements file is not enforced, so the code compiles and
  `ASAuthorizationController` can be *constructed*; whether the authorization sheet actually
  completes on a simulator without a provisioned App ID is *unverified* here.
- The Firebase half (`OAuthProvider("apple.com")` + nonce) additionally needs Apple as a sign-in
  provider in the Firebase console, which needs a Services ID and key — the same registration
  blocker.
- **Why it is not optional:** the app offers Google sign-in. App Review Guideline 4.8 requires an
  equivalent private login option alongside a third-party one, and Sign in with Apple is the
  canonical way to satisfy it. Shipping Google without Apple is a rejection risk on a build that
  otherwise carries two accepted risks already (5.2.3 downloads, 5.2.2 playback).

### 2.4 YouTube Data API for the import flow — **no key needed**

Confirmed from Android: `data/youtube/YouTubeImportApi.kt:16-18` sends the OAuth access token
per-call as `Authorization: Bearer …` with **no `key=` query parameter anywhere**, and
`di/ImportModule.kt` binds only the auth manager. The scope
`https://www.googleapis.com/auth/youtube.readonly` must be enabled on the OAuth consent screen for
the project — a console setting, not a client secret. **Fork closed: the import flow needs no API
key.**

---

## 3. USER-BLOCKED, precisely

Everything in this section reduces to spec §18 open question 1 (D6): **the Apple Developer Team ID
and the Firebase iOS app registration for `com.albunyaan.tube` in project `albunyaan-tube`**. The
standing blocker is unchanged since Phase 2 (`PHASE2-CARRYFORWARDS.md:119` — "no signing identity
(`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` unset)").

| Blocked item | Blocked on |
|---|---|
| Any real Firebase Auth call (sign-in, sign-up, password reset, verification email, re-auth, `updatePassword`, `verifyBeforeUpdateEmail`, `delete()`) | real `GoogleService-Info.plist` |
| Google Sign-In end-to-end | `GIDClientID` + reversed-client-id URL scheme (same plist) |
| Sign in with Apple end-to-end | Team ID + App ID capability + Firebase Apple provider |
| Signing/entitlements on device; every device-only acceptance item | Team ID |
| `WellKnownController`'s AASA content (§12 row 1) | Team ID; **and** the Cloudflare `/.well-known/*` 403 rule, which is outside the repo |
| End-to-end sync/import against production | a signed-in user, i.e. all of the above |

**Not blocked** (this is the phase gate, spec §15 row 4: "tests against fakes; end-to-end once
Firebase plist exists"):
- every ViewModel and pure decision type (`SplashRouter` matrix, bootstrap validation, the
  `AuthErrorCode` mapping table, `SyncManager`'s bind/merge/pull/push matrix, tombstone
  monotonicity, the push classifier, the stalled-cursor guard, import dedupe/chunking/429-stop,
  `ImportUiState` transitions, the Me week-bucketing);
- every screen rendered against `AppContainer.fake()`, including screenshots on all three simulators
  in en/ar;
- the `FitrahAPI` auth middleware (host scoping, single 401 retry, 403 envelope) against
  `RecordingTransport`;
- the SwiftData V5 migration;
- the whole strings/localization pass;
- the `Route`/`MainShellView` destinations and `MainShellRoutingTests`.

**The seam that makes it work** is the one Android already proved twice: `AuthRepository`
(`android/.../auth/AuthRepository.kt:24-45`) and `YouTubeAuthManager`
(`android/.../data/youtube/YouTubeAuthManager.kt:54-71`) are interfaces **specifically so the layer
above tests against fakes without the SDK** (`:44-52`). Phase 4's iOS equivalent must be the same:
Firebase types appear in exactly one file per concern (`FirebaseAuthClient`, `GoogleAuthProvider`,
`AppleAuthProvider`, `YouTubeAuthorizer`), each behind a small protocol on `AppContainer`, each with
a fake in `FitrahTubeTests/Support/TestDoubles.swift`. If a Firebase type appears in a ViewModel,
the gate is unmeetable.

---

## 4. Things to check the day the plist arrives

1. `FirebaseApp.configure()` succeeds and `Auth.auth().currentUser` survives a relaunch (Keychain
   persistence — note the app has never used the Keychain before; a `keychain-access-groups`
   entitlement is **not** required for the default group).
2. Google button → consent → `signInWithCredential` → `GET /api/account/me` returns
   `PENDING_PROFILE` on a fresh account → ProfileBootstrap.
3. The `youtube.readonly` incremental scope prompt appears **only** on the Import screen, never at
   sign-in (Android's contract, `YouTubeAuthManager.kt:45-46`).
4. Email/password sign-up → verification email actually arrives (the backend path
   `POST /api/account/send-verification-email` uses `firebaseAuth.generateEmailVerificationLink` +
   `MailService`, `AccountController.java:117-121` — so **mail delivery is a backend concern**, and a
   Firebase-only fallback fires when that endpoint fails, `EmailVerificationViewModel.kt:115-118`).
5. `DELETE /api/account/me` returns 204, then the next request 403s `ACCOUNT_DELETED` via
   `FirebaseAuthFilter.java:135-147`.
6. Sign in with Apple's private-relay email round-trips through
   `POST /api/account/profile` (the backend requires `principal.isEmailVerified()`, and Apple tokens
   report verified).
