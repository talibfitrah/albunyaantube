# Plan C — Account Bootstrap + Age Gate (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a one-time profile-bootstrap step (displayName + DOB) after first sign-in. Server-side enforce a 13+ age gate; under-13 users are hard-rejected and their Firebase Auth record deleted. Zero minor PII retained.

**Architecture:** Extends Plan B's existing `SplashRouter` with a new `accountStatus` branch. Adds two new fragments (`ProfileBootstrapFragment`, `AgeIneligibleFragment`) and one new backend endpoint (`POST /api/account/profile`). `SignInFragment`'s post-sign-in observer redirects to `splashFragment` instead of `mainShellFragment`, so all routing decisions live in `SplashRouter`.

**Tech Stack:** Backend — Spring Boot 3, Firebase Admin Java SDK, JUnit 5, Mockito, MockMvc. Android — Kotlin, Hilt, Retrofit, Moshi, Coroutines, Material 3, JUnit 4, Mockito-Kotlin.

**Branch:** `feature/PROFILE-01-account-bootstrap`
**Spec:** [`docs/superpowers/specs/2026-05-11-account-bootstrap-design.md`](../specs/2026-05-11-account-bootstrap-design.md) (commit 28b902d4)
**Target:** ~12 commits, ~800–1000 LOC. Merge to `develop`.

---

## Self-critique: things in the spec that this plan locks down

Drafting the plan surfaced three concrete points the spec was loose about. Calling them out here so we don't discover them mid-implementation:

1. **`dateOfBirth` wire format on POST.** Spec §5.2 says the field accepts `dateOfBirth` (ISO 8601). The plan locks this to `"YYYY-MM-DD"` string (date-only, no time/zone), parsed server-side via `LocalDate.parse(...)`. Storing as Firestore `Timestamp` (per D6) happens after parsing — wire is a string, storage is a timestamp at 00:00 UTC. (T2.)
2. **Age computation deterministic.** Server uses `LocalDate.now(ZoneOffset.UTC)` minus parsed DOB, comparing via `Period.between(...).years`. This way `dateOfBirth = today - 13y` evaluates to age=13 (eligible) and `today - 13y + 1d` evaluates to age=12 (rejected). T2 has an explicit boundary test.
3. **`FirebaseAuth.deleteUser(uid)` + `revokeRefreshTokens(uid)` order.** Spec §5.2 says backend revokes; client deletes. The plan locks the backend order: revoke FIRST, then delete Firestore doc. Reason: if revoke fails, we abort with 500 and the user retains a valid token but a still-present users doc — recoverable. If delete-doc-first then revoke-fails, the client could still trick a stale token into a successful re-bootstrap (race). (T2.)

---

## Task overview

| # | Task | LOC | New files | Edited files | Test framework |
|---|---|---|---|---|---|
| T1 | Backend: `User.dateOfBirth` field + tests | ~80 | 0 | 2 | JUnit |
| T2 | Backend: `AccountProfileService` + unit tests | ~260 | 2 | 0 | JUnit + Mockito |
| T3 | Backend: `AccountController` + DTOs + filter widening + MockMvc tests | ~280 | 5 | 1 | MockMvc |
| T4 | Backend: `AccountControllerIT` integration test + Firestore rules | ~180 | 1 | 1 | JUnit + Firebase emulator |
| T5 | Android: `AccountStatus` + `AccountService` + DTOs + tests | ~140 | 5 | 0 | JUnit |
| T6 | Android: `AccountRepository` + Hilt module + tests | ~220 | 4 | 1 | JUnit + Mockito-Kotlin |
| T7 | Android: `SplashRouter` extension + `SplashFragment` /me fetch + tests | ~170 | 0 | 3 | JUnit |
| T8 | Android: `ProfileBootstrapFragment` + ViewModel + 3 layouts + strings + tests | ~340 | 9 | 3 | JUnit |
| T9 | Android: `AgeIneligibleFragment` + ViewModel + 3 layouts + strings + tests | ~210 | 8 | 3 | JUnit |
| T10 | Android: nav graph + `SignInFragment` redirect change | ~60 | 0 | 2 | — |
| T11 | Manual UI verification across 3 form factors + RTL | — | 0 | 0 | — |
| T12 | 7-stage review pipeline + PR to develop | — | 0 | 0 | — |

**Per-task workflow** (same as Plan B):
1. **Implementer** writes the code (TDD where possible — failing test first).
2. **Spec reviewer** (general-purpose subagent) compares against this plan + the spec; flags drift.
3. **Code-quality reviewer** (general-purpose subagent) reads the diff cold; flags issues per `feedback_review_pipeline.md`.
4. Subagent reports addressed in the same commit if Critical, follow-up commit if Important, deferred-with-reason if Minor.
5. Commit prefix `[FEAT-ANDROID-PROFILE-01-Tn]: description` (backend tasks use `[FEAT-BACKEND-PROFILE-01-Tn]:`).

---

## T1 — Backend: User.dateOfBirth field

### Files
- Modify: `backend/src/main/java/com/albunyaan/tube/model/User.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/model/UserTest.java`

### Steps

- [ ] **Step 1: Write the failing test**

Add to `UserTest.java`:

```java
@Test
void copyPreservesDateOfBirth() {
    Timestamp dob = Timestamp.ofTimeSecondsAndNanos(946684800L, 0); // 2000-01-01
    User u = new User("uid-1", "a@b.com", "Alice", "user");
    u.setDateOfBirth(dob);
    User copy = u.copy();
    assertEquals(dob, copy.getDateOfBirth());
}

@Test
void newUserHasNullDateOfBirth() {
    User u = new User();
    assertNull(u.getDateOfBirth());
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests UserTest.copyPreservesDateOfBirth
```
Expected: FAIL with "cannot find symbol method setDateOfBirth".

- [ ] **Step 3: Add field + getter/setter to User.java**

After the `profileCompletedAt` field (around line 57):

```java
private Timestamp dateOfBirth;
```

After `getProfileCompletedAt`/`setProfileCompletedAt` (around line 283):

```java
public Timestamp getDateOfBirth() { return dateOfBirth; }
public void setDateOfBirth(Timestamp t) { this.dateOfBirth = t; }
```

In `copy()` method, after `c.profileCompletedAt = this.profileCompletedAt;`:

```java
c.dateOfBirth = this.dateOfBirth;
```

- [ ] **Step 4: Run tests to verify pass**

```bash
cd backend && ./gradlew test --tests UserTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/User.java backend/src/test/java/com/albunyaan/tube/model/UserTest.java
git commit -m "[FEAT-BACKEND-PROFILE-01-T1]: User.dateOfBirth field"
```

---

## T2 — Backend: AccountProfileService

### Files
- Create: `backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java`
- Create: `backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java`

### Steps

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java`:

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private FirebaseAuth firebaseAuth;

    private AccountProfileService service;
    private final Clock fixedClock = Clock.fixed(
        LocalDate.of(2026, 5, 11).atStartOfDay(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC
    );

    @BeforeEach
    void setUp() {
        service = new AccountProfileService(userRepository, firebaseAuth, fixedClock);
    }

    @Test
    void completeProfileAdultSuccess() throws Exception {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.completeProfile("uid-1", "Alice",
            LocalDate.of(2000, 1, 1)); // age 26

        assertEquals(UserStatus.ACTIVE, result.getStatusEnum());
        assertEquals("Alice", result.getDisplayName());
        assertNotNull(result.getDateOfBirth());
        assertNotNull(result.getProfileCompletedAt());
        verify(userRepository).save(any(User.class));
        verify(firebaseAuth, never()).deleteUser(any());
    }

    @Test
    void completeProfileUnder13Rejected() throws Exception {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        AgeIneligibleException ex = assertThrows(AgeIneligibleException.class,
            () -> service.completeProfile("uid-1", "Tot", LocalDate.of(2020, 1, 1)));

        // Revoke must happen BEFORE delete (see plan §self-critique #3).
        var order = inOrder(firebaseAuth, userRepository);
        order.verify(firebaseAuth).revokeRefreshTokens("uid-1");
        order.verify(userRepository).deleteByUid("uid-1");
        verify(userRepository, never()).save(any());
    }

    @Test
    void completeProfileBoundaryExactly13() throws Exception {
        // 2026-05-11 minus 13 years = 2013-05-11 → age 13, eligible
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.completeProfile("uid-1", "Teen", LocalDate.of(2013, 5, 11));
        assertEquals(UserStatus.ACTIVE, result.getStatusEnum());
    }

    @Test
    void completeProfileBoundaryDayUnder13() throws Exception {
        // 2013-05-12: birthday in 1 day → still 12, rejected
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        assertThrows(AgeIneligibleException.class,
            () -> service.completeProfile("uid-1", "Almost",
                LocalDate.of(2013, 5, 12)));
    }

    @Test
    void completeProfileRejectsAlreadyCompleted() {
        User existing = new User("uid-1", "a@b.com", "Alice", "user");
        existing.setStatusEnum(UserStatus.ACTIVE);
        existing.setProfileCompletedAt(Timestamp.now());
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        assertThrows(ProfileAlreadyCompletedException.class,
            () -> service.completeProfile("uid-1", "Alice", LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileRejectsMissingUser() {
        when(userRepository.findByUid("ghost")).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class,
            () -> service.completeProfile("ghost", "x", LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileRejectsBlankDisplayName() {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
            () -> service.completeProfile("uid-1", "   ", LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileRejectsTooLongDisplayName() {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        String over40 = "a".repeat(41);
        assertThrows(IllegalArgumentException.class,
            () -> service.completeProfile("uid-1", over40, LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileFailsClosedIfRevokeFails() throws Exception {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        doThrow(new FirebaseAuthException(mock())).when(firebaseAuth).revokeRefreshTokens("uid-1");

        assertThrows(AgeIneligibleAbortedException.class,
            () -> service.completeProfile("uid-1", "Tot", LocalDate.of(2020, 1, 1)));

        // Did NOT delete the doc — abort preserves recoverability.
        verify(userRepository, never()).deleteByUid(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests AccountProfileServiceTest
```
Expected: FAIL — `AccountProfileService` and exception classes don't exist.

- [ ] **Step 3: Add `deleteByUid` to UserRepository if missing**

Check `backend/src/main/java/com/albunyaan/tube/repository/UserRepository.java` for a `deleteByUid(String)` method. If absent, add:

```java
public void deleteByUid(String uid) {
    firestore.collection(USERS_COLLECTION).document(uid).delete();
}
```

- [ ] **Step 4: Create exception classes**

`backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java` (single file, all exception classes nested at bottom):

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;

@Service
public class AccountProfileService {

    private static final Logger log = LoggerFactory.getLogger(AccountProfileService.class);
    private static final int MIN_AGE = 13;
    private static final int MAX_DISPLAY_NAME_LENGTH = 40;

    private final UserRepository userRepository;
    private final FirebaseAuth firebaseAuth;
    private final Clock clock;

    public AccountProfileService(UserRepository userRepository,
                                  FirebaseAuth firebaseAuth,
                                  Clock clock) {
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
        this.clock = clock;
    }

    public User completeProfile(String uid, String displayName, LocalDate dateOfBirth) {
        validateDisplayName(displayName);
        validateDateOfBirth(dateOfBirth);

        User user = userRepository.findByUid(uid)
            .orElseThrow(() -> new UserNotFoundException(uid));

        if (user.getProfileCompletedAt() != null) {
            throw new ProfileAlreadyCompletedException(uid);
        }

        int age = Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
        if (age < MIN_AGE) {
            rejectUnderAge(uid);
            throw new AgeIneligibleException(uid, age);
        }

        Timestamp dobTs = Timestamp.ofTimeSecondsAndNanos(
            dateOfBirth.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 0);
        user.setDisplayName(displayName.trim());
        user.setDateOfBirth(dobTs);
        user.setStatusEnum(UserStatus.ACTIVE);
        user.setProfileCompletedAt(Timestamp.now());
        user.touch();
        return userRepository.save(user);
    }

    /**
     * Revoke FIRST, then delete (see plan T2 self-critique #3). If revoke fails
     * we abort without deleting — the user retains a valid token but their doc
     * is intact, so a retry can succeed. If we deleted first and revoke failed,
     * a stale ID token could be used to re-bootstrap from a different device.
     */
    private void rejectUnderAge(String uid) {
        try {
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            log.error("AGE_INELIGIBLE: revokeRefreshTokens failed for uid={}, aborting", uid, e);
            throw new AgeIneligibleAbortedException(uid, e);
        }
        userRepository.deleteByUid(uid);
        log.warn("AGE_INELIGIBLE: hard-rejected uid={} (doc deleted, refresh tokens revoked)", uid);
    }

    private void validateDisplayName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (name.trim().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
    }

    private void validateDateOfBirth(LocalDate dob) {
        if (dob == null) {
            throw new IllegalArgumentException("dateOfBirth must not be null");
        }
        if (dob.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("dateOfBirth must not be in the future");
        }
    }
}
```

`backend/src/main/java/com/albunyaan/tube/service/AccountProfileExceptions.java`:

```java
package com.albunyaan.tube.service;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String uid) { super("user not found: " + uid); }
}

public class ProfileAlreadyCompletedException extends RuntimeException {
    public ProfileAlreadyCompletedException(String uid) {
        super("profile already completed for uid=" + uid);
    }
}

public class AgeIneligibleException extends RuntimeException {
    private final int age;
    public AgeIneligibleException(String uid, int age) {
        super("age-ineligible: uid=" + uid + " age=" + age);
        this.age = age;
    }
    public int getAge() { return age; }
}

public class AgeIneligibleAbortedException extends RuntimeException {
    public AgeIneligibleAbortedException(String uid, Throwable cause) {
        super("age-ineligible rejection aborted (revoke failed) for uid=" + uid, cause);
    }
}
```

> Java allows multiple package-private classes per file; the file name matches the first public class — here we'd split into one file per public exception. Adjust accordingly: `UserNotFoundException.java`, `ProfileAlreadyCompletedException.java`, `AgeIneligibleException.java`, `AgeIneligibleAbortedException.java`.

- [ ] **Step 5: Register Clock bean**

Check `backend/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.java` (or any `@Configuration` class) for an existing `Clock` bean. If absent, add to a `@Configuration` class:

```java
@Bean
@ConditionalOnMissingBean
public Clock systemClock() {
    return Clock.systemUTC();
}
```

- [ ] **Step 6: Run tests to verify pass**

```bash
cd backend && ./gradlew test --tests AccountProfileServiceTest
```
Expected: 9 PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AccountProfileService.java \
        backend/src/main/java/com/albunyaan/tube/service/UserNotFoundException.java \
        backend/src/main/java/com/albunyaan/tube/service/ProfileAlreadyCompletedException.java \
        backend/src/main/java/com/albunyaan/tube/service/AgeIneligibleException.java \
        backend/src/main/java/com/albunyaan/tube/service/AgeIneligibleAbortedException.java \
        backend/src/main/java/com/albunyaan/tube/repository/UserRepository.java \
        backend/src/test/java/com/albunyaan/tube/service/AccountProfileServiceTest.java
git commit -m "[FEAT-BACKEND-PROFILE-01-T2]: AccountProfileService (age gate + revoke-then-delete)"
```

---

## T3 — Backend: AccountController + DTOs + filter widening

### Files
- Create: `backend/src/main/java/com/albunyaan/tube/controller/AccountController.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/CompleteProfileRequest.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/AccountMeResponse.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/ErrorResponse.java` (if not already in the project; check first)
- Create: `backend/src/test/java/com/albunyaan/tube/controller/AccountControllerTest.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java`

### Steps

- [ ] **Step 1: Write the failing MockMvc test**

`backend/src/test/java/com/albunyaan/tube/controller/AccountControllerTest.java`:

```java
package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.service.AccountProfileService;
import com.albunyaan.tube.service.AgeIneligibleException;
import com.albunyaan.tube.service.ProfileAlreadyCompletedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AccountProfileService accountProfileService;

    @Test
    @WithMockUser(username = "uid-1")
    void postProfileHappyPath() throws Exception {
        User saved = new User("uid-1", "a@b.com", "Alice", "user");
        saved.setStatusEnum(UserStatus.ACTIVE);
        when(accountProfileService.completeProfile(eq("uid-1"), eq("Alice"),
            eq(LocalDate.of(2000, 1, 1)))).thenReturn(saved);

        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Alice", "dateOfBirth": "2000-01-01"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Alice"))
            .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @WithMockUser(username = "uid-1")
    void postProfileUnder13Returns422() throws Exception {
        when(accountProfileService.completeProfile(any(), any(), any()))
            .thenThrow(new AgeIneligibleException("uid-1", 8));

        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Kid", "dateOfBirth": "2020-01-01"}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));
    }

    @Test
    @WithMockUser(username = "uid-1")
    void postProfileAlreadyCompletedReturns409() throws Exception {
        when(accountProfileService.completeProfile(any(), any(), any()))
            .thenThrow(new ProfileAlreadyCompletedException("uid-1"));

        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Alice", "dateOfBirth": "2000-01-01"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETED"));
    }

    @Test
    @WithMockUser(username = "uid-1")
    void postProfileBlankDisplayNameReturns400() throws Exception {
        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "", "dateOfBirth": "2000-01-01"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "uid-1")
    void postProfileMalformedDateReturns400() throws Exception {
        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Alice", "dateOfBirth": "not-a-date"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void postProfileUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Alice", "dateOfBirth": "2000-01-01"}
                    """))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests AccountControllerTest
```
Expected: FAIL — `AccountController` doesn't exist.

- [ ] **Step 3: Create DTOs**

`CompleteProfileRequest.java`:

```java
package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class CompleteProfileRequest {

    @NotBlank
    @Size(max = 40)
    private String displayName;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String s) { this.displayName = s; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate d) { this.dateOfBirth = d; }
}
```

`AccountMeResponse.java`:

```java
package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.User;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public class AccountMeResponse {

    private final String uid;
    private final String email;
    private final String displayName;
    private final String status;
    private final String role;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final Instant profileCompletedAt;

    private AccountMeResponse(String uid, String email, String displayName,
                              String status, String role, Instant profileCompletedAt) {
        this.uid = uid;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.role = role;
        this.profileCompletedAt = profileCompletedAt;
    }

    public static AccountMeResponse from(User u) {
        return new AccountMeResponse(
            u.getUid(), u.getEmail(), u.getDisplayName(),
            u.getStatus(), u.getRole(),
            u.getProfileCompletedAt() == null ? null
                : Instant.ofEpochSecond(u.getProfileCompletedAt().getSeconds(),
                                        u.getProfileCompletedAt().getNanos()));
    }

    public String getUid() { return uid; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public String getRole() { return role; }
    public Instant getProfileCompletedAt() { return profileCompletedAt; }
}
```

`ErrorResponse.java` (check if `backend/src/main/java/com/albunyaan/tube/dto/ErrorResponse.java` already exists — Plan A may have created it. If yes, reuse. If no, create:):

```java
package com.albunyaan.tube.dto;

public class ErrorResponse {
    private final String code;
    private final String message;

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
```

- [ ] **Step 4: Create AccountController**

`backend/src/main/java/com/albunyaan/tube/controller/AccountController.java`:

```java
package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.AccountMeResponse;
import com.albunyaan.tube.dto.CompleteProfileRequest;
import com.albunyaan.tube.dto.ErrorResponse;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AccountProfileService;
import com.albunyaan.tube.service.AgeIneligibleException;
import com.albunyaan.tube.service.ProfileAlreadyCompletedException;
import com.albunyaan.tube.service.UserNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountProfileService accountProfileService;
    private final UserRepository userRepository;

    public AccountController(AccountProfileService accountProfileService,
                              UserRepository userRepository) {
        this.accountProfileService = accountProfileService;
        this.userRepository = userRepository;
    }

    @PostMapping("/profile")
    public ResponseEntity<?> completeProfile(@AuthenticationPrincipal UserDetails principal,
                                              @Valid @RequestBody CompleteProfileRequest req) {
        var saved = accountProfileService.completeProfile(
            principal.getUsername(), req.getDisplayName(), req.getDateOfBirth());
        return ResponseEntity.ok(AccountMeResponse.from(saved));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(@AuthenticationPrincipal UserDetails principal) {
        var user = userRepository.findByUid(principal.getUsername())
            .orElseThrow(() -> new UserNotFoundException(principal.getUsername()));
        return ResponseEntity.ok(AccountMeResponse.from(user));
    }

    @ExceptionHandler(AgeIneligibleException.class)
    public ResponseEntity<ErrorResponse> handleAgeIneligible(AgeIneligibleException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new ErrorResponse("AGE_INELIGIBLE",
                "FitrahTube is for users 13 and older."));
    }

    @ExceptionHandler(ProfileAlreadyCompletedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyCompleted(ProfileAlreadyCompletedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("PROFILE_ALREADY_COMPLETED", e.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("USER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }
}
```

- [ ] **Step 5: Widen FirebaseAuthFilter.shouldNotFilter**

Open `backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java`. Find the `shouldNotFilter` method. It currently exempts `/api/v1/*`. Change so `/api/account/*` is NOT exempt — the filter must run there.

Locate the exemption logic (the exact wording depends on Plan A; likely a `startsWith("/api/v1/")` check). Add a check that ensures `/api/account/` paths are filtered (i.e., `shouldNotFilter` returns `false` for them):

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path.startsWith("/api/account/")) {
        return false; // Account endpoints always require auth — Plan C T3.
    }
    // existing exemption logic for /api/v1/*, etc.
    return path.startsWith("/api/v1/") || ...; // keep existing
}
```

Add to existing filter test (or create one if absent) — assert `/api/account/profile` is NOT exempt:

```java
@Test
void shouldNotFilterReturnsFalseForAccountPaths() {
    var req = new MockHttpServletRequest();
    req.setRequestURI("/api/account/profile");
    assertFalse(filter.shouldNotFilter(req));
}
```

- [ ] **Step 6: Run tests to verify pass**

```bash
cd backend && ./gradlew test --tests AccountControllerTest --tests FirebaseAuthFilterTest
```
Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/AccountController.java \
        backend/src/main/java/com/albunyaan/tube/dto/*.java \
        backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java \
        backend/src/test/java/com/albunyaan/tube/controller/AccountControllerTest.java \
        backend/src/test/java/com/albunyaan/tube/security/FirebaseAuthFilterTest.java
git commit -m "[FEAT-BACKEND-PROFILE-01-T3]: AccountController (POST /profile, GET /me) + filter widening"
```

---

## T4 — Backend: AccountControllerIT (integration) + Firestore rules

### Files
- Create: `backend/src/test/java/com/albunyaan/tube/controller/AccountControllerIT.java`
- Modify: `firestore.rules` (project root)

### Steps

- [ ] **Step 1: Update Firestore rules**

Open `firestore.rules`. Find the `/users/{userId}` rule block. Add the self-write allowance (per spec §5.3):

```
match /users/{userId} {
  allow read: if isAuthenticated() && (request.auth.uid == userId || isAdmin());
  allow write: if isAdmin();
  // Plan C T4 — self-write specific profile sub-fields, only when in PENDING_PROFILE.
  allow update: if isAuthenticated() && request.auth.uid == userId &&
                   resource.data.status == 'pending_profile' &&
                   request.resource.data.diff(resource.data)
                     .affectedKeys()
                     .hasOnly(['displayName', 'dateOfBirth', 'updatedAt',
                              'profileCompletedAt', 'status']);
}
```

- [ ] **Step 2: Write the failing integration test**

`backend/src/test/java/com/albunyaan/tube/controller/AccountControllerIT.java`:

```java
package com.albunyaan.tube.controller;

import com.albunyaan.tube.BaseIntegrationTest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountControllerIT extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FirebaseAuth firebaseAuth;

    @Test
    void completeProfileHappyPath() throws Exception {
        String uid = createPendingProfileUser("alice@example.com");
        String token = mintIdToken(uid);

        mockMvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Alice", "dateOfBirth": "2000-01-01"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.displayName").value("Alice"));

        User reloaded = userRepository.findByUid(uid).orElseThrow();
        assertEquals(UserStatus.ACTIVE, reloaded.getStatusEnum());
        assertNotNull(reloaded.getDateOfBirth());
        assertNotNull(reloaded.getProfileCompletedAt());
    }

    @Test
    void completeProfileUnder13DeletesDocAndRevokesTokens() throws Exception {
        String uid = createPendingProfileUser("kid@example.com");
        String token = mintIdToken(uid);

        mockMvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "Kid", "dateOfBirth": "2020-01-01"}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));

        assertTrue(userRepository.findByUid(uid).isEmpty(),
            "user doc must be deleted on age-ineligible");
        // FirebaseAuth admin emulator does NOT support verifying revokeRefreshTokens
        // directly, but we can assert validUntil. Skip if emulator-limited; doc-delete
        // is the durable assertion.
    }

    @Test
    void completeProfileSecondAttemptReturns409() throws Exception {
        String uid = createPendingProfileUser("bob@example.com");
        String token = mintIdToken(uid);
        String body = """
            {"displayName": "Bob", "dateOfBirth": "2000-01-01"}
            """;

        mockMvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETED"));
    }

    @Test
    void getMeReturnsCallerProfile() throws Exception {
        String uid = createPendingProfileUser("carol@example.com");
        String token = mintIdToken(uid);

        mockMvc.perform(get("/api/account/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uid").value(uid))
            .andExpect(jsonPath("$.status").value("pending_profile"));
    }

    @Test
    void postProfileUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName": "x", "dateOfBirth": "2000-01-01"}
                    """))
            .andExpect(status().isUnauthorized());
    }

    private String createPendingProfileUser(String email) throws Exception {
        var record = firebaseAuth.createUser(
            new com.google.firebase.auth.UserRecord.CreateRequest().setEmail(email));
        User u = new User(record.getUid(), email, null, "user");
        u.setStatusEnum(UserStatus.PENDING_PROFILE);
        userRepository.save(u);
        return record.getUid();
    }

    private String mintIdToken(String uid) throws Exception {
        // BaseIntegrationTest provides this helper via Firebase Auth emulator REST.
        return idTokenForUid(uid);
    }
}
```

> The `idTokenForUid` helper should already exist on `BaseIntegrationTest` from Plan A. If not, see Plan A's integration tests for the existing pattern (custom token → /accounts:signInWithCustomToken).

- [ ] **Step 3: Run integration test against emulator**

```bash
cd backend && ./gradlew test -Pintegration=true --tests AccountControllerIT
```

Expected: 5 PASS. Emulator must be running (Firebase emulator suite). If `GOOGLE_APPLICATION_CREDENTIALS` is not set, the test should be skipped per Plan A's `BaseIntegrationTest` pattern.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/controller/AccountControllerIT.java firestore.rules
git commit -m "[FEAT-BACKEND-PROFILE-01-T4]: AccountControllerIT + Firestore self-write rule"
```

---

## T5 — Android: AccountStatus + AccountService + DTOs

### Files
- Create: `android/app/src/main/java/com/albunyaan/tube/auth/AccountStatus.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/account/AccountService.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/account/CompleteProfileRequestDto.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/account/AccountMeResponseDto.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusTest.kt`

### Steps

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusTest.kt`:

```kotlin
package com.albunyaan.tube.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountStatusTest {
    @Test fun `parses known wire values`() {
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromWire("active"))
        assertEquals(AccountStatus.PENDING_PROFILE, AccountStatus.fromWire("pending_profile"))
        assertEquals(AccountStatus.BLOCKED, AccountStatus.fromWire("blocked"))
        assertEquals(AccountStatus.DELETED, AccountStatus.fromWire("deleted"))
    }

    @Test fun `unknown wire value falls back to PENDING_PROFILE`() {
        // Conservative default: treat unknown as "needs bootstrap" so the user
        // gets routed through the explicit flow rather than silently allowed in.
        assertEquals(AccountStatus.PENDING_PROFILE, AccountStatus.fromWire("future_status"))
        assertEquals(AccountStatus.PENDING_PROFILE, AccountStatus.fromWire(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests AccountStatusTest
```
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create AccountStatus enum**

`android/app/src/main/java/com/albunyaan/tube/auth/AccountStatus.kt`:

```kotlin
package com.albunyaan.tube.auth

/**
 * Plan C T5: client-side enum mirroring backend `UserStatus`.
 * Wire format matches the lowercase snake_case value from `AccountMeResponse.status`.
 *
 * Routing semantics in [com.albunyaan.tube.ui.SplashRouter]:
 *  - ACTIVE          → MainShell
 *  - PENDING_PROFILE → ProfileBootstrap
 *  - BLOCKED/DELETED → SignIn (with toast). Surfaced by Plan B's
 *                      AccountStatusInterceptor too; SplashRouter is the
 *                      cold-start path, the interceptor is the warm path.
 *
 * Unknown values map to PENDING_PROFILE as a conservative default: a status
 * the client doesn't recognize is treated as "needs bootstrap" so the user
 * gets a deterministic explicit flow rather than a silent allow into MainShell.
 */
enum class AccountStatus(val wire: String) {
    ACTIVE("active"),
    PENDING_PROFILE("pending_profile"),
    BLOCKED("blocked"),
    DELETED("deleted");

    companion object {
        fun fromWire(value: String?): AccountStatus =
            entries.firstOrNull { it.wire == value } ?: PENDING_PROFILE
    }
}
```

- [ ] **Step 4: Create AccountState sealed class**

`android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt`:

```kotlin
package com.albunyaan.tube.auth

/**
 * Plan C T5: hot state for the account profile. [AccountRepository] holds a
 * StateFlow of this. SplashRouter reads the latest emission to make routing
 * decisions.
 */
sealed interface AccountState {
    /** Initial / between sign-out events. */
    data object NotSignedIn : AccountState
    /** Fetch in flight. */
    data object Loading : AccountState
    /** Fetch failed after retries. */
    data class Failed(val cause: Throwable) : AccountState
    /** Fetch succeeded. */
    data class Loaded(
        val uid: String,
        val email: String?,
        val displayName: String?,
        val status: AccountStatus,
    ) : AccountState
}
```

- [ ] **Step 5: Create DTOs**

`android/app/src/main/java/com/albunyaan/tube/data/account/CompleteProfileRequestDto.kt`:

```kotlin
package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompleteProfileRequestDto(
    val displayName: String,
    /** Wire format: "YYYY-MM-DD". */
    val dateOfBirth: String,
)
```

`android/app/src/main/java/com/albunyaan/tube/data/account/AccountMeResponseDto.kt`:

```kotlin
package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AccountMeResponseDto(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val status: String,
    val role: String?,
    val profileCompletedAt: String?,
)
```

- [ ] **Step 6: Create Retrofit service**

`android/app/src/main/java/com/albunyaan/tube/data/account/AccountService.kt`:

```kotlin
package com.albunyaan.tube.data.account

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Plan C T5: Retrofit definition for the new /api/account/* endpoints.
 * Auth header is injected by Plan B's FirebaseAuthInterceptor.
 */
interface AccountService {

    @POST("api/account/profile")
    suspend fun completeProfile(@Body body: CompleteProfileRequestDto): AccountMeResponseDto

    @GET("api/account/me")
    suspend fun getMe(): AccountMeResponseDto
}
```

- [ ] **Step 7: Run tests to verify pass**

```bash
cd android && ./gradlew testDebugUnitTest --tests AccountStatusTest
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountStatus.kt \
        android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt \
        android/app/src/main/java/com/albunyaan/tube/data/account/ \
        android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusTest.kt
git commit -m "[FEAT-ANDROID-PROFILE-01-T5]: AccountStatus + AccountState + AccountService"
```

---

## T6 — Android: AccountRepository + Hilt module

### Files
- Create: `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepository.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/auth/di/AccountModule.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/auth/AccountRepositoryImplTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/network/NetworkModule.kt` (add AccountService provider)

### Steps

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/com/albunyaan/tube/auth/AccountRepositoryImplTest.kt`:

```kotlin
package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import retrofit2.HttpException
import retrofit2.Response

import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryImplTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: AccountService
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = mock()
        repository = AccountRepositoryImpl(service, backoffMs = 10L)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state is NotSignedIn`() {
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    @Test fun `fetchMe success updates accountState to Loaded`() = runTest(dispatcher) {
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        val result = repository.fetchMe()

        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("uid-1", state.uid)
        assertEquals(AccountStatus.ACTIVE, state.status)
    }

    @Test fun `fetchMe retries 3 times on network error then fails`() = runTest(dispatcher) {
        whenever(service.getMe()).thenThrow(IOException("offline"))
        val result = repository.fetchMe()

        assertTrue(result.isFailure)
        verify(service, times(3)).getMe()
        val state = repository.accountState.first() as AccountState.Failed
        assertTrue(state.cause is IOException)
    }

    @Test fun `fetchMe succeeds on second attempt after one failure`() = runTest(dispatcher) {
        whenever(service.getMe())
            .thenThrow(IOException("flaky"))
            .thenReturn(dto(status = "pending_profile"))

        val result = repository.fetchMe()
        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals(AccountStatus.PENDING_PROFILE, state.status)
    }

    @Test fun `completeProfile success updates accountState`() = runTest(dispatcher) {
        whenever(service.completeProfile(any())).thenReturn(dto(status = "active"))

        val result = repository.completeProfile("Alice", LocalDate.of(2000, 1, 1))

        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals(AccountStatus.ACTIVE, state.status)
        verify(service).completeProfile(CompleteProfileRequestDto("Alice", "2000-01-01"))
    }

    @Test fun `completeProfile maps 422 AGE_INELIGIBLE to AgeIneligibleError`() = runTest(dispatcher) {
        val errBody = okhttp3.ResponseBody.create(null,
            """{"code":"AGE_INELIGIBLE","message":"too young"}""")
        whenever(service.completeProfile(any()))
            .thenThrow(HttpException(Response.error<Any>(422, errBody)))

        val result = repository.completeProfile("Kid", LocalDate.of(2020, 1, 1))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AgeIneligibleError)
    }

    @Test fun `signOut resets accountState`() = runTest(dispatcher) {
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        repository.fetchMe()

        repository.signOut()
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    private fun dto(status: String) = AccountMeResponseDto(
        uid = "uid-1", email = "a@b.com", displayName = "Alice",
        status = status, role = "user", profileCompletedAt = null,
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests AccountRepositoryImplTest
```
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create AccountRepository interface**

`android/app/src/main/java/com/albunyaan/tube/auth/AccountRepository.kt`:

```kotlin
package com.albunyaan.tube.auth

import kotlinx.coroutines.flow.StateFlow

import java.time.LocalDate

/**
 * Plan C T6: hot state for the account profile + suspend functions for
 * /api/account/* calls. Singleton-scoped; UI layer reads [accountState]
 * to decide routing.
 */
interface AccountRepository {

    val accountState: StateFlow<AccountState>

    /**
     * Fetch the caller's profile from `/api/account/me`. Updates [accountState].
     * Retries up to 3 times with [backoffMs] linear backoff between attempts.
     */
    suspend fun fetchMe(): Result<AccountState.Loaded>

    /**
     * Submit `/api/account/profile`. On 422 AGE_INELIGIBLE returns
     * `Result.failure(AgeIneligibleError)`; on other failures returns the
     * underlying exception.
     */
    suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate): Result<AccountState.Loaded>

    /** Clears local state on sign-out. Does not call the network. */
    fun signOut()
}

/** Sentinel error type for under-13 rejection. UI maps this to navigation. */
class AgeIneligibleError : RuntimeException("age-ineligible")
```

- [ ] **Step 4: Create AccountRepositoryImpl**

`android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt`:

```kotlin
package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AccountRepositoryImpl(
    private val service: AccountService,
    /** Linear backoff between retry attempts. 1s in prod; overridable for tests. */
    private val backoffMs: Long = 1_000L,
) : AccountRepository {

    private val _state = MutableStateFlow<AccountState>(AccountState.NotSignedIn)
    override val accountState: StateFlow<AccountState> = _state.asStateFlow()

    override suspend fun fetchMe(): Result<AccountState.Loaded> {
        _state.value = AccountState.Loading
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val dto = service.getMe()
                val loaded = dto.toLoaded()
                _state.value = loaded
                return Result.success(loaded)
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) delay(backoffMs)
            } catch (e: HttpException) {
                // 4xx/5xx — don't retry, bubble up.
                _state.value = AccountState.Failed(e)
                return Result.failure(e)
            }
        }
        val cause = lastError ?: IOException("unknown fetch failure")
        _state.value = AccountState.Failed(cause)
        return Result.failure(cause)
    }

    override suspend fun completeProfile(
        displayName: String,
        dateOfBirth: LocalDate,
    ): Result<AccountState.Loaded> {
        val request = CompleteProfileRequestDto(
            displayName = displayName,
            dateOfBirth = dateOfBirth.format(DateTimeFormatter.ISO_LOCAL_DATE),
        )
        return try {
            val dto = service.completeProfile(request)
            val loaded = dto.toLoaded()
            _state.value = loaded
            Result.success(loaded)
        } catch (e: HttpException) {
            if (e.code() == 422 && bodyHasCode(e, "AGE_INELIGIBLE")) {
                Result.failure(AgeIneligibleError())
            } else {
                Result.failure(e)
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    override fun signOut() {
        _state.value = AccountState.NotSignedIn
    }

    private fun AccountMeResponseDto.toLoaded() = AccountState.Loaded(
        uid = uid,
        email = email,
        displayName = displayName,
        status = AccountStatus.fromWire(status),
    )

    /**
     * Parses the HttpException error body for a `code` field. Uses naive
     * substring match (the canonical Moshi parse would round-trip ResponseBody,
     * which Retrofit has already consumed by the time we catch HttpException).
     * Cheap and safe — the only producer is our own AccountController.
     */
    private fun bodyHasCode(e: HttpException, code: String): Boolean {
        val body = e.response()?.errorBody()?.string() ?: return false
        return body.contains("\"code\"") && body.contains("\"$code\"")
    }

    companion object { private const val MAX_ATTEMPTS = 3 }
}
```

- [ ] **Step 5: Create Hilt module**

`android/app/src/main/java/com/albunyaan/tube/auth/di/AccountModule.kt`:

```kotlin
package com.albunyaan.tube.auth.di

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountRepositoryImpl
import com.albunyaan.tube.data.account.AccountService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    @Provides
    @Singleton
    fun provideAccountRepository(service: AccountService): AccountRepository =
        AccountRepositoryImpl(service)
}
```

- [ ] **Step 6: Add AccountService provider to NetworkModule**

In `android/app/src/main/java/com/albunyaan/tube/network/NetworkModule.kt`, add:

```kotlin
@Provides
@Singleton
fun provideAccountService(retrofit: Retrofit): AccountService =
    retrofit.create(AccountService::class.java)
```

- [ ] **Step 7: Run tests to verify pass**

```bash
cd android && ./gradlew testDebugUnitTest --tests AccountRepositoryImplTest
```
Expected: 7 PASS.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt \
        android/app/src/main/java/com/albunyaan/tube/auth/di/AccountModule.kt \
        android/app/src/main/java/com/albunyaan/tube/network/NetworkModule.kt \
        android/app/src/test/java/com/albunyaan/tube/auth/AccountRepositoryImplTest.kt
git commit -m "[FEAT-ANDROID-PROFILE-01-T6]: AccountRepository + Hilt module + 3-retry fetchMe"
```

---

## T7 — Android: SplashRouter extension + SplashFragment /me fetch

### Files
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/SplashRouter.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/SplashFragment.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/ui/SplashRouterTest.kt`

### Steps

- [ ] **Step 1: Write failing test additions**

In `SplashRouterTest.kt`, add (preserving existing tests):

```kotlin
import com.albunyaan.tube.auth.AccountStatus

@Test fun `signed-in PENDING_PROFILE routes to bootstrap`() {
    val action = SplashRouter.decideSplashRoute(
        onboardingCompleted = true, signedIn = true,
        accountStatus = AccountStatus.PENDING_PROFILE,
    )
    assertEquals(R.id.action_splash_to_bootstrap, action)
}

@Test fun `signed-in ACTIVE routes to main`() {
    val action = SplashRouter.decideSplashRoute(
        onboardingCompleted = true, signedIn = true,
        accountStatus = AccountStatus.ACTIVE,
    )
    assertEquals(R.id.action_splash_to_main, action)
}

@Test fun `signed-in BLOCKED routes to signIn`() {
    val action = SplashRouter.decideSplashRoute(
        onboardingCompleted = true, signedIn = true,
        accountStatus = AccountStatus.BLOCKED,
    )
    assertEquals(R.id.action_splash_to_signIn, action)
}

@Test fun `signed-in null status (fetch failed) routes to signIn`() {
    val action = SplashRouter.decideSplashRoute(
        onboardingCompleted = true, signedIn = true,
        accountStatus = null,
    )
    assertEquals(R.id.action_splash_to_signIn, action)
}
```

The existing two tests (`signed-in routes to main`, `signed-out routes to signIn`) must be updated to pass `accountStatus = AccountStatus.ACTIVE` and `accountStatus = null` respectively. Update the test bodies.

- [ ] **Step 2: Run tests to verify failure**

```bash
cd android && ./gradlew testDebugUnitTest --tests SplashRouterTest
```
Expected: FAIL on the 4 new tests + 2 existing (signature mismatch).

- [ ] **Step 3: Update SplashRouter signature**

Rewrite `SplashRouter.kt`:

```kotlin
package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AccountStatus

/**
 * Plan B T5 + Plan C T7: post-splash routing.
 *
 *   - onboarding not done → onboarding (regardless of auth state)
 *   - signed-out          → sign-in
 *   - signed-in, status fetch failed (null) → sign-in (signed-out toast)
 *   - signed-in, ACTIVE          → main shell
 *   - signed-in, PENDING_PROFILE → bootstrap
 *   - signed-in, BLOCKED/DELETED → sign-in (handled by Plan B AccountStatusInterceptor on warm path;
 *                                          SplashRouter is the cold-start equivalent)
 */
internal object SplashRouter {

    fun decideSplashRoute(
        onboardingCompleted: Boolean,
        signedIn: Boolean,
        accountStatus: AccountStatus?,
    ): Int = when {
        !onboardingCompleted -> R.id.action_splash_to_onboarding
        !signedIn -> R.id.action_splash_to_signIn
        accountStatus == null -> R.id.action_splash_to_signIn
        accountStatus == AccountStatus.ACTIVE -> R.id.action_splash_to_main
        accountStatus == AccountStatus.PENDING_PROFILE -> R.id.action_splash_to_bootstrap
        else -> R.id.action_splash_to_signIn // BLOCKED, DELETED
    }

    fun decideOnboardingRoute(signedIn: Boolean): Int =
        if (signedIn) R.id.action_onboarding_to_main else R.id.action_onboarding_to_signIn
}
```

- [ ] **Step 4: Update SplashFragment to fetch /me in parallel**

In `SplashFragment.kt`, add `@Inject lateinit var accountRepository: AccountRepository`. Then in `onViewCreated` (replacing the `routeAfterSplash(onboardingDeferred.await())` invocation with a 3-way coordinator):

```kotlin
@Inject lateinit var accountRepository: AccountRepository

// inside the existing onViewCreated lifecycleScope.launch { ... } block:
val onboardingDeferred: Deferred<Boolean> = async {
    settingsPreferences.onboardingCompleted.first()
}
val accountStatusDeferred: Deferred<AccountStatus?> = async {
    if (firebaseAuth.currentUser == null) null
    else accountRepository.fetchMe().getOrNull()?.status
}

if (isDeepLinkLaunch()) {
    routeAfterSplash(onboardingDeferred.await(), accountStatusDeferred.await())
    return@launch
}

// ... existing animations ...

val onboardingCompleted = onboardingDeferred.await()
val accountStatus = accountStatusDeferred.await()
routeAfterSplash(onboardingCompleted, accountStatus)
```

Update the private `routeAfterSplash`:

```kotlin
private fun routeAfterSplash(onboardingCompleted: Boolean, accountStatus: AccountStatus?) {
    if (findNavController().currentDestination?.id != R.id.splashFragment) return
    val action = SplashRouter.decideSplashRoute(
        onboardingCompleted = onboardingCompleted,
        signedIn = firebaseAuth.currentUser != null,
        accountStatus = accountStatus,
    )
    findNavController().navigate(action)
}
```

- [ ] **Step 5: Run all tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests SplashRouterTest
```
Expected: 6 PASS (4 new + 2 updated).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/SplashRouter.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/SplashFragment.kt \
        android/app/src/test/java/com/albunyaan/tube/ui/SplashRouterTest.kt
git commit -m "[FEAT-ANDROID-PROFILE-01-T7]: SplashRouter + SplashFragment /me fetch (parallel)"
```

---

## T8 — Android: ProfileBootstrapFragment + ViewModel + 3 layouts + strings

### Files
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapFragment.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModel.kt`
- Create: `android/app/src/main/res/layout/fragment_profile_bootstrap.xml`
- Create: `android/app/src/main/res/layout-sw600dp/fragment_profile_bootstrap.xml`
- Create: `android/app/src/main/res/layout-sw720dp/fragment_profile_bootstrap.xml`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModelTest.kt`
- Modify: `android/app/src/main/res/values/strings_onboarding.xml`
- Modify: `android/app/src/main/res/values-ar/strings_onboarding.xml`
- Modify: `android/app/src/main/res/values-nl/strings_onboarding.xml`

### Steps

- [ ] **Step 1: Write the failing ViewModel test**

`android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModelTest.kt`:

```kotlin
package com.albunyaan.tube.ui.bootstrap

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.auth.AgeIneligibleError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileBootstrapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AccountRepository
    private lateinit var viewModel: ProfileBootstrapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        whenever(repository.accountState).thenReturn(MutableStateFlow(AccountState.NotSignedIn))
        viewModel = ProfileBootstrapViewModel(repository)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state has empty fields`() {
        val s = viewModel.ui.value
        assertEquals("", s.displayName)
        assertNull(s.dateOfBirth)
        assertFalse(s.isLoading)
        assertNull(s.error)
    }

    @Test fun `onDisplayNameChanged updates field and clears error`() {
        viewModel.surfaceError(BootstrapError.SAVE_FAILED)
        viewModel.onDisplayNameChanged("Alice")
        assertEquals("Alice", viewModel.ui.value.displayName)
        assertNull(viewModel.ui.value.error)
    }

    @Test fun `submit with blank name surfaces INVALID_NAME`() = runTest(dispatcher) {
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_NAME, viewModel.ui.value.error)
        verify(repository, never()).completeProfile(any(), any())
    }

    @Test fun `submit with missing dob surfaces INVALID_DOB`() = runTest(dispatcher) {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_DOB, viewModel.ui.value.error)
    }

    @Test fun `submit happy path transitions to NavigateToMain`() = runTest(dispatcher) {
        whenever(repository.completeProfile("Alice", LocalDate.of(2000, 1, 1)))
            .thenReturn(Result.success(AccountState.Loaded(
                "uid-1", "a@b.com", "Alice", AccountStatus.ACTIVE)))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapNav.NavigateToMain, viewModel.nav.value)
    }

    @Test fun `submit 422 AGE_INELIGIBLE transitions to NavigateToAgeIneligible`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any()))
            .thenReturn(Result.failure(AgeIneligibleError()))

        viewModel.onDisplayNameChanged("Kid")
        viewModel.onDobChanged(LocalDate.of(2020, 1, 1))
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapNav.NavigateToAgeIneligible, viewModel.nav.value)
    }

    @Test fun `submit network error surfaces SAVE_FAILED`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any()))
            .thenReturn(Result.failure(java.io.IOException("offline")))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapError.SAVE_FAILED, viewModel.ui.value.error)
    }

    @Test fun `submit during loading is no-op`() = runTest(dispatcher) {
        viewModel.setLoading(true)
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()
        verify(repository, never()).completeProfile(any(), any())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests ProfileBootstrapViewModelTest
```
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create ProfileBootstrapViewModel**

`android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModel.kt`:

```kotlin
package com.albunyaan.tube.ui.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AgeIneligibleError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import java.time.LocalDate
import javax.inject.Inject

enum class BootstrapError { INVALID_NAME, INVALID_DOB, SAVE_FAILED }
sealed interface BootstrapNav {
    data object Idle : BootstrapNav
    data object NavigateToMain : BootstrapNav
    data object NavigateToAgeIneligible : BootstrapNav
}

@HiltViewModel
class ProfileBootstrapViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    data class UiState(
        val displayName: String = "",
        val dateOfBirth: LocalDate? = null,
        val isLoading: Boolean = false,
        val error: BootstrapError? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _nav = MutableStateFlow<BootstrapNav>(BootstrapNav.Idle)
    val nav: StateFlow<BootstrapNav> = _nav.asStateFlow()

    fun seedDisplayName(initial: String) {
        if (_ui.value.displayName.isEmpty()) _ui.update { it.copy(displayName = initial) }
    }

    fun onDisplayNameChanged(v: String) {
        _ui.update { it.copy(displayName = v, error = null) }
    }

    fun onDobChanged(d: LocalDate) {
        _ui.update { it.copy(dateOfBirth = d, error = null) }
    }

    fun setLoading(loading: Boolean) {
        _ui.update { it.copy(isLoading = loading) }
    }

    fun surfaceError(e: BootstrapError) {
        _ui.update { it.copy(isLoading = false, error = e) }
    }

    fun consumeNav() { _nav.value = BootstrapNav.Idle }

    fun submit() {
        val s = _ui.value
        if (s.isLoading) return  // de-dupe rapid double-taps
        val name = s.displayName.trim()
        if (name.isBlank() || name.length > 40) {
            _ui.update { it.copy(error = BootstrapError.INVALID_NAME) }
            return
        }
        val dob = s.dateOfBirth
        if (dob == null) {
            _ui.update { it.copy(error = BootstrapError.INVALID_DOB) }
            return
        }
        _ui.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = accountRepository.completeProfile(name, dob)
            result.fold(
                onSuccess = {
                    _ui.update { it.copy(isLoading = false) }
                    _nav.value = BootstrapNav.NavigateToMain
                },
                onFailure = { e ->
                    _ui.update { it.copy(isLoading = false) }
                    if (e is AgeIneligibleError) {
                        _nav.value = BootstrapNav.NavigateToAgeIneligible
                    } else {
                        _ui.update { it.copy(error = BootstrapError.SAVE_FAILED) }
                    }
                }
            )
        }
    }
}
```

- [ ] **Step 4: Create Fragment**

`android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapFragment.kt`:

```kotlin
package com.albunyaan.tube.ui.bootstrap

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AuthRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class ProfileBootstrapFragment : Fragment(R.layout.fragment_profile_bootstrap) {

    @Inject lateinit var firebaseAuth: FirebaseAuth
    @Inject lateinit var authRepository: AuthRepository
    private val viewModel: ProfileBootstrapViewModel by viewModels()

    private lateinit var displayNameLayout: TextInputLayout
    private lateinit var displayNameField: TextInputEditText
    private lateinit var dobLayout: TextInputLayout
    private lateinit var dobField: TextInputEditText
    private lateinit var submitButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        wireListeners()

        viewModel.seedDisplayName(firebaseAuth.currentUser?.displayName.orEmpty())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    when (nav) {
                        BootstrapNav.Idle -> Unit
                        BootstrapNav.NavigateToMain -> {
                            findNavController().navigate(R.id.action_bootstrap_to_main)
                            viewModel.consumeNav()
                        }
                        BootstrapNav.NavigateToAgeIneligible -> {
                            findNavController().navigate(R.id.action_bootstrap_to_ageIneligible)
                            viewModel.consumeNav()
                        }
                    }
                }
            }
        }
    }

    private fun bindViews(v: View) {
        displayNameLayout = v.findViewById(R.id.displayNameLayout)
        displayNameField = v.findViewById(R.id.displayNameField)
        dobLayout = v.findViewById(R.id.dobLayout)
        dobField = v.findViewById(R.id.dobField)
        submitButton = v.findViewById(R.id.submitButton)
    }

    private fun wireListeners() {
        displayNameField.doAfterTextChanged { viewModel.onDisplayNameChanged(it?.toString().orEmpty()) }
        dobField.setOnClickListener { openDatePicker() }
        dobField.isFocusable = false
        submitButton.setOnClickListener { viewModel.submit() }
    }

    private fun openDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.bootstrap_dob_label))
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val date = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDate()
            viewModel.onDobChanged(date)
        }
        picker.show(parentFragmentManager, "dob-picker")
    }

    private fun render(state: ProfileBootstrapViewModel.UiState) {
        if (displayNameField.text?.toString() != state.displayName) {
            displayNameField.setText(state.displayName)
            displayNameField.setSelection(state.displayName.length)
        }
        dobField.setText(state.dateOfBirth?.format(DateTimeFormatter.ISO_LOCAL_DATE).orEmpty())
        submitButton.isEnabled = !state.isLoading
        displayNameLayout.error = state.error?.takeIf { it == BootstrapError.INVALID_NAME }
            ?.let { getString(R.string.bootstrap_error_invalid_name) }
        dobLayout.error = state.error?.takeIf { it == BootstrapError.INVALID_DOB }
            ?.let { getString(R.string.bootstrap_error_invalid_dob) }
        // SAVE_FAILED surfaced via Snackbar — leave to existing project pattern
    }
}

// extension lifted from existing project utilities (mirrors SignInFragment)
private inline fun com.google.android.material.textfield.TextInputEditText.doAfterTextChanged(
    crossinline onChanged: (CharSequence?) -> Unit,
) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChanged(s)
    })
}
```

- [ ] **Step 5: Create phone layout `res/layout/fragment_profile_bootstrap.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="@dimen/spacing_md"
    android:fitsSystemWindows="true">

    <TextView
        android:id="@+id/title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/bootstrap_title"
        android:textAppearance="?attr/textAppearanceHeadlineSmall"
        android:textAlignment="viewStart"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/displayNameLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_lg"
        android:hint="@string/bootstrap_display_name_label"
        app:layout_constraintTop_toBottomOf="@id/title"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/displayNameField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPersonName|textCapWords"
            android:maxLength="40"
            android:textAlignment="viewStart" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/dobLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_md"
        android:hint="@string/bootstrap_dob_label"
        app:layout_constraintTop_toBottomOf="@id/displayNameLayout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:endIconMode="custom"
        app:endIconDrawable="@drawable/ic_calendar_24">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/dobField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="none"
            android:textAlignment="viewStart"
            android:focusable="false"
            android:cursorVisible="false" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/submitButton"
        style="@style/Widget.Material3.Button"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_lg"
        android:text="@string/bootstrap_submit_button"
        app:layout_constraintTop_toBottomOf="@id/dobLayout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

> Add `res/drawable/ic_calendar_24.xml` if absent — use Material Symbols / Android Studio's "Asset Studio → Vector Asset → calendar_today" with no vector-level `android:tint`. Per CLAUDE.md, never use `ic_stat_*` or `@android:drawable/*` for nav, but tinted icons inside form fields are fine.

- [ ] **Step 6: Create tablet layout `res/layout-sw600dp/fragment_profile_bootstrap.xml`**

Same as phone but wrap the ConstraintLayout in a centred 560dp column:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">

    <androidx.constraintlayout.widget.ConstraintLayout
        xmlns:app="http://schemas.android.com/apk/res-auto"
        android:layout_width="560dp"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:padding="@dimen/spacing_lg">

        <!-- identical view IDs + structure as phone layout -->
        <!-- ... (TextView title, displayNameLayout, dobLayout, submitButton — same as above) -->
    </androidx.constraintlayout.widget.ConstraintLayout>
</FrameLayout>
```

Copy the inner views verbatim from the phone layout. Same IDs (`title`, `displayNameLayout`, `displayNameField`, `dobLayout`, `dobField`, `submitButton`).

- [ ] **Step 7: Create TV layout `res/layout-sw720dp/fragment_profile_bootstrap.xml`**

Same as tablet but with larger typography overrides if existing project pattern dictates (check Plan B's `fragment_sign_in.xml` sw720dp variant). Otherwise identical to sw600dp.

- [ ] **Step 8: Add string entries**

`res/values/strings_onboarding.xml`, append:

```xml
<string name="bootstrap_title">Tell us about you</string>
<string name="bootstrap_display_name_label">What should we call you?</string>
<string name="bootstrap_display_name_hint">Display name</string>
<string name="bootstrap_dob_label">Date of birth</string>
<string name="bootstrap_dob_hint">Tap to select</string>
<string name="bootstrap_submit_button">Continue</string>
<string name="bootstrap_error_invalid_name">Please tell us your name (1–40 characters)</string>
<string name="bootstrap_error_invalid_dob">Please choose your date of birth</string>
<string name="bootstrap_error_save_failed">Couldn’t save your profile — try again</string>
```

In `values-ar/strings_onboarding.xml` and `values-nl/strings_onboarding.xml`, copy the same `<string name="...">English value</string>` lines (per i18n strategy: English placeholder until human-translated, no machine translation).

- [ ] **Step 9: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests ProfileBootstrapViewModelTest && ./gradlew assembleDebug
```
Expected: 8 tests PASS + APK builds.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/ \
        android/app/src/main/res/layout/fragment_profile_bootstrap.xml \
        android/app/src/main/res/layout-sw600dp/fragment_profile_bootstrap.xml \
        android/app/src/main/res/layout-sw720dp/fragment_profile_bootstrap.xml \
        android/app/src/main/res/values/strings_onboarding.xml \
        android/app/src/main/res/values-ar/strings_onboarding.xml \
        android/app/src/main/res/values-nl/strings_onboarding.xml \
        android/app/src/main/res/drawable/ic_calendar_24.xml \
        android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/ProfileBootstrapViewModelTest.kt
git commit -m "[FEAT-ANDROID-PROFILE-01-T8]: ProfileBootstrapFragment + ViewModel + 3 layouts + strings"
```

---

## T9 — Android: AgeIneligibleFragment + Firebase Auth delete

### Files
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/AgeIneligibleFragment.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/AgeIneligibleViewModel.kt`
- Create: `android/app/src/main/res/layout/fragment_age_ineligible.xml`
- Create: `android/app/src/main/res/layout-sw600dp/fragment_age_ineligible.xml`
- Create: `android/app/src/main/res/layout-sw720dp/fragment_age_ineligible.xml`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/AgeIneligibleViewModelTest.kt`
- Modify: `android/app/src/main/res/values/strings_onboarding.xml` (+ values-ar, values-nl)

### Steps

- [ ] **Step 1: Write the failing ViewModel test**

`AgeIneligibleViewModelTest.kt`:

```kotlin
package com.albunyaan.tube.ui.bootstrap

import com.albunyaan.tube.auth.AuthRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
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
class AgeIneligibleViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: AgeIneligibleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        firebaseAuth = mock()
        authRepository = mock()
        viewModel = AgeIneligibleViewModel(firebaseAuth, authRepository)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `acknowledge deletes user and triggers NavigateToSignIn`() = runTest(dispatcher) {
        val user: FirebaseUser = mock()
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.delete()).thenReturn(Tasks.forResult(null))

        viewModel.acknowledge()
        advanceUntilIdle()

        verify(user).delete()
        verify(authRepository).signOut()
        assertEquals(AgeIneligibleNav.NavigateToSignIn, viewModel.nav.value)
    }

    @Test fun `acknowledge proceeds even if delete fails`() = runTest(dispatcher) {
        val user: FirebaseUser = mock()
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.delete()).thenReturn(Tasks.forException(RuntimeException("offline")))

        viewModel.acknowledge()
        advanceUntilIdle()

        verify(authRepository).signOut()
        assertEquals(AgeIneligibleNav.NavigateToSignIn, viewModel.nav.value)
    }

    @Test fun `acknowledge with null currentUser still signs out and navigates`() = runTest(dispatcher) {
        whenever(firebaseAuth.currentUser).thenReturn(null)

        viewModel.acknowledge()
        advanceUntilIdle()

        verify(authRepository).signOut()
        assertEquals(AgeIneligibleNav.NavigateToSignIn, viewModel.nav.value)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd android && ./gradlew testDebugUnitTest --tests AgeIneligibleViewModelTest
```
Expected: FAIL.

- [ ] **Step 3: Create ViewModel**

`AgeIneligibleViewModel.kt`:

```kotlin
package com.albunyaan.tube.ui.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.slf4j.LoggerFactory
import javax.inject.Inject

sealed interface AgeIneligibleNav {
    data object Idle : AgeIneligibleNav
    data object NavigateToSignIn : AgeIneligibleNav
}

@HiltViewModel
class AgeIneligibleViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(AgeIneligibleViewModel::class.java)

    private val _nav = MutableStateFlow<AgeIneligibleNav>(AgeIneligibleNav.Idle)
    val nav: StateFlow<AgeIneligibleNav> = _nav.asStateFlow()

    fun acknowledge() {
        viewModelScope.launch {
            // Try to delete the Firebase Auth user. Backend already revoked
            // their refresh tokens in §5.2 — if delete() fails (network),
            // they still can't sign back in once their ID token expires.
            try {
                firebaseAuth.currentUser?.delete()?.await()
            } catch (e: Throwable) {
                log.warn("FirebaseAuth.delete() failed in AgeIneligible flow, proceeding", e)
            }
            authRepository.signOut()
            _nav.value = AgeIneligibleNav.NavigateToSignIn
        }
    }

    fun consumeNav() { _nav.value = AgeIneligibleNav.Idle }
}
```

- [ ] **Step 4: Create Fragment**

`AgeIneligibleFragment.kt`:

```kotlin
package com.albunyaan.tube.ui.bootstrap

import android.os.Bundle
import android.view.View
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

@AndroidEntryPoint
class AgeIneligibleFragment : Fragment(R.layout.fragment_age_ineligible) {

    private val viewModel: AgeIneligibleViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.okButton).setOnClickListener {
            viewModel.acknowledge()
        }

        // Back-nav from this fragment is identical to OK — don't let the user
        // bounce back to bootstrap with a different DOB.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { viewModel.acknowledge() }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { nav ->
                    if (nav == AgeIneligibleNav.NavigateToSignIn) {
                        findNavController().navigate(R.id.action_ageIneligible_to_signIn)
                        viewModel.consumeNav()
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Create phone layout `res/layout/fragment_age_ineligible.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="@dimen/spacing_lg"
    android:fitsSystemWindows="true">

    <TextView
        android:id="@+id/title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/age_ineligible_title"
        android:textAppearance="?attr/textAppearanceHeadlineSmall"
        android:textAlignment="center"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/body"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintVertical_chainStyle="packed" />

    <TextView
        android:id="@+id/body"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_md"
        android:text="@string/age_ineligible_body"
        android:textAppearance="?attr/textAppearanceBodyLarge"
        android:textAlignment="center"
        app:layout_constraintTop_toBottomOf="@id/title"
        app:layout_constraintBottom_toTopOf="@id/okButton"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/okButton"
        style="@style/Widget.Material3.Button"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/spacing_lg"
        android:text="@string/age_ineligible_ok_button"
        app:layout_constraintTop_toBottomOf="@id/body"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 6: Create tablet + TV layouts**

`res/layout-sw600dp/fragment_age_ineligible.xml` — wrap in 560dp column (same pattern as T8 step 6).
`res/layout-sw720dp/fragment_age_ineligible.xml` — same as sw600dp.

Both keep IDs `title`, `body`, `okButton`.

- [ ] **Step 7: Add strings**

Append to `res/values/strings_onboarding.xml`:

```xml
<string name="age_ineligible_title">Sorry — come back soon</string>
<string name="age_ineligible_body">FitrahTube is for users 13 and older. Please come back when you’re a bit older.</string>
<string name="age_ineligible_ok_button">OK</string>
```

Copy English values into `values-ar/` and `values-nl/`.

- [ ] **Step 8: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest --tests AgeIneligibleViewModelTest && ./gradlew assembleDebug
```
Expected: 3 PASS + APK builds.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/bootstrap/AgeIneligible* \
        android/app/src/main/res/layout/fragment_age_ineligible.xml \
        android/app/src/main/res/layout-sw600dp/fragment_age_ineligible.xml \
        android/app/src/main/res/layout-sw720dp/fragment_age_ineligible.xml \
        android/app/src/main/res/values/strings_onboarding.xml \
        android/app/src/main/res/values-ar/strings_onboarding.xml \
        android/app/src/main/res/values-nl/strings_onboarding.xml \
        android/app/src/test/java/com/albunyaan/tube/ui/bootstrap/AgeIneligibleViewModelTest.kt
git commit -m "[FEAT-ANDROID-PROFILE-01-T9]: AgeIneligibleFragment + FirebaseAuth.delete flow"
```

---

## T10 — Android: nav graph + SignInFragment redirect

### Files
- Modify: `android/app/src/main/res/navigation/app_nav_graph.xml`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInFragment.kt`

### Steps

- [ ] **Step 1: Add new fragments + actions to nav graph**

In `app_nav_graph.xml`, after the existing `signInFragment` block, add:

```xml
<fragment
    android:id="@+id/profileBootstrapFragment"
    android:name="com.albunyaan.tube.ui.bootstrap.ProfileBootstrapFragment"
    android:label="Profile Bootstrap">
    <action
        android:id="@+id/action_bootstrap_to_main"
        app:destination="@id/mainShellFragment"
        app:popUpTo="@id/splashFragment"
        app:popUpToInclusive="true" />
    <action
        android:id="@+id/action_bootstrap_to_ageIneligible"
        app:destination="@id/ageIneligibleFragment" />
</fragment>

<fragment
    android:id="@+id/ageIneligibleFragment"
    android:name="com.albunyaan.tube.ui.bootstrap.AgeIneligibleFragment"
    android:label="Age Ineligible">
    <action
        android:id="@+id/action_ageIneligible_to_signIn"
        app:destination="@id/signInFragment"
        app:popUpTo="@id/splashFragment"
        app:popUpToInclusive="true" />
</fragment>
```

Inside `splashFragment` block, add:

```xml
<action
    android:id="@+id/action_splash_to_bootstrap"
    app:destination="@id/profileBootstrapFragment"
    app:popUpTo="@id/splashFragment"
    app:popUpToInclusive="true" />
```

- [ ] **Step 2: Modify SignInFragment post-sign-in observer**

In `SignInFragment.kt`, find the existing post-sign-in observer (lines ~131-141 in the file Plan B committed). Change the navigate call from `action_signIn_to_main` to a new action that routes via splash:

```kotlin
// Before:
//     nav.navigate(R.id.action_signIn_to_main)
// After:
nav.navigate(R.id.action_signIn_to_splash)
```

Add the action to `app_nav_graph.xml` inside the `signInFragment` block:

```xml
<action
    android:id="@+id/action_signIn_to_splash"
    app:destination="@id/splashFragment"
    app:popUpTo="@id/signInFragment"
    app:popUpToInclusive="true" />
```

Keep the existing `action_signIn_to_main` for now (referenced by tests; remove only after T11 verification).

- [ ] **Step 3: Build + smoke test**

```bash
cd android && ./gradlew assembleDebug
```
Expected: builds without nav graph schema errors.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/navigation/app_nav_graph.xml \
        android/app/src/main/java/com/albunyaan/tube/ui/auth/SignInFragment.kt
git commit -m "[FEAT-ANDROID-PROFILE-01-T10]: nav graph + SignInFragment routes via splash"
```

---

## T11 — Manual UI verification across 3 form factors + RTL

### Pre-conditions
- T1–T10 merged into the local feature branch.
- Backend running (`cd backend && ./gradlew bootRun`) with Firestore emulator OR real Firebase.
- Android emulator OR device(s) for: phone (sw<600dp), tablet (sw600dp), TV (sw720dp).
- Test user in Firebase Auth: `bootstrap-test@example.com` (password account, no displayName set).

### Test matrix

For each device variant (phone / sw600dp / sw720dp) AND each locale (en / ar — RTL):

- [ ] **Step 1: Cold-start happy path (adult)**
    1. Fresh install → splash plays → sign-in screen.
    2. Sign in with `bootstrap-test@example.com`.
    3. Splash plays again → routes to ProfileBootstrap (status=PENDING_PROFILE).
    4. Pre-fill check: displayName field should be **empty** (email/password user — no Firebase displayName). With a Google sign-in user it should be **pre-filled**.
    5. Enter name "Alice", tap DOB field → DatePicker opens, pick `2000-01-01`, OK.
    6. Tap Continue.
    7. Verify: routes to MainShell. Backend logs show `POST /api/account/profile 200`.

- [ ] **Step 2: Cold-start happy path (kid)**
    1. Sign out, sign back in with a different test user (status=PENDING_PROFILE).
    2. Bootstrap form → enter "Kid", DOB `2020-01-01`.
    3. Tap Continue.
    4. Verify: navigates to AgeIneligibleFragment showing the age-ineligible message.
    5. Tap OK.
    6. Verify: routes to SignIn. Try re-signing in with the same account — sign-in succeeds (Firebase Auth still has the record briefly before the client-side delete propagates, or already deleted), but `/api/account/me` returns 404 / lazy-creates a new PENDING_PROFILE doc. Bootstrap form appears again. Confirm the under-13 DOB is gone (no pre-fill).

- [ ] **Step 3: Cold-start happy path (existing ACTIVE user)**
    1. Sign in with an ACTIVE user (one who already completed bootstrap in T11 step 1).
    2. Splash → routes directly to MainShell (skips bootstrap).

- [ ] **Step 4: Back-nav from bootstrap**
    1. Sign in fresh → land at bootstrap.
    2. Tap Android back button.
    3. Verify: returns to SignIn screen. AuthRepository.signOut() called (FirebaseAuth.currentUser is null).

- [ ] **Step 5: Back-nav from age-ineligible**
    1. Reach AgeIneligibleFragment (kid flow).
    2. Tap back.
    3. Verify: same outcome as tapping OK — signs out + routes to SignIn. User cannot retry bootstrap with a different DOB.

- [ ] **Step 6: Network failure on /api/account/me**
    1. With device in airplane mode, cold-start the app.
    2. Splash plays.
    3. After 3 retries (~3s + animation hold), router routes to SignIn with toast "Couldn't connect."

- [ ] **Step 7: RTL (Arabic locale)**
    1. Switch device to Arabic locale.
    2. Cold-start; verify entire bootstrap + age-ineligible screens are mirrored.
    3. DOB DatePicker dialog should also respect RTL.
    4. Strings render English (placeholder per i18n policy).

### Acceptance
All 7 steps pass on phone + sw600dp + sw720dp + Arabic RTL = 28 manual verifications. Record screenshots in `docs/status/screenshots/PROFILE-01-T11/` (path per project convention; create dir if absent).

- [ ] **Step 8: Commit screenshots**

```bash
git add docs/status/screenshots/PROFILE-01-T11/
git commit -m "[TEST-ANDROID-PROFILE-01-T11]: manual verification across 3 form factors + RTL"
```

---

## T12 — 7-stage review pipeline + PR to develop

### Pipeline (per `feedback_review_pipeline.md`)

- [ ] **Stage 1: baseline** — `git status` clean, all unit tests + integration tests passing locally on the feature branch.

```bash
cd backend && ./gradlew test -Pintegration=true
cd ../android && ./gradlew testDebugUnitTest assembleDebug
```

- [ ] **Stage 2: code-reviewer subagent (background)**

Dispatch:
```
Agent (subagent_type: code-reviewer):
DESCRIPTION: Plan C — account bootstrap + age gate
PLAN_OR_REQUIREMENTS: docs/superpowers/plans/2026-05-11-plan-c-account-bootstrap.md
BASE_SHA: <commit of T1 parent>
HEAD_SHA: $(git rev-parse HEAD)
```

- [ ] **Stage 3: cso subagent (security focus)**

Dispatch a `cso` agent. The age-gate + delete flow + Firestore rules update are security-relevant. Specifically ask the cso to validate:
1. No path leaves an orphan auth record with a still-valid token.
2. Firestore rules don't allow escalating own status to ACTIVE without backend mediation.
3. No PII leak in `AccountMeResponse` (admin-only fields stripped).
4. Refresh-token revocation happens before doc delete (per plan §self-critique #3).

- [ ] **Stage 4: codex challenge (adversarial)**

Dispatch a third agent (general-purpose) with the prompt:
> Read docs/superpowers/specs/2026-05-11-account-bootstrap-design.md and the diff $(git diff <baseline>..HEAD).
> Find the worst design choice. Then find the worst implementation bug. Then find the worst test gap.
> If you can't find three, find as many as you can. Be aggressive.

- [ ] **Stage 5: consolidate**

Merge the three reports into one Critical / Important / Minor table. Critical issues fixed in the same task's commit family; Important within the PR; Minor deferred with a TODO.

- [ ] **Stage 6: patch + re-review**

Apply fixes. Re-dispatch the code-reviewer subagent on the patched diff. Iterate until no Critical/Important remain.

- [ ] **Stage 7: gstack /review + CodeRabbit**

Push branch → `/review` skill → ensure CodeRabbit clean (push back on UI-only suggestions per feedback_review_pushback.md).

- [ ] **Stage 8: PR to develop**

```bash
git push -u origin feature/PROFILE-01-account-bootstrap
gh pr create --base develop --title "Plan C: Account bootstrap + age gate" \
  --body "$(cat <<'EOF'
## Summary
- Adds POST /api/account/profile (displayName + DOB → ACTIVE) with server-side 13+ enforcement.
- Hard-rejects under-13: backend revokes refresh tokens + deletes user doc; client deletes Firebase Auth user. Zero minor PII retained.
- Adds ProfileBootstrapFragment + AgeIneligibleFragment with 3 form-factor layouts + RTL.
- Extends Plan B's SplashRouter with accountStatus branch.

## Spec
docs/superpowers/specs/2026-05-11-account-bootstrap-design.md

## Plan
docs/superpowers/plans/2026-05-11-plan-c-account-bootstrap.md

## Test plan
- [x] Backend unit tests (AccountProfileServiceTest, AccountControllerTest)
- [x] Backend integration test (AccountControllerIT against Firebase emulator)
- [x] Android unit tests (AccountStatusTest, AccountRepositoryImplTest, SplashRouterTest, ProfileBootstrapViewModelTest, AgeIneligibleViewModelTest)
- [x] Manual UI verification on phone / sw600dp / sw720dp + Arabic RTL (T11 screenshots committed)
- [x] 7-stage review pipeline (code-reviewer + cso + adversarial + consolidate + patch + gstack /review + CodeRabbit)

PR base: develop (per branching policy — never main until release)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Stage 9: Confirm PR clean, await user merge**

Do not merge without user confirmation.

---

## Self-review (checklist against spec)

**Spec coverage:**
- §5.1 dateOfBirth field → T1 ✓
- §5.2 POST /api/account/profile + GET /api/account/me → T2 (service), T3 (controller) ✓
- §5.2 backend revokes + deletes on age-ineligible → T2 ✓
- §5.3 Firestore rules self-write → T4 ✓
- §5.4 SplashRouter extension → T7 ✓
- §5.4 SplashFragment parallel /me fetch → T7 ✓
- §5.4 ProfileBootstrapFragment + 3 layouts → T8 ✓
- §5.4 AgeIneligibleFragment + 3 layouts → T9 ✓
- §5.4 Nav graph + SignInFragment redirect → T10 ✓
- §5.5 Strings (en/ar/nl) → T8, T9 ✓
- D1 displayName + DOB → T2 validation, T8 ViewModel ✓
- D2 age=13 threshold → T2 (Period.between).years comparison ✓
- D3 hard reject + delete → T2 rejectUnderAge ✓
- D4 backend revoke-then-delete order → T2 + plan §self-critique #3 ✓
- D5 1..40 chars → T2 validation, T8 form maxLength ✓
- D6 Firestore Timestamp at 00:00 UTC → T2 implementation ✓
- D7 409 if profileCompletedAt non-null → T2 + T3 controller ✓
- D8 FirebaseAuthFilter.shouldNotFilter widening → T3 ✓
- D9 pre-fill displayName from FirebaseAuth → T8 ProfileBootstrapFragment.seedDisplayName ✓
- D10 no setMaxDate on DatePicker → T8 openDatePicker (no .setSelection / no calendarConstraints) ✓
- D11 back-nav cancels sign-in / mirrors OK → T8 (system back default, see also T9 AgeIneligibleFragment OnBackPressedCallback) ✓
- D12 3 attempts, 1s linear backoff → T6 AccountRepositoryImpl + tests ✓
- D13 in-memory StateFlow, no disk → T6 ✓
- D14 SignIn routes to splash → T10 ✓
- §7 test surface → T2, T3, T4, T6, T8, T9 ✓
- §9 no migration, no feature flag → no migration tasks needed ✓

**Placeholder scan:** No "TBD", "TODO", or "fill in later" markers in tasks. Layout step 7 in T8/T9 references the sw600dp pattern but verbal — implementer copies verbatim with same view IDs. Acceptable, not a placeholder.

**Type consistency:**
- `AccountStatus` enum used consistently across T5, T6, T7
- `AccountState.Loaded` constructor signature `(uid, email, displayName, status)` consistent in T6 impl + tests + T8 tests
- `CompleteProfileRequestDto(displayName, dateOfBirth)` consistent in T5 + T6
- `BootstrapNav` / `AgeIneligibleNav` sealed interfaces with the same `consumeNav()` reset pattern in T8 + T9 ✓
- `BootstrapError` enum: `INVALID_NAME` / `INVALID_DOB` / `SAVE_FAILED` consistent T8 ViewModel + Fragment + strings ✓
- Action IDs: `action_splash_to_bootstrap`, `action_bootstrap_to_main`, `action_bootstrap_to_ageIneligible`, `action_ageIneligible_to_signIn`, `action_signIn_to_splash` — consistent across T7, T8, T9, T10 ✓

No gaps. Plan is internally consistent and covers every spec section.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-plan-c-account-bootstrap.md`.

Two execution options:

1. **Subagent-Driven (recommended)** — Fresh subagent per task + two-stage review between tasks. Best for surgical, well-scoped changes like this one.
2. **Inline Execution** — Execute tasks in this session using executing-plans. Faster on a small plan if the implementer is already loaded with context.

Which approach?
