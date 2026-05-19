# Plan G — Profile Edit + Moderator Suggest-Search (Design Spec)

**Status:** Draft — pending user review
**Author:** Claude (brainstorming session 2026-05-19)
**Supersedes:** —
**Extends:** Plan A (backend account), Plan C (account bootstrap), Plan E (moderator workflow)
**Target branch:** `feature/plan-g-profile-edit` (off `develop`)

---

## 1. Context

The Android app's Me tab has no entry point for users to edit their personal information after the one-time bootstrap, and the moderator content-suggestion flow shipped in Plan E supports URL paste only — its planned search-tab discovery surface was explicitly deferred. The backend stores all the fields needed (`User.displayName`, `User.dateOfBirth`, `User.email`, role-aware `RegistryController`), but `AccountController` exposes only `POST /api/account/profile` (one-shot bootstrap completion) and `GET /api/account/me`. There is no update endpoint.

This plan closes those gaps with one user-visible surface: a kebab overflow in the Me toolbar that opens a Profile page, plus — for moderator and admin roles — a second kebab item that opens a search-driven content-suggestion screen feeding into the existing Plan E submission pipeline.

### Verified state at design time

| Layer | Component | Status |
|---|---|---|
| Backend | `AccountController.POST /api/account/profile` | Shipped (Plan C) — bootstrap only |
| Backend | `AccountController.GET /api/account/me` | Shipped (Plan C) — lazy-create, role-preserving |
| Backend | `AccountProfileService.completeProfile` w/ ≥13 age gate | Shipped (Plan C) |
| Backend | `RegistryController.POST /channels|/playlists|/videos` | Shipped (Plan E) — `hasAnyRole('ADMIN','MODERATOR')` |
| Backend | `ApprovalController.GET /my-submissions` | Shipped (Plan E) |
| Backend | `SubmissionRateLimitInterceptor` (50/24h per uid) | Shipped (Plan E) |
| Backend | `YouTubeSearchController` at `/api/admin/youtube` | Shipped — only `POST /check-existing`; **no search endpoint** |
| Android | `MeFragment`, `AccountState.Loaded` w/ `role` field | Shipped (Plan B, E) |
| Android | `MySubmissionsFragment`, `SubmitContentBottomSheet` (URL-paste) | Shipped (Plan E) |
| Android | `ProfileBootstrapFragment` | Shipped (Plan C) — onboarding only |
| Android | Kebab on Me toolbar | **Missing** |
| Android | `ProfileFragment` (edit screen) | **Missing** |
| Android | `SuggestContentFragment` (search) | **Missing** |
| Vue admin | `ProfileSettingsView.vue` routed at `/settings/profile` | Routed but **not linked** from any nav |

---

## 2. Goals & non-goals

### Goals (Plan G core: G1–G3)

1. **G1** — Profile edit screen reachable from a Me-tab kebab item, with editable `displayName` and read-only email + DOB; backed by a new `PUT /api/account/profile` endpoint.
2. **G2** — Make DOB editable, reusing the existing ≥13 age-gate. Under-13 update → soft-delete + revoke refresh tokens path identical to bootstrap.
3. **G3** — Moderator-only `SuggestContentFragment` reachable from a second kebab item, backed by a new `GET /api/admin/youtube/search` (NewPipe-Extractor + Caffeine cache). Result tap pre-fills and opens the existing `SubmitContentBottomSheet`. Closes Plan E's deferred "search tab" item.

### Deferred (separate PRs after G1–G3)

- **G4** — Email change via Firebase `updateEmail` + re-auth modal + verification link.
- **G5** — Avatar: new `User.photoUrl` field, Firebase Storage bucket + rules, Android image picker/crop, server-side URL persistence.

### Non-goals

- No changes to the existing Plan E URL-paste submission pipeline beyond accepting a `prefillUrl` argument on `SubmitContentBottomSheet`.
- No changes to `MySubmissionsFragment` (the existing read-only list of the moderator's own submissions stays where it is).
- No changes to `ProfileBootstrapFragment` (bootstrap remains a separate one-shot flow).
- No admin-side Vue UI work other than (optionally) verifying whether `ProfileSettingsView.vue` was meant to be linked from a Plan F deliverable.
- No password change UI on Android (belongs in a dedicated Security screen, not the profile form).
- No locale persistence (device locale + existing Settings cover this today).

---

## 3. Architecture

### Navigation graph delta (`main_tabs_nav.xml`)

```
                              ┌──────────────────────┐
                              │   meFragment         │
                              │  (kebab MenuProvider)│
                              └──────────────────────┘
                                       │
                ┌──────────────────────┼─────────────────────────┐
                ▼                      ▼                         ▼
   action_me_to_profile      action_me_to_suggestContent     viewModel.signOut()
                │                      │
                ▼                      ▼
       ┌─────────────────┐   ┌─────────────────────────┐
       │ profileFragment │   │ suggestContentFragment  │
       └─────────────────┘   └─────────────────────────┘
                                       │
                                       ▼ (tap result)
                             ┌────────────────────────────┐
                             │ SubmitContentBottomSheet   │
                             │ (existing — prefillUrl arg)│
                             └────────────────────────────┘
                                       │
                                       ▼
                             ┌────────────────────────────┐
                             │ POST /api/admin/registry/* │
                             │      (existing pipeline)   │
                             └────────────────────────────┘
```

### Layered view

```
┌─────────────────────────────────────────────────────────────────┐
│ Android                                                         │
│                                                                 │
│  MeFragment ── MenuProvider ── kebab items                      │
│                                                                 │
│  ProfileFragment ── ProfileViewModel ── AccountUpdateRepository │
│                                                  │              │
│                                                  ▼              │
│                                       Retrofit AccountUpdateApi │
│                                                                 │
│  SuggestContentFragment ── SuggestContentViewModel              │
│                                       │                         │
│                                       ▼                         │
│                            YouTubeSearchRepository              │
│                                       │                         │
│                                       ▼                         │
│                            Retrofit YouTubeSearchApi            │
└─────────────────────────────────────────────────────────────────┘
                                       │
              HTTPS  (Bearer ID token via FirebaseAuthInterceptor)
                                       │
┌─────────────────────────────────────────────────────────────────┐
│ Spring Boot backend                                             │
│                                                                 │
│  AccountController                                              │
│    PUT  /api/account/profile  →  AccountProfileService          │
│                                    .updateProfile(uid, body)    │
│                                    .enforceAgeOrReject(dob)     │
│                                       │                         │
│                                       ▼                         │
│                                  UserRepository                 │
│                                                                 │
│  YouTubeSearchController                                        │
│    GET  /api/admin/youtube/search →  YouTubeSearchService       │
│                                       │                         │
│                                  NewPipe SearchExtractor        │
│                                  + Caffeine cache               │
│                                  + ChannelRepository.exists()   │
│                                  + PlaylistRepository.exists()  │
│                                  + VideoRepository.exists()     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Phasing

| Phase | Scope | PR | Est. | Risk |
|---|---|---|---|---|
| **G1** | Kebab + `ProfileFragment` (displayName edit) + `PUT /api/account/profile` | 1 (combined w/ G2, G3) | ~1 day | Low |
| **G2** | DOB editable, shared age-gate, under-13 soft-delete path | 1 | ~1 day | Low |
| **G3** | `SuggestContentFragment` + `GET /api/admin/youtube/search` (NewPipe-backed) | 1 | ~2 days | Low |
| **G4** | Email change via Firebase `updateEmail` + re-auth | 2 | ~3 days | Medium |
| **G5** | Avatar: `photoUrl` field + Firebase Storage + picker | 3 | ~3 days | Medium |

**G1 + G2 + G3 ship as one PR** (≈4 days work). G4 and G5 ship as separate PRs because each opens a new external surface (Firebase Auth re-flow / Firebase Storage) that doesn't share code with the rest.

---

## 5. Backend changes

### 5.1  G1 + G2 — `PUT /api/account/profile`

**`AccountController.java` (append):**
```java
@PutMapping("/profile")
public ResponseEntity<AccountMeResponse> updateProfile(
    @AuthenticationPrincipal FirebaseUserDetails principal,
    @RequestBody @Valid UpdateProfileRequest body) {
    return ResponseEntity.ok(accountProfileService.updateProfile(principal.getUid(), body));
}
```

**`UpdateProfileRequest.java` (new DTO):**
```java
public record UpdateProfileRequest(
    @Size(min = 1, max = 80) String displayName,    // null = no change
    @Nullable Timestamp dateOfBirth                  // null = no change
) {}
```

**`AccountProfileService.java` (extend):**
```java
public AccountMeResponse updateProfile(String uid, UpdateProfileRequest body) {
    User user = userRepository.findById(uid)
        .orElseThrow(() -> new UserNotFoundException(uid));

    // Short-circuit no-op (idempotent)
    if (isNoOpUpdate(user, body)) {
        return AccountMeResponse.from(user);
    }

    // Validate display name
    if (body.displayName() != null) {
        validateDisplayName(body.displayName());
    }

    // Age gate — shared with completeProfile()
    if (body.dateOfBirth() != null) {
        enforceAgeOrReject(uid, body.dateOfBirth());     // throws AgeIneligibleException + soft-deletes
    }

    // Apply
    User updated = user.copy();
    if (body.displayName() != null) updated.setDisplayName(body.displayName().trim());
    if (body.dateOfBirth() != null) updated.setDateOfBirth(body.dateOfBirth());
    updated.setUpdatedAt(Timestamp.now());
    userRepository.save(updated);

    auditLogService.logProfileEdit(uid, changedFields(user, updated));   // new method on AuditLogService — see note below
    return AccountMeResponse.from(updated);
}

private void enforceAgeOrReject(String uid, Timestamp dob) { /* extracted from completeProfile */ }
private void validateDisplayName(String name) { /* length, no control chars, no URL pattern */ }
private boolean isNoOpUpdate(User u, UpdateProfileRequest body) { /* field-by-field equality */ }
```

**Refactor notes:**
- `enforceAgeOrReject` and `validateDisplayName` are extracted from the existing `completeProfile()` body so both endpoints share the exact same guarantees. `completeProfile()` continues to work unchanged from the caller's perspective.
- `AuditLogService.logProfileEdit(uid, changedFields)` is a new method to add — pattern follows the existing `logAgeIneligible` call site in `AccountProfileService.rejectUnderAge` (claude-mem 12906). The audit row records uid, timestamp, and the diff of changed fields (no PII in the diff body beyond what's already in the User doc).

**`ProfileUpdateRateLimitInterceptor.java` (new — mirrors existing `SubmissionRateLimitInterceptor`):**
- Sliding window, 10 updates per hour per uid
- 429 response with `Retry-After` header + body `{"code":"RATE_LIMIT","retryAfterSeconds":<n>}`
- Wired in `WebConfig.addInterceptors` only for `PUT /api/account/profile`
- In-memory backing store (same as existing rate limiter); Redis-backed migration deferred if multi-instance

### 5.2  G3 — `GET /api/admin/youtube/search`

**`YouTubeSearchController.java` (append — class is already `@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")`):**
```java
@GetMapping("/search")
public YouTubeSearchResponse search(
    @RequestParam @NotBlank @Size(max = 200) String q,
    @RequestParam YouTubeContentType type,
    @RequestParam(required = false) String pageToken) {
    return youtubeSearchService.search(q, type, pageToken);
}

public enum YouTubeContentType { CHANNEL, PLAYLIST, VIDEO }
```

**`YouTubeSearchService.java` (new):**
```java
@Service
public class YouTubeSearchService {
    @Cacheable(value = "youtubeModeratorSearch",
               key = "#q + ':' + #type + ':' + (#pageToken ?: '')",
               unless = "#result == null")
    public YouTubeSearchResponse search(String q, YouTubeContentType type, String pageToken) {
        SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(
            q, contentFiltersFor(type), /*sortFilter*/ "");
        extractor.fetchPage();

        InfoItemsPage<InfoItem> page = (pageToken == null)
            ? extractor.getInitialPage()
            : extractor.getPage(decodePageToken(pageToken));

        List<SearchHit> hits = page.getItems().stream()
            .map(item -> toHit(item, type))
            .filter(Objects::nonNull)            // skip items of wrong type that slipped through
            .toList();

        annotateAlreadyKnown(hits, type);        // single batch repo lookup
        return new YouTubeSearchResponse(hits, encodePageToken(page.getNextPage()));
    }
}
```

**Response DTOs:**
```java
public record YouTubeSearchResponse(
    List<SearchHit> items,
    @Nullable String nextPageToken
) {}

public record SearchHit(
    String youtubeId,
    String name,
    String url,
    @Nullable String thumbnailUrl,
    @Nullable String secondary,        // channel name (video/playlist) | subscriber count (channel)
    boolean alreadyKnown,
    @Nullable String knownStatus       // APPROVED | PENDING | REJECTED
) {}
```

**Already-known annotation:** batch lookup against `channelRepository.findAllByYoutubeIdIn(ids)` / `playlistRepository.findAllByYoutubeIdIn(ids)` / `videoRepository.findAllByYoutubeIdIn(ids)` (single Firestore `whereIn` query per page). Maps id → `approvalMetadata.status`. Moderators see the badge before they tap, preventing redundant submissions.

**Cache:** new `youtubeModeratorSearch` Caffeine entry, 30 min TTL, max 500 entries. **Implementation note:** register the new cache name in `application.yml` (`spring.cache.cache-names`) and `application-prod.yml` (Redis variant) alongside the existing `youtubeChannelSearch` / `youtubePlaylistSearch` / `youtubeVideoSearch` entries — Caffeine and Redis both need explicit cache-name registration in this project.

**Page token encoding:** NewPipe `Page` → JSON via Jackson → base64 URL-safe → opaque string. Decode on next call. Token round-trips through the Android client unchanged.

### 5.3  G4 (deferred) — Email change

- Android: re-auth dialog (current password) → `FirebaseAuth.getCurrentUser().updateEmail(newEmail)` → "verification link sent" state
- Backend: new endpoint `POST /api/account/email-changed` revokes all other refresh tokens; subsequent `/me` call reflects new email automatically (token claim drives it)
- Edge cases: provider-linked accounts (Google sign-in users cannot change email via this path), already-in-use email collisions

### 5.4  G5 (deferred) — Avatar

- New field `User.photoUrl`
- Firebase Storage bucket `gs://<project>-avatars`, rule `/avatars/{uid}.jpg` writable only by owner, max 2 MB, content-type allowlist [jpg, png]
- Android: image picker via Activity Result API → crop to 1:1 → upload via Firebase Storage SDK → `PUT /api/account/profile {photoUrl}`
- Server-side resize optional (defer; client can produce 512×512 before upload)

---

## 6. Android changes

### 6.1  Kebab via `MenuProvider`

**`MeFragment.kt` — append in `onViewCreated`:**
```kotlin
requireActivity().addMenuProvider(object : MenuProvider {
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_me_kebab, menu)
        val role = viewModel.snapshotRole()
        menu.findItem(R.id.action_suggest_content).isVisible =
            role.equals("moderator", ignoreCase = true) ||
            role.equals("admin", ignoreCase = true)
    }
    override fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_profile        -> { findNavController().navigate(R.id.action_me_to_profile); true }
        R.id.action_suggest_content -> { findNavController().navigate(R.id.action_me_to_suggestContent); true }
        R.id.action_sign_out       -> { viewModel.signOut(); true }
        else                        -> false
    }
}, viewLifecycleOwner, Lifecycle.State.RESUMED)
```

**`MeViewModel.snapshotRole()`** — one-shot read from `AccountState.Loaded.role`, returns `""` if not loaded (kebab still shows Profile + Sign out; Suggest hidden). Same one-shot pattern Plan E uses for `MySubmissions` visibility — role is stable for the fragment lifetime.

**`res/menu/menu_me_kebab.xml` (new):**
```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android"
      xmlns:app="http://schemas.android.com/apk/res-auto">
    <item android:id="@+id/action_profile"
          android:title="@string/me_kebab_profile"
          app:showAsAction="never"/>
    <item android:id="@+id/action_suggest_content"
          android:title="@string/me_kebab_suggest_content"
          android:visible="false"
          app:showAsAction="never"/>
    <item android:id="@+id/action_sign_out"
          android:title="@string/me_kebab_sign_out"
          app:showAsAction="never"/>
</menu>
```

### 6.2  ProfileFragment + ViewModel

**Files added:**
```
ui/me/profile/ProfileFragment.kt
ui/me/profile/ProfileViewModel.kt
res/layout/fragment_profile.xml
res/layout-sw600dp/fragment_profile.xml
res/layout-sw720dp/fragment_profile.xml
data/account/AccountUpdateApi.kt
data/account/AccountUpdateRepository.kt
data/account/dto/UpdateProfileRequestDto.kt
di/AccountUpdateModule.kt
```

**Layout (`layout/fragment_profile.xml`):**
```xml
<ScrollView fitsSystemWindows="true">
  <LinearLayout vertical paddingHorizontal=spacing_md>
    <MaterialCardView>
      <LinearLayout>
        <TextView text="@string/profile_personal_info" style="?attr/textAppearanceTitleMedium"/>
        <TextInputLayout id=@+id/displayNameLayout hint=@string/profile_display_name>
          <TextInputEditText id=@+id/displayNameInput maxLength=80 textAlignment="viewStart"/>
        </TextInputLayout>
        <LinearLayout id=@+id/dobRow orientation=horizontal>
          <TextView id=@+id/dobLabel text=@string/profile_date_of_birth/>
          <TextView id=@+id/dobValue clickable=true focusable=true/>
        </LinearLayout>
        <TextView id=@+id/email text="" helperText=@string/profile_email_locked enabled=false/>
      </LinearLayout>
    </MaterialCardView>
    <Button id=@+id/saveButton text=@string/profile_save enabled=false/>
    <TextView id=@+id/lastUpdated text=@string/profile_last_updated_template/>
  </LinearLayout>
</ScrollView>
```

**Tablet/TV variants:** two-column layout via `ConstraintLayout` (display name left, DOB+email right). Same view IDs across all three variants per project rule. TV variant (`sw720dp`) preserves focus order top-to-bottom then left-to-right for D-pad navigation; the Save button stays the last focusable element so D-pad-down on the last field lands on Save.

**ViewModel state machine:**
```kotlin
sealed class ProfileUiState {
    object Loading : ProfileUiState()
    data class Editing(
        val original: ProfileFields,
        val draft: ProfileFields,
        val saving: Boolean = false,
        val error: ProfileError? = null
    ) : ProfileUiState() {
        val isDirty: Boolean get() = original != draft
    }
    object SignedOut : ProfileUiState()   // post-AgeIneligible
}

sealed class ProfileError {
    object Network : ProfileError()
    data class RateLimited(val retryAfterSec: Long) : ProfileError()
    object AgeIneligible : ProfileError()
    data class Validation(val field: String, val message: String) : ProfileError()
    object Unknown : ProfileError()
}

data class ProfileFields(
    val displayName: String,
    val dateOfBirth: LocalDate?,        // null if not yet captured
    val emailReadOnly: String
)
```

**AccountState propagation:** on successful save, `AccountRepository.applyProfileUpdate(updatedDto)` emits a new `AccountState.Loaded` (immutable data class) via its existing `StateFlow`, replacing the previous instance with the updated `displayName` / `dateOfBirth`. The Me-tab header — which already collects this flow — re-renders immediately without a `/me` round-trip. A new method on `AccountRepository` is added; the existing `/me` refresh path is untouched.

### 6.3  SuggestContentFragment + ViewModel

**Files added:**
```
ui/me/suggest/SuggestContentFragment.kt
ui/me/suggest/SuggestContentViewModel.kt
ui/me/suggest/SuggestResultsAdapter.kt
res/layout/fragment_suggest_content.xml
res/layout-sw600dp/fragment_suggest_content.xml
res/layout-sw720dp/fragment_suggest_content.xml
res/layout/item_suggest_result.xml
data/search/YouTubeSearchApi.kt
data/search/YouTubeSearchRepository.kt
data/search/dto/SearchHitDto.kt
di/SearchModule.kt
```

**Layout:**
```xml
<LinearLayout vertical>
  <com.google.android.material.search.SearchBar id=@+id/searchBar/>
  <ChipGroup id=@+id/typeChips singleSelection=true>
    <Chip id=@+id/chipChannel text=@string/suggest_type_channels checked=true/>
    <Chip id=@+id/chipPlaylist text=@string/suggest_type_playlists/>
    <Chip id=@+id/chipVideo text=@string/suggest_type_videos/>
  </ChipGroup>
  <RecyclerView id=@+id/results/>
  <ProgressBar id=@+id/loading visibility=gone/>
  <include id=@+id/emptyState layout=@layout/view_suggest_empty/>
</LinearLayout>
```

**ViewModel:**
- Search query is a `MutableStateFlow<String>` debounced 300 ms
- Type selection is a `MutableStateFlow<YouTubeContentType>`
- `combine(query, type)` triggers a paged search via `YouTubeSearchRepository`
- Pagination: scroll listener triggers `loadMore` at `PREFETCH_DISTANCE=10`; `submitList` callback also runs `canScrollVertically(1)` autofill check for tablet/TV grids (project rule from CLAUDE.md)

**Result-tap flow:**
```kotlin
override fun onResultClick(hit: SearchHit) {
    if (hit.alreadyKnown && hit.knownStatus in setOf("APPROVED", "PENDING")) {
        snack(R.string.suggest_already_in_registry)
        return
    }
    SubmitContentBottomSheet
        .newInstance(prefillUrl = hit.url)
        .show(childFragmentManager, "submit")
}
```

If `SubmitContentBottomSheet` does not already accept a `prefillUrl` arg, add one in this PR — minimal: a single `Bundle` key + `requireArguments().getString(ARG_URL)` in `onViewCreated` that pre-fills the URL field.

---

## 7. Data flow

### 7.1  Save profile (G1 + G2)

```
ProfileFragment "Save" tap
  → ProfileViewModel.save(draft)
    → AccountUpdateRepository.updateProfile(UpdateProfileRequest)
      → AccountUpdateApi.PUT /api/account/profile  [Bearer ID token via FirebaseAuthInterceptor]
        ← 200 + AccountMeResponse
      → AccountRepository.applyProfileUpdate(response)   // mutates AccountState.Loaded
      ← ProfileUiState.Editing(original=new, draft=new, saving=false)
      → snack "Profile updated"
```

### 7.2  Search and submit (G3)

```
SearchBar text change
  → debounce(300ms) + type chip
  → SuggestContentViewModel triggers
    → YouTubeSearchRepository.search(q, type, pageToken=null)
      → YouTubeSearchApi.GET /api/admin/youtube/search
        ← 200 + YouTubeSearchResponse { items, nextPageToken }
      → SuggestUiState.Results(items, nextPageToken)

Result tap
  → SubmitContentBottomSheet.newInstance(prefillUrl=hit.url).show(...)
    → existing Plan E pipeline:
      → URL parser → ParsedYouTubeUrl
      → category picker
      → POST /api/admin/registry/{channels|playlists|videos}
        ← 201 (PENDING) | 429 (rate limit) | 4xx
      → MySubmissionsFragment refresh on next view
```

---

## 8. Error handling

### 8.1  PUT /api/account/profile

| Backend → | Android maps to | UI behavior |
|---|---|---|
| 200 OK | `ProfileUiState.Editing(saving=false)` | Snackbar "Profile updated" |
| 422 `AGE_INELIGIBLE` | `ProfileError.AgeIneligible` | Non-dismissable dialog → `auth.signOut()` (backend already soft-deleted) |
| 429 `RATE_LIMIT` | `ProfileError.RateLimited(retryAfterSec)` | Snackbar "Too many updates. Try again in N min." |
| 400 validation | `ProfileError.Validation(field, msg)` | Inline error on the offending `TextInputLayout` |
| 401 / 403 | navigate to `signInFragment` | Auto re-auth (existing behavior) |
| 5xx / network | `ProfileError.Network` | Snackbar with Retry; save button re-enabled |

### 8.2  GET /api/admin/youtube/search

| Backend → | Android maps to | UI behavior |
|---|---|---|
| 200 OK + empty items | `SuggestUiState.Empty` | "No results for '<query>'" view |
| 200 OK + items | `SuggestUiState.Results` | Render list |
| 403 | `SuggestUiState.ForbiddenRole` | Should never happen (kebab item is role-gated); show generic error + log |
| 429 | `SuggestUiState.RateLimited` | "Search rate-limited" snackbar; debounce already keeps us well under |
| 5xx / network | `SuggestUiState.Error` | Snackbar + retry button |
| NewPipe `ParsingException` upstream | 502 | Snackbar "YouTube response changed; try again" |

---

## 9. Testing

| Layer | File | Coverage |
|---|---|---|
| Backend unit | `AccountControllerUpdateProfileTest` | Happy path; partial update (name only / DOB only / both); display-name validation; idempotent no-op returns 200; rate-limit 429 |
| Backend integration | `UpdateProfileIT` | Full Firestore round-trip; DOB-bypass guard (under-13 update → 422 + soft-deleted user + revoked tokens); audit log row emitted |
| Backend unit | `YouTubeSearchServiceTest` | Channel / playlist / video content filters; `alreadyKnown` annotation against mocked repos; page-token round-trip (encode → decode → encode); Caffeine cache hit on second identical call |
| Backend integration | `YouTubeSearchControllerIT` | MODERATOR allowed (200), ADMIN allowed (200), USER → 403; bad `type` → 400; empty `q` → 400 |
| Android unit | `ProfileViewModelTest` | Load → Editing; dirty detection (original vs draft); save success refreshes AccountState; error mapping (rate-limit / age-gate / validation / network) |
| Android unit | `AccountUpdateRepositoryTest` | PUT 200 → success; 429 → `RateLimitError(retryAfterSec)`; 422 → `AgeIneligibleError`; 4xx generic → `Unknown` |
| Android unit | `SuggestContentViewModelTest` | Debounced query coalesces; type switch resets list; `alreadyKnown` surfaces; pagination token advances; rate-limited render |
| Android unit | `YouTubeSearchRepositoryTest` | OK / 403 / 5xx / empty results / page-token round-trip |
| Android Espresso (optional) | `MeKebabTest` | Kebab shows Profile + Sign out for USER; +Suggest for MODERATOR; navigation actions wired |

Total expected new test files: **9** (8 + 1 optional Espresso).

---

## 10. Rollout & feature flags

- No feature flag needed for G1 / G2 — additive backend endpoint + new Android nav destination. Old clients keep working unchanged.
- G3 also additive — old clients without the kebab item never hit the new search endpoint.
- Ship Android side **after** backend is in production (typical lag is hours, but make sure of order).
- No DB migration. `User.dateOfBirth` already exists (Plan C T1, claude-mem 11236). No new fields for G1–G3.
- Backwards compat: `UpdateProfileRequest` fields are all optional → forward compat for adding more fields later.

---

## 11. Out of scope (this plan)

- **G4** email change — separate PR with its own design section
- **G5** avatar — separate PR with its own design section
- Vue admin nav-link to `ProfileSettingsView` — verify-only task; if Plan F was supposed to wire it, that's a Plan F follow-up
- Notifications preferences screen
- Password change screen
- Locale persistence per user

---

## 12. Plan-doc revisions

The following existing plan documents get **append-only notes** at the end of their respective files (no structural changes):

| Plan file | Append |
|---|---|
| `docs/superpowers/plans/2026-05-10-p0-firebase-sa-scrub.md` | _(no change — unrelated)_ |
| `docs/superpowers/plans/2026-05-10-plan-a-backend-account-foundation.md` | "## 2026-05-19 follow-up — Plan G extended `AccountController` with `PUT /api/account/profile` for personal-info edit (display name + DOB). See `2026-05-19-plan-g-profile-edit-and-suggest-search.md`." |
| `docs/superpowers/plans/2026-05-11-plan-b-android-auth.md` | _(no change — unrelated)_ |
| `docs/superpowers/plans/2026-05-11-plan-c-account-bootstrap.md` | "## 2026-05-19 follow-up — `AccountProfileService.completeProfile` refactored in Plan G to share its ≥13 age-gate (`enforceAgeOrReject`) with new `updateProfile`. Under-13 update → soft-delete + revoke path is identical to bootstrap." |
| `docs/superpowers/plans/2026-05-12-plan-d-sync-engine.md` | _(no change — unrelated)_ |
| `docs/superpowers/plans/2026-05-12-plan-e-moderator-workflow.md` | "## 2026-05-19 follow-up — The 'Search tab' deferred from `SubmitContentBottomSheet` was replaced in Plan G by a dedicated `SuggestContentFragment` reachable from the Me-tab kebab. Search hits a new server-side `GET /api/admin/youtube/search` (NewPipe-backed). The bottom sheet remains URL-paste and now also accepts a `prefillUrl` argument from search results." |
| `docs/superpowers/plans/2026-05-12-plan-f-admin-user-management.md` | "## 2026-05-19 follow-up — Verify whether `frontend/src/views/ProfileSettingsView.vue` (routed at `/settings/profile` per `router/index.ts:106`) was intended to be linked from this plan's deliverables. It is routed but not linked from any admin nav menu. Plan G does not wire it." |

These edits are made when the implementation plan executes — not during this spec.

---

## 13. Open questions for review

- **Sign out duplication.** Settings already has Sign out. Kebab includes it per the user's accepted preview. Keep, or drop?
- **DOB read-only-by-default toggle.** Current design makes DOB editable; alternative is a setting like "DOB editable: false (require support request)" if compliance later requires it.
- **`alreadyKnown` filtering.** Should already-PENDING and already-APPROVED items be filtered out of search results, or just badged? Current design badges them (less surprise; clearer).
- **Cache TTL for `youtubeModeratorSearch`.** 30 min vs the 1 h used by other YouTube caches. Picked 30 min because moderators iterate on the same query while curating, but inconsistency may not be worth it.

---

## 14. References

- `CLAUDE.md` — project rules: navigation icons, layout qualifiers, Edge-to-edge, pagination on large screens
- `docs/library-guides/newpipe-extractor.md` — NewPipe API reference for the search endpoint
- `docs/superpowers/specs/2026-05-12-plan-e-moderator-workflow-design.md` — pattern for moderator-gated UI surfaces
- claude-mem `13926` — backend support for moderator suggestion + approval (verified 2026-05-19)
- claude-mem `13930` — backend role/moderator system (verified 2026-05-19)
- claude-mem `13942` — Plan E PR #16 merge + `AccountState.role` field
- claude-mem `12697` / `12730` — `AccountController` + `AccountProfileService` bootstrap shape

---

_End of design spec. Next step: user review of this document, then `writing-plans` skill to produce the executable plan._
