# Plan C — Account bootstrap / profile (design spec)

**Date drafted:** 2026-05-11
**Author:** ANDROID-AUTH-01 follow-on
**Depends on:** Plan A (backend account foundation, merged 2026-05-11) · Plan B (Android Firebase Auth, merged 2026-05-11)
**Followed by:** Plan D (sync engine + anonymous→account merge) · Plan E (PENDING_PROFILE enforcement on moderator submissions)

> **Policy decision (2026-05-11):** FitrahTube is **13 and older only**. We do not collect or store any data for under-13 users. Plan C blocks them at bootstrap, deletes their Firebase Auth record, and never persists their DOB. This removes COPPA scope from the product entirely — there is no parental-consent flow, no Plan F email delivery, no kid-specific state machine.

---

## 1. Goal

A newly-signed-in user has a Firebase Auth record (from Plan B) and a lazily-created `users` Firestore doc (from Plan A) at status `PENDING_PROFILE`. Plan C adds the bootstrap step that collects `displayName` + `dateOfBirth`, transitions the user to `ACTIVE`, and routes them to MainShell. Under-13 users are hard-rejected: backend deletes the Firestore doc + revokes refresh tokens, Android deletes the Firebase Auth user, the user lands back at sign-in.

This plan does **only** the bootstrap form, the backend endpoint that accepts it, and the routing wiring. It does not enforce profile completion on moderator-submission endpoints (Plan E).

## 2. Depends-on / followed-by

- Plan A already added the `displayName` and `profileCompletedAt` fields on `User`, the `PENDING_PROFILE` enum value, the `users` Firestore collection, and the lazy-create-on-first-/api/v1/* code in `FirebaseAuthFilter`. This spec extends that surface; it does not duplicate it.
- Plan B already added `SignInFragment`, `SplashFragment` with `SplashRouter` (D2 routing decisions), and the `AuthRepository.authState` flow. Plan C inserts a new fragment (`ProfileBootstrapFragment`) and a one-shot `AgeIneligibleFragment` into the routing graph; the existing `SplashRouter.decideSplashRoute()` is extended with an `accountStatus` parameter.
- Plan E will reuse this plan's `PENDING_PROFILE` → `ACTIVE` transition to gate moderator-submission endpoints. Plan D will reuse the `displayName` field for sync-engine attribution.

## 3. Non-goals

- **Parental-consent / COPPA flow.** Out of product scope as of 2026-05-11. Under-13 users are not supported; their attempt to bootstrap is rejected and their auth record is deleted.
- **Profile editing after bootstrap.** Plan C is *first-write*. A "Profile" screen with an Edit button is future work, not part of this plan.
- **Display-name uniqueness or moderation.** Display names are free-form; admin frontend can surface them later if abused.
- **Photo / avatar upload.** Future work. Bootstrap uses a generated initials avatar.
- **`PENDING_PROFILE` enforcement on submission endpoints.** Plan E gates moderator submissions; Plan C only transitions status.
- **Firestore rules tightening for full `/users/{uid}` self-read.** Plan A keeps admin-or-self read; this plan only widens self-write to the bootstrap sub-fields.
- **Anonymous→account merge.** Plan D. Plan C assumes a clean sign-in with no prior anonymous state.

## 4. User flow

```
                    First sign-in (Plan B)
                            ↓
                  Firebase Auth user created
                            ↓
                  SplashFragment starts animation
                            ↓
            In parallel: GET /api/account/me
            (FirebaseAuthFilter lazy-creates users
             doc on first hit — Plan A behaviour)
                            ↓
              authState becomes SignedIn(uid)
                            ↓
       SplashRouter.decideSplashRoute(
         onboardingCompleted, signedIn=true, status)
                            ↓
        ┌──────────────────┬─┴───────────────────┐
        ↓                  ↓                     ↓
   status=ACTIVE   status=PENDING_PROFILE   /me fetch failed
        ↓                  ↓                     ↓
    MainShell    ProfileBootstrapFragment    retry × 3
                           ↓                     ↓
                  User fills form, submits   signOut + toast
                           ↓                     ↓
                  POST /api/account/profile  → SignIn
                           ↓
              ┌────────────┴────────────┐
              ↓                         ↓
          age >= 13                age < 13
              ↓                         ↓
       status=ACTIVE              422 AGE_INELIGIBLE
       profileCompletedAt=now     backend: delete users doc
              ↓                          + revoke refresh tokens
       Nav to MainShell                  ↓
                                  AgeIneligibleFragment
                                         ↓
                                  User taps OK
                                         ↓
                                  FirebaseAuth.delete() (client)
                                         ↓
                                  Nav to SignIn
```

## 5. Architecture

### 5.1 Data model changes (Firestore `users` collection)

```java
// Already in User (from Plan A):
private String uid;                       // @DocumentId
private String email;
private String displayName;               // populated by Plan C bootstrap
private String role;                      // "user" | "moderator" | "admin"
private String status;                    // "active" | "pending_profile" | "blocked" | "deleted"
private Timestamp createdAt;
private Timestamp updatedAt;
private Timestamp profileCompletedAt;     // populated by Plan C bootstrap

// New in Plan C:
private Timestamp dateOfBirth;            // wire stored as a Firestore Timestamp; backend computes age server-side
```

No new `UserStatus` enum values. The existing `PENDING_PROFILE` and `ACTIVE` are the only two relevant to bootstrap. Under-13 rejection is **terminal** — the doc is deleted, not held in a status.

### 5.2 Backend endpoints

| Method | Path | Auth | Body | Effect |
|--------|------|------|------|--------|
| `POST` | `/api/account/profile` | Firebase ID token | `{displayName, dateOfBirth}` | Self-write profile. Inside a transaction: validate body, compute age. If age ≥ 13 → set `displayName`+`dateOfBirth`+`profileCompletedAt=now`+`updatedAt=now`+`status="active"`. Returns the updated `User` (admin fields stripped). If age < 13 → delete the user doc, call `FirebaseAuth.revokeRefreshTokens(uid)`, return **422** with `{code: "AGE_INELIGIBLE"}`. |
| `GET`  | `/api/account/me` | Firebase ID token | — | Returns the caller's user doc (admin fields stripped). Used by SplashRouter to decide routing. |

Both endpoints live in a new `AccountController` under `/api/account/*`. `FirebaseAuthFilter.shouldNotFilter` is **widened** to drop the `/api/account/*` exemption (filter runs there → `request.auth.token.uid` is available; status check still skips because `PENDING_PROFILE` is `allowsAuth=true`). This is the same `shouldNotFilter` widening Plan A's D-list said Plans B/C/D would each do incrementally; this is Plan C's slice.

**Idempotency** (D7): `POST /api/account/profile` rejects with 409 if `profileCompletedAt is not null` (re-bootstrap is disallowed). Editing displayName/DOB later is a future-work endpoint.

**Refresh-token revocation on age-rejection**: belt-and-suspenders. The client deletes the Firebase Auth user itself (see §5.4), but if that call fails for network reasons, the revoked refresh token guarantees the orphaned auth user cannot be used to sign back in with a stale ID token. The client's auth state will be invalidated within ~1h regardless.

### 5.3 Firestore rules

Self-write of profile sub-fields only. The existing `/users/{userId}` rule (admin-only write) widens:

```
match /users/{userId} {
  allow read: if isAuthenticated() && (request.auth.uid == userId || isAdmin());
  allow write: if isAdmin();
  // NEW — self-write only specific profile sub-fields, only when user is in PENDING_PROFILE.
  // Block on any field outside the allowlist.
  allow update: if isAuthenticated() && request.auth.uid == userId &&
                   resource.data.status == 'pending_profile' &&
                   request.resource.data.diff(resource.data)
                     .affectedKeys()
                     .hasOnly(['displayName', 'dateOfBirth', 'updatedAt',
                              'profileCompletedAt', 'status']);
}
```

Defense-in-depth: backend still validates everything; rules block a direct-from-client write that bypasses the server. (The Android client doesn't write Firestore directly today — backend mediates — but rules guarantee that even if a future client adds a direct write, the blast radius is the bootstrap sub-fields only.)

### 5.4 Android changes

**SplashRouter extension** (existing file `SplashRouter.kt` from Plan B T5):

```kotlin
// Existing signature:
//   decideSplashRoute(onboardingCompleted: Boolean, signedIn: Boolean): NavAction
// New signature:
//   decideSplashRoute(
//     onboardingCompleted: Boolean,
//     signedIn: Boolean,
//     accountStatus: AccountStatus?,  // null = /me fetch not yet complete or failed
//   ): NavAction
```

Branches:
| signedIn | accountStatus | route |
|----------|---------------|-------|
| false | (any) | action_splash_to_signIn (existing) |
| true | null (fetch failed after retries) | action_splash_to_signIn + toast (new) |
| true | ACTIVE | action_splash_to_main (existing) |
| true | PENDING_PROFILE | action_splash_to_bootstrap (new) |
| true | BLOCKED | action_splash_to_signIn + suspended toast (Plan B already wired via AccountStatusInterceptor; SplashRouter forwards) |
| true | DELETED | action_splash_to_signIn + signed-out toast (same as above) |

`SplashFragment` adds a parallel `async` for `accountRepository.fetchMe()` alongside the existing onboarding-preference fetch. The 2.7s splash animation hides the network latency. Three retries with 1s backoff before surfacing null to the router.

**New `AccountRepository`** (Hilt singleton, mirrors `AuthRepository` shape):

```kotlin
interface AccountRepository {
    val accountState: StateFlow<AccountState>  // Loading | Loaded(User) | Failed | NotSignedIn
    suspend fun fetchMe(): Result<User>
    suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate): Result<User>
}
```

`AccountState` is a sealed class; `Loaded(User)` carries the cached user doc for the session. No disk persistence — refresh on cold start + on resume-from-stop > 5 min.

**New `ProfileBootstrapFragment`** (route id `R.id.profileBootstrapFragment`).
- Layout variants: `layout/` (phone, full-bleed), `layout-sw600dp/` (tablet, 560dp form column centred), `layout-sw720dp/` (TV, 560dp form column centred, larger type).
- Fields:
  - `displayName` — `TextInputLayout` + `TextInputEditText`, pre-filled from `FirebaseAuth.getInstance().currentUser?.displayName ?: ""` (D9), editable, 1–40 chars (D5).
  - `dateOfBirth` — `TextInputLayout` (read-only display) opens `MaterialDatePicker.Builder.datePicker()` on tap. `setMaxDate(today − 13y)` is **not** set (D10): we let the user pick any past date so age-ineligibility is enforced server-side, surfaced via `AgeIneligibleFragment`. Reasoning: a max-date constraint silently truncates the user's choice, making the rejection invisible. Letting them pick and then explaining is honest UX.
  - Submit button (filled, primary-green) — disabled until both fields valid.
- ViewModel: `ProfileBootstrapViewModel` mirrors `SignInViewModel` patterns (isLoading guard, error mapping, single `_ui: StateFlow<UiState>`).

**New `AgeIneligibleFragment`** (route id `R.id.ageIneligibleFragment`).
- Layout: centred message + single OK button. No form fields.
- Copy: "FitrahTube is for users 13 and older. We're sorry — please come back when you're a bit older."
- On OK tap:
  1. `FirebaseAuth.getInstance().currentUser?.delete()` (suspend, mapped to Result)
  2. `authRepository.signOut()` (clears any local cache)
  3. Nav `action_ageIneligible_to_signIn` with `popUpTo(splashFragment) { inclusive = true }`
- If `delete()` fails (network), still proceed with `signOut()` + nav. Backend already revoked the refresh token in §5.2, so the orphan Firebase Auth user cannot mint a new ID token after ~1h. Acceptable.

**Nav graph additions** (`res/navigation/app_nav_graph.xml`):
- `<fragment id="profileBootstrapFragment"/>` + `<fragment id="ageIneligibleFragment"/>`
- `action_splash_to_bootstrap`, `action_splash_to_ageIneligible`, `action_bootstrap_to_main`, `action_bootstrap_to_ageIneligible`, `action_ageIneligible_to_signIn` (with `popUpTo splashFragment inclusive=true`)
- `action_signIn_to_main` (existing): is **retired**. Plan B's `SignInFragment` post-sign-in observer changes to navigate back to `splashFragment` instead, which then re-evaluates routing. Single decision point, no duplicated logic.

**Back-nav semantics** (D11):
- From `ProfileBootstrapFragment` → system back: cancels sign-in. Calls `authRepository.signOut()` + nav to `signInFragment` with `popUpTo(splashFragment) inclusive=true`. The form's state is dropped (no draft persistence).
- From `AgeIneligibleFragment` → system back: identical to OK button (so the user can't bounce back to bootstrap with a different DOB).

### 5.5 Strings

New entries in `res/values/strings_onboarding.xml` (and AR/NL stubs in `strings_onboarding.xml` under `values-ar/` and `values-nl/`):

- `bootstrap_title`               "Tell us about you"
- `bootstrap_display_name_label`  "What should we call you?"
- `bootstrap_display_name_hint`   "Display name"
- `bootstrap_dob_label`           "Date of birth"
- `bootstrap_dob_hint`            "Tap to select"
- `bootstrap_submit_button`       "Continue"
- `bootstrap_error_invalid_name`  "Please tell us your name (1–40 characters)"
- `bootstrap_error_invalid_dob`   "Please choose your date of birth"
- `bootstrap_error_save_failed`   "Couldn't save your profile — try again"
- `age_ineligible_title`          "Sorry — come back soon"
- `age_ineligible_body`           "FitrahTube is for users 13 and older. Please come back when you're a bit older."
- `age_ineligible_ok_button`      "OK"

AR/NL placeholder English values until human-translated, per project i18n policy (`docs/design/i18n-strategy.md`). No machine translation.

## 6. Locked design decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Bootstrap collects `displayName` (string) + `dateOfBirth` (date, not bare age). | DOB is server-derivable; age changes over time. Storing DOB is correct; storing age would require a per-year backfill or annual recompute. |
| D2 | Age threshold = **13**. Server-computed from DOB at submission time. | US COPPA boundary; the universal cutoff used by major social apps. |
| D3 | Under-13 result is **hard rejection + Firebase Auth.delete()** (option A from 2026-05-11 product call). No PII retained for minors. | Cleanest privacy posture. Matches Twitter/Reddit/Instagram pattern. We can't enforce "real DOB" anyway; if a kid lies, no flow catches them — but a kid who answers honestly is treated honestly. |
| D4 | Backend deletes the `users` Firestore doc on AGE_INELIGIBLE + revokes refresh tokens. Client deletes the Firebase Auth user. Belt-and-suspenders for orphan-auth-user defence. | Privacy posture: leave **nothing** behind for an under-13. Server-side revocation is the floor; client-side delete is the ceiling. If the client's delete() fails, the revoked refresh token still locks them out within ~1h. |
| D5 | `displayName` validation: 1..40 chars, trimmed. No uniqueness check. | Matches `User.displayName` field length elsewhere; keeps server validation simple. |
| D6 | `dateOfBirth` stored as Firestore Timestamp (date-only semantics; backend treats time component as 00:00 UTC). | Consistent with `profileCompletedAt: Timestamp` style from Plan A. Avoids introducing a new wire-type. |
| D7 | `POST /api/account/profile` is **idempotent on first call**: rejects 409 if `profileCompletedAt is not null`. Re-bootstrap edits are out of scope. | Prevents accidental double-submit changing DOB. Editing is a future-work concern. |
| D8 | `FirebaseAuthFilter.shouldNotFilter` widens to drop the `/api/account/*` exemption. Other `/api/v1/*` routes remain exempt (Plan D widens those). | Per Plan A's D-list: each plan widens what it needs. Account endpoints obviously need auth. |
| D9 | `ProfileBootstrapFragment` pre-fills `displayName` from `FirebaseAuth.currentUser.displayName` (set by Google/Microsoft sign-in). Editable. Email sign-up users see an empty field. | Saves a tap for OAuth users; doesn't lock them in. |
| D10 | `MaterialDatePicker` does **not** set `setMaxDate(today − 13y)`. User can pick any past date; under-13 is rejected server-side and routed to `AgeIneligibleFragment`. | Silent truncation is dishonest UX. Server-side rejection + an explicit screen is the honest interaction. Also keeps the gate in one place (backend), not two (client picker + server). |
| D11 | Back-nav from `ProfileBootstrapFragment` cancels sign-in (calls `signOut()` + routes to `signInFragment`). Back-nav from `AgeIneligibleFragment` does the same as the OK button. | Don't trap an authenticated-but-not-bootstrapped user. Don't let an ineligible user retry with a different DOB by hitting back. |
| D12 | `GET /api/account/me` is fetched in parallel with the existing onboarding-preference fetch during the 2.7s splash animation. **3 attempts total** with **1s linear backoff between attempts** (worst case ~3s, comfortably inside the 2.7s splash + the existing `POST_ANIMATION_DELAY=800ms` hold). On terminal failure → sign-out + nav to `signInFragment` with "Couldn't connect" toast. | Zero added latency on the happy path. Resilient to flaky networks. Never silently routes to MainShell with stale state (Plan D's sync engine assumes a known-fresh status). |
| D13 | No client-side persistence of `User` state. `AccountRepository.accountState` is an in-memory `StateFlow` scoped to the application process; refresh on cold start (process restart) + on resume-from-stop > 5 min. | Conflicts with Plan D's sync semantics if we persisted; cheaper to refetch than to invalidate. |
| D14 | `SignInFragment`'s post-sign-in observer no longer navigates directly to `mainShellFragment`. It navigates to `splashFragment` instead (with `popUpTo(signInFragment) inclusive=true`), and `SplashRouter` decides where to actually go based on `accountStatus`. | Single decision point. Avoids duplicating the PENDING_PROFILE / ACTIVE / AGE_INELIGIBLE routing logic in two places. |

## 7. Test surface

Unit tests:
- `SplashRouterTest` — extend existing tests with new branches: PENDING_PROFILE→bootstrap, AGE_INELIGIBLE never reached (terminal at server), null-status→signIn-with-toast.
- `ProfileBootstrapViewModelTest` — happy path, displayName validation (blank, >40 chars), DOB validation (unset), submit isLoading guard, 422 AGE_INELIGIBLE → emits `NavigateToAgeIneligible` event, generic 500 → INVALID_RESPONSE error mapping.
- `AgeIneligibleViewModelTest` — happy path (delete success → signOut → nav), delete-fails → still signOut + nav (refresh-token revocation is the floor).
- `AccountRepositoryTest` — mock Retrofit; assert state transitions on success/failure/network-error; retry-with-backoff logic.

Integration tests (backend, against Firestore + Firebase Auth emulator):
- `AccountControllerIT` — happy path adult, age-ineligible flow (asserts doc deletion + revokeRefreshTokens called), 409 on second submit after status=ACTIVE, 401 missing token, 422 missing/malformed body fields.

Multi-stage review pipeline (same 7 stages as Plan B): code-reviewer → cso → adversarial → consolidate → patch + re-review → gstack /review → CodeRabbit. PR to `develop`, not `main` (per branching policy).

## 8. Resolved questions

All open questions from the previous draft of this spec were resolved during 2026-05-11 product/legal call. Recorded as D9–D14 above for traceability.

## 9. Migration / rollout

- No data migration needed: the new `dateOfBirth` field is null on all existing user docs (all current users were created by Plan A with status=PENDING_PROFILE and have no DOB). The Plan E `PENDING_PROFILE` gate has not shipped yet, so no existing user is currently locked out of any feature.
- After Plan C ships, all currently-PENDING_PROFILE users (none in production today, but seed users + dev accounts) will hit the bootstrap form on next cold start. The bootstrap form is the only blocker; once they submit they're ACTIVE.
- Feature flag: **no flag**. Bootstrap is the only acceptable post-sign-in path for new users; flagging it off would mean shipping a broken sign-up flow.
