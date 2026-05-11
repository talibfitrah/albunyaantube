# Plan B — Android Firebase Auth Integration (Design Spec)

**Status:** Draft for review
**Date:** 2026-05-11
**Branch:** `feature/ANDROID-AUTH-01-firebase-signin`
**Depends on:** Plan A (backend account foundation, merged 2026-05-11)
**Followed by:** Plan C (account bootstrap / profile), Plan D (sync engine + anonymous→account merge)

---

## 1. Goal

Wire Firebase Authentication into the Android app so users sign in with Google, Microsoft, or email/password; their Firebase ID token rides on every backend request; the backend's `FirebaseAuthFilter` (Plan A) recognises them; and account-lifecycle responses (`401`, `403 ACCOUNT_BLOCKED`, `403 ACCOUNT_DELETED`) drive the client into a clean sign-out.

This plan does **only** authentication transport. No profile, no sync, no anonymous merge — those land in C and D.

---

## 2. Non-goals

- Account bootstrap (display name, age, COPPA under-13 + parental consent) → **Plan C**
- Anonymous→account merge, subscriptions sync, downloads sync → **Plan D**
- Multi-factor auth, account linking across providers → **Future**
- Password-reset email *delivery* via SES/SendGrid → **Plan F**. This plan calls `FirebaseAuth.sendPasswordResetEmail()`, which uses Firebase's default templates.
- Admin frontend UI changes → **Plan F**
- Widening `FirebaseAuthFilter.shouldNotFilter` to drop the `/api/v1/*` exemption — deferred until user-bound `/api/v1/*` endpoints exist (Plan D). For Plan B, `/api/v1/*` requests carry the ID token (when available) but the filter still skips them; admin endpoints require it.

---

## 3. User-facing flow

```
App launch
  ↓
SplashFragment (existing) → check FirebaseAuth.currentUser
  ↓                                                    ↓
  signed-in                                       not signed-in
  ↓                                                    ↓
MainShellFragment (existing)                    SignInFragment (new)
                                                       ↓
                                            ┌──────────┴───────────┐
                                            ↓          ↓          ↓
                                          Google   Microsoft   email/pw
                                            └──────────┬───────────┘
                                                       ↓
                                                Firebase Auth
                                                       ↓
                                              MainShellFragment
```

**Sign-out** (from Me tab settings, or forced by `403 ACCOUNT_BLOCKED` / `403 ACCOUNT_DELETED`):
1. `FirebaseAuth.signOut()`
2. Clear cached ID token + local subscriptions/downloads state (the local-only DBs stay; Plan D handles sync semantics)
3. Pop nav back to `SignInFragment`
4. Show toast: "Signed out" or "Your account was blocked — contact support" depending on cause

**First-run path:** `OnboardingFragment` (existing) → `SignInFragment` → `MainShellFragment`. The onboarding screens are presented once before the first sign-in.

---

## 4. Auth providers

| Provider | Mechanism | Cost / config |
|---|---|---|
| Google | `com.google.android.gms:play-services-auth` + Firebase `GoogleAuthProvider` | Web client ID from Firebase console (already exists for backend) |
| Microsoft | `OAuthProvider` builder with `microsoft.com` provider id | Azure AD app registration; redirect URI `https://<firebase-project>.firebaseapp.com/__/auth/handler` |
| Email/password | `FirebaseAuth.createUserWithEmailAndPassword` / `signInWithEmailAndPassword` | None — enabled in Firebase console |

All three converge on `FirebaseAuth.getCurrentUser()` and a single ID-token contract — the rest of the app does not care which provider was used.

---

## 5. Architecture

### 5.1 New module: `auth` package

```
android/app/src/main/java/com/albunyaan/tube/auth/
├── AuthRepository.kt            # façade over FirebaseAuth
├── AuthState.kt                 # sealed class: SignedOut, SigningIn, SignedIn(user), Error
├── FirebaseAuthInterceptor.kt   # attaches Bearer <idToken>
├── AccountStatusInterceptor.kt  # observes 401/403 → triggers sign-out
└── di/FirebaseAuthModule.kt     # Hilt @Provides FirebaseAuth, AuthRepository, both interceptors
```

### 5.2 Sign-in UI

```
android/app/src/main/java/com/albunyaan/tube/ui/auth/
├── SignInFragment.kt            # XML-inflated; observes AuthViewModel state
├── SignInViewModel.kt           # Hilt; AuthRepository + StateFlow<SignInUiState>
└── res/layout/fragment_sign_in.xml       # phone
    res/layout-sw600dp/fragment_sign_in.xml   # tablet (wider form, centered card)
    res/layout-sw720dp/fragment_sign_in.xml   # TV (largest card, focus-friendly)
```

All three variants share the same view IDs (per CLAUDE.md tablet/TV rule). RTL strings use `textAlignment="viewStart"`. Sign-in surfaces:

- Email field + password field + **Sign in** button
- "Forgot password?" link → triggers `sendPasswordResetEmail()` + toast
- "Create account" link → swaps to sign-up state (same fragment, different ViewModel state)
- Horizontal divider with "or" label
- **Google** sign-in button (uses `play-services-auth` one-tap UI)
- **Microsoft** sign-in button (`FirebaseAuth.startActivityForSignInWithProvider`)
- Inline error text below the form
- Loading spinner replaces the button row while a request is in flight

### 5.3 Hilt wiring

```kotlin
@Module @InstallIn(SingletonComponent::class)
object FirebaseAuthModule {
    @Provides @Singleton fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
    @Provides @Singleton fun authRepository(...): AuthRepository = AuthRepositoryImpl(...)
    @Provides @IntoSet @AuthInterceptor fun authInterceptor(...): Interceptor = FirebaseAuthInterceptor(...)
    @Provides @IntoSet @AuthInterceptor fun statusInterceptor(...): Interceptor = AccountStatusInterceptor(...)
}
```

`NetworkModule.provideOkHttpClient` adds both interceptors. **Order matters**: `FirebaseAuthInterceptor` runs first (attaches token); `AccountStatusInterceptor` runs after as a *network* interceptor (observes the response).

### 5.4 ID-token lifecycle

```kotlin
class FirebaseAuthInterceptor(private val auth: FirebaseAuth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val user = auth.currentUser
            ?: return chain.proceed(chain.request())   // signed-out → no header

        val token = runBlocking {
            // forceRefresh=false → returns cached token until ~5min before expiry,
            // then refreshes silently. Firebase SDK handles refresh queuing.
            user.getIdToken(false).await().token
        } ?: return chain.proceed(chain.request())

        val authed = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        val response = chain.proceed(authed)

        // One-shot retry on 401 with force-refreshed token (covers stale-token race
        // when the backend rotated keys between cache fetch and request arrival).
        if (response.code == 401 && response.header("WWW-Authenticate")?.contains("Bearer") == true) {
            response.close()
            val fresh = runBlocking { user.getIdToken(true).await().token } ?: return response
            return chain.proceed(authed.newBuilder().header("Authorization", "Bearer $fresh").build())
        }
        return response
    }
}
```

**Why `runBlocking`:** OkHttp interceptors are synchronous. The Firebase SDK's `getIdToken()` returns a `Task<GetTokenResult>` which we adapt with `kotlinx-coroutines-play-services.await()`. The blocking sits on OkHttp's dispatcher thread (network thread pool), not the main thread, so it does not cause ANRs. Token refresh is local-only when the cached token is still fresh; the slow path (cache miss → network call to Google) only triggers near expiry.

**ID-token caching by Firebase SDK:** ~1h TTL. Subsequent `getIdToken(false)` calls within that window return synchronously from in-memory cache. After expiry the SDK transparently refreshes using the long-lived refresh token, also stored locally by the SDK. We do **not** maintain our own token cache.

### 5.5 Account-status interceptor

Plan A's `FirebaseAuthFilter` returns:
- `401` if the token is invalid/expired/missing
- `403 ACCOUNT_BLOCKED` if `user.status == "blocked"`
- `403 ACCOUNT_DELETED` if `user.status == "deleted"`

The interceptor handles only the **terminal** 403s — 401 is already handled by `FirebaseAuthInterceptor`'s one-shot retry above; if that also returns 401, we let it bubble to the repository layer.

```kotlin
class AccountStatusInterceptor(
    private val auth: FirebaseAuth,
    private val statusFlow: MutableSharedFlow<AccountStatusEvent>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 403) {
            val body = response.peekBody(512).string()
            val event = when {
                body.contains("\"code\":\"ACCOUNT_BLOCKED\"") -> AccountStatusEvent.Blocked
                body.contains("\"code\":\"ACCOUNT_DELETED\"") -> AccountStatusEvent.Deleted
                else -> null
            }
            if (event != null) {
                statusFlow.tryEmit(event)
                auth.signOut()
            }
        }
        return response
    }
}
```

`MainActivity` subscribes to `statusFlow` and shows the appropriate dialog + nav back to sign-in. The `signOut()` happens inside the interceptor (synchronously, before the response returns) so subsequent requests on the same screen do not re-trigger the 403 dialog.

### 5.6 What lives in `AuthRepository`

```kotlin
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val accountStatusEvents: SharedFlow<AccountStatusEvent>

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser>
    suspend fun signUpWithEmail(email: String, password: String): Result<FirebaseUser>
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser>
    suspend fun signInWithMicrosoft(activity: Activity): Result<FirebaseUser>
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
    suspend fun signOut()
}
```

`signOut()` calls `FirebaseAuth.signOut()` + emits `AuthState.SignedOut` + clears any in-memory cached user role. Local DBs (downloads, history, subscriptions) are **not** wiped — Plan D defines sync semantics.

---

## 6. Dependencies (new)

`android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
```

`android/build.gradle.kts` (root):
```kotlin
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}
```

`google-services.json` lives at `android/app/google-services.json`, **gitignored** (consistent with the SA scrub policy — secrets stay out of the repo). A `google-services.json.template` is checked in with the schema for new contributors. CI pulls the real file from a secret.

---

## 7. Testing strategy

### 7.1 Unit
- `AuthRepositoryTest` — Firebase SDK mocked via Mockito; covers success + each failure code (`ERROR_INVALID_EMAIL`, `ERROR_USER_DISABLED`, `ERROR_WRONG_PASSWORD`, …).
- `SignInViewModelTest` — state transitions: idle → loading → signed-in / error.
- `FirebaseAuthInterceptorTest` — MockWebServer + mocked `FirebaseUser.getIdToken`; verifies header attached, 401 retry path, signed-out request bypass.
- `AccountStatusInterceptorTest` — MockWebServer returns 403 with each error code; verify `statusFlow.emit` + `auth.signOut()`.

### 7.2 Integration (Firebase Auth Emulator, port 9099)
- `AuthRepositoryEmulatorTest` — sign-up + sign-in + sign-out round-trip against the emulator. Uses `FirebaseAuth.useEmulator("10.0.2.2", 9099)` in the debug build.
- One smoke test that exercises the full backend round-trip: Firebase emulator + backend running locally → sign in → call `/api/v1/categories` → assert 200 + `Authorization` header present in the recorded backend log.

### 7.3 UI verification (manual, per CLAUDE.md UI-preservation rule)
Run sign-in flow on:
- Phone (compileSdk 36 emulator)
- `sw600dp` tablet emulator
- `sw720dp` TV emulator
- RTL (Arabic locale) on each

Verify: form fits without scrolling on each; Google / Microsoft buttons render; focus order correct on TV; password field obscures input.

---

## 8. Backwards-compatibility / migration

Pre-Plan-B users have no Firebase account. There is **no migration** — the app forces sign-in for everyone on first launch after the update. The existing `OnboardingFragment` precedes sign-in for new installs; for upgrade installs we just route directly to `SignInFragment`. Anonymous browsing of `/api/v1/*` continues to work for users who close the sign-in screen and re-open the app from a deep link, but admin functionality is gated behind sign-in.

Local SQLite (downloads, history, subscriptions) is **not** wiped on sign-in — Plan D handles whether to merge those into a fresh server-side account.

---

## 9. Risks & open questions

| Risk | Mitigation |
|---|---|
| Microsoft OAuth requires Azure AD config + redirect URI | Document the Azure AD setup in the plan's "ops" section; user must register the app before this ships |
| Token-refresh storm on app cold-start | Firebase SDK already coalesces concurrent `getIdToken` calls; we rely on that |
| `runBlocking` on OkHttp dispatcher thread | Acceptable: never on main thread. Add lint suppression with comment, do not propagate the pattern elsewhere |
| Existing `OnboardingFragment` flow assumes no auth gate | Update `SplashFragment` to branch on `FirebaseAuth.currentUser` before deciding whether to show onboarding |
| Plan A's `FirebaseAuthFilter` still uses Firebase Admin SDK; this introduces the client SDK | Independent code paths — server verifies tokens minted by the client. No shared state. |
| google-services.json leak on push | gitignore + pre-commit guard already in place from SA scrub work |

**Open** (decide during plan-draft):
- Should the sign-up form ask for a display name now (then Plan C edits the same `users` doc), or wait for Plan C's bootstrap step? **Recommendation:** wait. Sign-up creates the auth record only; the `users` Firestore doc is created lazily by backend on first authenticated `/api/v1/*` call (Plan A's `FirebaseAuthFilter` already does this).
- Where does the sign-out trigger live in the Me tab? **Recommendation:** new "Account" section at the bottom of Me with a "Sign out" row. Detailed UI in the plan.

---

## 10. Deliverables checklist (plan-level)

- [ ] T1 Add Firebase Auth + Google Sign-In deps; wire google-services plugin; commit `.json.template`
- [ ] T2 `FirebaseAuthModule` + `AuthRepository` (+ unit tests)
- [ ] T3 `FirebaseAuthInterceptor` + `AccountStatusInterceptor` (+ unit tests, wire into `NetworkModule`)
- [ ] T4 `SignInFragment` + `SignInViewModel` + XML for phone/sw600dp/sw720dp (+ ViewModel tests)
- [ ] T5 Splash + nav-graph routing (signed-in vs signed-out)
- [ ] T6 Sign-out trigger in Me tab + `AccountStatusEvent` dialog in `MainActivity`
- [ ] T7 Firebase Auth emulator integration test
- [ ] T8 Manual UI verification across 3 variants + RTL
- [ ] T9 Documentation: `docs/status/ANDROID_GUIDE.md` section on auth flow

Plan B should land in ~9 commits, ~800–1000 LOC across `auth/`, `ui/auth/`, layouts, tests.
