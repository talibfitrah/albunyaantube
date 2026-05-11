# Plan B — Android Firebase Auth Integration (Implementation Plan)

**Branch:** `feature/ANDROID-AUTH-01-firebase-signin`
**Spec:** [`docs/superpowers/specs/2026-05-11-android-auth-design.md`](../specs/2026-05-11-android-auth-design.md)
**Target:** ~9 commits, ~800–1000 LOC. Merge to `develop`, not `main`.

## Self-critique: things in the spec that need fixing in this plan

Drafting the plan surfaced five concrete issues with the spec I wrote yesterday. Calling them out now rather than discovering them mid-implementation:

1. **Brittle JSON substring match in `AccountStatusInterceptor`.** Spec §5.5 matched `"\"code\":\"ACCOUNT_BLOCKED\""` via `body.contains(...)`. That breaks the second any whitespace appears in the response, or if the field order shifts. Plan uses Moshi to parse the error envelope. (T3.)
2. **`Activity` leaked into `AuthRepository.signInWithMicrosoft(activity)`.** Singleton-scoped repository shouldn't hold Activity references. Plan moves the Microsoft OAuth call to the ViewModel layer (`startActivityForSignInWithProvider(activity, provider)`) and feeds the resulting `AuthCredential` back to the repository. (T2, T4.)
3. **`/api/v1/*` is exempt from Plan A's `FirebaseAuthFilter`** — `AccountStatusInterceptor` will never fire there. A BLOCKED user can still browse public catalog endpoints until they hit `/api/admin/*`. Plan A accepted this; the plan documents it explicitly so we don't promise behavior we can't deliver. (T3 review notes.)
4. **NetworkModule today wires interceptors inline.** Adding @IntoSet multibinding for the new interceptors requires migrating the existing `X-Device-Id` and logging interceptors to the same pattern. Plan does the migration in T3, not later. (T3.)
5. **`firebase-bom` and `play-services-auth` versions are unverified.** Spec named 33.0.0 and 21.2.0 from memory. T1 pins versions by reading the current published BoM from Google's Maven before writing the gradle file — no hard-coded guesses.

Two **non-fixable** concerns are scoped out and recorded:
- Microsoft OAuth on TV is unsupported (Custom Tabs absent on AOSP TV). T6 surfaces this as "Google sign-in only" on TV form factor.
- `runBlocking` inside the interceptor remains. Firebase SDK coalesces concurrent `getIdToken` calls; we rely on that. T3 adds a load test asserting we don't saturate the OkHttp dispatcher under 64 concurrent requests against a cold token.

---

## Task overview

| # | Task | LOC | New files | Edited files |
|---|---|---|---|---|
| T1 | Firebase deps + google-services bootstrap | ~60 | 2 | 3 |
| T2 | AuthRepository + Hilt module + tests | ~220 | 7 | 0 |
| T3 | Two interceptors + NetworkModule refactor + tests | ~200 | 4 | 1 |
| T4 | Sign-in fragment + ViewModel + 3 layouts + strings | ~280 | 8 | 3 |
| T5 | Splash routing + nav graph + auth gate | ~80 | 0 | 3 |
| T6 | Sign-out + 403 dialog wiring + status flow | ~110 | 2 | 3 |
| T7 | Firebase Auth emulator integration tests | ~140 | 3 | 1 |
| T8 | Manual UI verification across 3 variants + RTL | — | 0 | 0 |
| T9 | ANDROID_GUIDE.md update | ~40 | 0 | 1 |

**Per-task workflow** (same as Plan A):
1. **Implementer** writes the code.
2. **Spec reviewer** (general-purpose subagent) compares against this plan + the spec; flags drift.
3. **Code-quality reviewer** (general-purpose subagent) reads the diff cold; flags issues per `feedback_review_pipeline.md`.
4. Both subagent reports addressed in the same commit if Critical, follow-up commit if Important, deferred-with-reason if Minor.
5. Commit with `[PLATFORM-TICKET-Tn]: description` prefix; one task = one commit family on `feature/ANDROID-AUTH-01-firebase-signin`.

---

## T1 — Firebase deps + google-services bootstrap

### Pre-condition
- `git status` clean on `feature/ANDROID-AUTH-01-firebase-signin`
- Firebase project already exists (Plan A backend uses the same project)

### Steps

1. **Pin BoM version.** Read https://firebase.google.com/docs/android/learn-more (or `curl https://search.maven.org/solrsearch/select?q=g:com.google.firebase+a:firebase-bom&core=gav&rows=1`) to get the current Firebase Android BoM version. **Do not hard-code from memory.** Pin to the exact discovered version; document in the commit message.

2. **Root `android/build.gradle.kts`:** add
    ```kotlin
    plugins {
        id("com.google.gms.google-services") version "<verified-current>" apply false
    }
    ```

3. **`android/app/build.gradle.kts`:** apply plugin at top + add dependencies block entries
    ```kotlin
    plugins {
        // existing plugins …
        id("com.google.gms.google-services")
    }
    dependencies {
        // existing …
        implementation(platform("com.google.firebase:firebase-bom:<verified-current>"))
        implementation("com.google.firebase:firebase-auth")
        implementation("com.google.android.gms:play-services-auth:<verified-current>")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:<match-coroutines-version>")
    }
    ```
    Match `kotlinx-coroutines-play-services` version to the project's existing `kotlinx-coroutines` version (resolve from `libs.versions.toml` or wherever it's pinned). Version drift breaks structured concurrency in subtle ways.

4. **Commit `android/app/google-services.json.template`.** Plain-JSON skeleton matching Firebase's schema, with placeholder strings for every value. Documents the file structure for new contributors without leaking the real config.

5. **`.gitignore`:** add `android/app/google-services.json`. Verify the real file is **not** tracked: `git ls-files android/app/google-services.json` returns empty.

6. **Local setup:** download `google-services.json` from Firebase console into `android/app/`. Verify build succeeds:
    ```bash
    cd android && ./gradlew assembleDebug
    ```

### Tests
- Build passes locally and in CI (`./gradlew assembleDebug` + `:app:test`).
- `git ls-files android/app/google-services.json` is empty.

### Spec/quality review focus
- Did we pick the **current** BoM version, not yesterday's?
- Is `google-services.json` gitignored *before* anyone adds the real file?

### Commit
`[FEAT-ANDROID-AUTH-01-T1]: Firebase Auth deps + google-services bootstrap`

---

## T2 — AuthRepository + Hilt module + tests

### New files (under `android/app/src/main/java/com/albunyaan/tube/auth/`)

#### `AuthState.kt`
```kotlin
sealed interface AuthState {
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data class SignedIn(val user: FirebaseUser, val uid: String) : AuthState
    data class Error(val message: String, val cause: AuthErrorCode) : AuthState
}

enum class AuthErrorCode {
    INVALID_EMAIL, WRONG_PASSWORD, USER_NOT_FOUND, USER_DISABLED, EMAIL_ALREADY_IN_USE,
    WEAK_PASSWORD, NETWORK, GOOGLE_SIGN_IN_FAILED, MICROSOFT_SIGN_IN_FAILED,
    PASSWORD_RESET_FAILED, UNKNOWN
}
```

#### `AccountStatusEvent.kt`
```kotlin
sealed interface AccountStatusEvent {
    data object Blocked : AccountStatusEvent
    data object Deleted : AccountStatusEvent
}
```

#### `AuthRepository.kt` (interface)
Methods, all `suspend` and returning `Result<T>` for caller-controlled error handling (NOT throwing through the layers):
```kotlin
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val accountStatusEvents: SharedFlow<AccountStatusEvent>

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser>
    suspend fun signUpWithEmail(email: String, password: String): Result<FirebaseUser>
    suspend fun signInWithCredential(credential: AuthCredential): Result<FirebaseUser>
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
    suspend fun signOut()

    /** Internal — invoked by AccountStatusInterceptor. Not part of UI contract. */
    fun emitAccountStatus(event: AccountStatusEvent)
}
```

Note the **single** `signInWithCredential` instead of separate `signInWithGoogle` / `signInWithMicrosoft` (fixes self-critique #2). The Google or Microsoft credential is built in the ViewModel layer where the Activity is available.

#### `AuthRepositoryImpl.kt`
- Wraps `FirebaseAuth`
- Maps `FirebaseAuthException.errorCode` strings to `AuthErrorCode`
- Maintains `MutableStateFlow<AuthState>` and `MutableSharedFlow<AccountStatusEvent>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` — buffer overflow drops new events when a backlog accumulates; we never want to *block* an interceptor's emit.
- `FirebaseAuth.AuthStateListener` observes sign-in/sign-out and updates `authState` accordingly. **Registers in `init`, never unregisters** — repository is Singleton, lives for app lifetime.

#### `AuthErrorMapper.kt`
- Pure function: `FirebaseAuthException.errorCode → AuthErrorCode`. Tested separately.

#### `di/FirebaseAuthModule.kt`
```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class FirebaseAuthModule {

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    companion object {
        @Provides @Singleton
        fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
    }
}
```

### Tests (`android/app/src/test/java/com/albunyaan/tube/auth/`)

#### `AuthRepositoryImplTest.kt`
Mock `FirebaseAuth`. Cover:
- `signInWithEmail` success → emits `SignedIn`, `Result.success`
- `signInWithEmail` invalid email → emits `Error(INVALID_EMAIL)`, `Result.failure`
- `signInWithEmail` user disabled → emits `Error(USER_DISABLED)`, `Result.failure` (this is how Plan A's BLOCKED status surfaces at sign-in time; the backend already set `disabled=true` on the Firebase user)
- `signUpWithEmail` email already in use → emits `Error(EMAIL_ALREADY_IN_USE)`
- `signInWithCredential` with Google credential → success
- `signOut` → emits `SignedOut`, listeners notified
- `emitAccountStatus(Blocked)` → received on `accountStatusEvents` flow within 100ms

#### `AuthErrorMapperTest.kt`
Table-driven: input `errorCode` string → expected `AuthErrorCode`. Covers all 11 known codes + one unknown (→ `UNKNOWN`).

### Spec/quality review focus
- Is `MutableSharedFlow` buffer policy correct? (We DROP_OLDEST on overflow; reviewer should question whether SUSPEND would be safer. Argument for DROP_OLDEST: emit is called from OkHttp interceptor thread; we must not block.)
- `FirebaseAuth.AuthStateListener` registration is unbalanced (no unregister). Is that fine for a Singleton? (Yes — repository lives for app lifetime; unregistering on app shutdown is unnecessary and risks NPE if the SDK is already torn down. Document the choice.)

### Commit
`[FEAT-ANDROID-AUTH-01-T2]: AuthRepository + FirebaseAuthModule`

---

## T3 — Two interceptors + NetworkModule refactor + tests

### NetworkModule refactor

Migrate the two existing inline interceptors (HTTP logging, `X-Device-Id`) to multibinding so the new ones drop in cleanly.

**New qualifier:** `@AppInterceptor` (in `com.albunyaan.tube.di`)

**NetworkModule changes:**
```kotlin
@Provides @IntoSet @AppInterceptor
fun loggingInterceptor(): Interceptor = HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BASIC
}

@Provides @IntoSet @AppInterceptor
fun deviceIdInterceptor(@ApplicationContext context: Context): Interceptor =
    Interceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .header("X-Device-Id", getOrCreateDeviceId(context))
            .build())
    }

@Provides @Singleton
fun provideOkHttpClient(
    @ApplicationContext context: Context,
    @AppInterceptor interceptors: Set<@JvmSuppressWildcards Interceptor>,
): OkHttpClient {
    val cacheDir = File(context.cacheDir, "http_cache")
    if (cacheDir.exists()) {
        Thread { cacheDir.deleteRecursively() }.start()
    }
    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
    interceptors.forEach(builder::addInterceptor)
    return builder.build()
}
```

**Critical order property:** `Set<Interceptor>` from Hilt has **non-deterministic iteration order**. If order matters (it does: `FirebaseAuthInterceptor` must run before `AccountStatusInterceptor` reads the response), we either:
- (a) Use `@IntoMap` with explicit integer priority keys and sort by key, OR
- (b) Provide a single `List<Interceptor>` in a fixed order in this module instead of multibinding

Decision: **(b)**. Multibinding's value is letting separate modules contribute interceptors. Order requirement here means a single source of truth. Refactor:

```kotlin
@Provides @Singleton @AppInterceptor
fun appInterceptors(
    @ApplicationContext context: Context,
    authInterceptor: FirebaseAuthInterceptor,
    statusInterceptor: AccountStatusInterceptor,
): List<Interceptor> = listOf(
    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
    Interceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .header("X-Device-Id", getOrCreateDeviceId(context))
            .build())
    },
    authInterceptor,        // attaches Bearer token, owns 401 retry
    statusInterceptor,      // observes 403 → signs out
)
```

`provideOkHttpClient` consumes the `List<Interceptor>` in order. Self-critique #4 fixed.

### `FirebaseAuthInterceptor.kt`

```kotlin
@Singleton
class FirebaseAuthInterceptor @Inject constructor(
    private val auth: FirebaseAuth,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val user = auth.currentUser ?: return chain.proceed(chain.request())

        val token = runBlocking { user.getIdToken(false).await().token }
            ?: return chain.proceed(chain.request())

        val signed = chain.request().withBearer(token)
        val response = chain.proceed(signed)

        if (response.code == 401 && response.isFirebaseUnauthorized()) {
            response.close()
            val refreshed = runBlocking { user.getIdToken(true).await().token }
                ?: return chain.proceed(signed)
            return chain.proceed(signed.withBearer(refreshed))
        }
        return response
    }

    private fun Request.withBearer(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun Response.isFirebaseUnauthorized() =
        header("WWW-Authenticate")?.contains("Bearer", ignoreCase = true) == true
}
```

Notes:
- `runBlocking` on OkHttp's dispatcher thread (NOT main). Documented at the class level with a `// PERFORMANCE: …` comment so reviewers know it was intentional.
- One-shot retry — never loops. The second token, if also rejected, propagates 401 to repository layer.

### `AccountStatusInterceptor.kt`

```kotlin
@JsonClass(generateAdapter = true)
data class ApiErrorEnvelope(val code: String?, val message: String?)

@Singleton
class AccountStatusInterceptor @Inject constructor(
    private val auth: FirebaseAuth,
    private val repository: AuthRepository,
    private val moshi: Moshi,
) : Interceptor {

    private val adapter by lazy { moshi.adapter(ApiErrorEnvelope::class.java) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code != 403) return response

        val peeked = response.peekBody(1024).string()
        val envelope = try { adapter.fromJson(peeked) } catch (_: IOException) { null }

        val event = when (envelope?.code) {
            "ACCOUNT_BLOCKED" -> AccountStatusEvent.Blocked
            "ACCOUNT_DELETED" -> AccountStatusEvent.Deleted
            else -> return response
        }

        repository.emitAccountStatus(event)
        auth.signOut()
        return response
    }
}
```

Self-critique #1 fixed: proper Moshi parsing instead of `body.contains(...)`. The 1024-byte peek is bounded so we can't OOM on a malicious huge response.

### Tests (`android/app/src/test/java/com/albunyaan/tube/auth/`)

#### `FirebaseAuthInterceptorTest.kt`
- Signed-out user → no `Authorization` header
- Signed-in user → `Authorization: Bearer <token>` present
- Backend 401 with `WWW-Authenticate: Bearer …` → force-refreshes token, retries once
- Backend 401 *without* `WWW-Authenticate` header → does not retry (covers Firestore client errors, network proxies)
- Two consecutive 401s → final 401 returned to caller (no infinite loop)

Use `okhttp3.mockwebserver.MockWebServer`. Mock `FirebaseUser.getIdToken` via Mockito; return alternating tokens to verify the retry path.

#### `AccountStatusInterceptorTest.kt`
- 200 → returned unchanged, no flow emit
- 403 with `{"code":"ACCOUNT_BLOCKED","message":"..."}` → emits `Blocked`, signs out
- 403 with `{"code":"ACCOUNT_DELETED","message":"..."}` → emits `Deleted`, signs out
- 403 with `{"code":"OTHER"}` → does NOT emit, does NOT sign out (returns response unchanged)
- 403 with malformed JSON → does NOT emit, does NOT sign out
- 403 with body > 1024 bytes → peek is bounded; parse may fail; treated as "OTHER"

#### `LoadInterceptorTest.kt` (load test, JUnit)
- 64 concurrent OkHttp dispatcher threads issuing signed requests against a 200-OK MockWebServer
- Mock `getIdToken(false)` to suspend 50ms before returning
- Assert all 64 complete within 5s (proves the Firebase SDK's coalescing handles the spike). If it doesn't, the test fails loudly and we need to add our own token cache in front of the SDK. **This is the load test the spec promised.**

### Spec/quality review focus
- Is the single-`List<Interceptor>` decision documented in code with rationale?
- Is the 1024-byte peek bound enforced? (Mock a 2KB body; assert no OOM.)
- Does the test cover the case where `FirebaseUser` is non-null but `getIdToken().token` is null? (Rare — happens during account-link race conditions.)

### Commit
`[FEAT-ANDROID-AUTH-01-T3]: FirebaseAuthInterceptor + AccountStatusInterceptor`

---

## T4 — Sign-in fragment + ViewModel + 3 layouts + strings

### Strings (`res/values/strings.xml` + AR + NL)

New keys:
```
auth_sign_in_title          "Sign in"
auth_sign_up_title          "Create account"
auth_email_hint             "Email"
auth_password_hint          "Password"
auth_sign_in_button         "Sign in"
auth_sign_up_button         "Create account"
auth_forgot_password        "Forgot password?"
auth_create_account_link    "Don't have an account? Create one"
auth_have_account_link      "Already have an account? Sign in"
auth_divider_or             "or"
auth_google_button          "Continue with Google"
auth_microsoft_button       "Continue with Microsoft"
auth_password_reset_sent    "Password reset email sent"
auth_error_invalid_email    "That email looks wrong"
auth_error_wrong_password   "Email or password is incorrect"
auth_error_user_not_found   "No account found"
auth_error_user_disabled    "This account has been blocked. Contact support."
auth_error_email_in_use     "An account already exists with that email"
auth_error_weak_password    "Password must be at least 6 characters"
auth_error_network          "No internet connection"
auth_error_google           "Google sign-in failed"
auth_error_microsoft        "Microsoft sign-in failed"
auth_error_generic          "Something went wrong"
```

AR strings translated by translator (placeholder English values until then; flagged in a TODO comment in `values-ar/strings.xml`). NL same. **Do not** machine-translate inline — `docs/design/i18n-strategy.md` mandates human translation.

### Layouts

#### `res/layout/fragment_sign_in.xml` (phone)
```
ScrollView
  └── ConstraintLayout (padding @dimen/spacing_md)
        ├── ImageView: app logo (top, 96dp)
        ├── TextView: auth_sign_in_title (h2 style)
        ├── TextInputLayout + TextInputEditText: email
        ├── TextInputLayout + TextInputEditText: password (passwordToggleEnabled)
        ├── TextView (link style): forgot password
        ├── Button (filled): sign in (id=signInButton)
        ├── TextView (link style): create account link (id=toggleModeLink)
        ├── horizontal divider with "or" label
        ├── Button (outlined, leading icon): Google
        ├── Button (outlined, leading icon): Microsoft
        └── ProgressBar (id=loadingSpinner, visibility=gone)
```

Form width: `match_parent` with horizontal padding. Inputs use `android:textAlignment="viewStart"` (RTL safe).

#### `res/layout-sw600dp/fragment_sign_in.xml` (tablet)
Same view IDs. Centered card, max width 480dp, drop shadow. Logo larger (128dp).

#### `res/layout-sw720dp/fragment_sign_in.xml` (TV)
Same view IDs. Max width 560dp. All interactive elements have `android:focusable="true"` and `android:nextFocusDown="…"` chained explicitly so the D-pad walks the form top-to-bottom. **No Microsoft button on TV** — replaced with an info text: `auth_microsoft_unavailable_tv` = "Microsoft sign-in is not available on TV. Use Google or email." See self-critique non-fixable concern #1.

### ViewModel

#### `SignInViewModel.kt`
```kotlin
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    enum class Mode { SIGN_IN, SIGN_UP }

    data class UiState(
        val mode: Mode = Mode.SIGN_IN,
        val email: String = "",
        val password: String = "",
        val isLoading: Boolean = false,
        val error: AuthErrorCode? = null,
        val passwordResetSent: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun onEmailChanged(value: String) { _ui.update { it.copy(email = value, error = null) } }
    fun onPasswordChanged(value: String) { _ui.update { it.copy(password = value, error = null) } }
    fun toggleMode() { _ui.update { it.copy(mode = if (it.mode == Mode.SIGN_IN) Mode.SIGN_UP else Mode.SIGN_IN, error = null) } }

    fun submit() = viewModelScope.launch {
        _ui.update { it.copy(isLoading = true, error = null) }
        val s = _ui.value
        val result = when (s.mode) {
            Mode.SIGN_IN -> authRepository.signInWithEmail(s.email, s.password)
            Mode.SIGN_UP -> authRepository.signUpWithEmail(s.email, s.password)
        }
        _ui.update { it.copy(isLoading = false, error = result.exceptionOrNull()?.toAuthErrorCode()) }
    }

    fun onGoogleCredential(credential: AuthCredential) = viewModelScope.launch { /* … */ }
    fun onMicrosoftCredential(credential: AuthCredential) = viewModelScope.launch { /* … */ }

    fun forgotPassword() = viewModelScope.launch {
        val email = _ui.value.email
        if (email.isBlank()) { _ui.update { it.copy(error = AuthErrorCode.INVALID_EMAIL) }; return@launch }
        val result = authRepository.sendPasswordResetEmail(email)
        if (result.isSuccess) _ui.update { it.copy(passwordResetSent = true) }
        else _ui.update { it.copy(error = AuthErrorCode.PASSWORD_RESET_FAILED) }
    }
}
```

### Fragment

#### `SignInFragment.kt`
- Standard `@AndroidEntryPoint` + `by viewModels()` + `viewLifecycleOwner.repeatOnLifecycle(STARTED)` to collect `ui` flow
- Binds form fields → `onEmailChanged` / `onPasswordChanged`
- Submits on button click + on IME action `done` on password field
- Google sign-in launches via `ActivityResultContracts.StartIntentSenderForResult`; on success builds `GoogleAuthProvider.getCredential(idToken, null)` and passes to `viewModel.onGoogleCredential(...)`
- Microsoft launches via `FirebaseAuth.startActivityForSignInWithProvider(requireActivity(), OAuthProvider.newBuilder("microsoft.com").build())`; on success passes resulting credential to `viewModel.onMicrosoftCredential(...)`
- On TV form factor (detect via `resources.configuration.uiMode and UI_MODE_TYPE_MASK == UI_MODE_TYPE_TELEVISION`), hide the Microsoft button + show the unavailable-on-TV message
- Navigation: observed `AuthState.SignedIn` → `findNavController().navigate(R.id.action_signIn_to_mainShell)`

### Tests

#### `SignInViewModelTest.kt`
- Initial state: SignIn mode, empty fields, idle
- `onEmailChanged("a@b")` → UI updates, clears error
- `submit()` calls `signInWithEmail` with current email + password; sets loading=true, then false on result
- Sign-up mode: `submit()` calls `signUpWithEmail` instead
- `forgotPassword()` with blank email → emits `INVALID_EMAIL` error
- `forgotPassword()` with valid email + repo returns success → emits `passwordResetSent = true`
- `toggleMode()` swaps SIGN_IN ↔ SIGN_UP

Fragment-level UI tests are out of scope for unit (would need Robolectric or Espresso). Covered by T8 manual verification.

### Spec/quality review focus
- TV layout: are all interactive elements reachable via D-pad?
- AR layout: do form fields render with start alignment (RTL: text appears at the right edge)? Test in `values-ar/` preview.
- Strings: any missing translations? (TODO comments + grep for them in T9 doc update.)

### Commit
`[FEAT-ANDROID-AUTH-01-T4]: SignInFragment + SignInViewModel + layouts`

---

## T5 — Splash routing + nav graph + auth gate

### Existing entry point
`SplashFragment.kt` (already exists, currently routes to `OnboardingFragment` or `MainShellFragment` based on `hasOnboarded` pref).

### Updated routing logic
```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val hasOnboarded = preferences.hasOnboarded
    val isSignedIn = FirebaseAuth.getInstance().currentUser != null
    val destination = when {
        !hasOnboarded -> R.id.onboardingFragment
        !isSignedIn -> R.id.signInFragment
        else -> R.id.mainShellFragment
    }
    findNavController().navigate(destination, null, navOptions {
        popUpTo(R.id.splashFragment) { inclusive = true }
    })
}
```

### `res/navigation/app_nav_graph.xml`
- Add `<fragment android:id="@+id/signInFragment" …/>` as a top-level destination (sibling to `mainShellFragment`)
- Add action `action_signIn_to_mainShell` with `popUpTo="@id/signInFragment"` + `popUpToInclusive="true"`
- Add action `action_onboarding_to_signIn` with same pop-inclusive flag (onboarding → sign-in, not main)
- Update `OnboardingFragment.kt`'s "done" click handler to navigate via the new action

### Auth gate (optional, T5 if time permits, else T6)
Add a `NavController.OnDestinationChangedListener` in `MainActivity` that, if it observes navigation to anything inside `mainShellFragment` while `FirebaseAuth.currentUser == null`, redirects to `signInFragment`. This is belt-and-braces — interceptors already handle 403s, but defends against deep-link navigation that bypasses the splash route.

### Tests
- `SplashFragmentTest` (Robolectric): three routing branches verified
  - !hasOnboarded → onboarding
  - hasOnboarded + signed-out → sign-in
  - hasOnboarded + signed-in → main shell

### Spec/quality review focus
- Does the auth gate fire on every navigation, or only on top-level switches? (Top-level only — sub-destinations within main shell don't need re-checks; `FirebaseAuthInterceptor` handles in-flight 401/403.)

### Commit
`[FEAT-ANDROID-AUTH-01-T5]: Splash routing + nav graph for sign-in`

---

## T6 — Sign-out + 403 dialog wiring + status flow

### Me tab "Account" section

Add to `res/layout/fragment_me.xml` (and tablet/TV variants):
- New "Account" section header below existing rows
- Row: "Signed in as `${user.email}`" (read-only)
- Row: "Sign out" — button styled like other actionable rows

`MeFragment.kt` (or wherever the Me tab lives):
- `@Inject` `AuthRepository`
- Show signed-in-as text from `authRepository.authState.collectAsState()`
- "Sign out" click → confirm dialog → `viewLifecycleOwner.lifecycleScope.launch { authRepository.signOut() }` → navigate to `signInFragment`

### `MainActivity.kt` observes account-status events

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // existing setup …

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authRepository.accountStatusEvents.collect { event ->
                    showAccountStatusDialog(event)
                }
            }
        }
    }

    private fun showAccountStatusDialog(event: AccountStatusEvent) {
        val (titleRes, messageRes) = when (event) {
            AccountStatusEvent.Blocked -> R.string.account_blocked_title to R.string.account_blocked_body
            AccountStatusEvent.Deleted -> R.string.account_deleted_title to R.string.account_deleted_body
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok) { _, _ ->
                findNavController(R.id.mainNavHost).navigate(R.id.signInFragment, null, navOptions {
                    popUpTo(R.id.mainShellFragment) { inclusive = true }
                })
            }
            .setCancelable(false)
            .show()
    }
}
```

### Strings
```
account_blocked_title   "Account blocked"
account_blocked_body    "Your account has been blocked by an administrator. Contact support for details."
account_deleted_title   "Account deleted"
account_deleted_body    "Your account has been deleted. To use FitrahTube again, create a new account."
ok                      "OK"
```

### Tests
- `MeFragmentTest`: sign-out click → `authRepository.signOut()` called, navigates to sign-in
- `MainActivityAccountStatusTest` (Robolectric): emitting `Blocked` event → dialog shown with blocked strings; emitting `Deleted` → deleted strings

### Spec/quality review focus
- Confirm dialog → does it survive configuration change (rotation)? (Use `DialogFragment` or `MaterialAlertDialogBuilder` with state restoration.)
- After dialog dismissal, does the next request *also* get a 403? The repo's `signOut()` clears `currentUser` synchronously, so subsequent `FirebaseAuthInterceptor` calls bypass the token attach → backend returns 401, which is fine (we're already navigating to sign-in).

### Commit
`[FEAT-ANDROID-AUTH-01-T6]: Sign-out trigger + 403 dialog handling`

---

## T7 — Firebase Auth emulator integration tests

### Debug build override

`AlBunyaanApplication.kt`:
```kotlin
override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) {
        val authEmulatorHost = BuildConfig.AUTH_EMULATOR_HOST  // empty if disabled
        if (authEmulatorHost.isNotBlank()) {
            FirebaseAuth.getInstance().useEmulator(authEmulatorHost, BuildConfig.AUTH_EMULATOR_PORT)
        }
    }
}
```

`android/app/build.gradle.kts`:
```kotlin
defaultConfig {
    // existing …
    val authHost = localProperties.getProperty("auth.emulator.host", "")
    val authPort = localProperties.getProperty("auth.emulator.port", "9099").toInt()
    buildConfigField("String", "AUTH_EMULATOR_HOST", "\"$authHost\"")
    buildConfigField("int", "AUTH_EMULATOR_PORT", "$authPort")
}
```

Same override pattern as `api.base.url`. Real devices set `auth.emulator.host=192.168.x.x`; emulator sets `auth.emulator.host=10.0.2.2`. Empty = no emulator override (hits live Firebase).

### `androidTest` integration test

`android/app/src/androidTest/java/com/albunyaan/tube/auth/AuthRepositoryEmulatorTest.kt`:
- `@HiltAndroidTest`
- BeforeAll: `FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)`
- Test 1: `signUpWithEmail("test1@example.com", "password123")` → success, `currentUser != null`, `authState.first() is SignedIn`
- Test 2: `signInWithEmail` with wrong password → `Result.failure`, `WRONG_PASSWORD` error code
- Test 3: `signOut` → `currentUser == null`, `authState.first() is SignedOut`
- Test 4: full round-trip — sign up → sign out → sign in with same credentials → success
- AfterAll: clear emulator users via the emulator's REST endpoint

### End-to-end smoke test (manual, documented in T8)
1. Start backend with Firebase emulators (`./gradlew bootRun` with `GOOGLE_APPLICATION_CREDENTIALS` pointing at emulator config + `FIREBASE_AUTH_EMULATOR_HOST=localhost:9099`)
2. Start Android debug build pointed at backend `http://10.0.2.2:8080/` and auth emulator `10.0.2.2:9099`
3. Sign in with email/password (new account)
4. Open `adb logcat` and verify `OkHttp` log shows `Authorization: Bearer ey…` on `/api/v1/categories` request
5. Backend logs show `FirebaseAuthFilter` resolving the UID

Documented as a runbook in `docs/status/ANDROID_GUIDE.md` (T9).

### Spec/quality review focus
- Does the emulator override gate on `BuildConfig.DEBUG` AND on a non-empty host? (Yes — release builds must never hit an emulator.)
- Is the emulator host configurable per-developer? (Yes — `local.properties`, not committed.)

### Commit
`[TEST-ANDROID-AUTH-01-T7]: Firebase Auth emulator integration tests`

---

## T8 — Manual UI verification across 3 variants + RTL

**No commit-able artifact**, but a mandatory step before T9 docs.

### Devices
- Phone emulator (Pixel 7, API 35, ltr-en)
- Tablet emulator (Pixel Tablet, API 35, ltr-en)
- TV emulator (Android TV 1080p, API 35, ltr-en)
- Each device repeated with Arabic locale (rtl-ar)

### Checklist (per device × locale)
- [ ] Sign-in screen renders without scrolling needed to see the Sign-in button
- [ ] All form labels and buttons are localised (no English strings on AR build except known TODOs)
- [ ] Google button launches account picker
- [ ] Microsoft button launches Custom Tab (skipped on TV)
- [ ] On TV: D-pad walks email → password → forgot → sign-in → toggle → google in order
- [ ] On AR: form fields and buttons align to the right; password toggle button on the left
- [ ] After successful sign-in, navigates to main shell (Home tab)
- [ ] Sign-out from Me tab returns to sign-in screen
- [ ] Force-block the test account via backend admin endpoint → next admin request → 403 dialog with blocked-account message → OK → back at sign-in
- [ ] No regressions in onboarding, search, or categories tabs

### Output
Screenshots saved to `/tmp/plan-b-ui-verification/` for the reviewer to inspect. Findings go directly into a follow-up commit on this branch (not a separate PR).

---

## T9 — ANDROID_GUIDE.md update

`docs/status/ANDROID_GUIDE.md` gets a new section "Authentication" describing:
- Plan B's architecture in 1-2 paragraphs (link to spec)
- How to provision `google-services.json` (point at Firebase console + redirect URI for Microsoft)
- How to run the auth emulator locally
- Known limitations: Microsoft on TV, no anonymous sign-in (Plan D)

Plus a runbook section "Verifying auth end-to-end" containing the manual smoke-test steps from T7.

### Commit
`[DOCS-ANDROID-AUTH-01-T9]: Auth flow section in ANDROID_GUIDE`

---

## Closing checklist — before opening a PR

Same 7-stage pipeline as Plan A (per `feedback_review_pipeline.md`):

1. **Baseline:** unit + integration tests green on `feature/ANDROID-AUTH-01-firebase-signin`
2. **code-reviewer subagent** (background) — full diff vs `develop`
3. **cso skill** — security review (this one matters: we're touching auth)
4. **Codex challenge** (or Agent fallback) — adversarial second opinion
5. **Consolidate findings, patch, re-review**
6. **gstack /review**
7. **CodeRabbit** — accept Critical/Important, defer-with-reason for Minor

Then: PR to `develop`. **Not** `main`. Branching policy from memory.

---

## Estimated total

- LOC: ~1130 (slightly above the 1000 target — driven mostly by tests, which we don't compromise on)
- Plan length: ~600 lines (this file). Spec: 299 lines. Combined plan-document: ~900 lines, under the 1000-line cap requested.
- Calendar: 2-3 implementation sessions. Each task is small enough to land in one focused sitting; review pipeline adds ~½ session at the end.
