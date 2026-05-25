# Onboarding — Phone Collection, Email Verification, Personal Info Edit

**Date**: 2026-05-25
**Status**: Design — pending approval
**Scope**: Android client + backend (`com.albunyaan.tube` / `albunyaantube/`)

## Goal

Extend first-time signup and the Personal Info screen so that:

1. **Phone number** is a mandatory field at signup. Format-validated client-side via libphonenumber (E.164); not OTP-verified. Trust users.
2. **Email** is verified at signup for the email/password provider only (Google/Microsoft skip — pre-verified by IdP). Hard-gate: user cannot proceed past sign-in until they click the verification link.
3. **Personal Info** (`ProfileFragment`) gains editable email, password, and phone rows. Email change uses `verifyBeforeUpdateEmail` (no in-app re-gate; old email stays valid until click).

## Non-goals

- No phone OTP / SMS / WhatsApp verification. Phone is trust-based per user direction.
- No new backend account status (`PENDING_EMAIL_VERIFICATION`). Email-verified gating is client-side via `FirebaseUser.isEmailVerified`.
- No data migration. Firestore is schemaless; existing users get `phoneNumber: null` lazily and the profile-edit UI surfaces an "Add" affordance until populated.
- No admin / web frontend changes — Personal Info edit is Android-only.

## Architecture

```
NEW            EXTENDED                       UNCHANGED
─────────────  ─────────────────────────────  ──────────────
EmailVerify    ProfileBootstrap (+ phone)     SignIn (no email field
Fragment       Profile (+ email/pw/phone edit) added — email already
                Backend DTOs (+ phoneNumber)   collected there;
                User model (+ phoneNumber)     isEmailShape() retained)
```

### Post-sign-in routing (new fork)

Replaces the unconditional `action_signIn_to_splash` in `SignInFragment.onViewCreated`.

```
SignedIn arrives
   ├─ providerData includes EmailAuthProvider AND !currentUser.isEmailVerified
   │    ├─ mode == SIGN_UP  → sendEmailVerification() once → EmailVerificationFragment
   │    └─ mode == SIGN_IN  → EmailVerificationFragment (no re-send)
   └─ otherwise (Google/Microsoft, or password+verified)
        → splash → /api/account/me → bootstrap | main | signIn (current behaviour)
```

Single decision point at the same spot the existing `hasNavigatedFromSignIn` guard lives — no MainActivity / SplashRouter changes.

## Section 2 — Bootstrap form (signup)

### Layout change (`fragment_profile_bootstrap.xml` × 3 variants)

Insert phone country picker + phone field between DOB and the conditional password section. View IDs identical across `layout/`, `layout-sw600dp/`, `layout-sw720dp/`.

```
displayName           (existing)
DOB                   (existing)
─────────────
phoneCountryLayout    (NEW — Material exposed dropdown, AutoCompleteTextView)
phoneLayout           (NEW — TextInputLayout, inputType="phone")
─────────────
[password section]    (unchanged; conditional on EmailAuthProvider absence)
submitButton
```

### `ProfileBootstrapViewModel.UiState`

```kotlin
data class UiState(
    val displayName: String = "",
    val dateOfBirth: LocalDate? = null,
    val phoneCountry: String? = null,           // NEW — ISO-3166-1 alpha-2 (e.g. "NL")
    val phoneNumber: String = "",                // NEW — national-portion as typed
    val password: String = "",
    val passwordConfirm: String = "",
    val passwordRequired: Boolean = false,
    val profileSaved: Boolean = false,
    val isLoading: Boolean = false,
    val error: BootstrapError? = null,
) {
    fun firstValidationError(): BootstrapError? {
        val name = displayName.trim()
        if (name.isBlank() || name.length > 40) return BootstrapError.INVALID_NAME
        if (dateOfBirth == null)                  return BootstrapError.INVALID_DOB
        if (phoneCountry.isNullOrBlank())         return BootstrapError.INVALID_PHONE_COUNTRY
        val e164 = formatE164(phoneCountry, phoneNumber)
        if (e164 == null)                         return BootstrapError.INVALID_PHONE
        if (passwordRequired) {
            if (password.length < MIN_PASSWORD_LENGTH) return BootstrapError.INVALID_PASSWORD
            if (password != passwordConfirm)           return BootstrapError.PASSWORD_MISMATCH
        }
        return null
    }
    val isFormValid: Boolean get() = firstValidationError() == null
}
```

`formatE164(region, national)` wraps `PhoneNumberUtil.parse(national, region) → format(E164)`, returning null on `NumberParseException` or `isValidNumberForRegion(false)`.

### `BootstrapError` additions

```kotlin
enum class BootstrapError {
    INVALID_NAME, INVALID_DOB,
    INVALID_PHONE_COUNTRY, INVALID_PHONE,   // NEW
    INVALID_PASSWORD, PASSWORD_MISMATCH,
    PASSWORD_SET_FAILED, SAVE_FAILED,
}
```

### `AccountRepository.completeProfile` signature

```kotlin
suspend fun completeProfile(
    displayName: String,
    dob: LocalDate,
    phoneNumber: String,   // NEW — E.164 string, e.g. "+31612345678"
): Result<Unit>
```

The corresponding `CompleteProfileRequestDto` and backend `CompleteProfileRequest` both gain `phoneNumber` (see Section 5).

### Country picker data

Use `PhoneNumberUtil.getSupportedRegions()` for the canonical region list; display name from `Locale("", regionCode).getDisplayCountry(currentLocale)` (i18n for en/ar/nl). Sort alphabetically by display name. Default selection: `TelephonyManager.networkCountryIso?.uppercase()` if available, else `Locale.getDefault().country`.

## Section 3 — Email verification gate

### New fragment: `EmailVerificationFragment`

Files:
- `android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationFragment.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationViewModel.kt`
- `android/app/src/main/res/layout/fragment_email_verification.xml` (× 3 variants)

UI:

```
┌─────────────────────────────────────────┐
│ ← (back = sign out)                     │
│  [envelope icon]                        │
│  Verify your email                      │
│                                         │
│  We sent a verification link to         │
│  you@example.com. Tap it, then come     │
│  back here.                             │
│                                         │
│  [ I've verified my email ]             │
│  [ Resend email ]                       │
│  [ Use a different email ]              │
│                                         │
│  Last sent 12 seconds ago               │
└─────────────────────────────────────────┘
```

ViewModel state:

```kotlin
data class UiState(
    val email: String = "",
    val isChecking: Boolean = false,         // "I've verified" in flight
    val isResending: Boolean = false,
    val lastSentAtMs: Long? = null,          // for "X seconds ago" + 60s resend cooldown
    val error: EmailVerifyError? = null,     // RATE_LIMITED, NETWORK, NOT_YET_VERIFIED, UNKNOWN
)

sealed interface Nav {
    data object Idle : Nav
    data object NavigateToSplash : Nav
    data object NavigateToSignIn : Nav
}
```

Behaviour:

- **Enter**: read `firebaseAuth.currentUser?.email`. If `lastSentAtMs` is null in `SavedStateHandle`, call `sendEmailVerification()` once. (Sign-up path triggers this; sign-in path also triggers because the user landed here means we want them to know we re-sent.) Store `lastSentAtMs = System.currentTimeMillis()`.
- **"I've verified my email"**: `isChecking = true`; `currentUser.reload().await()` (network call); if `isEmailVerified` → `Nav.NavigateToSplash`; else `error = NOT_YET_VERIFIED`. `isChecking = false`.
- **"Resend email"**: 60s client-side cooldown vs `lastSentAtMs`. Disabled button + tooltip until cooldown elapses. On click: `sendEmailVerification()` → update `lastSentAtMs`. Firebase server-side rate-limits ~5 per 15 min; map `tooManyRequests` to `RATE_LIMITED`.
- **"Use a different email"**: `authRepository.signOut()` → `Nav.NavigateToSignIn`.
- **Back-press**: hardware/system back routes to "Use a different email" via `OnBackPressedCallback` — same trap-prevention pattern as `ProfileBootstrapFragment`.

Trigger in `SignInFragment` (modify the existing `authState.filterIsInstance<SignedIn>().first()` block):

```kotlin
val user = firebaseAuth.currentUser!!
val isPasswordProvider = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
val nextAction = when {
    !isPasswordProvider -> R.id.action_signIn_to_splash
    user.isEmailVerified -> R.id.action_signIn_to_splash
    else -> R.id.action_signIn_to_emailVerification
}
if (!hasNavigatedFromSignIn) {
    hasNavigatedFromSignIn = true
    findNavController().navigate(nextAction)
}
```

Nav graph: add `emailVerificationFragment` destination with `action_signIn_to_emailVerification`, `action_emailVerification_to_splash`, `action_emailVerification_to_signIn`.

## Section 4 — Profile (Personal Info) edit flows

### Layout change (`fragment_profile.xml`)

Card already contains displayName + DOB + email-locked. Add edit buttons to the email row, add phone row, add password row. Keep the unified "Save" button for displayName + DOB (existing draft pattern); the three new edit flows operate via bottom sheets and don't touch the draft.

```
Display name   [TextInput]                ← existing, draft-tracked
─────────
Date of birth  2000-01-01    ›            ← existing, draft-tracked
─────────
Email          you@example.com    Edit    ← Edit reveals BottomSheet
─────────
Phone          +31 6 12345678    Edit    ← Edit reveals BottomSheet
─────────
Password       ••••••••••       Edit    ← Edit reveals BottomSheet
─────────
        [ Save ]                          ← only enables for name/DOB diff
```

### `ProfileFields` additions (`ProfileUiState.kt`)

```kotlin
data class ProfileFields(
    val displayName: String,
    val dateOfBirth: String?,
    val emailReadOnly: String,
    val phoneNumber: String?,           // NEW — E.164 or null
    val hasPasswordProvider: Boolean,   // NEW — drives "Edit password" row visibility
)
```

`hasPasswordProvider` is computed from `firebaseAuth.currentUser?.providerData`. If the user signed in only via Google/Microsoft, the password row shows a different affordance: "Set a password" (uses `EmailAuthProvider.getCredential` + `linkWithCredential`). For first slice, hide the row entirely if `!hasPasswordProvider` — Bootstrap already handles Google-first password setup.

### Bottom sheet — Edit Email (`EditEmailBottomSheetFragment`)

```
Edit email
─────────────────────────────
Current password   [______]
New email          [______]
        [ Cancel ]   [ Send verification ]
```

Submit flow:

1. Validate `isEmailShape(newEmail)` client-side (reuse `SignInViewModel.isEmailShape` — extract to a shared util `EmailShape.kt`).
2. `EmailAuthProvider.getCredential(currentUser.email!!, currentPassword)` → `currentUser.reauthenticate(...)` → `currentUser.verifyBeforeUpdateEmail(newEmail)`.
3. Map errors:
   - `wrong-password` → inline error on current-password field
   - `requires-recent-login` → shouldn't happen post-reauth, surface as Snackbar
   - `invalid-email`/`email-already-in-use` → inline error on new-email field
   - Network → Snackbar in sheet
4. On success: dismiss sheet, Snackbar "Verification link sent to *new@example.com*. Tap it to complete the change. Your current email stays active until then." → return to profile.
5. The profile row continues to show the *old* email until the user clicks the link and Firebase swaps it. Next `/api/account/me` call after the swap brings the new value (no client polling needed).

### Bottom sheet — Edit Password (`EditPasswordBottomSheetFragment`)

```
Edit password
─────────────────────────────
Current password   [______]
New password       [______]
Confirm new        [______]
        [ Cancel ]   [ Update password ]
```

Submit flow:

1. Validate new password length ≥ 8 (matches `ProfileBootstrapViewModel.MIN_PASSWORD_LENGTH`) and `new == confirm`.
2. Re-auth (same as Edit Email) → `currentUser.updatePassword(new)`.
3. Errors: `wrong-password` → current-password field; `weak-password` → new-password field; network → Snackbar.
4. Success → dismiss + Snackbar "Password updated."

### Bottom sheet — Edit Phone (`EditPhoneBottomSheetFragment`)

```
Edit phone number
─────────────────────────────
Country  [Netherlands ▾]
Number   [_____________]
        [ Cancel ]   [ Save ]
```

Pre-fill: parse the current `phoneNumber` via `PhoneNumberUtil.parse(current, null)` → country = `getRegionCodeForNumber(parsed)`, national = `getNationalSignificantNumber(parsed)`. If null/unparseable, default to device country / empty.

Submit flow:

1. Validate via `formatE164(country, national)` — same helper as bootstrap.
2. `accountUpdateRepository.updateProfile(UpdateProfileRequestDto(phoneNumber = e164))` → PUT `/api/account/profile`.
3. Errors: backend 400 with `phoneNumber: ...` → inline error on number field (requires extending `splitFieldMessage` to recognize `phoneNumber`); backend 429 → Snackbar with retry-after; network → Snackbar.
4. Success → dismiss + Snackbar "Phone number updated." Profile row refreshes from the returned `AccountMeResponseDto`.

### Shared utilities

- `EmailShape.kt` — extract `isEmailShape` from `SignInViewModel` (cross-screen reuse).
- `PhoneFormat.kt` — wrap libphonenumber: `formatE164(region, national)`, `parseDisplay(e164) -> (region, national)`.

## Section 5 — Backend changes

### `User.java` (model)

Add `private String phoneNumber;` plus getter/setter and include in the copy constructor / `recordSoftDelete` paths (just a passthrough, no special handling).

### DTOs

```java
// CompleteProfileRequest.java — phoneNumber REQUIRED at first signup
@NotBlank
@Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164 format")
private String phoneNumber;

// UpdateProfileRequest.java — phoneNumber NULLABLE (null = no change)
public record UpdateProfileRequest(
    @Size(min = 1, max = 40) String displayName,
    LocalDate dateOfBirth,
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber   // NEW
) {}

// AccountMeResponse.java — add phoneNumber field, populate via .from(user)
```

### `AccountProfileService.java`

```java
private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

void validatePhoneNumber(String s) {
    if (s == null || s.isBlank()) {
        throw new ProfileValidationException("phoneNumber", "must not be blank");
    }
    if (!E164.matcher(s).matches()) {
        throw new ProfileValidationException("phoneNumber",
                "must be E.164 format (e.g. +31612345678)");
    }
}

// completeProfile — call validatePhoneNumber, set on user, persist
// updateProfile — null-skip merge, include phoneNumber in updates map,
//                 add to isNoOpUpdate comparison and changedFields() diff
// profileMatches — extend idempotent-retry check to include phoneNumber
```

Audit log diff (`changedFields`) adds `phoneNumber: "changed"` sentinel. PII safety preserved.

### Android DTO mirrors

```kotlin
// CompleteProfileRequestDto.kt
@JsonClass(generateAdapter = true)
data class CompleteProfileRequestDto(
    val displayName: String,
    val dateOfBirth: String,
    val phoneNumber: String,   // NEW
)

// dto/UpdateProfileRequestDto.kt
@JsonClass(generateAdapter = true)
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    val dateOfBirth: String? = null,
    val phoneNumber: String? = null,   // NEW
)

// AccountMeResponseDto.kt
@JsonClass(generateAdapter = true)
data class AccountMeResponseDto(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val dateOfBirth: String?,
    val phoneNumber: String?,   // NEW
    val status: String,
    val role: String?,
    val profileCompletedAt: String?,
)
```

### `AccountUpdateRepository.splitFieldMessage`

Add `"phoneNumber"` to the field allowlist alongside `"displayName"` and `"dateOfBirth"` so backend validation errors route to the phone sheet rather than defaulting to displayName.

### `AccountState` / `AccountRepositoryImpl`

`AccountState.Loaded` gains `phoneNumber: String?` so `ProfileViewModel.loadFromAccount()` can seed the field. The state is hydrated from `/api/account/me` which now includes the field.

## Section 6 — Dependencies

```
// android/app/build.gradle.kts
implementation("io.michaelrocks:libphonenumber-android:8.13.35")
// ~150KB compressed AAR. Provides PhoneNumberUtil + AsYouTypeFormatter.
```

No backend dependencies added (using a regex, not libphonenumber-java).

## Section 7 — Strings (en / ar / nl)

```
bootstrap_phone_country_label, bootstrap_phone_label, bootstrap_phone_hint
bootstrap_error_invalid_phone_country, bootstrap_error_invalid_phone

profile_phone, profile_password, profile_edit, profile_email_change_sent,
profile_password_updated, profile_phone_updated

email_verification_title, email_verification_body (with %1$s for email),
email_verification_resend, email_verification_check_now,
email_verification_use_different, email_verification_not_yet,
email_verification_rate_limited, email_verification_last_sent
(with %1$d for seconds)

edit_email_title, edit_email_current_password, edit_email_new_email,
edit_email_send
edit_password_title, edit_password_current, edit_password_new,
edit_password_confirm, edit_password_update
edit_phone_title, edit_phone_country, edit_phone_number, edit_phone_save
```

## Section 8 — Risks & open questions

1. **libphonenumber metadata size**: 150KB compressed. Existing release APK is ~12MB; +1.2% is acceptable. If R8 fails to shrink, fall back to a regex+country-array — see Section 5 backend regex for the equivalent shape.
2. **Existing users without phoneNumber**: profile screen shows "Add" affordance on the phone row. They are NOT forced to set a phone retroactively; only new signups go through the bootstrap form. Decision: backwards-compatible, no migration. **Open**: do we want to also gate `/api/account/me`-driven ACTIVE → soft-locked if `phoneNumber == null`? Default: no.
3. **`verifyBeforeUpdateEmail` requires the new email to be unique in Firebase Auth**: if another user already has it, `email-already-in-use` surfaces — handled.
4. **Re-auth UX**: the Edit Email and Edit Password sheets each ask for the current password. This is the Firebase requirement (`requires-recent-login`). If the user signed in via Google/Microsoft only, they have no password — Edit Email needs a different re-auth path (`OAuthProvider`). For first slice: hide Edit Email if `!hasPasswordProvider` and surface a note "Linked accounts: contact support to change your email." **Open**: should we wire the OAuth re-auth path now or defer to a follow-up?
5. **Phone change on profile**: the new phone is written immediately to the user doc. No verification = trust. If we later add OTP, this becomes a longer flow but the storage shape is unchanged.
6. **Sign-out side effect**: if a user is on the verify-email screen and signs out, all the half-state account info (Firebase doc may be created if `getMe` ran already) stays in Firestore in PENDING_PROFILE state. Acceptable — same as a user who signs up via email/password and abandons before bootstrap today.

## Section 9 — Test plan

### Backend (JUnit + Mockito)

- `AccountProfileServiceTest`:
  - `completeProfile_rejectsBlankPhone`
  - `completeProfile_rejectsMalformedPhone` (no `+`, too short, too long, invalid chars)
  - `completeProfile_acceptsValidE164`
  - `updateProfile_phoneOnly_persistsAndAudits`
  - `updateProfile_phoneSameAsExisting_noOp`
  - `profileMatches_phoneDifference_returnsFalse`

### Android (Robolectric + unit tests)

- `ProfileBootstrapViewModelTest`:
  - `firstValidationError_blankPhoneCountry` → `INVALID_PHONE_COUNTRY`
  - `firstValidationError_blankPhoneNumber` → `INVALID_PHONE`
  - `firstValidationError_invalidPerCountry` (Dutch number with 5 digits) → `INVALID_PHONE`
  - `firstValidationError_validPhone` → `null`
  - `submit_passesE164ToRepository`
- `EmailVerificationViewModelTest`:
  - `enter_sendsVerificationOnce` (uses Mockito to verify `sendEmailVerification` called exactly once when `lastSentAtMs == null`)
  - `enter_doesNotResendIfRecentlySent` (rotation reproduction)
  - `checkNow_reloadsAndNavigatesOnVerified`
  - `checkNow_emitsErrorWhenStillUnverified`
  - `resend_cooldownEnforced`
- `EditEmailBottomSheetTest`, `EditPasswordBottomSheetTest`, `EditPhoneBottomSheetTest`:
  - reauth + Firebase call paths mocked, assert correct method calls and error mapping
- `ProfileViewModelTest`:
  - `loadFromAccount_includesPhoneNumber`
  - `accountUpdateRepository.splitFieldMessage("phoneNumber: …") routes to phoneNumber field`

### Manual device matrix (per CLAUDE.md)

- Samsung S25 Ultra (Android 15), Huawei Honor Play (Android 14), Pixel Tablet AVD, Android TV AVD
- Locales: English, Arabic (RTL), Dutch
- Flows:
  - Email/password sign-up → see EmailVerification screen → click link in inbox → "I've verified" → bootstrap → fill phone → main shell
  - Google sign-up → bootstrap directly (no verification screen) → fill phone → main shell
  - Existing email/password user with unverified email signs in → EmailVerification → resend → verify → main
  - Profile → Edit email → wrong password → inline error → correct password → toast → check inbox → click link → return to app → next /me call shows new email
  - Profile → Edit password → wrong current → inline error → correct → toast
  - Profile → Edit phone → bad number → inline → good number → toast → row updates
  - All three sheets on RTL Arabic: layout doesn't break, country picker dropdown reads right-to-left

## Section 10 — Out-of-scope follow-ups (not in this spec)

- OAuth re-auth path for Edit Email when user has no password provider (Question 4 above)
- Bulk migration to require phone on existing accounts
- Phone OTP verification (WhatsApp Business or Firebase Phone Auth)
- Server-side enforcement of email-verified via a new account status
- Admin dashboard surfacing of phone number / email-verification status
- AsYouTypeFormatter live-formatting on the phone input (cosmetic; defer)
