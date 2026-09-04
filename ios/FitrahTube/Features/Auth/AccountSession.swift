import Foundation
import Observation

/// Where "a device wipe is still owed for this uid" survives a process death (Stage 5 / C1.2).
/// The wipe is the only thing that can run it: the server has already revoked and deleted the
/// Firebase user, so no later `/me` can re-trigger `handleDeletion()` — without a durable marker
/// the cleanup is not deferred, it is unreachable forever.
@MainActor protocol DeletionMarking: AnyObject, Sendable {
    var pendingUid: String? { get set }
}

/// The production marker: one `UserDefaults` key, written before the detached cleanup starts and
/// cleared only once the wipe reported no error.
@MainActor final class UserDefaultsDeletionMarker: DeletionMarking {
    nonisolated static let defaultsKey = "com.albunyaan.tube.deletionPending"
    private let defaults: UserDefaults

    init(defaults: UserDefaults) { self.defaults = defaults }

    var pendingUid: String? {
        get { defaults.string(forKey: Self.defaultsKey) }
        set {
            if let newValue { defaults.set(newValue, forKey: Self.defaultsKey) }
            else { defaults.removeObject(forKey: Self.defaultsKey) }
        }
    }
}

/// The default, so the seven suites that never delete need no store at all — and so no test can
/// write a pending-deletion flag into `UserDefaults.standard`.
@MainActor final class InMemoryDeletionMarker: DeletionMarking {
    var pendingUid: String?
    init() {}
}

nonisolated enum AccountState: Sendable, Equatable {
    case signedOut, loading
    /// A lightweight (code, message) pair, never a raw response object — Android's comment
    /// (`AccountState.kt:16-27`) records a retained `ResponseBody` pinning a connection-pool slot
    /// for the life of a hot flow. iOS has no such pool, but code+message is what the UI needs.
    case failed(code: Int?, message: String)
    case loaded(AccountMe)
    /// Callers write `session.state.me?.isModerator` — there is no `.loaded?` shorthand in Swift.
    var me: AccountMe? { if case .loaded(let me) = self { me } else { nil } }
}

/// The ONE holder of account state, and the only thing that re-scopes the local stores. Everything
/// per-user in this app (favorites, saved playlists, subscriptions) keys off `currentUserId`, and
/// before this task nothing set it — every store sat on the `""` anon sentinel for the life of the
/// process, so signing in showed the guest library and signing out kept showing the account's.
@MainActor @Observable final class AccountSession {
    private let auth: any AuthClient
    private let account: AccountClient
    private let stores: [any UserScoped]
    private let status: AccountStatusCenter
    private let sleep: @Sendable (Duration) async -> Void
    /// Returns the FIRST error the wipe hit, or nil when everything went (Stage 5 / C2.2): the four
    /// SwiftData deletes used to be `try?`-swallowed, so a full or corrupt store left every row on
    /// disk while the app announced the account erased.
    private let wipe: @MainActor @Sendable () async -> Error?
    private let marker: any DeletionMarking
    /// The federated providers, asked to forget their OWN SDK sessions on every session drop
    /// (Stage 4 / I1). Empty is the honest default for a suite with no federated sign-in.
    private let providers: [any OAuthSignInProvider]

    private(set) var state: AccountState = .signedOut
    /// The signed-in Firebase identity, which is NOT `state.me`: `SplashRouter.outcome` needs
    /// `hasPasswordProvider`/`isEmailVerified` (spec §13), and neither is on the backend's account
    /// record. Set from the auth stream, so it is populated before `/me` has answered.
    private(set) var user: AuthUser?

    /// "" when signed out — the anon sentinel every store already defaults to, so nothing
    /// downstream needs an optional.
    var uid: String { state.me?.uid ?? "" }

    init(auth: any AuthClient, account: AccountClient, stores: [any UserScoped],
         status: AccountStatusCenter, sleep: @escaping @Sendable (Duration) async -> Void,
         wipe: @escaping @MainActor @Sendable () async -> Error?,
         marker: any DeletionMarking = InMemoryDeletionMarker(),
         providers: [any OAuthSignInProvider] = []) {
        self.auth = auth
        self.account = account
        self.stores = stores
        self.status = status
        self.sleep = sleep
        self.wipe = wipe
        self.marker = marker
        self.providers = providers
    }

    /// Observes `AuthClient.state`; on each change sets every store's `currentUserId` FIRST, then
    /// refreshes. The order is the whole point: a `/me` answer that landed before the stores were
    /// re-scoped would be rendered against the previous account's local rows.
    ///
    /// Runs until the stream finishes (`UnavailableAuthClient` — no plist — yields once and ends)
    /// or the calling task is cancelled.
    func start() async {
        await resumePendingDeletion()
        for await authState in auth.state {
            switch authState {
            case .signedOut:
                user = nil
                scope(to: "")
                state = .signedOut
            case .signedIn(let signedIn):
                user = signedIn
                // A new account on this device gets its own deletion latch: without this, a second
                // account deleted in the same process would find the first one's task and wipe
                // nothing (`handleDeletion`).
                deletion = nil
                scope(to: signedIn.uid)
                await refresh()
            }
        }
    }

    /// Stage 5 / C1.2 + C2.2: the wipe a previous launch owed this device. Idempotent — every step
    /// of `LocalAccountWiper.wipe()` is a delete — so re-running it costs nothing when it already
    /// ran, and it is the ONLY thing that can recover a cleanup interrupted by process death or
    /// refused by a full store. The marker survives a wipe that reported an error, so the next
    /// launch tries again.
    ///
    /// Runs BEFORE the auth stream so a marker left by a deletion never has a signed-in account
    /// racing it back onto the screen.
    func resumePendingDeletion() async {
        guard marker.pendingUid != nil else { return }
        if await wipe() == nil { marker.pendingUid = nil }
    }

    /// Fix round 1 / I2: the refresh currently running, handed to a second caller instead of a
    /// second request. `SignInViewModel.land()` refreshes on the same auth transition `start()` is
    /// about to refresh on; with no guard both wrote `state` and the last writer won, so a
    /// `.loaded` account could be overwritten by the loser's `.failed` and `RootView` would then
    /// read `status == nil` and route a pending-profile account to the shell.
    private var inFlight: Task<Void, Never>?

    /// `MAX_ATTEMPTS = 3`, linear backoff `1 s * attempt`; IOException retries, 4xx/5xx NEVER
    /// (`AccountRepositoryImpl.kt:111-147`). The splash calls it with `maxAttempts: 1`.
    ///
    /// Coalesced: the FIRST caller's `maxAttempts` is the budget that runs, and a second caller
    /// awaits that work rather than racing it. Ordering with `start()` is unchanged — `start()`
    /// calls `scope(to:)` and then `refresh()` with no suspension between them, so a `/me` answer
    /// can still never land before the stores are re-scoped
    /// (`aUidChangeScopesEveryStoreBeforeTheFirstRequest`).
    ///
    /// **A `.task`-scoped caller must not be the LEADER.** Only the first caller's cancellation
    /// reaches the shared work, and every follower — `start()` included — observes whatever state
    /// that cancellation left. Stage 3 / M6: the cancellation return now restores the pre-refresh
    /// state rather than parking the whole app at `.loading`, but a screen whose `.task` leads is
    /// still deciding for every other observer, so use an unstructured `Task {}` at those sites.
    func refresh(maxAttempts: Int = 3) async {
        // A follower must NOT cancel the shared work — only the caller that started it does, which
        // is what keeps `start()`'s cancellation (Task 9 / M4) reaching the retry loop.
        if let inFlight {
            await inFlight.value
            return
        }
        let task = Task { await self.fetch(maxAttempts: maxAttempts) }
        inFlight = task
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
        inFlight = nil
    }

    private func fetch(maxAttempts: Int) async {
        // Stage 3 / M6: what the cancellation return below restores. A cancelled LEADER used to
        // leave `.loading` standing forever — and every follower, `start()` included, returned
        // having observed it, so `RootView` rendered a signed-in user as a guest with nothing left
        // to re-drive `/me`.
        let previousState = state
        state = .loading
        for attempt in 1...max(1, maxAttempts) {
            do {
                state = .loaded(try await account.me())
                return
            } catch {
                switch error {
                // The transport already posts these (`AuthorizedTransport`'s 403 envelope check),
                // but a terminal account must drop its session even if that post is missed —
                // "Something went wrong" over a dead account is the wrong end state, not a banner.
                case .blocked: handle(.blocked)
                case .deletedAccount: handle(.deleted)
                // Fix round 1 / I1: BOUNDED. `BearerRetry` surfaces a bare 401 in THREE cases —
                // the cross-account identity change (Task 7), `token(true)` returning nil (the
                // ordinary expired/failed-refresh path), and a freshly refreshed token still being
                // rejected. Only the first is followed by an auth transition, so parking at
                // `.loading` and waiting for the stream hung the other two forever: signed-in user,
                // no `/me`, no banner, and `RootView` rendering them as a guest with no explanation.
                // So: re-drive inside the budget (a new token may be minted between sends), then
                // fall through to `.unknown(let status)` and fail. No backoff — a rejected token is
                // not a network stall, and waiting does not make it acceptable.
                case .unknown(status: 401) where attempt < maxAttempts: continue
                case .network where attempt < maxAttempts:
                    await sleep(.seconds(attempt))
                    // Fix round 1 / M4: `AccountClient.send` maps `CancellationError` to `.network`
                    // by design and the real sleep's `try?` swallows the cancellation, so a screen
                    // that went away mid-refresh used to burn all three attempts and land on "No
                    // internet connection" — a banner over a session nobody is watching.
                    if Task.isCancelled {
                        state = previousState
                        return
                    }
                    continue
                case .network: state = .failed(code: nil, message: String(localized: "auth_error_network"))
                case .unknown(let status): state = .failed(code: status, message: String(localized: "auth_error_generic"))
                default: state = .failed(code: nil, message: String(localized: "auth_error_generic"))
                }
                return
            }
        }
    }

    /// Task 11: the identity `AuthClient.reload()` just answered. Firebase's auth-state listener
    /// does NOT fire when a reload flips `isEmailVerified`, so nothing else can move `user` off the
    /// stale value — and `RootView`'s outcome reads exactly that field, which would park a
    /// just-verified account on the verification screen forever.
    ///
    /// A DIFFERENT uid is refused: swapping identity is `start()`'s job, because only it re-scopes
    /// every store first, and doing it here would render the new account against the old one's rows.
    func adopt(_ reloaded: AuthUser) {
        guard user?.uid == reloaded.uid else { return }
        user = reloaded
    }

    /// Task 17: the account record the server just answered a profile edit with
    /// (`AccountRepository.applyProfileUpdate`). Nothing re-reads `/me` after a `PUT`, so without
    /// this every reader of `state.me` — the Profile screen's own next visit included — keeps
    /// rendering the value the edit replaced, until some unrelated refresh happens to run.
    ///
    /// A DIFFERENT uid is refused, for `adopt(_:)`'s reason: swapping identity is `start()`'s job,
    /// because only it re-scopes every per-user store first.
    func apply(_ updated: AccountMe) {
        guard state.me?.uid == updated.uid else { return }
        state = .loaded(updated)
    }

    /// Sign-out ONLY: the local library is deliberately kept (`AccountRepositoryImpl.kt:44-49`).
    /// Re-scoping to `""` is what hides the account's rows behind the guest's.
    func signOut() {
        // Nothing to drop, nothing to announce — and this is what stops `RootView`'s
        // consume -> handle -> signOut path from looping on the `.signedOut` it posts below.
        guard dropSession() else { return }
        // `.signedOut` is not a 403: it is posted so per-account holders can release state without
        // every one of them depending on the auth client (`AccountStatusCenter.swift`).
        status.post(.signedOut)
    }

    /// The drop itself, without the announcement. `false` when there was no session to drop.
    /// Split out so the deletion path can announce `.deleted` UNCONDITIONALLY: Firebase's own
    /// `delete()` fires the auth listener, which may have set `.signedOut` here first, and a
    /// terminal alert that depends on which of the two got there first is a coin toss.
    @discardableResult
    private func dropSession() -> Bool {
        guard state != .signedOut else { return false }
        do {
            try auth.signOut()
        } catch {
            // Stage 5 / C1.3. `Auth.signOut()` calls `updateCurrentUser(nil, byForce: false,
            // savingToDisk: true)`, which assigns `_currentUser = nil` ONLY when the Keychain write
            // succeeded — so a throw means the session is still live and `idToken(forceRefresh:)`
            // still mints bearers for it. Publishing `.signedOut` over that is a lie the very next
            // request contradicts, and the next launch restores the account anyway. Stay signed in
            // and say something went wrong (WHAT, not WHY).
            state = .failed(code: nil, message: String(localized: "auth_error_generic"))
            return false
        }
        // Stage 4 / I1: BOTH paths land here — the user's own sign-out and `performDeletion`'s
        // drop — so one call site covers both, and the Google refresh token cannot outlive either.
        for provider in providers { provider.signOutProvider() }
        user = nil
        scope(to: "")
        state = .signedOut
        return true
    }

    /// .blocked -> signOut; .deleted -> the device wipe; .signedOut -> signOut.
    func handle(_ event: AccountStatusEvent) {
        switch event {
        case .blocked, .signedOut:
            // A block is REVERSIBLE, so the local library survives it exactly as an ordinary
            // sign-out does.
            signOut()
        case .deleted:
            handleDeletion()
        }
    }

    /// The account is gone. BOTH paths land here — the admin-side 403 `ACCOUNT_DELETED` envelope
    /// (`deletingFirebaseUser: false`; the account is already gone server-side and this user can no
    /// longer re-authenticate, so no Firebase delete is attempted) and the user's own successful
    /// `DELETE /api/account/me` (`true`).
    ///
    /// **DETACHED and uncancelled (CF-G-5).** Android runs this cleanup in `viewModelScope`, so a
    /// user who leaves the screen while the 204 is landing keeps every local row of an account that
    /// no longer exists. Nothing about this work belongs to a screen's lifetime.
    ///
    /// **Latched, so it runs exactly once.** Three arrivals are possible for one deletion: the
    /// transport's post, `fetch()`'s own `.deletedAccount` catch, and the `.deleted` this posts —
    /// each of which reaches `handle(_:)`. The latch is cleared on the next sign-in (`start()`), so
    /// a second account deleted in the same process still wipes.
    ///
    /// **Wipe BEFORE the sign-out**, and before Firebase: `dropSession()` re-scopes every per-user
    /// store, which re-READS it, and a re-read that lands before the rows are gone leaves deleted
    /// objects on screen; and a Firebase failure must not be able to skip a device the server has
    /// already erased.
    @discardableResult
    func handleDeletion(deletingFirebaseUser: Bool = false) -> Task<Void, Never> {
        if let deletion {
            // Fix round 1 / I2: the latch must not SWALLOW a later `true`. The 403 envelope can
            // arrive before the user's own 204 (a concurrent `/me` against an account the server
            // deletes mid-DELETE), and that arrival latches with `false` — so the credential this
            // path could still delete would outlive the account.
            //
            // Stage 3 / I1: it is a FLAG, never a chained task. The chain this replaces awaited
            // `deletion.value`, which only completes after `performDeletion` has run
            // `dropSession()` — so `FirebaseAuthClient.deleteUser()` went through `requireUser()`
            // against a nil `currentUser`, threw `.unknown`, and was swallowed by its own `try?`.
            // The credential the chain existed to delete was never deleted. `performDeletion`
            // re-reads this flag immediately before its own delete instead, which is BEFORE the
            // sign-out.
            deletingFirebase = deletingFirebase || deletingFirebaseUser
            return deletion
        }
        deletingFirebase = deletingFirebaseUser
        // Stage 5 / C1.2: durable BEFORE the detached task exists. `deleteAccountPermanently`
        // revokes and deletes the Firebase user, so on the next launch `/me` answers a bare 401 and
        // nothing can reach `handleDeletion()` again — an interrupted cleanup would be owed to this
        // device forever. `start()` redeems the marker.
        marker.pendingUid = user?.uid ?? state.me?.uid ?? ""
        // No `@MainActor in` on the closure: `performDeletion` carries the isolation and the hop.
        let task = Task.detached {
            await self.performDeletion()
        }
        deletion = task
        return task
    }

    private func performDeletion() async {
        let wipeError = await wipe()
        // `try?`: the server has already deleted the account, so there is nothing to roll back and
        // nowhere to route a failure to. A Firebase user whose `delete()` was refused
        // (`requiresRecentLogin`) is signed out below anyway, and its next `/me` answers the 403
        // envelope — the same terminal path, without a re-auth prompt for an account that no longer
        // exists. Stage 3 / I1: read HERE, so a `true` that arrived while the wipe was running
        // still deletes the credential, and always before `dropSession()`.
        if deletingFirebase { try? await auth.deleteUser() }
        dropSession()
        // Stage 5 / C2.2: a wipe that hit a full or corrupt store keeps the marker, so the next
        // launch tries again rather than leaving the rows on disk under an "account deleted" alert.
        if wipeError == nil { marker.pendingUid = nil }
        status.post(.deleted)
    }

    /// The deletion latch. Non-nil from the first `handleDeletion()` until the next sign-in.
    private var deletion: Task<Void, Never>?
    /// Whether the Firebase credential is already being deleted, so a second `true` (or a `true`
    /// that arrives after a `false` latched) never asks twice. Assigned on every fresh latch, so
    /// the next account in this process starts from its own answer.
    private var deletingFirebase = false

    #if DEBUG
    /// The screenshot rig's launch barrier (`FitrahTubeApp.awaitFakeAccountIfSignedIn`): yields
    /// until `/me` has landed, so the `-fitrah-seed-*` hooks write through per-user stores that
    /// `start()` has already re-scoped. Returns the yields spent, which is the only observable
    /// difference between stopping at the account and burning the bound.
    /// A `while`, not `for … where`: `where` SKIPS an iteration rather than ending the loop, so the
    /// shape this replaces spent the whole bound on every fixture launch. Waiting for `.loading` to
    /// end is not the same test and would be wrong — the initial state is `.signedOut`, so it would
    /// fall through before `start()` had run at all.
    @discardableResult
    func awaitAccount(bound: Int) async -> Int {
        var yields = 0
        while state.me == nil, yields < bound {
            yields += 1
            await Task.yield()
        }
        return yields
    }
    #endif

    /// Unchanged uid -> untouched store: every `currentUserId` write re-runs that store's fetch,
    /// and launch would otherwise fire three pointless SwiftData queries to set `""` to `""`.
    private func scope(to uid: String) {
        for store in stores where store.currentUserId != uid { store.currentUserId = uid }
    }
}
