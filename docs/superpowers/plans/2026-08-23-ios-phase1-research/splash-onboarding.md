# Phase 1 research — Splash & Onboarding (Android → SwiftUI behavioural contract)

Scope: `SplashFragment` + `SplashRouter`, `OnboardingFragment` + pager, and the launch/splash/onboarding/app-icon assets iOS should reuse.
Spec anchors read first: `docs/superpowers/specs/2026-08-23-ios-app-design.md:103-130` (§6 Navigation) and `:130-169` (§7 Design system).
All paths below are absolute-relative to `/Users/farouqabouumar/Development/albunyaantube`.

---

## 0. Source files

| Role | File |
|---|---|
| Splash screen | `android/app/src/main/java/com/albunyaan/tube/ui/SplashFragment.kt` |
| Routing (pure fn) | `android/app/src/main/java/com/albunyaan/tube/ui/SplashRouter.kt` |
| Routing test (contract) | `android/app/src/test/java/com/albunyaan/tube/ui/SplashRouterTest.kt` |
| Splash layout | `android/app/src/main/res/layout/fragment_splash.xml` |
| Onboarding screen | `android/app/src/main/java/com/albunyaan/tube/ui/OnboardingFragment.kt` |
| Onboarding page model | `android/app/src/main/java/com/albunyaan/tube/onboarding/OnboardingPage.kt` |
| Onboarding pager adapter | `android/app/src/main/java/com/albunyaan/tube/onboarding/OnboardingPagerAdapter.kt` |
| Onboarding VM (empty stub) | `android/app/src/main/java/com/albunyaan/tube/onboarding/OnboardingViewModel.kt` |
| Onboarding layouts | `android/app/src/main/res/layout/fragment_onboarding.xml`, `.../layout/page_onboarding_item.xml` |
| Strings | `android/app/src/main/res/values/strings_onboarding.xml`, `.../values/strings.xml` |
| Dimens | `android/app/src/main/res/values/dimens.xml`, `values-sw600dp/dimens.xml`, `values-sw720dp/dimens.xml` |
| Nav graph | `android/app/src/main/res/navigation/app_nav_graph.xml` |

There is **one** layout per screen — no `layout-sw600dp` / `layout-sw720dp` / `layout-land` variants exist for splash or onboarding. All tablet adaptation happens through dimension buckets. iOS should do the same with a size-class-driven metric table, not separate views.

---

## 1. SplashFragment

### 1.1 Where it sits

`app_nav_graph.xml:5` — `app:startDestination="@id/splashFragment"`; the splash is the app's first destination, hosted in `MainActivity`. `AndroidManifest.xml:33-37` sets `android:icon="@mipmap/ic_launcher"`, `roundIcon="@mipmap/ic_launcher_round"`, `theme="@style/Theme.Albunyaan"`. **There is no Android-12 `SplashScreen` API usage, no `windowSplashScreen*` attributes, and no `values-v31` bucket** (verified: no hits for `installSplashScreen|windowSplashScreen` under `app/src/main`). The system window background is whatever `Theme.Albunyaan` (`res/values/themes.xml:3`, `parent="Theme.Material3.DayNight.NoActionBar"`) resolves — the same `?android:attr/colorBackground` the splash layout uses (`fragment_splash.xml:6`).

**iOS mapping:** a static `LaunchScreen` (solid `background` token, no content or logo-only) that hands off to the animated SwiftUI splash overlay — spec D13, `2026-08-23-ios-app-design.md:32`.

### 1.2 Layout metrics (dp/sp), phone / sw600dp / sw720dp

Four views on a `ConstraintLayout`, background `?android:attr/colorBackground` (`fragment_splash.xml:6`).

| View | Property | Phone | sw600dp | sw720dp | Cite |
|---|---|---|---|---|---|
| `splashIcon` | size (`splash_logo_size`) | **160dp** | **220dp** | **280dp** | `fragment_splash.xml:11-12`; `values/dimens.xml:209`, `values-sw600dp/dimens.xml:106`, `values-sw720dp/dimens.xml:116` |
| `splashIcon` | src | `@drawable/albunyaantube_logo` | — | — | `fragment_splash.xml:13` |
| `splashIcon` | contentDescription | `@string/app_name` | — | — | `fragment_splash.xml:14` |
| `splashIcon` | position | centered H, `layout_constraintVertical_bias="0.35"` (i.e. 35% down the free vertical space between top and bottom of parent) | — | — | `fragment_splash.xml:16-20` |
| `appName` | textSize (`splash_title_size`) | **32sp** | **40sp** | **48sp** | `fragment_splash.xml:28`; `dimens.xml:210`, sw600:107, sw720:117 |
| `appName` | style / color | bold, `?attr/colorOnBackground` | — | — | `fragment_splash.xml:29-30` |
| `appName` | marginTop (`spacing_lg`) | **24dp** | **32dp** | **40dp** | `fragment_splash.xml:32`; `dimens.xml:9`, sw600:37, sw720:36 |
| `tagline` | textSize (`text_subtitle`) | **16sp** | 16sp | **18sp** | `fragment_splash.xml:43`; `dimens.xml:195`, sw720:100 |
| `tagline` | color / gravity | `?attr/colorOnSurfaceVariant`, centered | — | — | `fragment_splash.xml:44-45` |
| `tagline` | paddingStart/End (`spacing_xl`) | **32dp** | **48dp** | **64dp** | `fragment_splash.xml:47-48`; `dimens.xml:10`, sw600:38, sw720:37 |
| `tagline` | marginTop (`spacing_sm`) | **8dp** (all buckets) | 8dp | 8dp | `fragment_splash.xml:49`; `dimens.xml:7` (not overridden) |
| `loadingSpinner` | indeterminate circular, tint `?attr/colorPrimary` | — | — | — | `fragment_splash.xml:55-59` |
| `loadingSpinner` | marginBottom (`spacing_xxl`) | **48dp** (all buckets, not overridden) | 48dp | 48dp | `fragment_splash.xml:61`; `dimens.xml:11` |
| `loadingSpinner` | position | pinned to parent bottom, centered H | — | — | `fragment_splash.xml:62-64` |

Slide distance used by the animations: `splash_slide_distance` = **30dp** (`values/dimens.xml:211`, **not overridden** in sw600/sw720 — same 30 pt on iPad), read at `SplashFragment.kt:118`.

Initial visibility of `splashIcon`, `appName`, `tagline`, `loadingSpinner` is all `invisible` (`fragment_splash.xml:15,31,46,60` — `android:visibility="invisible"` on each). `invisible` (not `gone`) — the layout is measured with everything in place, so **nothing reflows during the animation**. iOS must lay the full stack out up front and animate only opacity/offset.

### 1.3 Animation timeline (exact)

Constants (`SplashFragment.kt:81-84`):

```
LOGO_DISPLAY_DURATION  = 600 ms
TEXT_FADE_DURATION     = 400 ms
TAGLINE_DELAY          = 150 ms
POST_ANIMATION_DELAY   = 800 ms
SPLASH_PRE_AWAIT_MS    = 600 + (400 * 3) + 150 + 800 = 2750 ms   (SplashFragment.kt:96-97)
UPDATE_AWAIT_GRACE_MS  = 500 ms                                   (SplashFragment.kt:105)
```

Sequence, all on the main coroutine, started in `onViewCreated` (`SplashFragment.kt:120`):

| t (ms) | Event | Cite |
|---|---|---|
| 0 | Three parallel async tasks launched (see §1.4) | `SplashFragment.kt:123-154` |
| 0 | Deep-link check; if deep link → cancel update probe, await onboarding + account, route **immediately** (no animation at all) | `SplashFragment.kt:161-165` |
| 0 → 600 | `delay(LOGO_DISPLAY_DURATION)` — **all four views still `invisible`; the screen is a bare background colour**. The Kotlin comment says "Show logo alone for 600ms" but the code makes the logo visible *after* the delay. | `SplashFragment.kt:168` |
| 600 | `splashIcon` snapped visible at `alpha = 1` — **no fade, no scale, instant appear** | `SplashFragment.kt:171-172` |
| 600 → 1000 | `appName`: `alpha 0→1` **and** `translationY 30dp→0` played together, `duration = 400`, `DecelerateInterpolator` | `SplashFragment.kt:176-187` |
| 1000 → 1150 | `delay(TAGLINE_DELAY)` — dead gap | `SplashFragment.kt:189,192` |
| 1150 → 1550 | `tagline`: identical `alpha 0→1` + `translationY 30dp→0`, 400 ms, `DecelerateInterpolator` | `SplashFragment.kt:193-204` |
| 1550 → 1950 | `loadingSpinner`: `alpha 0→1`, 400 ms, **no interpolator set** → platform default (`AccelerateDecelerate`) | `SplashFragment.kt:209-215` |
| 1950 → 2750 | `delay(POST_ANIMATION_DELAY)` — hold, spinner visible | `SplashFragment.kt:220` |
| 2750 → ≤3250 | `withTimeoutOrNull(500) { updateInfoDeferred.await() }`; if non-null → blocking update dialog (Android only, see §1.4c) | `SplashFragment.kt:236-238,252-261` |
| after | `onboardingDeferred.await()`, `accountStatusDeferred.await()` (both normally already resolved), then `routeAfterSplash(...)` | `SplashFragment.kt:239-241` |

Each animator is registered in `runningAnimators` and cancelled in `onDestroyView` (`SplashFragment.kt:78,186,203,214,336-341`) — iOS gets this for free by tying the animation to view lifetime, but the **awaits must be cancelled too** (see §1.6 detach guards).

**iOS motion contract** (spec §7, `2026-08-23-ios-app-design.md:169`): 400 ms fade + 30 pt slide, `.easeOut` (DecelerateInterpolator ≈ `easeOut`); **static under Reduce Motion** — under `accessibilityReduceMotion` show the final composed state and keep the same total dwell so the routing timing does not change.

### 1.4 Parallel work started at t=0

Three `async` blocks, all launched before the deep-link check:

**(a) Onboarding flag** — `settingsPreferences.onboardingCompleted.first()` (`SplashFragment.kt:123-125`).
Backing store: DataStore boolean `onboarding_completed`, default `false` (`preferences/SettingsPreferences.kt:89,280-282`).

**(b) Account status** — `SplashFragment.kt:131-145`:
- If `firebaseAuth.currentUser == null` → resolves `null` **without any network call**.
- Else `accountRepository.fetchMe(maxAttempts = 1)` → `.getOrNull()`; on success, `launch { syncManager.bind(loaded.uid) }` **fire-and-forget, does not block routing** (`SplashFragment.kt:141`; `data/sync/SyncManager.kt:74`). Resolves `loaded?.status`.

**Backend call:** `GET api/account/me` (`data/account/AccountService.kt:16-17`) → `AccountMeResponseDto`. Auth header injected by `FirebaseAuthInterceptor` (`AccountService.kt:8-9` comment). No query params, no body.

`AccountRepositoryImpl.fetchMe(maxAttempts)` behaviour (`auth/AccountRepositoryImpl.kt:82-118`):
- Sets `accountState = Loading` **before** the first attempt (`:84`).
- `budget = maxAttempts.coerceAtLeast(1)`; splash passes **1**, so **no retry and no backoff on the splash path**. Every other caller uses `MAX_ATTEMPTS = 3` with **1000 ms linear backoff** between attempts (`:20-21,86,94`).
- `IOException` → retry if budget remains, else `accountState = Failed(httpCode = nil, message, cause)` and `Result.failure`.
- `HttpException` (any 4xx/5xx) → **never retried**, `accountState = Failed(httpCode: e.code(), message: e.message(), cause: nil)`, `Result.failure` (`:95-107`).

**(c) Update probe** — `updatePromptFlow.checkForUpdate()` (`SplashFragment.kt:152-154`).
`UpdatePromptFlow.checkForUpdate()` (`update/UpdatePromptFlow.kt:133-161`) is a GitHub release-catalog probe, `CHECK_TIMEOUT_MS = 2750` (`UpdatePromptFlow.kt:564` — deliberately equal to `SPLASH_PRE_AWAIT_MS`; pinned by `app/src/test/java/com/albunyaan/tube/update/UpdatePromptFlowTest.kt:128-136`). Returns `nil` on timeout, no-update, or fetch failure; short-circuits to `nil` if the prompt was already handled this process (`UpdatePromptFlow.kt:134-137`) so a rotation/recreate does not re-hit the network.

> **iOS deviation (explicit, spec D3 `:22`):** in-app update is **out of scope** on iOS — the App Store handles updates. Replace slot (c) with the **remote-config `minAppVersion` fetch**; on failure show nothing and route normally, on `currentVersion < minAppVersion` show a blocking "update required" screen instead of routing. Spec §6 `:116` calls this slot "remote-config fetch". Everything else about slot (c) — parallel start, bounded await, non-blocking failure — carries over unchanged, including the `UPDATE_AWAIT_GRACE_MS = 500 ms` bounded await.

### 1.5 Deep-link launch (animation skip)

`isDeepLinkLaunch()` = `activity.intent.action == Intent.ACTION_VIEW && intent.data != null` (`SplashFragment.kt:330-333`).

When true (`SplashFragment.kt:161-165`):
1. `updateInfoDeferred.cancel()` — probe abandoned, **no update prompt on a deep-link cold start** (deliberate: "the user tapped a link expecting content").
2. `routeAfterSplash(onboardingDeferred.await(), accountStatusDeferred.await())` immediately — **no animation, no 2750 ms dwell**. The splash frame is still shown while the two awaits resolve (worst case one `GET /api/account/me` round-trip), but nothing animates.
3. **The deep-link target itself is not honoured here** — routing still goes through `SplashRouter`, so an incoming `albunyaantube://video/<id>` on a first launch lands on **Onboarding**, and on a signed-out launch lands on **SignIn**. The deep link is consumed by whatever handles the intent later in `MainActivity`/nav-graph deep links, not by the splash. See open question Q4.

Deep-link surface (`AndroidManifest.xml:52-75` custom scheme; `:76-129` `autoVerify` App Links): `albunyaantube://channel`, `://playlist`, `://video`, plus the verified https hosts (spec §6 `:120` lists the iOS equivalents).

**iOS mapping:** on `onOpenURL` (or a launch `URLContext`), set a `pendingDeepLink` before the splash animation starts; if present → skip animation, skip the remote-config gate, resolve onboarding + account, route via the same router, and only then apply the deep link (iOS should fix the drop described in Q4).

### 1.6 Routing (`routeAfterSplash`) — guards, side effects, order

`SplashFragment.kt:264-328`, executed in this exact order:

1. **Detach guard** — `if (!isAdded) return` (`:271`). The coroutine is `viewLifecycleOwner`-scoped but a `fetchMe` in flight can outlive the user backgrounding out; without this `requireContext()`/`findNavController()` throws.
2. **Destination guard** — `if (findNavController().currentDestination?.id != R.id.splashFragment) return` (`:272`). Prevents a second navigation if something already moved off the splash. **iOS must reproduce this**: route exactly once, guarded on "am I still the splash root".
3. `signedIn = firebaseAuth.currentUser != nil` (`:273`).
4. **Toast + sign-out on failed `/me`** (`:274-291`): if `signedIn && accountStatus == nil`:
   - `Toast(getString(R.string.splash_couldnt_connect), Toast.LENGTH_LONG)` — string key `splash_couldnt_connect`, English text **"Couldn't connect — please sign in again."** (`res/values/strings_onboarding.xml:40`).
   - `requireActivity().lifecycleScope.launch { authRepository.signOut() }` — deliberately on the **activity** scope so the sign-out completes even if the splash is torn down immediately (`:284-290`).
5. **Terminal-status event** (`:300-321`): if `signedIn && (status == DELETED || status == BLOCKED)`, cast `authRepository as? AccountStatusEmitter` and `emit(.Deleted | .Blocked)`. If the cast fails, `Log.e` (fail-loud DI check) and continue.
   The event is consumed by `ui/MainActivity.kt:118-151`: a **non-cancelable** `MaterialAlertDialog`, positive button `R.string.ok` → navigate to sign-in.
   - Blocked → title `account_blocked_title` **"Account blocked"**, body `account_blocked_body` **"Your account has been blocked by an administrator. Contact support for details."** (`res/values/strings.xml:703-704`).
   - Deleted → title `account_deleted_title` **"Account deleted"**, body `account_deleted_body` **"Your account has been deleted. To use FitrahTube again, create a new account."** (`res/values/strings.xml:705-706`).
   - `AccountStatusEvent.SignedOut` is filtered out before the dialog (user-initiated) — `MainActivity.kt:122-125`.
6. `SplashRouter.decideSplashRoute(...)` → `findNavController().navigate(action)` (`:322-327`).

### 1.7 `SplashRouter` — the routing matrix

`ui/SplashRouter.kt:22-33` (pure function, unit-tested at `app/src/test/java/com/albunyaan/tube/ui/SplashRouterTest.kt:16-79`):

| Condition (first match wins) | Android destination | Cite |
|---|---|---|
| `!onboardingCompleted` | Onboarding — **regardless of auth state** | `SplashRouter.kt:27`; test `:16-25` |
| `!signedIn` | SignIn | `:28`; test `:34-39` |
| `accountStatus == nil` (fetch failed) | SignIn — "don't trust stale state" | `:29` |
| `ACTIVE` | Main shell | `:30`; test `:27-32` |
| `PENDING_PROFILE` | ProfileBootstrap | `:31`; test `:51-59` |
| `BLOCKED` / `DELETED` (else) | SignIn | `:32`; test `:61-79` |

Onboarding exit (`SplashRouter.kt:36-37`): `signedIn ? Main : SignIn`.

Nav-graph back-stack semantics to reproduce (`app_nav_graph.xml:11-33`):
- splash → onboarding: `launchSingleTop`, **splash stays on the stack** (`:11-14`).
- splash → signIn / main: `popUpTo="@id/app_nav_graph"` **inclusive** — the whole back stack is cleared (`:15-26`).
- splash → bootstrap: `popUpTo="@id/splashFragment"` inclusive (`:29-33`).
- onboarding → signIn / main: `popUpTo="@id/app_nav_graph"` inclusive (`:43-54`).

**Every terminal route replaces the root — there is no back navigation to the splash or the onboarding.** On iOS: the splash is a root-level overlay/state, not a `NavigationStack` push.

> **iOS deviation (spec §6, `2026-08-23-ios-app-design.md:105-114`, D11 `:30`):** iOS **must not force sign-in**. The ported matrix is:
> ```
> !onboardingCompleted → Onboarding
> signed out           → Main (guest)
> me == nil (network)  → Main (guest, retry fetchMe in background)
> ACTIVE               → Main
> PENDING_PROFILE      → ProfileBootstrap
> BLOCKED / DELETED    → sign out → Main (guest) + terminal alert
> ```
> Consequences the implementer must handle:
> - The `splash_couldnt_connect` toast + forced sign-out on `me == nil` (step 4 above) **does not apply**; instead keep the session, land on guest Main, and retry `fetchMe` with the full 3-attempt / 1 s-backoff budget in the background.
> - The BLOCKED/DELETED alert copy is reused verbatim, but the OK button lands on guest **Main**, not SignIn.

### 1.8 State shape

There is **no ViewModel for splash** — all of it lives inline in the fragment; only the decision is factored out as a pure function. The iOS port should keep the pure router (`SplashRouter` as a free function / enum, spec §16 `:310` lists it as a unit-test target) and give the screen a small observable:

Inputs the router needs (`SplashRouter.kt:22-25`): `onboardingCompleted: Bool`, `signedIn: Bool`, `accountStatus: AccountStatus?`.

`AccountStatus` (`auth/AccountStatus.kt:19-28`) — wire values `"active"`, `"pending_profile"`, `"blocked"`, `"deleted"`; **`fromWire(nil or unknown)` falls back to `BLOCKED`** (`:26-27`) — fail-closed, preserve this exactly.

`AccountState` (`auth/AccountState.kt:8-43`):
- `.notSignedIn`
- `.loading`
- `.failed(httpCode: Int?, message: String?, cause: Error?)` — deliberately a *lightweight* value, not the raw response (avoids pinning the OkHttp body in a hot flow; on iOS just don't retain `URLResponse`/`Data`).
- `.loaded(uid, email?, displayName?, dateOfBirth?, phoneNumber?, status, role)`

Splash-local state to model on iOS: `animationPhase` (idle / logo / name / tagline / spinner / held), `pendingDeepLink: URL?`, `updateGate: MinVersionInfo?`, plus a `hasRouted: Bool` re-entry guard mirroring the `currentDestination` check.

### 1.9 Splash edge cases

- **Zero loading/error/empty states.** No retry button, no error view, no cancel. The only failure surface is the one `LENGTH_LONG` toast (`SplashFragment.kt:279-283`). iOS: prefer a transient banner/`Text` overlay, or (per §1.7 deviation) drop it entirely and go to guest Main.
- **Rotation / process recreate** replays the whole 2750 ms animation; only the update probe is idempotent via `promptDismissedThisProcess` (`UpdatePromptFlow.kt:134-137`). iOS should not re-run the animation on a size-class change.
- Animators cancelled in `onDestroyView` (`SplashFragment.kt:336-341`).
- The spinner is purely decorative — it is not bound to any in-flight request and appears at a fixed t=1550 ms whether or not the network is busy.
- Localisation gap: `splash_couldnt_connect` is **English in all three locales** (`values/strings_onboarding.xml:40`, `values-nl/strings_onboarding.xml:40`, `values-ar/strings_onboarding.xml:40`).
- `SPLASH_PRE_AWAIT_MS` and `UpdatePromptFlow.CHECK_TIMEOUT_MS` are coupled by an assertion test (`UpdatePromptFlowTest.kt:128-136`) — if iOS keeps a parallel bounded probe, keep the same "probe budget ≤ animation budget" invariant.

### 1.10 Splash strings

| Key | English | nl | ar | Cite |
|---|---|---|---|---|
| `app_name` | **FitrahTube** | FitrahTube | فطرة تيوب | `values/strings.xml:3`, `values-nl/strings.xml:3`, `values-ar/strings.xml:3` |
| `splash_tagline` | **Your trusted source for Islamic content** | Uw betrouwbare bron voor islamitische content | مصدرك الموثوق للمحتوى الإسلامي | `values/strings.xml:262`, `values-nl/strings.xml:234`, `values-ar/strings.xml:223` |
| `splash_couldnt_connect` | **Couldn't connect — please sign in again.** | (English) | (English) | `values/strings_onboarding.xml:40` |
| `account_blocked_title` | **Account blocked** | — | — | `values/strings.xml:703` |
| `account_blocked_body` | **Your account has been blocked by an administrator. Contact support for details.** | — | — | `values/strings.xml:704` |
| `account_deleted_title` | **Account deleted** | — | — | `values/strings.xml:705` |
| `account_deleted_body` | **Your account has been deleted. To use FitrahTube again, create a new account.** | — | — | `values/strings.xml:706` |

---

## 2. OnboardingFragment

### 2.1 Content — exactly 3 pages

`onboarding/OnboardingPage.kt:13-28` — a static `listOf` of `OnboardingPage(iconRes, titleRes, descriptionRes)`; no remote content, no VM, no analytics.

| # | Icon drawable | Title key / EN | Description key / EN |
|---|---|---|---|
| 1 | `ic_compass` (`OnboardingPage.kt:15`) | `onboarding_page1_title` — **"Browse"** | `onboarding_page1_desc` — **"Explore a diverse collection of Islamic videos, from lectures to documentaries, all in one place."** |
| 2 | `ic_headphones` (`:21`) | `onboarding_page2_title` — **"Listen in background"** | `onboarding_page2_desc` — **"Continue listening to lectures and recitations even when the app is in the background."** |
| 3 | `ic_download_circle` (`:26`) | `onboarding_page3_title` — **"Download for offline"** | `onboarding_page3_desc` — **"Save your favorite content to watch or listen offline, anytime, anywhere."** |

Strings at `res/values/strings_onboarding.xml:11-20`.

Chrome strings (`res/values/strings_onboarding.xml:3,6-8`):

| Key | EN | nl | ar |
|---|---|---|---|
| `onboarding_continue` | **Next** | Volgende | التالي |
| `onboarding_get_started` | **Get Started** | Aan de slag | ابدأ الآن |
| `onboarding_skip` | **Skip** | Overslaan | تخطي |
| `onboarding_carousel_content` (a11y) | **Onboarding slides** | Introductie dia's | شرائح التعريف |

Localised page copy: `values-nl/strings_onboarding.xml:11-20` (Bladeren / Luister op de achtergrond / Download voor offline) and `values-ar/strings_onboarding.xml:11-20` (تصفح / استمع في الخلفية / حمّل للمشاهدة بدون إنترنت). Full Dutch and Arabic translations exist for all onboarding copy — **unlike** the bootstrap/splash-error strings, which are English placeholders in nl/ar (`values-nl/strings_onboarding.xml:22-40`).

`onboarding_help` ("Learn more about the onboarding content") and `onboarding_help_body` ("Slides summarize FitrahTube's mission, safe content curation, and download policy.") exist in all three locales (`values/strings_onboarding.xml:4-5`) but are **referenced by no code or layout** — dead. Do not port.

### 2.2 Screen layout metrics (dp/sp), phone / sw600dp / sw720dp

`res/layout/fragment_onboarding.xml`, root padding `onboarding_padding` = **24 / 32 / 40 dp** (`:6`; `dimens.xml:103`, sw600:74, sw720:78).

| Element | Property | Phone | sw600 | sw720 | Cite |
|---|---|---|---|---|---|
| `viewPager` | fills top → top of indicator row; `contentDescription = onboarding_carousel_content` | — | — | — | `fragment_onboarding.xml:9-17` |
| indicator dot | size | **8×8 dp** (hardcoded) | 8 | 8 | `fragment_onboarding.xml:33-34,40-41,47-48`; `drawable/onboarding_indicator_active.xml:4` |
| indicator dot | margin (all sides, hardcoded) | **6 dp** → 12 dp gap between dots | 6 | 6 | `fragment_onboarding.xml:35,42,49` |
| indicator row | marginBottom (`spacing_lg`) | **24** | **32** | **40** | `fragment_onboarding.xml:26` |
| `primaryCta` | height (`onboarding_button_height`) | **56** | **60** | **64** | `fragment_onboarding.xml:57`; `dimens.xml:104`, sw600:75, sw720:79 |
| `primaryCta` | corner radius | **28** | **30** | **32** | `fragment_onboarding.xml:66`; `dimens.xml:109`, sw600:76, sw720:80 — always exactly height/2 → **fully capsule** |
| `primaryCta` | max width (`onboarding_button_max_width`) | **400 dp** (all buckets) | 400 | 400 | `fragment_onboarding.xml:59,67`; `dimens.xml:110` (no override) |
| `primaryCta` | horizontal margin (`spacing_lg`) | **24** | **32** | **40** | `fragment_onboarding.xml:58` |
| `primaryCta` | marginBottom (`spacing_md`) | **16** | **20** | **24** | `fragment_onboarding.xml:71`; `dimens.xml:8`, sw600:36, sw720:35 |
| `primaryCta` | fill / label colour | `backgroundTint = @color/primary_green`, `textColor = white` | — | — | `fragment_onboarding.xml:61-62` |
| `skipButton` | Material3 **TextButton** (no fill), `textColor = @color/home_text_secondary` | — | — | — | `fragment_onboarding.xml:76,81` |
| `skipButton` | marginBottom (`spacing_lg`), pinned to parent bottom, centered H | **24** | **32** | **40** | `fragment_onboarding.xml:79,84-86` |

Vertical order bottom-up: **Skip** (bottom) → **primary CTA** → **dots** → pager. Skip sits *below* the CTA.

Focus order for keyboard/D-pad: `primaryCta.nextFocusDown = skipButton`, `nextFocusUp = viewPager`; `skipButton.nextFocusUp = primaryCta` (`fragment_onboarding.xml:64-65,83`).

### 2.3 Page layout metrics — `res/layout/page_onboarding_item.xml`

| Element | Property | Phone | sw600 | sw720 | Cite |
|---|---|---|---|---|---|
| root | paddingHorizontal (`spacing_lg`), `clipToPadding=false`, `clipChildren=false` | **24** | **32** | **40** | `page_onboarding_item.xml:6-8` |
| `topGuide` | horizontal guideline, begin | **24 dp** (hardcoded) | 24 | 24 | `:11-16` |
| `bottomGuide` | horizontal guideline, end | **80 dp** (hardcoded — reserved for the overlaying indicator row) | 80 | 80 | `:19-24` |
| `iconContainer` | size (`onboarding_icon_container_size`) | **160** | **160** | **180** | `:32-33`; `dimens.xml:99`, sw600:70, sw720:74 |
| `iconContainer` | background | circle (`oval`) filled with `@color/settings_icon_bg` | — | — | `:34`; `drawable/onboarding_icon_bg.xml:2-4` |
| `iconContainer` | placement | packed vertical chain, `layout_constraintVertical_bias="0.35"` between `topGuide` and `titleText` | — | — | `:35-40` |
| `iconImage` | size (`onboarding_icon_size`) | **80** | **80** | **90** | `:44-45`; `dimens.xml:100`, sw600:71, sw720:75 |
| `iconImage` | centered in container; `contentDescription = onboarding_carousel_content` | — | — | — | `:46-47` |
| `titleText` | textSize (`onboarding_title_size`) | **28sp** | **32sp** | **36sp** | `:55`; `dimens.xml:101`, sw600:72, sw720:76 |
| `titleText` | bold, centered, colour `@color/home_text_primary` | — | — | — | `:56-58` |
| `titleText` | marginTop (`spacing_xl`) | **32** | **48** | **64** | `:59` |
| `titleText` | max width (`onboarding_content_max_width`) | **600 dp** (all buckets) | 600 | 600 | `:64`; `dimens.xml:111` (no override) |
| `descriptionText` | textSize (`onboarding_description_size`) | **16sp** | **18sp** | **20sp** | `:71`; `dimens.xml:102`, sw600:73, sw720:77 |
| `descriptionText` | colour `@color/home_text_secondary`, centered, `lineSpacingExtra = 4dp` | — | — | — | `:72-74` |
| `descriptionText` | marginTop (`spacing_md`) | **16** | **20** | **24** | `:75` |
| `descriptionText` | max width | **600 dp** | 600 | 600 | `:80` |

`res/layout/page_onboarding.xml` exists (fixed `320dp` height, `icon_xlarge` 96/96/128 dp icon, hardcoded `@android:color/black` title and `darker_gray` body) but **is dead** — the adapter inflates `page_onboarding_item` (`OnboardingPagerAdapter.kt:17`). Ignore it; the live page is `page_onboarding_item.xml`.

### 2.4 Colours used

| Token | Light | Dark | Cite |
|---|---|---|---|
| `primary_green` (CTA fill, active dot, icon tint) | `#275E4B` | `#35C491` | `values/colors.xml:3`, `values-night/colors.xml:4` |
| `settings_icon_bg` (icon circle) | `#F0F0F0` | `#2A3530` | `values/colors.xml:84`, `values-night/colors.xml:67` |
| `home_text_primary` (title) | `#1A1A1A` | `#F1F5F9` | `values/colors.xml:34`, `values-night/colors.xml:25` |
| `home_text_secondary` (description, Skip label) | `#6B7280` | `#9CB3A7` | `values/colors.xml:35`, `values-night/colors.xml:26` |
| inactive dot | **`#CCCCCC` hardcoded** — not dark-mode aware | `#CCCCCC` | `drawable/onboarding_indicator_inactive.xml:5` |
| CTA label | **`@android:color/white` hardcoded** | white | `fragment_onboarding.xml:61` |

> **iOS deviation (spec §7, `2026-08-23-ios-app-design.md:155`):** the CTA label on the brand fill must use the `onBrand` token — light `#FFFFFF`, **dark `#0A1F18`** — because white on the dark-mode mint `#35C491` is 2.2:1 and fails AA. Android's hardcoded white is a bug iOS is instructed not to copy. Likewise the inactive dot should use a real dark-mode-aware token (e.g. `textMuted` at reduced opacity), not `#CCCCCC`.

### 2.5 Behaviour

`ui/OnboardingFragment.kt`:

- **Pager**: `ViewPager2` with `OnboardingPagerAdapter(onboardingPages)` (`:37`). Horizontal swipe both directions, no looping, no page transformer, default `offscreenPageLimit`, no parallax. `OnboardingPagerAdapter` is a plain `RecyclerView.Adapter`, `itemCount = 3` (`OnboardingPagerAdapter.kt:25`).
- **Page-change callback** (`:38-43`): on `onPageSelected(position)` → `updateIndicators(position)` + `updateButton(position)`. Fires on settle, not during drag — the dot and the label flip once the page snaps, not continuously. No debounce, no animation on the dot swap (`setBackgroundResource`, `:74`).
- **Initial state**: `updateIndicators(0)` + `updateButton(0)` are called explicitly (`:46-47`) — page 0, dot 1 active, label "Next".
- **Indicators** (`:63-77`): three fixed `View`s (not generated from the page list); the active one gets `onboarding_indicator_active`, all others `onboarding_indicator_inactive`. Two `Log.d` calls fire on every page change (`:66,73`) — debug noise, do not port.
- **Primary CTA** (`:50-57`): if `currentItem < pages.count - 1` → `viewPager.currentItem = currentItem + 1` (**animated scroll**, ViewPager2 default); else → `navigateToMain()`.
- **Button label** (`:79-85`): `onboarding_get_started` ("Get Started") on the last page, `onboarding_continue` ("Next") otherwise.
- **Skip** (`:59`): `navigateToMain()` directly, from **any** page including the last — Skip and Get Started are behaviourally identical.
- **No Back button**, no "previous page" control, no page counter, no progress bar. System back on Android pops to the splash destination (splash is still on the stack, `app_nav_graph.xml:11-14`) — see Q3.

**`navigateToMain()`** (`:92-107`) — the ordering here is load-bearing:

1. `settingsPreferences.setOnboardingCompleted(true)` is **awaited** (`:99`; DataStore edit at `SettingsPreferences.kt:284-287`, key `onboarding_completed`, `:89`).
2. Only then `findNavController()`, guarded by `currentDestination?.id == R.id.onboardingFragment` (`:101`).
3. `navigate(SplashRouter.decideOnboardingRoute(signedIn: firebaseAuth.currentUser != nil))` (`:102-104`) → Main if signed in, else SignIn (`SplashRouter.kt:36-37`).

The in-source comment (`:93-97`) documents the bug this ordering fixes: the write used to be fire-and-forget on `viewLifecycleOwner.lifecycleScope` and the navigation destroyed the fragment mid-`DataStore.edit`, so onboarding reappeared on the next cold start. **iOS must persist the flag (and let the write complete) before dismissing the onboarding.** A `UserDefaults` synchronous write makes this trivial; if the flag lives in SwiftData/an actor, `await` it first.

> **iOS deviation:** per D11 (`:30`) the signed-out branch goes to **guest Main**, not SignIn.

### 2.6 State shape

`onboarding/OnboardingViewModel.kt:11` is `class OnboardingViewModel : ViewModel()` — a **completely empty placeholder**; the doc comment lists intentions (carousel pages, DataStore flag, help modal, analytics) that were never implemented, and nothing constructs it. All real state is in the fragment.

iOS state to model: `currentPage: Int` (0…2), derived `isLastPage`, derived `ctaTitle`; the pages array is a static constant. No async, no network, no error/empty/loading state on this screen at all.

### 2.7 Onboarding edge cases

- Onboarding is shown **before** any auth consideration (`SplashRouter.kt:27`) — a returning signed-in user who reinstalls sees onboarding first.
- No error, empty, or loading state; no toast, no snackbar, no dialog anywhere in this screen.
- `bottomGuide` at 80 dp reserves room for the dot row, which is a sibling of the pager, not inside a page — on iOS put the dots outside the `TabView(.page)` and give pages the same 80 pt bottom inset so long descriptions never collide.
- Accessibility gap to fix on iOS: the pager and **every page icon** carry the same `contentDescription` ("Onboarding slides") (`fragment_onboarding.xml:13`; `page_onboarding_item.xml:47`), so VoiceOver would repeat it per page. Mark the icons decorative (`.accessibilityHidden(true)`) and label the container.
- RTL: Arabic is a supported locale (`SettingsPreferences.kt:95` — `SUPPORTED_LOCALES = ["en","ar","nl"]`); `ViewPager2` mirrors automatically. On iOS the paging `TabView` mirrors too, but the **dot order must mirror** with it and the "Next" advance direction is leading→trailing.

---

## 3. Assets to reuse

### 3.1 Splash / launch

| File | Type / size | Use | Note |
|---|---|---|---|
| `android/app/src/main/res/drawable/albunyaantube_logo.png` | PNG **800×800**, 230 741 bytes | Splash logo (`fragment_splash.xml:13`) and sign-in logo (`layout/fragment_sign_in.xml:37`, `layout-sw600dp/fragment_sign_in.xml:37`, `layout-sw720dp/fragment_sign_in.xml:41`) | **The only splash artwork. 800×800 is below the 1024 px the spec assumes** (`2026-08-23-ios-app-design.md:167`) — see Q1. |
| `android/app/src/main/res/drawable/ic_splash_house.xml` | vector, declared 120×120 dp, 24 viewport, `fillColor = @color/primary_green` | **Dead** — zero references anywhere in `app/src`. | Do not port. |

There are **no raster launch/splash assets** in any `drawable-*dpi` bucket — those contain only `ic_action_more.png`, `ic_stat_movie.png`, `ic_stat_playlist.png` (mdpi/hdpi/xhdpi/xxhdpi) and `drawable-anydpi/{ic_action_more,ic_stat_movie,ic_stat_playlist}.xml`.

### 3.2 Onboarding

| File | Type | Rendered size | Purpose |
|---|---|---|---|
| `res/drawable/ic_compass.xml` | vector, 48dp declared / 24 viewport, `fillColor = @color/primary_green` | 80/80/90 dp | Page 1 icon. Path is Material **`play_circle_filled`** (circle + right-pointing triangle), **not a compass**, despite the name. SF Symbol equivalent: `play.circle.fill`. |
| `res/drawable/ic_headphones.xml` | vector, 48dp / 24 viewport, `primary_green` | 80/80/90 dp | Page 2 icon. Material `headset`. SF Symbol: `headphones`. |
| `res/drawable/ic_download_circle.xml` | vector, 48dp / 24 viewport, `primary_green` | 80/80/90 dp | Page 3 icon. Material `file_download` (arrow + tray line), **not circular** despite the name. SF Symbol: `arrow.down.to.line` or `square.and.arrow.down`. |
| `res/drawable/onboarding_icon_bg.xml` | shape `oval`, solid `@color/settings_icon_bg` | 160/160/180 dp | Icon backing circle → SwiftUI `Circle().fill(Color.settingsIconBg)`. |
| `res/drawable/onboarding_indicator_active.xml` | shape `oval`, 8×8 dp, solid `@color/primary_green` | 8 dp | Active dot. |
| `res/drawable/onboarding_indicator_inactive.xml` | shape `oval`, 8×8 dp, solid `#CCCCCC` | 8 dp | Inactive dot (hardcoded colour — replace, §2.4). |

Per spec §7 (`:167`) icons are SF Symbols with the same meaning, so the three vectors are **reference-only**; only `albunyaantube_logo.png` is an actual bitmap to copy. The shape drawables become SwiftUI shapes.

### 3.3 App icon sources

| File | Size | Note |
|---|---|---|
| `res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive-icon: `<background>@drawable/ic_launcher_background`, `<foreground>@mipmap/ic_launcher_foreground` | `:2-4` |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | identical adaptive-icon definition | 268 bytes, byte-identical to `ic_launcher.xml` |
| `res/drawable/ic_launcher_background.xml` | vector 108×108 dp, `fillColor = #00000000` | **Fully transparent** by design (`:8-11`: "so no white frame/circle leaks behind the logo"). iOS app icons cannot be transparent — needs an opaque backing plate, see Q1. |
| `res/mipmap-mdpi/ic_launcher_foreground.png` | **108×108** | adaptive foreground |
| `res/mipmap-hdpi/ic_launcher_foreground.png` | **162×162** | |
| `res/mipmap-xhdpi/ic_launcher_foreground.png` | **216×216** | |
| `res/mipmap-xxhdpi/ic_launcher_foreground.png` | **324×324** | |
| `res/mipmap-xxxhdpi/ic_launcher_foreground.png` | **432×432** | **largest available foreground** |
| `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | **48 / 72 / 96 / 144 / 192** | legacy square icon |
| `res/mipmap-{...}/ic_launcher_round.png` | 48 / 72 / 96 / 144 / 192 | **byte-identical** to the matching `ic_launcher.png` at every density (same file sizes: 4171 / 7732 / 11776 / 22311 / 34050) — the "round" variant is not actually a distinct asset |

Manifest wiring: `AndroidManifest.xml:33-34`.

**Best available master for a 1024×1024 iOS app icon: `res/drawable/albunyaantube_logo.png` at 800×800** (larger than any launcher asset). Both candidate sources are under 1024 px — see Q1.

---

## 4. Open questions

1. **No ≥1024 px icon master exists.** Spec §7 (`:167`) says "adaptive foreground at 1024 px", but the largest assets in the repo are `albunyaantube_logo.png` 800×800 and `ic_launcher_foreground.png` 432×432. Upscale, re-export from a vector source outside the repo, or ship at 800 and accept the softness? Also: `ic_launcher_background.xml` is deliberately fully transparent, so an opaque backing colour (brand green? white?) must be chosen for the iOS icon, which cannot have alpha.
2. **Splash first 600 ms is a blank background** (`SplashFragment.kt:168-172` — the logo is made visible *after* the delay, contradicting its own "show logo alone for 600 ms" comment). Is this the intended look (iOS static launch screen bridges it invisibly) or should the iOS splash show the logo from frame 0 and start the 600 ms hold with it visible?
3. **System back from Onboarding** pops to the splash destination, which is still on the stack (`app_nav_graph.xml:11-14`) and would re-run the whole splash flow. iOS has no equivalent gesture from a root-modal onboarding — confirm onboarding is non-dismissible (no swipe-down, no back) until Skip/Get Started.
4. **Deep-link target is dropped when onboarding is incomplete or the user is signed out** — the splash skips the animation but still routes through `SplashRouter` (`SplashFragment.kt:161-165`), so the URL is never applied. Should iOS hold the pending deep link across Onboarding/guest-Main and apply it after, or match Android and drop it?
5. **Splash spinner is decorative**, appearing at a fixed t=1550 ms unrelated to any in-flight request. Keep it purely for pacing, or bind it to the real remote-config/`fetchMe` work on iOS?
6. **The remote-config `minAppVersion` gate** replacing Android's GitHub update probe (spec D3 `:22`, §6 `:116`) has no Android counterpart to port from — no `minAppVersion` or `RemoteConfig` reference exists anywhere under `android/app/src/main/java`. Its timeout budget, blocking-screen copy, and "force vs. recommend" semantics are undefined and need a decision.
7. **`splash_couldnt_connect` disappears entirely under the iOS routing (spec §6 `:105-114`, guest Main instead of forced sign-out).** Confirm nothing is surfaced when the background `fetchMe` retry also fails, or specify a silent-degradation rule for the Me tab.
