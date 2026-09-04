import Foundation
import Observation

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

    private(set) var state: AccountState = .signedOut
    /// The signed-in Firebase identity, which is NOT `state.me`: `SplashRouter.outcome` needs
    /// `hasPasswordProvider`/`isEmailVerified` (spec §13), and neither is on the backend's account
    /// record. Set from the auth stream, so it is populated before `/me` has answered.
    private(set) var user: AuthUser?

    /// "" when signed out — the anon sentinel every store already defaults to, so nothing
    /// downstream needs an optional.
    var uid: String { state.me?.uid ?? "" }

    init(auth: any AuthClient, account: AccountClient, stores: [any UserScoped],
         status: AccountStatusCenter, sleep: @escaping @Sendable (Duration) async -> Void) {
        self.auth = auth
        self.account = account
        self.stores = stores
        self.status = status
        self.sleep = sleep
    }

    /// Observes `AuthClient.state`; on each change sets every store's `currentUserId` FIRST, then
    /// refreshes. The order is the whole point: a `/me` answer that landed before the stores were
    /// re-scoped would be rendered against the previous account's local rows.
    ///
    /// Runs until the stream finishes (`UnavailableAuthClient` — no plist — yields once and ends)
    /// or the calling task is cancelled.
    func start() async {
        for await authState in auth.state {
            switch authState {
            case .signedOut:
                user = nil
                scope(to: "")
                state = .signedOut
            case .signedIn(let signedIn):
                user = signedIn
                scope(to: signedIn.uid)
                await refresh()
            }
        }
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
                    if Task.isCancelled { return }
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
        guard state != .signedOut else { return }
        auth.signOut()
        user = nil
        scope(to: "")
        state = .signedOut
        // `.signedOut` is not a 403: it is posted so per-account holders can release state without
        // every one of them depending on the auth client (`AccountStatusCenter.swift`).
        status.post(.signedOut)
    }

    /// .blocked -> signOut; .deleted -> signOut (Task 18 adds the wipe here); .signedOut -> signOut.
    func handle(_ event: AccountStatusEvent) {
        switch event {
        case .blocked, .signedOut:
            // A block is REVERSIBLE, so the local library survives it exactly as an ordinary
            // sign-out does.
            signOut()
        case .deleted:
            signOut()
            // Task 18 adds the wipe here
        }
    }

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
