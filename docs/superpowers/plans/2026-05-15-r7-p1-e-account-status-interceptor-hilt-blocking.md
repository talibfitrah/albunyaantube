# AccountStatusInterceptor Hilt-Init Blocking Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `accountRepositoryProvider.get()` blocking calls inside the OkHttp `Authenticator`/`Interceptor` chain with a lazy event-bus or already-resolved reference so a Hilt-init storm during cold start cannot deadlock the network thread.

**Architecture:** Tier C's R5 P0 fix added a `Provider<AccountRepository>` to break a Hilt construction cycle (`AccountStatusInterceptor → OkHttp → AccountRepository → AccountStatusInterceptor`). The Provider works for steady-state but in cold-start scenarios the first network call can fire from `Application.onCreate()` BEFORE the Provider's target dependency graph has finished construction, causing `provider.get()` to block the OkHttp dispatcher thread. Fix: route status events through a `MutableSharedFlow<AccountStatusEvent>` owned at the Application scope; the interceptor emits to the flow, the repository (constructed lazily and lifecycle-bound) collects from it.

**Tech Stack:** Hilt DI, OkHttp 4 Interceptor + Authenticator, Kotlin coroutines (SharedFlow), JUnit 4 + Mockito-Kotlin + Turbine.

**Spec source:** Cubic R7 P1 finding (Plan 0 → HEAD review, 2026-05-15). Tier C R5 P0 cycle-break commit: `66ad8446` (visible via `git log`).

**Ticket prefix:** `AUTH-INTERCEPT-DECOUPLE-01`. Branch: `feature/AUTH-INTERCEPT-DECOUPLE-01-shared-flow`. Commit prefix: `[FIX-AUTH-INTERCEPT-DECOUPLE-01-Tn]`.

---

## File Structure

| Path | Responsibility | Change type |
|------|----------------|-------------|
| `android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusBus.kt` | Singleton SharedFlow of status events emitted by interceptor, collected by repository | Create |
| `android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusInterceptor.kt` | Emit to `AccountStatusBus` instead of holding a Provider | Modify |
| `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt` | Subscribe to `AccountStatusBus` from a coroutine started in `init` | Modify |
| `android/app/src/main/java/com/albunyaan/tube/di/AppModule.kt` | Bind `AccountStatusBus` as `@Singleton` | Modify |
| `android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusBusTest.kt` | Event-shape coverage | Create |
| `android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusInterceptorTest.kt` | Replace `provider.get()` mocking with bus emit verification | Modify |

---

## Task 1: Create AccountStatusBus

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusBus.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusBusTest.kt`

- [ ] **Step 1: Write the bus class**

```kotlin
package com.albunyaan.tube.auth

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cubic R7 P1 (AUTH-INTERCEPT-DECOUPLE-01) — replaces the
 * Provider<AccountRepository> hack added in Tier C R5 P0. The interceptor
 * emits to this bus instead of calling provider.get() on the OkHttp
 * dispatcher thread. The repository subscribes from a lifecycle-bound
 * coroutine. Decoupling lets cold-start network calls proceed without
 * blocking on the Hilt DI graph reaching a usable state.
 *
 * BUFFER_CAPACITY=16 + SUSPEND overflow strategy: under a storm of
 * status events the interceptor would suspend on emit rather than drop
 * events. Acceptable because the interceptor runs on OkHttp's network
 * dispatcher (already a background thread pool) and the bus is drained
 * by the repository on a dedicated coroutine.
 */
@Singleton
class AccountStatusBus @Inject constructor() {
    private val _events = MutableSharedFlow<AccountStatusEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<AccountStatusEvent> = _events.asSharedFlow()

    suspend fun emit(event: AccountStatusEvent) = _events.emit(event)

    companion object {
        const val BUFFER_CAPACITY = 16
    }
}

sealed interface AccountStatusEvent {
    /** 403 with code=BLOCKED or DELETED on the response — sign out, surface UI. */
    data class AccountUnavailable(val httpCode: Int, val code: String) : AccountStatusEvent

    /** 401 — token refresh failed or token revoked server-side. */
    data object Unauthenticated : AccountStatusEvent
}
```

- [ ] **Step 2: Write smoke test**

`AccountStatusBusTest.kt`:

```kotlin
package com.albunyaan.tube.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class AccountStatusBusTest {

    @Test fun emit_thenCollect_deliversEvent() = runTest {
        val bus = AccountStatusBus()
        bus.events.test {
            bus.emit(AccountStatusEvent.AccountUnavailable(403, "BLOCKED"))
            assertEquals(AccountStatusEvent.AccountUnavailable(403, "BLOCKED"), awaitItem())
            cancel()
        }
    }

    @Test fun emit_unauthenticated_delivers() = runTest {
        val bus = AccountStatusBus()
        bus.events.test {
            bus.emit(AccountStatusEvent.Unauthenticated)
            assertEquals(AccountStatusEvent.Unauthenticated, awaitItem())
            cancel()
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:test --tests AccountStatusBusTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusBus.kt \
        android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusBusTest.kt
git commit -m "[FIX-AUTH-INTERCEPT-DECOUPLE-01-T1]: AccountStatusBus event channel"
```

---

## Task 2: AccountStatusInterceptor emits to bus

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusInterceptor.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusInterceptorTest.kt`

- [ ] **Step 1: Read the current interceptor**

Run: `sed -n '1,80p' android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusInterceptor.kt`

- [ ] **Step 2: Write failing test (bus emit, no provider.get)**

In `AccountStatusInterceptorTest.kt`, replace the existing provider-based tests with bus-collection assertions. Example new test:

```kotlin
@Test fun blockedResponse_emitsAccountUnavailable() = runTest {
    val bus = AccountStatusBus()
    val interceptor = AccountStatusInterceptor(bus, this.coroutineContext)
    val chain = mock<Interceptor.Chain>()
    val req = Request.Builder().url("https://x/api/admin/x").build()
    val resp = Response.Builder()
        .request(req).protocol(Protocol.HTTP_1_1).code(403)
        .body("""{"code":"BLOCKED"}""".toResponseBody("application/json".toMediaType()))
        .message("Forbidden").build()
    whenever(chain.request()).thenReturn(req)
    whenever(chain.proceed(req)).thenReturn(resp)

    bus.events.test {
        interceptor.intercept(chain)
        val event = awaitItem()
        assertEquals(AccountStatusEvent.AccountUnavailable(403, "BLOCKED"), event)
        cancel()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:test --tests AccountStatusInterceptorTest.blockedResponse_emitsAccountUnavailable`
Expected: FAIL — constructor signature still takes `Provider<AccountRepository>`.

- [ ] **Step 4: Replace Provider with AccountStatusBus**

Modify the constructor and intercept logic. Pre-fix:

```kotlin
class AccountStatusInterceptor @Inject constructor(
    private val accountRepositoryProvider: Provider<AccountRepository>,
) : Interceptor { /* ... call accountRepositoryProvider.get().signOut() on block ... */ }
```

Post-fix:

```kotlin
@Singleton
class AccountStatusInterceptor @Inject constructor(
    private val bus: AccountStatusBus,
    // Application-scoped coroutine context for emit. Injected so tests can
    // pass `TestScope().coroutineContext`. Production binds it to a
    // SupervisorJob in AppModule (Task 4).
    @Named("appCoroutineContext") private val emitContext: CoroutineContext,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath
        if (!path.startsWith("/api/admin/") && !path.startsWith("/api/v1/")) {
            return response
        }
        if (response.code == 401) {
            CoroutineScope(emitContext).launch {
                bus.emit(AccountStatusEvent.Unauthenticated)
            }
            return response
        }
        if (response.code == 403) {
            val bodyCode = peekBodyCode(response)
            if (bodyCode == "BLOCKED" || bodyCode == "DELETED") {
                CoroutineScope(emitContext).launch {
                    bus.emit(AccountStatusEvent.AccountUnavailable(403, bodyCode))
                }
            }
        }
        return response
    }

    private fun peekBodyCode(response: Response): String? {
        // Reuse the bounded-read shape from R8 P2 AccountRepositoryImpl.bodyHasCode.
        val errorBody = response.peekBody(MAX_PEEK_BYTES)
        val text = errorBody.string()
        val match = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(text)
        return match?.groupValues?.get(1)
    }

    companion object {
        private const val MAX_PEEK_BYTES = 4_096L
    }
}
```

- [ ] **Step 5: Run all interceptor tests**

Run: `./gradlew :app:test --tests AccountStatusInterceptorTest`
Expected: PASS — new test passes; existing path-prefix gating tests adjusted to use the bus instead of the provider mock.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountStatusInterceptor.kt \
        android/app/src/test/java/com/albunyaan/tube/auth/AccountStatusInterceptorTest.kt
git commit -m "[FIX-AUTH-INTERCEPT-DECOUPLE-01-T2]: interceptor emits to AccountStatusBus"
```

---

## Task 3: AccountRepositoryImpl subscribes to the bus

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt`

- [ ] **Step 1: Inject the bus and an application-scoped coroutine scope**

Add to the constructor:

```kotlin
class AccountRepositoryImpl @Inject constructor(
    /* existing deps... */
    private val statusBus: AccountStatusBus,
    @Named("appScope") private val appScope: CoroutineScope,
) : AccountRepository {

    init {
        // Cubic R7 P1 (AUTH-INTERCEPT-DECOUPLE-01) — collect status events
        // from the bus instead of being constructed by the interceptor.
        // Pre-fix Tier C's R5 P0 Provider<AccountRepository> hack worked
        // for steady-state but a cold-start network call (fired from
        // Application.onCreate() before the Hilt graph reached this
        // repository) could deadlock the OkHttp dispatcher on
        // provider.get(). The bus indirection lets the interceptor emit
        // immediately while the repository drains at its own pace.
        appScope.launch {
            statusBus.events.collect { event ->
                when (event) {
                    is AccountStatusEvent.AccountUnavailable -> {
                        // Pre-fix: provider.get().signOut() AND emit a UI state.
                        signOut()
                        _accountState.value = AccountState.Failed(
                            httpCode = event.httpCode,
                            message = "account.${event.code.lowercase()}",
                            cause = null,
                        )
                    }
                    AccountStatusEvent.Unauthenticated -> {
                        signOut()
                        _accountState.value = AccountState.Failed(
                            httpCode = 401,
                            message = "account.unauthenticated",
                            cause = null,
                        )
                    }
                }
            }
        }
    }
    // ... rest of class unchanged ...
}
```

- [ ] **Step 2: Compile**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Run all repository tests**

Run: `./gradlew :app:test --tests AccountRepositoryImplTest`
Expected: PASS — pre-existing tests unaffected; the bus collector is started in `init` and the existing tests don't exercise the interceptor path.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt
git commit -m "[FIX-AUTH-INTERCEPT-DECOUPLE-01-T3]: repository subscribes to AccountStatusBus"
```

---

## Task 4: AppModule bindings

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/AppModule.kt`

- [ ] **Step 1: Bind app coroutine scope + context**

Within `AppModule`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @Named("appScope")
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("AppScope"))

    @Provides
    @Singleton
    @Named("appCoroutineContext")
    fun provideAppCoroutineContext(@Named("appScope") scope: CoroutineScope): CoroutineContext =
        scope.coroutineContext

    // Remaining provides unchanged...
}
```

- [ ] **Step 2: Remove the now-unused Provider<AccountRepository> binding wiring**

Search for any `@Provides` of `Provider<AccountRepository>` (Hilt generally generates this automatically, so likely none). Confirm nothing else references the Provider:

Run: `grep -rn "Provider<AccountRepository>" android/app/src`
Expected: 0 matches after Task 2 + Task 3 are merged.

- [ ] **Step 3: Compile + run all unit tests**

Run: `cd android && ./gradlew :app:test`
Expected: SUCCESS modulo the pre-existing failures noted in Task 3 / SYNC-CURSOR-PERSIST-01.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/di/AppModule.kt
git commit -m "[FIX-AUTH-INTERCEPT-DECOUPLE-01-T4]: appScope + appCoroutineContext bindings"
```

---

## Test plan

- Unit: `./gradlew :app:test --tests AccountStatusBusTest --tests AccountStatusInterceptorTest --tests AccountRepositoryImplTest`.
- Cold-start smoke: build a debug APK with `-Pandroid.experimental.testOptions.androidTest.delayBeforeTestMs=0`, force-stop the app, launch from notification-tap deep link (intent fires from `Application.onCreate`) → verify no ANR, no deadlock in the OkHttp dispatcher thread (`adb shell ps -T`, look for the OkHttp Dispatcher thread state).
- Integration: existing `SyncControllerIntegrationTest` exercises the interceptor against the Firebase emulator → no behaviour change at this layer; tests should pass without modification.

## Risks

- **Bus backpressure**: `SUSPEND` overflow on a 16-buffer means a very rapid 17-event burst (e.g., a misbehaving test driver) suspends the interceptor's emit. In production the rate is bounded by HTTP response rate per second; 16 is generous. Drop to `DROP_OLDEST` if production telemetry shows interceptor stalls.
- **Repository constructed lazily**: the bus-collector starts only when the repository is first constructed. If construction is delayed past a network failure, the early event is lost. Mitigation: keep `replay = 1` instead of `0` so the first subscriber receives the most recent event. (Trade-off: stale events on app restart — acceptable for sign-out actions because re-sign-out is a no-op.)
- **Test parallelism**: tests that share an `AccountStatusBus` instance leak events across cases. Use a fresh `AccountStatusBus()` per test (the suggested test setup already does).

## Self-review checklist

- [x] Each step is one action.
- [x] Code blocks contain the full snippet to add/replace.
- [x] No "TBD" / "implement appropriate" placeholders.
- [x] Type names (`AccountStatusEvent.AccountUnavailable`, `AccountStatusEvent.Unauthenticated`) consistent across Tasks 1, 2, 3.
- [x] `@Named` qualifiers (`"appScope"`, `"appCoroutineContext"`) consistent between Task 2 (interceptor consumes) and Task 4 (module provides).
