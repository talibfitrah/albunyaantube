# Onboarding — Phone, Email Verify, Personal Info Edit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mandatory E.164 phone collection at signup, hard-gate email verification for email/password sign-ups, and editable email/password/phone rows on the Personal Info screen.

**Architecture:** Phone is trust-based (libphonenumber-android client-side, regex server-side, no OTP). Email verification gate is purely client-side via `FirebaseUser.isEmailVerified` — no new backend account status. Personal Info edits use Firebase's `verifyBeforeUpdateEmail`, re-auth + `updatePassword`, and a backend partial-update PUT for phone.

**Tech Stack:** Kotlin + Hilt + Material 3 on Android; Java 17 + Spring Boot + Firestore on backend. Firebase Auth on both ends. libphonenumber-android 8.13.35 (~150KB AAR). JUnit 4 + mockito-kotlin + kotlinx-coroutines-test on Android; JUnit 5 + Mockito on backend.

**Source spec:** `docs/superpowers/specs/2026-05-25-onboarding-phone-and-edit-design.md`

---

## File Structure

### Backend (Java)

```
backend/src/main/java/com/albunyaan/tube/
├── model/User.java                         MODIFY  + phoneNumber field
├── dto/CompleteProfileRequest.java         MODIFY  + @NotBlank @Pattern phoneNumber
├── dto/UpdateProfileRequest.java           MODIFY  + nullable phoneNumber to record
├── dto/AccountMeResponse.java              MODIFY  + phoneNumber field + builder
└── service/AccountProfileService.java      MODIFY  + validatePhoneNumber + weave through

backend/src/test/java/com/albunyaan/tube/
└── service/AccountProfileServiceTest.java  MODIFY  + 6 new tests

backend/src/main/java/com/albunyaan/tube/controller/
└── AccountController.java                  MODIFY  pass phoneNumber to service
```

### Android — Data layer

```
android/app/src/main/java/com/albunyaan/tube/
├── data/account/CompleteProfileRequestDto.kt        MODIFY  + phoneNumber
├── data/account/dto/UpdateProfileRequestDto.kt      MODIFY  + phoneNumber
├── data/account/AccountMeResponseDto.kt             MODIFY  + phoneNumber
├── data/account/AccountUpdateRepository.kt          MODIFY  splitFieldMessage allowlist
├── auth/AccountState.kt                             MODIFY  + phoneNumber on Loaded
├── auth/AccountRepository.kt                        MODIFY  completeProfile signature
└── auth/AccountRepositoryImpl.kt                    MODIFY  pass phoneNumber, hydrate toLoaded
```

### Android — Shared utilities (NEW)

```
android/app/src/main/java/com/albunyaan/tube/util/
├── EmailShape.kt                           CREATE  extract isEmailShape
└── PhoneFormat.kt                          CREATE  wrap libphonenumber
```

### Android — Bootstrap (signup)

```
android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/
├── ProfileBootstrapViewModel.kt            MODIFY  + phone fields + INVALID_PHONE errors
└── ProfileBootstrapFragment.kt             MODIFY  wire phone country picker + field

android/app/src/main/res/layout/
├── fragment_profile_bootstrap.xml          MODIFY  + phone rows
├── ../layout-sw600dp/fragment_profile_bootstrap.xml  MODIFY  same
└── ../layout-sw720dp/fragment_profile_bootstrap.xml  MODIFY  same
```

### Android — Email verification (NEW)

```
android/app/src/main/java/com/albunyaan/tube/ui/auth/
├── EmailVerificationFragment.kt            CREATE
└── EmailVerificationViewModel.kt           CREATE

android/app/src/main/res/layout/
├── fragment_email_verification.xml         CREATE  3 variants
├── ../layout-sw600dp/fragment_email_verification.xml
└── ../layout-sw720dp/fragment_email_verification.xml
```

### Android — Sign-in routing

```
android/app/src/main/java/com/albunyaan/tube/ui/auth/
├── SignInFragment.kt                       MODIFY  routing fork
└── SignInViewModel.kt                      MODIFY  delegate to EmailShape util

android/app/src/main/res/navigation/
└── app_nav_graph.xml                       MODIFY  + emailVerificationFragment dest + actions
```

### Android — Profile (Personal Info)

```
android/app/src/main/java/com/albunyaan/tube/ui/me/profile/
├── ProfileUiState.kt                       MODIFY  + phoneNumber + hasPasswordProvider
├── ProfileViewModel.kt                     MODIFY  seed phone, refresh after sheets
└── ProfileFragment.kt                      MODIFY  + Edit buttons, sheet launchers

android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/  (NEW directory)
├── EditEmailBottomSheetFragment.kt         CREATE
├── EditEmailViewModel.kt                   CREATE
├── EditPasswordBottomSheetFragment.kt      CREATE
├── EditPasswordViewModel.kt                CREATE
├── EditPhoneBottomSheetFragment.kt         CREATE
└── EditPhoneViewModel.kt                   CREATE

android/app/src/main/res/layout/
├── fragment_profile.xml                    MODIFY  + phone row + edit affordances
├── bottom_sheet_edit_email.xml             CREATE
├── bottom_sheet_edit_password.xml          CREATE
└── bottom_sheet_edit_phone.xml             CREATE
```

### Build / strings

```
android/app/build.gradle.kts                MODIFY  + libphonenumber-android dep

android/app/src/main/res/values/strings.xml     MODIFY  + ~25 keys
android/app/src/main/res/values-ar/strings.xml  MODIFY  + ~25 keys
android/app/src/main/res/values-nl/strings.xml  MODIFY  + ~25 keys
```

---

## Phase 1 — Backend

### Task 1: Add `phoneNumber` to User model

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/model/User.java`

No tests — POJO getter/setter only. Validation is in the service layer.

- [ ] **Step 1: Add field declaration**

Add below `private Timestamp dateOfBirth;` (around line 56):

```java
/** Plan H: E.164 phone number, e.g. "+31612345678". Trust-based — no OTP. */
private String phoneNumber;
```

- [ ] **Step 2: Add getter + setter**

Add to the getters/setters block (next to `getDateOfBirth`/`setDateOfBirth`):

```java
public String getPhoneNumber() {
    return phoneNumber;
}

public void setPhoneNumber(String phoneNumber) {
    this.phoneNumber = phoneNumber;
}
```

- [ ] **Step 3: Update `copy()` (if present)**

Search the file for `public User copy()`. If it exists, add `c.phoneNumber = this.phoneNumber;` to the field-copying block. If it does not exist, skip this step.

```bash
grep -n "public User copy" backend/src/main/java/com/albunyaan/tube/model/User.java
```

- [ ] **Step 4: Compile to verify no breakage**

```bash
cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/User.java
git commit -m "[FEAT]: add phoneNumber field to User model"
```

---

### Task 2: Add `phoneNumber` to backend DTOs

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/dto/CompleteProfileRequest.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/dto/UpdateProfileRequest.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/dto/AccountMeResponse.java`

- [ ] **Step 1: Extend `CompleteProfileRequest`**

Add the import `import jakarta.validation.constraints.Pattern;` near the existing constraint imports.

Add below the `dateOfBirth` field:

```java
@NotBlank
@Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164 format")
private String phoneNumber;
```

Add getter + setter after the existing pair:

```java
public String getPhoneNumber() { return phoneNumber; }
public void setPhoneNumber(String s) { this.phoneNumber = s; }
```

- [ ] **Step 2: Extend `UpdateProfileRequest` record**

Replace the record declaration with:

```java
public record UpdateProfileRequest(
    @Size(min = 1, max = 40) String displayName,
    LocalDate dateOfBirth,
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164 format") String phoneNumber
) {}
```

Add import `import jakarta.validation.constraints.Pattern;` if missing.

- [ ] **Step 3: Extend `AccountMeResponse`**

Add field below `dateOfBirth`:

```java
private final String phoneNumber;
```

Update the constructor signature:

```java
private AccountMeResponse(String uid, String email, String displayName,
                           String dateOfBirth, String phoneNumber, String status, String role,
                           Instant profileCompletedAt) {
    this.uid = uid;
    this.email = email;
    this.displayName = displayName;
    this.dateOfBirth = dateOfBirth;
    this.phoneNumber = phoneNumber;
    this.status = status;
    this.role = role;
    this.profileCompletedAt = profileCompletedAt;
}
```

Update `from(User u)` to pass `u.getPhoneNumber()`:

```java
return new AccountMeResponse(
        u.getUid(),
        u.getEmail(),
        u.getDisplayName(),
        dobIso,
        u.getPhoneNumber(),
        u.getStatus(),
        u.getRole(),
        completedAt);
```

Add getter:

```java
public String getPhoneNumber() { return phoneNumber; }
```

- [ ] **Step 4: Compile**

```bash
cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL. (Tests will fail to compile because of the added record component — fixed in Task 4.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/CompleteProfileRequest.java \
        backend/src/main/java/com/albunyaan/tube/dto/UpdateProfileRequest.java \
        backend/src/main/java/com/albunyaan/tube/dto/AccountMeResponse.java
git commit -m "[FEAT]: add phoneNumber to backend DTOs"
```

---

### Task 3: `validatePhoneNumber` + wire into `completeProfile`

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/AccountController.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add to `AccountProfileServiceTest.java` inside the test class:

```java
@Test
void completeProfileRejectsBlankPhoneNumber() throws Exception {
    User existing = new User("uid-1", "a@b.com", null, "user");
    existing.setStatusEnum(UserStatus.PENDING_PROFILE);
    when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

    ProfileValidationException ex = assertThrows(ProfileValidationException.class,
        () -> service.completeProfile("uid-1", "Alice", LocalDate.of(2000, 1, 1), ""));
    assertEquals("phoneNumber", ex.getField());
}

@Test
void completeProfileRejectsMalformedPhoneNumber() throws Exception {
    User existing = new User("uid-1", "a@b.com", null, "user");
    existing.setStatusEnum(UserStatus.PENDING_PROFILE);
    when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

    // Missing leading + sign
    assertThrows(ProfileValidationException.class,
        () -> service.completeProfile("uid-1", "Alice", LocalDate.of(2000, 1, 1), "31612345678"));
    // Too short (7 digits after +)
    assertThrows(ProfileValidationException.class,
        () -> service.completeProfile("uid-1", "Alice", LocalDate.of(2000, 1, 1), "+1234567"));
    // Leading zero after + is invalid (E.164: first digit 1-9)
    assertThrows(ProfileValidationException.class,
        () -> service.completeProfile("uid-1", "Alice", LocalDate.of(2000, 1, 1), "+0123456789"));
}

@Test
void completeProfileAcceptsValidE164PhoneNumber() throws Exception {
    User existing = new User("uid-1", "a@b.com", null, "user");
    existing.setStatusEnum(UserStatus.PENDING_PROFILE);
    when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = service.completeProfile("uid-1", "Alice",
        LocalDate.of(2000, 1, 1), "+31612345678");

    assertEquals("+31612345678", result.getPhoneNumber());
    assertEquals(UserStatus.ACTIVE, result.getStatusEnum());
}
```

Also update the existing `completeProfileAdultSuccess` and `completeProfileUnder13Rejected` tests to pass a 4th argument `"+31612345678"` to `service.completeProfile(...)`.

- [ ] **Step 2: Run tests to verify they fail with a compile error**

```bash
cd backend && ./gradlew :backend:test --tests "com.albunyaan.tube.service.AccountProfileServiceTest"
```

Expected: FAIL — `completeProfile` takes 3 args, not 4.

- [ ] **Step 3: Add `validatePhoneNumber` to `AccountProfileService`**

Add the regex constant near the other static patterns (top of class):

```java
private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");
```

Add the method as a package-private helper alongside `validateDisplayName`:

```java
void validatePhoneNumber(String phoneNumber) {
    if (phoneNumber == null || phoneNumber.isBlank()) {
        throw new ProfileValidationException("phoneNumber", "must not be blank");
    }
    if (!E164_PATTERN.matcher(phoneNumber).matches()) {
        throw new ProfileValidationException("phoneNumber",
                "must be E.164 format (e.g. +31612345678)");
    }
}
```

- [ ] **Step 4: Extend `completeProfile` to accept and persist `phoneNumber`**

Change the signature:

```java
public User completeProfile(String uid, String displayName, LocalDate dateOfBirth, String phoneNumber)
        throws ExecutionException, InterruptedException, TimeoutException {
    validateDisplayName(displayName);
    validateDateOfBirth(dateOfBirth);
    validatePhoneNumber(phoneNumber);
    // ... rest unchanged until the field-assignment block
```

In the persist block (just before `user.setStatusEnum(UserStatus.ACTIVE);`), add:

```java
user.setPhoneNumber(phoneNumber.trim());
```

Update `profileMatches` to also compare phone:

```java
private static boolean profileMatches(User user, String displayName, LocalDate dateOfBirth, String phoneNumber) {
    if (user.getDisplayName() == null || !user.getDisplayName().equals(displayName.trim())) return false;
    if (user.getPhoneNumber() == null || !user.getPhoneNumber().equals(phoneNumber.trim())) return false;
    Timestamp ts = user.getDateOfBirth();
    if (ts == null) return false;
    LocalDate storedDate = java.time.Instant
            .ofEpochSecond(ts.getSeconds(), ts.getNanos())
            .atZone(ZoneOffset.UTC)
            .toLocalDate();
    return storedDate.equals(dateOfBirth);
}
```

Update the one call site inside `completeProfile`:

```java
if (profileMatches(user, displayName, dateOfBirth, phoneNumber)) {
    return user;
}
```

- [ ] **Step 5: Update `AccountController.completeProfile` call site**

In `backend/src/main/java/com/albunyaan/tube/controller/AccountController.java`, change:

```java
var saved = accountProfileService.completeProfile(
        principal.getUid(), req.getDisplayName(), req.getDateOfBirth(), req.getPhoneNumber());
```

- [ ] **Step 6: Run tests, verify they pass**

```bash
cd backend && ./gradlew :backend:test --tests "com.albunyaan.tube.service.AccountProfileServiceTest"
```

Expected: PASS — all `completeProfile*` tests green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java \
        backend/src/main/java/com/albunyaan/tube/controller/AccountController.java \
        backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java
git commit -m "[FEAT]: validatePhoneNumber + completeProfile persistence"
```

---

### Task 4: Phone in `updateProfile` + audit + idempotency

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add to `AccountProfileServiceTest`:

```java
@Test
void updateProfilePhoneOnlyPersistsAndAudits() throws Exception {
    User existing = new User("uid-1", "a@b.com", "Alice", "user");
    existing.setPhoneNumber("+31612345678");
    existing.setStatusEnum(UserStatus.ACTIVE);
    when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

    UpdateProfileRequest body = new UpdateProfileRequest(null, null, "+447412345678");
    var resp = service.updateProfile("uid-1", body);

    assertEquals("+447412345678", resp.getPhoneNumber());
    verify(userRepository).updateFields(eq("uid-1"),
        argThat(updates -> "+447412345678".equals(updates.get("phoneNumber"))));
    verify(auditLogService).logProfileEdit(eq("uid-1"),
        argThat(diff -> "changed".equals(diff.get("phoneNumber"))));
}

@Test
void updateProfilePhoneSameAsExistingIsNoOp() throws Exception {
    User existing = new User("uid-1", "a@b.com", "Alice", "user");
    existing.setPhoneNumber("+31612345678");
    existing.setStatusEnum(UserStatus.ACTIVE);
    when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

    UpdateProfileRequest body = new UpdateProfileRequest(null, null, "+31612345678");
    service.updateProfile("uid-1", body);

    verify(userRepository, never()).updateFields(any(), any());
    verify(auditLogService, never()).logProfileEdit(any(), any());
}

@Test
void updateProfileRejectsMalformedPhone() {
    User existing = new User("uid-1", "a@b.com", "Alice", "user");
    existing.setPhoneNumber("+31612345678");
    existing.setStatusEnum(UserStatus.ACTIVE);
    when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

    UpdateProfileRequest body = new UpdateProfileRequest(null, null, "not-a-phone");
    assertThrows(ProfileValidationException.class,
        () -> service.updateProfile("uid-1", body));
    verify(userRepository, never()).updateFields(any(), any());
}
```

- [ ] **Step 2: Run tests, expect failure**

```bash
cd backend && ./gradlew :backend:test --tests "com.albunyaan.tube.service.AccountProfileServiceTest"
```

Expected: the three new tests FAIL — `phoneNumber` argument to `UpdateProfileRequest` is rejected (already added in Task 2) but service doesn't read it yet.

- [ ] **Step 3: Wire `phoneNumber` into `updateProfile`**

In `AccountProfileService.updateProfile`, after the existing `dateOfBirth` validation block, add:

```java
if (body.phoneNumber() != null) {
    validatePhoneNumber(body.phoneNumber());
}
```

In the persist block (next to `displayName` / `dateOfBirth` handling), add:

```java
if (body.phoneNumber() != null) {
    String trimmed = body.phoneNumber().trim();
    updates.put("phoneNumber", trimmed);
    updated.setPhoneNumber(trimmed);
}
```

Update `isNoOpUpdate` to consider phone:

```java
private boolean isNoOpUpdate(User u, UpdateProfileRequest body) {
    boolean nameSame = body.displayName() == null
            || body.displayName().trim().equals(u.getDisplayName());
    boolean dobSame = body.dateOfBirth() == null
            || body.dateOfBirth().equals(timestampToLocalDate(u.getDateOfBirth()));
    boolean phoneSame = body.phoneNumber() == null
            || body.phoneNumber().trim().equals(u.getPhoneNumber());
    return nameSame && dobSame && phoneSame;
}
```

Update `changedFields` to include phone:

```java
private Map<String, Object> changedFields(User before, User after) {
    Map<String, Object> diff = new LinkedHashMap<>();
    if (!Objects.equals(before.getDisplayName(), after.getDisplayName())) {
        diff.put("displayName", "changed");
    }
    if (!Objects.equals(before.getDateOfBirth(), after.getDateOfBirth())) {
        diff.put("dateOfBirth", "changed");
    }
    if (!Objects.equals(before.getPhoneNumber(), after.getPhoneNumber())) {
        diff.put("phoneNumber", "changed");
    }
    return diff;
}
```

- [ ] **Step 4: Run tests, expect green**

```bash
cd backend && ./gradlew :backend:test --tests "com.albunyaan.tube.service.AccountProfileServiceTest"
```

Expected: PASS — all tests green including new phone-related ones.

- [ ] **Step 5: Run the full backend test suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL. No regressions in other tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java \
        backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java
git commit -m "[FEAT]: phoneNumber in updateProfile + audit diff"
```

---

## Phase 2 — Android data layer & utilities

### Task 5: Add libphonenumber-android dependency

**Files:**
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Locate the dependencies block**

```bash
grep -n "implementation(" android/app/build.gradle.kts | head -10
```

- [ ] **Step 2: Add the dependency**

Inside the `dependencies { ... }` block, add:

```kotlin
implementation("io.michaelrocks:libphonenumber-android:8.13.35")
```

- [ ] **Step 3: Sync + smoke compile**

```bash
cd android && ./gradlew :app:assembleDebug --dry-run
```

Expected: BUILD SUCCESSFUL (no actual build, just dependency resolution).

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "[CHORE]: add libphonenumber-android 8.13.35"
```

---

### Task 6: Extract `EmailShape.kt` utility

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/util/EmailShape.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/util/EmailShapeTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInViewModel.kt`

- [ ] **Step 1: Write failing tests**

Create `android/app/src/test/java/com/albunyaan/tube/util/EmailShapeTest.kt`:

```kotlin
package com.albunyaan.tube.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailShapeTest {
    @Test fun `accepts simple address`()              = assertTrue(isEmailShape("a@b.co"))
    @Test fun `accepts multi-dot domain`()            = assertTrue(isEmailShape("u.s@a.b.co"))
    @Test fun `rejects missing at`()                  = assertFalse(isEmailShape("ab.co"))
    @Test fun `rejects double at`()                   = assertFalse(isEmailShape("a@@b.co"))
    @Test fun `rejects empty local`()                 = assertFalse(isEmailShape("@b.co"))
    @Test fun `rejects empty domain`()                = assertFalse(isEmailShape("a@"))
    @Test fun `rejects domain without dot`()          = assertFalse(isEmailShape("a@b"))
    @Test fun `rejects leading-dot domain`()          = assertFalse(isEmailShape("a@.co"))
    @Test fun `rejects trailing-dot domain`()         = assertFalse(isEmailShape("a@b."))
}
```

- [ ] **Step 2: Run tests, expect compile failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.util.EmailShapeTest"
```

Expected: FAIL — `isEmailShape` not defined.

- [ ] **Step 3: Create the utility**

Create `android/app/src/main/java/com/albunyaan/tube/util/EmailShape.kt`:

```kotlin
package com.albunyaan.tube.util

/**
 * RFC-5322-shaped email validator. Mirrors Firebase Auth's own minimum
 * shape: a single non-empty local part, one '@', and a domain with at
 * least one dot (no leading or trailing dot). Pre-network gate so we
 * don't burn Firebase throttle quota on obviously-malformed input.
 */
fun isEmailShape(s: String): Boolean {
    val at = s.indexOf('@')
    if (at <= 0 || at != s.lastIndexOf('@')) return false
    if (at == s.length - 1) return false
    val domain = s.substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
}
```

- [ ] **Step 4: Run tests, expect green**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.util.EmailShapeTest"
```

Expected: PASS.

- [ ] **Step 5: Delete the private copy in `SignInViewModel`**

In `SignInViewModel.kt`, remove the private `isEmailShape` function (and the call site `if (!isEmailShape(snapshot.email))` becomes `if (!com.albunyaan.tube.util.isEmailShape(snapshot.email))` or add an import line `import com.albunyaan.tube.util.isEmailShape`).

- [ ] **Step 6: Run the existing `SignInViewModelTest`**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.auth.SignInViewModelTest"
```

Expected: PASS — existing tests untouched.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/util/EmailShape.kt \
        android/app/src/test/java/com/albunyaan/tube/util/EmailShapeTest.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInViewModel.kt
git commit -m "[REFACTOR]: extract isEmailShape to shared util"
```

---

### Task 7: Create `PhoneFormat.kt` utility

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/util/PhoneFormat.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/util/PhoneFormatTest.kt`

- [ ] **Step 1: Write failing tests**

Create `android/app/src/test/java/com/albunyaan/tube/util/PhoneFormatTest.kt`:

```kotlin
package com.albunyaan.tube.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneFormatTest {

    private lateinit var ctx: Context

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext() }

    @Test fun `formatE164 returns canonical Dutch mobile`() {
        val out = PhoneFormat.formatE164(ctx, region = "NL", national = "612345678")
        assertEquals("+31612345678", out)
    }

    @Test fun `formatE164 rejects too-short Dutch number`() {
        val out = PhoneFormat.formatE164(ctx, region = "NL", national = "12345")
        assertNull(out)
    }

    @Test fun `formatE164 strips separators`() {
        val out = PhoneFormat.formatE164(ctx, region = "NL", national = "06 12 34 56 78")
        assertEquals("+31612345678", out)
    }

    @Test fun `parseDisplay splits Dutch E164 into region and national`() {
        val pair = PhoneFormat.parseDisplay(ctx, e164 = "+31612345678")
        assertNotNull(pair)
        assertEquals("NL", pair!!.first)
        assertEquals("612345678", pair.second)
    }

    @Test fun `parseDisplay returns null for malformed input`() {
        assertNull(PhoneFormat.parseDisplay(ctx, "not-a-number"))
    }
}
```

- [ ] **Step 2: Run tests, expect compile failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.util.PhoneFormatTest"
```

Expected: FAIL — `PhoneFormat` not defined.

- [ ] **Step 3: Create the utility**

Create `android/app/src/main/java/com/albunyaan/tube/util/PhoneFormat.kt`:

```kotlin
package com.albunyaan.tube.util

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/**
 * Wraps libphonenumber-android. The PhoneNumberUtil is per-context (loads
 * metadata via the AAR's asset bundle) — callers pass any Context (typically
 * ApplicationContext). All methods are pure and side-effect free.
 */
object PhoneFormat {

    private fun util(ctx: Context): PhoneNumberUtil =
        PhoneNumberUtil.createInstance(ctx.applicationContext)

    /**
     * Parse [national] (digits, may contain spaces / dashes / parens) using
     * [region] (ISO-3166-1 alpha-2) and return the E.164 string when valid
     * for that region, else null.
     */
    fun formatE164(ctx: Context, region: String, national: String): String? = try {
        val u = util(ctx)
        val parsed = u.parse(national, region)
        if (!u.isValidNumberForRegion(parsed, region)) null
        else u.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    } catch (_: NumberParseException) {
        null
    }

    /**
     * Reverse of [formatE164]: split an E.164 string into (region, national).
     * Returns null when [e164] cannot be parsed.
     */
    fun parseDisplay(ctx: Context, e164: String): Pair<String, String>? = try {
        val u = util(ctx)
        val parsed = u.parse(e164, null)
        val region = u.getRegionCodeForNumber(parsed) ?: return null
        val national = u.getNationalSignificantNumber(parsed)
        region to national
    } catch (_: NumberParseException) {
        null
    }

    /** ISO-3166-1 alpha-2 region codes libphonenumber knows about. */
    fun supportedRegions(ctx: Context): Set<String> =
        util(ctx).supportedRegions
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.util.PhoneFormatTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/util/PhoneFormat.kt \
        android/app/src/test/java/com/albunyaan/tube/util/PhoneFormatTest.kt
git commit -m "[FEAT]: PhoneFormat util wraps libphonenumber-android"
```

---

### Task 8: Add `phoneNumber` to Android DTOs

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/account/CompleteProfileRequestDto.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/account/dto/UpdateProfileRequestDto.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/account/AccountMeResponseDto.kt`

- [ ] **Step 1: Extend `CompleteProfileRequestDto`**

Replace the class with:

```kotlin
package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompleteProfileRequestDto(
    val displayName: String,
    /** Wire format: "YYYY-MM-DD". */
    val dateOfBirth: String,
    /** E.164 international format, e.g. "+31612345678". */
    val phoneNumber: String,
)
```

- [ ] **Step 2: Extend `UpdateProfileRequestDto`**

Replace with:

```kotlin
package com.albunyaan.tube.data.account.dto

import com.squareup.moshi.JsonClass

/**
 * Request body for PUT /api/account/profile.
 * All fields nullable: null = no change.
 */
@JsonClass(generateAdapter = true)
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    val dateOfBirth: String? = null,
    val phoneNumber: String? = null,
)
```

- [ ] **Step 3: Extend `AccountMeResponseDto`**

Replace with:

```kotlin
package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AccountMeResponseDto(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val dateOfBirth: String?,
    val phoneNumber: String?,
    val status: String,
    val role: String?,
    val profileCompletedAt: String?,
)
```

- [ ] **Step 4: Smoke compile**

```bash
cd android && ./gradlew :app:compileDebugKotlin
```

Expected: errors in `AccountRepositoryImpl.toLoaded`, `AccountUpdateRepository`, anywhere that constructs these DTOs. Those are intentional and fixed in subsequent tasks.

- [ ] **Step 5: Commit (still red — DTOs alone are a sensible commit boundary)**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/account/CompleteProfileRequestDto.kt \
        android/app/src/main/java/com/albunyaan/tube/data/account/dto/UpdateProfileRequestDto.kt \
        android/app/src/main/java/com/albunyaan/tube/data/account/AccountMeResponseDto.kt
git commit -m "[FEAT]: add phoneNumber to Android DTOs"
```

---

### Task 9: Extend `AccountState`, `AccountRepository`, `AccountRepositoryImpl`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepository.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt`

- [ ] **Step 1: Extend `AccountState.Loaded`**

In `AccountState.kt`, change the `Loaded` declaration:

```kotlin
data class Loaded(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val dateOfBirth: String?,
    val phoneNumber: String?,
    val status: AccountStatus,
    val role: String,
) : AccountState
```

- [ ] **Step 2: Extend `AccountRepository.completeProfile`**

In `AccountRepository.kt`, change:

```kotlin
suspend fun completeProfile(
    displayName: String,
    dateOfBirth: LocalDate,
    phoneNumber: String,
): Result<AccountState.Loaded>
```

- [ ] **Step 3: Update `AccountRepositoryImpl.completeProfile`**

In `AccountRepositoryImpl.kt`, change the override and the request build:

```kotlin
override suspend fun completeProfile(
    displayName: String,
    dateOfBirth: LocalDate,
    phoneNumber: String,
): Result<AccountState.Loaded> {
    val request = CompleteProfileRequestDto(
        displayName = displayName,
        dateOfBirth = dateOfBirth.format(DateTimeFormatter.ISO_LOCAL_DATE),
        phoneNumber = phoneNumber,
    )
    // ... rest unchanged
}
```

- [ ] **Step 4: Update `toLoaded()` helper (search for `fun AccountMeResponseDto.toLoaded`)**

```bash
grep -n "fun AccountMeResponseDto.toLoaded\|toLoaded(): AccountState" android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt
```

In the body of that extension, add `phoneNumber = phoneNumber,` to the `AccountState.Loaded(...)` constructor call. The existing pattern is:

```kotlin
private fun AccountMeResponseDto.toLoaded(): AccountState.Loaded = AccountState.Loaded(
    uid = uid,
    email = email,
    displayName = displayName,
    dateOfBirth = dateOfBirth,
    phoneNumber = phoneNumber,
    status = AccountStatus.from(status),
    role = role.orEmpty(),
)
```

- [ ] **Step 5: Compile**

```bash
cd android && ./gradlew :app:compileDebugKotlin
```

Expected: still fails — call sites in `ProfileBootstrapViewModel`, fakes in tests still pass 2-arg completeProfile. Resolved in Task 12.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt \
        android/app/src/main/java/com/albunyaan/tube/auth/AccountRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt
git commit -m "[FEAT]: phoneNumber on AccountState + completeProfile arg"
```

---

### Task 10: Extend `AccountUpdateRepository.splitFieldMessage`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/account/AccountUpdateRepository.kt`

- [ ] **Step 1: Locate the allowlist**

```bash
grep -n "displayName.*dateOfBirth" android/app/src/main/java/com/albunyaan/tube/data/account/AccountUpdateRepository.kt
```

- [ ] **Step 2: Add `phoneNumber` to the allowlist**

Change:

```kotlin
return when (field) {
    "displayName", "dateOfBirth" -> field to raw.substring(sep + 2).trim()
    else -> "displayName" to raw
}
```

To:

```kotlin
return when (field) {
    "displayName", "dateOfBirth", "phoneNumber" -> field to raw.substring(sep + 2).trim()
    else -> "displayName" to raw
}
```

- [ ] **Step 3: Smoke compile**

```bash
cd android && ./gradlew :app:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/account/AccountUpdateRepository.kt
git commit -m "[FIX]: route phoneNumber validation errors to phone field"
```

---

## Phase 3 — Bootstrap form

### Task 11: `BootstrapError` + `UiState` phone fields + validation

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModel.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModelTest.kt`

- [ ] **Step 1: Add new error variants**

In `ProfileBootstrapViewModel.kt`, extend the enum:

```kotlin
enum class BootstrapError {
    INVALID_NAME,
    INVALID_DOB,
    INVALID_PHONE_COUNTRY,
    INVALID_PHONE,
    INVALID_PASSWORD,
    PASSWORD_MISMATCH,
    PASSWORD_SET_FAILED,
    SAVE_FAILED,
}
```

- [ ] **Step 2: Add phone fields to UiState + `formatE164` injection**

Update the constructor to inject `Context`:

```kotlin
@HiltViewModel
class ProfileBootstrapViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val firebaseAuth: FirebaseAuth,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
```

Add the new imports at the top:

```kotlin
import android.content.Context
import com.albunyaan.tube.util.PhoneFormat
import dagger.hilt.android.qualifiers.ApplicationContext
```

Add fields to `UiState`:

```kotlin
data class UiState(
    val displayName: String = "",
    val dateOfBirth: LocalDate? = null,
    val phoneCountry: String? = null,    // ISO-3166-1 alpha-2 e.g. "NL"
    val phoneNumber: String = "",         // national-portion as typed
    val password: String = "",
    val passwordConfirm: String = "",
    val passwordRequired: Boolean = false,
    val profileSaved: Boolean = false,
    val isLoading: Boolean = false,
    val error: BootstrapError? = null,
)
```

Move `firstValidationError()` *out* of the UiState (since it now needs Context for libphonenumber) and into the ViewModel as a private method:

```kotlin
fun firstValidationError(s: UiState = _ui.value): BootstrapError? {
    val name = s.displayName.trim()
    if (name.isBlank() || name.length > 40) return BootstrapError.INVALID_NAME
    if (s.dateOfBirth == null)                  return BootstrapError.INVALID_DOB
    if (s.phoneCountry.isNullOrBlank())         return BootstrapError.INVALID_PHONE_COUNTRY
    val e164 = PhoneFormat.formatE164(appContext, s.phoneCountry, s.phoneNumber)
        ?: return BootstrapError.INVALID_PHONE
    if (s.passwordRequired) {
        if (s.password.length < MIN_PASSWORD_LENGTH) return BootstrapError.INVALID_PASSWORD
        if (s.password != s.passwordConfirm)         return BootstrapError.PASSWORD_MISMATCH
    }
    return null
}

val isFormValid: Boolean get() = firstValidationError() == null
```

Remove the prior `fun UiState.firstValidationError()` and the `val UiState.isFormValid: Boolean` extensions. The fragment will instead observe `viewModel.uiStateAndValidity` or use `viewModel.isFormValid` — adjust `render()` in the Fragment accordingly (next task).

Add handlers:

```kotlin
fun onPhoneCountryChanged(region: String) {
    _ui.update { it.copy(phoneCountry = region, error = null) }
}

fun onPhoneNumberChanged(v: String) {
    _ui.update { it.copy(phoneNumber = v, error = null) }
}
```

- [ ] **Step 3: Update `submit()` to pass E.164**

```kotlin
fun submit() {
    val s = _ui.value
    if (s.isLoading) return
    val validationError = firstValidationError(s)
    if (validationError != null) {
        _ui.update { it.copy(error = validationError) }
        return
    }
    val name = s.displayName.trim()
    val dob = s.dateOfBirth!!
    val phoneE164 = PhoneFormat.formatE164(appContext, s.phoneCountry!!, s.phoneNumber)!!
    _ui.update { it.copy(isLoading = true, error = null) }
    viewModelScope.launch {
        if (!s.profileSaved) {
            val profileResult = accountRepository.completeProfile(name, dob, phoneE164)
            // ... rest unchanged (replace 2-arg call with 3-arg above)
```

- [ ] **Step 4: Update the existing test setup**

In `ProfileBootstrapViewModelTest.kt`, add the `Context` to the constructor. Use Robolectric for a real Context:

```kotlin
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfileBootstrapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AccountRepository
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var ctx: Context
    private lateinit var viewModel: ProfileBootstrapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        whenever(repository.accountState).thenReturn(MutableStateFlow(AccountState.NotSignedIn))
        firebaseAuth = mock()
        ctx = ApplicationProvider.getApplicationContext()
        viewModel = ProfileBootstrapViewModel(repository, firebaseAuth, ctx)
    }
```

Update the existing happy-path test to set phone:

```kotlin
@Test fun `submit happy path transitions to NavigateToMain`() = runTest(dispatcher) {
    whenever(repository.completeProfile("Alice", LocalDate.of(2000, 1, 1), "+31612345678"))
        .thenReturn(Result.success(AccountState.Loaded(
            uid = "uid-1", email = "a@b.com", displayName = "Alice",
            dateOfBirth = null, phoneNumber = "+31612345678",
            status = AccountStatus.ACTIVE, role = "user")))

    viewModel.onDisplayNameChanged("Alice")
    viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
    viewModel.onPhoneCountryChanged("NL")
    viewModel.onPhoneNumberChanged("612345678")
    viewModel.submit()
    advanceUntilIdle()

    assertEquals(BootstrapNav.NavigateToMain, viewModel.nav.value)
}
```

Add new tests for phone validation:

```kotlin
@Test fun `submit with missing phone country surfaces INVALID_PHONE_COUNTRY`() = runTest(dispatcher) {
    viewModel.onDisplayNameChanged("Alice")
    viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
    viewModel.onPhoneNumberChanged("612345678")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(BootstrapError.INVALID_PHONE_COUNTRY, viewModel.ui.value.error)
}

@Test fun `submit with blank phone number surfaces INVALID_PHONE`() = runTest(dispatcher) {
    viewModel.onDisplayNameChanged("Alice")
    viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
    viewModel.onPhoneCountryChanged("NL")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(BootstrapError.INVALID_PHONE, viewModel.ui.value.error)
}

@Test fun `submit with too-short national number surfaces INVALID_PHONE`() = runTest(dispatcher) {
    viewModel.onDisplayNameChanged("Alice")
    viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
    viewModel.onPhoneCountryChanged("NL")
    viewModel.onPhoneNumberChanged("12345")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(BootstrapError.INVALID_PHONE, viewModel.ui.value.error)
}
```

- [ ] **Step 5: Run tests, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.bootstrap.ProfileBootstrapViewModelTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModel.kt \
        android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModelTest.kt
git commit -m "[FEAT]: phone country + number on bootstrap UiState"
```

---

### Task 12: Add phone fields to bootstrap layouts (3 variants) + strings

**Files:**
- Modify: `android/app/src/main/res/layout/fragment_profile_bootstrap.xml`
- Modify: `android/app/src/main/res/layout-sw600dp/fragment_profile_bootstrap.xml`
- Modify: `android/app/src/main/res/layout-sw720dp/fragment_profile_bootstrap.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-ar/strings.xml`
- Modify: `android/app/src/main/res/values-nl/strings.xml`

- [ ] **Step 1: Add strings (en)**

In `values/strings.xml` add:

```xml
<string name="bootstrap_phone_country_label">Country</string>
<string name="bootstrap_phone_label">Mobile number</string>
<string name="bootstrap_phone_hint">Phone number</string>
<string name="bootstrap_error_invalid_phone_country">Pick your country</string>
<string name="bootstrap_error_invalid_phone">Enter a valid mobile number</string>
```

- [ ] **Step 2: Add strings (ar)**

In `values-ar/strings.xml`:

```xml
<string name="bootstrap_phone_country_label">الدولة</string>
<string name="bootstrap_phone_label">رقم الجوال</string>
<string name="bootstrap_phone_hint">رقم الهاتف</string>
<string name="bootstrap_error_invalid_phone_country">اختر دولتك</string>
<string name="bootstrap_error_invalid_phone">أدخل رقم جوال صحيح</string>
```

- [ ] **Step 3: Add strings (nl)**

In `values-nl/strings.xml`:

```xml
<string name="bootstrap_phone_country_label">Land</string>
<string name="bootstrap_phone_label">Mobiel nummer</string>
<string name="bootstrap_phone_hint">Telefoonnummer</string>
<string name="bootstrap_error_invalid_phone_country">Kies je land</string>
<string name="bootstrap_error_invalid_phone">Voer een geldig mobiel nummer in</string>
```

- [ ] **Step 4: Extend the phone-variant layout**

In `android/app/src/main/res/layout/fragment_profile_bootstrap.xml`, insert two new TextInputLayouts between `dobLayout` and `passwordExplainer`. The phoneCountry is a Material exposed-dropdown:

```xml
<com.google.android.material.textfield.TextInputLayout
    android:id="@+id/phoneCountryLayout"
    style="@style/Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/spacing_md"
    android:hint="@string/bootstrap_phone_country_label"
    app:layout_constraintTop_toBottomOf="@id/dobLayout"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent">

    <AutoCompleteTextView
        android:id="@+id/phoneCountryField"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="none"
        android:textAlignment="viewStart" />
</com.google.android.material.textfield.TextInputLayout>

<com.google.android.material.textfield.TextInputLayout
    android:id="@+id/phoneLayout"
    style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/spacing_sm"
    android:hint="@string/bootstrap_phone_label"
    app:layout_constraintTop_toBottomOf="@id/phoneCountryLayout"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent">

    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/phoneField"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="phone"
        android:textAlignment="viewStart"
        android:autofillHints="phone"
        android:imeOptions="actionNext" />
</com.google.android.material.textfield.TextInputLayout>
```

Then update the `passwordExplainer` `app:layout_constraintTop_toBottomOf` to `@id/phoneLayout`.

- [ ] **Step 5: Replicate the same insertion in the sw600dp and sw720dp layouts**

Open `layout-sw600dp/fragment_profile_bootstrap.xml` and `layout-sw720dp/fragment_profile_bootstrap.xml`. Add the same two new layouts, adjusted to whatever constraint chain the existing file uses. View IDs must match the phone variant. Make sure to update the password section's top constraint.

- [ ] **Step 6: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/fragment_profile_bootstrap.xml \
        android/app/src/main/res/layout-sw600dp/fragment_profile_bootstrap.xml \
        android/app/src/main/res/layout-sw720dp/fragment_profile_bootstrap.xml \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: phone country picker + field in bootstrap layouts"
```

---

### Task 13: Wire `ProfileBootstrapFragment` to phone fields

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapFragment.kt`

- [ ] **Step 1: Add view properties**

Inside the class, add fields:

```kotlin
private lateinit var phoneCountryLayout: TextInputLayout
private lateinit var phoneCountryField: AutoCompleteTextView
private lateinit var phoneLayout: TextInputLayout
private lateinit var phoneField: TextInputEditText
```

Imports:

```kotlin
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import com.albunyaan.tube.util.PhoneFormat
import java.util.Locale
```

- [ ] **Step 2: Bind in `bindViews(v: View)`**

```kotlin
phoneCountryLayout = v.findViewById(R.id.phoneCountryLayout)
phoneCountryField  = v.findViewById(R.id.phoneCountryField)
phoneLayout        = v.findViewById(R.id.phoneLayout)
phoneField         = v.findViewById(R.id.phoneField)
```

- [ ] **Step 3: Populate the country dropdown**

Add a helper inside the fragment:

```kotlin
private data class CountryRow(val isoCode: String, val displayName: String) {
    override fun toString(): String = displayName
}

private fun buildCountryRows(): List<CountryRow> {
    val ctx = requireContext()
    val locale = ctx.resources.configuration.locales[0]
    return PhoneFormat.supportedRegions(ctx)
        .map { iso ->
            CountryRow(
                isoCode = iso,
                displayName = Locale("", iso).getDisplayCountry(locale).ifBlank { iso },
            )
        }
        .sortedBy { it.displayName }
}
```

In `onViewCreated`, after `bindViews(view)`:

```kotlin
val countries = buildCountryRows()
val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, countries)
phoneCountryField.setAdapter(adapter)
phoneCountryField.setOnItemClickListener { _, _, position, _ ->
    val row = adapter.getItem(position) ?: return@setOnItemClickListener
    viewModel.onPhoneCountryChanged(row.isoCode)
}
// Seed default selection from network/locale
val defaultIso = (requireContext().resources.configuration.locales[0].country)
    .takeIf { it.isNotBlank() } ?: "NL"
countries.firstOrNull { it.isoCode == defaultIso }?.let {
    phoneCountryField.setText(it.displayName, /* filter */ false)
    viewModel.onPhoneCountryChanged(it.isoCode)
}
```

- [ ] **Step 4: Wire the phone-number text watcher**

In `wireListeners()`:

```kotlin
phoneField.addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) {
        viewModel.onPhoneNumberChanged(s?.toString().orEmpty())
    }
})
```

- [ ] **Step 5: Surface phone errors in `render(state)`**

Add to the error block:

```kotlin
phoneCountryLayout.error = state.error?.takeIf { it == BootstrapError.INVALID_PHONE_COUNTRY }
    ?.let { getString(R.string.bootstrap_error_invalid_phone_country) }
phoneLayout.error = state.error?.takeIf { it == BootstrapError.INVALID_PHONE }
    ?.let { getString(R.string.bootstrap_error_invalid_phone) }
```

Update the `submitButton.isEnabled` line:

```kotlin
submitButton.isEnabled = viewModel.isFormValid && !state.isLoading
```

(Previously it read `state.isFormValid`, but the extension was removed in Task 11. Use `viewModel.isFormValid` instead.)

- [ ] **Step 6: Manual smoke**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapFragment.kt
git commit -m "[FEAT]: ProfileBootstrap wires phone country + field"
```

---

## Phase 4 — Email verification fragment

### Task 14: `EmailVerificationViewModel` skeleton + tests

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationViewModel.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/auth/EmailVerificationViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.albunyaan.tube.ui.auth

import androidx.lifecycle.SavedStateHandle
import com.albunyaan.tube.auth.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var authRepository: AuthRepository
    private lateinit var saved: SavedStateHandle

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock {
            on { email } doReturn "you@example.com"
            on { isEmailVerified } doReturn false
        }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.sendEmailVerification()).thenReturn(Tasks.forResult(null))
        whenever(user.reload()).thenReturn(Tasks.forResult(null))
        authRepository = mock()
        saved = SavedStateHandle()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun newVm() = EmailVerificationViewModel(auth, authRepository, saved)

    @Test fun `enter sends verification once when lastSentAt is null`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        verify(user, times(1)).sendEmailVerification()
        assertNotNull(vm.ui.value.lastSentAtMs)
    }

    @Test fun `enter does not resend when lastSentAt already in saved state`() = runTest(dispatcher) {
        saved["lastSentAtMs"] = 1_700_000_000_000L
        newVm()
        advanceUntilIdle()
        verify(user, never()).sendEmailVerification()
    }

    @Test fun `checkNow navigates to splash when verified`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        whenever(user.isEmailVerified).thenReturn(true)
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerificationViewModel.Nav.NavigateToSplash, vm.nav.value)
    }

    @Test fun `checkNow surfaces NOT_YET_VERIFIED when still false`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        // user.isEmailVerified stays false
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerifyError.NOT_YET_VERIFIED, vm.ui.value.error)
    }

    @Test fun `resend respects 60s cooldown`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        clearInvocations(user)
        vm.resend()
        advanceUntilIdle()
        verify(user, never()).sendEmailVerification() // within cooldown
        assertEquals(EmailVerifyError.RATE_LIMITED, vm.ui.value.error)
    }

    @Test fun `signOut clears state and navigates to signIn`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.signOut()
        advanceUntilIdle()
        verify(authRepository).signOut()
        assertEquals(EmailVerificationViewModel.Nav.NavigateToSignIn, vm.nav.value)
    }
}
```

Note: depends on `kotlinx-coroutines-play-services` (already in the project) and adds nothing new.

- [ ] **Step 2: Run tests, expect compile failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.auth.EmailVerificationViewModelTest"
```

Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create the ViewModel**

`android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationViewModel.kt`:

```kotlin
package com.albunyaan.tube.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class EmailVerifyError {
    NOT_YET_VERIFIED,
    RATE_LIMITED,
    NETWORK,
    UNKNOWN,
}

@HiltViewModel
class EmailVerificationViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val authRepository: AuthRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val email: String = "",
        val isChecking: Boolean = false,
        val isResending: Boolean = false,
        val lastSentAtMs: Long? = null,
        val error: EmailVerifyError? = null,
    )

    sealed interface Nav {
        data object Idle : Nav
        data object NavigateToSplash : Nav
        data object NavigateToSignIn : Nav
    }

    private val _ui = MutableStateFlow(UiState(
        email = firebaseAuth.currentUser?.email.orEmpty(),
        lastSentAtMs = saved.get<Long>("lastSentAtMs"),
    ))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    init {
        if (saved.get<Long>("lastSentAtMs") == null) {
            sendVerificationEmail()
        }
    }

    fun consumeNav() { _nav.value = Nav.Idle }

    fun checkNow() {
        if (_ui.value.isChecking) return
        _ui.update { it.copy(isChecking = true, error = null) }
        viewModelScope.launch {
            val user = firebaseAuth.currentUser
            if (user == null) {
                _ui.update { it.copy(isChecking = false, error = EmailVerifyError.UNKNOWN) }
                return@launch
            }
            try {
                user.reload().await()
                if (user.isEmailVerified) {
                    _nav.value = Nav.NavigateToSplash
                } else {
                    _ui.update { it.copy(isChecking = false, error = EmailVerifyError.NOT_YET_VERIFIED) }
                    return@launch
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isChecking = false, error = EmailVerifyError.NETWORK) }
                return@launch
            }
            _ui.update { it.copy(isChecking = false) }
        }
    }

    fun resend() {
        val last = _ui.value.lastSentAtMs
        val now = System.currentTimeMillis()
        if (last != null && now - last < COOLDOWN_MS) {
            _ui.update { it.copy(error = EmailVerifyError.RATE_LIMITED) }
            return
        }
        sendVerificationEmail()
    }

    private fun sendVerificationEmail() {
        if (_ui.value.isResending) return
        _ui.update { it.copy(isResending = true, error = null) }
        viewModelScope.launch {
            val user = firebaseAuth.currentUser
            if (user == null) {
                _ui.update { it.copy(isResending = false, error = EmailVerifyError.UNKNOWN) }
                return@launch
            }
            try {
                user.sendEmailVerification().await()
                val now = System.currentTimeMillis()
                saved["lastSentAtMs"] = now
                _ui.update { it.copy(isResending = false, lastSentAtMs = now) }
            } catch (e: Exception) {
                // Firebase tooManyRequests surfaces here; map to RATE_LIMITED
                val mapped = if (e.message?.contains("too-many-requests", ignoreCase = true) == true)
                    EmailVerifyError.RATE_LIMITED
                else EmailVerifyError.NETWORK
                _ui.update { it.copy(isResending = false, error = mapped) }
            }
        }
    }

    fun signOut() {
        authRepository.signOut()
        _nav.value = Nav.NavigateToSignIn
    }

    companion object {
        const val COOLDOWN_MS = 60_000L
    }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.auth.EmailVerificationViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationViewModel.kt \
        android/app/src/test/java/com/albunyaan/tube/ui/auth/EmailVerificationViewModelTest.kt
git commit -m "[FEAT]: EmailVerificationViewModel + cooldown + tests"
```

---

### Task 15: Email verification layouts + strings

**Files:**
- Create: `android/app/src/main/res/layout/fragment_email_verification.xml`
- Create: `android/app/src/main/res/layout-sw600dp/fragment_email_verification.xml`
- Create: `android/app/src/main/res/layout-sw720dp/fragment_email_verification.xml`
- Modify: `android/app/src/main/res/values/strings.xml` (+ar, +nl)

- [ ] **Step 1: Add strings (en)**

```xml
<string name="email_verification_title">Verify your email</string>
<string name="email_verification_body">We sent a verification link to %1$s. Tap it, then come back here.</string>
<string name="email_verification_check_now">I\'ve verified my email</string>
<string name="email_verification_resend">Resend email</string>
<string name="email_verification_use_different">Use a different email</string>
<string name="email_verification_not_yet">Still not verified. Check your inbox (and spam folder).</string>
<string name="email_verification_rate_limited">Please wait before resending.</string>
<string name="email_verification_last_sent">Last sent %1$d seconds ago</string>
<string name="email_verification_network_error">Couldn\'t reach the server. Try again.</string>
```

- [ ] **Step 2: Add strings (ar)**

```xml
<string name="email_verification_title">تحقق من بريدك الإلكتروني</string>
<string name="email_verification_body">أرسلنا رابط التحقق إلى %1$s. اضغط عليه ثم عُد إلى هنا.</string>
<string name="email_verification_check_now">لقد تحققت من بريدي</string>
<string name="email_verification_resend">إعادة إرسال البريد</string>
<string name="email_verification_use_different">استخدم بريدًا آخر</string>
<string name="email_verification_not_yet">لم يتم التحقق بعد. راجع بريدك (والرسائل غير المرغوب فيها).</string>
<string name="email_verification_rate_limited">الرجاء الانتظار قبل إعادة الإرسال.</string>
<string name="email_verification_last_sent">آخر إرسال قبل %1$d ثانية</string>
<string name="email_verification_network_error">تعذر الوصول إلى الخادم. حاول مجددًا.</string>
```

- [ ] **Step 3: Add strings (nl)**

```xml
<string name="email_verification_title">Bevestig je e-mail</string>
<string name="email_verification_body">We hebben een bevestigingslink gestuurd naar %1$s. Klik erop en kom dan terug.</string>
<string name="email_verification_check_now">Ik heb mijn e-mail bevestigd</string>
<string name="email_verification_resend">E-mail opnieuw verzenden</string>
<string name="email_verification_use_different">Gebruik een ander e-mailadres</string>
<string name="email_verification_not_yet">Nog niet bevestigd. Check je inbox (en spam).</string>
<string name="email_verification_rate_limited">Wacht even voordat je opnieuw verzendt.</string>
<string name="email_verification_last_sent">Laatst verstuurd %1$d seconden geleden</string>
<string name="email_verification_network_error">Kan de server niet bereiken. Probeer opnieuw.</string>
```

- [ ] **Step 4: Phone-variant layout**

Create `android/app/src/main/res/layout/fragment_email_verification.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true"
    android:fitsSystemWindows="true"
    android:background="?android:attr/colorBackground">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:gravity="center_horizontal"
        android:paddingHorizontal="@dimen/spacing_md"
        android:paddingTop="@dimen/spacing_lg">

        <ImageView
            android:id="@+id/icon"
            android:layout_width="96dp"
            android:layout_height="96dp"
            android:src="@android:drawable/ic_dialog_email"
            android:layout_marginBottom="@dimen/spacing_md"
            android:contentDescription="@string/email_verification_title" />

        <TextView
            android:id="@+id/title"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/email_verification_title"
            android:textAppearance="?attr/textAppearanceHeadline5"
            android:textAlignment="center"
            android:layout_marginBottom="@dimen/spacing_md" />

        <TextView
            android:id="@+id/bodyText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBody1"
            android:textAlignment="center"
            android:layout_marginBottom="@dimen/spacing_lg"
            tools:ignore="MissingTools" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/checkNowButton"
            android:layout_width="match_parent"
            android:layout_height="@dimen/auth_button_height"
            android:text="@string/email_verification_check_now" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/resendButton"
            style="@style/Widget.Material3.Button.OutlinedButton"
            android:layout_width="match_parent"
            android:layout_height="@dimen/auth_button_height"
            android:layout_marginTop="@dimen/spacing_sm"
            android:text="@string/email_verification_resend" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/signOutButton"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/spacing_sm"
            android:text="@string/email_verification_use_different" />

        <TextView
            android:id="@+id/lastSentText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBody2"
            android:textAlignment="center"
            android:layout_marginTop="@dimen/spacing_md"
            android:visibility="gone" />

        <TextView
            android:id="@+id/errorText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/spacing_sm"
            android:textAppearance="?attr/textAppearanceBody2"
            android:textColor="?attr/colorError"
            android:textAlignment="center"
            android:visibility="gone" />

        <ProgressBar
            android:id="@+id/spinner"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/spacing_md"
            android:visibility="gone" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 5: sw600dp + sw720dp variants**

Copy the phone variant into `layout-sw600dp/fragment_email_verification.xml` and `layout-sw720dp/fragment_email_verification.xml`. Constrain the inner LinearLayout to a max width of 560dp (sw600) / 720dp (sw720) by wrapping in a centered horizontally-constrained container — or copy verbatim if you want the same layout, since the screen has few elements. Smallest-effort path: identical content with tweaked top padding.

- [ ] **Step 6: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/fragment_email_verification.xml \
        android/app/src/main/res/layout-sw600dp/fragment_email_verification.xml \
        android/app/src/main/res/layout-sw720dp/fragment_email_verification.xml \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: EmailVerification layouts + strings (en/ar/nl)"
```

---

### Task 16: `EmailVerificationFragment`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationFragment.kt`

- [ ] **Step 1: Create the fragment**

```kotlin
package com.albunyaan.tube.ui.auth

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class EmailVerificationFragment : Fragment(R.layout.fragment_email_verification) {

    private val viewModel: EmailVerificationViewModel by viewModels()

    private lateinit var bodyText: TextView
    private lateinit var checkNowButton: MaterialButton
    private lateinit var resendButton: MaterialButton
    private lateinit var signOutButton: MaterialButton
    private lateinit var lastSentText: TextView
    private lateinit var errorText: TextView
    private lateinit var spinner: ProgressBar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bind(view)
        wire()

        // Back-press = sign out (avoid trap loop, matches ProfileBootstrap pattern)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { viewModel.signOut() }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    when (nav) {
                        EmailVerificationViewModel.Nav.Idle -> Unit
                        EmailVerificationViewModel.Nav.NavigateToSplash -> {
                            findNavController().navigate(R.id.action_emailVerification_to_splash)
                            viewModel.consumeNav()
                        }
                        EmailVerificationViewModel.Nav.NavigateToSignIn -> {
                            findNavController().navigate(R.id.action_emailVerification_to_signIn)
                            viewModel.consumeNav()
                        }
                    }
                }
            }
        }
    }

    private fun bind(v: View) {
        bodyText       = v.findViewById(R.id.bodyText)
        checkNowButton = v.findViewById(R.id.checkNowButton)
        resendButton   = v.findViewById(R.id.resendButton)
        signOutButton  = v.findViewById(R.id.signOutButton)
        lastSentText   = v.findViewById(R.id.lastSentText)
        errorText      = v.findViewById(R.id.errorText)
        spinner        = v.findViewById(R.id.spinner)
    }

    private fun wire() {
        checkNowButton.setOnClickListener { viewModel.checkNow() }
        resendButton.setOnClickListener { viewModel.resend() }
        signOutButton.setOnClickListener { viewModel.signOut() }
    }

    private fun render(state: EmailVerificationViewModel.UiState) {
        bodyText.text = getString(R.string.email_verification_body, state.email)

        val busy = state.isChecking || state.isResending
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        checkNowButton.isEnabled = !busy
        resendButton.isEnabled = !busy

        state.lastSentAtMs?.let { ms ->
            val elapsedSec = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
            lastSentText.visibility = View.VISIBLE
            lastSentText.text = getString(R.string.email_verification_last_sent, elapsedSec)
        } ?: run { lastSentText.visibility = View.GONE }

        val errorRes = when (state.error) {
            EmailVerifyError.NOT_YET_VERIFIED -> R.string.email_verification_not_yet
            EmailVerifyError.RATE_LIMITED     -> R.string.email_verification_rate_limited
            EmailVerifyError.NETWORK,
            EmailVerifyError.UNKNOWN          -> R.string.email_verification_network_error
            null -> null
        }
        if (errorRes != null) {
            errorText.visibility = View.VISIBLE
            errorText.setText(errorRes)
        } else {
            errorText.visibility = View.GONE
        }
    }
}
```

- [ ] **Step 2: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: actions don't exist yet → unresolved-reference for `action_emailVerification_to_splash` / `action_emailVerification_to_signIn`. Fixed in next task.

- [ ] **Step 3: Commit (still red — nav graph addition is next task's job)**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/auth/EmailVerificationFragment.kt
git commit -m "[FEAT]: EmailVerificationFragment with cooldown UI"
```

---

### Task 17: Nav graph — `emailVerificationFragment` destination + actions

**Files:**
- Modify: `android/app/src/main/res/navigation/app_nav_graph.xml`

- [ ] **Step 1: Find existing `signInFragment` / `splashFragment` destinations**

```bash
grep -n 'android:id="@+id/signInFragment\|android:id="@+id/splashFragment' android/app/src/main/res/navigation/app_nav_graph.xml
```

- [ ] **Step 2: Add the new destination + actions**

Below the existing `signInFragment` block (and `splashFragment` block), add:

```xml
<fragment
    android:id="@+id/emailVerificationFragment"
    android:name="com.albunyaan.tube.ui.auth.EmailVerificationFragment"
    android:label="EmailVerification" >
    <action
        android:id="@+id/action_emailVerification_to_splash"
        app:destination="@id/splashFragment"
        app:popUpTo="@id/emailVerificationFragment"
        app:popUpToInclusive="true" />
    <action
        android:id="@+id/action_emailVerification_to_signIn"
        app:destination="@id/signInFragment"
        app:popUpTo="@id/emailVerificationFragment"
        app:popUpToInclusive="true" />
</fragment>
```

Add inside `<fragment android:id="@+id/signInFragment">`:

```xml
<action
    android:id="@+id/action_signIn_to_emailVerification"
    app:destination="@id/emailVerificationFragment"
    app:popUpTo="@id/signInFragment"
    app:popUpToInclusive="true" />
```

- [ ] **Step 3: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/navigation/app_nav_graph.xml
git commit -m "[FEAT]: nav graph emailVerificationFragment + actions"
```

---

## Phase 5 — SignIn routing fork

### Task 18: `SignInFragment` routes to email verify when unverified

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInFragment.kt`

- [ ] **Step 1: Locate the existing post-sign-in observer**

```bash
grep -n "authRepository.authState" android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInFragment.kt
```

- [ ] **Step 2: Replace the navigation block**

Find the existing block:

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    authRepository.authState
        .filterIsInstance<AuthState.SignedIn>()
        .first()
    if (!hasNavigatedFromSignIn) {
        hasNavigatedFromSignIn = true
        val nav = findNavController()
        if (nav.currentDestination?.id == R.id.signInFragment) {
            nav.navigate(R.id.action_signIn_to_splash)
        }
    }
}
```

Replace with:

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    authRepository.authState
        .filterIsInstance<AuthState.SignedIn>()
        .first()
    if (hasNavigatedFromSignIn) return@launch
    hasNavigatedFromSignIn = true

    val user = firebaseAuth.currentUser
    val isPasswordProvider = user?.providerData
        ?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true
    val needsVerification = isPasswordProvider && user?.isEmailVerified == false

    if (needsVerification && viewModel.ui.value.mode == SignInViewModel.Mode.SIGN_UP) {
        // Fire-and-forget; the EmailVerificationFragment also sends once
        // on enter, but doing it here means the user sees the email sooner.
        try { user!!.sendEmailVerification().await() } catch (_: Exception) { /* ignored */ }
    }

    val actionId = if (needsVerification) {
        R.id.action_signIn_to_emailVerification
    } else {
        R.id.action_signIn_to_splash
    }
    val nav = findNavController()
    if (nav.currentDestination?.id == R.id.signInFragment) {
        nav.navigate(actionId)
    }
}
```

- [ ] **Step 3: Add the `await` import if not already present**

```kotlin
import kotlinx.coroutines.tasks.await
```

- [ ] **Step 4: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInFragment.kt
git commit -m "[FEAT]: SignIn routes unverified email/password to verify"
```

---

## Phase 6 — Profile state + phone editing

### Task 19: `ProfileUiState` + `ProfileViewModel` — phone + hasPasswordProvider

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/ProfileUiState.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/ProfileViewModel.kt`

- [ ] **Step 1: Extend `ProfileFields`**

```kotlin
data class ProfileFields(
    val displayName: String,
    val dateOfBirth: String?,
    val emailReadOnly: String,
    val phoneNumber: String?,
    val hasPasswordProvider: Boolean,
)
```

- [ ] **Step 2: Update `ProfileViewModel.loadFromAccount`**

Inject `FirebaseAuth` into the ViewModel:

```kotlin
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val updateRepository: AccountUpdateRepository,
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {
```

Update `loadFromAccount`:

```kotlin
private fun loadFromAccount() {
    val state = accountRepository.accountState.value
    if (state is AccountState.Loaded) {
        val hasPwd = firebaseAuth.currentUser?.providerData
            ?.any { it.providerId == com.google.firebase.auth.EmailAuthProvider.PROVIDER_ID }
            ?: false
        val fields = ProfileFields(
            displayName = state.displayName.orEmpty(),
            dateOfBirth = state.dateOfBirth,
            emailReadOnly = state.email.orEmpty(),
            phoneNumber = state.phoneNumber,
            hasPasswordProvider = hasPwd,
        )
        _uiState.value = ProfileUiState.Editing(original = fields, draft = fields)
    }
}
```

(No `phoneNumber`-edit handler here — phone edits go through the bottom sheet which calls the repository directly and triggers `accountRepository.applyProfileUpdate(response)`. The collector in `init { ... }` reads the new value back into `loadFromAccount`.)

- [ ] **Step 3: Smoke build + run existing ProfileViewModelTest**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.profile.ProfileViewModelTest"
```

If a test exists, update its fakes to include `phoneNumber` and `hasPasswordProvider` in the `ProfileFields` constructor.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/me/profile/ProfileUiState.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/profile/ProfileViewModel.kt
git commit -m "[FEAT]: ProfileFields adds phone + hasPasswordProvider"
```

---

### Task 20: `EditPhoneViewModel` + `EditPhoneBottomSheetFragment` + layout

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPhoneViewModel.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPhoneBottomSheetFragment.kt`
- Create: `android/app/src/main/res/layout/bottom_sheet_edit_phone.xml`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/me/profile/edit/EditPhoneViewModelTest.kt`
- Modify: `android/app/src/main/res/values/strings.xml` (+ ar / nl)

- [ ] **Step 1: Write failing tests**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountUpdateRepository
import com.albunyaan.tube.data.account.ProfileUpdateResult
import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditPhoneViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var ctx: Context
    private lateinit var updateRepo: AccountUpdateRepository
    private lateinit var accountRepo: AccountRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ctx = ApplicationProvider.getApplicationContext()
        updateRepo = mock()
        accountRepo = mock()
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `submit with invalid number surfaces INVALID_PHONE`() = runTest(dispatcher) {
        val vm = EditPhoneViewModel(ctx, updateRepo, accountRepo)
        vm.onCountryChanged("NL")
        vm.onNumberChanged("12345") // too short
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPhoneError.INVALID_PHONE, vm.ui.value.error)
        verifyNoInteractions(updateRepo)
    }

    @Test fun `submit happy path calls updateProfile and emits Done`() = runTest(dispatcher) {
        val response = AccountMeResponseDto(
            uid = "u1", email = "a@b.co", displayName = "Alice",
            dateOfBirth = null, phoneNumber = "+31612345678",
            status = "active", role = "user", profileCompletedAt = null)
        whenever(updateRepo.updateProfile(UpdateProfileRequestDto(phoneNumber = "+31612345678")))
            .thenReturn(ProfileUpdateResult.Success(response))

        val vm = EditPhoneViewModel(ctx, updateRepo, accountRepo)
        vm.onCountryChanged("NL")
        vm.onNumberChanged("612345678")
        vm.submit()
        advanceUntilIdle()

        verify(accountRepo).applyProfileUpdate(response)
        assertEquals(EditPhoneViewModel.Nav.Done, vm.nav.value)
    }
}
```

- [ ] **Step 2: Run tests, expect compile failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.profile.edit.EditPhoneViewModelTest"
```

Expected: FAIL — classes do not exist.

- [ ] **Step 3: Create `EditPhoneViewModel`**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.data.account.AccountUpdateRepository
import com.albunyaan.tube.data.account.ProfileUpdateResult
import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import com.albunyaan.tube.util.PhoneFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class EditPhoneError { INVALID_COUNTRY, INVALID_PHONE, NETWORK, RATE_LIMITED, UNKNOWN }

@HiltViewModel
class EditPhoneViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val updateRepository: AccountUpdateRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    data class UiState(
        val country: String? = null,
        val number: String = "",
        val saving: Boolean = false,
        val error: EditPhoneError? = null,
    )

    sealed interface Nav {
        data object Idle : Nav
        data object Done : Nav
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    fun seed(country: String?, number: String?) {
        _ui.update { it.copy(country = country, number = number.orEmpty()) }
    }

    fun onCountryChanged(c: String) = _ui.update { it.copy(country = c, error = null) }
    fun onNumberChanged(n: String)  = _ui.update { it.copy(number = n, error = null) }

    fun submit() {
        val s = _ui.value
        if (s.saving) return
        if (s.country.isNullOrBlank()) {
            _ui.update { it.copy(error = EditPhoneError.INVALID_COUNTRY) }
            return
        }
        val e164 = PhoneFormat.formatE164(appContext, s.country, s.number)
        if (e164 == null) {
            _ui.update { it.copy(error = EditPhoneError.INVALID_PHONE) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = updateRepository.updateProfile(UpdateProfileRequestDto(phoneNumber = e164))) {
                is ProfileUpdateResult.Success -> {
                    accountRepository.applyProfileUpdate(r.response)
                    _nav.value = Nav.Done
                }
                is ProfileUpdateResult.RateLimited ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.RATE_LIMITED) }
                ProfileUpdateResult.NetworkError ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.NETWORK) }
                is ProfileUpdateResult.ValidationFailed ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.INVALID_PHONE) }
                ProfileUpdateResult.AgeIneligible,
                is ProfileUpdateResult.Unknown ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.UNKNOWN) }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.profile.edit.EditPhoneViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Create the layout `bottom_sheet_edit_phone.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="@dimen/spacing_md">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/edit_phone_title"
        android:textAppearance="?attr/textAppearanceHeadline6"
        android:layout_marginBottom="@dimen/spacing_md" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/countryLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/edit_phone_country">

        <AutoCompleteTextView
            android:id="@+id/countryField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="none" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/numberLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:hint="@string/edit_phone_number">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/numberField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="phone"
            android:autofillHints="phone" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/saveButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_md"
        android:text="@string/edit_phone_save" />

    <TextView
        android:id="@+id/errorText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:textColor="?attr/colorError"
        android:visibility="gone" />

    <ProgressBar
        android:id="@+id/savingSpinner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="@dimen/spacing_sm"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 6: Create the BottomSheet fragment**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.R
import com.albunyaan.tube.util.PhoneFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class EditPhoneBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: EditPhoneViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_edit_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val countryLayout: TextInputLayout = view.findViewById(R.id.countryLayout)
        val countryField: AutoCompleteTextView = view.findViewById(R.id.countryField)
        val numberLayout: TextInputLayout = view.findViewById(R.id.numberLayout)
        val numberField: TextInputEditText = view.findViewById(R.id.numberField)
        val saveButton: MaterialButton = view.findViewById(R.id.saveButton)
        val errorText: TextView = view.findViewById(R.id.errorText)
        val spinner: ProgressBar = view.findViewById(R.id.savingSpinner)

        // Seed from arguments
        val seedCountry = arguments?.getString(ARG_COUNTRY)
        val seedNumber  = arguments?.getString(ARG_NUMBER)
        viewModel.seed(seedCountry, seedNumber)

        // Country dropdown
        val locale = requireContext().resources.configuration.locales[0]
        val rows = PhoneFormat.supportedRegions(requireContext())
            .map { iso -> iso to (Locale("", iso).getDisplayCountry(locale).ifBlank { iso }) }
            .sortedBy { it.second }
        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            rows.map { it.second },
        )
        countryField.setAdapter(adapter)
        countryField.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCountryChanged(rows[position].first)
        }
        // Pre-fill country label if seed present
        seedCountry?.let { iso ->
            val display = rows.firstOrNull { it.first == iso }?.second
            if (display != null) countryField.setText(display, /* filter */ false)
        }
        seedNumber?.let { numberField.setText(it) }

        numberField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onNumberChanged(s?.toString().orEmpty())
            }
        })

        saveButton.setOnClickListener { viewModel.submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { s ->
                    saveButton.isEnabled = !s.saving
                    spinner.visibility = if (s.saving) View.VISIBLE else View.GONE
                    val msgRes = when (s.error) {
                        EditPhoneError.INVALID_COUNTRY -> R.string.bootstrap_error_invalid_phone_country
                        EditPhoneError.INVALID_PHONE   -> R.string.bootstrap_error_invalid_phone
                        EditPhoneError.NETWORK         -> R.string.profile_error_network
                        EditPhoneError.RATE_LIMITED    -> R.string.profile_error_rate_limited_short
                        EditPhoneError.UNKNOWN, null   -> null
                    }
                    errorText.visibility = if (msgRes != null) View.VISIBLE else View.GONE
                    msgRes?.let { errorText.setText(it) }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    if (nav == EditPhoneViewModel.Nav.Done) dismiss()
                }
            }
        }
    }

    companion object {
        const val TAG = "EditPhoneBottomSheet"
        private const val ARG_COUNTRY = "country"
        private const val ARG_NUMBER  = "number"

        fun newInstance(country: String?, number: String?): EditPhoneBottomSheetFragment =
            EditPhoneBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_COUNTRY, country)
                    putString(ARG_NUMBER, number)
                }
            }
    }
}
```

- [ ] **Step 7: Add strings**

`values/strings.xml`:

```xml
<string name="edit_phone_title">Edit phone number</string>
<string name="edit_phone_country">Country</string>
<string name="edit_phone_number">Phone number</string>
<string name="edit_phone_save">Save</string>
<string name="profile_error_rate_limited_short">Too many requests. Try again soon.</string>
```

`values-ar/strings.xml`:

```xml
<string name="edit_phone_title">تعديل رقم الجوال</string>
<string name="edit_phone_country">الدولة</string>
<string name="edit_phone_number">رقم الهاتف</string>
<string name="edit_phone_save">حفظ</string>
<string name="profile_error_rate_limited_short">عدد كبير من الطلبات. حاول لاحقًا.</string>
```

`values-nl/strings.xml`:

```xml
<string name="edit_phone_title">Telefoonnummer bewerken</string>
<string name="edit_phone_country">Land</string>
<string name="edit_phone_number">Telefoonnummer</string>
<string name="edit_phone_save">Opslaan</string>
<string name="profile_error_rate_limited_short">Te veel verzoeken. Probeer het later opnieuw.</string>
```

- [ ] **Step 8: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPhoneViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPhoneBottomSheetFragment.kt \
        android/app/src/main/res/layout/bottom_sheet_edit_phone.xml \
        android/app/src/test/java/com/albunyaan/tube/ui/me/profile/edit/EditPhoneViewModelTest.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: EditPhone bottom sheet + ViewModel + tests"
```

---

## Phase 7 — Edit email

### Task 21: `EditEmailViewModel` + `EditEmailBottomSheetFragment` + layout

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditEmailViewModel.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditEmailBottomSheetFragment.kt`
- Create: `android/app/src/main/res/layout/bottom_sheet_edit_email.xml`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/me/profile/edit/EditEmailViewModelTest.kt`
- Modify: strings (en/ar/nl)

- [ ] **Step 1: Write failing tests**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditEmailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock { on { email } doReturn "old@example.com" }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.reauthenticate(any())).thenReturn(Tasks.forResult(null))
        whenever(user.verifyBeforeUpdateEmail(any())).thenReturn(Tasks.forResult(null))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `submit with malformed new email surfaces INVALID_EMAIL`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("not-an-email")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditEmailError.INVALID_EMAIL, vm.ui.value.error)
        verify(user, never()).reauthenticate(any<AuthCredential>())
    }

    @Test fun `submit wrong password surfaces WRONG_PASSWORD`() = runTest(dispatcher) {
        whenever(user.reauthenticate(any())).thenReturn(
            Tasks.forException(FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "bad"))
        )
        val vm = EditEmailViewModel(auth)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("new@example.com")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditEmailError.WRONG_PASSWORD, vm.ui.value.error)
        verify(user, never()).verifyBeforeUpdateEmail(any())
    }

    @Test fun `submit happy path emits Done`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("new@example.com")
        vm.submit()
        advanceUntilIdle()
        verify(user).verifyBeforeUpdateEmail("new@example.com")
        assertEquals(EditEmailViewModel.Nav.Done, vm.nav.value)
    }
}
```

- [ ] **Step 2: Run tests, expect compile failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.profile.edit.EditEmailViewModelTest"
```

- [ ] **Step 3: Create `EditEmailViewModel`**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.util.isEmailShape
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class EditEmailError {
    INVALID_EMAIL, WRONG_PASSWORD, EMAIL_IN_USE, NETWORK, UNKNOWN,
}

@HiltViewModel
class EditEmailViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {

    data class UiState(
        val currentPassword: String = "",
        val newEmail: String = "",
        val saving: Boolean = false,
        val error: EditEmailError? = null,
    )

    sealed interface Nav {
        data object Idle : Nav
        data object Done : Nav
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    fun onCurrentPasswordChanged(v: String) = _ui.update { it.copy(currentPassword = v, error = null) }
    fun onNewEmailChanged(v: String)        = _ui.update { it.copy(newEmail = v, error = null) }

    fun submit() {
        val s = _ui.value
        if (s.saving) return
        if (!isEmailShape(s.newEmail)) {
            _ui.update { it.copy(error = EditEmailError.INVALID_EMAIL) }
            return
        }
        val user = firebaseAuth.currentUser
        val currentEmail = user?.email
        if (user == null || currentEmail.isNullOrBlank()) {
            _ui.update { it.copy(error = EditEmailError.UNKNOWN) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                user.reauthenticate(EmailAuthProvider.getCredential(currentEmail, s.currentPassword)).await()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.WRONG_PASSWORD) }
                return@launch
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditEmailError.NETWORK) }
                return@launch
            }
            try {
                user.verifyBeforeUpdateEmail(s.newEmail).await()
                _nav.value = Nav.Done
            } catch (e: FirebaseAuthUserCollisionException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.EMAIL_IN_USE) }
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.INVALID_EMAIL) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditEmailError.NETWORK) }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.profile.edit.EditEmailViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Layout `bottom_sheet_edit_email.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="@dimen/spacing_md">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/edit_email_title"
        android:textAppearance="?attr/textAppearanceHeadline6"
        android:layout_marginBottom="@dimen/spacing_md" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/currentPasswordLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/edit_email_current_password"
        app:passwordToggleEnabled="true">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/currentPasswordField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword"
            android:autofillHints="password" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/newEmailLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:hint="@string/edit_email_new_email">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/newEmailField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textEmailAddress"
            android:autofillHints="emailAddress" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/sendButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_md"
        android:text="@string/edit_email_send" />

    <TextView
        android:id="@+id/errorText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:textColor="?attr/colorError"
        android:visibility="gone" />

    <ProgressBar
        android:id="@+id/savingSpinner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="@dimen/spacing_sm"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 6: Create `EditEmailBottomSheetFragment`**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditEmailBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: EditEmailViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.bottom_sheet_edit_email, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pwLayout: TextInputLayout    = view.findViewById(R.id.currentPasswordLayout)
        val pwField: TextInputEditText   = view.findViewById(R.id.currentPasswordField)
        val emailLayout: TextInputLayout = view.findViewById(R.id.newEmailLayout)
        val emailField: TextInputEditText = view.findViewById(R.id.newEmailField)
        val sendButton: MaterialButton   = view.findViewById(R.id.sendButton)
        val errorText: TextView          = view.findViewById(R.id.errorText)
        val spinner: ProgressBar         = view.findViewById(R.id.savingSpinner)

        pwField.addTextChangedListener(simple { viewModel.onCurrentPasswordChanged(it) })
        emailField.addTextChangedListener(simple { viewModel.onNewEmailChanged(it) })
        sendButton.setOnClickListener { viewModel.submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { s ->
                    sendButton.isEnabled = !s.saving
                    spinner.visibility = if (s.saving) View.VISIBLE else View.GONE
                    pwLayout.error    = if (s.error == EditEmailError.WRONG_PASSWORD)
                        getString(R.string.edit_email_wrong_password) else null
                    emailLayout.error = when (s.error) {
                        EditEmailError.INVALID_EMAIL -> getString(R.string.edit_email_invalid)
                        EditEmailError.EMAIL_IN_USE  -> getString(R.string.edit_email_in_use)
                        else -> null
                    }
                    if (s.error == EditEmailError.NETWORK || s.error == EditEmailError.UNKNOWN) {
                        errorText.visibility = View.VISIBLE
                        errorText.setText(R.string.profile_error_network)
                    } else {
                        errorText.visibility = View.GONE
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect {
                    if (it == EditEmailViewModel.Nav.Done) {
                        Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            getString(R.string.edit_email_sent, viewModel.ui.value.newEmail),
                            Snackbar.LENGTH_LONG,
                        ).show()
                        dismiss()
                    }
                }
            }
        }
    }

    private fun simple(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
    }

    companion object { const val TAG = "EditEmailBottomSheet" }
}
```

- [ ] **Step 7: Add strings**

`values/strings.xml`:

```xml
<string name="edit_email_title">Change email</string>
<string name="edit_email_current_password">Current password</string>
<string name="edit_email_new_email">New email</string>
<string name="edit_email_send">Send verification</string>
<string name="edit_email_sent">Verification link sent to %1$s. Tap it to complete the change. Your current email stays active until then.</string>
<string name="edit_email_wrong_password">Wrong password</string>
<string name="edit_email_invalid">Not a valid email</string>
<string name="edit_email_in_use">Email is already in use</string>
```

`values-ar/strings.xml`:

```xml
<string name="edit_email_title">تغيير البريد الإلكتروني</string>
<string name="edit_email_current_password">كلمة المرور الحالية</string>
<string name="edit_email_new_email">البريد الإلكتروني الجديد</string>
<string name="edit_email_send">إرسال رابط التحقق</string>
<string name="edit_email_sent">تم إرسال رابط التحقق إلى %1$s. اضغط عليه لإكمال التغيير. سيظل بريدك الحالي ساريًا حتى ذلك الحين.</string>
<string name="edit_email_wrong_password">كلمة المرور غير صحيحة</string>
<string name="edit_email_invalid">عنوان بريد غير صالح</string>
<string name="edit_email_in_use">البريد الإلكتروني مستخدم بالفعل</string>
```

`values-nl/strings.xml`:

```xml
<string name="edit_email_title">E-mail wijzigen</string>
<string name="edit_email_current_password">Huidig wachtwoord</string>
<string name="edit_email_new_email">Nieuwe e-mail</string>
<string name="edit_email_send">Bevestigingslink sturen</string>
<string name="edit_email_sent">Bevestigingslink verzonden naar %1$s. Klik erop om de wijziging te voltooien. Je huidige e-mail blijft tot dan actief.</string>
<string name="edit_email_wrong_password">Verkeerd wachtwoord</string>
<string name="edit_email_invalid">Geen geldig e-mailadres</string>
<string name="edit_email_in_use">E-mailadres is al in gebruik</string>
```

- [ ] **Step 8: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditEmailViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditEmailBottomSheetFragment.kt \
        android/app/src/main/res/layout/bottom_sheet_edit_email.xml \
        android/app/src/test/java/com/albunyaan/tube/ui/me/profile/edit/EditEmailViewModelTest.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: EditEmail bottom sheet + verifyBeforeUpdate"
```

---

## Phase 8 — Edit password

### Task 22: `EditPasswordViewModel` + `EditPasswordBottomSheetFragment` + layout

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPasswordViewModel.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPasswordBottomSheetFragment.kt`
- Create: `android/app/src/main/res/layout/bottom_sheet_edit_password.xml`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/me/profile/edit/EditPasswordViewModelTest.kt`
- Modify: strings

- [ ] **Step 1: Write failing tests** (mirrors EditEmail; condensed here)

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditPasswordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock { on { email } doReturn "you@example.com" }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.reauthenticate(any())).thenReturn(Tasks.forResult(null))
        whenever(user.updatePassword(any())).thenReturn(Tasks.forResult(null))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `mismatch surfaces PASSWORD_MISMATCH`() = runTest(dispatcher) {
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("12345678")
        vm.onConfirmChanged("87654321")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPasswordError.PASSWORD_MISMATCH, vm.ui.value.error)
    }

    @Test fun `too-short new surfaces WEAK_PASSWORD`() = runTest(dispatcher) {
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("short")
        vm.onConfirmChanged("short")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPasswordError.WEAK_PASSWORD, vm.ui.value.error)
    }

    @Test fun `happy path emits Done`() = runTest(dispatcher) {
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("newpassword1")
        vm.onConfirmChanged("newpassword1")
        vm.submit()
        advanceUntilIdle()
        verify(user).updatePassword("newpassword1")
        assertEquals(EditPasswordViewModel.Nav.Done, vm.nav.value)
    }

    @Test fun `wrong current password surfaces WRONG_PASSWORD`() = runTest(dispatcher) {
        whenever(user.reauthenticate(any())).thenReturn(
            Tasks.forException(FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "bad"))
        )
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("newpassword1")
        vm.onConfirmChanged("newpassword1")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPasswordError.WRONG_PASSWORD, vm.ui.value.error)
        verify(user, never()).updatePassword(any())
    }
}
```

- [ ] **Step 2: Create `EditPasswordViewModel`**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class EditPasswordError {
    WEAK_PASSWORD, PASSWORD_MISMATCH, WRONG_PASSWORD, NETWORK, UNKNOWN,
}

@HiltViewModel
class EditPasswordViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {

    data class UiState(
        val current: String = "",
        val newPassword: String = "",
        val confirm: String = "",
        val saving: Boolean = false,
        val error: EditPasswordError? = null,
    )

    sealed interface Nav { data object Idle : Nav; data object Done : Nav }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    fun onCurrentChanged(v: String) = _ui.update { it.copy(current = v, error = null) }
    fun onNewChanged(v: String)     = _ui.update { it.copy(newPassword = v, error = null) }
    fun onConfirmChanged(v: String) = _ui.update { it.copy(confirm = v, error = null) }

    fun submit() {
        val s = _ui.value
        if (s.saving) return
        if (s.newPassword.length < MIN_PASSWORD_LENGTH) {
            _ui.update { it.copy(error = EditPasswordError.WEAK_PASSWORD) }
            return
        }
        if (s.newPassword != s.confirm) {
            _ui.update { it.copy(error = EditPasswordError.PASSWORD_MISMATCH) }
            return
        }
        val user = firebaseAuth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) {
            _ui.update { it.copy(error = EditPasswordError.UNKNOWN) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                user.reauthenticate(EmailAuthProvider.getCredential(email, s.current)).await()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.WRONG_PASSWORD) }
                return@launch
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.NETWORK) }
                return@launch
            }
            try {
                user.updatePassword(s.newPassword).await()
                _nav.value = Nav.Done
            } catch (e: FirebaseAuthWeakPasswordException) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.WEAK_PASSWORD) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.NETWORK) }
            }
        }
    }

    companion object { const val MIN_PASSWORD_LENGTH = 8 }
}
```

- [ ] **Step 3: Run tests, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.profile.edit.EditPasswordViewModelTest"
```

- [ ] **Step 4: Layout `bottom_sheet_edit_password.xml`**

Same structure as the email sheet but three password fields:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="@dimen/spacing_md">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/edit_password_title"
        android:textAppearance="?attr/textAppearanceHeadline6"
        android:layout_marginBottom="@dimen/spacing_md" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/currentLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/edit_password_current"
        app:passwordToggleEnabled="true">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/currentField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword"
            android:autofillHints="password" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/newLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:hint="@string/edit_password_new"
        app:passwordToggleEnabled="true">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/newField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword"
            android:autofillHints="newPassword" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/confirmLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:hint="@string/edit_password_confirm"
        app:passwordToggleEnabled="true">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/confirmField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword"
            android:autofillHints="newPassword" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/updateButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_md"
        android:text="@string/edit_password_update" />

    <TextView
        android:id="@+id/errorText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_sm"
        android:textColor="?attr/colorError"
        android:visibility="gone" />

    <ProgressBar
        android:id="@+id/savingSpinner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="@dimen/spacing_sm"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 5: Create `EditPasswordBottomSheetFragment`**

```kotlin
package com.albunyaan.tube.ui.me.profile.edit

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.albunyaan.tube.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditPasswordBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: EditPasswordViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.bottom_sheet_edit_password, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentLayout: TextInputLayout = view.findViewById(R.id.currentLayout)
        val currentField:  TextInputEditText = view.findViewById(R.id.currentField)
        val newLayout: TextInputLayout = view.findViewById(R.id.newLayout)
        val newField:  TextInputEditText = view.findViewById(R.id.newField)
        val confirmLayout: TextInputLayout = view.findViewById(R.id.confirmLayout)
        val confirmField:  TextInputEditText = view.findViewById(R.id.confirmField)
        val updateButton: MaterialButton = view.findViewById(R.id.updateButton)
        val errorText: TextView = view.findViewById(R.id.errorText)
        val spinner: ProgressBar = view.findViewById(R.id.savingSpinner)

        currentField.addTextChangedListener(simple { viewModel.onCurrentChanged(it) })
        newField.addTextChangedListener(simple { viewModel.onNewChanged(it) })
        confirmField.addTextChangedListener(simple { viewModel.onConfirmChanged(it) })
        updateButton.setOnClickListener { viewModel.submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { s ->
                    updateButton.isEnabled = !s.saving
                    spinner.visibility = if (s.saving) View.VISIBLE else View.GONE
                    currentLayout.error = if (s.error == EditPasswordError.WRONG_PASSWORD)
                        getString(R.string.edit_password_wrong_current) else null
                    newLayout.error = if (s.error == EditPasswordError.WEAK_PASSWORD)
                        getString(R.string.edit_password_weak) else null
                    confirmLayout.error = if (s.error == EditPasswordError.PASSWORD_MISMATCH)
                        getString(R.string.edit_password_mismatch) else null
                    if (s.error == EditPasswordError.NETWORK || s.error == EditPasswordError.UNKNOWN) {
                        errorText.visibility = View.VISIBLE
                        errorText.setText(R.string.profile_error_network)
                    } else errorText.visibility = View.GONE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect {
                    if (it == EditPasswordViewModel.Nav.Done) {
                        Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            R.string.edit_password_updated,
                            Snackbar.LENGTH_SHORT,
                        ).show()
                        dismiss()
                    }
                }
            }
        }
    }

    private fun simple(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
    }

    companion object { const val TAG = "EditPasswordBottomSheet" }
}
```

- [ ] **Step 6: Add strings**

`values/strings.xml`:

```xml
<string name="edit_password_title">Change password</string>
<string name="edit_password_current">Current password</string>
<string name="edit_password_new">New password</string>
<string name="edit_password_confirm">Confirm new password</string>
<string name="edit_password_update">Update password</string>
<string name="edit_password_updated">Password updated.</string>
<string name="edit_password_wrong_current">Wrong current password</string>
<string name="edit_password_weak">Use at least 8 characters</string>
<string name="edit_password_mismatch">Passwords don\'t match</string>
```

`values-ar/strings.xml`:

```xml
<string name="edit_password_title">تغيير كلمة المرور</string>
<string name="edit_password_current">كلمة المرور الحالية</string>
<string name="edit_password_new">كلمة المرور الجديدة</string>
<string name="edit_password_confirm">تأكيد كلمة المرور الجديدة</string>
<string name="edit_password_update">تحديث كلمة المرور</string>
<string name="edit_password_updated">تم تحديث كلمة المرور.</string>
<string name="edit_password_wrong_current">كلمة المرور الحالية غير صحيحة</string>
<string name="edit_password_weak">استخدم 8 أحرف على الأقل</string>
<string name="edit_password_mismatch">كلمتا المرور غير متطابقتين</string>
```

`values-nl/strings.xml`:

```xml
<string name="edit_password_title">Wachtwoord wijzigen</string>
<string name="edit_password_current">Huidig wachtwoord</string>
<string name="edit_password_new">Nieuw wachtwoord</string>
<string name="edit_password_confirm">Bevestig nieuw wachtwoord</string>
<string name="edit_password_update">Wachtwoord bijwerken</string>
<string name="edit_password_updated">Wachtwoord bijgewerkt.</string>
<string name="edit_password_wrong_current">Verkeerd huidig wachtwoord</string>
<string name="edit_password_weak">Gebruik minimaal 8 tekens</string>
<string name="edit_password_mismatch">Wachtwoorden komen niet overeen</string>
```

- [ ] **Step 7: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPasswordViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/profile/edit/EditPasswordBottomSheetFragment.kt \
        android/app/src/main/res/layout/bottom_sheet_edit_password.xml \
        android/app/src/test/java/com/albunyaan/tube/ui/me/profile/edit/EditPasswordViewModelTest.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: EditPassword bottom sheet + reauth + tests"
```

---

## Phase 9 — Profile layout: phone row + edit buttons

### Task 23: Extend `fragment_profile.xml` and wire `ProfileFragment`

**Files:**
- Modify: `android/app/src/main/res/layout/fragment_profile.xml`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/profile/ProfileFragment.kt`
- Modify: strings

- [ ] **Step 1: Add strings**

`values/strings.xml`:

```xml
<string name="profile_phone">Phone number</string>
<string name="profile_phone_unset">Not set</string>
<string name="profile_edit">Edit</string>
<string name="profile_add">Add</string>
<string name="profile_password">Password</string>
<string name="profile_password_dots">••••••••••</string>
```

`values-ar/strings.xml`:

```xml
<string name="profile_phone">رقم الجوال</string>
<string name="profile_phone_unset">غير محدد</string>
<string name="profile_edit">تعديل</string>
<string name="profile_add">إضافة</string>
<string name="profile_password">كلمة المرور</string>
<string name="profile_password_dots">••••••••••</string>
```

`values-nl/strings.xml`:

```xml
<string name="profile_phone">Telefoonnummer</string>
<string name="profile_phone_unset">Niet ingesteld</string>
<string name="profile_edit">Bewerken</string>
<string name="profile_add">Toevoegen</string>
<string name="profile_password">Wachtwoord</string>
<string name="profile_password_dots">••••••••••</string>
```

- [ ] **Step 2: Extend `fragment_profile.xml`**

In the existing `personalInfoCard`'s inner LinearLayout, replace the locked-email block:

```xml
<!-- BEFORE: locked email block -->
<LinearLayout ...>
    <TextView android:id="@+id/emailLabel" .../>
    <TextView android:text="@string/profile_email_locked" .../>
</LinearLayout>
```

with three edit-capable rows:

```xml
<!-- Email row (with Edit button) -->
<LinearLayout
    android:id="@+id/emailRow"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="@dimen/spacing_md">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_weight="1"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodySmall"
            android:textColor="?android:attr/textColorSecondary"
            android:text="@string/profile_email_label"
            tools:text="Email" />
        <TextView
            android:id="@+id/emailValue"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodyMedium"
            tools:text="you@example.com" />
    </LinearLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/emailEditButton"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/profile_edit" />
</LinearLayout>

<View android:layout_width="match_parent"
      android:layout_height="@dimen/divider_thickness"
      android:background="@color/background_gray"
      android:layout_marginStart="@dimen/spacing_lg" />

<!-- Phone row -->
<LinearLayout
    android:id="@+id/phoneRow"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="@dimen/spacing_md">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_weight="1"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodySmall"
            android:textColor="?android:attr/textColorSecondary"
            android:text="@string/profile_phone" />
        <TextView
            android:id="@+id/phoneValue"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodyMedium"
            tools:text="+31 6 12345678" />
    </LinearLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/phoneEditButton"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/profile_edit" />
</LinearLayout>

<View android:layout_width="match_parent"
      android:layout_height="@dimen/divider_thickness"
      android:background="@color/background_gray"
      android:layout_marginStart="@dimen/spacing_lg" />

<!-- Password row (visibility=gone for non-password providers) -->
<LinearLayout
    android:id="@+id/passwordRow"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="@dimen/spacing_md"
    android:visibility="gone">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_weight="1"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodySmall"
            android:textColor="?android:attr/textColorSecondary"
            android:text="@string/profile_password" />
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodyMedium"
            android:text="@string/profile_password_dots" />
    </LinearLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/passwordEditButton"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/profile_edit" />
</LinearLayout>
```

Add `profile_email_label` to strings (en/ar/nl) — value "Email" / "البريد الإلكتروني" / "E-mail".

- [ ] **Step 3: Wire `ProfileFragment` — add edit buttons + sheet launchers**

In `ProfileFragment.kt`, add to `onViewCreated`:

```kotlin
binding.emailEditButton.setOnClickListener {
    com.albunyaan.tube.ui.me.profile.edit.EditEmailBottomSheetFragment().show(
        parentFragmentManager,
        com.albunyaan.tube.ui.me.profile.edit.EditEmailBottomSheetFragment.TAG,
    )
}
binding.phoneEditButton.setOnClickListener {
    val ctx = requireContext()
    val current = (vm.uiState.value as? ProfileUiState.Editing)?.draft?.phoneNumber
    val parsed = current?.let { com.albunyaan.tube.util.PhoneFormat.parseDisplay(ctx, it) }
    com.albunyaan.tube.ui.me.profile.edit.EditPhoneBottomSheetFragment
        .newInstance(country = parsed?.first, number = parsed?.second)
        .show(parentFragmentManager,
              com.albunyaan.tube.ui.me.profile.edit.EditPhoneBottomSheetFragment.TAG)
}
binding.passwordEditButton.setOnClickListener {
    com.albunyaan.tube.ui.me.profile.edit.EditPasswordBottomSheetFragment().show(
        parentFragmentManager,
        com.albunyaan.tube.ui.me.profile.edit.EditPasswordBottomSheetFragment.TAG,
    )
}
```

In `render()` — `is ProfileUiState.Editing` branch — replace the line `binding.emailLabel.text = state.draft.emailReadOnly` with:

```kotlin
binding.emailValue.text = state.draft.emailReadOnly
binding.phoneValue.text = state.draft.phoneNumber
    ?: getString(R.string.profile_phone_unset)
binding.phoneEditButton.setText(
    if (state.draft.phoneNumber.isNullOrBlank()) R.string.profile_add
    else R.string.profile_edit
)
binding.passwordRow.visibility =
    if (state.draft.hasPasswordProvider) View.VISIBLE else View.GONE
```

(Delete the prior `binding.emailLabel` reference — that view ID no longer exists.)

- [ ] **Step 4: Smoke build**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run full Android unit test suite**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/res/layout/fragment_profile.xml \
        android/app/src/main/java/com/albunyaan/tube/ui/me/profile/ProfileFragment.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: Personal Info edit rows for email/phone/password"
```

---

## Phase 10 — Final verification

### Task 24: Full build + lint + on-device smoke

**Files:** none — verification only.

- [ ] **Step 1: Backend full test**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Android full unit test**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Android assemble release (R8 shake validation for libphonenumber)**

```bash
cd android && ./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. Check the resulting APK size diff vs. previous beta (`ls -la android/app/build/outputs/apk/release/`); expect ~150KB increase from libphonenumber.

- [ ] **Step 4: Android lint**

```bash
cd android && ./gradlew :app:lintDebug
```

Expected: no new errors. Existing warnings acceptable.

- [ ] **Step 5: Manual device smoke (per CLAUDE.md device matrix)**

Devices: Samsung S25 Ultra (Android 15), Huawei Honor Play (Android 14), Pixel Tablet AVD, Android TV AVD.
Locales: English, Arabic, Dutch.

Test flows (mark each ✓ or ✗):
- [ ] Email/password sign-up → EmailVerification screen appears → resend works → check email in inbox → click link → "I've verified" → bootstrap → fill phone (NL +31 6...) → main shell.
- [ ] Google sign-up → no verify screen → bootstrap → fill phone → main shell.
- [ ] Email/password sign-in with unverified email → verify screen → "Use different email" → back to sign-in.
- [ ] Profile → Edit phone → wrong number rejected → valid number → toast → phone row updates.
- [ ] Profile → Edit email → wrong password → inline error → correct password → "Verification sent" snackbar → check inbox → click link → re-launch app → /me reports new email.
- [ ] Profile → Edit password → wrong current → inline error → correct → toast.
- [ ] Profile (Google-only user) → Password row hidden, only Edit email / phone visible.
- [ ] Arabic locale on S25 Ultra: country dropdown RTL, sheet layouts mirror, no overflow.
- [ ] Tablet sw600dp: bootstrap form centered, phone row visible.
- [ ] TV sw720dp: focus traversal lands on phone fields after DOB; D-pad-friendly.

- [ ] **Step 6: Push develop branch**

(Per CLAUDE.md branching policy: stay on `develop`, never push to `main`.)

```bash
git push origin develop
```

---

## Self-Review

**1. Spec coverage check:**

- Spec §1 — phone field, libphonenumber, no OTP → Tasks 1–13 ✓
- Spec §2 — hard email gate for password sign-ups → Tasks 14–18 ✓
- Spec §3 — profile-edit flows (email / pw / phone) → Tasks 19–23 ✓
- Spec §4 (architecture: client-side gate, no new backend state) → Task 18 ✓ (no PENDING_EMAIL_VERIFICATION state added on backend)
- Spec §5 backend changes → Tasks 1–4 ✓
- Spec §6 libphonenumber dep → Task 5 ✓
- Spec §7 strings — covered piecemeal in Tasks 12, 15, 20, 21, 22, 23 ✓
- Spec §8 Q2 (existing-user backfill) — default "no" per commit message; not implemented (intentional).
- Spec §8 Q4 (OAuth-only re-auth for email change) — deferred per commit message; Task 23 shows `passwordRow.visibility = gone` for non-password providers, hiding the affordance.
- Spec §9 test plan — backend tests in Tasks 3–4, Android VM tests in Tasks 6, 7, 11, 14, 20, 21, 22; manual device matrix in Task 24.

**2. Placeholder scan:** every step has executable code or a commit command. No "TBD"/"TODO" inside the plan body. Open spec questions are tracked but not labeled TBD in code.

**3. Type consistency:**
- `phoneNumber: String` in backend DTOs (NotBlank where required, nullable on update); `phoneNumber: String` / `String?` in Kotlin DTOs; `phoneNumber: String?` on `AccountState.Loaded` and `ProfileFields` (null for legacy users) — consistent.
- `EditPhoneError`, `EditEmailError`, `EditPasswordError`, `EmailVerifyError` defined and used identically by name in their respective ViewModels and Fragments.
- `formatE164(ctx, region, national)` and `parseDisplay(ctx, e164)` reused unchanged across Bootstrap and EditPhone sheet.
- `applyProfileUpdate(response: AccountMeResponseDto)` consumed by EditPhone exactly as it is by displayName/DOB edits in `ProfileViewModel.save`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-25-onboarding-phone-and-edit.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
